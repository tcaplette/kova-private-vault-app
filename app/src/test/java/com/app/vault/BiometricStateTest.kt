package com.app.vault

import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
import androidx.biometric.BiometricManager.BIOMETRIC_STATUS_UNKNOWN
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import com.app.vault.ui.BiometricLayout
import com.app.vault.ui.BiometricState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The biometric state table — `unavailable-capability-states` — and the layout table —
 * `biometric-unlock-screen` — one assertion per row.
 *
 * The emulator cannot present a weak-only biometric, a security-update state, a missing
 * sensor or a face, so both tables are asserted here, where none of them is needed.
 *
 * JVM scope: pure functions of three and four values.
 */
class BiometricStateTest {

    private fun state(strong: Int, weak: Int, enabled: Boolean = false) =
        BiometricWrap.state(strong, weak, enabled)

    @Test
    fun aStrongBiometricIsOnOrOffByTheWrap() {
        assertEquals(BiometricState.On, state(BIOMETRIC_SUCCESS, BIOMETRIC_SUCCESS, enabled = true))
        assertEquals(BiometricState.Off, state(BIOMETRIC_SUCCESS, BIOMETRIC_SUCCESS, enabled = false))
        // WEAK is any for this row.
        assertEquals(BiometricState.Off, state(BIOMETRIC_SUCCESS, BIOMETRIC_ERROR_NO_HARDWARE))
    }

