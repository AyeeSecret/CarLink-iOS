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

    private fun showInstructions() {
        instructions.text = "Join this phone's hotspot on the iPad and enter this code in CarLink Display. " +
            "Connect the iPad before plugging in your Android Auto phone. " +
            "Stop the relay when done. Screen images travel unencrypted over your hotspot.\n\nCode: $token"
    }

    private fun newToken(): String =
        (SecureRandom().nextInt(9_000) + 1_000).toString()
}
