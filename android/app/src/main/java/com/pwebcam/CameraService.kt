package com.pwebcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors

class CameraService : Service() {

    companion object {
        const val ACTION_STOP = "com.pwebcam.STOP"
        const val PORT_CAMERA1 = 8080
        const val PORT_CAMERA2 = 8081
        private const val CHANNEL_ID = "pwebcam_channel"
        private const val NOTIFICATION_ID = 1

        /** Start on boot; on by default. Read straight from prefs — the service may not be running. */
        fun autostartEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("pwebcam", Context.MODE_PRIVATE).getBoolean("autostart", true)

        fun setAutostart(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences("pwebcam", Context.MODE_PRIVATE)
                .edit().putBoolean("autostart", enabled).apply()
        }

        @Volatile var isRunning = false
        @Volatile private var instance: CameraService? = null

        /** Adjust EV compensation by [delta] steps. Safe to call from any thread. */
        fun adjustEv(delta: Int) {
            val svc = instance ?: return
            svc.lastEv = (svc.lastEv + delta).coerceIn(-3, 3)
            svc.saveSettings()
            svc.cameraExecutor.execute {
                svc.activeSource?.configure(svc.lastEv, svc.lastFocus)
            }
            if (svc.activeSource != null) svc.updateNotification(svc.streamingText())
        }

        fun currentEv(): Int = instance?.lastEv ?: 0

        fun setFocus(alias: String) {
            val svc = instance ?: return
            svc.lastFocus = alias
            svc.saveSettings()
            svc.cameraExecutor.execute {
                svc.activeSource?.configure(svc.lastEv, svc.lastFocus)
            }
            if (svc.activeSource != null) svc.updateNotification(svc.streamingText())
        }

        fun currentFocus(): String = instance?.lastFocus ?: "picture"

        fun setFlash(enabled: Boolean) {
            val svc = instance ?: return
            svc.lastFlash = enabled
            svc.saveSettings()
            val brightness = svc.lastFlashBrightness
            svc.cameraExecutor.execute {
                svc.activeSource?.setFlash(enabled, brightness)
            }
            if (svc.activeSource != null) svc.updateNotification(svc.streamingText())
        }

        fun setFlashBrightness(brightness: Int) {
            val svc = instance ?: return
            svc.lastFlashBrightness = brightness
            svc.saveSettings()
            val enabled = svc.lastFlash
            svc.cameraExecutor.execute {
                svc.activeSource?.setFlash(enabled, brightness)
            }
        }

        fun currentFlash(): Boolean = instance?.lastFlash ?: false
        fun currentFlashBrightness(): Int = instance?.lastFlashBrightness ?: 1
        fun maxFlashBrightness(): Int = instance?.activeSource?.maxFlashBrightness() ?: 1
        fun supportsFlashBrightness(): Boolean = instance?.activeSource?.supportsFlashBrightness() ?: false

        fun setCam2HardwareJpeg(enabled: Boolean) {
            val svc = instance ?: return
            svc.lastCam2HardwareJpeg = enabled
            svc.saveSettings()
            // Restart Camera2 source with new setting if currently active
            svc.cameraExecutor.execute {
                if (svc.activeSource is Camera2Source) {
                    svc.activeSource?.stop()
                    svc.activeSource = try {
                        Camera2Source(
                            svc, svc.lastFlash, svc.lastFlashBrightness, enabled
                        ).also {
                            it.setQuality(svc.lastQuality, svc.lastMaxFps)
                            it.start { frame -> svc.server2.pushFrame(frame) }
                        }
                    } catch (e: Exception) { e.printStackTrace(); null }
                }
            }
        }

        /** Live JPEG quality (10-100) and frame-rate cap (1-30); both persisted on the phone. */
        fun setQuality(quality: Int, maxFps: Int) {
            val svc = instance ?: return
            svc.lastQuality = quality.coerceIn(10, 100)
            svc.lastMaxFps = maxFps.coerceIn(1, 30)
            svc.saveSettings()
            val q = svc.lastQuality
            val f = svc.lastMaxFps
            svc.cameraExecutor.execute { svc.activeSource?.setQuality(q, f) }
        }

        fun currentCam2HardwareJpeg(): Boolean = instance?.lastCam2HardwareJpeg ?: true
        fun isCamera2Active(): Boolean = instance?.activeSource is Camera2Source
        fun activeApiName(): String = instance?.activeSource?.apiName() ?: "—"
    }

