package com.app.vault

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.work.WorkManager
import androidx.work.WorkQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sets FLAG_SECURE on every activity as it is created, so the app cannot be
 * screenshotted or screen-recorded.
 *
 * Done here rather than in MainActivity.onCreate so that activity number two
 * cannot be added without it. [AutoLock] rides the same callbacks for the same
 * reason — P1-10.
 *
 * `recents-shows-identity`. The recent-apps card is not blank: `MainActivity` lifts the
 * flag on the way out, only over a committed frame of the identity picture, and sets it
 * again before anything else is drawn on the way back.
 */
open class VaultApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // `app-languages` D2. First, so a worker started in a cold process — an import's
        // notification with no activity — already speaks the chosen language.
        AppLanguage.apply(this)
        registerActivityLifecycleCallbacks(FlagSecure)
        registerActivityLifecycleCallbacks(AutoLock)
        AutoLock.attach(this)
        TamperLog.attach(this)
        // `backup-to-cloud-apps` D10, D11. Backup after every change, and at lock.
        BackupAuto.attach(this)
    }

    // A configuration change hands the framework's own locales back; on 30–32 that is the
    // device's language, so the choice is put back on top of it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.apply(this)
    }

    /**
     * The flag, on every window from its creation. Never lifted here: lifting it for the
     * recents card is `MainActivity`'s, which knows when the identity picture is on screen —
     * `recents-shows-identity` D4.
     */
    private object FlagSecure : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

/**
 * Auto-lock — P1-10. The vault closes the moment the app is not in front of the
 * user: home, recents, app switch, screen off, another app taking focus.
 *
 * Counted on `onStart`/`onStop`, never `onPause`/`onResume`: a dialog or a
 * permission sheet pauses the activity without stopping it, and locking the app
 * underneath its own prompt is not hardening, it is a bug.
 *
 * **Two suppressions, and there is no third.** Both are user-initiated, both are
 * visible while they last, and both end on their own:
 *
 *  1. **A picker this app launched.** The photo picker *is* the app backgrounding
 *     itself. Locking here also breaks import outright — the result callback
 *     reads the metadata blob ID out of a session that would no longer have one,
 *     and the chosen files would disappear with no error at all.
 *  2. **An import in flight.** P1-8.6 made imports run behind a foreground
 *     service precisely so a multi-GB copy survives the user leaving. Locking
 *     would park every one of them on `ImportWorker`'s wait-for-unlock branch, so
 *     an unattended import would never be unattended.
 *
 * Deliberately **not** a grace period. A timeout is a window an attacker waits
 * out; these two end with an event the user can see — a picker on screen, a
 * notification in the shade.
 *
 * `androidx.lifecycle:lifecycle-process` would be the textbook home for this and
 * is a dependency Phase 1 never listed, for a counter that is a handful of lines.
 */
object AutoLock : Application.ActivityLifecycleCallbacks {

    private const val TAG = "AutoLock"

    private lateinit var appContext: Context

    private var started = 0

    /**
     * An activity-result flow this app launched is outstanding. Set immediately
     * before `launch`, cleared when the result arrives — and on destroy, so a
     * process that dies inside the picker cannot leave it stuck on.
     */
    @Volatile
    private var awaitingResult = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watcher: Job? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Called around every `ActivityResultLauncher.launch` in the app. */
    fun awaitingResult(pending: Boolean) {
        awaitingResult = pending
        evaluate()
    }

    /**
     * `recents-shows-identity` D1. A result this app launched is outstanding — the photo
     * picker, the file chooser, a permission request. `MainActivity`'s leave path reads it
     * and raises no identity picture, because each of those comes back to the screen it left.
     */
    val isAwaitingResult: Boolean get() = awaitingResult

    /** For tests: whether the app currently believes it is foreground. */
    val isForeground: Boolean get() = started > 0

    /**
     * `screen-off-lock-all-screens`. Moves every time the policy finds the app away with no
     * picker outstanding — whether or not anything was open to lock. `MainActivity` compares it
     * on `onStart`, and a changed value is what "the app was locked while away" means to the
     * screens. A picker round trip leaves it alone, which is what keeps a chosen file on screen.
     */
    @Volatile
    var awayEpoch = 0
        private set

    /**
     * `screen-off-lock-all-screens`. The policy, asked again outside a lifecycle event — for a
     * keyring that opened while the app was away, which no stop will ever report.
     */
    fun recheck() = evaluate()

