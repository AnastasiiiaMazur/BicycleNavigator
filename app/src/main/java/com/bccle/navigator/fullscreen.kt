package com.bccle.navigator

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

fun Activity.applyFullscreen(padTarget: View? = null, transientBySwipe: Boolean = true) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN              or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION         or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN       or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION  or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }

    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
        val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottom = max(sys.bottom, ime.bottom)
        v.setPadding(sys.left, sys.top, sys.right, bottom)
        insets
    }
}

fun androidx.fragment.app.Fragment.applyFullscreen(padTarget: View? = view, transientBySwipe: Boolean = true) {
    requireActivity().applyFullscreen(padTarget, transientBySwipe)
}

fun android.app.Dialog.applyFullscreen(padTarget: View? = null, transientBySwipe: Boolean = true) {
    WindowCompat.setDecorFitsSystemWindows(window!!, false)
    val controller = WindowInsetsControllerCompat(window!!, window!!.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    if (transientBySwipe) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    padTarget?.let { target ->
        ViewCompat.setOnApplyWindowInsetsListener(target) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, max(sys.bottom, ime.bottom))
            insets
        }
        target.requestApplyInsets()
    }
}