package com.app.vault

import android.content.Context

/**
 * How the user last set the grid — how dense (P2-8) and in what order (P2-16).
 *
 * In `SharedPreferences` for the same reason [Onboarding] is: `filesDir` is the
 * directory `FORMAT.md` §2 enumerates and Gate 1 inspects, and a column count is
 * not worth a format amendment. `shared_prefs/` sits outside that list, so §2 is
 * unchanged.
 *
 * **What it leaks:** that the app has been opened and a grid preference set —
 * which the existence of `shared_prefs/onboarding` already says. It names no
 * vault, no item, and no count of either. It is deliberately *not* per vault:
 * a per-vault preference would make the number of preference entries the number
 * of vaults, which is exactly the tell I8 exists to prevent. **P2-16's ordering
 * is stored on the same terms** — one entry for the device, naming nothing. P2-20's
 * face-down switch is the third and
 * makes the same trade: one boolean for the device, naming no vault, no item and no
 * count of either. **P2-22's launcher identity is the fourth** and makes it again:
 * one name for the device, naming no vault, no item and no count of either — and it
 * is deliberately not per vault for the reason above, which reaches it hardest. An
 * identity stored per vault would make the number of identity entries the number of
 * vaults, on the one preference whose effect a person holding the phone can already
 * see. **P2-23's appearance choice is the fifth** and makes the trade once more: one
 * name for the device, naming no vault, no item and no count of either.
 */
object GridPreference {

    private const val PREFS = "grid"
    private const val KEY_DENSITY = "density"
    private const val KEY_SORT = "sort"
    private const val KEY_FACE_DOWN = "face_down"
    private const val KEY_FACE_DOWN_LOCK_AFTER = "face_down_lock_after_ms"
    private const val KEY_IDENTITY = "identity"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_STRIP_EXIF = "strip_exif_import"
    private const val KEY_DETAILS_PRESET = "photo_details_preset"
    private const val KEY_DETAILS_CUSTOM = "photo_details_custom"
    private const val KEY_MEDIA_KEEP_ON = "media_keep_screen_on"
    private const val KEY_MEDIA_BRIGHTNESS = "media_full_brightness"
    private const val KEY_MEDIA_STRETCH = "media_stretch_small"
    private const val KEY_MEDIA_QUALITY = "media_full_quality"
    private const val KEY_MEDIA_SPEED = "media_playback_speed"
    private const val KEY_MEDIA_RESUME = "media_resume_videos"
    private const val KEY_MEDIA_REPEAT = "media_repeat_video"
    private const val KEY_MEDIA_SKIP = "media_double_tap_skip"
    private const val KEY_MEDIA_ORIENTATION = "media_video_orientation"
    private const val KEY_SLIDESHOW_SPEED = "slideshow_speed"
    private const val KEY_SLIDESHOW_REPEAT = "slideshow_repeat"
    private const val KEY_SLIDESHOW_SHUFFLE = "slideshow_shuffle"
    private const val KEY_NOTES_MOVE_CHECKED = "notes_move_checked"
    private const val KEY_NOTES_INCOGNITO = "notes_incognito_keyboard"
    private const val KEY_MEMOS_QUALITY = "memos_recording_quality"
    private const val KEY_MEMOS_STEREO = "memos_stereo"
    private const val KEY_MEMOS_BLUETOOTH = "memos_bluetooth_mic"
    private const val KEY_MEMOS_SPEED = "memos_playback_speed"
    private const val KEY_MEMOS_SKIP_SILENCE = "memos_skip_silence"