    override fun onActivityStarted(activity: Activity) = onForeground()

    override fun onActivityStopped(activity: Activity) {
        onBackground()
        // `identity-switch-done-step` D2. The departure is where a switch's outgoing
        // launcher alias is disabled, because disabling it finishes every activity
        // started through it — see `LauncherIdentity.switchTo`. Each guard is a case
        // where that finish would land on something the owner still needs:
        //  - `started == 0`: an activity is still visible.
        //  - `!awaitingResult`: a picker this app launched is up, and finishing the
        //    activity beneath it loses the result. The next real departure catches up.
        //  - `!isChangingConfigurations`: a recreate drops the count to zero for a moment,
        //    and the activity being recreated must not be finished.
        if (started == 0 && !awaitingResult && !activity.isChangingConfigurations) {
            LauncherIdentity.reconcile(activity.applicationContext)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        // A picker that never returned cannot hold the vault open forever.
        if (started <= 0) awaitingResult = false
        evaluate()
    }

    /**
     * The policy is a count, not an activity — these two take no `Activity`
     * because nothing here has ever looked at one. That also makes the policy
     * drivable from a test with no UI, which is the only way to assert it
     * without an emulator dance for every case.
     */
    fun onForeground() {
        started++
        evaluate()
    }

    fun onBackground() {
        // Clamped: an unbalanced stop must not drive the count negative, or every
        // later check for "is the app foreground" reads as a lie in the other
        // direction.
        started = maxOf(0, started - 1)
        evaluate()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /**
     * Locks **whatever is open** unless something suppresses it.
     *
     * Whatever is open, not the vault: a keyring with no vault open on it is the
     * vault list, and it holds every vault's name in the clear. `keyring-auto-lock`.
     *
     * The work case is one flow rather than a poll: its **first** emission is
     * the current state, so "nothing is running" locks immediately and
     * "something is" waits for the emission that says otherwise — which is also
     * the lock that has to happen when the last job finishes with the app
     * already in the background.
     *
     * **Import and export both count** (P1-13). The rule P1-10 set has not grown a
     * third case: the two suppressions are still a picker this app launched, and
     * long-running work the user can see in the shade. An export is the second
     * kind — and without it a backgrounded multi-GB export would lock the vault
     * under itself and park on `ExportWorker`'s "wait for the user" branch, the
     * identical failure P1-8.6 and P1-10 already fixed once for imports.
     */
    private fun evaluate() {
        watcher?.cancel()
        watcher = null

        // **Two questions, and running them together is what caused this defect.**
        // The first is *may we lock* — the app is away and no picker is outstanding.
        if (started > 0 || awaitingResult) return
        // The second is *is there anything to lock*, and it is the general question.
        // It used to read `!VaultSession.isUnlocked`, which asks whether a **vault**
        // is open. `vault-keyring` put a level above that: a passphrase opens a
        // keyring, the names of its vaults become readable, and no vault is open
        // until one is chosen. So the vault list — the screen the user lands on
        // after typing their passphrase — returned here and never locked. Screen
        // off, home, recents: the keyring stayed open, and coming back showed every
        // vault and its name with no passphrase.
        //
        // Kept rather than dropped for `lockNow`'s idempotence: this is what stops a
        // WorkManager query and a coroutine on every activity stop in an install
        // that has nothing open, which is every launch before the first passphrase.
        if (!VaultSession.isUnlocked && !VaultKeyringSession.isOpen) {
            // Nothing to lock, and still a departure: the screens reached from the lock
            // screen go back to it — `screen-off-lock-all-screens`.
            awayEpoch++
            return
        }
        if (!::appContext.isInitialized) return

        watcher = scope.launch {
            runCatching {
                WorkManager.getInstance(appContext)
                    .getWorkInfosFlow(
                        WorkQuery.fromTags(ImportWorker.IMPORT_TAG, ExportWorker.EXPORT_TAG)
                    )
                    .first { infos -> infos.none { !it.state.isFinished } }
            }
            // Re-checked after the wait: the user may have come back, or started
            // another picker, while an import was finishing.
            if (started <= 0 && !awaitingResult) {
                awayEpoch++
                VaultLifecycle.lockNow()
                Log.i(TAG, "locked on background")
            }
        }
    }
}
