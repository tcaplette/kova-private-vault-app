package com.app.vault

import android.content.Context

/**
 * Whether the app may add to a vault, or send a share out of one — P1-16, and
 * `one-time-purchase`.
 *
 * Two invariants shape every line of this file.
 *
 * **I4.** Read and export are never gated, metered or delayed by billing state.
 * That is not a policy this file implements; it is a place this file is not
 * allowed to appear. `EntitlementScanTest` fails the build if this symbol is
 * referenced from any export, viewer, playback, archive-write or unlock source,
 * and it is asserted negatively against a fixture so it cannot rot into a no-op.
 * A user who cannot pay still opens, views and exports everything they have,
 * forever — and still opens every share sent to them, which the same scan protects.
 *
 * **I5.** A licence check that fails grants full access. That is [importAllowed]'s
 * last line and nothing else: the probe is wrapped so that a throw, a dead client,
 * a missing Play Store or an answer that never came all produce `true`. There is
 * deliberately **no `Unknown` state and no timeout to tune** — "we do not know" and
 * "entitled" are the same value here, and a state machine with an `Unknown` member
 * is a state machine someone can later route to `Blocked`. If you are about to add
 * that enum: the missing state is the point.
 *
 * The trial is app-managed, and starts at the first item added to a vault by any
 * route — [startTrialIfNeeded] is called from each one. No payment method, no Play
 * involvement, no server —
 * a server would need `INTERNET`, which I1 forbids outright. The cost of that is
 * recorded and asserted rather than hidden: clearing app data resets the trial
 * (`EntitlementTest.dataClearResetsTrial`), and setting the device clock back
 * extends it (P2-5 owns clock-rollback detection). The worst case either way is
 * someone importing for free, which I4 already makes survivable.
 *
 * Stored in `SharedPreferences`, beside the onboarding flags and for the same
 * reason: `FORMAT.md` §2 enumerates `filesDir`, and a date is not worth a format
 * amendment. Its own prefs file rather than a third key in [Onboarding], because
 * "what onboarding has said" and "when the trial started" are unrelated facts.
 */
object Entitlement {

    private const val PREFS = "entitlement"
    private const val KEY_TRIAL_STARTED_AT = "trialStartedAt"

    const val TRIAL_DAYS = 30
    private const val DAY_MS = 86_400_000L

    /** The two days P1-16 allows a reminder on. Not a range, not a schedule. */
    val REMINDER_DAYS = intArrayOf(23, 28)

    /**
     * What is still true when the gate closes, said in the same breath as the
     * refusal. A user who reads "expired" and concludes their photographs are
     * locked has been told something false by us.
     */
    val BLOCKED = R.string.trial_blocked

    /**
     * The purchase answer, as a swappable function so the tests can force each
     * side of it without Play. Defaults to the real client; `null` there — no
     * answer yet — reads as entitled, which is I5 rather than an oversight.
     */
    @Volatile
    var purchasedProbe: () -> Boolean = { Billing.hasPurchased() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 0 when no trial has started. Written once and never moved. */
    fun trialStartedAt(context: Context): Long =
        prefs(context).getLong(KEY_TRIAL_STARTED_AT, 0L)

    /**
     * Called after every add that actually **succeeded** — an import, a capture, a
     * saved memo, a new note, a kept share, ingested cover captures, an archive
     * import, a restore. A pick the user cancelled through, a discard, an edit or a
     * failure starts nothing — and a later add does not move the date, which is
     * what the absence check is for.
     */
    fun startTrialIfNeeded(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_TRIAL_STARTED_AT)) {
            p.edit().putLong(KEY_TRIAL_STARTED_AT, System.currentTimeMillis()).apply()
        }
    }

    /** Whole days since the trial started; 0 when it has not. */
    fun trialDay(context: Context): Int {
        val started = trialStartedAt(context)
        if (started == 0L) return 0
        return ((System.currentTimeMillis() - started) / DAY_MS).toInt().coerceAtLeast(0)
    }

    /** [TRIAL_DAYS] before the first add, counting down to 0 after it. */
    fun daysRemaining(context: Context): Int =
        if (trialStartedAt(context) == 0L) TRIAL_DAYS
        else (TRIAL_DAYS - trialDay(context)).coerceAtLeast(0)

    /** Whether the trial has run out. Says nothing about the purchase. */
    fun trialExpired(context: Context): Boolean =
        trialStartedAt(context) != 0L && trialDay(context) >= TRIAL_DAYS

    /**
     * The only question this file answers, asked wherever the app adds to a vault —
     * an import, the camera, a voice memo, a new note, a kept share, a decoy, an
     * archive import, vault creation — and before a share is sealed.
     *
     * `true` unless all three are definitively known — the trial started, it ran
     * out, and Play answered that the purchase is not owned. Anything less than all
     * three is access.
     */
    fun importAllowed(context: Context): Boolean {
        if (!trialExpired(context)) return true
        return runCatching { purchasedProbe() }.getOrDefault(true)
    }

    /** The reminder for today, or null on all 28 other days of the trial. */
    fun reminder(context: Context): String? {
        if (trialStartedAt(context) == 0L || trialExpired(context)) return null
        if (trialDay(context) !in REMINDER_DAYS.toList()) return null
        val left = daysRemaining(context)
        return context.resources.getQuantityString(R.plurals.trial_reminder, left, left)
    }

    /** Test-only: reach an expiry state without waiting 30 days for it. */
    fun setTrialStartedAtForTest(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_TRIAL_STARTED_AT, millis).commit()
    }

    /** Test-only: a cleared install is a new install, and gets a new trial. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().commit()
        purchasedProbe = { Billing.hasPurchased() }
    }
}
