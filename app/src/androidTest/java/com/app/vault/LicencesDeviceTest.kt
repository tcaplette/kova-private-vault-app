package com.app.vault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `third-party-notices`, task 3.4 — the home drawer's Open-source licences row, the list, one
 * licence, and back twice; and that every entry the build generated carries its licence text.
 *
 * **This test creates a real vault in the app's own `filesDir`,** because the home drawer is
 * behind an unlock. It is removed in [tearDown]; an existing vault is left alone and the test
 * skips — `ScreenChromeDeviceTest`'s terms.
 */
@RunWith(AndroidJUnit4::class)
class LicencesDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobs get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private var created = false

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"
    }

    @Before
    fun setUp() {
        // The suite needs the unlock screen, not a cover — see ScreenChromeDeviceTest.setUp.
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val lifecycle = VaultLifecycle(slots, BlobStore(blobs, incoming))
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        lockKeyringAndVault()
        created = true
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        if (!created) return
        slots.delete()
        blobs.deleteRecursively()
        incoming.deleteRecursively()
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(45_000, condition)

    private fun onHome(): Boolean =
        compose.onAllNodes(hasContentDescription("Menu")).fetchSemanticsNodes().isNotEmpty()

    private fun unlockToHome() {
        await { showing("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        await { compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unlock").performClick()
        await(::onHome)
        compose.waitForIdle()
    }

    @Test
    fun everyGeneratedEntryHasItsLicenceText() {
        val all = Notices.read(ctx)
        assertTrue("the notices list holds the classpath and the three manual entries", all.size >= 100)
        all.forEach { c ->
            assertTrue("${c.name} has no licence", c.licences.isNotEmpty())
            c.licences.forEach { assertTrue("${c.name}: ${it.name} has no text", it.text.isNotBlank()) }
        }
        listOf("libsodium", "Inter", "JetBrains Mono", "lazysodium-android").forEach { name ->
            assertTrue("$name is listed", all.any { it.name == name })
        }
        assertEquals(
            "JNA is recorded under the Apache option of its dual licence",
            listOf("Apache License 2.0"),
            all.single { it.id == "net.java.dev.jna:jna" }.licences.map { it.name },
        )
    }

    @Test
    fun drawerToListToLazysodiumAndBackTwice() {
        val count = Notices.read(ctx).size
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockToHome()
            compose.onNode(hasContentDescription("Menu")).performClick()
            await { showing("Recover a vault with its kit") }

            // The row names how many the screen lists.
            val line = "$count libraries and their licences"
            compose.onNodeWithText(line).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open-source licences").performClick()

            await { showing("is built with these libraries") }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("lazysodium-android"))
            compose.onNodeWithText("lazysodium-android").performClick()

            await { showing("Mozilla Public License 2.0") }
            assertTrue("the detail states the version", showing("Version 5.2.0"))
            assertTrue("and the coordinate", showing("com.goterl:lazysodium-android:5.2.0"))
            assertTrue("and the upstream source", showing("https://github.com/terl/lazysodium-android"))
            // The full text: its opening and its last exhibit.
            assertTrue("the licence text is shown", showing("Mozilla Public License Version 2.0"))
            assertTrue("in full", showing("Exhibit B - \"Incompatible With Secondary Licenses\" Notice"))

            // Back from a licence lands on the list at the row that was tapped — its lead may be
            // scrolled away, so the row is what proves it.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { !showing("Exhibit B") }
            compose.onNodeWithText("lazysodium-android").assertIsDisplayed()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { !showing("lazysodium-android") }
            assertTrue("back from the list lands on the home screen", onHome())
        }
    }
}
