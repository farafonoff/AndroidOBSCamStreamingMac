package com.pwebcam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream

class Camera2Source(
    private val context: Context,
    private val flashEnabled: Boolean = false,
    private val flashBrightness: Int = 1,
    private val useHardwareJpeg: Boolean = true,
    ev: Int = 0,
    focusAlias: String = "picture",
) : CameraSource {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var maxTorchStrength: Int = 1
    private var aeCompRange: android.util.Range<Int> = android.util.Range(0, 0)
    private var currentFlashEnabled: Boolean = flashEnabled
    private var currentFlashBrightness: Int = flashBrightness
    private var currentEv: Int = ev
    private var currentFocusAlias: String = focusAlias

    override fun apiName(): String =
        if (useHardwareJpeg) "Camera2 (HW JPEG)" else "Camera2 (SW JPEG)"

    override fun configure(ev: Int, focusAlias: String) {
        currentEv = ev
        currentFocusAlias = focusAlias
        applyRepeatingRequest()
    }

    override fun supportsFlashBrightness(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxTorchStrength > 1

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("UNCHECKED_CAST")
    private fun queryMaxTorchStrength(manager: CameraManager, cameraId: String): Int {
        // Use string key to avoid compile-time reference to API 33 constant
        val key = CameraCharacteristics.Key("android.flash.torchStrengthMaxLevel", Int::class.java)
        return manager.getCameraCharacteristics(cameraId).get(key) ?: 1
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyFlashStrength(builder: CaptureRequest.Builder, level: Int) {
        val key = CaptureRequest.Key("android.flash.flashStrengthLevel", Int::class.java)
        builder.set(key, level.coerceIn(1, maxTorchStrength))
    }

    override fun maxFlashBrightness(): Int = maxTorchStrength

    @Suppress("DEPRECATION")
    override fun start(onFrame: (ByteArray) -> Unit) {
        thread = HandlerThread("Camera2Thread").also { it.start() }
        handler = Handler(thread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            maxTorchStrength = queryMaxTorchStrength(manager, cameraId)
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        aeCompRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: android.util.Range(0, 0)

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val format = if (useHardwareJpeg) ImageFormat.JPEG else ImageFormat.YUV_420_888
        val size = map.getOutputSizes(format)
            .filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: return

        imageReader = ImageReader.newInstance(size.width, size.height, format, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val bytes = if (useHardwareJpeg) imageToJpegDirect(image)
                                else                 imageYuvToJpeg(image)
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
                            applyRepeatingRequest()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                        }
                    },
                    handler
                )
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
        }, handler)
    }

    override fun setFlash(enabled: Boolean, brightness: Int) {
        currentFlashEnabled = enabled
        currentFlashBrightness = brightness.coerceIn(1, maxTorchStrength)
        applyRepeatingRequest()
    }

    private fun applyRepeatingRequest() {
        val session = captureSession ?: return
        val device  = cameraDevice  ?: return
        val reader  = imageReader   ?: return

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)

            // Focus
            val afMode = when (currentFocusAlias) {
                "video"    -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                "fixed",
                "infinity" -> CaptureRequest.CONTROL_AF_MODE_OFF
                else       -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            set(CaptureRequest.CONTROL_AF_MODE, afMode)

            // EV compensation — clamp to device-reported range
            if (aeCompRange.upper > 0 || aeCompRange.lower < 0) {
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    currentEv.coerceIn(aeCompRange.lower, aeCompRange.upper))
            }

            if (useHardwareJpeg) {
                set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
            }
            if (currentFlashEnabled) {
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxTorchStrength > 1) {
                    applyFlashStrength(this, currentFlashBrightness)
                }
            } else {
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        }
        try { session.setRepeatingRequest(request.build(), null, handler) } catch (_: Exception) {}
    }

    override fun stop() {
        currentFlashEnabled = false
        try { applyRepeatingRequest() } catch (_: Exception) {}
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice = null
        imageReader?.close();    imageReader = null
        thread?.quitSafely()
        thread?.join(2000)
        thread = null
        handler = null
    }

    // ── JPEG encoding helpers ─────────────────────────────────────────────────

    private fun imageToJpegDirect(image: Image): ByteArray {
        val buf = image.planes[0].buffer
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    private fun imageYuvToJpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h + w * h / 2)

        // Y plane
        val yPlane = image.planes[0]
        val yBuf   = yPlane.buffer
        val yStride = yPlane.rowStride
        if (yStride == w) {
            yBuf.get(nv21, 0, w * h)
        } else {
            for (row in 0 until h) {
                yBuf.position(row * yStride)
                yBuf.get(nv21, row * w, w)
            }
        }

        // Interleave V, U for NV21
        val vBuf    = image.planes[2].buffer
        val uBuf    = image.planes[1].buffer
        val vStride = image.planes[2].rowStride
        val uStride = image.planes[1].rowStride
        val vPixel  = image.planes[2].pixelStride
        val uPixel  = image.planes[1].pixelStride
        var offset  = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[offset++] = vBuf.get(row * vStride + col * vPixel)
                nv21[offset++] = uBuf.get(row * uStride + col * uPixel)
            }
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream(nv21.size / 4)
        yuv.compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY.toInt(), out)
        return out.toByteArray()
    }

    companion object {
        private const val JPEG_QUALITY: Byte = 80
    }
}
