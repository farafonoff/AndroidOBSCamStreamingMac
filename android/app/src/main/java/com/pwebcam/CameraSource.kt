package com.pwebcam

interface CameraSource {
    fun start(onFrame: (ByteArray) -> Unit)
    fun stop()
    fun configure(ev: Int, focusAlias: String) {}
    fun setFlash(enabled: Boolean, brightness: Int = 1) {}
    fun supportsFlashBrightness(): Boolean = false
    fun maxFlashBrightness(): Int = 1
    fun apiName(): String = "Unknown"

    /** Live bandwidth controls: JPEG quality (1-100) and a frame-rate cap (1-30). */
    fun setQuality(jpegQuality: Int, maxFps: Int) {}
}

/** Drops frames above a target rate; call [allow] before encoding so skipped frames cost nothing. */
class FramePacer {
    @Volatile var maxFps = 30
    private var lastNs = 0L

    fun allow(): Boolean {
        if (maxFps >= 30) return true
        val now = System.nanoTime()
        // 2 ms slack so camera timestamp jitter doesn't skip every other frame.
        if (now - lastNs < 1_000_000_000L / maxFps - 2_000_000L) return false
        lastNs = now
        return true
    }
}
