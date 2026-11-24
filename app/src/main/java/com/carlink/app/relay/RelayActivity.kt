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
        token = savedInstanceState?.getString("token") ?: newToken()
        instructions = TextView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(instructions)
            addView(Button(context).apply {
                text = "Start screen relay"
                setOnClickListener {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    startActivityForResult(manager.createScreenCaptureIntent(), 1)
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

    @Deprecated("Uses the platform screen capture consent result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        startForegroundService(Intent(this, RelayService::class.java)
            .putExtra("capture", data)
            .putExtra("token", token))
    }

    private fun showInstructions() {
        instructions.text = "Join this phone's hotspot on the iPad. Enter its Router address, port 8765, " +
            "and this code in the iPad app. Share the entire screen, then open CarLink. " +
            "Stop the relay when done. Screen images travel unencrypted over your hotspot.\n\nCode: $token"
    }

    private fun newToken(): String =
        (SecureRandom().nextInt(900_000_000) + 100_000_000).toString()
}