    private val server1 = MjpegServer(PORT_CAMERA1)
    private val server2 = MjpegServer(PORT_CAMERA2)

    // Single-threaded: open/close calls are always serialized.
    // Both servers share one camera — only one source is active at a time.
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var activeSource: CameraSource? = null

    // Camera settings — persisted in SharedPreferences, owned by the phone.
    @Volatile private var lastEv: Int = 0
    @Volatile private var lastFocus: String = "picture"
    @Volatile private var lastFlash: Boolean = false
    @Volatile private var lastFlashBrightness: Int = 1
    @Volatile private var lastCam2HardwareJpeg: Boolean = true
    @Volatile private var lastQuality: Int = 80
    @Volatile private var lastMaxFps: Int = 30

    // mDNS/DNS-SD advertisement so the Mac receiver can find the phone over Wi-Fi.
    // Keeps the Wi-Fi radio out of power-save while serving; saves latency/jitter on old phones.
    private var wifiLock: WifiManager.WifiLock? = null

    private val nsdListeners = mutableListOf<NsdManager.RegistrationListener>()

    private val prefs by lazy { getSharedPreferences("pwebcam", Context.MODE_PRIVATE) }

    private fun loadSettings() {
        lastEv             = prefs.getInt("ev", 0)
        lastFocus          = prefs.getString("focus", "picture") ?: "picture"
        lastFlash            = prefs.getBoolean("flash", false)
        lastFlashBrightness  = prefs.getInt("flash_brightness", 1)
        lastCam2HardwareJpeg = prefs.getBoolean("cam2_hw_jpeg", true)
        lastQuality          = prefs.getInt("quality", 80)
        lastMaxFps           = prefs.getInt("max_fps", 30)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("ev", lastEv)
            .putString("focus", lastFocus)
            .putBoolean("flash", lastFlash)
            .putInt("flash_brightness", lastFlashBrightness)
            .putBoolean("cam2_hw_jpeg", lastCam2HardwareJpeg)
            .putInt("quality", lastQuality)
            .putInt("max_fps", lastMaxFps)
            .apply()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        loadSettings()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Waiting for connection…"))
        } catch (e: Exception) {
            // Camera-type foreground services can't start from the background on newer
            // Android (e.g. a boot start). Bail out; the user opens the app instead.
            e.printStackTrace()
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        wireHttp(server1)
        wireHttp(server2)
        wireServer(server1) { Camera1Source(ev = lastEv, focusAlias = lastFocus, flashEnabled = lastFlash) }
        wireServer(server2) { Camera2Source(this, lastFlash, lastFlashBrightness, lastCam2HardwareJpeg, lastEv, lastFocus) }
        try { server1.start() } catch (e: Exception) { e.printStackTrace() }
        try { server2.start() } catch (e: Exception) { e.printStackTrace() }
        registerNsd()
        acquireWifiLock()
        return START_STICKY
    }

    /**
     * Advertise on the LAN: `_pwebcam._tcp` (what the Mac receiver browses for; Camera1 port,
     * Camera2 port as a TXT attribute) and `_http._tcp` (so the web UI shows up in Bonjour browsers).
     */
    private fun registerNsd() {
        if (nsdListeners.isNotEmpty()) return
        register("_pwebcam._tcp.", "pwebcam-${Build.MODEL}") {
            setAttribute("cam1", PORT_CAMERA1.toString())
            setAttribute("cam2", PORT_CAMERA2.toString())
        }
        register("_http._tcp.", "pwebcam (${Build.MODEL})") { setAttribute("path", "/ui") }
    }

