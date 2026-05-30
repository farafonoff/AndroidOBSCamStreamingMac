package com.pwebcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btn_toggle)
        tvStatus = findViewById(R.id.tv_status)

        btnToggle.setOnClickListener {
            if (CameraService.isRunning) stopStream() else requestCameraPermissionAndStart()
        }

        // Auto-start: if service already running just sync UI, otherwise kick it off.
        if (CameraService.isRunning) {
            syncUi()
        } else {
            requestCameraPermissionAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        syncUi()
    }

    private fun syncUi() {
        val active = CameraService.isRunning
        btnToggle.text = getString(if (active) R.string.stop_streaming else R.string.start_streaming)
        tvStatus.text = getString(if (active) R.string.status_streaming else R.string.status_stopped)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        syncUi()
    }

    private fun stopStream() {
        startService(Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        })
        syncUi()
    }

    companion object {
        private const val REQ_CAMERA = 1
    }
}
