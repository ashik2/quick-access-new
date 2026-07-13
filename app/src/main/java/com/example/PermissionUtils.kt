package com.example

import android.content.Context
import android.os.Build
import android.provider.Settings

object PermissionUtils {
    private var lastCheckTime = 0L
    private var lastResult = false

    fun canDrawOverlaysCompat(context: Context): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCheckTime < 2000) {
            return lastResult
        }
        lastCheckTime = currentTime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            lastResult = Settings.canDrawOverlays(context.applicationContext)
            return lastResult
        }
        lastResult = true
        return true
    }
}

