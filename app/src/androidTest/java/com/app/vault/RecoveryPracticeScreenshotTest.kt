package com.app.vault

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BiometricLayout
import com.app.vault.ui.BiometricState
import com.app.vault.ui.KitCheckFraming
import com.app.vault.ui.KitCheckScreen
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.PassphrasePracticeScreen
import com.app.vault.ui.PracticeNewPassphraseScreen
import com.app.vault.ui.PracticeState
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.theme.VaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * `recovery-practice` 9.2: the practice screen (empty, wrong with the panel, busy), the new
 * passphrase screen, the kit check in first run's frame and in a vault's own (empty and one
 * wrong), and the drawer's Extra Security row on and off — each in dark, light, dark at the
 * largest font scale, and German. Composed directly; no vault is made. Captures from
 * `uiAutomation`, written with `dd` to [DIR].
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPracticeScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val mnemonic = Mnemonic.fromResources(ctx)
    private val words = mnemonic.encode(ByteArray(Mnemonic.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) })
    private val fingerprint = BiometricWrap.practiceWords(BiometricLayout.Fingerprint)

    private companion object {
        const val DIR = "/data/local/tmp/recovery-practice"
    }

    private class Variant(val name: String, val theme: ThemeChoice, val scale: Float, val tag: String?)

    private val variants = listOf(
        Variant("dark", ThemeChoice.Dark, 1f, null),
        Variant("light", ThemeChoice.Light, 1f, null),
        Variant("dark-font2", ThemeChoice.Dark, 2f, null),
        Variant("de", ThemeChoice.Dark, 1f, "de"),
    )

    private fun shot(name: String, content: @Composable () -> Unit, act: (Variant) -> Unit = {}) {
        variants.forEach { v ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    activity.setContent {
                        VaultTheme(v.theme) {
                            val base = LocalConfiguration.current
                            val config = Configuration(base).apply {
                                v.tag?.let { setLocales(LocaleList.forLanguageTags(it)) }
                            }
                            val context = LocalContext.current.createConfigurationContext(config)
                            CompositionLocalProvider(
                                LocalDensity provides Density(LocalDensity.current.density, v.scale),
                                LocalConfiguration provides config,
                                LocalContext provides context,
                            ) { content() }
                        }
                    }
                }
                compose.waitForIdle()
                act(v)
                compose.waitForIdle()
                Thread.sleep(700)
                val png = java.io.ByteArrayOutputStream().also {
                    instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                save("$name-${v.name}.png", png)
            }
        }
    }

    private fun save(name: String, bytes: ByteArray) {
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, never `tee` — CLAUDE.md rule 21.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    // --- practice ----------------------------------------------------------------------------

    @Test fun practiceEmpty() = shot("01-practice-empty", {
        PassphrasePracticeScreen(PracticeState(), fingerprint, {}, {}, {})
    })

    @Test fun practiceWrong() = shot("02-practice-wrong", {
        val state = PracticeState().apply {
            entry = TextFieldValue("not-my-passphrase")
            markWrong()
        }
        PassphrasePracticeScreen(state, fingerprint, {}, {}, {})
    })

    @Test fun practiceBusy() = shot("03-practice-busy", {
        val state = PracticeState().apply {
            entry = TextFieldValue("checking-this-one")
            checking = true
        }
        PassphrasePracticeScreen(state, fingerprint, {}, {}, {})
    })

    @Test fun newPassphrase() = shot("04-new-passphrase", {
        PracticeNewPassphraseScreen(PracticeState(), fingerprint, {}, {})
    })

    @Test fun newPassphraseConfirmNeeded() = shot("05-new-passphrase-confirm-needed", {
        val state = PracticeState().apply {
            draft.new = "lantern quarry obsidian meadow"
            draft.again = "lantern quarry obsidian meadow"
            confirmNeeded = true
        }
        PracticeNewPassphraseScreen(state, fingerprint, {}, {})
    })

    // --- the kit check -----------------------------------------------------------------------

    @Composable
    private fun firstRunCheck() = OnboardingFrame(step = Onboarding.Step.Kit, onBack = {}) {
        KitCheckScreen(mnemonic, words, {}, {}, KitCheckFraming.Onboarding)
    }

    @Composable
    private fun vaultCheck() = KitCheckScreen(mnemonic, words, {}, {}, KitCheckFraming.Standalone)

    private fun oneWrong(v: Variant) {
        val asked = KitCheckWalk.asked(compose)
        val other = listOf("zoo", "abandon").first { it != words[asked[1] - 1] }
        val label = AppLanguage.localized(ctx, LocaleList.forLanguageTags(v.tag ?: "en")).getString(R.string.kit_check_action)
        KitCheckWalk.enter(compose, words, wrong = asked[1] to other, checkLabel = label)
    }

    @Test fun kitCheckFirstRunEmpty() = shot("06-kit-check-first-run-empty", { firstRunCheck() })

    @Test fun kitCheckFirstRunOneWrong() = shot("07-kit-check-first-run-one-wrong", { firstRunCheck() }, ::oneWrong)

    @Test fun kitCheckVaultEmpty() = shot("08-kit-check-vault-empty", { vaultCheck() })

    @Test fun kitCheckVaultOneWrong() = shot("09-kit-check-vault-one-wrong", { vaultCheck() }, ::oneWrong)

    // --- the drawer --------------------------------------------------------------------------

    @Composable
    private fun drawer(on: Boolean) = VaultDrawerSheet(
        owned = false,
        trialDaysRemaining = 12,
        trialStarted = true,
        onPurchase = {},
        currentIdentity = LauncherIdentity.Vault,
        onIdentity = {},
        biometricState = BiometricState.On,
        onBiometric = {},
        faceDownAvailable = true,
        faceDownOn = false,
        faceDownLockAfter = 0L,
        onFaceDown = {},
        decoySetUp = false,
        onSetUpDecoy = {},
        onPanicCredential = {},
        onIntruderCapture = {},
        onExport = {},
        listKitViewsLeft = 3,
        extraSecurityOn = on,
        currentAppearance = ThemeChoice.Dark,
        onChooseAppearance = {},
    )

    private fun toTheRow(@Suppress("UNUSED_PARAMETER") v: Variant) {
        val label = compose.onAllNodes(hasText("Extra", substring = true)).fetchSemanticsNodes()
        if (label.isNotEmpty()) runCatching { compose.onNode(hasText("Extra", substring = true)).performScrollTo() }
    }

    @Test fun drawerOn() = shot("10-drawer-extra-security-on", { drawer(true) }, ::toTheRow)

    @Test fun drawerOff() = shot("11-drawer-extra-security-off", { drawer(false) }, ::toTheRow)
}
