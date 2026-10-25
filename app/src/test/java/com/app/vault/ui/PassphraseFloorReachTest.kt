package com.app.vault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the floor reaches **exactly** the screens it is meant to — task 5.9, and the new
 * capability's "the floor does not vary by screen".
 *
 * Two failures this catches, and they fail in opposite directions.
 *
 * **A screen that quietly loses its meter.** The owner's instruction was that every screen
 * setting a passphrase carries the floor, because "it would be strange and misleading if
 * only a few of the screens had a minimum security restriction and the others didnt". A
 * refactor that drops one leaves the app enforcing different rules in different places,
 * which is the exact thing that was asked against.
 *
 * **A screen that gains one it must not have.** Measuring a passphrase that already exists
 * tells the user nothing they can act on, and on the lock screen it would leak a property
 * of a stored credential to whoever is holding the phone.
 *
 * JVM scope: both are properties of the source.
 */
class PassphraseFloorReachTest {

    /** The seven screens that *set* a passphrase — `passphrase-strength-meter`, D5. */
    private val settingScreens = listOf(
        "OnboardingSteps.kt",      // first-run passphrase step
        "VaultGate.kt",            // setup on the lock-screen path
        "VaultListScreen.kt",      // vault creation, own passphrase
        "RecoveryKit.kt",          // recovery and backup restore
        "ArchiveImportScreen.kt",  // backup import, a list not on this phone
        "PanicScreen.kt",          // panic phrase enrolment and change
        "DecoyScreen.kt",          // decoy creation
    )

    /**
     * Files holding a passphrase field that only ever *verifies* one. `ExportScreen`'s two
     * fields re-authenticate an export; the rest sit beside setting fields in files already
     * listed above, which is why this list is short and why the meter lives inside
     * `PanicScreen`'s `PhrasePair` rather than beside each of its six fields.
     */
    private val verifyOnlyScreens = listOf("ExportScreen.kt")

    private fun uiDir(): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault/ui"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the ui package from ${File(".").absolutePath}")
    }

    private fun source(name: String): String =
        uiDir().walkTopDown().single { it.name == name }.readText()

    private fun withoutComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    private fun drawsMeter(name: String): Boolean =
        withoutComments(source(name)).contains("PassphraseStrengthMeter(")

    @Test
    fun everyScreenThatSetsAPassphraseDrawsTheMeter() {
        val missing = settingScreens.filterNot { drawsMeter(it) }
        assertTrue(
            "these screens set a passphrase and must show the floor: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun noScreenThatOnlyChecksAPassphraseDrawsTheMeter() {
        val extra = verifyOnlyScreens.filter { drawsMeter(it) }
        assertTrue(
            "these screens only verify an existing passphrase and must not measure it: $extra",
            extra.isEmpty(),
        )
    }

    @Test
    fun theMeterIsDrawnNowhereElse() {
        val drawing = uiDir().walkTopDown()
            .filter { it.extension == "kt" }
            // Its own declaration file, which names it by definition.
            .filterNot { it.name == "PassphraseStrengthMeter.kt" }
            .filter { withoutComments(it.readText()).contains("PassphraseStrengthMeter(") }
            .map { it.name }
            .toList()
            .sorted()
        assertEquals(
            "the meter belongs on the seven setting screens and nowhere else",
            settingScreens.sorted(),
            drawing,
        )
    }

    /**
     * The floor's numbers live in `PassphraseStrength` and are stated to the user through
     * `PassphraseRequirement`. A screen that spelled out "15" or reached for the constant
     * itself is a screen that can drift from the others.
     */
    @Test
    fun noScreenStatesAThresholdOfItsOwn() {
        for (name in settingScreens) {
            val code = withoutComments(source(name))
            for (forbidden in listOf("MIN_CODE_POINTS", "MIN_SCORE", "MIN_PASSPHRASE_BYTES")) {
                assertTrue(
                    "$name names $forbidden directly — the requirement text is PassphraseRequirement",
                    !code.contains(forbidden),
                )
            }
        }
    }

    /** The old floor's sentence, in case a screen is ever restored from an older copy. */
    @Test
    fun noScreenStillQuotesTheOldFloor() {
        for (file in uiDir().walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            assertTrue(
                "${file.name} still tells the user to use at least 8 characters",
                !text.contains("at least 8 characters") && !text.contains("At least 8 characters"),
            )
        }
    }
}
