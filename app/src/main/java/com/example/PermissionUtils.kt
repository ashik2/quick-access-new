package com.example

import android.content.Context
import android.os.Build
import android.provider.Settings

object PermissionUtils {
    fun canDrawOverlaysCompat(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context.applicationContext)
        }
        return true
    }
}

