package com.app.vault

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frame carries a position and a Back, and no way out — `first-run-onboarding`.
 *
 * **Every user walks the eight steps.** The trailing slot used to hold "Skip", then
 * "Exit setup"; both are gone, and the assertions below keep them gone. The last
 * step's "Finish setup" is the only control that ends the flow, and it belongs to
 * the step, not the frame.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFrameTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun withFrame(
        step: Onboarding.Step,
        onBack: (() -> Unit)? = {},
        assertions: () -> Unit,
    ) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    VaultTheme {
                        OnboardingFrame(
                            step = step,
                            onBack = onBack,
                        ) {
                            Text("hosted step content", modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            compose.waitForIdle()
            assertions()
        }
    }

    /**
     * No step, the last included, offers a way out of the flow.
     *
     * "Exit setup" ended first run permanently from step 3 onward, so a user could
     * leave before ever learning the decoy, the backup, the panic credential or the
     * cover identity exist.
     */
    @Test
    fun noStepOffersAnExit() {
        Onboarding.Step.entries.forEach { step ->
            withFrame(step) {
                assertEquals(
                    "$step must not offer an exit — every user walks the eight steps",
                    0,
                    compose.onAllNodesWithTextSafe("Exit setup") +
                        compose.onAllNodesWithTextSafe("Leave setup"),
                )
            }
        }
    }

    /**
     * The regression this whole change exists for.
     *
     * A "Skip" that called the step's own advance was a second forward control
     * wearing a label that promised an outcome it did not deliver. Nothing in the
     * frame may reintroduce it.
     */
    @Test
    fun noStepOffersASkip() {
        Onboarding.Step.entries.forEach { step ->
            withFrame(step) {
                assertEquals(
                    "$step must not offer a skip — it was a Continue in a text button",
                    0, compose.onAllNodesWithTextSafe("Skip"),
                )
            }
        }
    }

    @Test
    fun theFrameSaysWhereYouAre() {
        // The count is a label above the rail; the rail itself carries the same
        // value in its contentDescription, so a screen reader is not left with a
        // decorative bar.
        withFrame(Onboarding.Step.Backup) {
            compose.onNodeWithText("Step 7 of 9").assertIsDisplayed()
            compose.onNodeWithContentDescription("Step 7 of 9").assertExists()
        }
        withFrame(Onboarding.Step.Identity) {
            compose.onNodeWithText("Step 9 of 9").assertIsDisplayed()
        }
    }

    @Test
    fun backIsOfferedWhenThereIsSomewhereToGo() {
        var backs = 0
        withFrame(Onboarding.Step.Panic, onBack = { backs++ }) {
            compose.onNodeWithText("Back").performClick()
        }
        assertEquals("a flow that only goes forward is the bug", 1, backs)
    }

    @Test
    fun backIsAbsentOnTheFirstDrawnStep() {
        withFrame(Onboarding.Step.Fingerprint, onBack = null) {
            assertEquals(0, compose.onAllNodesWithTextSafe("Back"))
        }
    }

    @Test
    fun theHostedContentIsStillDrawn() {
        withFrame(Onboarding.Step.Decoy) {
            compose.onNodeWithText("hosted step content").assertIsDisplayed()
        }
    }

    /**
     * The count sits in the same place whether or not Back is drawn.
     *
     * Back comes and goes between steps. A count laid out beside it moved by half of
     * Back's width on every step that gained or lost it — the one thing that changes
     * from step to step was also the one thing that jumped.
     */
    @Test
    fun theCountSitsInTheSamePlaceWithAndWithoutBack() {
        fun centreX(onBack: (() -> Unit)?): Float {
            var x = 0f
            withFrame(Onboarding.Step.Capture, onBack = onBack) {
                val bounds = compose.onNodeWithText("Step 5 of 9").getBoundsInRoot()
                x = ((bounds.left + bounds.right) / 2).value
            }
            return x
        }
        assertEquals(
            "the step count must not move when Back appears",
            centreX(onBack = null), centreX(onBack = {}), 0.5f,
        )
    }

    /** Counts matching nodes without throwing when there are none. */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextSafe(
        text: String,
    ): Int = onAllNodes(hasText(text)).fetchSemanticsNodes().size
}
