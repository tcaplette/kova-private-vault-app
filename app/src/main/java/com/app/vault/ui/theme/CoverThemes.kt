package com.app.vault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The music player's and voice recorder's themes — `music-recorder-covers`.
 *
 * **Both resolve against the system's night-mode flag and nothing else**, exactly as
 * [CalculatorTheme] does and for the same reason: the vault's
 * [com.app.vault.ThemeChoice] is a vault artefact, set inside the app these screens
 * exist to hide, and a cover that changed appearance with it would be changing for a
 * reason nobody looking at a music player could explain.
 *
 * **No `ColorScheme` is constructed here.** `DesignTokensTest.exactlyTwoSchemesAreConstructed`
 * holds the theme package to one `darkColorScheme` and one `lightColorScheme`. Both
 * covers read their palette directly and pass every colour they draw explicitly,
 * including their ripples — which is also what keeps a Material default from leaking
 * the host's tokens onto a cover.
 *
 * Two covers in one file rather than two files of nine lines each: they are the same
 * mechanism twice, and reading them side by side is how they stay the same.
 */

val LocalMusicPalette = staticCompositionLocalOf<MusicPalette> {
    error("No MusicPalette provided — the music player is being drawn outside MusicTheme")
}

val LocalRecorderPalette = staticCompositionLocalOf<RecorderPalette> {
    error("No RecorderPalette provided — the recorder is being drawn outside RecorderTheme")
}

/**
 * The music player's colour vocabulary.
 *
 * Composable getters for [VaultColors]' reason: a colour captured outside composition
 * survives a scheme change, and the compiler is the only auditor that catches it
 * every time.
 */
object MusicColors {
    val Ground @Composable @ReadOnlyComposable get() = LocalMusicPalette.current.ground
    val Surface @Composable @ReadOnlyComposable get() = LocalMusicPalette.current.surface
    val Accent @Composable @ReadOnlyComposable get() = LocalMusicPalette.current.accent
    val OnAccent @Composable @ReadOnlyComposable get() = LocalMusicPalette.current.onAccent
    val TextPrimary @Composable @ReadOnlyComposable get() = LocalMusicPalette.current.textPrimary
    val TextSecondary @Composable @ReadOnlyComposable get() = LocalMusicPalette.current.textSecondary
    val Outline @Composable @ReadOnlyComposable get() = LocalMusicPalette.current.outline
}

/** The voice recorder's colour vocabulary. */
object RecorderColors {
    val Ground @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.ground
    val Surface @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.surface
    val Record @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.record
    val OnRecord @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.onRecord
    val Accent @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.accent
    val TextPrimary @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.textPrimary
    val TextSecondary @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.textSecondary
    val Outline @Composable @ReadOnlyComposable get() = LocalRecorderPalette.current.outline
}

/** Wraps the music player cover, and nothing else in the app. */
@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) MusicPalette.Dark else MusicPalette.Light
    CompositionLocalProvider(LocalMusicPalette provides palette, content = content)
}

/** Wraps the voice recorder cover, and nothing else in the app. */
@Composable
fun RecorderTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) RecorderPalette.Dark else RecorderPalette.Light
    CompositionLocalProvider(LocalRecorderPalette provides palette, content = content)
}
