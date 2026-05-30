package com.pwebcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class CameraService : Service() {

    companion object {
        const val ACTION_STOP = "com.pwebcam.STOP"
        private const val CHANNEL_ID = "pwebcam_channel"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 8080
        private const val JPEG_QUALITY = 80

        /** True while the service is alive. MainActivity reads this to sync its UI. */
        @Volatile var isRunning = false
    }

    private var camera: Camera? = null
    private var surfaceTexture: SurfaceTexture? = null
    private val server = MjpegServer(PORT)
    // Single-threaded so open/close calls are always serialized
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for connection…"))

        server.clientListener = object : MjpegServer.ClientListener {
            override fun onFirstClientConnected() {
                cameraExecutor.execute { openCamera() }
                updateNotification("Streaming on :$PORT")
            }
            override fun onLastClientDisconnected() {
                cameraExecutor.execute { closeCamera() }
                updateNotification("Waiting for connection…")
            }
        }
        try { server.start() } catch (e: Exception) { e.printStackTrace() }
        return START_STICKY
    }

    private fun openCamera() {
        if (camera != null) return
        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
            camera!!.apply {
                surfaceTexture = SurfaceTexture(1)
                setPreviewTexture(surfaceTexture)

                parameters = parameters.apply {
                    val best = supportedPreviewSizes
                        .filter { it.width <= 1280 && it.height <= 720 }
                        .maxByOrNull { it.width * it.height }
                        ?: supportedPreviewSizes[0]
                    setPreviewSize(best.width, best.height)

                    supportedPreviewFpsRange
                        .filter { it[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] >= 30_000 }
                        .minByOrNull { it[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] }
                        ?.also { setPreviewFpsRange(it[0], it[1]) }

                    previewFormat = ImageFormat.NV21
                    focusMode = if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE else focusMode
                }

                setPreviewCallback { data, cam ->
                    val sz = cam.parameters.previewSize
                    val yuv = YuvImage(data, ImageFormat.NV21, sz.width, sz.height, null)
                    val out = ByteArrayOutputStream(data.size / 4)
                    yuv.compressToJpeg(Rect(0, 0, sz.width, sz.height), JPEG_QUALITY, out)
                    server.pushFrame(out.toByteArray())
                }

                startPreview()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        camera?.apply {
            setPreviewCallback(null)
            stopPreview()
            release()
        }
        camera = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    override fun onDestroy() {
        isRunning = false
        server.stop()
        server.clientListener = null
        cameraExecutor.execute { closeCamera() }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "pwebcam stream", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CameraService::class.java).apply { action = ACTION_STOP },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("pwebcam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

}
