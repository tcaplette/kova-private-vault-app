package com.app.vault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The calculator cover's theme — P2-25.
 *
 * **It resolves against the system's night-mode flag and nothing else.** The vault's
 * [com.app.vault.ThemeChoice] is a vault artefact: it is a thing the owner set inside
 * the app this screen exists to hide, and a cover that followed it would change
 * appearance for a reason nobody looking at a calculator could explain. The device's
 * own dark-mode setting is the only input, which is what every calculator on the
 * device does.
 *
 * **No `ColorScheme` is constructed here.** `DesignTokensTest.exactlyTwoSchemesAreConstructed`
 * holds the theme package to one `darkColorScheme` and one `lightColorScheme`, and it
 * is right to: a third would be the palette-picker surface arriving by another door.
 * The calculator needs none — it reads [CalculatorColors] directly and passes every
 * colour it draws explicitly, including its ripples.
 */
val LocalCalculatorPalette = staticCompositionLocalOf<CalculatorPalette> {
    error("No CalculatorPalette provided — the calculator is being drawn outside CalculatorTheme")
}

/**
 * The calculator's colour vocabulary.
 *
 * Composable getters for [VaultColors]' reason: a colour captured outside composition
 * survives a scheme change, and the compiler is the only auditor that catches it
 * every time.
 */
object CalculatorColors {
    val Ground @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.ground
    val KeyDigit @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.keyDigit
    val KeyFunction @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.keyFunction
    val KeyOperator @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.keyOperator
    val Accent @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.accent
    val EqualsFill @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.equalsFill
    val OnAccent @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.onAccent
    val OnEquals @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.onEquals
    val TextPrimary @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.textPrimary
    val TextSecondary @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.textSecondary
    val Error @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.error
    val ChipOutline @Composable @ReadOnlyComposable get() = LocalCalculatorPalette.current.chipOutline
}

/** Wraps the calculator cover, and nothing else in the app. */
@Composable
fun CalculatorTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) CalculatorPalette.Dark else CalculatorPalette.Light
    CompositionLocalProvider(LocalCalculatorPalette provides palette, content = content)
}
