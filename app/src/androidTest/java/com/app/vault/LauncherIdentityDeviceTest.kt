package com.app.vault

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launcher identity on a device — P2-22.
 *
 * `LauncherIdentityTest` owns the enum; this owns the two things only a device has.
 * **Component state**, which no source can be read for: a manifest scan cannot see
 * that two aliases are enabled, which a switch now leaves on purpose until the app
 * departs and `LauncherIdentity.reconcile` removes the outgoing one —
 * `identity-switch-done-step`. And **the boundary**, which is
 * asserted here rather than assumed — the application label does not follow the
 * identity, and this test records that rather than failing on it.
 *
 * **Restores the device preference and the component states it changes.** The
 * identity is one entry in `shared_prefs/grid`, shared with the app the developer is
 * using, and its effect is an icon on their home screen. `FaceDownDeviceTest`'s rule,
 * with more to put back.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIdentityDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm: PackageManager get() = ctx.packageManager

    private lateinit var before: LauncherIdentity
    private val triggersBefore = mutableMapOf<Cover, Trigger?>()
    private val holdsBefore = mutableMapOf<Cover, Int?>()

    /**
     * Every identity but the vault's own opens a cover, and a cover identity cannot
     * be presented until its trigger is chosen (P2-24). So the triggers are set up
     * here — this test is about the *switching mechanism*, and it needs identities it
     * is allowed to switch to.
     */
    @Before
    fun remember() {
        before = GridPreference.identity(ctx)
        Cover.entries.forEach { cover ->
            triggersBefore[cover] = CoverTrigger.triggerFor(ctx, cover)
            holdsBefore[cover] = CoverTrigger.holdFor(ctx, cover)
            // `cover-hold-dots`: a pattern cover is configured only with its hold area; the
            // calculator's key sequence takes none (`calculator-dots-methods`).
            check(CoverTrigger.set(ctx, cover, testTrigger(cover), area = if (cover == Cover.Calculator) null else 0)) {
                "the test trigger for ${cover.name} was refused"
            }
        }
    }

    @After
    fun restore() {
        Cover.entries.forEach { cover ->
            val had = triggersBefore[cover]
            CoverTrigger.clear(ctx, cover)
            if (had != null) CoverTrigger.set(ctx, cover, had, area = holdsBefore[cover])
        }
        LauncherIdentity.switchTo(ctx, before)
        // The switch only enables; the departure pass leaves exactly one entry.
        LauncherIdentity.reconcile(ctx)
    }

    private fun testTrigger(cover: Cover): Trigger = when (cover) {
        Cover.Calculator -> Trigger.Keys(listOf("7", "3", "sin"))
        // `pattern-cover-trigger`. Any five distinct nodes is a valid pattern; this
        // one is arbitrary and used only as "a trigger this cover will accept".
        Cover.MusicPlayer, Cover.VoiceRecorder -> Trigger.Pattern(listOf(0, 4, 8, 2, 6))
    }

    /** Every `MAIN`/`LAUNCHER` component this package currently presents. */
    private fun launcherEntries(): List<String> =
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ctx.packageName),
            0,
        ).map { it.activityInfo.name }

    private fun state(identity: LauncherIdentity): Int =
        pm.getComponentEnabledSetting(identity.component(ctx))

    private fun states(): Map<LauncherIdentity, Int> =
        LauncherIdentity.entries.associateWith { state(it) }

    // ---------------------------------------------------------------- 10.1, 10.2

    /**
     * The invariant, asserted against the platform's own view rather than the
     * manifest, in its two passes — `identity-switch-done-step`. The switch enables
     * the incoming alias and leaves the outgoing one alone; the departure's
     * `reconcile` then leaves exactly one entry, the stored identity's.
     *
     * Iterates **every** identity. A cover identity cannot be presented until its
     * trigger is chosen, so [remember] configures them first — without that,
     * `reconcile` would correctly resolve each one back to the default and this would
     * assert the opposite of the requirement.
     */
    @Test
    fun exactlyOneLauncherEntryAfterEverySwitch() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        LauncherIdentity.reconcile(ctx)
        var outgoing = LauncherIdentity.Default
        LauncherIdentity.entries.forEach { identity ->
            LauncherIdentity.switchTo(ctx, identity)
            assertEquals(
                "the switch to ${identity.name} did not leave both entries",
                setOf(outgoing, identity).map { ctx.packageName + it.aliasSuffix }.toSet(),
                launcherEntries().toSet(),
            )

            LauncherIdentity.reconcile(ctx)
            val entries = launcherEntries()
            assertEquals("launcher entries after departing ${identity.name}: $entries", 1, entries.size)
            assertEquals(
                ctx.packageName + identity.aliasSuffix,
                entries.single(),
            )
            assertEquals(identity, GridPreference.identity(ctx))
            outgoing = identity
        }
    }

    /** And back again — a switch is reversible and leaves nothing behind once departed. */
    @Test
    fun switchingAwayAndBackLeavesOneEntry() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)
        LauncherIdentity.reconcile(ctx)
        assertEquals(1, launcherEntries().size)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Vault)
        LauncherIdentity.reconcile(ctx)
        assertEquals(1, launcherEntries().size)
        assertEquals(ctx.packageName + LauncherIdentity.Vault.aliasSuffix, launcherEntries().single())
    }

    /**
     * **`admit` never disables anything.** Every identity in turn is admitted with
     * nothing departing in between, so every alias ends up enabled — the start path
     * cannot close the activity a launch just created.
     */
    @Test
    fun admitNeverDisablesAnything() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        LauncherIdentity.reconcile(ctx)
        LauncherIdentity.entries.forEach { identity ->
            GridPreference.setIdentity(ctx, identity)
            LauncherIdentity.admit(ctx)
        }
        assertEquals(
            "admit disabled an alias",
            LauncherIdentity.entries.map { ctx.packageName + it.aliasSuffix }.toSet(),
            launcherEntries().toSet(),
        )
    }

    /**
     * The process is still here to make the assertion. `DONT_KILL_APP` is what this
     * measures — a test process that had been killed would show as a crashed run,
     * not a failed one.
     */
    @Test
    fun theProcessSurvivesASwitch() {
        val pid = android.os.Process.myPid()
        LauncherIdentity.switchTo(ctx, LauncherIdentity.MusicPlayer)
        LauncherIdentity.reconcile(ctx)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Vault)
        LauncherIdentity.reconcile(ctx)
        assertEquals("the process was replaced by a switch", pid, android.os.Process.myPid())
    }

    /**
     * **The Done step stays on screen** — `identity-switch-done-step`, driven through
     * the real mechanism. `MainActivity` is launched through the vault's own alias, as
     * a launcher launches it, and the switch is made from inside it. Android finishes
     * every activity started through a component when that component is disabled, so
     * a switch that disabled the outgoing alias closed this activity about a second
     * later. Two seconds on, it must still be resumed.
     *
     * Then the app departs — the scenario moves to `CREATED`, which stops the app's
     * only activity — and the departure's `reconcile` leaves exactly one entry, the
     * incoming one.
     */
    @Test
    fun aSwitchFromTheForegroundLeavesTheActivityUpUntilDeparture() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Vault)
        LauncherIdentity.reconcile(ctx)
        val throughAlias = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(LauncherIdentity.Vault.component(ctx))

        ActivityScenario.launch<MainActivity>(throughAlias).use { scenario ->
            scenario.onActivity { LauncherIdentity.switchTo(it, LauncherIdentity.MusicPlayer) }
            Thread.sleep(2_000)
            assertEquals(
                "the switch finished the activity that made it",
                Lifecycle.State.RESUMED,
                scenario.state,
            )

            scenario.moveToState(Lifecycle.State.CREATED)
            val entries = launcherEntries()
            assertEquals("launcher entries after departure: $entries", 1, entries.size)
            assertEquals(ctx.packageName + LauncherIdentity.MusicPlayer.aliasSuffix, entries.single())
        }
    }

    // --------------------------------------------------------------------- 10.3

    /**
     * **The two-icons window, driven rather than reasoned about.**
     *
     * A process death after a switch's enable and before any departure leaves two
     * aliases enabled — the phone advertising the vault beside the identity chosen to
     * avoid it. The next start's [LauncherIdentity.admit] must **not** repair it:
     * that start came through one of these aliases, and disabling it would close the
     * launch. The next departure's [LauncherIdentity.reconcile] must.
     */
    @Test
    fun aSwitchInterruptedBeforeItsDisableIsRepairedAtTheNextDeparture() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Vault)
        LauncherIdentity.reconcile(ctx)

        // The half-switch: the preference and the enable, and then the process dies.
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)
        assertEquals("the half-switch did not leave two entries", 2, launcherEntries().size)

        // What every start does: nothing disabled.
        LauncherIdentity.admit(ctx)
        assertEquals("the start-time pass disabled an entry", 2, launcherEntries().size)

        // What every departure does.
        LauncherIdentity.reconcile(ctx)
        val entries = launcherEntries()
        assertEquals("reconcile did not repair the half-switch: $entries", 1, entries.size)
        assertEquals(ctx.packageName + LauncherIdentity.Calculator.aliasSuffix, entries.single())
    }

    /**
     * And the other half of the same failure: a death **before** the enable, leaving
     * the preference pointing at an alias no launcher is showing. The start enables it
     * and keeps the one it came through; the departure removes that one.
     */
    @Test
    fun aSwitchInterruptedBeforeItsEnableIsRepairedAtTheNextStart() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Vault)
        LauncherIdentity.reconcile(ctx)
        GridPreference.setIdentity(ctx, LauncherIdentity.MusicPlayer)

        LauncherIdentity.admit(ctx)
        assertEquals(
            setOf(LauncherIdentity.Vault, LauncherIdentity.MusicPlayer)
                .map { ctx.packageName + it.aliasSuffix }.toSet(),
            launcherEntries().toSet(),
        )

        LauncherIdentity.reconcile(ctx)
        assertEquals(
            ctx.packageName + LauncherIdentity.MusicPlayer.aliasSuffix,
            launcherEntries().single(),
        )
    }

    // --------------------------------------------------------------------- 10.4

    /**
     * Reconciling a device already in the right state writes nothing, so a launcher
     * does not redraw its entry on every departure. Component state is the
     * observable: an unnecessary write would move a component off
     * `COMPONENT_ENABLED_STATE_DEFAULT`, and neither a second reconcile nor an
     * `admit` changes anything.
     */
    @Test
    fun reconcileIsIdempotent() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)
        LauncherIdentity.reconcile(ctx)
        val settled = states()

        LauncherIdentity.reconcile(ctx)
        assertEquals("a settled device was written to", settled, states())

        LauncherIdentity.admit(ctx)
        assertEquals("a settled device was written to", settled, states())
        assertEquals(1, launcherEntries().size)
    }

    // --------------------------------------------------------------------- 10.5

    /**
     * Recents follows the identity. Read back from the platform's own task record,
     * which is where the switcher reads it — not from the value that was passed in.
     */
    @Test
    fun recentsCarriesTheChosenIdentity() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.MusicPlayer)

        ActivityScenario.launch(MainActivity::class.java).use {
            val tasks = ctx.getSystemService(ActivityManager::class.java).appTasks
            val label = tasks.firstNotNullOfOrNull { task ->
                @Suppress("DEPRECATION")
                task.taskInfo?.taskDescription?.label
            }
            assertNotNull("no task description was set", label)
            assertEquals(ctx.getString(LauncherIdentity.MusicPlayer.label), label)
        }
    }

    // --------------------------------------------------------------------- 10.6

    /**
     * **The documented boundary, asserted rather than assumed.** The application
     * label is what a running import's notification carries, what Settings → Apps
     * shows, and what the app-info page shows. An alias label does not reach it.
     *
     * This test records the leak; it does not fail on it. If it ever starts failing,
     * something changed `<application android:label>` — which `FORMAT.md` §10 and
     * `LauncherIdentityScanTest` both say must not happen quietly.
     */
    @Test
    fun theApplicationLabelDoesNotFollowTheIdentity() {
        val real = pm.getApplicationLabel(ctx.applicationInfo).toString()

        LauncherIdentity.entries.forEach { identity ->
            LauncherIdentity.switchTo(ctx, identity)
            assertEquals(
                "the application label changed with the identity",
                real,
                pm.getApplicationLabel(ctx.applicationInfo).toString(),
            )
        }
        // Not pinned to a literal: `LauncherIdentityScanTest` owns what the label may
        // be. What this asserts is that switching identity does not move it — and
        // that it is not one of the identities, which is the failure that would put
        // an identity's name into Settings and the permission dialogs.
        LauncherIdentity.entries.forEach {
            assertNotEquals(
                "the application label is an identity's own name",
                ctx.getString(it.label).lowercase(),
                real.lowercase(),
            )
        }
    }

    // --------------------------------------------------------------------- 10.7

    /**
     * Every alias is a route to the one activity and to nothing else. There is no
     * second destination behind any of them — the cover front-ends are P2-24's, and
     * until then an alias opens the vault's unlock screen directly.
     */
    @Test
    fun everyAliasRoutesToTheOneActivity() {
        LauncherIdentity.entries.forEach { identity ->
            val info = pm.getActivityInfo(identity.component(ctx), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(
                "${identity.name} does not target MainActivity",
                MainActivity::class.java.name,
                info.targetActivity,
            )
            assertTrue("${identity.name} is not exported", info.exported)
        }
    }

    /** And the activity itself is not an entry point. */
    @Test
    fun theActivityIsNotItselfALauncherEntry() {
        val activity = ComponentName(ctx, MainActivity::class.java)
        assertTrue(
            "MainActivity is a launcher entry",
            launcherEntries().none { it == activity.className },
        )
        assertTrue(
            "MainActivity is exported",
            !pm.getActivityInfo(activity, PackageManager.MATCH_DISABLED_COMPONENTS).exported,
        )
    }

    // --------------------------------------------------------------------- 10.8

    /**
     * The choice is in `shared_prefs/`, so it outlives the process that made it —
     * and every component state is derived from it at the next start, which is the
     * same property that makes it outlive a reboot and an app update. Neither of
     * those replays a switch; the start runs [LauncherIdentity.admit] and the next
     * departure [LauncherIdentity.reconcile].
     */
    @Test
    fun theChoiceOutlivesTheProcessThatMadeIt() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)

        // A fresh handle on the same store is what a new process gets.
        val reread = LauncherIdentity.of(
            ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
                .getString("identity", null),
        )
        assertEquals(LauncherIdentity.Calculator, reread)

        // Stored by name and not by ordinal — the wrong icon on a home screen is
        // what an ordinal would cost when an identity is inserted later.
        assertEquals(
            "Calculator",
            ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
                .getString("identity", null),
        )
    }
}
