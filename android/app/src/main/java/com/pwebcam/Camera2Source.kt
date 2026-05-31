package com.pwebcam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

class Camera2Source(private val context: Context) : CameraSource {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Suppress("DEPRECATION")
    override fun start(onFrame: (ByteArray) -> Unit) {
        thread = HandlerThread("Camera2Thread").also { it.start() }
        handler = Handler(thread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val size = map.getOutputSizes(ImageFormat.JPEG)
            .filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: return

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    onFrame(bytes)
                } finally {
                    image.close()
                }
            }, handler)
        }

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    listOf(imageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(imageReader!!.surface)
                                set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
                                set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            session.setRepeatingRequest(request.build(), null, handler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                        }
                    },
                    handler
                )
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close(); cameraDevice = null
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close(); cameraDevice = null
            }
        }, handler)
    }

    override fun stop() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice = null
        imageReader?.close();    imageReader = null
        // quitSafely lets pending callbacks finish before the looper exits
        thread?.quitSafely()
        thread?.join(2000)  // wait up to 2 s so the next openCamera() doesn't race
        thread = null
        handler = null
    }

    companion object {
        private const val JPEG_QUALITY: Byte = 80
    }
}
