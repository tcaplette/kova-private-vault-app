package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BiometricState
import com.app.vault.ui.PurchaseScreen
import com.app.vault.ui.ShareScreen
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `one-time-purchase` D7, on screen: the Purchase screen in every state, the home
 * drawer's row, and the send flow's refusal.
 *
 * `FLAG_SECURE` makes the app unscreenshottable, so it is cleared on a throwaway
 * `ComponentActivity` and the frame is taken by `uiAutomation` — the real composited
 * pixels, never a `View.draw` that can invent what is not there. Each shot also
 * asserts the words D7 specifies, so a regression fails here and not only in review.
 * Written to `/data/local/tmp/purchase-screen`.
 */
@RunWith(AndroidJUnit4::class)
class PurchaseScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val DIR = "/data/local/tmp/purchase-screen"
        const val PRICE = "$8.99"
    }

    private fun shot(name: String, content: @Composable () -> Unit, check: () -> Unit = {}) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { content() }
            }
            compose.waitForIdle()
            check()
            Thread.sleep(400)
            val png = java.io.ByteArrayOutputStream().also {
                instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            val ui = instr.uiAutomation
            ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
            // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
            // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
            val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
            drain(stdout)
        }
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun has(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Composable
    private fun Screen(
        owned: Boolean = false,
        pending: Boolean = false,
        started: Boolean = true,
        days: Int = 12,
        price: String? = PRICE,
        theme: ThemeChoice = ThemeChoice.Dark,
    ) {
        VaultTheme(theme) {
            PurchaseScreen(
                daysRemaining = days,
                owned = owned,
                pending = pending,
                trialStarted = started,
                price = price,
                onBuy = {},
                purchaseProblem = null,
                onBack = {},
            )
        }
    }

    @Test fun trial() = shot("01-trial", { Screen() }) {
        assertTrue(has("12 days left"))
        assertTrue(has("One payment. No subscription, nothing renews."))
        assertTrue(has("Buy for $PRICE"))
        assertTrue(has("opening shares sent to you"))
    }

    @Test fun notStarted() = shot("02-not-started", { Screen(started = false, days = 30) }) {
        assertTrue(has("Trial not started"))
        assertTrue(has("30 days, from the first thing you add"))
    }

    @Test fun ended() = shot("03-ended", { Screen(days = 0) }) {
        assertTrue(has("Trial ended"))
        assertTrue(has("Buy for $PRICE"))
    }

    @Test fun endedLight() = shot("03b-ended-light", { Screen(days = 0, theme = ThemeChoice.Light) })

    @Test fun priceNotYetKnown() = shot("04-no-price", { Screen(price = null) }) {
        assertTrue("never a price written into the app", has("Buy") && !has("$"))
    }

    @Test fun pending() = shot("05-pending", { Screen(pending = true) }) {
        assertTrue(has("Payment pending"))
        assertTrue(has("waiting for your payment to clear"))
        assertFalse("no second purchase", has("Buy"))
        assertFalse("nothing counting down", has("days left"))
    }

    @Test fun owned() = shot("06-owned", { Screen(owned = true) }) {
        assertTrue(has("Owned"))
        assertTrue(has("comes back if you reinstall"))
        assertFalse(has("Buy"))
    }

    @Test fun homeDrawer() = shot("07-home-drawer", {
        VaultTheme(ThemeChoice.Dark) {
            VaultDrawerSheet(
                owned = false,
                trialDaysRemaining = 12,
                trialStarted = true,
                onPurchase = {},
                currentIdentity = LauncherIdentity.Vault,
                onIdentity = {},
                biometricState = BiometricState.Off,
                onBiometric = {},
                faceDownAvailable = false,
                faceDownOn = false,
                faceDownLockAfter = 0L,
                onFaceDown = {},
                decoySetUp = false,
                onSetUpDecoy = {},
                onPanicCredential = {},
                onIntruderCapture = {},
                onExport = {},
                currentAppearance = ThemeChoice.Dark,
                onChooseAppearance = {},
            )
        }
    }) {
        assertTrue(has("Purchase  ·  Trial, 12 days left"))
        assertFalse(has("Subscription"))
    }

    @Test fun sendRefused() = shot("08-send-refused", {
        VaultTheme(ThemeChoice.Dark) {
            ShareScreen(
                itemName = "holiday.jpg",
                itemSize = 2_400_000,
                refusal = null,
                contacts = emptyList(),
                selections = com.app.vault.ui.ShareSelections(recipient = null),
                onSelections = {},
                onPair = {},
                code = null,
                sealedTo = null,
                busy = false,
                progress = null,
                failed = null,
                strippable = true,
                onCreate = { _, _, _ -> },
                onSend = {},
                onCopyCode = {},
                onBack = {},
                trialEnded = true,
                price = PRICE,
            )
        }
    }) {
        assertTrue(has("Your trial has ended"))
        assertTrue(has("Sending a share needs the one-time purchase"))
        assertTrue(has("Buy for $PRICE"))
        assertFalse("no compose state behind the refusal", has("No limit"))
    }
}
