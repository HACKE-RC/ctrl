package com.example.ctrl.mcp

import android.content.Context
import android.os.Build
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager

data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotation: Int,
) {
    companion object {
        fun fromContext(context: Context): DisplayInfo {
            val metrics = context.resources.displayMetrics
            val densityDpi = metrics.densityDpi

            if (Build.VERSION.SDK_INT >= 30) {
                val wm = context.getSystemService(WindowManager::class.java)
                val bounds: Rect = wm.currentWindowMetrics.bounds
                val dm = context.getSystemService(DisplayManager::class.java)
                val rotation = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
                return DisplayInfo(
                    widthPx = bounds.width(),
                    heightPx = bounds.height(),
                    densityDpi = densityDpi,
                    rotation = rotation,
                )
            }

            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val display: Display = wm.defaultDisplay
            return DisplayInfo(
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
                densityDpi = densityDpi,
                rotation = display.rotation,
            )
        }
    }
}