    /**
     * One key per destination — `destination-view-modes`.
     *
     * ***Four entries, and the I8 argument in this file's KDoc is why it is four and not
     * one-per-vault.*** The rule there is that an entry is one per *device* and never per
     * vault, because a per-vault entry makes the number of preference entries the number of
     * vaults — the count I8 exists to hide. These keys are named by **destination**, of which
     * there are exactly four on every install: four on a device holding one vault and four on
     * a device holding ten. No key names a vault, an item, an album, or a count of any of
     * them.
     *
     * Written out rather than derived from the enum's name so that renaming a destination in
     * source cannot silently re-point every device's saved choice — the same hazard the
     * name-versus-ordinal rule above [sort] guards against, one level up.
     */
    private fun keyFor(destination: VaultDestination) = when (destination) {
        VaultDestination.Media -> "view_media"
        VaultDestination.Files -> "view_files"
        VaultDestination.Notes -> "view_notes"
        VaultDestination.Memos -> "view_memos"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored column count, or null if the user has never chosen one. */
    fun columns(context: Context): Int? =
        prefs(context).getInt(KEY_DENSITY, 0).takeIf { it > 0 }

    fun setColumns(context: Context, columns: Int) {
        prefs(context).edit().putInt(KEY_DENSITY, columns).apply()
    }

    /**
     * The stored ordering, or [VaultSort.Default] when none was chosen — P2-16.
     *
     * Stored as the enum's **name**, never its ordinal: an ordinal makes the
     * stored value depend on the declaration order, so adding an ordering in the
     * middle later would silently re-point every device's saved choice. An
     * unrecognised name reads as the default rather than throwing, so a preference
     * written by a later build cannot stop this one opening a grid.
     */
    fun sort(context: Context): VaultSort =
        VaultSort.of(prefs(context).getString(KEY_SORT, null))

    fun setSort(context: Context, sort: VaultSort) {
        prefs(context).edit().putString(KEY_SORT, sort.name).apply()
    }

    /**
     * Takes the old slideshow's interval off an install that stored one before that slideshow
     * was removed (2026-09-12). Its key was `slideshow`; the rebuilt slideshow's are below and
     * named apart. Nothing reads it; a no-op once it is gone.
     */
    fun sweepRetired(context: Context) {
        prefs(context).edit().remove("slideshow").apply()
    }

    /**
     * Media's nine settings — `media-settings`. One entry each for the device, by name, naming
     * no vault, no item and no count: this file's I8 argument, unchanged. An absent entry reads
     * the default, which is what the viewer did before the setting existed.
     */
    fun mediaSettings(context: Context): MediaSettings {
        val p = prefs(context)
        val d = MediaSettings()
        return MediaSettings(
            keepScreenOn = p.getBoolean(KEY_MEDIA_KEEP_ON, d.keepScreenOn),
            fullBrightness = p.getBoolean(KEY_MEDIA_BRIGHTNESS, d.fullBrightness),
            stretchSmall = p.getBoolean(KEY_MEDIA_STRETCH, d.stretchSmall),
            fullQuality = p.getBoolean(KEY_MEDIA_QUALITY, d.fullQuality),
            speed = PlaybackSpeed.of(p.getString(KEY_MEDIA_SPEED, null)),
            resume = p.getBoolean(KEY_MEDIA_RESUME, d.resume),
            repeat = p.getBoolean(KEY_MEDIA_REPEAT, d.repeat),
            skip = SkipLength.of(p.getString(KEY_MEDIA_SKIP, null)),
            orientation = VideoOrientation.of(p.getString(KEY_MEDIA_ORIENTATION, null)),
        )
    }

    fun setMediaSettings(context: Context, settings: MediaSettings) {
        prefs(context).edit()
            .putBoolean(KEY_MEDIA_KEEP_ON, settings.keepScreenOn)
            .putBoolean(KEY_MEDIA_BRIGHTNESS, settings.fullBrightness)
            .putBoolean(KEY_MEDIA_STRETCH, settings.stretchSmall)
            .putBoolean(KEY_MEDIA_QUALITY, settings.fullQuality)
            .putString(KEY_MEDIA_SPEED, settings.speed.name)
            .putBoolean(KEY_MEDIA_RESUME, settings.resume)
            .putBoolean(KEY_MEDIA_REPEAT, settings.repeat)
            .putString(KEY_MEDIA_SKIP, settings.skip.name)
            .putString(KEY_MEDIA_ORIENTATION, settings.orientation.name)
            .apply()
    }

    /**
     * The slideshow's Options — `slideshow`. One entry each for the device, by name, naming no
     * vault, no item and no count: this file's I8 argument, unchanged.
     */
    fun slideshowSettings(context: Context): SlideshowSettings {
        val p = prefs(context)
        val d = SlideshowSettings()
        return SlideshowSettings(
            speed = SlideshowSpeed.of(p.getString(KEY_SLIDESHOW_SPEED, null)),
            repeat = p.getBoolean(KEY_SLIDESHOW_REPEAT, d.repeat),
            shuffle = p.getBoolean(KEY_SLIDESHOW_SHUFFLE, d.shuffle),
        )
    }

    fun setSlideshowSettings(context: Context, settings: SlideshowSettings) {
        prefs(context).edit()
            .putString(KEY_SLIDESHOW_SPEED, settings.speed.name)
            .putBoolean(KEY_SLIDESHOW_REPEAT, settings.repeat)
            .putBoolean(KEY_SLIDESHOW_SHUFFLE, settings.shuffle)
            .apply()
    }

    /**
     * Notes' two settings — `notes-settings`. One entry each for the device, by name, naming no
     * vault, no note and no count: this file's I8 argument, unchanged. Absent reads the default.
     */
    fun noteSettings(context: Context): NoteSettings {
        val p = prefs(context)
        val d = NoteSettings()
        return NoteSettings(
            moveChecked = p.getBoolean(KEY_NOTES_MOVE_CHECKED, d.moveChecked),
            incognito = p.getBoolean(KEY_NOTES_INCOGNITO, d.incognito),
        )
    }

    fun setNoteSettings(context: Context, settings: NoteSettings) {
        prefs(context).edit()
            .putBoolean(KEY_NOTES_MOVE_CHECKED, settings.moveChecked)
            .putBoolean(KEY_NOTES_INCOGNITO, settings.incognito)
            .apply()
    }

    /**
     * Memos' five settings — `memos-settings`, on the same terms. Quality and speed by name, an
     * unknown name reading the default.
     */
    fun memoSettings(context: Context): MemoSettings {
        val p = prefs(context)
        val d = MemoSettings()
        return MemoSettings(
            quality = RecordingQuality.of(p.getString(KEY_MEMOS_QUALITY, null)),
            stereo = p.getBoolean(KEY_MEMOS_STEREO, d.stereo),
            bluetooth = p.getBoolean(KEY_MEMOS_BLUETOOTH, d.bluetooth),
            speed = PlaybackSpeed.of(p.getString(KEY_MEMOS_SPEED, null)),
            skipSilence = p.getBoolean(KEY_MEMOS_SKIP_SILENCE, d.skipSilence),
        )
    }

    fun setMemoSettings(context: Context, settings: MemoSettings) {
        prefs(context).edit()
            .putString(KEY_MEMOS_QUALITY, settings.quality.name)
            .putBoolean(KEY_MEMOS_STEREO, settings.stereo)
            .putBoolean(KEY_MEMOS_BLUETOOTH, settings.bluetooth)
            .putString(KEY_MEMOS_SPEED, settings.speed.name)
            .putBoolean(KEY_MEMOS_SKIP_SILENCE, settings.skipSilence)
            .apply()
    }

    /**
     * Whether face-down auto-lock is on — P2-20. **Off until the user turns it on.**
     *
     * A boolean needs no name-versus-ordinal rule: an absent key reads `false`,
     * which is the shipped default, so a device that has never been asked and a
     * device that answered no are the same device as far as this is concerned. That
     * is the right way round — the feature locks a vault the user is actively
     * looking at, and does not get to surprise anybody into it.
     */
    fun faceDown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FACE_DOWN, false)

