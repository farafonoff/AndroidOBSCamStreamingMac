package com.pwebcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class CameraService : Service() {

    companion object {
        const val ACTION_STOP = "com.pwebcam.STOP"
        const val PORT_CAMERA1 = 8080
        const val PORT_CAMERA2 = 8081
        private const val CHANNEL_ID = "pwebcam_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile var isRunning = false
        @Volatile private var instance: CameraService? = null

        /** Adjust EV compensation by [delta] steps. Safe to call from any thread. */
        fun adjustEv(delta: Int) {
            val svc = instance ?: return
            svc.lastEv = (svc.lastEv + delta).coerceIn(-3, 3)
            svc.saveSettings()
            svc.cameraExecutor.execute {
                (svc.activeSource as? Camera1Source)?.configure(svc.lastEv, svc.lastFocus)
            }
            if (svc.activeSource != null) svc.updateNotification(svc.streamingText())
        }

        fun currentEv(): Int = instance?.lastEv ?: 0

        fun setFocus(alias: String) {
            val svc = instance ?: return
            svc.lastFocus = alias
            svc.saveSettings()
            svc.cameraExecutor.execute {
                (svc.activeSource as? Camera1Source)?.configure(svc.lastEv, svc.lastFocus)
            }
            if (svc.activeSource != null) svc.updateNotification(svc.streamingText())
        }

        fun currentFocus(): String = instance?.lastFocus ?: "picture"
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

    private val prefs by lazy { getSharedPreferences("pwebcam", Context.MODE_PRIVATE) }

    private fun loadSettings() {
        lastEv    = prefs.getInt("ev", 0)
        lastFocus = prefs.getString("focus", "picture") ?: "picture"
    }

    private fun saveSettings() {
        prefs.edit().putInt("ev", lastEv).putString("focus", lastFocus).apply()
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
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for connection…"))

        wireServer(server1) { Camera1Source(ev = lastEv, focusAlias = lastFocus) }
        wireServer(server2) { Camera2Source(this) }
        try { server1.start() } catch (e: Exception) { e.printStackTrace() }
        try { server2.start() } catch (e: Exception) { e.printStackTrace() }
        return START_STICKY
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
            // Apply to running camera immediately (if already active)
            cameraExecutor.execute {
                (activeSource as? Camera1Source)?.configure(lastEv, lastFocus)
            }
        }
        server.clientListener = object : MjpegServer.ClientListener {
            override fun onFirstClientConnected() {
                cameraExecutor.execute {
                    activeSource?.stop()
                    activeSource = try {
                        factory().also { it.start { frame -> server.pushFrame(frame) } }
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
        return "Streaming · EV $ev · focus: $lastFocus"
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
