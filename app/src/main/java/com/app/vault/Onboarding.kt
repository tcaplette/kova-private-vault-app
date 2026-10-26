package com.app.vault

import android.content.Context
import android.util.Base64

/**
 * What onboarding has already said, and how far first run got —
 * `first-run-onboarding`.
 *
 * Deliberately in `SharedPreferences` rather than a file in `filesDir`: `filesDir`
 * is the directory `FORMAT.md` §2 enumerates and Gate 1 inspects, and a step
 * counter is not worth a format amendment. `shared_prefs/` sits outside that list,
 * so §2 is unchanged.
 *
 * **The flow pauses and resumes under its keyring.** A lock or a restart mid-flow
 * does not end it; unlocking lands on the step that was reached. The step is stored
 * sealed under the keyring that was open when it was reached — always the owner's —
 * so the decoy's keyring, or any other, reads nothing and resumes nothing.
 *
 * **What it leaks:** that the app has been opened at least once, and that first run
 * started and has not finished, which `done` already said. The sealed step does not
 * say how far. The launcher icon and the store listing already say the app exists
 * (§10 item 5), and `vault.slots` already says a vault does. Nothing new.
 *
 * **What it deliberately does not record: which steps were skipped, or what any of
 * them enrolled.** An install that skipped the decoy and the panic steps must be
 * indistinguishable from one that completed them and turned both off — a stored
 * "declined panic" is the tell those features exist to avoid, and it would be a
 * decoy flag by another name (I8). The three keys below are the whole of it.
 *
 * Nothing else reads any of this. No feature is gated on it; it decides which
 * screen is drawn and nothing more.
 */
object Onboarding {

    private const val PREFS = "onboarding"
    private const val KEY_STEP = "step"
    private const val KEY_DONE = "done"

    /**
     * First run's nine steps, in the order they occur — `first-run-onboarding`.
     *
     * **The declaration order is the sequence.** `ordinal` is what advances, so
     * there is no second list to keep in step with this one and no way for the two
     * to disagree. `OnboardingTest` asserts the order, because an enum reordered by
     * a well-meaning refactor would silently reorder first run.
     *
     * Five of these positions are forced by behaviour rather than chosen:
     *
     *  - [Name] precedes [Passphrase] because the vault is created at the passphrase
     *    step's "Create vault", and it is created under the name and look chosen here
     *    — `first-vault-naming`.
     *  - [Kit] follows [Passphrase] because the mnemonic is derived from the VMK at
     *    creation and shown exactly once.
     *  - [Decoy] precedes [Panic] because `VaultLifecycle.destroy` overwrites every
     *    slot except the one that authenticated — on a single-vault install panic
     *    destroys nothing, so enrolling it first arms a credential that does not do
     *    what the screen says.
     *  - [Backup] sits between them: after [Decoy] because `BackupScreen` quotes the
     *    store's size before the folder picker and a decoy adds 30–80 items to it,
     *    and before [Panic] because panic's enrolment screen has to be able to say
     *    that panic empties the backup folder.
     *  - [Identity] is last because choosing a cover replaces the launcher entry and
     *    a cover has no escape hatch, so a step after it would be reachable only by
     *    performing the trigger.
     */
    enum class Step {
        Name,
        Passphrase,
        Kit,
        Fingerprint,
        Capture,
        Decoy,
        Backup,
        Panic,
        Identity;

        /** The next step, or null at the end of the flow. */
        val next: Step? get() = entries.getOrNull(ordinal + 1)

        /**
         * The step Back returns to, or null on [Name], the kit and the first step after it.
         *
         * Every step is drawn on every device, so this is the one before, with nothing
         * to skip in reverse. Never back past the kit: the vault exists by then and
         * the mnemonic has been shown once — returning to either screen would offer to
         * create a second vault, or to redisplay words the flow has already discarded.
         *
         * Before the vault exists nothing has been created, so the passphrase goes back
         * to [Name] and loses nothing — `first-vault-naming` D5. Back on [Name] is the
         * welcome screen, which is not a step.
         */
        fun previousStep(): Step? = when (this) {
            Passphrase -> Name
            else -> entries.getOrNull(ordinal - 1)?.takeIf { it > Kit }
        }

        /** "Step 4 of 9", for the frame. One-based, because people count from one. */
        val position: Int get() = ordinal + 1
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- the flow -------------------------------------------------------------

    /** Whether first run is still running. False forever once [finish] is called. */
    fun inProgress(context: Context): Boolean = !prefs(context).getBoolean(KEY_DONE, false)

    /**
     * The step to resume at, or null. Null with no keyring open, under any keyring
     * but the one the step was reached under, and on an install that has stored
     * nothing — the three are not told apart.
     */
    fun pausedStep(context: Context): Step? {
        // runCatching: a pre-sealing install stored an int here, and getString throws on it.
        val stored = runCatching { prefs(context).getString(KEY_STEP, null) }.getOrNull() ?: return null
        val sealed = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        val plain = VaultKeyringSession.openHeld(sealed) ?: return null
        return try {
            plain.singleOrNull()?.let { Step.entries.getOrNull(it.toInt()) }
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Records a step as reached, [sealed] under the open keyring by
     * `VaultKeyringSession.sealHeld`. Written on arrival, not on completion.
     */
    fun reached(context: Context, sealed: ByteArray) {
        prefs(context).edit().putString(KEY_STEP, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    /**
     * Ends the flow, permanently, and removes the paused step with it.
     *
     * Called when the last step completes, when a restore from backup replaces
     * first run, and by a panic unlock — after a panic nothing may greet anyone
     * with setup screens, or say setup was running. No other route ends it: a
     * lock or a restart pauses the flow, and [pausedStep] resumes it.
     *
     * `commit` rather than `apply`: this is written on the way out of the flow, and
     * a process killed before the write lands would run first run again.
     */
    fun finish(context: Context) {
        prefs(context).edit().remove(KEY_STEP).putBoolean(KEY_DONE, true).commit()
    }

    /** Test-only: a cleared install is a new install, and runs first run again. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
