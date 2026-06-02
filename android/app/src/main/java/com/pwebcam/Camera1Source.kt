package com.pwebcam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import java.io.ByteArrayOutputStream

@Suppress("DEPRECATION")
class Camera1Source(
    private val ev: Int = 0,
    private val focusAlias: String = "picture",
    private val flashEnabled: Boolean = false,
) : CameraSource {

    private var camera: Camera? = null
    private var surfaceTexture: SurfaceTexture? = null

    override fun start(onFrame: (ByteArray) -> Unit) {
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

                val evClamped = ev.coerceIn(minExposureCompensation, maxExposureCompensation)
                setExposureCompensation(evClamped)

                val mode = focusAlias.toCamera1FocusMode()
                if (supportedFocusModes.contains(mode)) focusMode = mode

                flashMode = if (flashEnabled && supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH) == true)
                    Camera.Parameters.FLASH_MODE_TORCH
                else
                    Camera.Parameters.FLASH_MODE_OFF
            }

            setPreviewCallback { data, cam ->
                val sz = cam.parameters.previewSize
                val yuv = YuvImage(data, ImageFormat.NV21, sz.width, sz.height, null)
                val out = ByteArrayOutputStream(data.size / 4)
                yuv.compressToJpeg(Rect(0, 0, sz.width, sz.height), JPEG_QUALITY, out)
                onFrame(out.toByteArray())
            }

            startPreview()
        }
    }

    override fun apiName(): String = "Camera1"

    override fun configure(newEv: Int, newFocusAlias: String) {
        camera?.parameters = camera?.parameters?.apply {
            val evClamped = newEv.coerceIn(minExposureCompensation, maxExposureCompensation)
            setExposureCompensation(evClamped)
            val mode = newFocusAlias.toCamera1FocusMode()
            if (supportedFocusModes.contains(mode)) focusMode = mode
        }
    }

    override fun setFlash(enabled: Boolean, brightness: Int) {
        camera?.parameters = camera?.parameters?.apply {
            flashMode = if (enabled && supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH) == true)
                Camera.Parameters.FLASH_MODE_TORCH
            else
                Camera.Parameters.FLASH_MODE_OFF
        }
    }

    override fun stop() {
        // Always extinguish torch before releasing so it doesn't stay on
        try { setFlash(false) } catch (_: Exception) {}
        camera?.apply {
            setPreviewCallback(null)
            stopPreview()
            release()
        }
        camera = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    companion object {
        private const val JPEG_QUALITY = 80

        private fun String.toCamera1FocusMode(): String = when (this) {
            "picture"  -> Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            "video"    -> Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            "fixed"    -> Camera.Parameters.FOCUS_MODE_FIXED
            "infinity" -> Camera.Parameters.FOCUS_MODE_INFINITY
            else       -> this
        }
    }
}
