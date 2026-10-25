package com.app.vault.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.app.vault.FolderColour

/**
 * The active palette. Provided by [VaultTheme] and by nothing else.
 *
 * **No default.** A surface composed outside [VaultTheme] fails loudly here rather
 * than quietly drawing one scheme's colours while the app is in the other — which is
 * precisely the bug P2-23's coverage test exists to catch, and it should not be
 * possible to introduce it by forgetting a wrapper.
 *
 * `static` because the palette changes on a user action, not per frame: a static
 * local invalidates everything that read it when it changes, which is what a scheme
 * switch wants, and costs nothing in between.
 */
val LocalVaultPalette = staticCompositionLocalOf<VaultPalette> {
    error("No VaultPalette provided — a composable is being drawn outside VaultTheme")
}

/**
 * The palette from `DESIGN.md` section 4, read from whichever scheme is active.
 *
 * **These property names are the app's colour vocabulary and did not change with
 * P2-23.** Before it, each was a constant; after it, each reads
 * [LocalVaultPalette]. Every one of the 188 call sites across the app kept
 * compiling and started following the scheme — that equivalence is the whole reason
 * this file has the shape it has, and `ThemeScanTest` asserts the names are still
 * all here so a call site cannot be silently dropped.
 *
 * **A `@Composable` getter cannot be read outside composition**, which makes the
 * compiler the auditor: a colour read from a non-composable helper, where it would
 * be captured once and survive a scheme change, will not build. That is a finding to
 * report, not a line to work around.
 *
 * The literals live in [VaultPalette] so `DesignTokensTest` can recompute every
 * ratio in `DESIGN.md` on the JVM, for both schemes, without an emulator.
 */
object VaultColors {

    // --- surfaces -----------------------------------------------------------

    /** App ground. Deliberately not #000000 in either scheme — see DESIGN.md 4.1. */
    val Surface: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.surface

    val SurfaceContainerLow: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.surfaceContainerLow

    val SurfaceContainer: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.surfaceContainer

    val SurfaceContainerHigh: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.surfaceContainerHigh

    /**
     * The media viewer's ground, and the *only* place pure black is a surface.
     * A photograph should sit on nothing — and that stays true in daylight, so this
     * is identical in both schemes.
     */
    val ViewerGround: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.viewerGround

    // --- foregrounds --------------------------------------------------------

    val OnSurface: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onSurface

    val OnSurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onSurfaceVariant

    /**
     * Chrome drawn over [ViewerGround], pinned light in both schemes.
     *
     * The viewer's ground does not follow the scheme, so its foreground must not
     * either: the light palette's [OnSurface] over black is 1.1:1. Used by
     * `VaultViewer` and by nothing else — a screen reaching for this is drawing over
     * something it should not be.
     */
    val OnViewerGround: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onViewerGround

    /** Muted chrome over [ViewerGround]. Pinned for [OnViewerGround]'s reason. */
    val OnViewerGroundVariant: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onViewerGroundVariant

    /** The glyph on a coloured vault badge, pinned white in both schemes — [VaultPalette.onBadge]. */
    val OnBadge: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onBadge

    // --- accents ------------------------------------------------------------

    /**
     * The primary action, and nothing else. Never a status, badge, or checkmark
     * (DESIGN.md 4.1) — scarcity is what keeps "secure = the accent" from becoming
     * the app's whole visual argument. Its one other use is the unlock screen's brand
     * mark. The rule binds both schemes.
     */
    val Primary: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.primary

    val OnPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onPrimary

    /** Structural: selection, emphasis, information. Carries no meaning of its own. */
    val Secondary: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.secondary

    val OnSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onSecondary

    /**
     * *Recording, now.* The camera's video shutter and its running dot, and the voice
     * memo's record control, recording dot and live waveform — nothing else. See
     * [VaultPalette.record] for why this is not [Error].
     */
    val Record: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.record

    val Error: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.error

    val OnError: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onError

    /** Destroy Vault's fill, and nothing else — see [VaultPalette.danger]. Same in both schemes. */
    val Danger: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.danger

    val OnDanger: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.onDanger

    // --- lines --------------------------------------------------------------

    /**
     * Bounds interactive elements. Clears the 3:1 non-text floor against *every*
     * surface in its own scheme, including `SurfaceContainerHigh` — a value tuned
     * only against the app ground fails on a raised sheet, which is exactly where
     * text fields and outlined buttons live.
     */
    val Outline: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.outline

    /** Hairline dividers. Decorative only — below 3:1 in both schemes, so never a control boundary. */
    val OutlineVariant: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.outlineVariant

    val Scrim: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.scrim

    // --- folder fills -------------------------------------------------------
    //
    // `folder-system`. Read through [folder] rather than by name at a call site: a
    // stored ordinal picks one of these, and a `when` over the enum is the one place
    // that mapping should exist.

    val FolderRed: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.folderRed

    val FolderAmber: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.folderAmber

    val FolderTeal: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.folderTeal

    val FolderBlue: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.folderBlue

    val FolderPink: Color
        @Composable @ReadOnlyComposable get() = LocalVaultPalette.current.folderPink

    /**
     * The fill a folder of [colour] is drawn in.
     *
     * [FolderColour.None] is a raised surface and not a colour: a folder nobody has
     * coloured looks like a folder rather than like a gap.
     */
    @Composable
    @ReadOnlyComposable
    fun folder(colour: FolderColour): Color = when (colour) {
        FolderColour.None -> SurfaceContainerHigh
        FolderColour.Red -> FolderRed
        FolderColour.Amber -> FolderAmber
        FolderColour.Teal -> FolderTeal
        FolderColour.Blue -> FolderBlue
        FolderColour.Pink -> FolderPink
    }
}
