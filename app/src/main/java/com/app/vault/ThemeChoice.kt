package com.app.vault

import androidx.annotation.StringRes

/**
 * Which colour scheme the app draws — P2-23.
 *
 * `DESIGN.md` §3 was dark-only until this, and the rule changed on **accessibility**
 * grounds rather than competitive ones: the app is a photo viewer and dark-only is
 * genuinely hard to read in daylight. Everything §3 rejected alongside dark-only
 * survives — no dynamic colour, no wallpaper-derived accent, no palette picker, no
 * purple — and now binds twice instead of once.
 *
 * **[Dark] is the default**, so an install that predates P2-23 looks identical after
 * the update until somebody chooses otherwise.
 *
 * Nothing here logs. Nothing here touches an Android type either, which is what
 * keeps [resolve]'s whole truth table a JVM test rather than an emulator run —
 * `FaceDown.kt`'s rule and its reason.
 */
enum class ThemeChoice(
    /** What the drawer row and the chooser call this. */
    @StringRes val label: Int,
) {

    /**
     * Follow the platform's night-mode flag, whatever it currently says.
     *
     * **Labelled with one word**, because three segments across a drawer sheet have
     * no room for five and a segment that ellipsises says nothing. The words it gave
     * up are bought back by the caption under the control. Nothing stored moves: the
     * preference is written by enum [name], never by this label and never by ordinal.
     */
    System(R.string.appearance_system),

    Light(R.string.appearance_light),

    Dark(R.string.appearance_dark);

    companion object {

        val Default = Dark

        /**
         * The stored choice, or [Default] for an absent or unrecognised name.
         *
         * `VaultSort.of`'s rule and its reason: a preference written by a later
         * build must not stop this one drawing a screen.
         */
        fun of(name: String?): ThemeChoice =
            entries.firstOrNull { it.name == name } ?: Default

        /**
         * Whether to draw the dark scheme.
         *
         * **A pure function of two booleans-worth of input**, deliberately: three
         * choices against two system states is a six-row truth table, and a table
         * that small should be checkable without booting anything. [System] reads
         * the platform; [Light] and [Dark] ignore it entirely, which is the whole
         * distinction between "follow" and "force".
         */
        fun resolve(choice: ThemeChoice, systemIsDark: Boolean): Boolean = when (choice) {
            System -> systemIsDark
            Light -> false
            Dark -> true
        }
    }
}
