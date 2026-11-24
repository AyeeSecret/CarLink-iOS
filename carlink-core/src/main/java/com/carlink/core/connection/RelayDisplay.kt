package com.carlink.core.connection

import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle

object RelayDisplay {
    fun options(context: Context): Bundle? {
        if (Build.VERSION.SDK_INT < 26) return null
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = manager.displays.firstOrNull { it.name == "CarLink Relay" } ?: return null
        return ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle()
    }
}
