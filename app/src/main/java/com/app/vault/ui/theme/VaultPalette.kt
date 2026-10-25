package com.app.vault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The two palettes from `DESIGN.md` section 4 — one property per row of the table,
 * one instance per scheme.
 *
 * **These are the only colour literals in the app.** A screen that needs a colour
 * this file does not have amends `DESIGN.md` and adds it here, to *both* palettes —
 * a local `Color(0xFF...)` in a screen file is a defect, not a shortcut.
 *
 * **Why a data class and not two objects of constants.** [VaultColors] reads the
 * active one through a `CompositionLocal`, so its properties are composable getters
 * and cannot be read from a JVM test. The values have to live somewhere a test can
 * reach without an emulator, because `DesignTokensTest` recomputes every ratio in
 * `DESIGN.md` rather than trusting the table, and it must keep doing that on the
 * JVM. Nothing in this file imports the Compose runtime, and `ThemeScanTest` fails
 * the build if that changes.
 *
 * Every pair that carries text or bounds a control has a measured WCAG 2.1 ratio
 * recorded in `DESIGN.md`, recomputed on every run, **for both palettes**.
 */
data class VaultPalette(

    // --- surfaces -----------------------------------------------------------

    val surface: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,

    // --- foregrounds --------------------------------------------------------

    val onSurface: Color,
    val onSurfaceVariant: Color,

    // --- accents ------------------------------------------------------------

    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val error: Color,
    val onError: Color,

    /**
     * `vault-drawer-trial-and-destroy-pinned`. Destroy Vault's fill — the one act in the app
     * with no way back — and nothing else. Material Red 700, the same in both schemes.
     *
     * [onDanger] (white) on it is 4.98:1, above AA's 4.5:1. The fill stands 3.03:1 off the
     * dark drawer (`#1E2732`) and 3.88:1 off the light one (`#DDE4EC`), both above the 3:1 a
     * control's boundary needs. Apple's `systemRed` (`#FF3B30`) gives white 3.55:1 and
     * `#E53935` 4.23:1, so both fail; a darker `#B3261E` falls to 2.31:1 on the dark drawer.
     *
     * **Not [error].** In the dark scheme that is `#FFB4AB`, a pale salmon that does not read
     * as danger, and changing it would recolour every error line in the app.
     */
    val danger: Color,
    val onDanger: Color,

    // --- lines --------------------------------------------------------------

    val outline: Color,
    val outlineVariant: Color,

    val scrim: Color,

    // --- folder fills -------------------------------------------------------
    //
    // `folder-system`. A folder's tile fill, never its label: the name is drawn
    // below the tile on the app surface, so no text ever sits on one of these and
    // none of them needs a text-contrast pair. That is the point — meaning never
    // rides on colour alone (§6.4), so these carry none.
    //
    // No hue in 260°–310° (§12's purple ban, which binds every token), and none in
    // the primary's indigo: the primary holds the primary-action role and nothing else.
    val folderRed: Color,
    val folderAmber: Color,
    val folderTeal: Color,
    val folderBlue: Color,
    val folderPink: Color,
) {

    /**
     * The media viewer's ground, and the *only* place pure black is a surface.
     * A photograph should sit on nothing — **in both schemes**, which is why this is
     * a constant on the companion rather than a per-palette value.
     */
    val viewerGround: Color get() = ViewerGround

    /** Chrome drawn over [viewerGround]. Pinned light in both schemes — see below. */
    val onViewerGround: Color get() = OnViewerGround

    /** Muted chrome over [viewerGround]. Pinned for [onViewerGround]'s reason. */
    val onViewerGroundVariant: Color get() = OnViewerGroundVariant

    /**
     * *Recording, now* — the camera's shutter in video mode and its running dot, and
     * the voice memo's record control, recording dot and live waveform
     * (`voice-memo-layout`). Nothing else, and never text.
     *
     * Pinned in both schemes for [onViewerGround]'s reason: it is drawn over a
     * viewfinder, which is a ground that does not follow the scheme. On the memo
     * screen it sits on `surface` instead, and clears the 3:1 a filled control needs
     * there in both schemes — `DesignTokensTest` checks it. It is red
     * because a record control is red on every camera ever made, and it is **not**
     * [error]: nothing has gone wrong, and a token that means "this failed" standing
     * in for one that means "this is running" is how the two stop being tellable
     * apart the first time both appear on one screen.
     */
    val record: Color get() = Record

    /**
     * The glyph on a coloured vault badge — `vault-appearance`. Pinned white in both schemes:
     * the five folder fills are mid-tones in both, and white clears 3:1 on every one of them
     * (light amber lowest, 3.19:1). On the No colour badge the glyph is `onSurfaceVariant`.
     * `DesignTokensTest` recomputes all ten pairs.
     */
    val onBadge: Color get() = OnBadge

    companion object {

        /** See [VaultPalette.onBadge]. */
        val OnBadge = Color(0xFFFFFFFF)

        /**
         * Pure black, in both schemes. `DESIGN.md` §4.1: in the viewer the
         * photograph should sit on nothing, and that is true in daylight too.
         */
        val ViewerGround = Color(0xFF000000)

        /**
         * **The viewer's ground does not follow the scheme, so its foreground must
         * not either.** In the light palette `onSurface` is `#0E141A`, which over
         * black is **1.1:1** — the viewer is where a light scheme fails first, and
         * the failure is invisible from within the dark scheme that produced it.
         *
         * Two roles and not one: the viewer draws muted text as well as primary
         * text over black, and the light palette's `onSurfaceVariant` (`#3B4753`)
         * over black is 1.9:1 — the same failure one shade along.
         *
         * These are the dark palette's two foregrounds, pinned. 17.33:1 and 8.57:1
         * on black respectively, both above the text floor, both recomputed by
         * `DesignTokensTest` rather than trusted.
         */
        val OnViewerGround = Color(0xFFE4EAF0)
        val OnViewerGroundVariant = Color(0xFF9AA7B5)

        /**
         * The record red, pinned in both schemes — see [VaultPalette.record].
         *
         * 5.37:1 on black, against the 3:1 a filled control has to clear. **It is
         * never text** — the elapsed time beside it is drawn in [onViewerGround] at
         * 17.33:1 — which is why it sits with the bounds pairs and does not answer
         * the light scheme's AAA rule. Hue 358°, well clear of §12's 260°–310° ban.
         * `DesignTokensTest` recomputes all of it rather than trusting this
         * sentence.
         */
        val Record = Color(0xFFE5484D)

        /**
         * The dark scheme — the values `DESIGN.md` §4 records.
         *
         * `primary` is indigo (hue 230°): a light tone read against the dark ground,
         * with `onPrimary` a deep indigo on it at 7.80:1. `secondary` is the same hue
         * at low chroma — Material 3's tonal rule — so it reads as the primary's family
         * without competing with the primary action.
         */
        val Dark = VaultPalette(
            surface = Color(0xFF0B0F14),
            surfaceContainerLow = Color(0xFF12181F),
            surfaceContainer = Color(0xFF161D26),
            surfaceContainerHigh = Color(0xFF1E2732),
            onSurface = Color(0xFFE4EAF0),
            onSurfaceVariant = Color(0xFF9AA7B5),
            primary = Color(0xFF9AABFF),
            onPrimary = Color(0xFF0A1550),
            secondary = Color(0xFFBEC4E4),
            onSecondary = Color(0xFF1B2140),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            danger = Color(0xFFD32F2F),
            onDanger = Color(0xFFFFFFFF),
            outline = Color(0xFF6A7684),
            outlineVariant = Color(0xFF232C36),
            scrim = Color(0xFF000000),
            folderRed = Color(0xFFB4443C),
            folderAmber = Color(0xFFB07A22),
            folderTeal = Color(0xFF2E8A7E),
            folderBlue = Color(0xFF3E6EA8),
            folderPink = Color(0xFFB0517E),
        )

        /**
         * P2-23. The same hue families at the tones a light scheme needs.
         *
         * **Roles, not values.** In `darkColorScheme` `primary` is a light tone read
         * against a dark ground; in `lightColorScheme` it is a *fill that white text
         * sits on*. Pasting the dark values here would put light indigo on near-white.
         * This is the argument §4 already makes about the CSS tables it declined to
         * copy from, one scheme along.
         *
         * **Derived to clear AAA, not to pass.** The first set that cleared AA
         * bottomed out at 5.50:1, which would have left the light scheme measurably
         * worse than the dark one; `primary`, `error` and `onSurfaceVariant` were
         * darkened until the floor reached **7.41:1**. This palette therefore has
         * **no** AAA exception, where the dark one documents one at 6.92:1 for muted
         * text on a raised container.
         *
         * `primary` is the same indigo at a deep tone (`#2A3A9E`, hue 232°) that white
         * sits on at 9.56:1; `secondary` is its low-chroma tone, as in the dark scheme.
         *
         * No value falls in the 260°–310° hue ban, and the primary keeps its single role.
         */
        val Light = VaultPalette(
            surface = Color(0xFFF7F9FB),
            surfaceContainerLow = Color(0xFFEFF3F7),
            surfaceContainer = Color(0xFFE7ECF2),
            surfaceContainerHigh = Color(0xFFDDE4EC),
            onSurface = Color(0xFF0E141A),
            onSurfaceVariant = Color(0xFF3B4753),
            primary = Color(0xFF2A3A9E),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFF3D4369),
            onSecondary = Color(0xFFFFFFFF),
            error = Color(0xFF8C1D18),
            onError = Color(0xFFFFFFFF),
            danger = Color(0xFFD32F2F),
            onDanger = Color(0xFFFFFFFF),
            outline = Color(0xFF5C6874),
            outlineVariant = Color(0xFFC9D2DC),
            scrim = Color(0xFF000000),
            folderRed = Color(0xFFC0524A),
            folderAmber = Color(0xFFBC8626),
            folderTeal = Color(0xFF34988B),
            folderBlue = Color(0xFF4A7CB8),
            folderPink = Color(0xFFBE5C8B),
        )

        /** Both, for any check that must run over every scheme rather than one. */
        val All = mapOf("Dark" to Dark, "Light" to Light)
    }
}
