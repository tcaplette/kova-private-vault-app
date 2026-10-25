package com.app.vault

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.ShareOpenScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The opened-share screen offers keeping only where keeping is allowed —
 * `share-keep-gate`.
 *
 * **This class exists for `DecoyFlowScreenTest`'s reason.** `FLAG_SECURE` makes the
 * app unscreenshottable, so a claim about what is on this screen is a test or it is
 * not checked at all. The claim here is the one whose absence was the defect: a
 * container that says "the sender set this to open once" used to offer, forty dp
 * below that sentence, the button that made the sentence false.
 *
 * The screenshots this writes are the human half of the same check — the vertical
 * rhythm after the button is gone is not something a semantics tree can show.
 */
@RunWith(AndroidJUnit4::class)
class ShareOpenScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private fun opened(policy: ShareContainer.Policy) = ShareOpen.Outcome.Opened(
        details = ShareContainer.Opened(
            kind = ShareContainer.Kind.IMAGE,
            displayName = "holiday.jpg",
            mime = "image/jpeg",
            policy = policy,
            shareId = ByteArray(ShareContainer.SHARE_ID_BYTES),
        ),
        blobId = ByteArray(32),
        plaintextLength = 2_400_000,
    )

    /**
     * Renders the opened state for [policy] and runs [assertions].
     *
     * **`FLAG_SECURE` is cleared on the way in**, because `VaultApplication` sets it
     * on every activity as it is created and a screenshot of a secure window is a
     * black rectangle. It is cleared on this throwaway `ComponentActivity` only; no
     * shipping surface is touched.
     */
    private fun screen(
        policy: ShareContainer.Policy,
        shot: String?,
        keepAllowed: Boolean = true,
        assertions: () -> Unit,
    ) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme {
                        ShareOpenScreen(
                            mnemonic = Mnemonic.fromResources(ctx),
                            words = List(4) { "" },
                            onWordsChange = {},
                            busy = false,
                            failed = false,
                            refusal = null,
                            opened = opened(policy),
                            probing = false,
                            sealedHere = false,
                            identityUnreadable = false,
                            onContacts = {},
                            onOpen = {},
                            onView = {},
                            onKeep = {},
                            onBack = {},
                            keepAllowed = keepAllowed,
                        )
                    }
                }
            }
            compose.waitForIdle()
            assertions()
            if (shot != null) {
                val dir = File(ctx.getExternalFilesDir(null), "share-keep-gate").apply { mkdirs() }
                File(dir, "$shot.png").outputStream().use {
                    instr.uiAutomation.takeScreenshot()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun anUnmanagedShareStillOffersKeeping() {
        screen(ShareContainer.Policy.Unmanaged, "unmanaged") {
            assertTrue("the default policy must be unchanged", count("Keep in this vault") > 0)
            assertTrue(count("If you leave without keeping it, nothing is saved.") > 0)
            assertTrue("nothing says a limit that is not there", count("can't be kept") == 0)
        }
    }

    /**
     * `one-time-purchase`. After the trial a share still opens and is still viewed —
     * keeping, which is an add, is what stops, and it stops in words with the way
     * forward beside it rather than as a greyed-out button.
     */
    @Test
    fun afterTheTrialAnUnmanagedShareOpensAndSaysWhyItCannotBeKept() {
        screen(ShareContainer.Policy.Unmanaged, "keep-refused-trial", keepAllowed = false) {
            assertTrue("opening is never gated", count("View") > 0)
            assertTrue("no keep control, disabled or otherwise", count("Keep in this vault") == 0)
            assertTrue(
                count("Keeping shares needs the one-time purchase. When you leave, nothing is saved.") == 1,
            )
            assertTrue("the way forward is beside the refusal", count("Buy") == 1)
            assertTrue(count("One payment. No subscription, nothing renews.") == 1)
            assertTrue("the unmanaged promise is not made", count("If you leave without keeping it") == 0)
        }
    }

    @Test
    fun afterTheTrialALimitedShareStatesOnlyItsPolicy() {
        screen(ShareContainer.Policy.SingleView, null, keepAllowed = false) {
            assertTrue(count("This share can't be kept. When you leave, nothing is saved.") > 0)
            assertTrue("the policy is the reason; payment is not offered", count("Buy") == 0)
            assertTrue(count("needs the one-time purchase") == 0)
        }
    }

    @Test
    fun anOpenOnceShareOffersNoKeepingAndSaysSo() {
        screen(ShareContainer.Policy.SingleView, "single-view") {
            assertTrue(
                "the button that voids the limit must be gone, not disabled",
                count("Keep in this vault") == 0,
            )
            assertTrue(
                "an absent action is explained, not left to be noticed",
                count("This share can't be kept. When you leave, nothing is saved.") > 0,
            )
            assertTrue(
                "the unmanaged promise must not survive alongside it",
                count("If you leave without keeping it") == 0,
            )
            // Stated once: `limitSentence` above carries the limit itself.
            assertTrue(count("set this to open once") == 1)
        }
    }

    @Test
    fun anExpiringShareOffersNoKeepingEither() {
        val policy = ShareContainer.Policy.Expiry(System.currentTimeMillis() + 86_400_000L)
        screen(policy, "expiring") {
            assertTrue(
                "a kept copy defeats an expiry date as completely as an open-once limit",
                count("Keep in this vault") == 0,
            )
            assertTrue(count("This share can't be kept. When you leave, nothing is saved.") > 0)
            assertTrue(count("set this to stop opening on") == 1)
        }
    }

    @Test
    fun theViewActionSurvivesInEveryBranch() {
        listOf(
            ShareContainer.Policy.Unmanaged,
            ShareContainer.Policy.SingleView,
            ShareContainer.Policy.Expiry(System.currentTimeMillis() + 86_400_000L),
        ).forEach { policy ->
            screen(policy, null) {
                assertTrue("$policy lost its only remaining action", count("View") > 0)
            }
        }
    }
}
