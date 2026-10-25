package com.app.vault

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * The release build's half of `debug-crash-log-screen`: the debug build's `CrashLogHook` signature
 * with nothing behind it. The release APK has no crash records, no screen that reads them and no
 * code that deletes them — the drawer checks [available] and draws no row.
 */
object CrashLogHook {

    const val available = false

    val icon: Int = 0

    @Suppress("UNUSED_PARAMETER")
    fun summary(context: Context): String = ""

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Screen(
        record: String?,
        onOpen: (String) -> Unit,
        confirmingClear: Boolean,
        onAskClear: () -> Unit,
        onDismissClear: () -> Unit,
        onBack: () -> Unit,
    ) = Unit
}
