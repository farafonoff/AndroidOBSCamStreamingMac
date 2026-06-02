package com.pwebcam

interface CameraSource {
    fun start(onFrame: (ByteArray) -> Unit)
    fun stop()
    fun configure(ev: Int, focusAlias: String) {}
    fun setFlash(enabled: Boolean, brightness: Int = 1) {}
    fun supportsFlashBrightness(): Boolean = false
    fun maxFlashBrightness(): Int = 1
    fun apiName(): String = "Unknown"
}
