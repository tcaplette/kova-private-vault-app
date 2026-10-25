package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-5's detection, as arithmetic — the Verify's "scripted reproduction", one per
 * event, with no emulator and no dependence on the device's real ADB or root state.
 *
 * [DeviceEvents.evaluate] is pure and uses only the `const val` event codes (inlined
 * at compile time, so this loads no native library), which is what lets the truth
 * table be checked here on the JVM. The on-device half — flushing into the log and
 * the digest — is `DeviceEventsDeviceTest`.
 */
class DeviceEventsTest {

    private val now = 1_700_000_000_000L

    private fun state(
        adb: Boolean = false,
        dev: Boolean = false,
        root: Boolean = false,
        fingerprint: String = "device-A",
        time: Long = now,
    ) = DeviceEvents.State(adb, dev, root, fingerprint, time)

    private fun baseline(
        adb: Boolean = false,
        dev: Boolean = false,
        root: Boolean = false,
        fingerprint: String = "device-A",
        high: Long = now,
    ) = DeviceEvents.Baseline(adb, dev, root, fingerprint, high)

    private fun evaluate(
        baseline: DeviceEvents.Baseline?,
        current: DeviceEvents.State,
        anchorPresent: Boolean = true,
        vaultExists: Boolean = true,
    ) = DeviceEvents.evaluate(baseline, current, anchorPresent, vaultExists)

    // --- 5.2 developer options / USB debugging ---------------------------------

    @Test fun developerOptionsNewlyEnabledFires() {
        assertEquals(
            setOf(TamperLog.DEV_OPTIONS_ENABLED),
            evaluate(baseline(dev = false), state(dev = true)),
        )
        assertEquals(
            setOf(TamperLog.DEV_OPTIONS_ENABLED),
            evaluate(baseline(adb = false), state(adb = true)),
        )
    }

    @Test fun developerOptionsStayingOnDoesNotReFire() {
        assertEquals(emptySet<Int>(), evaluate(baseline(dev = true), state(dev = true)))
        assertEquals(emptySet<Int>(), evaluate(baseline(adb = true), state(adb = true)))
    }

    // --- 5.3 root, both directions ---------------------------------------------

    @Test fun rootVerdictChangeFiresEitherDirection() {
        assertEquals(
            setOf(TamperLog.ROOT_STATUS_CHANGED),
            evaluate(baseline(root = false), state(root = true)),
        )
        assertEquals(
            setOf(TamperLog.ROOT_STATUS_CHANGED),
            evaluate(baseline(root = true), state(root = false)),
        )
    }

    @Test fun anUnchangedRootVerdictIsSilent() {
        assertEquals(emptySet<Int>(), evaluate(baseline(root = true), state(root = true)))
    }

    // --- 5.4 app-data clear ----------------------------------------------------

    @Test fun anchorPresentWithNoDataIsAClear() {
        assertEquals(
            setOf(TamperLog.DATA_CLEAR_DETECTED),
            evaluate(baseline = null, current = state(), anchorPresent = true, vaultExists = false),
        )
    }

    @Test fun noAnchorNoDataIsAFirstRun() {
        assertEquals(
            emptySet<Int>(),
            evaluate(baseline = null, current = state(), anchorPresent = false, vaultExists = false),
        )
    }

    @Test fun anchorPresentButVaultStillHereIsNotAClear() {
        assertEquals(
            emptySet<Int>(),
            evaluate(baseline = null, current = state(), anchorPresent = true, vaultExists = true),
        )
    }

    // --- 5.5 new-device restore ------------------------------------------------

    @Test fun aDifferingFingerprintFires() {
        assertEquals(
            setOf(TamperLog.NEW_DEVICE_RESTORE),
            evaluate(baseline(fingerprint = "device-A"), state(fingerprint = "device-B")),
        )
    }

    @Test fun aMissingAnchorWithABaselineFires() {
        // The baseline arrived but the device-bound anchor did not — a data restore.
        assertEquals(
            setOf(TamperLog.NEW_DEVICE_RESTORE),
            evaluate(baseline(fingerprint = "device-A"), state(fingerprint = "device-A"), anchorPresent = false),
        )
    }

    @Test fun sameDeviceWithAnchorDoesNotFire() {
        // The archive-import case: the baseline was written for this device at first
        // launch, the anchor exists, so no new-device event.
        assertEquals(emptySet<Int>(), evaluate(baseline(fingerprint = "device-A"), state(fingerprint = "device-A")))
    }

    // --- 5.6 clock rollback ----------------------------------------------------

    @Test fun aClockBelowTheHighMarkFires() {
        assertEquals(
            setOf(TamperLog.CLOCK_ROLLBACK),
            evaluate(baseline(high = now), state(time = now - DeviceEvents.CLOCK_TOLERANCE_MS - 1)),
        )
    }

    @Test fun aClockWithinToleranceIsSilent() {
        assertEquals(
            emptySet<Int>(),
            evaluate(baseline(high = now), state(time = now - DeviceEvents.CLOCK_TOLERANCE_MS + 1)),
        )
    }

    // --- one transition at a time ----------------------------------------------

    @Test fun aCleanRunFiresNothing() {
        assertEquals(emptySet<Int>(), evaluate(baseline(), state()))
    }
}
