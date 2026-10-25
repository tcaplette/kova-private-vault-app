package com.app.vault.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The 4dp scale from `DESIGN.md` section 7. Nothing between the steps.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Screen horizontal inset. */
    val screenInset = 16.dp

    /** Between top-level sections of a screen. */
    val section = 24.dp

    /** Between items that belong together. */
    val related = 8.dp

    /**
     * Minimum touch target. Met by padding *around* the glyph — a 24dp icon
     * stays 24dp and gets 12dp of padding. Never by inflating the glyph.
     */
    val touchTarget = 48.dp

    /**
     * Top app bar height — P2-6. 64dp: 16 × the 4dp base unit, and the one value
     * above `touchTarget` the scale needs. It is not a step between the listed
     * ones, it is past the end of them, which is why it is named rather than
     * written as a literal at the one call site in `VaultScaffold`.
     */
    val barHeight = 64.dp

    /**
     * The floating action button's inset from the screen's bottom and trailing
     * edges. 16dp: the same step as [screenInset], because the button sits over
     * content that runs edge to edge and needs the screen's own margin rather
     * than the grid's 2dp gutter.
     */
    val fabInset = 16.dp

    /**
     * Bottom content padding for the tile list, so the last row can always be
     * scrolled clear of the floating action button.
     *
     * 88dp: the button's 56dp container plus [fabInset] above and below it. It
     * is past the end of the 4dp scale's listed steps, like [barHeight], which
     * is why it is named here rather than written as a literal at its one call
     * site.
     */
    val fabClearance = 88.dp

    /** Between photo grid cells. Chrome between tiles is chrome between photographs. */
    val gridGutter = 2.dp

    /** Photo grid cell minimum width, for `GridCells.Adaptive`. */
    val gridCellMin = 112.dp

    /** The memo screen's record control — `voice-memo-layout`. The circle and its ring. */
    val recordControl = 72.dp

    /** The memo record control's ring, drawn in `outline`. */
    val recordRing = 2.dp

    /** The memo record control's fill at rest: a circle inside the ring. */
    val recordFill = 56.dp

    /** The memo record control's fill while recording: the rounded square. */
    val recordFillActive = 28.dp

    /** The memo screen's waveforms, live and recorded, share this height. */
    val waveformHeight = 96.dp

    /** One bar of a memo waveform, and the height of a silent one. */
    val waveformBar = 3.dp

    /** Between two bars of a memo waveform. */
    val waveformGap = 2.dp

    /** The playhead across a recorded memo's waveform. */
    val playhead = 2.dp

    /** The memo screen's listen-back play/pause control. */
    val playControl = 56.dp

    /** A vault's badge on its list row — `vault-appearance`. */
    val badge = 40.dp

    /** The badge's live preview on New vault and Edit vault. */
    val badgePreview = 72.dp

    /** Material's shared-axis travel between a vault flow's steps — `new-vault-setup-flow`, D10. */
    val sharedAxis = 30.dp

    /**
     * The unlock screen's brand mark — `unlock-screen-redesign`, D3. The circle the lock
     * glyph sits in, above "Welcome back".
     */
    val lockMark = 72.dp

    /** The lock mark on a protected vault's badge, and the padlock inside it. */
    val badgeMark = 20.dp
    val badgeMarkGlyph = 14.dp

    /** Between two rows of a grouped list — the Android 16 Settings segmented block. */
    val groupGap = 2.dp

    /**
     * The vault list's large title bar, expanded. 152dp, Material's own large-bar height,
     * named here rather than left to the component default so it is written down.
     */
    val largeBarHeight = 152.dp
}

/** Corner radii from `DESIGN.md` section 7.1. */
object Radius {
    val container = 12.dp
    val control = 8.dp

    /** User media carries no radius. Present so a screen reads it rather than writing `0.dp`. */
    val media = 0.dp

    /** The memo record control's fill while recording — the square's corners. */
    val recordFillActive = 8.dp

    /** A grouped list's outer corners: the top of its first row, the bottom of its last. */
    val groupOuter = 20.dp

    /** A grouped list's corners between two rows. */
    val groupInner = 4.dp

    /**
     * A passphrase strength meter's segment — half its 4dp height, so each segment is a
     * capped bar rather than a rectangle.
     *
     * Named for what it draws rather than borrowed from [control], for the reason
     * [recordFillActive] is: 8dp on a 4dp-tall bar is not a smaller corner, it is a
     * different shape.
     */
    val meterSegment = 2.dp
}