    fun setFaceDown(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_FACE_DOWN, on).apply()
    }

    /**
     * Face-down's Lock after presets, in ms — `face-down-lock-settings` D3. Android's "Lock after"
     * list, stopping at 5 min: a phone face down longer than that is long past any screen timeout.
     */
    val FACE_DOWN_LOCK_AFTER = listOf(0L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)

    /** Face-down's Lock after in ms. Immediately (0) until chosen; a value not in the list reads as 0. */
    fun faceDownLockAfter(context: Context): Long =
        prefs(context).getLong(KEY_FACE_DOWN_LOCK_AFTER, 0L).takeIf { it in FACE_DOWN_LOCK_AFTER } ?: 0L

    fun setFaceDownLockAfter(context: Context, ms: Long) {
        require(ms in FACE_DOWN_LOCK_AFTER) { "not a Lock after preset: $ms" }
        prefs(context).edit().putLong(KEY_FACE_DOWN_LOCK_AFTER, ms).apply()
    }

    /**
     * Which launcher entry the app presents — P2-22. [LauncherIdentity.Default]
     * until the user chooses, so an install that predates the feature looks
     * identical after the update.
     *
     * By **name** and not ordinal, an unrecognised value reading as the default, for
     * the reasons written above [sort]. It matters more here than there: this value
     * decides which manifest component is enabled, so a silently re-pointed
     * preference would put the wrong icon on somebody's home screen.
     *
     * **This is the single source of truth for the alias component states.** Each
     * one is derived from it — enabled by [LauncherIdentity.admit] at every start,
     * the rest disabled by [LauncherIdentity.reconcile] at every departure — which is
     * what makes a process death partway through a switch repairable.
     */
    fun identity(context: Context): LauncherIdentity =
        LauncherIdentity.of(prefs(context).getString(KEY_IDENTITY, null))

    fun setIdentity(context: Context, identity: LauncherIdentity) {
        prefs(context).edit().putString(KEY_IDENTITY, identity.name).apply()
    }

    /**
     * Delete a stored identity naming something this build does not have —
     * `music-recorder-covers`.
     *
     * [identity] already reads an unrecognised name as the default, so nothing
     * depends on this. What it removes is the record: a preference file that still
     * says `Flashlight` says that this install once wore a cover, to anyone who can
     * read the file, for no benefit to the user whose cover was taken away.
     */
    fun sweepUnknownIdentity(context: Context) {
        val stored = prefs(context).getString(KEY_IDENTITY, null) ?: return
        if (LauncherIdentity.entries.none { it.name == stored }) {
            prefs(context).edit().remove(KEY_IDENTITY).apply()
        }
    }

    /**
     * Which colour scheme the app draws — P2-23. [ThemeChoice.Default] until the
     * user chooses, which is Dark, so an install that predates the setting is
     * identical after the update.
     *
     * By **name** and not ordinal, an unrecognised value reading as the default, for
     * the reasons written above [sort].
     *
     * **Read twice per launch, deliberately.** `MainActivity` reads it before
     * `super.onCreate` to pick the window theme — the window background is resolved
     * before any of this app's code runs, and it is what stops a cold start flashing
     * the other scheme's ground — and `VaultTheme` reads it again to pick the
     * palette. Both resolve through `ThemeChoice.resolve`, so they cannot disagree.
     */
    fun appearance(context: Context): ThemeChoice =
        ThemeChoice.of(prefs(context).getString(KEY_APPEARANCE, null))

    fun setAppearance(context: Context, choice: ThemeChoice) {
        prefs(context).edit().putString(KEY_APPEARANCE, choice.name).apply()
    }

    /**
     * The app language on Android 11–12, where the platform has no per-app language —
     * `app-languages` D2. A BCP 47 tag, or null for the device's language. One value for
     * the device, naming no vault, no item and no count of either. Android 13+ never
     * writes it: [AppLanguage] hands it to `LocaleManager` and clears it.
     */
    fun language(context: Context): String? =
        prefs(context).getString(KEY_LANGUAGE, null)

    fun setLanguage(context: Context, tag: String?) {
        // `commit`, not `apply`: on 30–32 the process may be killed straight after a
        // choice, and the next cold start reads this before anything is drawn.
        prefs(context).edit().putString(KEY_LANGUAGE, tag).commit()
    }

    /**
     * Whether an imported photograph has its metadata removed before it is sealed —
     * `exif-strip-choice`.
     *
     * **False by default, and that is the decision.** A file the user put into their own vault
     * is theirs as they gave it; a vault that silently edits what it stores is a vault that
     * cannot be trusted to give it back. The opposite default applies on the way out, where the
     * cost of being wrong is somebody else's copy.
     *
     * **The seventh entry, and it makes this file's trade once more:** one boolean for the
     * device, naming no vault, no item and no count of either. Deliberately not per vault, for
     * the reason the KDoc above gives — a per-vault entry would make the number of preference
     * entries the number of vaults.
     *
     * It is read by the import paths only. The export control and the share screen ask their
     * own question, because a sender who keeps metadata in their own vault has not thereby
     * agreed to send it to anyone.
     */
    fun stripExifOnImport(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRIP_EXIF, false)

    fun setStripExifOnImport(context: Context, strip: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRIP_EXIF, strip).apply()
    }

    /**
     * Privacy or Custom — `exif-strip-choice`. By name, an absent or unknown value reading as
     * Privacy, so a new install strips what Privacy strips without anyone having chosen it.
     */
    fun photoDetailsPreset(context: Context): PhotoDetailsPreset =
        PhotoDetailsPreset.of(prefs(context).getString(KEY_DETAILS_PRESET, null))

    fun setPhotoDetailsPreset(context: Context, preset: PhotoDetailsPreset) {
        prefs(context).edit().putString(KEY_DETAILS_PRESET, preset.name).apply()
    }

    /**
     * The categories Custom removes. **Absent reads as Privacy's set**, which is where Custom
     * starts the first time it is chosen; a stored empty set is the user's own answer and is
     * returned as one. One entry for the install, whatever the number of vaults.
     */
    fun photoDetailsCustom(context: Context): Set<PhotoDetail> =
        prefs(context).getStringSet(KEY_DETAILS_CUSTOM, null)?.let(PhotoDetail::namedIn)
            ?: ExifPolicy.PRIVACY

    fun setPhotoDetailsCustom(context: Context, removed: Set<PhotoDetail>) {
        prefs(context).edit().putStringSet(KEY_DETAILS_CUSTOM, removed.map { it.name }.toSet()).apply()
    }

    /**
     * How [destination] is drawn — `destination-view-modes`.
     *
     * [VaultDestination.defaultViewMode] until the user chooses, which is exactly what that
     * destination drew before the toggle existed: an install that predates this change looks
     * identical after it.
     *
     * By **name** and not ordinal, an unrecognised value reading as the default, for the
     * reasons written above [sort]. It matters the same way here: adding a third mode later
     * would otherwise re-point every saved choice, and a preference written by a later build
     * must not stop this one drawing a screen.
     */
    fun viewMode(context: Context, destination: VaultDestination): VaultViewMode {
        val stored = prefs(context).getString(keyFor(destination), null)
        return VaultViewMode.entries.firstOrNull { it.name == stored }
            ?: destination.defaultViewMode
    }

    fun setViewMode(context: Context, destination: VaultDestination, mode: VaultViewMode) {
        prefs(context).edit().putString(keyFor(destination), mode.name).apply()
    }

    /** For the test that holds the I8 count: exactly one key per destination, and no more. */
    internal fun viewModeKeys(): List<String> = VaultDestination.entries.map { keyFor(it) }
}
