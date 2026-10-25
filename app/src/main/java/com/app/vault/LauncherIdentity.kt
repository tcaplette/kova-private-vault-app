package com.app.vault

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes

/**
 * Which launcher entry the app presents — P2-22.
 *
 * `MainActivity` carries no `MAIN`/`LAUNCHER` filter and is not exported. Four
 * `activity-alias` entries target it — the vault's own, and one per cover — each with
 * its own name and icon, and exactly one of them is enabled whenever the app is not in
 * the foreground ([switchTo] says why not at every instant). Tapping any of them opens the vault's
 * ordinary unlock screen; **no alias lies about what it does**, it only declines to
 * advertise. A cover front-end behind one of these is P2-24's to add, under P2-24's
 * terms, and nothing here authenticates: the passphrase remains the only thing that
 * opens anything.
 *
 * **What this renames, and what it does not.** The alias governs the launcher entry.
 * [MainActivity] sets an `ActivityManager.TaskDescription` so recents follows it.
 * Everything else — notifications, Settings → Apps, the app-info page, storage,
 * battery, the permission manager — reads the **application** label from
 * `<application android:label>`, which an alias label does not reach and no runtime
 * API overrides. That boundary is `FORMAT.md` §10 and is not a defect to be fixed
 * later; hardcoding a neutral application label was considered and rejected, because
 * it is a build-time choice imposed on every install including the honest default.
 *
 * Nothing in this file logs. The chosen identity in a log line is the one fact the
 * feature exists to withhold.
 */
