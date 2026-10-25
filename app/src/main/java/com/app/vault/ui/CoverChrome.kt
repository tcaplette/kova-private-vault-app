package com.app.vault.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The chrome a cover shares — `music-recorder-covers`.
 *
 * The status and navigation bar icons follow the **cover's** scheme while it is up,
 * and are put back when it goes away. `MainActivity` sets them from the vault's
 * stored appearance, which is a vault artefact: light icons over a dark player would
 * be the cover's own chrome disagreeing with it, on the two strips of screen a person
 * sees before anything else.
 *
 * The calculator carries its own copy of this, written before there was a second
 * cover to share it with. It is left where it is: this file exists so the two covers
 * added here agree with each other, not to reopen a screen this change does not
 * otherwise touch.
 */
@Composable
fun CoverSystemBars() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(view, dark) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window
            ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        onDispose {
            controller.isAppearanceLightStatusBars = before
            controller.isAppearanceLightNavigationBars = before
        }
    }
}

/** Milliseconds as `m:ss`, or `h:mm:ss` past an hour — what every transport prints. */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
