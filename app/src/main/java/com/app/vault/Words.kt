package com.app.vault

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The app's text, for code that builds a sentence away from the screen — `app-languages`.
 * A [Context] in the app ([words]); in a JVM test, which has no resources to resolve
 * against, the base `strings.xml` read directly.
 */
interface Words {
    fun get(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

fun Context.words(): Words = object : Words {
    override fun get(id: Int, vararg args: Any) = getString(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any) =
        resources.getQuantityString(id, count, *args)
}

/**
 * [words] inside a composable. Reading [LocalConfiguration] subscribes the caller to a change
 * of language, which reading [LocalContext] alone does not.
 */
@Composable
fun localWords(): Words {
    LocalConfiguration.current
    return LocalContext.current.words()
}
