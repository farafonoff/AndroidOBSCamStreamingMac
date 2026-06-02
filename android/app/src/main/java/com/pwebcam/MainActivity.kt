package com.pwebcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var layoutControls: LinearLayout
    private lateinit var tvEv: TextView
    private lateinit var btnEvUp: Button
    private lateinit var btnEvDown: Button
    private lateinit var btnFocusPicture: Button
    private lateinit var btnFocusVideo: Button
    private lateinit var btnFocusFixed: Button
    private lateinit var btnFocusInfinity: Button
    private lateinit var swFlash: Switch
    private lateinit var seekFlashBrightness: SeekBar
    private lateinit var layoutCam2Jpeg: LinearLayout
    private lateinit var swCam2HwJpeg: Switch

    private val focusButtons get() = listOf(btnFocusPicture, btnFocusVideo, btnFocusFixed, btnFocusInfinity)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle        = findViewById(R.id.btn_toggle)
        tvStatus         = findViewById(R.id.tv_status)
        layoutControls   = findViewById(R.id.layout_controls)
        tvEv             = findViewById(R.id.tv_ev)
        btnEvUp          = findViewById(R.id.btn_ev_up)
        btnEvDown        = findViewById(R.id.btn_ev_down)
        btnFocusPicture  = findViewById(R.id.btn_focus_picture)
        btnFocusVideo    = findViewById(R.id.btn_focus_video)
        btnFocusFixed    = findViewById(R.id.btn_focus_fixed)
        btnFocusInfinity = findViewById(R.id.btn_focus_infinity)
        swFlash               = findViewById(R.id.sw_flash)
        seekFlashBrightness   = findViewById(R.id.seek_flash_brightness)
        layoutCam2Jpeg        = findViewById(R.id.layout_cam2_jpeg)
        swCam2HwJpeg          = findViewById(R.id.sw_cam2_hw_jpeg)

        btnToggle.setOnClickListener {
            if (CameraService.isRunning) stopStream() else requestCameraPermissionAndStart()
        }

        btnEvUp.setOnClickListener   { CameraService.adjustEv(+1); syncUi() }
        btnEvDown.setOnClickListener { CameraService.adjustEv(-1); syncUi() }

        btnFocusPicture.setOnClickListener  { CameraService.setFocus("picture");  syncUi() }
        btnFocusVideo.setOnClickListener    { CameraService.setFocus("video");    syncUi() }
        btnFocusFixed.setOnClickListener    { CameraService.setFocus("fixed");    syncUi() }
        btnFocusInfinity.setOnClickListener { CameraService.setFocus("infinity"); syncUi() }

        swFlash.setOnCheckedChangeListener { _, checked ->
            CameraService.setFlash(checked)
            syncUi()
        }

        if (CameraService.isRunning) syncUi() else requestCameraPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        syncUi()
    }

    private fun syncUi() {
        val active = CameraService.isRunning
        btnToggle.text = getString(if (active) R.string.stop_streaming else R.string.start_streaming)
        tvStatus.text = if (active)
            "${getString(R.string.status_streaming)}\n${CameraService.activeApiName()}"
        else
            getString(R.string.status_stopped)
        layoutControls.visibility = if (active) View.VISIBLE else View.GONE

        if (active) {
            val ev = CameraService.currentEv()
            tvEv.text = "EV ${if (ev >= 0) "+$ev" else "$ev"}"

            val activeFocus = CameraService.currentFocus()
            val activeBtn = when (activeFocus) {
                "video"    -> btnFocusVideo
                "fixed"    -> btnFocusFixed
                "infinity" -> btnFocusInfinity
                else       -> btnFocusPicture
            }
            focusButtons.forEach { btn ->
                btn.setTypeface(null, if (btn == activeBtn) Typeface.BOLD else Typeface.NORMAL)
                btn.alpha = if (btn == activeBtn) 1.0f else 0.5f
            }

            // Update switch without triggering the listener
            swFlash.setOnCheckedChangeListener(null)
            swFlash.isChecked = CameraService.currentFlash()
            swFlash.setOnCheckedChangeListener { _, checked ->
                CameraService.setFlash(checked)
                syncUi()
            }

            // Camera2 hardware JPEG toggle — visible only when Camera2 port is active
            val cam2Active = CameraService.isCamera2Active()
            layoutCam2Jpeg.visibility = if (cam2Active) View.VISIBLE else View.GONE
            if (cam2Active) {
                swCam2HwJpeg.setOnCheckedChangeListener(null)
                swCam2HwJpeg.isChecked = CameraService.currentCam2HardwareJpeg()
                swCam2HwJpeg.setOnCheckedChangeListener { _, checked ->
                    CameraService.setCam2HardwareJpeg(checked)
                }
            }

            // Brightness slider — only visible when device supports torch strength
            val supportsFlashBrightness = CameraService.supportsFlashBrightness()
            val flashOn = CameraService.currentFlash()
            seekFlashBrightness.visibility =
                if (supportsFlashBrightness && flashOn) View.VISIBLE else View.GONE
            if (supportsFlashBrightness) {
                seekFlashBrightness.max = CameraService.maxFlashBrightness() - 1
                seekFlashBrightness.progress = CameraService.currentFlashBrightness() - 1
                seekFlashBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) CameraService.setFlashBrightness(progress + 1)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
        }
    }

    private fun requestCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startStream()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startStream()
        } else {
            tvStatus.text = "Camera permission denied"
        }
    }

    private fun startStream() {
        val intent = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        syncUi()
    }

    private fun stopStream() {
        startService(Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_STOP })
        syncUi()
    }

    companion object {
        private const val REQ_CAMERA = 1
    }
}