    /** WEAK is any for this row: a weak biometric beside it is the greyed row's to explain. */
    @Test
    fun anEmptyStrongSensorIsNotSetUp() {
        assertEquals(BiometricState.NotSetUp, state(BIOMETRIC_ERROR_NONE_ENROLLED, BIOMETRIC_SUCCESS))
        assertEquals(BiometricState.NotSetUp, state(BIOMETRIC_ERROR_NONE_ENROLLED, BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(BiometricState.NotSetUp, state(BIOMETRIC_ERROR_NONE_ENROLLED, BIOMETRIC_ERROR_NO_HARDWARE))
    }

    @Test
    fun aKnownVulnerabilityNeedsAnUpdate() {
        assertEquals(BiometricState.NeedsUpdate, state(BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED, BIOMETRIC_SUCCESS))
        assertEquals(BiometricState.NeedsUpdate, state(BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED, BIOMETRIC_ERROR_NO_HARDWARE))
    }

    @Test
    fun onlyAWeakBiometricIsNotSecureEnough() {
        assertEquals(BiometricState.NotSecureEnough, state(BIOMETRIC_ERROR_NO_HARDWARE, BIOMETRIC_SUCCESS))
        assertEquals(BiometricState.NotSecureEnough, state(BIOMETRIC_ERROR_NO_HARDWARE, BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    /**
     * The ordering the decision depends on: a weak-only phone may answer STRONG with
     * `HW_UNAVAILABLE`, and must still read as NotSecureEnough, not NotAvailableNow.
     */
    @Test
    fun theWeakRowIsCheckedBeforeHardwareUnavailable() {
        assertEquals(BiometricState.NotSecureEnough, state(BIOMETRIC_ERROR_HW_UNAVAILABLE, BIOMETRIC_SUCCESS))
        assertEquals(BiometricState.NotSecureEnough, state(BIOMETRIC_ERROR_HW_UNAVAILABLE, BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(BiometricState.NotAvailableNow, state(BIOMETRIC_ERROR_HW_UNAVAILABLE, BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(BiometricState.NotAvailableNow, state(BIOMETRIC_ERROR_HW_UNAVAILABLE, BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun everythingElseIsNotSupported() {
        assertEquals(BiometricState.NotSupported, state(BIOMETRIC_ERROR_NO_HARDWARE, BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(BiometricState.NotSupported, state(BIOMETRIC_ERROR_UNSUPPORTED, BIOMETRIC_ERROR_UNSUPPORTED))
        assertEquals(BiometricState.NotSupported, state(BIOMETRIC_STATUS_UNKNOWN, BIOMETRIC_STATUS_UNKNOWN))
    }

    @Test
    fun everyStateHasItsWords() {
        // Every state with a live row has its line; the two without one have none.
        BiometricState.entries.forEach {
            val hasLiveRow = it != BiometricState.NotSecureEnough && it != BiometricState.NotSupported
            assertEquals("$it's live line", hasLiveRow, BiometricWrap.liveLine(it) != null)
        }
    }

    // --- the layout — `biometric-unlock-screen`, one assertion per row -----------

    private fun layout(state: BiometricState, fingerprint: Boolean, face: Boolean, allStrong: Boolean?) =
        BiometricWrap.layout(state, fingerprint, face, allStrong)

    /** Row 1: nothing strong is None whatever the features and names say. */
    @Test
    fun nothingStrongIsNone() {
        listOf(BiometricState.NotSecureEnough, BiometricState.NotSupported).forEach { s ->
            listOf(true, false).forEach { fp ->
                listOf(true, false).forEach { face ->
                    listOf(true, false, null).forEach { strong ->
                        assertEquals(BiometricLayout.None, layout(s, fp, face, strong))
                    }
                }
            }
        }
    }

    /** Every state in which a strong biometric is present takes its rows from the features. */
    private val strongPresent = listOf(
        BiometricState.On, BiometricState.Off, BiometricState.NotSetUp,
        BiometricState.NotAvailableNow, BiometricState.NeedsUpdate,
    )

    /** Row 2: both sensors, every sensor strong — Pixel 8. */
    @Test
    fun bothSensorsAllStrongIsBoth() = strongPresent.forEach {
        assertEquals(BiometricLayout.Both, layout(it, fingerprint = true, face = true, allStrong = true))
    }

    /** Row 3: both sensors, a weak one among them — Samsung, Pixel 7 — or API 30, unknown. */
    @Test
    fun bothSensorsNotAllStrongIsFingerprint() = strongPresent.forEach {
        assertEquals(BiometricLayout.Fingerprint, layout(it, fingerprint = true, face = true, allStrong = false))
        assertEquals(BiometricLayout.Fingerprint, layout(it, fingerprint = true, face = true, allStrong = null))
    }

    /** Row 4: a fingerprint and no face, whatever the names say. */
    @Test
    fun fingerprintOnlyIsFingerprint() = strongPresent.forEach { s ->
        listOf(true, false, null).forEach {
            assertEquals(BiometricLayout.Fingerprint, layout(s, fingerprint = true, face = false, allStrong = it))
        }
    }

    /** Row 5: a face and no fingerprint — Pixel 4. */
    @Test
    fun faceOnlyIsFace() = strongPresent.forEach { s ->
        listOf(true, false, null).forEach {
            assertEquals(BiometricLayout.Face, layout(s, fingerprint = false, face = true, allStrong = it))
        }
    }

    /** Row 6: a strong biometric that is neither declared feature. */
    @Test
    fun neitherFeatureIsUnnamed() = strongPresent.forEach { s ->
        listOf(true, false, null).forEach {
            assertEquals(BiometricLayout.Unnamed, layout(s, fingerprint = false, face = false, allStrong = it))
        }
    }

    /** Each layout's facts, in order: How, Fingerprint only (Both), Turns off, Passphrase. */
    @Test
    fun eachLayoutHasItsFacts() {
        val how = "How you open the vault"
        val only = "To use only your fingerprint"
        val off = "It turns off if your biometrics change"
        val passphrase = "Your passphrase always opens the vault"
        fun headings(l: BiometricLayout) = BiometricWrap.facts(l).map { TestWords.text(it.first) }
        assertEquals(listOf(how, off, passphrase), headings(BiometricLayout.Fingerprint))
        assertEquals(listOf(how, off, passphrase), headings(BiometricLayout.Face))
        assertEquals(listOf(how, only, off, passphrase), headings(BiometricLayout.Both))
        assertEquals(listOf(how, off, passphrase), headings(BiometricLayout.Unnamed))
        assertEquals(listOf(passphrase), headings(BiometricLayout.None))
        assertEquals(
            "Adding or removing a face or fingerprint in your device's settings turns this off. Unlock with " +
                "your passphrase, then turn it back on from Biometric unlock in the menu.",
            TestWords.text(BiometricWrap.facts(BiometricLayout.Both)[2].second),
        )
    }

    /** The reason sentence is drawn only where the hardware exists. */
    @Test
    fun theUnavailableLineSaysWhyOnlyWhereTheHardwareExists() {
        assertEquals("Not supported on this device.", TestWords.text(BiometricWrap.unavailableLine(face = true, hardwarePresent = false)))
        assertEquals("Not supported on this device.", TestWords.text(BiometricWrap.unavailableLine(face = false, hardwarePresent = false)))
        assertEquals(
            "Not supported on this device. Its face unlock isn't secure enough to protect encrypted data.",
            TestWords.text(BiometricWrap.unavailableLine(face = true, hardwarePresent = true)),
        )
        assertEquals(
            "Not supported on this device. Its fingerprint sensor isn't secure enough to protect encrypted data.",
            TestWords.text(BiometricWrap.unavailableLine(face = false, hardwarePresent = true)),
        )
    }
}
