package com.carlink.app.relay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.carlink.app.ui.HomeActivity
import java.security.SecureRandom

class RelayActivity : Activity() {
    private lateinit var instructions: TextView
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = RelayService.activeCode ?: savedInstanceState?.getString("token") ?: newToken()
        instructions = TextView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(instructions)
            addView(Button(context).apply {
                text = "Start screen relay"
                setOnClickListener {
                    if (android.os.Build.VERSION.SDK_INT >= 29 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
                    } else requestCapture()
                }
            })
            addView(Button(context).apply {
                text = "Open CarLink"
                setOnClickListener { startActivity(Intent(context, HomeActivity::class.java)) }
            })
            addView(Button(context).apply {
                text = "Stop relay"
                setOnClickListener {
                    stopService(Intent(context, RelayService::class.java))
                    token = newToken()
                    showInstructions()
                }
            })
        }
        setContentView(column)
        showInstructions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("token", token)
        super.onSaveInstanceState(outState)
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) requestCapture()
    }

    @Deprecated("Uses the platform screen capture consent result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        startForegroundService(Intent(this, RelayService::class.java)
            .putExtra("capture", data)
            .putExtra("token", token))
    }

    private fun showInstructions() {
        instructions.text = "Join this phone's hotspot on the iPad and enter this code in CarLink Display. " +
            "Rotate the S22 to landscape, select Share entire screen, connect the iPad, then open CarLink. " +
            "Stop the relay when done. Screen images travel unencrypted over your hotspot.\n\nCode: $token"
    }

    private fun newToken(): String =
        (SecureRandom().nextInt(9_000) + 1_000).toString()
}
