package com.carlink.app.relay

import android.app.Activity
import android.content.Intent
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
                text = "Start headless relay"
                setOnClickListener {
                    startForegroundService(Intent(context, RelayService::class.java).putExtra("token", token))
                }
            })
            addView(Button(context).apply {
                text = "Open CarLink"
                setOnClickListener { startActivity(Intent(context, HomeActivity::class.java)) }
            })
            addView(Button(context).apply {
                text = "Darken S22 screen"
                setOnClickListener { showDarkScreen() }
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

    private fun showDarkScreen() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val controls = content.getChildAt(0) ?: return
        val previousBrightness = window.attributes.screenBrightness
        val insets = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        content.removeView(controls)
        val dark = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            contentDescription = "Restore relay controls"
            setOnClickListener {
                content.removeView(this)
                content.addView(controls)
                window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                insets.show(bars)
            }
        }
        content.addView(dark, android.view.ViewGroup.LayoutParams(-1, -1))
        window.attributes = window.attributes.apply { screenBrightness = 0f }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        insets.hide(bars)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("token", token)
        super.onSaveInstanceState(outState)
    }

    private fun showInstructions() {
        instructions.text = "Join this phone's hotspot on the iPad and enter this code in CarLink Display. " +
            "Connect the iPad before plugging in your Android Auto phone. " +
            "Stop the relay when done. Screen images travel unencrypted over your hotspot.\n\nCode: $token"
    }

    private fun newToken(): String =
        (SecureRandom().nextInt(9_000) + 1_000).toString()
}