    private fun register(type: String, name: String, attrs: NsdServiceInfo.() -> Unit) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            port = PORT_CAMERA1
            attrs()
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { nsdListeners.remove(this) }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try {
            getSystemService(NsdManager::class.java)
                .registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListeners.add(listener)
        } catch (e: Exception) { e.printStackTrace() }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "pwebcam").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun releaseWifiLock() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun unregisterNsd() {
        val nsd = getSystemService(NsdManager::class.java)
        nsdListeners.toList().forEach { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        nsdListeners.clear()
    }

    // ── Web UI / JSON API ────────────────────────────────────────────────────

    private fun wireHttp(server: MjpegServer) {
        server.httpHandler = { path, params ->
            when (path) {
                "/ui" -> MjpegServer.HttpReply(assets.open("ui.html").readBytes(), "text/html; charset=utf-8")
                "/api/state" -> jsonReply(server)
                "/api/set" -> { applyWebSettings(params); jsonReply(server) }
                else -> null
            }
        }
    }

    private fun applyWebSettings(p: Map<String, String>) {
        p["evDelta"]?.toIntOrNull()?.let { adjustEv(it.coerceIn(-1, 1)) }
        p["focus"]?.takeIf { it in setOf("picture", "video", "fixed", "infinity") }?.let { setFocus(it) }
        p["flash"]?.let { setFlash(it == "1") }
        p["flashBrightness"]?.toIntOrNull()?.let { setFlashBrightness(it) }
        p["hwJpeg"]?.let { setCam2HardwareJpeg(it == "1") }
        if ("quality" in p || "maxFps" in p) {
            setQuality(p["quality"]?.toIntOrNull() ?: lastQuality, p["maxFps"]?.toIntOrNull() ?: lastMaxFps)
        }
    }

    private fun jsonReply(server: MjpegServer): MjpegServer.HttpReply {
        val src = activeSource
        val json = JSONObject()
            .put("model", Build.MODEL)
            .put("thisApi", if (server === server2) "Camera2" else "Camera1")
            .put("otherPort", if (server === server2) PORT_CAMERA1 else PORT_CAMERA2)
            .put("activeApi", src?.apiName() ?: "—")
            .put("streaming", src != null)
            .put("clients", server.clientCount)
            .put("frames", server.framesPushed.get())
            .put("bytes", server.bytesPushed.get())
            .put("quality", lastQuality)
            .put("maxFps", lastMaxFps)
            .put("ev", lastEv)
            .put("focus", lastFocus)
            .put("flash", lastFlash)
            .put("flashBrightness", lastFlashBrightness)
            .put("maxFlashBrightness", src?.maxFlashBrightness() ?: 1)
            .put("supportsFlashBrightness", src?.supportsFlashBrightness() ?: false)
            .put("hwJpeg", lastCam2HardwareJpeg)
        return MjpegServer.HttpReply(json.toString().toByteArray(), "application/json")
    }

    /**
     * Attach a ClientListener to [server] that starts the source produced by
     * [factory] when the first client connects, and stops it when the last one leaves.
     *
     * Connecting to server2 (Camera2) while server1 (Camera1) is active will
     * automatically stop Camera1 and start Camera2, and vice-versa.
     */
    private fun wireServer(server: MjpegServer, factory: () -> CameraSource) {
        server.onSettings = { params ->
            params["ev"]?.toIntOrNull()?.also { lastEv = it }
            params["focus"]?.also { lastFocus = it }
            cameraExecutor.execute {
                activeSource?.configure(lastEv, lastFocus)
            }
        }
        server.clientListener = object : MjpegServer.ClientListener {
            override fun onFirstClientConnected() {
                cameraExecutor.execute {
                    activeSource?.stop()
                    activeSource = try {
                        factory().also {
                            it.setQuality(lastQuality, lastMaxFps)
                            it.start { frame -> server.pushFrame(frame) }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
                updateNotification(streamingText())
            }

            override fun onLastClientDisconnected() {
                cameraExecutor.execute {
                    activeSource?.stop()
                    activeSource = null
                }
                updateNotification("Waiting for connection…")
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        unregisterNsd()
        releaseWifiLock()
        server1.clientListener = null
        server2.clientListener = null
        server1.stop()
        server2.stop()
        cameraExecutor.execute { activeSource?.stop(); activeSource = null }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "pwebcam stream", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun streamingText(): String {
        val ev = if (lastEv >= 0) "+$lastEv" else "$lastEv"
        val flash = if (lastFlash) " · flash on" else ""
        return "Streaming · EV $ev · focus: $lastFocus$flash"
    }

    private fun buildNotification(text: String): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags
        )
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, CameraService::class.java).apply { action = ACTION_STOP },
            pendingFlags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("pwebcam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopAction)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
