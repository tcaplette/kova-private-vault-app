package com.app.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The cover front ends on a device — P2-24.
 *
 * `CoverTriggerTest` and `CalculatorTest` own the arithmetic; this owns what only a
 * device has: the launcher entries, the preference store, and the rate limiter
 * behaving across real writes.
 *
 * **No `MainActivity` is launched anywhere in this file**, which is P2-23's finding
 * applied rather than rediscovered: hosting anything in the real activity runs its
 * `onCreate`, and doing that repeatedly left a Keystore alias behind and broke
 * `BiometricWrapTest` in the full suite while every class passed alone.
 *
 * **Restores everything it changes.** The triggers, the identity and the covers' own
 * state are all shared with the app the developer is using, and a test that left a
 * cover enabled would change what their home screen shows.
 */
@RunWith(AndroidJUnit4::class)
class CoverDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm: PackageManager get() = ctx.packageManager

    private lateinit var identityBefore: LauncherIdentity
    private val triggersBefore = mutableMapOf<Cover, Trigger?>()
    private val holdsBefore = mutableMapOf<Cover, Int?>()
    private var calcPatternBefore: Trigger.Pattern? = null

    @Before
    fun remember() {
        identityBefore = GridPreference.identity(ctx)
        Cover.entries.forEach {
            triggersBefore[it] = CoverTrigger.triggerFor(ctx, it)
            holdsBefore[it] = CoverTrigger.holdFor(ctx, it)
        }
        calcPatternBefore = CoverTrigger.patternFor(ctx, Cover.Calculator)
    }

    @After
    fun restore() {
        Cover.entries.forEach { cover ->
            val before = triggersBefore[cover]
            CoverTrigger.clear(ctx, cover)
            when {
                // `calculator-dots-methods`: whichever of its three methods it had.
                cover == Cover.Calculator && before == null ->
                    calcPatternBefore?.let { CoverTrigger.set(ctx, cover, it, area = holdsBefore[cover]) }
                cover == Cover.Calculator -> CoverTrigger.set(ctx, cover, before!!, dots = calcPatternBefore)
                before != null -> CoverTrigger.set(ctx, cover, before, area = holdsBefore[cover])
            }
            CoverTrigger.clearAttempts(ctx, cover)
        }
        LauncherIdentity.switchTo(ctx, identityBefore)
    }

    private fun launcherEntries(): List<String> =
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(ctx.packageName),
            0,
        ).map { it.activityInfo.name }

    private val calcTrigger = Trigger.Keys(listOf("7", "3", "sin"))

    // --- the identities ------------------------------------------------------

    /**
     * Six identities now, and still exactly one launcher entry once the departure's
     * `reconcile` has run — `identity-switch-done-step`: the switch itself only enables.
     */
    @Test
    fun exactlyOneLauncherEntryAcrossAllSixIdentities() {
        LauncherIdentity.entries.forEach { identity ->
            identity.cover?.let { CoverTrigger.set(ctx, it, triggerFor(it), area = holdFor(it)) }
            LauncherIdentity.switchTo(ctx, identity)
            LauncherIdentity.reconcile(ctx)

            val entries = launcherEntries()
            assertEquals("entries after ${identity.name}: $entries", 1, entries.size)
            assertEquals(ctx.packageName + identity.aliasSuffix, entries.single())
        }
    }

    private fun triggerFor(cover: Cover): Trigger = when (cover) {
        Cover.Calculator -> calcTrigger
        // `pattern-cover-trigger`. Any five distinct nodes is a valid pattern; this
        // one is arbitrary and used only as "a trigger this cover will accept".
        Cover.MusicPlayer, Cover.VoiceRecorder -> Trigger.Pattern(listOf(0, 4, 8, 2, 6))
    }

    /** `cover-hold-dots` D5. The pattern covers need their hold area; the calculator has none. */
    private fun holdFor(cover: Cover): Int? = if (cover == Cover.Calculator) null else 0

    /**
     * **A cover identity with no trigger is not presentable**, and the app resolves
     * back to the vault's own rather than putting an unopenable cover on a home
     * screen. Clearing app data can leave the identity preference and the trigger in
     * two different files with no ordering guarantee between them.
     */
    @Test
    fun aStoredCoverIdentityWithNoTriggerResolvesToTheDefault() {
        CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)
        assertEquals(LauncherIdentity.Calculator, LauncherIdentity.resolved(ctx))

        CoverTrigger.clear(ctx, Cover.Calculator)
        assertEquals(
            "an unopenable cover was left presentable",
            LauncherIdentity.Default,
            LauncherIdentity.resolved(ctx),
        )

        LauncherIdentity.reconcile(ctx)
        assertEquals(
            ctx.packageName + LauncherIdentity.Default.aliasSuffix,
            launcherEntries().single(),
        )
    }

    /**
     * ***An install carrying a `Transport` or `Phrase` string updates into the same
     * failure mode as no trigger at all*** — `pattern-cover-trigger`'s migration (D5).
     *
     * Written with the raw preference key and the old encoded value, because that is
     * what an install that set a trigger before this change actually has on disk; the
     * shape `Trigger.decode` used to parse it into is gone from the build reading it
     * now. This is the one direction that cannot strand anybody: the identity falls
     * back to the vault's own rather than leaving an enabled launcher entry nothing can
     * open.
     */
    @Test
    fun anInstallCarryingAPreMigrationTriggerResolvesToTheDefault() {
        val covers = ctx.getSharedPreferences("covers", Context.MODE_PRIVATE)
        covers.edit().putString(Cover.MusicPlayer.name, "Next\u001FNext\u001FShuffle\u001FPlayPause").commit()
        LauncherIdentity.switchTo(ctx, LauncherIdentity.MusicPlayer)

        assertNull(
            "an unparseable stored value read back as a trigger",
            CoverTrigger.triggerFor(ctx, Cover.MusicPlayer),
        )
        assertFalse(CoverTrigger.isConfigured(ctx, Cover.MusicPlayer))
        assertEquals(
            "the cover is unselectable, so the identity must not resolve to it",
            LauncherIdentity.Default,
            LauncherIdentity.resolved(ctx),
        )

        LauncherIdentity.reconcile(ctx)
        val entries = launcherEntries()
        assertEquals("more than one launcher entry after reconcile", 1, entries.size)
        assertEquals(
            "the vault's own identity is not what reconcile left enabled",
            ctx.packageName + LauncherIdentity.Default.aliasSuffix,
            entries.single(),
        )

        covers.edit().remove(Cover.MusicPlayer.name).commit()
    }

    /** Every alias still routes to the one activity — six of them now. */
    @Test
    fun everyAliasRoutesToTheOneActivity() {
        LauncherIdentity.entries.forEach { identity ->
            val info = pm.getActivityInfo(
                identity.component(ctx), PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals(
                "${identity.name} does not target MainActivity",
                MainActivity::class.java.name,
                info.targetActivity,
            )
        }
    }

    // --- the trigger ---------------------------------------------------------

    /**
     * **No default, and that is the whole design.** A cover with no trigger cannot be
     * enabled, and nothing about the build, the install or the device derives one.
     */
    @Test
    fun aCoverWithNoTriggerIsNotConfigured() {
        Cover.entries.forEach { cover ->
            CoverTrigger.clear(ctx, cover)
            assertNull("${cover.name} produced a trigger from nowhere", CoverTrigger.triggerFor(ctx, cover))
            assertFalse(CoverTrigger.isConfigured(ctx, cover))
        }
    }

    /** And a trigger below its floor is refused rather than stored. */
    @Test
    fun aTriggerBelowItsFloorIsRefused() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        assertFalse(CoverTrigger.set(ctx, Cover.Calculator, Trigger.Keys(listOf("1", "2"))))
        assertFalse(CoverTrigger.isConfigured(ctx, Cover.Calculator))
    }

    @Test
    fun aStoredTriggerRoundTripsThroughTheStore() {
        Cover.entries.forEach { cover ->
            val trigger = triggerFor(cover)
            assertTrue(CoverTrigger.set(ctx, cover, trigger, area = holdFor(cover)))
            assertEquals(trigger, CoverTrigger.triggerFor(ctx, cover))
        }
    }

    // --- `cover-hold-dots` D5: the hold area ---------------------------------------

    /** Every area round-trips with the pattern; the calculator stores none. */
    @Test
    fun theHoldAreaRoundTripsThroughTheStore() {
        val pattern = triggerFor(Cover.MusicPlayer)
        listOf(Cover.MusicPlayer, Cover.VoiceRecorder).forEach { cover ->
            (0 until HOLD_AREAS).forEach { area ->
                assertTrue(CoverTrigger.set(ctx, cover, pattern, area = area))
                assertEquals(area, CoverTrigger.holdFor(ctx, cover))
                assertTrue(CoverTrigger.isConfigured(ctx, cover))
            }
        }
        // `calculator-dots-methods` D1: a sequence with a hold is no method of the calculator's.
        CoverTrigger.clear(ctx, Cover.Calculator)
        assertFalse(CoverTrigger.set(ctx, Cover.Calculator, calcTrigger, area = 3))
        assertNull("the calculator stored a hold area", CoverTrigger.holdFor(ctx, Cover.Calculator))
    }

    // --- `calculator-dots-methods` D1: the calculator's three methods in the store ---------

    private val calcPattern = Trigger.Pattern(listOf(0, 4, 8, 2, 6))

    /** Each method stores exactly its own keys, reads back as itself, and is configured. */
    @Test
    fun eachCalculatorMethodRoundTripsThroughTheStore() {
        val covers = ctx.getSharedPreferences("covers", Context.MODE_PRIVATE)
        val calc = Cover.Calculator

        assertTrue(CoverTrigger.set(ctx, calc, calcTrigger))
        assertEquals(CalculatorMethod.Sequence, CoverTrigger.calculatorMethod(ctx))
        assertFalse(covers.contains(patternKey(calc)))
        assertFalse(covers.contains(holdKey(calc)))

        assertTrue(CoverTrigger.set(ctx, calc, calcTrigger, dots = calcPattern))
        assertEquals(CalculatorMethod.SequenceThenPattern, CoverTrigger.calculatorMethod(ctx))
        assertEquals(calcTrigger, CoverTrigger.triggerFor(ctx, calc))
        assertEquals(calcPattern, CoverTrigger.patternFor(ctx, calc))
        assertNull(CoverTrigger.holdFor(ctx, calc))
        assertTrue(CoverTrigger.isConfigured(ctx, calc))

        assertTrue(CoverTrigger.set(ctx, calc, calcPattern, area = 11))
        assertEquals(CalculatorMethod.HoldThenPattern, CoverTrigger.calculatorMethod(ctx))
        assertNull("the sequence survived a change of method", CoverTrigger.triggerFor(ctx, calc))
        assertEquals(calcPattern, CoverTrigger.patternFor(ctx, calc))
        assertEquals(11, CoverTrigger.holdFor(ctx, calc))
        assertTrue(CoverTrigger.isConfigured(ctx, calc))

        // Set up again as Button sequence: only the sequence is left.
        assertTrue(CoverTrigger.set(ctx, calc, calcTrigger))
        assertEquals(CalculatorMethod.Sequence, CoverTrigger.calculatorMethod(ctx))
        assertFalse("the pattern survived a change of method", covers.contains(patternKey(calc)))
        assertFalse("the hold survived a change of method", covers.contains(holdKey(calc)))
    }

    /** A combination that is no method is refused and stores nothing; stored values that fit none are not configured. */
    @Test
    fun aCalculatorCombinationThatIsNoMethodIsRefused() {
        val calc = Cover.Calculator
        CoverTrigger.clear(ctx, calc)
        assertFalse("a pattern with no hold", CoverTrigger.set(ctx, calc, calcPattern))
        assertFalse("a pattern, a hold and a second pattern", CoverTrigger.set(ctx, calc, calcPattern, area = 2, dots = calcPattern))
        assertFalse("a sequence, a hold and a pattern", CoverTrigger.set(ctx, calc, calcTrigger, area = 2, dots = calcPattern))
        assertFalse("a short second pattern", CoverTrigger.set(ctx, calc, calcTrigger, dots = Trigger.Pattern(listOf(0, 1, 2))))
        assertNull(CoverTrigger.calculatorMethod(ctx))
        assertFalse(CoverTrigger.isConfigured(ctx, calc))
        // A sequence and a hold on disk — the state no setup can produce.
        ctx.getSharedPreferences("covers", Context.MODE_PRIVATE).edit()
            .putString(calc.name, calcTrigger.encode()).putInt(holdKey(calc), 4).commit()
        assertNull(CoverTrigger.calculatorMethod(ctx))
        assertFalse(CoverTrigger.isConfigured(ctx, calc))
    }

    /** `clear` takes the calculator's pattern and hold; the sweep keeps them and takes a removed cover's. */
    @Test
    fun clearAndTheSweepTakeTheCalculatorsPattern() {
        val covers = ctx.getSharedPreferences("covers", Context.MODE_PRIVATE)
        assertTrue(CoverTrigger.set(ctx, Cover.Calculator, calcTrigger, dots = calcPattern))
        covers.edit().putString("Flashlightpattern", calcPattern.encode()).commit()
        CoverTrigger.sweepUnknownCovers(ctx)
        assertFalse(covers.contains("Flashlightpattern"))
        assertEquals("the sweep took a known cover's pattern", calcPattern, CoverTrigger.patternFor(ctx, Cover.Calculator))
        CoverTrigger.clear(ctx, Cover.Calculator)
        assertFalse("clear left the pattern behind", covers.contains(patternKey(Cover.Calculator)))
        assertNull(CoverTrigger.calculatorMethod(ctx))
    }

    /** No default area: a pattern cover with no area, or one out of range, is refused and not configured. */
    @Test
    fun aPatternCoverWithNoAreaIsNotConfigured() {
        val pattern = triggerFor(Cover.MusicPlayer)
        listOf(Cover.MusicPlayer, Cover.VoiceRecorder).forEach { cover ->
            CoverTrigger.clear(ctx, cover)
            listOf(null, -1, 12).forEach { area ->
                assertFalse("$cover stored with area $area", CoverTrigger.set(ctx, cover, pattern, area = area))
                assertFalse(CoverTrigger.isConfigured(ctx, cover))
            }
            // A pattern on disk with no area beside it — the state no setup can produce.
            ctx.getSharedPreferences("covers", Context.MODE_PRIVATE).edit()
                .putString(cover.name, pattern.encode()).commit()
            assertNotNull(CoverTrigger.triggerFor(ctx, cover))
            assertFalse("$cover configured with no area", CoverTrigger.isConfigured(ctx, cover))
        }
    }

    /** `clear` takes the area; the sweep keeps a known cover's and takes a removed one's. */
    @Test
    fun clearAndTheSweepTakeTheHoldArea() {
        val covers = ctx.getSharedPreferences("covers", Context.MODE_PRIVATE)
        CoverTrigger.set(ctx, Cover.MusicPlayer, triggerFor(Cover.MusicPlayer), area = 7)
        covers.edit().putInt("Flashlighthold", 2).commit()
        CoverTrigger.sweepUnknownCovers(ctx)
        assertFalse("a removed cover's area survived", covers.contains("Flashlighthold"))
        assertEquals("the sweep took a known cover's area", 7, CoverTrigger.holdFor(ctx, Cover.MusicPlayer))
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
        assertFalse("clear left the area behind", covers.contains(holdKey(Cover.MusicPlayer)))
        assertNull(CoverTrigger.holdFor(ctx, Cover.MusicPlayer))
    }

    /**
     * **Two independently configured installs do not open each other's cover.** The
     * device cannot hold two installs, so this asserts the property that makes it
     * true: the trigger comes only from what was stored, and a different stored value
     * is rejected.
     */
    @Test
    fun aDifferentTriggerDoesNotOpenThisCover() {
        CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
        assertEquals(
            Outcome.Rejected,
            CoverTrigger.attempt(ctx, Cover.Calculator, Trigger.Keys(listOf("1", "1", "1"))),
        )
        assertEquals(
            Outcome.Accepted,
            CoverTrigger.attempt(ctx, Cover.Calculator, calcTrigger),
        )
    }

    /** An unconfigured cover rejects everything, including a well-formed trigger. */
    @Test
    fun anUnconfiguredCoverAcceptsNothing() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        assertEquals(
            Outcome.Rejected,
            CoverTrigger.attempt(ctx, Cover.Calculator, calcTrigger),
        )
    }

    // --- the rate limiter ----------------------------------------------------

    /**
     * **Past the limit, the correct trigger reaches nothing** — and reaches the
     * unlock screen once the period elapses. Driven with an injected clock rather
     * than by waiting ten real minutes.
     */
    @Test
    fun theCorrectTriggerIsRefusedWhileRateLimitedAndAcceptedAfterwards() {
        CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)

        val start = 1_000_000L
        val wrong = Trigger.Keys(listOf("1", "1", "1"))
        repeat(Attempts.MAX_FAILURES) {
            assertEquals(
                Outcome.Rejected,
                CoverTrigger.attempt(ctx, Cover.Calculator, wrong, start + it),
            )
        }

        assertEquals(
            "the correct trigger was accepted while rate-limited",
            Outcome.Limited,
            CoverTrigger.attempt(ctx, Cover.Calculator, calcTrigger, start + 1_000),
        )

        assertEquals(
            "the lockout never expired",
            Outcome.Accepted,
            CoverTrigger.attempt(
                ctx, Cover.Calculator, calcTrigger, start + Attempts.LOCKOUT_MS + 10_000,
            ),
        )
    }

    /**
     * A rejected and a limited attempt are different values so the *caller* can be
     * held to treating them identically — but neither may ever be an acceptance,
     * which is the property that matters here.
     */
    @Test
    fun neitherRejectionNorLockoutEverOpensAnything() {
        CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
        listOf(Outcome.Rejected, Outcome.Limited).forEach {
            assertNotEquals(Outcome.Accepted, it)
        }
    }

    // --- what the covers keep ------------------------------------------------

    /**
     * ***The player's memory is plaintext, in the covers' own store, and reaches no
     * vault*** — `music-recorder-covers`.
     *
     * Asserted with **no session live**: the vault cannot be involved, because there
     * is no key to involve it with.
     */
    @Test
    fun thePlayersMemoryIsWrittenWithoutTheVault() {
        CoverStore.setLastPlayed(ctx, 4321L, 12_000L)
        assertEquals(4321L, CoverStore.lastTrackId(ctx))
        assertEquals(12_000L, CoverStore.lastPositionMs(ctx))
        assertFalse("the cover unlocked the vault", VaultSession.isUnlocked)
        CoverStore.clearLastPlayed(ctx)
        assertNull("a cleared memory still names a track", CoverStore.lastTrackId(ctx))
    }

    /**
     * ***A recording is a plaintext file in the recorder's own directory***, and
     * nowhere near `FORMAT.md` §2's.
     *
     * The file is created here rather than recorded, because what is being asserted is
     * *where the store puts it and what it keeps about it* — the microphone is
     * `CoverScreenTest`'s to drive.
     */
    @Test
    fun aRecordingLandsInTheRecordersOwnDirectoryInPlaintext() {
        val file = RecorderStore.newFile(ctx)
        file.writeBytes(ByteArray(64) { 7 })
        RecorderStore.add(ctx, file, durationMs = 1_500L)
        try {
            val listed = RecorderStore.recordings(ctx).firstOrNull { it.name == file.name }
            assertNotNull("the recording was not listed", listed)
            assertEquals(1_500L, listed!!.durationMs)
            assertEquals(64L, listed.sizeBytes)

            // Its own directory, under filesDir, and not the vault's own files.
            assertEquals(File(ctx.filesDir, "recordings"), file.parentFile)
            assertTrue("the file is not there", file.isFile)
            assertFalse("the recorder wrote a blob", File(ctx.filesDir, "blobs/" + file.name).exists())

            // Plaintext: what was written is what is on disk, byte for byte.
            assertTrue("the recording was not stored verbatim", file.readBytes().all { it == 7.toByte() })

            RecorderStore.rename(ctx, listed, "Kitchen")
            assertEquals(
                "Kitchen",
                RecorderStore.recordings(ctx).first { it.name == file.name }.title,
            )
        } finally {
            RecorderStore.recordings(ctx)
                .firstOrNull { it.name == file.name }
                ?.let { RecorderStore.delete(ctx, it) }
            file.delete()
        }
        assertFalse("delete left the file behind", file.exists())
    }

    /**
     * ***A cover this build does not have is swept out of storage***, rather than left
     * naming a disguise the install once wore — `music-recorder-covers`.
     *
     * Written with the raw preference names the removed covers used, because that is
     * what an updated install actually has on disk; the enum they came from is gone.
     */
    @Test
    fun aRemovedCoversTriggerIsSweptAndTheKnownOnesAreNot() {
        CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
        val covers = ctx.getSharedPreferences("covers", Context.MODE_PRIVATE)
        covers.edit()
            .putString("Flashlight", "5")
            .putInt("Flashlightfailures", 3)
            .putString("Notes", "SaveLongPresspelican")
            .commit()

        CoverTrigger.sweepUnknownCovers(ctx)

        assertFalse("a removed cover's trigger survived", covers.contains("Flashlight"))
        assertFalse("a removed cover's limiter survived", covers.contains("Flashlightfailures"))
        assertFalse("a removed cover's trigger survived", covers.contains("Notes"))
        assertNotNull(
            "the sweep took a cover this build does have",
            CoverTrigger.triggerFor(ctx, Cover.Calculator),
        )
    }

    /**
     * And the identity preference itself. `LauncherIdentity.of` already reads an
     * unknown name as the default; what this asserts is that the record is deleted
     * rather than left saying which cover the install used to wear.
     */
    @Test
    fun aRemovedCoversIdentityIsSweptAtReconcile() {
        ctx.getSharedPreferences("grid", Context.MODE_PRIVATE)
            .edit().putString("identity", "Flashlight").commit()

        LauncherIdentity.reconcile(ctx)

        assertFalse(
            "the stored identity still names a removed cover",
            ctx.getSharedPreferences("grid", Context.MODE_PRIVATE).contains("identity"),
        )
        assertEquals(LauncherIdentity.Default, LauncherIdentity.resolved(ctx))
    }
}
