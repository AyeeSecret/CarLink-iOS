package com.carlink.app.relay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.carlink.app.ui.HomeActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class RelayActivity : Activity() {
    private lateinit var instructions: TextView
    private lateinit var approve: Button
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            instructions.text = intent.getStringExtra("status")
            approve.isEnabled = intent.getBooleanExtra("pending", false)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, updates, IntentFilter(RelayService.STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses.orEmpty()
                .any { it.processName == "${packageName}:aa" }) {
            startService(Intent(this, RelayService::class.java).setAction(RelayService.QUERY))
        }
    }

    override fun onStop() {
        unregisterReceiver(updates)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instructions = TextView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(instructions)
            addView(Button(context).apply {
                text = "Start headless relay"
                setOnClickListener {
                    if (android.os.Build.VERSION.SDK_INT < 26) return@setOnClickListener
                    val permission = if (android.os.Build.VERSION.SDK_INT >= 33)
                        android.Manifest.permission.NEARBY_WIFI_DEVICES else android.Manifest.permission.ACCESS_FINE_LOCATION
                    if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        startRelay()
                    } else requestPermissions(arrayOf(permission), 21)
                }
            })
            approve = Button(context).apply {
                text = "Approve iPad"
                isEnabled = false
                setOnClickListener { startService(Intent(context, RelayService::class.java).setAction(RelayService.APPROVE)) }
            }
            addView(approve)
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
                    approve.isEnabled = false
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

    private fun startRelay() {
        startForegroundService(Intent(this, RelayService::class.java).setAction(RelayService.START))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startRelay()
    }

    private fun showInstructions() {
        instructions.text = "Start the relay to create an offline Wi-Fi network for the iPad."
    }
}
