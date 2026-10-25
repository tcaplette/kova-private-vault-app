package com.app.vault

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.VaultGrid
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The floating action button's states and its four actions — `settings-screen-and-fab`.
 *
 * **Rendered directly rather than through the activity.** Every other question about
 * the button — what each action opens, what happens after — is answered by the
 * handlers it calls, and those are the same handlers the drawer called before this
 * change, covered where they already were. What is *only* true on screen is which
 * state draws the button at all, and that needs a grid and nothing else. A real vault,
 * an unlock and an import would be three preconditions for a visibility check.
 *
 * The states are the ones `grid-fab` fixes: absent while selecting, absent in the
 * trash, present inside a folder, present in a search. Absent means **not drawn** — the
 * assertions below check the node does not exist rather than that it is disabled,
 * because a control that is present and dead only advertises a dead end.
 */
@RunWith(AndroidJUnit4::class)
class FabDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    /** One item, so the grid is not drawing its empty state instead. */
    private fun item() = VaultItem(
        blobIds = listOf(ByteArray(16) { 7 }),
        plaintextLength = 1L,
        thumb = null,
        filename = "a.jpg",
        album = null,
        createdAt = null,
        importedAt = 1L,
        tags = emptyList(),
    )

    /**
     * Renders the grid in whichever state is being asked about.
     *
     * `fabExpanded` is hoisted to the activity in the real app so back can collapse
     * it; here it is local state for the reason it is state at all — the expanded set
     * has to survive the recomposition its own tap causes.
     */
    private fun grid(
        selecting: Boolean = false,
        trash: Boolean = false,
        folderName: String? = null,
        query: String = "",
        onCamera: () -> Unit = {},
        cameraAvailable: Boolean = true,
        onNewNote: () -> Unit = {},
        onHome: () -> Unit = {},
        onNewFolder: () -> Unit = {},
    ) {
        compose.setContent {
            VaultTheme {
                var expanded by remember { mutableStateOf(false) }
                val one = remember { item() }
                VaultGrid(
                    items = listOf(one),
                    thumbnailFor = { null },
                    onOpen = {},
                    selected = if (selecting) setOf(one.itemId) else emptySet(),
                    onBeginSelection = {},
                    onToggleSelection = {},
                    onSelectAll = {},
                    onClearSelection = {},
                    onDeleteSelected = {},
                    onExportSelected = {},
                    onImport = {},
                    trash = trash,
                    folderName = folderName,
                    query = query,
                    drawerState = DrawerState(DrawerValue.Closed),
                    drawerContent = {},
                    fabExpanded = expanded,
                    onFabExpandedChange = { expanded = it },
                    onCamera = onCamera,
                    cameraAvailable = cameraAvailable,
                    onNewNote = onNewNote,
                    onHome = onHome,
                    onNewFolder = onNewFolder,
                    vaultName = "Vault",
                )
            }
        }
    }

    private fun fab() = compose.onNodeWithContentDescription("Actions")

    @Test
    fun theButtonIsDrawnOnTheOrdinaryGrid() {
        grid()
        fab().assertIsDisplayed()
    }

    @Test
    fun theButtonIsAbsentWhileSelecting() {
        grid(selecting = true)
        fab().assertDoesNotExist()
    }

    @Test
    fun theButtonIsAbsentInTheTrash() {
        grid(trash = true)
        fab().assertDoesNotExist()
    }

    @Test
    fun theButtonIsDrawnInsideAFolder() {
        grid(folderName = "Holiday")
        fab().assertIsDisplayed()
    }

    @Test
    fun theButtonIsDrawnWhileSearching() {
        grid(query = "")
        fab().assertIsDisplayed()
    }

    /**
     * Two actions, and two things deliberately not among them.
     *
     * **New vault** moved to the home screen's bar: creating a vault is a thing you
     * do to the list, not a thing you put in the vault you are looking at, and
     * sitting it beside the content actions made it read as a vault inside a vault.
     *
     * **New folder** moved to this grid's own header — `new-folder-in-the-header`.
     * It was two taps behind this button while every other way of putting something
     * into a vault was one tap up there.
     */
    @Test
    fun expandingOffersTheThreeContentActions() {
        grid()
        fab().performClick()

        listOf("Camera", "Voice memo", "New note").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("New vault").assertDoesNotExist()
        compose.onNodeWithText("New folder").assertDoesNotExist()
    }

    /**
     * `unavailable-capability-states`. With no camera the Camera action stays in its place,
     * unusable, and its label says why. The set is still three, and pressing Camera opens
     * nothing and leaves the set where it was.
     */
    @Test
    fun theCameraActionSaysWhyWithoutACamera() {
        var opened = false
        grid(onCamera = { opened = true }, cameraAvailable = false)
        fab().performClick()

        val camera = compose.onNodeWithText("Camera  ·  Not on this device")
        camera.assertIsDisplayed()
        camera.assertIsNotEnabled()
        compose.onNodeWithText("Camera").assertDoesNotExist()
        // `voice-memo`. Every device has a microphone, so this one is never unusable —
        // a device that refuses one says so on the screen it opens.
        compose.onNodeWithText("Voice memo").assertIsDisplayed()
        compose.onNodeWithText("New note").assertIsDisplayed()
        assertTrue(
            "three actions, Camera first",
            camera.fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithText("Voice memo").fetchSemanticsNode().boundsInRoot.top,
        )

        camera.performClick()
        compose.waitForIdle()
        assertFalse("an unusable Camera must open nothing", opened)
        compose.onNodeWithText("Voice memo").assertIsDisplayed()
    }

    @Test
    fun anActionInvokesItsHandlerAndCollapsesTheSet() {
        var opened = false
        // New folder left the set, so the act this asserts runs through New note now.
        grid(onNewNote = { opened = true })

        fab().performClick()
        compose.onNodeWithText("New note").performClick()

        assertTrue("choosing New note must invoke its handler", opened)
        compose.onNodeWithText("Camera").assertDoesNotExist()
    }

    /**
     * Dismissing invokes none of the four — `grid-fab`'s "dismissed without acting".
     *
     * Back is the activity's `BackHandler` and is covered there; this is the other
     * half of the same guarantee, through the control that is over the scrim.
     */
    @Test
    fun dismissingCollapsesAndInvokesNothing() {
        var any = false
        grid(
            onCamera = { any = true },
            onNewNote = { any = true },
            onNewFolder = { any = true },
        )

        fab().performClick()
        compose.onNodeWithText("New note").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close actions").performClick()

        compose.onNodeWithText("New note").assertDoesNotExist()
        assertFalse("dismissing must invoke none of the three", any)
    }
}
