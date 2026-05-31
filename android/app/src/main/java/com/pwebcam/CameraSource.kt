package com.pwebcam

interface CameraSource {
    /** Start capturing. [onFrame] is called on an arbitrary thread for every JPEG frame. */
    fun start(onFrame: (ByteArray) -> Unit)
    fun stop()
}
