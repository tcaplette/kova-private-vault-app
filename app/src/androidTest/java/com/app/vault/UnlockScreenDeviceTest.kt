package com.app.vault

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.VaultUnlockScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `unlock-screen-redesign`, task 3.2 — the unlock screen's layout, focus, submit, failure,
 * recovery link, busy indicator and biometric control, in both schemes.
 *
 * Composed directly on a throwaway activity, edge to edge as `MainActivity` is, so the
 * scaffold's `safeDrawing` inset sees the keyboard. No vault is created.
 */
@RunWith(AndroidJUnit4::class)
class UnlockScreenDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private val unlocked = mutableListOf<ByteArray>()

    private fun screen(onRecover: (() -> Unit)? = {}, onBiometric: (() -> Unit)? = null): @Composable () -> Unit = {
        VaultUnlockScreen(
            onUnlock = { unlocked += it.copyOf(); it.fill(0) },
            busy = busy,
            failed = failed,
            onBiometric = onBiometric,
            biometricLabel = "Use fingerprint",
            onRecover = onRecover,
        )
    }

    /** Runs [check] once per scheme, each on a fresh activity and fresh state. */
    private fun inBothSchemes(
        content: () -> @Composable () -> Unit = { screen() },
        check: (ActivityScenario<ComponentActivity>) -> Unit,
    ) {
        for (scheme in listOf(ThemeChoice.Dark, ThemeChoice.Light)) {
            busy = false
            failed = false
            unlocked.clear()
            val body = content()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                    activity.setContent { VaultTheme(scheme) { body() } }
                }
                compose.waitForIdle()
                check(scenario)
            }
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())
    private fun unlockButton() = compose.onNode(hasClickAction() and hasText("Unlock"))

    @Test
    fun noBarAndWelcomeBackIsTheHeading() = inBothSchemes {
        val headings = compose.onAllNodes(isHeading()).fetchSemanticsNodes()
        assertEquals("one heading, and no app bar title", 1, headings.size)
        compose.onNode(isHeading() and hasText("Welcome back")).assertExists()
        assertEquals(0, compose.onAllNodes(hasText("Locked")).fetchSemanticsNodes().size)
    }

    @Test
    fun imeDoneSubmitsAndClearsTheField() = inBothSchemes {
        field().performImeAction()
        compose.waitForIdle()
        assertEquals("Done on an empty field does nothing", 0, unlocked.size)

        field().performTextInput("correct horse")
        field().performImeAction()
        compose.waitForIdle()
        assertEquals(1, unlocked.size)
        assertArrayEquals("correct horse".toByteArray(Charsets.UTF_8), unlocked[0])
        val text = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue("the field was not cleared", text.isEmpty())
    }

    @Test
    fun theFieldIsFocusedWithNoBiometricOffered() = inBothSchemes {
        compose.waitUntil(5_000) {
            field().fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
        }
        field().assertIsFocused()
    }

    @Test
    fun aFailureIsTheFieldsErrorState() = inBothSchemes {
        failed = true
        compose.waitForIdle()
        val message = compose.onNode(hasText("That did not open a vault."), useUnmergedTree = true)
        message.assertExists()
        field().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        val fieldBounds = field().fetchSemanticsNode().boundsInRoot
        val messageTop = message.fetchSemanticsNode().boundsInRoot.top
        assertTrue("the message is not under the field", messageTop >= fieldBounds.top)
    }

    @Test
    fun forgotPassphraseOpensRecovery() {
        var recovered = 0
        inBothSchemes(content = { recovered = 0; screen(onRecover = { recovered++ }) }) {
            val link = compose.onNode(hasClickAction() and hasText("Forgot passphrase?"))
            link.assertHeightIsAtLeast(48.dp)
            link.performClick()
            compose.waitForIdle()
            assertEquals(1, recovered)
        }
    }

    @Test
    fun busyPutsTheIndicatorInsideUnlock() = inBothSchemes {
        field().performTextInput("correct horse")
        compose.waitForIdle()
        val idle = unlockButton().fetchSemanticsNode().boundsInRoot

        busy = true
        compose.waitForIdle()
        compose.onNode(
            hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate) and
                hasAnyAncestor(hasClickAction() and hasStateDescription("Unlocking")),
            useUnmergedTree = true,
        ).assertExists()
        unlockButton().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unlocking"))
        val during = unlockButton().fetchSemanticsNode().boundsInRoot
        // Size, not position: the field is disabled while busy, the keyboard drops, and
        // the pinned actions follow it down — that is the layout working.
        assertEquals("Unlock changed width while busy", idle.width, during.width)
        assertEquals("Unlock changed height while busy", idle.height, during.height)

        unlockButton().performClick()
        compose.waitForIdle()
        assertEquals("a tap while busy started an attempt", 0, unlocked.size)
    }

    @Test
    fun theBiometricButtonShowsTheLabelAndRaisesThePrompt() {
        var raised = 0
        inBothSchemes(content = { raised = 0; screen(onBiometric = { raised++ }) }) {
            val button = compose.onNode(hasClickAction() and hasText("Use fingerprint"))
            button.assertExists()
            button.performClick()
            compose.waitForIdle()
            assertEquals(1, raised)
        }
    }

    @Test
    fun unlockSitsAboveTheKeyboard() = inBothSchemes { scenario ->
        var imeTop = 0
        compose.waitUntil(10_000) {
            var shown = false
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(root)
                shown = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                if (shown) imeTop = root.height - insets!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
            shown
        }
        // The IME reports visible before its inset animation has finished, and the pinned
        // actions ride that animation up. Given the time it takes, they must end above it.
        var bottom = Float.MAX_VALUE
        runCatching {
            compose.waitUntil(5_000) {
                bottom = unlockButton().fetchSemanticsNode().boundsInWindow.bottom
                bottom <= imeTop
            }
        }
        assertTrue("Unlock's bottom ${bottom}px is under the keyboard's top ${imeTop}px", bottom <= imeTop)
    }
}
