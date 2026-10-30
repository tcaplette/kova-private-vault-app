package com.app.vault

import android.content.Context

/**
 * When the list's passphrase is asked for after a fingerprint unlock — `recovery-practice` D1.
 *
 * Three values in [GridPreference]'s file: the due time, the step (0–4) and the time the
 * schedule was last set. The intervals are [INTERVAL_DAYS]; step 4 repeats.
 *
 * - Right on the first entry advances one step; any wrong entry in the check returns to 0.
 * - Skip keeps the step and is due again in 1 day.
 * - A passphrase unlock is practice in itself: due one current interval ahead, step unchanged.
 * - A device clock earlier than the last set time is due at once, rather than waiting out a
 *   clock that went backwards.
 *
 * Every rule takes `now` from the caller, so `PassphrasePracticeTest` drives the whole schedule
 * on the JVM with an in-memory [Store].
 */
class PassphrasePractice(
    private val store: Store,
    private val extraSecurityOn: () -> Boolean,
    private val wrapExists: () -> Boolean,
) {

    /** The three values, so the schedule needs no Android to be tested. */
    interface Store {
        fun getLong(name: String): Long?
        fun putLong(name: String, value: Long)
        fun remove(vararg names: String)
    }

    companion object {
        const val KEY_DUE_AT = "practice_due_at"
        const val KEY_STEP = "practice_step"
        const val KEY_SET_AT = "practice_set_at"

        val INTERVAL_DAYS = listOf(1, 3, 7, 14, 30)
        private const val LAST_STEP = 4
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun of(context: Context): PassphrasePractice {
            val app = context.applicationContext
            val prefs = GridPreference.prefs(app)
            val store = object : Store {
                override fun getLong(name: String): Long? =
                    if (prefs.contains(name)) prefs.getLong(name, 0L) else null
                override fun putLong(name: String, value: Long) {
                    prefs.edit().putLong(name, value).apply()
                }
                override fun remove(vararg names: String) {
                    prefs.edit().apply { names.forEach { remove(it) } }.apply()
                }
            }
            return PassphrasePractice(
                store,
                extraSecurityOn = { ExtraSecurity.isOn(app) },
                wrapExists = { BiometricWrap(app).hasRecord() },
            )
        }
    }

    private val step: Int
        get() = (store.getLong(KEY_STEP) ?: 0L).toInt().coerceIn(0, LAST_STEP)

    private fun set(now: Long, step: Int, dueInDays: Int) {
        store.putLong(KEY_STEP, step.toLong())
        store.putLong(KEY_DUE_AT, now + dueInDays * DAY_MS)
        store.putLong(KEY_SET_AT, now)
    }

    /** Step 0, due in 1 day: biometric enabled, Extra Security switched on, a new passphrase set. */
    fun start(now: Long) = set(now, 0, INTERVAL_DAYS[0])

    /** Biometric disabled or Extra Security switched off. */
    fun clear() = store.remove(KEY_DUE_AT, KEY_STEP, KEY_SET_AT)

    fun isDue(now: Long): Boolean {
        if (!extraSecurityOn() || !wrapExists()) return false
        val due = store.getLong(KEY_DUE_AT) ?: return false
        val setAt = store.getLong(KEY_SET_AT) ?: return false
        return now >= due || now < setAt
    }

    /** The check ended right. [firstTry] false means a wrong entry came first, so back to step 0. */
    fun recordRight(now: Long, firstTry: Boolean) {
        val next = if (firstTry) minOf(step + 1, LAST_STEP) else 0
        set(now, next, INTERVAL_DAYS[next])
    }

    /** A wrong entry: step 0 at once, the check still due, so a lock now asks again next time. */
    fun recordWrong() = store.putLong(KEY_STEP, 0L)

    /** Skip or Back: step unchanged, due again in 1 day. */
    fun skip(now: Long) = set(now, step, INTERVAL_DAYS[0])

    /** A passphrase unlock: due one current interval ahead, step unchanged. Nothing when unscheduled. */
    fun notePassphraseUnlock(now: Long) {
        if (store.getLong(KEY_DUE_AT) == null) return
        set(now, step, INTERVAL_DAYS[step])
    }

    /** Days until the next check at the current step — for the snackbar after a right answer. */
    fun nextInterval(): Int = INTERVAL_DAYS[step]
}
