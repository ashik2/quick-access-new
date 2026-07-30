package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IconCacheManager {
    // Memory cache for up to 300 app icons
    private val memoryCache = LruCache<String, ImageBitmap>(300)

    fun getCachedIcon(packageName: String): ImageBitmap? {
        if (packageName.startsWith("sys:")) return null
        return memoryCache.get(packageName)
    }

    suspend fun loadIconBitmap(context: Context, packageName: String): ImageBitmap? {
        if (packageName.startsWith("sys:")) return null

        getCachedIcon(packageName)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val iconDrawable = pm.getApplicationIcon(packageName)
                // Downscale icon to max 128x128 for high performance and low memory footprint
                val width = iconDrawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(128)
                val height = iconDrawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(128)

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                iconDrawable.setBounds(0, 0, width, height)
                iconDrawable.draw(canvas)

                val imageBitmap = bitmap.asImageBitmap()
                memoryCache.put(packageName, imageBitmap)
                imageBitmap
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun preloadIcons(context: Context, packageNames: List<String>) {
        withContext(Dispatchers.IO) {
            for (pkg in packageNames) {
                if (memoryCache.get(pkg) == null) {
                    loadIconBitmap(context, pkg)
                }
            }
        }
    }
}

@Composable
fun rememberAppIcon(context: Context, packageName: String): ImageBitmap? {
    if (packageName.startsWith("sys:")) return null

    val cached = remember(packageName) { IconCacheManager.getCachedIcon(packageName) }
    val iconState = remember(packageName) { mutableStateOf(cached) }

    if (iconState.value == null) {
        LaunchedEffect(packageName) {
            val loaded = IconCacheManager.loadIconBitmap(context, packageName)
            iconState.value = loaded
        }
    }

    return iconState.value
}