enum class LauncherIdentity(
    /**
     * The alias component, relative to the package — the manifest's `android:name`.
     */
    val aliasSuffix: String,
    /**
     * What the launcher, recents and the chooser row call this — the same
     * `@string/identity_*` the manifest's alias carries, which
     * `LauncherIdentityScanTest` holds them to. `app-languages` D5: the launcher and
     * recents say it in the device's language, the app's own screens in the app's.
     */
    @StringRes val label: Int,
    /** The launcher and recents icon. */
    val iconRes: Int,
    /**
     * The cover front end this identity opens, or null to open the vault directly —
     * P2-24.
     *
     * A cover identity **cannot be enabled until its trigger is chosen**, and
     * [reconcile] falls back to [Default] if a stored one has no trigger, so a cover
     * nobody can open is never the thing on the home screen.
     */
    val cover: Cover? = null,
) {

    /**
     * The vault's own name and icon, and the default — an install that never opens
     * the chooser looks identical to the build before P2-22.
     *
     * It is an alias like the other three rather than a filter on `MainActivity`
     * itself. Keeping the honest identity on the activity would make "exactly one
     * enabled" untrue by construction the moment any alias is on, because the one
     * component that cannot be disabled is the app's only real activity.
     */
    Vault(".alias.Vault", R.string.identity_vault, R.mipmap.ic_launcher_vault),


    /**
     * The three covers. Each opens a real, complete, offline app whose user-chosen
     * trigger leaves it for the vault's ordinary unlock screen.
     *
     * **The trigger is never the credential.** It reveals the unlock screen; the
     * passphrase still opens the vault, from every cover without exception — the hold that
     * brings up the music player's and voice recorder's dots only shows them
     * (`cover-hold-dots`). That is the line between this and every
     * incumbent calculator vault, where the arithmetic *is* the PIN at fifteen to
     * twenty bits against an offline-attackable slot array.
     *
     * `music-recorder-covers` replaced the notes list and the flashlight with these
     * two. The flashlight had one control, so its trigger was one small number; the
     * notes cover was a second notes implementation living beside the vault's own.
     */
    Calculator(".alias.Calculator", R.string.identity_calculator, R.mipmap.ic_launcher_calculator, Cover.Calculator),
    MusicPlayer(".alias.Music", R.string.identity_musicplayer, R.mipmap.ic_launcher_music, Cover.MusicPlayer),
    VoiceRecorder(
        ".alias.Recorder",
        R.string.identity_voicerecorder,
        R.mipmap.ic_launcher_recorder,
        Cover.VoiceRecorder,
    );

    /** Whether the manifest ships this alias enabled — [Vault] alone does. */
    val enabledInManifest: Boolean get() = this == Default

    fun component(context: Context): ComponentName =
        ComponentName(context.packageName, context.packageName + aliasSuffix)

    companion object {

        val Default = Vault

        /**
         * The stored identity, or [Default] for an absent or unrecognised name.
         *
         * `VaultSort.of`'s rule and its reason: a preference written by a later
         * build must not stop this one presenting a launcher entry.
         */
        fun of(name: String?): LauncherIdentity =
            entries.firstOrNull { it.name == name } ?: Default

        /**
         * The identity actually presentable on this device — P2-24.
         *
         * A cover identity whose trigger is missing falls back to [Default] rather
         * than putting a cover on the home screen that nobody can open. That is not
         * hypothetical: clearing the app's data leaves the identity preference in
         * `shared_prefs/grid` and the trigger in `shared_prefs/covers`, and there is
         * no ordering guarantee between two files.
         */
        fun resolved(context: Context): LauncherIdentity {
            val stored = GridPreference.identity(context)
            val cover = stored.cover ?: return stored
            return if (CoverTrigger.isConfigured(context, cover)) stored else Default
            // A revoked runtime grant is deliberately NOT a fallback —
            // `music-recorder-covers`. A missing trigger means a cover nobody can
            // open, so falling back is the only safe answer. A revoked grant is a
            // different thing: the cover still opens, its trigger still works, and
            // only its content is missing, which every real player and recorder
            // draws as a no-access state. Reverting would put the vault's own name
            // back on the home screen because a permission was turned off.
        }

        /**
         * Choose an identity — persist, then enable the incoming alias. **Nothing is
         * disabled here**; the outgoing alias goes at the next departure, when
         * `AutoLock` runs [reconcile] — `identity-switch-done-step` D1.
         *
         * **Named `switchTo` and not `apply`**, which the task list called it: a
         * companion function named `apply` shadows the stdlib scope function at
         * every call site on this type. The rename is the trade `VaultNote.add`
         * already made in P2-21, for the same kind of reason.
         *
         * **Why the disable waits.** Android finishes every activity that was started
         * through a component when that component is disabled.
         * [PackageManager.DONT_KILL_APP] spares the process and nothing else, which is
         * all its javadoc promises. Measured on API 36:
         * `wm_finish_activity: [.., com.app.vault/.alias.Vault, disabled-package]`, with
         * no process kill. The screen completing the switch was started through the
         * outgoing alias, so disabling it here closed the Done step about a second after
         * it appeared. Until the app leaves the foreground both entries are enabled;
         * `FORMAT.md` §10(39) records that window.
         *
         * **Persisting first** is what lets [reconcile] finish the job after a process
         * death. The preference is the single source of truth and every component
         * state is derived from it; there is deliberately no "switch in progress"
         * flag, because a second piece of state can be lost by the same death it would
         * exist to survive.
         */
        fun switchTo(context: Context, identity: LauncherIdentity) {
            GridPreference.setIdentity(context, identity)
            admit(context)
        }

        /**
         * Enable the stored identity's alias and **disable nothing** — the start path,
         * and the enabling half of a switch. `identity-switch-done-step` D3.
         *
         * A start comes through one of these aliases. Disabling it there would finish
         * the activity the start has just created: a process death after a switch's
         * enable, or a cover whose trigger is missing resolving back to the vault,
         * would each close the app on launch. The stale entry goes at the next
         * departure instead. Idempotent, for [reconcile]'s reason.
         */
        fun admit(context: Context) {
            val want = sweepAndResolve(context)
            set(context.packageManager, want, want.component(context), true)
        }

        /**
         * Force every alias's component state to match the stored identity — the
         * full pass, enable the wanted one, disable the rest.
         *
         * Run **only when the app leaves the foreground** (`AutoLock.onActivityStopped`),
         * because disabling the alias a running activity came through finishes that
         * activity (see [switchTo]). There it completes a switch, repairs a process
         * death between a switch's enable and its disable — which would otherwise leave
         * two launcher entries, the phone advertising the vault beside the identity
         * chosen to avoid it — and is what makes the choice survive a reboot and an app
         * update, neither of which replays a switch.
         *
         * **Idempotent.** A component already in the wanted state is not written, so
         * a launcher does not redraw its entry on every departure. A component the
         * user has never switched reads [PackageManager.COMPONENT_ENABLED_STATE_DEFAULT],
         * which is compared against what the manifest ships rather than assumed to be
         * disabled — so a fresh install reconciles to no writes at all.
         */
        fun reconcile(context: Context) {
            val want = sweepAndResolve(context)
            val pm = context.packageManager
            // Enable before disable: a launcher never sees zero entries in between.
            set(pm, want, want.component(context), true)
            entries.filter { it != want }.forEach { set(pm, it, it.component(context), false) }
        }

        private fun sweepAndResolve(context: Context): LauncherIdentity {
            // A cover this build no longer has leaves a stored identity, a trigger
            // and a limiter record behind it. `LauncherIdentity.of` already reads
            // the identity as the default; this deletes the rest, so the preference
            // file stops recording that the install once wore a cover that is gone.
            CoverTrigger.sweepUnknownCovers(context)
            GridPreference.sweepUnknownIdentity(context)
            return resolved(context)
        }

        private fun set(
            pm: PackageManager,
            identity: LauncherIdentity,
            component: ComponentName,
            enabled: Boolean,
        ) {
            val current = pm.getComponentEnabledSetting(component)
            val already = when (current) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> enabled
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> !enabled
                // Never switched: the effective state is what the manifest ships.
                else -> identity.enabledInManifest == enabled
            }
            if (already) return
            pm.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
