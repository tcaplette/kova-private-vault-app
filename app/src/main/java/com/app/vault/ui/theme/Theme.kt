package com.app.vault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.app.vault.ThemeChoice

/**
 * The app's two colour schemes, from `DESIGN.md` section 3.
 *
 * **Two, and exactly two.** P2-23 added the light one on accessibility grounds — the
 * app is a photo viewer and dark-only is hard to read in daylight — and added
 * nothing else. There is no palette picker, no custom accent, no per-screen
 * override, and no third scheme; `DesignTokensTest` asserts the count rather than
 * trusting this sentence.
 *
 * **Dynamic colour is still absent, and that absence is still the decision.** It
 * hands the app's identity to the user's wallpaper — on a stock Pixel, a purple —
 * and a vault whose accent changes with the wallpaper has no identity to recognise.
 * `DesignTokensTest` asserts this package contains no `dynamicDarkColorScheme` or
 * `dynamicLightColorScheme` reference, so that rejection fails the build rather than
 * a review. What it no longer bans is the *symbol* `lightColorScheme`, because one
 * is now correct; in its place it recomputes every contrast pair and the hue ban
 * across **both** palettes, which is a stricter check than the one it replaced.
 */
private val DarkScheme = with(VaultPalette.Dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        error = error,
        onError = onError,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceContainer,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
    )
}

private val LightScheme = with(VaultPalette.Light) {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        error = error,
        onError = onError,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceContainer,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
    )
}

/** The two schemes and their palettes, kept together so neither is chosen alone. */
private fun schemeFor(dark: Boolean): ColorScheme = if (dark) DarkScheme else LightScheme

private val VaultShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(Radius.control),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(Radius.container),
    large = androidx.compose.foundation.shape.RoundedCornerShape(Radius.container),
)

/**
 * Wraps every screen in the app.
 *
 * Its KDoc used to say "takes no theme argument; there is nothing to choose". There
 * is now exactly one thing to choose, and [ThemeChoice] is the whole of it.
 *
 * [choice] resolves against the platform's night-mode flag only when it is
 * [ThemeChoice.System] — a forced Light or Dark ignores the system entirely, which
 * is the distinction the setting exists to offer. The activity resolves the same
 * pair a second time, before `super.onCreate`, to pick the window background; the
 * two agree because they call the same [ThemeChoice.resolve].
 */
@Composable
fun VaultTheme(
    choice: ThemeChoice = ThemeChoice.Default,
    content: @Composable () -> Unit,
) {
    val dark = ThemeChoice.resolve(choice, isSystemInDarkTheme())
    val palette = if (dark) VaultPalette.Dark else VaultPalette.Light
    CompositionLocalProvider(LocalVaultPalette provides palette) {
        MaterialTheme(
            colorScheme = schemeFor(dark),
            typography = VaultTypography,
            shapes = VaultShapes,
            content = content,
        )
    }
}
