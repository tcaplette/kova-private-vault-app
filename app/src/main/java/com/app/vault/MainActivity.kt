package com.app.vault

import android.app.ActivityManager
import android.provider.MediaStore
import android.graphics.Bitmap
import android.util.Log
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList as ComposeLocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.app.vault.ui.LanguageSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.toArgb
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import android.print.PrintManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.lifecycleScope
import com.app.vault.ui.KitCheckFraming
import com.app.vault.ui.KitCheckScreen
import com.app.vault.ui.PassphrasePracticeScreen
import com.app.vault.ui.PracticeNewPassphraseScreen
import com.app.vault.ui.PracticeStage
import com.app.vault.ui.PracticeState
import com.app.vault.ui.FolderCoverScreen
import com.app.vault.ui.FrameOutcome
import com.app.vault.ui.FolderDeleteScreen
import com.app.vault.ui.FolderNameSheet
import com.app.vault.ui.FolderPickerScreen
import com.app.vault.ui.FolderSheet
import com.app.vault.ui.PhotoDetailsSheet
import com.app.vault.ui.ArchiveImportScreen
import com.app.vault.ui.ImportDone
import com.app.vault.ui.ImportKind
import com.app.vault.ui.BackupScreen
import com.app.vault.ui.BackupStatus
import com.app.vault.ui.backupDrawerLine
import com.app.vault.ui.RestoreFile
import com.app.vault.ui.RestoreState
import com.app.vault.ui.BiometricLayout
import com.app.vault.ui.BiometricScreen
import com.app.vault.ui.FaceDownScreen
import com.app.vault.ui.LicencesScreen
import com.app.vault.ui.BiometricState
import com.app.vault.ui.CameraScreen
import com.app.vault.ui.AddContactScreen
import com.app.vault.ui.AddRoute
import com.app.vault.ui.ContactsScreen
import com.app.vault.ui.RemoteEnd
import com.app.vault.ui.RemotePairingScreen
import com.app.vault.ui.RemoteStep
import com.app.vault.ui.inviter
import com.app.vault.ui.ShareSelections
import com.app.vault.ui.PairingScreen
import com.app.vault.ui.PairingStep
import com.app.vault.ui.CaptureSheet
import com.app.vault.ui.BackupStepScreen
import com.app.vault.ui.CaptureStepScreen
import com.app.vault.ui.DecoyStepScreen
import com.app.vault.ui.FingerprintStepScreen
import com.app.vault.ui.IdentityStepScreen
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.OnboardingLanguageScreen
import com.app.vault.ui.OnboardingWelcomeScreen
import com.app.vault.ui.PassphraseStepScreen
import com.app.vault.ui.RecoveryKitStepScreen
import com.app.vault.ui.PanicStepScreen
import com.app.vault.ui.EditorScreen
import com.app.vault.ui.ExportScreen
import com.app.vault.ui.ShareOpenScreen
import com.app.vault.ui.ShareScreen
import com.app.vault.ui.DecoyAuthSheet
import com.app.vault.ui.DecoyBuildStep
import com.app.vault.ui.DecoyFrame
import com.app.vault.ui.DecoyHandoverStep
import com.app.vault.ui.DecoyIdentityStep
import com.app.vault.ui.DecoyIntroStep
import com.app.vault.ui.DecoyPhotosStep
import com.app.vault.ui.DecoyPreserveSheet
import com.app.vault.ui.DecoySheet
import com.app.vault.ui.DeleteConfirmation
import com.app.vault.ui.DecoySummary
import com.app.vault.ui.CalculatorCover
import com.app.vault.ui.MusicCover
import com.app.vault.ui.RecorderCover
import com.app.vault.ui.CommitState
import com.app.vault.ui.IdentityCommitSheet
import com.app.vault.ui.IdentitySetupScreen
import com.app.vault.ui.ExportTarget
import com.app.vault.ui.IdentityScreen
import com.app.vault.ui.EditVaultScreen
import com.app.vault.ui.AddPassphraseStep
import com.app.vault.ui.DiscardFlowDialog
import com.app.vault.ui.NameStep
import com.app.vault.ui.NewPassphrase
import com.app.vault.ui.NewPassphraseStep
import com.app.vault.ui.NewVaultStep
import com.app.vault.ui.OpensStep
import com.app.vault.ui.StepFrame
import com.app.vault.ui.VaultCaptureStep
import com.app.vault.ui.VaultFlowBody
import com.app.vault.ui.ChangeVaultPassphraseScreen
import com.app.vault.ui.PassphraseAct
import com.app.vault.ui.PassphraseActState
import com.app.vault.ui.RemoveVaultPassphraseScreen
import com.app.vault.ui.RecoverVaultScreen
import com.app.vault.ui.VaultPassphraseSheet
import com.app.vault.ui.PanicField
import com.app.vault.ui.PanicProblem
import com.app.vault.ui.PanicScreen
import com.app.vault.ui.RecoverScreen
import com.app.vault.ui.RecoveryKitScreen
import com.app.vault.ui.RecoveryKitViewScreen
import com.app.vault.ui.KitStatus
import com.app.vault.ui.NoteScreen
import com.app.vault.ui.NoteSheet
import com.app.vault.ui.RenameSheet
import com.app.vault.ui.PurchaseScreen
import com.app.vault.ui.DestinationSettingsSheet
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.VaultGrid
import com.app.vault.ui.HomeTarget
import com.app.vault.ui.VaultHomeTransition
import com.app.vault.ui.VaultListScreen
import com.app.vault.ui.VaultPickerScreen
import com.app.vault.ui.IntruderCaptureScreen
import com.app.vault.ui.DestroyStep
import com.app.vault.ui.VaultDestroyDialog
import com.app.vault.ui.VaultSetup
import com.app.vault.ui.VaultUnlockScreen
import com.app.vault.ui.theme.VaultPalette
import com.app.vault.ui.VaultViewer
import com.app.vault.ui.viewerSequence
import com.app.vault.ui.DiscardMemoConfirmation
import com.app.vault.ui.VoiceMemoScreen
import com.app.vault.ui.ViewerNotice
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultTheme
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The vault's one activity — setup, unlock, grid, viewer.
 *
 * Three states, routed on two facts: whether the slot file exists, and whether
 * [VaultSession] holds a key. File existence is not a secret — `vault.slots`
 * either is there or is not, and `FORMAT.md` 10(7) already records that the slot
 * count is public. What I8 protects is telling vaults apart, and nothing here
 * does that.
 */
/**
 * Which act the naming screen is performing — `folder-system`.
 *
 * One screen, two jobs, so the flag says which rather than two flags saying it
 * between them: a pair where only one may be set is a pair that can both be set.
 */
sealed class FolderNaming {
    /** Creating one inside [parentId] — null for the home screen. */
    class Create(val parentId: ByteArray?) : FolderNaming()

    /** Renaming [folder], whose current name the field starts with. */
    class Rename(val folder: VaultFolder) : FolderNaming()
}

/** The longest edge the editor previews at — see [MainActivity.previewFor]. */
private const val EDITOR_PREVIEW_EDGE = 2_048

/**
 * What an edited copy is encoded at.
 *
 * 92 and not 100: above about 95 a JPEG grows sharply for differences nobody sees,
 * and the copy is already one generation from the original. Below 90 the generation
 * starts to show on the flat areas an edit tends to produce.
 */
private const val EDITOR_QUALITY = 92

/** A count, never a name — `VaultRename`'s rule, on the editor's failure path. */
private const val TAG_EDIT = "VaultEdit"

class MainActivity : FragmentActivity() {

    private companion object {
        /**
         * `cover-fingerprint-dots` D6. Intruder captures, one at a time, for the whole process —
         * attempts a second apart each get their capture instead of colliding on the camera, and
         * an activity recreated mid-burst queues behind the old one's.
         */
        val CAPTURES: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor()

        /** `cover-hold-dots` D4. How long the error state stands on shown dots before they go. */
        const val DOTS_ERROR_MS = 600L


        /**
         * `backup-to-cloud-apps` D2. The Save sheet's suggested name. It does not say
         * "vault": a file called that in someone's Drive is the fact the decoy denies.
         */
        const val BACKUP_FILE_NAME = "backup.bin"

        /** `backup-file-recover-and-import`, D9. The Restore row's refusal of another list's backup. */
        val INCOMPLETE_BACKUP = R.string.backup_incomplete
        val UNREADABLE_BACKUP = R.string.backup_unreadable
        val OTHER_LIST_BACKUP = R.string.backup_other_list

        /**
         * Said when a wrap has just been thrown away. It names what happened and
         * what still works; there is nothing for the user to fix, because the
         * passphrase field beneath it is the whole recovery path.
         */
        val BIO_INVALIDATED = R.string.bio_invalidated

        /**
         * `vault-keyring`. Said once, after the unlock that migrated this install.
         *
         * The wrap held a copy of the slot's payload, and the payload's meaning
         * changed — so it is retired rather than left to fail on the next fingerprint
         * with nothing said. It names the cause and the one-tap fix, and it does not
         * say "invalidated": nothing went wrong and nothing was lost.
         */
        val BIO_AFTER_MIGRATION = R.string.bio_after_migration

        /**
         * P2-25. Said when this build meets a vault a **newer** build wrote — the
         * rollback case, after a release is pulled back.
         *
         * It names the cause and the fix, and it deliberately does not say
         * "corrupt", "damaged" or "cannot be opened": nothing is wrong with the
         * vault, every byte is where it was, and the one thing standing between the
         * user and their photographs is a version number. The wrong word here reads
         * as total loss at the moment it is least true.
         */
        val NEWER_VERSION = R.string.vault_newer_version

        /**
         * `index-pointer-recovery`. Said once, on the unlock that repaired a pointer
         * that had stopped naming this vault's index.
         *
         * **Past tense, and it states the outcome first.** By the time anyone reads
         * this the vault is open and everything is in front of them, so the sentence
         * that matters is that nothing was lost — not the mechanism. It asks for
         * nothing, because there is nothing to do.
         */
        val INDEX_RELINKED = R.string.vault_index_relinked

        /**
         * `index-pointer-recovery`. Said when the pointer is wrong and the search
         * found no index to put in its place.
         *
         * **Cause and route, which is what an error owes the person reading it.** It
         * does not offer to delete the vault: a vault that will not open on this
         * build may open on one that reads more, and putting destruction beside a
         * failure is how people lose things at the worst possible moment. It is also
         * the one warning here allowed to say "damaged", because unlike
         * [NEWER_VERSION] something genuinely is.
         */
        val INDEX_UNREADABLE = R.string.vault_index_unreadable

        /**
         * P2-17. Said when a capture was taken and could not be written — a full
         * disk, most often. It names the outcome rather than the cause, because
         * there is exactly one thing the user can do about any of them, and the
         * capture is already gone.
         */
        val COULD_NOT_STORE = R.string.capture_not_saved
    }

    private val blobs by lazy { BlobStore(File(filesDir, "blobs")) }
    private val thumbs by lazy { ThumbnailStore(blobs) }
    private val lifecycleOps by lazy { VaultLifecycle(File(filesDir, "vault.slots"), blobs) }
    private val bio by lazy { BiometricWrap(this) }

    /** `recovery-practice` D1. The schedule exists only beside a wrap. */
    private val practice by lazy { PassphrasePractice.of(this) }

    /** Every wrap removal clears the practice schedule with it — `recovery-practice` 1.3. */
    private fun disableWrap() {
        bio.disable()
        practice.clear()
    }

    private var index by mutableStateOf(MetadataDb.empty())

    /**
     * `file-import` D4. The item IDs the vault held when the running import batch was
     * enqueued, or null when no batch is running.
     *
     * **A baseline and not a count**, because what the message needs is the *kinds* that
     * landed, and a count cannot give them. Held in memory for [WorkJobs]' reason — this
     * is a fact about the user's own content and `work.db` is readable with `run-as` —
     * and losing it to a process death costs nothing but the message.
     *
     * Set once per quiet period: a second pick while a batch is still running extends
     * that batch rather than starting a new one, so the baseline is not moved under it.
     */
    private var importBaseline: Set<String>? = null

    /**
     * The request ids of the imports this process enqueued — the **batch**, as
     * distinct from every import this install has ever run.
     *
     * `getWorkInfosByTagLiveData(IMPORT_TAG)` returns all work carrying the tag, and
     * WorkManager keeps finished work for a week. Counting `FAILED` across that set
     * meant one import that failed on Tuesday put *"1 import did not complete.
     * Nothing was added. Try again."* on top of every successful batch until the
     * following Tuesday — over content that had in fact landed, which is the message
     * telling the user the opposite of the truth about their own files.
     *
     * Ids only, and in memory: a request id is WorkManager's own and names nothing
     * about the content, and losing the set to a process death costs one message.
     */
    private val importBatch = mutableSetOf<java.util.UUID>()

    /**
     * `file-import` D4. Where an import went, when it was not where the user is standing.
     * The line itself is decided by the free `landedElsewhere` function; this holds it.
     *
     * ***Its own field and not written straight to [notice].*** The import observer fires
     * on every WorkManager emission, and the ordinary branch of that block clears
     * [notice]; a message written directly there would be erased by the next emission a
     * frame later. This holds it until the user moves, which is [goToDestination]'s job
     * because moving is what makes the message spent.
     */
    private var importLanded by mutableStateOf<String?>(null)

    /**
     * `remove-originals-on-import`. This session's photo-picker imports, by request id —
     * in memory for [WorkJobs]' reason: a source URI never reaches `work.db`.
     */
    private val gallerySources = mutableMapOf<java.util.UUID, Uri>()

    /** What the originals card is offering, or null when it is not showing. */
    private var originalsOffer by mutableStateOf<OriginalRemoval.Offer?>(null)

    /** Android's delete dialog is open; the card's actions wait for its answer. */
    private var removingOriginals by mutableStateOf(false)

    /** The line that replaces the card once Android has removed everything offered. */
    private var originalsRemoved by mutableStateOf(false)

    /** What the open request carries, so its result takes exactly those off the offer. */
    private var originalsSent: List<Uri> = emptyList()

    private var open by mutableStateOf<VaultItem?>(null)

    /**
     * Video stopping points reported this vault session — `viewer-swipe-navigation`, D7.
     * Item ID to position; null clears one.
     *
     * **Held, not written per swipe.** The index is one blob rewritten whole, so twenty
     * videos swiped through would be twenty index writes. The viewer reports here, and the
     * whole map goes to the vault in one commit when the viewer closes ([flushResume]) and
     * before the key is dropped ([writeResumeBeforeLock]).
     *
     * **Kept until the key goes, not until the write lands**, so it is also what the viewer
     * resumes from: a video swiped away and back, or reopened while the close's write is
     * still running, starts where it stopped. `VideoResume.record` writes only what differs
     * from the row, so sending an entry twice costs nothing. Its own monitor, because
     * auto-lock drains it from a background coroutine.
     */
    private val resumePending = HashMap<String, Long?>()

    /**
     * Where the viewer's close writes run. **Not `lifecycleScope`**: a viewer closed by the
     * activity being destroyed — a configuration change — disposes after that scope is
     * cancelled, and its positions would never be written.
     */
    private val resumeWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The item a delete has been asked for and not yet confirmed — P2-10.
     *
     * Held here, not in the grid or the viewer, because both raise the same
     * question and the answer is the same act. The dialog is drawn once, above
     * the `when`.
     */
    private var pendingDelete by mutableStateOf<VaultItem?>(null)

    /**
     * The selected items, by `itemId` — P2-11. Empty means no selection mode.
     *
     * Here rather than in `VaultGrid` for the reason the drawer's state is here:
     * **back ends a selection**, and back is one handler at the activity with one
     * precedence order. A selection owned by the grid would have to call out to
     * this class to be dismissed, which is the same state kept in two places.
     *
     * IDs and not [VaultItem]s. Every commit replaces the rows in the index, so an
     * item object held across one is a row that no longer exists; the ID is what
     * every action looks its row up by anyway.
     */
    private var selection by mutableStateOf<Set<String>>(emptySet())

    /**
     * The scope the export screen was opened with — P2-11. Null means the whole
     * vault, which is what the drawer's action means.
     *
     * **Separate from [selection] on purpose.** The selection is cleared when the
     * work is enqueued — those rows are leaving — and the screen stays up showing
     * progress. Reading the live selection there would re-title the screen
     * "Unvault Everything" and restate the vault's item count the instant a scoped
     * export began, which is the screen lying about what it is doing.
     */
    private var exportSelection by mutableStateOf<Set<String>?>(null)

    /**
     * Set while the pending delete is the **selection** rather than one item —
     * P2-11. The dialog needs the list; this says which list to hand it.
     */
    private var pendingDeleteSelection by mutableStateOf(false)

    /**
     * The folders the user has walked into, outermost first — `folder-system`.
     *
     * Empty is the home screen. The last entry is the folder being viewed, and its
     * length is how many levels back has to climb.
     *
     * ***A stack and not a filter.*** P2-13 held one album name here and narrowed the
     * whole live list by it. A folder holds folders now, so "which folder am I in" is
     * not enough — leaving one has to land in the folder that held it rather than at
     * the top, and only the path knows which that is.
     *
     * View state, like [trash] beside it: it never survives a lock and nothing about
     * it reaches disk. It is also what an import started from this screen lands in,
     * which is why it is read at the moment `importAll` runs rather than when the
     * picker returns.
     */
    private var folderPath by mutableStateOf<List<ByteArray>>(emptyList())

    /**
     * How the grid is ordered — P2-16.
     *
     * Read from `GridPreference` at startup and written back when the user picks
     * one, so it survives a lock, an unlock and the process being killed. Held
     * here rather than in `VaultGrid` because the ordering has to be applied where
     * `ordered` is built: the viewer pages through that list, and a grid sorting
     * for itself would page in a different order from the one on screen.
     */
    private var sort by mutableStateOf(VaultSort.Default)

    /**
     * `media-settings`. How photos and videos look and play — read in [onCreate] beside the
     * ordering, written as the Media sheet changes it, and read by the viewer and the decode.
     */
    private var mediaSettings by mutableStateOf(MediaSettings())
    /** `notes-settings` and `memos-settings`, read at start like Media's and written as changed. */
    private var noteSettings by mutableStateOf(NoteSettings())
    private var memoSettings by mutableStateOf(MemoSettings())

    /**
     * P2-20. Whether face-down auto-lock is on, and whether the device could do it
     * at all. Both read once in [onCreate]: the preference so the choice survives
     * the process that made it, availability because it is a system-service lookup
     * and hardware does not appear while the app is running.
     */
    /**
     * The note editor — P2-21. [noteScreen] is whether it is open; [editingNote] is
     * the note it is open on, or null for a new one that does not exist yet.
     *
     * Two fields and not one, because "open on nothing" is a real state: a new note
     * has no row until Save. The screen is picked by [noteScreen] alone, so the back
     * `when` has one flag to read, which is the rule that list's KDoc sets.
     */
    private var editingNote by mutableStateOf<VaultItem?>(null)
    private var noteScreen by mutableStateOf(false)

    private var faceDownOn by mutableStateOf(false)
    private var faceDownLockAfter by mutableStateOf(0L)

    /**
     * The phone's screen-off timeout, ms — `face-down-lock-settings` D4. Read on `onStart`, so a
     * change made in Android's Settings is picked up on the way back. [Long.MAX_VALUE] if unread.
     */
    private var screenTimeoutMs by mutableStateOf(Long.MAX_VALUE)
    private var faceDownAvailable by mutableStateOf(false)

    /**
     * P2-22. The launcher identity in effect, and the chooser's flag.
     *
     * Read in [onCreate] beside the ordering, for its reason: the
     * choice must survive the process that made it. It is read **after**
     * [LauncherIdentity.admit] has run, so what the drawer names is the stored,
     * presentable identity rather than what a half-finished switch left.
     */
    private var identity by mutableStateOf(LauncherIdentity.Default)
    private var identityScreen by mutableStateOf(false)

    /** `biometric-unlock-screen`. The Biometric unlock screen, opened from its drawer row. */
    private var biometricScreen by mutableStateOf(false)
    private var faceDownScreen by mutableStateOf(false)

    /**
     * `third-party-notices`. The Open-source licences screen and the component open on it — null
     * for the list. The notices are read once, off the main thread, at start; the drawer's count
     * and the screen both draw from them.
     */
    private var licencesScreen by mutableStateOf(false)
    private var licenceOpen by mutableStateOf<String?>(null)
    private var notices by mutableStateOf<List<Notices.Component>>(emptyList())

    /**
     * The entitlement's own screen — `settings-separation`.
     *
     * ***There is no settings screen any more.*** The app settings are rows in the
     * drawer, and so are the open vault's trash, backup and destruction; what is left of the
     * screen that held both is this one section, which is a countdown, a paragraph
     * and a purchase rather than a row that names a state.
     */
    private var purchaseScreen by mutableStateOf(false)

    /**
     * The settings sheet of the destination on screen, raised by the grid header's
     * `ic_tune`. A flag: the sheet reads [destination]. Cleared on leaving the vault and on
     * lock, with everything else that is a view of it.
     */
    private var destinationSettings by mutableStateOf(false)

    /**
     * Whether the grid's floating action button is showing its four actions.
     *
     * Here for the drawer's reason and no other: back collapses the set before it
     * closes the drawer, so the one `BackHandler` has to be able to read it.
     */
    private var fabExpanded by mutableStateOf(false)

    /**
     * Where the user is standing inside the open vault — `vault-destinations`.
     *
     * Here for [fabExpanded]'s reason and no other: back returns to
     * [VaultDestination.Media] before it leaves the vault, so the one `BackHandler` has to
     * be able to read it. A destination the handler cannot see is the failure [folderPath]
     * and [fabExpanded] are both held here to avoid.
     *
     * View state, like [trash] and [folderPath] beside it: nothing about it reaches disk,
     * and it is [VaultDestination.Default] again after a lock.
     */
    private var destination by mutableStateOf(VaultDestination.Default)

    /**
     * Which of Media's chips is selected — `vault-destinations`.
     *
     * Not persisted, by requirement: a vault reopens on [MediaFilter.All], because a vault
     * that opened showing half its media would be answering a question the user asked in a
     * previous session.
     */
    private var mediaFilter by mutableStateOf(MediaFilter.Default)

    /**
     * How each destination is drawn — `destination-view-modes`.
     *
     * ***Deliberately not cleared on lock, and that is the line between this and the three
     * pieces of state around it.*** [destination], [mediaFilter] and [places] are views of an
     * *unlocked vault* — where the user was looking, what they had narrowed, what level they
     * stood on — and every one of them dies with the session for that reason. This is not
     * that. It is how the person likes their lists, it is a device preference beside the
     * ordering and the density, and a vault that reopened having forgotten it would be
     * forgetting a setting rather than protecting a secret.
     *
     * Seeded from `GridPreference` when the activity is created, and written back the moment
     * the toggle is used, so it survives a lock, an unlock and the process being killed.
     *
     * A map and not four fields: the four are read and written identically, and four fields
     * would be four call sites for one rule.
     */
    private val viewModes = mutableStateMapOf<VaultDestination, VaultViewMode>()

    /** [destination]'s mode, or its default until the user has chosen one. */
    private val viewMode: VaultViewMode
        get() = viewModes[destination] ?: destination.defaultViewMode

    /**
     * Records the mode for the destination being viewed and persists it.
     *
     * Named once, as `goToDestination` is and for the same reason: the toggle is the only
     * caller today, and a second one that set the state without writing the preference would
     * be a mode that forgot itself on the next launch.
     */
    private fun setViewMode(mode: VaultViewMode) {
        viewModes[destination] = mode
        GridPreference.setViewMode(this, destination, mode)
    }

    /**
     * What each destination was left standing in — `vault-destinations`.
     *
     * **The alternative was clearing the level and the query on every switch**, which is
     * fewer lines and wrong: a user two folders deep in Files who glances at Media loses
     * their place for having looked.
     *
     * The scroll position is deliberately **not** here. It lives in the grid, as one lazy
     * state object per destination inside a `remember` — a position is a property of a list
     * that is drawn, and hoisting it up here would mean this activity holding a measurement
     * it never reads. This map holds only the two things back and the chrome already act on.
     *
     * A plain map and not state: it is written on the way out of a destination and read on
     * the way in, and nothing recomposes on it. Cleared with the session, like everything
     * else that is a view of an unlocked vault.
     */
    private val places = mutableMapOf<VaultDestination, Place>()

    /** One destination's place. See [places]. */
    private class Place(val folderPath: List<ByteArray>, val query: String)

    /**
     * P2-24. The cover in front of everything, and the trigger-setup flow.
     *
     * [coverShowing] starts true whenever the active identity has one and is cleared
     * only by a correct trigger. It is read **before** `recovering`, `hasVault` and
     * `unlocked`, which is the whole point: a live session that skipped the cover
     * would evaporate the identity for exactly as long as the vault is open.
     *
     * [coverGrant] is whether the cover currently being shown or set up has the
     * runtime permission it needs — the audio library for the music player, the
     * microphone for the recorder. **A cover never asks for it itself, and since
     * `cover-biometric-suppression` it has no callback with which it could**: a system
     * permission dialog carries the *application* label, which is the vault's real
     * name, so the request belongs to identity setup, inside the vault. A cover
     * without its grant draws the state a real app draws and names the Settings
     * toggle; it offers no button.
     *
     * The covers keep the rest of their own state — a library, a queue, a list of
     * recordings — in their own composition and their own plaintext stores. None of
     * it is held here, because none of it is this activity's business.
     */
    private var coverShowing by mutableStateOf(false)
    private var coverGrant by mutableStateOf(true)

    /**
     * `recents-shows-identity` D1, D2. The user chose to leave — Home, Recents, another app —
     * and the identity picture is raised over whatever is on screen, so the recent-apps card
     * shows the identity. Set in [onUserLeaveHint], cleared in [onStart] and [onResume] after
     * `FLAG_SECURE` is back. Plain state: nothing about it survives the process.
     */
    private var leaving by mutableStateOf(false)

    // --- `cover-hold-dots`: the pattern covers' shown dots ---------------------------------

    /**
     * D4. The dots are up: the owner held their area on the live cover, or entered the calculator's
     * sequence on **Button sequence, then pattern** (`calculator-dots-methods` D4).
     */
    private var coverDots by mutableStateOf(false)

    /** D4. A pattern on those dots was refused: the error state stands for [DOTS_ERROR_MS]. */
    private var coverDotsError by mutableStateOf(false)

    /**
     * D5. The worn cover's hold area, read on arrival in [onStart] and never in a composition.
     * Null where none is stored — the calculator on its two methods with a sequence.
     */
    private var coverHoldArea by mutableStateOf<Int?>(null)

    /**
     * `calculator-dots-methods` D4. The worn calculator's method, read on arrival beside
     * [coverHoldArea]: whether a hold on `=` reports a sequence. Null for the other covers.
     */
    private var coverCalculatorMethod by mutableStateOf<CalculatorMethod?>(null)

    /** The error state's 600ms, on the main thread. */
    private val coverHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Which identity the setup flow is working on, and the **whole** of what this
     * activity keeps about it. The step, both recorded performances and the floor
     * message live inside `IdentitySetupScreen`, where composition discards them when
     * the screen leaves — which is what makes "abandoning stores nothing" true by
     * construction rather than by a cleanup here.
     */
    private var settingUpIdentity by mutableStateOf<LauncherIdentity?>(null)

    /**
     * The identity whose commit sheet is up, and what that sheet is currently saying.
     *
     * ***The sheet is where the switch's cost is stated and where the permission dialog
     * is raised from.*** The step this replaced said "Allow access" over a button whose
     * only effect was to raise the dialog that asks the same question — two consents for
     * one decision. The dialog is now the next thing a user sees after choosing a cover,
     * and [commitState] is how its answer gets back to them: only this activity sees the
     * result, so only this activity moves the sheet.
     *
     * Null once the sheet is gone. Set together, always: a sheet showing a rationale for
     * an identity nobody is committing to is a sheet describing nothing.
     */
    private var committingIdentity by mutableStateOf<LauncherIdentity?>(null)
    private var commitState by mutableStateOf(CommitState.Commit)

    /**
     * P2-23. Which colour scheme the app draws.
     *
     * Read in [onCreate] beside the other device preferences — but **also read
     * before `super.onCreate`**, by [applyWindowTheme], because the window
     * background is resolved before any of this runs. Both reads go through
     * `ThemeChoice.resolve`, so the window and the composition cannot disagree.
     *
     * There is no second flag beside it any more. The chooser was a screen; it is a
     * control in the drawer, so there is no screen to be open and nothing to close.
     */
    private var appearance by mutableStateOf(ThemeChoice.Default)

    /**
     * `app-languages` D3. Moves when a language is chosen on Android 11–12, where no
     * configuration change follows the choice; [setLocalizedContent] reads it so every
     * `stringResource` redraws in the new language in place.
     */
    private var languageEpoch by mutableIntStateOf(0)

    /** `exif-strip-choice`. Whether an import removes a photograph's metadata. */
    private var stripExifOnImport by mutableStateOf(false)

    /** `exif-strip-choice`. Privacy or Custom, and what Custom removes — the Photo details sheet's answer. */
    private var photoDetailsPreset by mutableStateOf(PhotoDetailsPreset.PRIVACY)
    private var photoDetailsCustom by mutableStateOf(ExifPolicy.PRIVACY)

    /** `exif-strip-choice`. The Photo details sheet is up, over whichever screen the drawer was on. */
    private var photoDetailsSheet by mutableStateOf(false)

    /** `app-languages` D6. The Language sheet, opened from the home drawer. */
    private var languageSheet by mutableStateOf(false)

    /**
     * `language-picker-redesign` D2. The app's one snackbar host, drawn over every screen: a
     * language change's Undo, over the welcome screen or home, wherever the sheet was opened.
     */
    private val snackbars = SnackbarHostState()

    /**
     * The grid's query, or null when the search is closed — P2-15.
     *
     * `""` is open-and-empty and is not null: an open search with nothing typed
     * shows everything, and a closed one has no field. **View state and nothing
     * else** — never a preference, never a scheduled-work record, never a log
     * line, and cleared on lock with the rest of the session. A query is text the
     * user wrote about what they are looking for in their own vault.
     */
    private var query by mutableStateOf("")

    /**
     * The selection is waiting for a folder — P2-13. The move screen's flag.
     */
    private var movingToFolder by mutableStateOf(false)

    /**
     * The one item being moved from the viewer — `viewer-chrome-and-editor`.
     *
     * Separate from [movingToFolder], which moves the grid's *selection*: the picker
     * is the same screen and `VaultFolders.move` is the same call, but the scope is
     * one item that is on screen rather than a set that is not, and conflating them
     * would move a stale selection the user cannot see.
     */
    private var movingItem by mutableStateOf<VaultItem?>(null)

    /**
     * The item open in the image editor — `viewer-chrome-and-editor`. Null when it is
     * not open.
     *
     * Held here rather than in the editor for `open`'s reason: a lock has to be able
     * to close it, and the one `BackHandler` has to be able to read it.
     */
    private var editingImage by mutableStateOf<VaultItem?>(null)

    /**
     * What the last "use this frame" produced — `video-frame-thumbnail`. The viewer shows
     * it only as the answer to a choice it made, so this is never cleared.
     */
    private var frameOutcome by mutableStateOf<FrameOutcome?>(null)

    /**
     * The folder waiting to be moved — `folder-system`.
     *
     * The same picker answers both questions, so the two flags are separate: one
     * says "a selection needs a destination", this one says "this folder does". Null
     * when no folder is being moved.
     */
    private var movingFolder by mutableStateOf<VaultFolder?>(null)

    /**
     * A folder is being named — `folder-system`. Null when no naming screen is open.
     *
     * Creating and renaming are one screen, so this says which: [FolderNaming.Create]
     * carries the parent the new folder goes under, [FolderNaming.Rename] carries the
     * folder being renamed.
     */
    private var naming by mutableStateOf<FolderNaming?>(null)

    /**
     * The folder whose actions are showing — `folder-system`. Null when none are.
     *
     * A sheet over the grid rather than a screen, so the grid stays composed
     * underneath it — which is why this is not a branch of the `when` below.
     */
    private var folderSheet by mutableStateOf<VaultFolder?>(null)

    /**
     * The note whose actions are showing, or null. [folderSheet]'s twin one row down.
     *
     * A sheet over the grid rather than a screen, for that state's reason: the tile
     * being acted on stays composed and visible behind it, which is why this is not a
     * branch of the `when` below either.
     */
    private var noteOptions by mutableStateOf<VaultItem?>(null)

    /** The folder waiting for a cover — `folder-system`. */
    private var covering by mutableStateOf<VaultFolder?>(null)

    /** The folder waiting to be deleted — `folder-system`. Its confirmation's flag. */
    private var deletingFolder by mutableStateOf<VaultFolder?>(null)

    /** Why the last folder write was refused, or null — `folder-system`. */
    private var folderProblem by mutableStateOf<String?>(null)

    /**
     * The item waiting for a new name — P2-14. The rename screen's flag.
     *
     * An item rather than a boolean, because the rename screen needs the name it
     * is editing and the viewer's settled page is the only thing that knows which
     * item that is. Its branch sits **immediately ahead of the viewer's**, so the
     * screen draws over an open item and [open] is untouched underneath: back
     * clears this and the same photograph is there again.
     */
    private var renaming by mutableStateOf<VaultItem?>(null)

    /**
     * Showing the trash — P2-12. A screen flag like the rest of them.
     *
     * The trash is drawn by `VaultGrid` given the trashed rows, so this is the
     * only thing that says which list the user is looking at. Cleared on lock with
     * everything else, because a trash view over a locked vault would be a list of
     * items nothing can read.
     */
    private var trash by mutableStateOf(false)

    /**
     * Set while the pending confirmation is a **destruction** rather than a delete
     * — P2-12. Decides which of the dialog's two texts is shown and which of
     * `VaultDeletion`'s two operations the confirm runs.
     */
    private var pendingPurge by mutableStateOf(false)
    /**
     * An unlock or create attempt is running. **Separate from [importing] on
     * purpose.** These were one flag until an end-to-end run deadlocked on it:
     * a killed import stays ENQUEUED, which kept the flag true, which disabled
     * the passphrase field — and unlocking was the only thing that would let
     * that import finish. One flag, two unrelated meanings, no way out.
     */
    private var busy by mutableStateOf(false)

    /** Imports in flight. Never gates the unlock screen. */
    private var importing by mutableStateOf(false)

    /**
     * Whether a vault is open. `screen-off-lock-all-screens`: recovery and archive import
     * open through this flag and never through [keyringOpen], so an open here is handled
     * exactly as one there — see [opened].
     */
    private val unlockedState = mutableStateOf(false)
    private var unlocked: Boolean
        get() = unlockedState.value
        set(value) {
            val rising = value && !unlockedState.value
            unlockedState.value = value
            if (rising) opened()
        }

    /**
     * `vault-keyring`. Whether a **keyring** is open — which is what a passphrase
     * opens now, and is not the same as a vault being open.
     *
     * [unlocked] still means "a vault is open", because that is what every screen
     * below the list is about. The two states are the two levels: no keyring is the
     * lock screen, a keyring and no vault is the list, both is the grid.
     *
     * `screen-off-lock-all-screens`. Every open path sets this or [unlocked] from the UI
     * block that finishes it, and both setters call [opened] on the way up.
     */
    private val keyringOpenState = mutableStateOf(false)
    private var keyringOpen: Boolean
        get() = keyringOpenState.value
        set(value) {
            val rising = value && !keyringOpenState.value
            keyringOpenState.value = value
            if (rising) opened()
        }

    /**
     * `screen-off-lock-all-screens`. Something just opened, so two things are done: a kit
     * held across a lock is offered to the keyring now open, and the auto-lock policy is
     * asked again — a keyring that opened while the app was away (a creation, a recovery,
     * an archive import, a derivation finishing after the screen went off) is reported by
     * no lifecycle event. The recheck is **posted**, so it runs after the rest of the open
     * path's block, including the line that puts up a new kit for the lock's hook to seal.
     */
    private fun opened() {
        restoreHeldKit()
        resumeOnboarding()
        android.os.Handler(mainLooper).post { AutoLock.recheck() }
    }

    /**
     * `screen-off-lock-all-screens`, D5. A recovery kit that was on screen when the vault
     * locked: its words and vault name, sealed under the keyring that was open, by the
     * before-lock hook. Ciphertext under a key no longer in memory — only that keyring
     * opening again can read it, and any other keyring leaves it untouched and unmentioned.
     */
    @Volatile
    private var heldKit: ByteArray? = null

    /** `screen-off-lock-all-screens`, D1. The [AutoLock.awayEpoch] this activity last saw. */
    private var seenEpoch = 0

    /** `vault-keyring`. The open keyring's vaults, by name. Re-read after every write. */
    private var vaultEntries by mutableStateOf<List<VaultKeyring.Entry>>(emptyList())

    /**
     * `vault-list-refresh`, D9. The keyring position of the vault last opened — the row the
     * open vault grows out of and shrinks back into. Set as a vault finishes opening.
     */
    private var openPosition by mutableStateOf(-1)

    /** `vault-list-refresh`. The vault Edit vault is up for, or null. */
    private var editingVault by mutableStateOf<VaultKeyring.Entry?>(null)

    /**
     * Edit vault's unsaved name and appearance — `vault-passphrase-manage`, D5. Held here rather than
     * in the screen, so a trip to a passphrase screen and back leaves them as they were.
     */
    private var editDraftName by mutableStateOf("")
    private var editDraftAppearance by mutableStateOf(VaultAppearance.of(0))

    /** The passphrase screen pushed over Edit vault, or null — D6. */
    private var passphraseScreen by mutableStateOf<PassphraseAct?>(null)

    /** That screen's proving field was wrong — the error goes under it. */
    private var passphraseWrong by mutableStateOf(false)

    /** That screen's act did not save. */
    private var passphraseProblem by mutableStateOf<String?>(null)

    /** "Passphrase added." and its siblings on Edit vault, until it is left — D5. */
    private var passphraseStatus by mutableStateOf<String?>(null)

    /** `vault-keyring`. The vault waiting to be removed — its confirmation's flag. */
    /**
     * The vault the destroy confirmation is up for — `delete-vault`.
     *
     * A dialog rather than a screen since `delete-vault`, so this is not a branch in the
     * screen `when` any more: §6.7 asks destructive confirmations in a modal over the
     * surface that raised them, and both routes to it — the list's glyph and the vault
     * settings sheet's row — keep the user where they were.
     */
    private var destroyingVault by mutableStateOf<VaultKeyring.Entry?>(null)

    /**
     * The vault to destroy once the export finishes, or null — `delete-vault` D2.
     *
     * **Deliberately not durable.** If the process dies mid-export the vault is left
     * present and holding whatever did not come out, which is the correct half to keep;
     * the user destroys it again. A flag on disk saying "destroy this when you next
     * start" is a promise to delete a user's vault that survives the crash that broke
     * it.
     */
    private var destroyAfterExport by mutableStateOf<Int?>(null)

    /** Why a destroy did not finish, shown where the user chose it. */
    private var destroyProblem by mutableStateOf<String?>(null)

    /** `protected-vault-destroy-gate` D4. Which step of the destroy dialog is showing. */
    private var destroyStep by mutableStateOf(DestroyStep.Gate)

    /**
     * The key the gate unsealed, held between a passed gate and a choice — seconds, never on
     * disk. Each outcome takes a copy; [closeDestroy] zeroes this on every way out, lock included.
     */
    private var destroyKey: ByteArray? = null

    /** The vault's backup destination, read once with [destroyKey] when the gate passed. */
    private var destroyBackup by mutableStateOf<String?>(null)
    private var destroyChecking by mutableStateOf(false)
    private var destroyWrong by mutableStateOf(false)

    /** Bumped on every close, so a check finishing after its dialog has gone changes nothing. */
    private var destroyGeneration = 0

    /**
     * D5. The export screen is the return run, authorised by the gate: it asks for no
     * passphrase and offers no archive. Cleared by [closeExport] and on lock.
     */
    private var exportAuthorised by mutableStateOf(false)

    /** The return run's export has been handed to WorkManager; leaving the screen no longer cancels it. */
    private var returnStarted = false

    /** The return opened its vault from the list, so going back before it starts leaves it again. */
    private var returnOpenedVault = false

    /** `vault-keyring`. What the list says when a write was refused. */
    private var vaultListProblem by mutableStateOf<String?>(null)
    private var hasVault by mutableStateOf(false)
    private var unlockFailed by mutableStateOf(false)
    private var setupProgress by mutableStateOf<Pair<Int, Int>?>(null)
    private var notice by mutableStateOf<String?>(null)

    /**
     * P2-25. Set when an index this build cannot read turns out to have been
     * written by a *newer* one — the rollback case. Shown on the lock screen with
     * the runtime warnings, because that is where the user is returned to, and
     * cleared on the next unlock attempt like [unlockFailed].
     */
    private var newerVersion by mutableStateOf(false)

    /**
     * The vault's index pointer does not name an index, and the search on the unlock
     * path found nothing — `index-pointer-recovery`. Surfaced as a warning beside
     * [newerVersion], never as a crash.
     */
    private var indexUnreadable by mutableStateOf(false)

    /**
     * The index pointer was wrong and has been repaired — `index-pointer-recovery`.
     *
     * Shown on the unlock that repaired it and not afterwards, because after it there
     * is nothing to say: the vault opens normally from then on.
     */
    private var indexRelinked by mutableStateOf(false)

    /** P1-9. Recomputed on every arrival at the locked state and after a toggle. */
    private var bioEnabled by mutableStateOf(false)

    /**
     * What the device can do about biometric unlock — `unavailable-capability-states`.
     *
     * Read by [refreshBiometric] on every [onStart] and on return from Android's
     * enrolment screen, **never in a composition**. `canAuthenticate` is a binder call
     * into the biometric service, and this was originally read inside the grid's
     * composition — every recomposition made one, which on this emulator is enough
     * for ActivityManager to kill the process for "excessive binder traffic during
     * cached" when the app is backgrounded. One read per return to the foreground is
     * what lets a user who enrols in Settings come back to a row that says so.
     */
    private var bioState by mutableStateOf(BiometricState.NotSupported)

    /** `BiometricWrap.allStrong`, read with [bioState]: which rows, not whether. */
    private var bioAllStrong by mutableStateOf<Boolean?>(null)

    /**
     * `biometric-unlock-screen` D1. Which sensors exist, read once — a sensor does not appear
     * at runtime, and the activity is not recreated on rotation. `PackageManager` calls, so
     * never from a composition either.
     */
    private val bioHasFingerprint by lazy { BiometricWrap.hasFingerprint(this) }
    private val bioHasFace by lazy { BiometricWrap.hasFace(this) }
    private var bioNotice by mutableStateOf<Int?>(null)

    /**
     * The enrolment round trip is in flight — a plain field, not composition state:
     * nothing draws it. Read once in the result callback, which raises the enable
     * prompt if the trip ended with a strong biometric enrolled.
     */
    private var enrolThenEnable = false

    /**
     * What the platform calls the unlock screen's biometric control — `biometric-naming`.
     *
     * Read here, beside [bioState], for the same reason and on the same event: the
     * accessor is a binder call into `IAuthService`, so a read inside a composition
     * makes one per recomposition. Passed into the unlock screen as a parameter and
     * never read by a composable: I7 keeps `BiometricWrap` off the passphrase path, and
     * a label fetched in a composable would also make the screen undrivable from a
     * preview.
     */
    private var bioButtonLabel by mutableStateOf<String?>(null)

    /**
     * Whether the automatic prompt has already fired for this arrival at the lock
     * screen. It fires once: a prompt that reappears on every dismissal is a
     * screen the user cannot leave without force-stopping the app.
     */
    private var bioPrompted = false

    /**
     * P1-10. What the app can see about this device, read once at launch. Shown
     * and nothing else — no code below branches on it.
     */
    private var warnings by mutableStateOf<List<String>>(emptyList())

    /**
     * P1-11. What the tamper log recorded since the previous unlock. Shown and
     * nothing else — nothing below branches on it, including the tampering line.
     */
    private var digest by mutableStateOf<List<String>>(emptyList())

    /**
     * `intruder-captures-at-home` D7. Whether the open keyring's capture store holds captures the
     * user has not looked at.
     *
     * **Held once, here, and rendered in two places.** The home bar's drawer control and the home
     * drawer's Intruder capture row both read this; neither computes it. Refreshed at ingest and
     * whenever the Intruder capture screen changes the store; false whenever no keyring is open,
     * so a keyring opened next never shows the last one's badge.
     */
    private var capturesUnseen by mutableStateOf(false)

    /**
     * `per-vault-intruder-capture` D7. The open vault's [capturesUnseen]: whether its own capture
     * store holds captures not yet looked at. Drawn on the grid's drawer control and the vault
     * drawer's Intruder capture row from this one value; never on anything at home.
     */
    private var vaultCapturesUnseen by mutableStateOf(false)

    /**
     * P1-12. The 24 words, held only while the kit screen is up, and never
     * written anywhere. Non-null means the screen is showing.
     */
    private var recoveryKit by mutableStateOf<List<String>?>(null)
    private var recovering by mutableStateOf(false)

    /**
     * `recovery-practice` D5. The kit check is drawn in place of the creation kit on screen —
     * whichever one it is, since only one is ever up. Memory only; a lock drops it, so a
     * resumed kit shows its words with an empty acknowledgement.
     */
    private var kitChecking by mutableStateOf(false)

    /** D5. Back from the check: the words reopen with the acknowledgement already typed. */
    private var kitAcknowledged by mutableStateOf(false)

    /**
     * D6. The held kit on screen is the decoy's — a list of its own, printed as one under the
     * decoy's name. Carried through a lock in the held kit's status field.
     */
    private var recoveryKitDecoy by mutableStateOf(false)

    /** `recovery-practice` D3. The practice screens are up, after a due fingerprint unlock. */
    private var practiceShowing by mutableStateOf(false)
    private val practiceState = PracticeState()

    /** D7. Read once at start and on each toggle. */
    private var extraSecurityOn by mutableStateOf(true)
    private var recoveryFailed by mutableStateOf(false)

    /** P1-14. */
    private var importingArchive by mutableStateOf(false)
    private var archiveFile by mutableStateOf<Uri?>(null)
    private var archiveFailed by mutableStateOf(false)

    /** `recovery-own-slot-only`, D7. The open list has no free position. */
    private var archiveFull by mutableStateOf(false)

    /** The chosen file's display name, for the file button — never the URI's last segment. */
    private var archiveFileName by mutableStateOf<String?>(null)
    private var archiveProgress by mutableStateOf<Pair<Int, Int>?>(null)

    /** P1-16. Set when entitlement stopped an archive import, cleared on leaving. */
    private var archiveBlocked by mutableStateOf<String?>(null)

    /**
     * `backup-file-recover-and-import`, D7. What the chosen file or folder is, null while it
     * is read; whether it is a folder; and where the flow has got to. Cleared on leaving.
     */
    private var importKind by mutableStateOf<ImportKind?>(null)
    private var importFolder by mutableStateOf(false)
    private var importWordsFailed by mutableStateOf(false)
    private var importNeedsPassphrase by mutableStateOf(false)
    private var importInUse by mutableStateOf(false)
    private var importNoRoom by mutableStateOf(false)
    private var importSealing by mutableStateOf(false)
    private var importDone by mutableStateOf<ImportDone?>(null)

    /** A backup the chosen file or folder could not be read as, shown under its row. */
    private var importProblem by mutableStateOf<String?>(null)

    /**
     * `new-vault-setup-flow`. Which vault flow is up — New vault, from the list and the grid
     * header, or Add a passphrase, from Edit vault — or null.
     *
     * Everything the flow holds is held **here rather than in its steps**, `decoyStep`'s reason:
     * a `remember` inside a step dies when the step changes, which would make Back a way of losing
     * work. All of it dies with [closeVaultFlow] and none of it is written down. Nothing is
     * committed before the capture step's action (or How it opens', for a list-opened vault).
     */
    private var vaultFlow by mutableStateOf<VaultFlow?>(null)
    private var flowStep by mutableStateOf(NewVaultStep.Name)
    private var flowName by mutableStateOf("")
    private var flowAppearance by mutableStateOf(VaultAppearance.defaultFor(0))

    /** What the appearance was when the flow opened — D6's "changed from its default". */
    private var flowDefaultAppearance = VaultAppearance.defaultFor(0)

    /** How it opens: false is the list row, the default. */
    private var flowOwn by mutableStateOf(false)

    /**
     * The new passphrase and its second entry, as text in state — as every passphrase screen holds
     * them — and encoded to bytes only for the commit.
     */
    private val flowPass = NewPassphrase()

    /** Add a passphrase's list passphrase, as typed; and as bytes once proven, until the commit. */
    private var flowListTyped by mutableStateOf("")
    private var flowListBytes: ByteArray? = null
    private var flowListWrong by mutableStateOf(false)

    /** D5. The modes the capture step's switches say, written only with the commit. */
    private var setupCaptureModes by mutableStateOf<Set<Capture.Mode>>(emptySet())

    /** A proof or a commit is running: the action reads so, and Back and Cancel do nothing. */
    private var setupBusy by mutableStateOf(false)

    /** Name and look's refusal under the field — empty, full, or the entitlement gate (D4). */
    private var flowNameError by mutableStateOf<String?>(null)

    /** A commit that did not land, on the step that made it. */
    private var flowProblem by mutableStateOf<String?>(null)

    /** D6. The discard dialog is up. */
    private var flowDiscarding by mutableStateOf(false)

    /**
     * `per-vault-passphrases`. The vault whose own passphrase is being asked for, or
     * null. Set by tapping a locked row; cleared by opening it or by dismissing.
     */
    private var promptingVault by mutableStateOf<VaultKeyring.Entry?>(null)

    /** `per-vault-passphrases`. Set when the last attempt at that prompt did not open it. */
    private var vaultPassphraseFailed by mutableStateOf(false)

    /** `per-vault-passphrases`. The recover-one-vault screen, reached from the list. */
    private var recoveringVault by mutableStateOf(false)

    /** `per-vault-passphrases`. Set when the last vault recovery recovered nothing. */
    private var vaultRecoveryFailed by mutableStateOf(false)

    /**
     * `per-vault-passphrases`. The vault [recoveryKit]'s words belong to, or null when
     * they are the keyring's.
     *
     * The kit screen says a different thing for each, because a keyring's words no
     * longer open a vault that carries its own passphrase.
     */
    private var recoveryKitVault by mutableStateOf<String?>(null)

    /**
     * At creation, how many views the kit on screen will still have — `vault-passphrase-manage`, D3.
     * Three except when a vault's passphrase is added again with its count carried.
     */
    private var recoveryKitCreationLeft by mutableStateOf(VaultKeyring.KIT_VIEWS)

    /**
     * `recovery-kit-three-views`, D4. Non-null while [recoveryKit] is a **later view** from the
     * drawer rather than the kit at creation: the review form, with its status line.
     */
    private var recoveryKitStatus by mutableStateOf<KitStatus?>(null)

    /**
     * `recovery-kit-three-views`. The kit a drawer row opened the view screen for: -1 the open
     * list's own, else the position of a vault with its own passphrase; null when not open.
     */
    private var kitViewFor by mutableStateOf<Int?>(null)
    private var kitViewBusy by mutableStateOf(false)
    private var kitViewWrong by mutableStateOf(false)
    private var kitViewFailed by mutableStateOf(false)

    /**
     * Bumped by every view spent. A list view changes no entry, so [vaultEntries] compares equal
     * after it; the drawer reads this so its row recomposes with the new count.
     */
    private var kitViewsSpent by mutableStateOf(0)

    /** P2-2. The decoy flow: its own steps, its own picker, its own refusals. */
    private var decoying by mutableStateOf(false)
    private var decoyProblem by mutableStateOf<String?>(null)
    private var decoySummary by mutableStateOf<DecoySummary?>(null)

    /**
     * `decoy-flow-overhaul`. Where in the flow the user is, and what they have
     * entered so far.
     *
     * **Held here rather than in the step composables** so that going back preserves
     * what was typed: a `remember` inside a step is discarded the moment the step
     * changes, which would make Back a way of losing work rather than of correcting
     * it. Everything here dies with [closeDecoy] and none of it is written down —
     * a flow position that survived the process would be a record that the flow ran.
     */
    private var decoyStep by mutableStateOf(DecoyFlow.Step.Intro)
    private var decoySheet by mutableStateOf<DecoySheet?>(null)
    private var decoyName by mutableStateOf("")
    private var decoyAuthFailed by mutableStateOf(false)

    /**
     * The decoy's own passphrase, carried from step 3 to the build as **bytes**.
     *
     * Never a `String` field: `KeyMaterialScanTest` fails the build on one, because a
     * `String` cannot be zeroed and survives until GC. That is also why the two
     * passphrase fields stay local to their step and are not restored by Back — the
     * name comes back, key material does not.
     */
    private var decoyPassBytes: ByteArray? = null

    /**
     * The passphrases of *other* vaults to preserve — never the one open, which
     * `VaultLifecycle.create` excludes from session state without being told.
     *
     * A `mutableStateListOf` so the sheet's rows track it, and owned here so the
     * bytes survive the sheet closing and are zeroed once, by [closeDecoy], however
     * the flow is left.
     */
    private val decoyPreserve = mutableStateListOf<ByteArray>()

    /**
     * The decoy's own 24 words, held from the moment it is built until the handover
     * step hands them over.
     *
     * Not put straight into `recoveryKit`, because that branch sits above the flow's
     * in the `when` and would draw the kit over the handover step — showing the user
     * words for a vault they had not yet been shown.
     */
    private var decoyKitWords by mutableStateOf<List<String>?>(null)

    /**
     * Whether the handover step has been left for the decoy's own recovery kit.
     *
     * **The kit is drawn inside this flow rather than handed to the app's kit
     * branch**, because that branch stands down while first run is in progress —
     * `kit != null && onboardingStep == null`. The decoy flow is reachable from
     * first run's own decoy step, so routing the words through that branch would
     * have shown them from Settings and silently skipped them during onboarding,
     * which is the one path where the user is least likely to know they were owed
     * a kit at all.
     */
    private var decoyShowingKit by mutableStateOf(false)

    /**
     * Whether the decoy flow has completed since the app started.
     *
     * The one thing first run's decoy step needs to know, and the one form it may
     * take: memory, for this process, written nowhere. A flow resumed after a
     * process death redraws step 5 as untouched, which costs a label and nothing
     * else — a decoy that survived in a preference would be the marker the whole
     * capability exists to avoid.
     */
    private var decoyBuiltThisRun by mutableStateOf(false)

    /**
     * Whether the open keyring records a decoy — the drawer's row, and the gate on
     * building a second one.
     *
     * **Not [decoyBuiltThisRun], and the two are not redundant.** That one is first
     * run's, lives for one process by design, and answers "did the step just ahead of
     * this one run". This one is read back out of the sealed keyring block, so it
     * survives the process and answers "does this install already have one" — the
     * question the settings sheet was getting wrong, because nothing was left to ask.
     *
     * Set wherever [keyringOpen] is, from the session that has just opened or closed;
     * false whenever no keyring is open, and false inside the decoy, whose own keyring
     * carries no such bit.
     */
    private var decoySetUp by mutableStateOf(false)

    /**
     * The user's own passphrase, kept for the length of the build so the flow can put
     * them back where it found them.
     *
     * **Without this the app strands you in the decoy.** `create` opens the new
     * keyring and closes the one it was called from, and the master passphrase is
     * zeroed the moment it is used — so there was no way back, and first run simply
     * carried on with the decoy open. Steps 6, 7 and 8 would then have mirrored the
     * decoy to the backup folder, enrolled the panic credential against the decoy,
     * and set the app identity from inside it.
     *
     * The cost is named rather than hidden: the master passphrase is in memory as
     * bytes from the authorisation sheet until the return unlock consumes it, which
     * is the length of one decoy build. It is zeroed on every path out — the return,
     * [closeDecoy], and a failure in between.
     */
    private var decoyReturnPass: ByteArray? = null

    /** Which vault was open when the flow started, so the return reopens that one. */
    private var decoyReturnPosition: Int = -1

    /**
     * P2-2. What the picker returned and what [Decoy] decided about each one, held
     * only while the screen is up. Parallel lists rather than a class, because they
     * are consumed together and immediately, and nothing persists either of them.
     */
    private var decoyPicked: List<Uri> = emptyList()
    private var decoyPlan: List<Decoy.Decision> = emptyList()

    /** P2-2. Fixed when the flow is entered, so the window does not slide under it. */
    private var decoyStartedAt = 0L

    /** P2-3. The enrolment surface. Nothing else in the app knows this feature exists. */
    private var panicScreen by mutableStateOf(false)
    /** `panic-phrase`. Read from the open keyring's record, never from a preference. */
    private var panicEnabled by mutableStateOf(false)
    private var panicProblem by mutableStateOf<PanicProblem?>(null)
    private var panicBusy by mutableStateOf(false)
    /** Bumped on each success, so the screen closes its sheet and empties its fields. */
    private var panicSettled by mutableStateOf(0)

    /** P2-4. The intruder-capture enrolment surface and the modes currently on. */
    /**
     * `intruder-capture-sheet`. Enrolment is a sheet over the grid, not a branch of
     * the screen `when` — it changes three reversible things and comes back, so the
     * folder the user was standing in stays behind it.
     */
    private var captureSheet by mutableStateOf(false)
    private var captureModes by mutableStateOf<Set<Capture.Mode>>(emptySet())
    private var captureProblem by mutableStateOf<String?>(null)

    /** P2-4. The mode whose permission request is in flight, so the result knows which to finish. */
    private var pendingCaptureMode: Capture.Mode? = null

    // --- `intruder-captures-at-home`: the Intruder capture screen and its flows ----------------

    /** The screen, opened from the home drawer's row. */
    private var intruderScreen by mutableStateOf(false)

    /**
     * `per-vault-intruder-capture` D6. The screen is the open vault's timeline, opened from its
     * drawer: [captureKey] is then the vault's capture store key, and the sheet sets the vault's
     * own modes. False at home.
     */
    private var captureVault by mutableStateOf(false)

    /**
     * D8, D9. The capture permissions held and whether the app is exempt from Android's
     * unused-app reset, as of the last resume — the banners and the sheet's rows read these.
     */
    private var capturePermsHeld by mutableStateOf<Set<Int>>(emptySet())
    private var autoRevokeExempt by mutableStateOf(true)

    /** D8. A mode turned on while Android's settings page was opened for its permission. */
    private var pendingSettingsMode: Capture.Mode? = null

    /**
     * The keyring's capture store key, held only while the screen is open — D1. Zeroed on leave
     * and on lock. Not Compose state: nothing draws it.
     */
    private var captureKey: ByteArray? = null

    /** The store's index, for its thumbnails, and the timeline built from it and its log. */
    private var captureIndex by mutableStateOf(MetadataDb.empty())
    private var captureRows by mutableStateOf<List<CaptureLog.Row>>(emptyList())

    /** False until the store has been read, so the empty state never flashes over captures. */
    private var capturesLoaded by mutableStateOf(false)
    private var captureSelection by mutableStateOf<Set<String>>(emptySet())

    /** The capture open in the viewer. */
    private var captureOpen by mutableStateOf<VaultItem?>(null)

    /** "Moved to {vault}", "Deleted", or a refusal — the screen's notice slot. */
    private var captureNotice by mutableStateOf<String?>(null)

    /** The captures being moved: non-null shows Move to vault. */
    private var captureMoving by mutableStateOf<List<VaultItem>?>(null)

    /** A vault with its own passphrase, picked for the move: its passphrase sheet is up. */
    private var captureMovePrompt by mutableStateOf<VaultKeyring.Entry?>(null)
    private var captureMoveFailed by mutableStateOf(false)

    /** The vault the captures are going into, once it has opened for the move. */
    private var captureMoveTarget by mutableStateOf<VaultKeyring.Entry?>(null)

    /** That vault's folders: non-null shows the folder picker over it. */
    private var captureMoveFolders by mutableStateOf<List<VaultFolder>?>(null)

    /**
     * The KEK a protected vault's passphrase gave, held from the passphrase to the commit or the
     * cancel so the vault's key can be unsealed again at the commit — D5. Null for a vault the
     * keyring key opens. Zeroed on commit, cancel, back and lock.
     */
    private var captureMoveKek: ByteArray? = null
    private var captureMoveBusy by mutableStateOf(false)
    private var captureMoveProblem by mutableStateOf<String?>(null)

    /** The captures awaiting the delete confirmation. */
    private var captureDeleting by mutableStateOf<List<VaultItem>?>(null)

    /** The export screen is exporting captures from the store, not a vault's items — D9. */
    private var exportCaptures by mutableStateOf(false)

    /**
     * P2-17. The camera screen, and whether `CAMERA` is granted as of the last time
     * anything asked. Held as state rather than read in the composition so that the
     * permission dialog's answer redraws the screen behind it.
     */
    private var cameraScreen by mutableStateOf(false)
    private var cameraPermitted by mutableStateOf(false)
    private var cameraProblem by mutableStateOf<String?>(null)

    /**
     * `camera-capture-feedback`. The last capture of the open camera session — the
     * row behind the receipt in the corner, and what tapping it opens.
     *
     * **This session's, and cleared when the camera opens and closes.** A camera's
     * corner thumbnail normally shows the vault's most recent item whenever it was
     * made; here that would put an old photograph on screen in front of whoever is
     * behind the phone. `CameraScreen`'s own note carries the reasoning.
     */
    private var lastCapture by mutableStateOf<VaultItem?>(null)

    /** True from the moment a capture is handed over until it is committed or fails. */
    private var captureSaving by mutableStateOf(false)

    /**
     * The folder the last capture went into, so opening it lands on a list that
     * contains it. Not state: it is read at the moment the receipt is tapped, and
     * nothing draws from it.
     */
    private var lastCapturePath: List<ByteArray> = emptyList()

    // --- the voice memo screen (`voice-memo`) ---------------------------------

    private var voiceMemoScreen by mutableStateOf(false)
    private var voiceMemoPermitted by mutableStateOf(false)
    private var voiceMemoProblem by mutableStateOf<String?>(null)

    /** Whether the memo screen holds a recording that leaving would destroy. */
    private var memoUnsaved by mutableStateOf(false)

    /** Whether the leave-and-lose-it confirmation is up. */
    private var confirmingMemoDiscard by mutableStateOf(false)

    /**
     * `destination-settings-and-slideshow` D5. [VoiceMemo.changes], as state: the memo lives in
     * [VoiceMemo] for the process, and this is what redraws its screen when it changes — a Stop
     * from the notification included.
     */
    private var memoChanges by mutableIntStateOf(0)

    /** D6. The once-said sentence when notifications were refused and the shade has no Stop. */
    private var memoNotice by mutableStateOf<String?>(null)

    /**
     * D5. The open vault's capture **public** key, set as it opens. A memo belongs to the vault its
     * record is sealed to, and this is how the memo screen knows it is that vault: another vault's
     * memo is never shown, saved or discarded here. Not secret — it seals, and opens nothing.
     */
    @Volatile
    private var openCaptureKey: ByteArray? = null

    /** The memo in hand, if it belongs to the vault that is open. */
    private fun memoHere(): VoiceMemo.Memo? =
        VoiceMemo.current?.takeIf { memo -> openCaptureKey?.contentEquals(memo.publicKey) == true }

    /**
     * P2-17. A recording in flight: the write end of the pipe the recorder holds, the
     * thread encrypting the read end, and where that thread leaves its result.
     *
     * Not state — nothing composes on them. The blob IDs are claimed as they are
     * written so an abandoned recording can delete exactly what it wrote.
     */
    // Volatile: the before-lock hook reads both off the main thread — D6.
    @Volatile
    private var recordingWrite: android.os.ParcelFileDescriptor? = null
    @Volatile
    private var recordingThread: Thread? = null
    private val recordingBlobs = mutableListOf<ByteArray>()

    @Volatile
    private var recordingWritten: WrittenItem? = null

    /**
     * A finished camera recording and every blob its write claimed — P2-17. A memo is
     * [VoiceMemo]'s and never comes through here (`destination-settings-and-slideshow` D5).
     */
    private class Recorded(val written: WrittenItem, val claimed: List<ByteArray>)


    /**
     * P1-16, `one-time-purchase` D2. Play's answer as Compose state, written only by
     * [syncBilling] — which [Billing.onChange] calls the moment Play answers, so a
     * purchase completed over a refusal lifts that refusal where it stands.
     */
    private var owned by mutableStateOf(false)
    private var purchasePending by mutableStateOf(false)
    private var price by mutableStateOf<String?>(null)

    /**
     * Bumped on every billing answer. [addAllowed] reads it so a composition that
     * asks the gate is re-asked when the answer changes — including null → false,
     * which moves no other state here.
     */
    private var billingRev by mutableIntStateOf(0)

    private fun syncBilling() {
        owned = Billing.owned == true
        purchasePending = Billing.pending
        price = Billing.price
        billingRev++
    }

    /**
     * [Entitlement.importAllowed] as composition reads it — the same answer, with
     * the read of [billingRev] that makes it recompose. The one question both the
     * send refusal and the keep refusal ask.
     */
    private fun addAllowed(): Boolean {
        billingRev
        return Entitlement.importAllowed(this)
    }

    /** P1-16. Why the purchase could not be offered, when it could not be. */
    private var purchaseProblem by mutableStateOf<String?>(null)

    /**
     * P4-2. The item the send flow is running for, or null when it is not running.
     *
     * The code, the container and the progress live beside it and are cleared
     * together in [endShare] — **nothing here outlives the flow**, which is the
     * whole of §7.19's "shown once and is not saved".
     */
    /**
     * P4-3. The receive flow's state, cleared together in [endShareOpen].
     *
     * [shareOpened] holds a blob that is sealed but **not in the index** — keeping
     * the item adds the row, leaving deletes the blob. That ordering is
     * `ImportPipeline`'s and it is why a share the user walks away from never
     * briefly existed in their vault.
     */
    /**
     * P4-4. Pairing and the contact list — install state, not vault state, so none
     * of it is cleared when a vault closes.
     *
     * [identityState] is read when either screen opens rather than held live: the
     * file is small, it changes only from these two screens, and a cached copy is
     * one more thing that can be wrong after a *Start a new identity*.
     */
    private var contactsScreen by mutableStateOf(false)

    /**
     * `debug-crash-log-screen`. Which step of the debug build's crash log is showing — the
     * screen, the open record's file name, the Clear all confirmation. The screen owns the
     * files; these only say what is drawn, so the one back handler can unwind them.
     */
    private var crashLogScreen by mutableStateOf(false)
    private var crashRecord by mutableStateOf<String?>(null)
    private var confirmingCrashClear by mutableStateOf(false)

    private var pairingScreen by mutableStateOf(false)
    private var pairingStep by mutableStateOf(PairingStep.Show)
    private var identityState by mutableStateOf<Identity.State>(Identity.State.Unreadable)
    private var pairingScanned by mutableStateOf<ByteArray?>(null)
    private var pairingName by mutableStateOf("")
    private var pairingMessage by mutableStateOf<String?>(null)
    private var pairingSaved by mutableStateOf<String?>(null)
    private var removingContact by mutableStateOf<Identity.Contact?>(null)
    private var confirmingNewIdentity by mutableStateOf(false)

    /**
     * `remote-pairing`. The add-a-contact choice, the remote flow's step, the picked
     * invite the responder is answering, and the sessions listed under Waiting. Install
     * state like the rest of this block: a lock between the share sheet and the reply
     * does not end a pairing.
     */
    private var addingContact by mutableStateOf(false)
    private var remoteStep by mutableStateOf<RemoteStep?>(null)
    private var remoteInvite by mutableStateOf<ByteArray?>(null)
    private var pairingWaiting by mutableStateOf<List<Identity.PairingSession>>(emptyList())

    private var openingShare by mutableStateOf(false)
    private var shareStaged by mutableStateOf<java.io.File?>(null)
    private var shareWords by mutableStateOf(List(ShareCode.WORD_COUNT) { "" })
    private var shareOpenBusy by mutableStateOf(false)
    private var shareOpenFailed by mutableStateOf(false)
    private var shareRefusal by mutableStateOf<ShareOpen.Refusal?>(null)
    private var shareOpened by mutableStateOf<ShareOpen.Outcome.Opened?>(null)
    /**
     * `share-format-v2`. The file names no mode, so the picked file is probed with
     * this install's identity before the recipient is asked anything: whether the
     * probe is running, whether it found the file sealed here, and whether this
     * phone could not read its identity at all — in which case no probe runs.
     */
    private var shareProbing by mutableStateOf(false)
    private var shareSealedHere by mutableStateOf(false)
    private var shareIdentityUnreadable by mutableStateOf(false)

    /**
     * The received item being looked at, as a row that exists **only here**.
     *
     * Not in the index and never written to it: the viewer takes a `VaultItem`, and
     * this is one built around the sealed blob so the received item is drawn by the
     * same code every other item is. Keeping it is what commits an identical row.
     */
    private var sharePreview by mutableStateOf<VaultItem?>(null)

    private var sharingItem by mutableStateOf<VaultItem?>(null)
    private var shareCode by mutableStateOf<List<String>?>(null)
    /** P4-5. The contact a container was sealed to, for the send state that carries no code. */
    private var shareSealedTo by mutableStateOf<String?>(null)
    /**
     * The recipients the send screen may offer — read when the flow opens, and
     * through [Identity.contacts] rather than [Identity.state], so composing a
     * code share does not mint an identity for somebody who never pairs.
     */
    private var shareContacts by mutableStateOf<List<Identity.Contact>>(emptyList())
    /** `remote-pairing` D6. The compose state's choices, here so a trip to pairing keeps them. */
    private var shareSelections by mutableStateOf(ShareSelections(recipient = null))
    private var shareFile by mutableStateOf<java.io.File?>(null)
    /** `FORMAT.md` §10 item 51. The last save-to-device result, for the send state's line. */
    private var shareSaved by mutableStateOf<Boolean?>(null)
    private var shareProgress by mutableStateOf<Pair<Long, Long>?>(null)
    private var shareFailed by mutableStateOf<String?>(null)
    private var shareBusy by mutableStateOf(false)

    /** P1-13. */
    private var exporting by mutableStateOf(false)
    private var exportAuthFailed by mutableStateOf(false)
    private var exportTree by mutableStateOf<android.net.Uri?>(null)
    private var exportProgress by mutableStateOf<Triple<Int, Int, Int>?>(null)

    /**
     * `first-run-onboarding`. The step on screen, or null when the flow is not
     * running.
     *
     * Held in the activity rather than read from `Onboarding` on every
     * recomposition: the prefs value is the durable record of how far first run
     * got, this is what is being drawn. They are written together in
     * [advanceOnboarding] and never diverge.
     */
    private var onboardingStep by mutableStateOf<Onboarding.Step?>(null)

    /**
     * Whether the welcome has been passed. Deliberately **not** persisted: the
     * welcome stands before the passphrase, and a flow interrupted there has no vault
     * and starts again at the welcome, so there is no later launch for this to
     * survive into, and it is one less thing recorded about a user's install.
     */
    private var welcomeSeen by mutableStateOf(false)

    /**
     * Whether first run's language screen has been passed — `onboarding-language-screen`.
     * Not persisted, for [welcomeSeen]'s reason: before step 1 there is no vault, and a
     * restart begins first run again here, with the language already chosen selected.
     */
    private var languageSeen by mutableStateOf(false)

    /** Why a vault was not created, shown on the step that tried. */
    private var setupProblem by mutableStateOf<String?>(null)

    /**
     * `first-vault-naming` D3. The first vault's name and look, as Name and look left them.
     * **Never saveable**: a saved instance bundle is written to disk by the system, and a
     * vault's name lives nowhere outside the sealed keyring. A process that dies before
     * "Create vault" starts again at the language screen, as [languageSeen] already does.
     * Cleared once the vault exists.
     */
    private var firstName by mutableStateOf("")
    private var firstAppearance by mutableStateOf(VaultAppearance.defaultFor(0))

    /** Name and look's refusal, under its field — `first-vault-naming` D4. */
    private var firstNameError by mutableStateOf<String?>(null)

    /**
     * `cloud-mirror`. The backup screen and what it is doing.
     *
     * [restoringBackup] decides which job the folder picker is doing — it is the
     * same picker for both, because the mirror and the restore read the same
     * folder. [mirrorDestination] is the folder as the user would name it.
     *
     * `backup-screen-status-first`. [backupStatus] is the open vault's, derived from
     * listing its folder and never stored; null is a vault whose folder has not been
     * listed yet this session, which the drawer reads as "On". It holds counts only —
     * naming a blob on a screen would be the vault-to-blob mapping I8 forbids.
     */
    private var backupScreen by mutableStateOf(false)
    private var restoringBackup by mutableStateOf(false)
    private var mirrorDestination by mutableStateOf<String?>(null)
    private var mirrorProblem by mutableStateOf<String?>(null)
    private var backupStatus by mutableStateOf<BackupStatus?>(null)
    private var restoreState by mutableStateOf<RestoreState>(RestoreState.Idle)

    /**
     * `switch-builds-without-losing-vaults` D1, D3. The file chosen on the restore screen with no
     * vault list on the device, while it is read for its kind and, as a `.vault`, opened.
     */
    private var restoreFile by mutableStateOf<RestoreFile?>(null)
    private var restoreFileUri: Uri? = null

    /**
     * D3. The vault a `.vault` file brought in, held until "Create vault" makes the list around
     * it. **Never saveable**, as [firstName]: its key is in memory only, zeroed at creation, on
     * Back to the welcome (which also deletes its blobs), and in [onDestroy].
     */
    private var heldArchive: VaultLifecycle.HeldArchive? = null

    /** D4. The held vault's file name and item count, for step 1's line. */
    private var heldFrom by mutableStateOf<Pair<String, Int>?>(null)

    /**
     * `backup-to-cloud-apps` D7, `backup-file-recover-and-import` D8. The backup chosen to
     * recover from — a file or an SD-card folder — held in memory across the words screen
     * until Remove, a lock, or leaving.
     */
    private var pendingBackup by mutableStateOf<ChosenBackup?>(null)

    /** A backup as Recover shows it: where it is, whether it is a folder, and its name. */
    private class ChosenBackup(val uri: Uri, val folder: Boolean, val name: String)

    /**
     * Which screen a backup file or folder is being picked for. The folder picker is shared
     * with the mirror, which [restoringBackup] tells apart; this says which restore.
     */
    private enum class BackupPick { RESTORE, IMPORT, RECOVER }

    /** `new-vault-setup-flow`. The two vault flows. */
    private enum class VaultFlow { New, Add }

    private var backupPick = BackupPick.RESTORE

    /**
     * `recovery-own-slot-only`, D4. The recovery about to run follows a backup restored onto
     * an empty phone — a backup file, or a mirror folder — so `recover` writes a fresh slot
     * file. False for recovery from the lock screen, which reseals only the list's own slot.
     */
    private var restoringOntoEmpty by mutableStateOf(false)

    /** The new passphrase already opens something here — shown under the first field. */
    private var recoveryInUse by mutableStateOf(false)

    /** D8. A refusal about the chosen backup, shown under its row on the words step. */
    private var recoveryBackupProblem by mutableStateOf<String?>(null)
    private var recoveryProgress by mutableStateOf<Pair<Int, Int>?>(null)
    private var recoverySealing by mutableStateOf(false)

    private val mnemonic by lazy { Mnemonic.fromResources(this) }

    /**
     * System photo picker. Needs no `READ_MEDIA_*`; that is why it is the only
     * option.
     *
     * The result callback clears P1-10's auto-lock suppression **before** doing
     * anything with the URIs, so a cancelled pick re-arms the lock as fast as a
     * successful one.
     */
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        AutoLock.awaitingResult(false)
        importAll(uris, fromGallery = true)
    }

    /**
     * P2-2's picker. Separate contract from [pickMedia] because its result goes to
     * the decoy plan rather than straight into an import — nothing is written until
     * the user has seen what the dates will be and confirmed.
     */
    private val pickDecoyMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Decoy.MAX_ITEMS)
    ) { uris ->
        AutoLock.awaitingResult(false)
        planDecoy(uris)
    }

    /**
     * Android's biometric enrolment screen — `unavailable-capability-states`. The hold
     * is released first, as every picker's is; then the state is re-read, and if a
     * strong biometric is now enrolled the enable prompt is raised once: the user
     * asked to use biometric unlock, and setting one up was the step in the way.
     * Cancelling that prompt leaves it off, and nothing raises it again.
     */
    private val enrolBiometric = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        AutoLock.awaitingResult(false)
        refreshBiometric()
        val raise = enrolThenEnable && bioState.ready && !bioEnabled
        enrolThenEnable = false
        if (raise) enableBiometric()
    }

    /** For anything the photo picker will not offer. Also permissionless. */
    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        AutoLock.awaitingResult(false)
        importAll(uris)
    }

    /**
     * `remove-originals-on-import`. Android's own delete confirmation. The app deletes
     * nothing: it hands Android the items, and Android shows them and asks.
     */
    private val deleteOriginals = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        AutoLock.awaitingResult(false)
        removingOriginals = false
        val sent = originalsSent
        originalsSent = emptyList()
        // Locked while Android's dialog was up: Android did what the user allowed, and
        // there is no vault open to record it in or to tell.
        if (result.resultCode != RESULT_OK || !unlocked) return@registerForActivityResult
        originalsOffer = originalsOffer?.without(sent)?.takeUnless { it.isEmpty }
        if (originalsOffer == null) originalsRemoved = true
        thread {
            TamperLog.append(blobs, TamperLog.SOURCE_DELETED, TamperLog.countPayload(sent.size))
        }
    }

    /**
     * P1-13's SAF destination. Permissionless, and the grant is persisted so the
     * export worker can still write after the activity is gone.
     */
    private val pickExportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        AutoLock.awaitingResult(false)
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        exportTree = uri
    }

    /**
     * `cloud-mirror`. The folder the mirror writes into, and the folder a restore
     * reads back from — one contract for both, because they are the same folder.
     *
     * The grant is persistable so the worker can write to it on a later launch;
     * that URI is the only new persisted state this feature has.
     */
    private val pickMirrorTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> mirrorTreePicked(uri) }

    /**
     * `backup-to-cloud-apps` D1. A removable volume's row opens the folder picker already
     * at that volume (`StorageVolume.createOpenDocumentTreeIntent`), which is a plain
     * intent rather than a contract, so its result is read here and handed on.
     */
    private val pickVolumeTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> mirrorTreePicked(result.data?.data) }

    /**
     * Where both folder pickers land. **The phone's own storage is refused before a grant
     * is taken** — `backup-to-cloud-apps` D2: a copy there is lost with the phone, and
     * refusing first means there is nothing to hand back.
     */
    private fun mirrorTreePicked(uri: android.net.Uri?) {
        AutoLock.awaitingResult(false)
        if (uri == null) return
        if (!restoringBackup && CloudMirror.isInternalStorage(uri)) {
            mirrorProblem = getString(R.string.backup_folder_on_phone)
            return
        }
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (!restoringBackup) adoptMirrorFolder(uri)
        else when (backupPick) {
            BackupPick.RESTORE -> adoptRestoreFolder(uri)
            BackupPick.IMPORT -> importChose(uri, folder = true)
            BackupPick.RECOVER -> recoverChose(uri, folder = true)
        }
    }

    /**
     * `backup-to-cloud-apps` D2. The cloud app a row named, for the Save sheet's initial
     * location. Held only across the sheet's round trip.
     */
    private var backupAuthority: String? = null

    /**
     * A cloud app's backup file — `backup-to-cloud-apps` D2. The Save sheet, pointed at the
     * app the person chose where the platform honours `EXTRA_INITIAL_URI`, titled with a
     * name that does not say "vault". The file it returns is the destination: persisted
     * read and write, stored in the vault's index, written now.
     */
    private val createBackupFile = registerForActivityResult(
        object : ActivityResultContracts.CreateDocument("application/octet-stream") {
            override fun createIntent(context: android.content.Context, input: String) =
                super.createIntent(context, input).apply {
                    backupAuthority?.let {
                        putExtra(
                            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                            android.provider.DocumentsContract.buildRootsUri(it),
                        )
                    }
                }
        }
    ) { uri ->
        AutoLock.awaitingResult(false)
        backupAuthority = null
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        adoptBackupFile(uri)
    }

    /**
     * `backup-to-cloud-apps` D7. The backup file a restore reads — any type, because the
     * provider may report one it guessed. Nothing is unpacked here: the table is sealed,
     * so the words come first.
     */
    private val openBackupFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        AutoLock.awaitingResult(false)
        if (uri == null) return@registerForActivityResult
        // The file restored from is the file this vault backs up to: its index records the
        // same document. Keeping the grant lets backup carry on into it after the restore
        // instead of leaving it behind at the provider and saving a second one. Best effort —
        // a provider that grants read only leaves the vault to choose again.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (backupPick == BackupPick.RECOVER) {
            recoverChose(uri, folder = false)
            return@registerForActivityResult
        }
        // `backup-to-cloud-apps` D13. With the list open, the backup is one of its vaults.
        if (VaultKeyringSession.isOpen) {
            restoreFileIntoList(uri)
            return@registerForActivityResult
        }
        // `switch-builds-without-losing-vaults` D1. No list here: a backup or a `.vault`.
        if (!lifecycleOps.vaultExists()) {
            restoreChoseOnEmpty(uri)
            return@registerForActivityResult
        }
        if (CloudMirror.localEntries(blobsDir()).isNotEmpty()) {
            restoreState = RestoreState.Problem(
                getString(R.string.restore_device_not_empty)
            )
            return@registerForActivityResult
        }
        restoreOntoEmpty(ChosenBackup(uri, folder = false, name = displayName(uri)))
    }

    /**
     * The empty-phone restore — `recovery-own-slot-only` D4. The backup goes to Recover with
     * its row shown and not removable, and is copied there, on step 2, once the words open it.
     */
    private fun restoreOntoEmpty(chosen: ChosenBackup) {
        pendingBackup = chosen
        restoringOntoEmpty = true
        restoreState = RestoreState.Idle
        backupScreen = false
        recoveryFailed = false
        recoveryBackupProblem = null
        recovering = true
    }

    /**
     * `switch-builds-without-losing-vaults` D1. A file chosen on the restore screen of a device
     * with no list, read for its kind by its structure, as Import reads it: a backup continues
     * to the recovery kit as it always has; anything else is a `.vault`, which asks for its
     * passphrase here. The file's row stands while its head is read.
     */
    private fun restoreChoseOnEmpty(uri: Uri) {
        val name = displayName(uri)
        restoreFileUri = uri
        restoreFile = RestoreFile(name, vault = false)
        restoreState = RestoreState.Idle
        thread {
            val backup = runCatching { isBackupFile(uri) }
                .onFailure { Log.w("BackupRestore", "the chosen file could not be read", it) }
                .getOrNull()
            runOnUiThread {
                if (restoreFileUri != uri) return@runOnUiThread
                when (backup) {
                    null -> {
                        clearRestoreFile()
                        // Its kind is unknown, so the file is not called a backup.
                        restoreState = RestoreState.Problem(getString(R.string.restore_chosen_unreadable))
                    }
                    true -> {
                        clearRestoreFile()
                        if (CloudMirror.localEntries(blobsDir()).isNotEmpty()) {
                            restoreState = RestoreState.Problem(getString(R.string.restore_device_not_empty))
                        } else {
                            restoreOntoEmpty(ChosenBackup(uri, folder = false, name = name))
                        }
                    }
                    false -> {
                        // Import's rule: no grant is kept behind a `.vault`.
                        runCatching {
                            contentResolver.releasePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        restoreFile = RestoreFile(name, vault = true)
                    }
                }
            }
        }
    }

    private fun clearRestoreFile() {
        restoreFile = null
        restoreFileUri = null
    }

    /**
     * D3. Reads the chosen `.vault` into the empty store and holds its vault for "Create vault";
     * first run then continues at step 1. On the Play build the entitlement check comes first,
     * as Import's does (D4). A failure is Import's one message, under the emptied field.
     */
    private fun openRestoreVaultFile(passphrase: ByteArray) {
        val uri = restoreFileUri
        val file = restoreFile
        if (uri == null || file == null) return passphrase.fill(0)
        if (!Entitlement.importAllowed(this)) {
            passphrase.fill(0)
            restoreFile = file.copy(problem = getString(Entitlement.BLOCKED))
            return
        }
        restoreFile = file.copy(problem = null)
        restoreState = RestoreState.Copying(0, 0)
        thread {
            val held = runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    lifecycleOps.readArchiveForFirstList(input, passphrase) { done, total ->
                        runOnUiThread { restoreState = RestoreState.Copying(done, total) }
                    }
                }
            }.onFailure {
                passphrase.fill(0)
                Log.w("VaultArchive", "the .vault file could not be read", it)
            }.getOrNull()
            runOnUiThread {
                restoreState = RestoreState.Idle
                if (held == null) {
                    restoreFile = restoreFile?.copy(problem = getString(R.string.import_failed))
                    return@runOnUiThread
                }
                heldArchive = held
                heldFrom = file.name to held.items
                clearRestoreFile()
                backupScreen = false
            }
        }
    }

    /** D5. The held vault leaves: its blobs are deleted and its key zeroed. */
    private fun discardHeldArchive() {
        heldArchive?.let(lifecycleOps::discardHeld)
        heldArchive = null
        heldFrom = null
    }

    /** Whether the file at [uri] is a backup by its structure; anything else is tried as a `.vault`. */
    private fun isBackupFile(uri: Uri): Boolean {
        val head = ByteArray(BackupFile.HEAD_BYTES)
        val read = contentResolver.openInputStream(uri)!!.use { input ->
            var n = 0
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            n
        }
        return read == head.size && BackupFile.isBackup(head)
    }

    /** The name a picker's document goes by, for a row — never the URI's last segment when it has one. */
    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /**
     * A backup file or SD-card folder as `VaultLifecycle` takes one —
     * `backup-file-recover-and-import`, D3. A file opens when the words open its sealed table;
     * a folder when it holds the words' keyring blob. Unpacking copies every blob with nothing
     * already here overwritten, and the keyring's copy into the side directory.
     */
    private fun backupSource(uri: Uri, folder: Boolean): VaultLifecycle.BackupSource =
        if (folder) CloudMirror.source(SafDestination(this, uri), blobsDir())
        else BackupFile.source(blobsDir()) { contentResolver.openInputStream(uri) }

    /**
     * A second vault's backup file, onto a device whose list is already open —
     * `backup-to-cloud-apps` D13. The open keyring's key opens the table, so no words are
     * asked for; a backup sealed under another keyring does not open and is refused. Blobs
     * already here — the keyring, which the first restore rewrote — are left alone.
     */
    private fun restoreFileIntoList(
        uri: android.net.Uri,
        progress: (Int, Int) -> Unit = { done, total -> restoreState = RestoreState.Copying(done, total) },
        finish: (String?) -> Unit = ::finishRestoreIntoList,
    ) {
        progress(0, 0)
        thread {
            val key = VaultKeyringSession.backupKey()
            val problem = if (key == null) getString(R.string.restore_open_vaults_first) else try {
                val stream = contentResolver.openInputStream(uri)
                if (stream == null) getString(R.string.restore_file_not_opened) else stream.use { input ->
                    val opened = BackupFile.open(input, key)
                        ?: return@use getString(OTHER_LIST_BACKUP)
                    val keyringName = VaultKeyringSession.keyringBlobId()?.let { hex(it) }
                    val sideDir = File(filesDir, "restore-keyring").also { it.deleteRecursively() }
                    val landed = BackupFile.unpack(
                        input, opened, blobsDir(), skipExisting = true,
                        side = setOfNotNull(keyringName), sideDir = sideDir,
                    ) { done, total ->
                        runOnUiThread { progress(done, total) }
                    }
                    if (landed < 0) getString(INCOMPLETE_BACKUP)
                    else {
                        adoptKeyringFrom(sideDir)
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w("BackupRestore", "the backup file could not be read", e)
                getString(R.string.restore_file_unreadable)
            } finally {
                key?.fill(0)
            }
            // `one-time-purchase` D4. A restore that landed is an add. It is never gated.
            runOnUiThread {
                if (problem == null) Entitlement.startTrialIfNeeded(this)
                finish(problem)
            }
        }
    }

    /**
     * [restoreFileIntoList] for an SD card folder. The folder must hold this list's keyring
     * blob — the one file every backup of its vaults carries — or it is another list's.
     */
    private fun restoreFolderIntoList(
        tree: android.net.Uri,
        progress: (Int, Int) -> Unit = { done, total -> restoreState = RestoreState.Copying(done, total) },
        finish: (String?) -> Unit = ::finishRestoreIntoList,
    ) {
        val keyring = VaultKeyringSession.keyringBlobId()?.let { hex(it) }
        progress(0, 0)
        thread {
            val problem = runCatching {
                val source = SafDestination(this, tree)
                val names = source.names()
                when {
                    // D9. A folder with no backup in it at all says so; one holding another
                    // list's says where that backup goes instead.
                    names.keys.none(::isBlobName) -> getString(R.string.restore_no_backup_in_folder)
                    keyring == null || keyring !in names -> getString(OTHER_LIST_BACKUP)
                    else -> {
                        // The folder's copy of the keyring may list this vault where the one
                        // already here, from an older backup, does not.
                        val sideDir = File(filesDir, "restore-keyring").also { it.deleteRecursively(); it.mkdirs() }
                        source.openInput(ExportEntry(names.getValue(keyring).uri, keyring)).use { input ->
                            File(sideDir, keyring).outputStream().use { input.copyTo(it) }
                        }
                        CloudMirror.restoreFrom(
                            source, blobsDir(), skipExisting = true, exclude = setOf(keyring),
                        ) { done, total ->
                            runOnUiThread { progress(done, total) }
                        }
                        adoptKeyringFrom(sideDir)
                        null
                    }
                }
            }.getOrElse { getString(R.string.restore_no_backup_in_folder) }
            // `one-time-purchase` D4. A restore that landed is an add. It is never gated.
            runOnUiThread {
                if (problem == null) Entitlement.startTrialIfNeeded(this)
                finish(problem)
            }
        }
    }

    private fun isBlobName(name: String) = name.length == 32 && name.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * `backup-to-cloud-apps` D13. Merges the vaults a backup's copy of the keyring lists into
     * the open keyring, then drops the copy.
     */
    private fun adoptKeyringFrom(sideDir: File) {
        runCatching {
            val id = checkNotNull(VaultKeyringSession.keyringBlobId())
            val added = VaultKeyringSession.withKey { key ->
                VaultKeyringSession.adoptEntries(blobs, BlobStore(sideDir, File(sideDir, "incoming")), key, id)
            }
            Log.i("BackupRestore", "vaults joined from the backup's keyring: $added")
        }.onFailure { Log.w("BackupRestore", "the backup's keyring could not be read", it) }
        sideDir.deleteRecursively()
    }

    private fun finishRestoreIntoList(problem: String?) {
        if (problem != null) {
            restoreState = RestoreState.Problem(problem)
            return
        }
        restoreState = RestoreState.Idle
        backupScreen = false
        vaultEntries = VaultKeyringSession.vaults()
    }

    private fun chooseRestoreFile() {
        restoringBackup = false
        backupPick = BackupPick.RESTORE
        AutoLock.awaitingResult(true)
        openBackupFile.launch(arrayOf("*/*"))
    }

    /**
     * A sealed share, saved instead of sent — `FORMAT.md` §10 item 51. The same save
     * dialog the archive uses, titled with the container's own neutral name; the file
     * is ciphertext, so this is I9's user-initiated export and nothing else.
     */
    private val saveShareFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        AutoLock.awaitingResult(false)
        val file = shareFile
        if (uri == null || file == null) return@registerForActivityResult
        thread {
            val ok = runCatching { ShareSend.saveTo(this, file, uri) }.isSuccess
            runOnUiThread { shareSaved = ok }
        }
    }

    /** P1-14. Where the user puts the archive. I9 allows ciphertext out only this way. */
    private val createArchive = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        AutoLock.awaitingResult(false)
        if (uri != null) startArchive(uri) else ArchivePassphrase.clear()
    }

    /** P1-14. The archive to import. */
    private val openArchive = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        AutoLock.awaitingResult(false)
        // A cancelled picker keeps the file already chosen. No grant is persisted: the file
        // may be a `.vault`, and panic deletes the file behind every single-file grant.
        if (uri != null) importChose(uri, folder = false)
    }

    /**
     * P4-3. The received container to open.
     *
     * `OpenDocument` and no intent filter: this app never accepts an intent from
     * another app, which is the Do-not-build row's own words and what
     * `OpenExternallyScanTest` fails the build for. The recipient saves the file
     * from their messenger and picks it here.
     */
    private val openShareFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        AutoLock.awaitingResult(false)
        if (uri != null) beginShareOpen(uri)
    }

    /**
     * `remote-pairing`. The invite, reply or confirmation being opened — the same
     * `OpenDocument` route as a share, for the same reason: no intent filter.
     */
    private val openPairingFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        AutoLock.awaitingResult(false)
        if (uri != null) pairingFilePicked(uri)
    }

    /**
     * P2-4. The one place a capture permission is requested, and only when a mode is
     * being turned on. Launching it backgrounds the activity, so auto-lock is
     * suppressed across the round trip exactly as the pickers do — a lock here would
     * take the VMK the destination key is derived from.
     */
    private val requestCapturePerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        AutoLock.awaitingResult(false)
        refreshCapturePermissions()
        val mode = pendingCaptureMode
        pendingCaptureMode = null
        when {
            mode == null -> Unit
            result.values.all { it } -> finishCaptureEnable(mode)
            else -> captureProblem = getString(R.string.capture_mode_permission_denied)
        }
    }

    /**
     * P2-17. The camera screen's own permissions, for P2-4's reason — the dialog
     * backgrounds the activity, and a lock there would take the VMK the capture is
     * about to be encrypted with.
     *
     * Separate launcher from [requestCapturePerms] because the answers mean opposite
     * things. A denied capture *mode* stays off; a denied camera here draws an
     * explanation and one action, and a denied microphone changes nothing at all —
     * the recording is simply silent.
     */
    private val requestCameraPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        AutoLock.awaitingResult(false)
        cameraPermitted = VaultCamera.permitted(this)
    }

    /**
     * `RECORD_AUDIO` for a memo — `voice-memo`.
     *
     * **Its own launcher, not `requestCameraPerms`.** That one writes
     * `cameraPermitted` on return, and a grant made on the memo screen has nothing to
     * say about the camera. Keeping them separate is also the reason a user who
     * granted the microphone here has armed nothing else: intruder capture's audio
     * mode is a setting, and this is a permission.
     */
    private val requestMemoPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AutoLock.awaitingResult(false)
        voiceMemoPermitted = granted
    }

    /**
     * `POST_NOTIFICATIONS`, asked the first time a memo is recorded and not before —
     * `destination-settings-and-slideshow` D6. **The answer does not decide whether it records**:
     * either way the recording starts when the dialog closes. Refused, the recording still runs
     * with the screen off; the shade just has no Stop, and the memo screen says so.
     */
    private val requestMemoNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        AutoLock.awaitingResult(false)
        startMemo()
    }

    /**
     * The grant a cover needs — `music-recorder-covers`.
     *
     * **Its own launcher**, for the reason the two above are separate: the answers
     * mean different things. A denied capture mode stays off and a denied memo
     * microphone changes a screen; a denied cover grant means the identity does not
     * switch, because a cover whose content is missing is not a convincing one.
     *
     * `awaitingResult` for `requestCapturePerms`' reason: the dialog backgrounds the
     * activity, and an auto-lock across it would take the session the user is standing
     * in the middle of a settings flow inside.
     */
    private val requestCoverPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AutoLock.awaitingResult(false)
        coverGrant = coverGranted(coverBeingSetUp())

        // The refusal path, and the only place the app is entitled to explain itself.
        // `shouldShowRequestPermissionRationale` is the platform's own answer to
        // "should I explain", and asking it *before* a first request returns false for
        // a permission never requested — which is why the step this replaced could
        // never have been driven by it and had to be unconditional instead.
        committingIdentity?.let { pending ->
            when {
                granted -> {
                    committingIdentity = null
                    settingUpIdentity = pending
                }
                // False here is a permanent refusal *or* a device-policy block; the
                // platform reports them identically, so the state they share promises
                // nothing about what the settings page will contain.
                coverPermission(pending.cover)
                    ?.let(::shouldShowRequestPermissionRationale) == true ->
                    commitState = CommitState.Rationale
                else -> commitState = CommitState.Blocked
            }
        }
    }

    /**
     * Launching a picker backgrounds this activity, and auto-lock would fire
     * before the user has picked anything — taking the key with it, so the files
     * they chose would land on a locked session and vanish silently.
     */
    /**
     * P1-16's gate, checked here rather than deeper in: the picker is not launched
     * at all, so nothing is half-started and no file is read. Returns false and
     * says why.
     */
    private fun mayAddToVault(): Boolean {
        if (Entitlement.importAllowed(this)) return true
        notice = getString(Entitlement.BLOCKED)
        return false
    }

    fun pickVisualMedia() {
        if (!mayAddToVault()) return
        AutoLock.awaitingResult(true)
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    fun pickDocuments() {
        if (!mayAddToVault()) return
        AutoLock.awaitingResult(true)
        openDocuments.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // P2-23. **Before `super.onCreate`, and that is the whole point.** The
        // window background comes from the theme and is resolved before any of this
        // app's code runs — it is what stops a cold start flashing a ground the app
        // is not about to draw. After `super` it is too late.
        applyWindowTheme()
        super.onCreate(savedInstanceState)
        // `app-languages` D2. Before anything reads a string: on 30–32 this activity's
        // resources start in the device's language until the choice is put on them.
        AppLanguage.apply(this)
        // `viewer-swipe-navigation`. The session's video stopping points are written at
        // every lock, inside it, while the key they are sealed under still exists.
        VaultLifecycle.beforeLock = writeResumeBeforeLock
        // `per-vault-intruder-capture` D8. A revocation kills the process, so a start is where a
        // lost permission is first seen.
        CapturePermissions.observe(this)
        appearance = GridPreference.appearance(this)
        stripExifOnImport = GridPreference.stripExifOnImport(this)
        photoDetailsPreset = GridPreference.photoDetailsPreset(this)
        photoDetailsCustom = GridPreference.photoDetailsCustom(this)
        // P2-6. Android 15 enforces edge-to-edge and Android 16 removed the opt-out,
        // so this is a declaration of what already happens rather than a request.
        // Declaring it here means one place decides it; `VaultScaffold` then holds
        // the one inset rule for every screen that uses it.
        //
        // P2-23 gave it arguments. Called bare it derives the bar *icon* colours
        // from the platform's night-mode flag — which is not this app's setting the
        // moment a user forces a mode, and light icons on a light bar are invisible
        // controls.
        applyEdgeToEdge()
        hasVault = lifecycleOps.vaultExists()
        // `switch-builds-without-losing-vaults` D5. Before the welcome: a `.vault` read whose
        // process ended before "Create vault" left blobs nothing can open.
        if (!hasVault) lifecycleOps.sweepWithoutList()
        // `first-run-onboarding`. A device with no vault whose flow has never
        // finished is a first run, and the flow owns it from the passphrase on.
        // `inProgress` is false on every later launch, so this is a no-op for every
        // install that has already been through it.
        if (!hasVault && Onboarding.inProgress(this)) beginOnboarding()
        unlocked = VaultSession.isUnlocked
        // P2-16. Restored on launch, so the ordering the user picked survives the
        // process that picked it — the shape P2-8's density already has.
        sort = GridPreference.sort(this)
        mediaSettings = GridPreference.mediaSettings(this)
        noteSettings = GridPreference.noteSettings(this)
        memoSettings = GridPreference.memoSettings(this)
        // `destination-view-modes`, beside the ordering and for its reason: how the person
        // likes their lists survives the process that was told. Seeded for all four here
        // rather than read per frame, so a destination switch costs no preference read.
        VaultDestination.entries.forEach { at ->
            viewModes[at] = GridPreference.viewMode(this, at)
        }
        // The old slideshow's interval, stored under a key the rebuilt one does not use.
        GridPreference.sweepRetired(this)
        // `destination-settings-and-slideshow` D5. The memo lives for the process; its changes
        // redraw its screen — a Stop from the notification among them — and a memo that is no
        // longer recording no longer needs the microphone's foreground service.
        lifecycleScope.launch {
            VoiceMemo.changes.collect {
                memoChanges = it
                if (VoiceMemo.current?.recording != true) MemoRecordingService.stop(this@MainActivity)
            }
        }
        lifecycleScope.launch {
            notices = withContext(Dispatchers.IO) { Notices.read(this@MainActivity) }
        }
        // P2-20, likewise. Off until the user says otherwise.
        faceDownOn = GridPreference.faceDown(this)
        extraSecurityOn = ExtraSecurity.isOn(this)
        faceDownLockAfter = GridPreference.faceDownLockAfter(this)
        faceDownAvailable = FaceDownLock.available(this)
        // P2-22. **Admit before reading.** Every alias's component state is derived
        // from the stored preference. The start path only **enables** the stored
        // identity's alias — `identity-switch-done-step` D3: this launch came through
        // one of these aliases, and disabling it here would finish the activity being
        // created. Any stale entry goes when the app next leaves the foreground, where
        // `AutoLock` runs the full `LauncherIdentity.reconcile`. Idempotent, so a device
        // already in the right state is not written to on a cold start.
        LauncherIdentity.admit(this)
        identity = LauncherIdentity.resolved(this)
        applyTaskDescription()
        // P2-24. The cover is up before anything else is decided, and it is up even
        // when a session is already live.
        coverShowing = identity.cover != null
        coverGrant = coverGranted(identity.cover)
        val hadWrap = bio.hasRecord()
        bioEnabled = bio.isEnabled()
        // `isEnabled` removes a half-state wrap on its own; the schedule goes with it.
        if (!bioEnabled) practice.clear()
        warnings = RuntimeWarnings.check(this).map { it.message }
        // A wrap that was on disk and is not usable was destroyed by an enrolment
        // change, whether the platform deleted the key or merely invalidated it.
        // Both are the same sentence to the user.
        if (hadWrap && !bioEnabled) {
            bioNotice = BIO_INVALIDATED
            // Detected while locked, so there is no key to seal it with yet — it
            // reaches the log at the next successful unlock.
            TamperLog.recordBiometricInvalidated()
        }
        // P2-5. The device checks, beside the biometric one and for the same reason:
        // detected while locked, held pending, flushed to the log at the next unlock
        // (or the next vault creation, for a data clear that left none to open). Warn
        // and record, never block — nothing below branches on the result.
        DeviceEvents.check(this).forEach { TamperLog.recordDeviceEvent(it) }
        loadIndex()
        watchImports()
        watchMirror()
        watchExports()
        autoPromptBiometric()
        // A launch is not a return; only departures after this one reset the screens.
        seenEpoch = AutoLock.awayEpoch
        // P1-16. Connects once per process; the answer lands in Billing.active and
        // is read from there. Nothing waits on it — no answer reads as entitled.
        // `distribution-flavors`: the `github` build has no purchase to connect for.
        if (BuildConfig.TRIAL) {
            Billing.onChange = { runOnUiThread(::syncBilling) }
            Billing.connect(this)
        }
        setLocalizedContent {
            VaultTheme(appearance) {
                // `recents-shows-identity` D2. The live tree, and the identity picture beside it.
                Box(Modifier.fillMaxSize()) {
                Surface(color = VaultColors.Surface, modifier = Modifier.fillMaxSize()) {
                    // P2-20. `keyringOpen` and `unlocked` are already Compose state,
                    // so lock, unlock and the toggle all re-run this with no plumbing
                    // of their own. Both, because an unlock opens the keyring onto the
                    // vault list with no vault open, and recovery or archive import
                    // opens a vault with no keyring — `face-down-lock-on-keyring` D2.
                    // The other half of the window is `onResume`/`onPause` below —
                    // together they are the whole of the suppression story, because
                    // P1-10's two suppressions both leave this activity not resumed.
                    DisposableEffect(keyringOpen, unlocked, faceDownOn, faceDownLockAfter) {
                        // `::faceDownLock` and not `VaultLifecycle.lockNow` — this fires
                        // while the list or grid is on screen, so the screen has to go with
                        // the key. It reaches the same mechanism by the same path
                        // the Lock button takes. Keyed on Lock after too, so a new period
                        // re-arms the detector with it.
                        FaceDownLock.start(this@MainActivity, ::faceDownLock)
                        onDispose { FaceDownLock.stop() }
                    }
                    val item = open
                    val kit = recoveryKit
                    // One ordering, read by both the grid and the viewer — P2-8.
                    // The viewer pages through the same sequence the grid shows,
                    // so computing it twice would be two places for the order to
                    // drift and the pager to jump.
                    // P2-16. The one place sequence is decided. The viewer's
                    // pager, P2-13's album filter and P2-15's query all descend
                    // from this list, so all three inherit the ordering without
                    // knowing it can change.
                    val ordered = remember(index.items, sort) {
                        // `remove-note-attachments`. **The filter that hid owned items
                        // is gone, and that is what releases them.** An item recorded as
                        // owned by a note is an ordinary item now: it appears here, in
                        // the trash, in search and in export like anything else. Nothing
                        // was deleted to achieve it — the field is simply no longer read,
                        // because a stored photograph no screen can reach would be worse
                        // than the feature this removed.
                        // `notes-as-documents`: pinned first, and the grid's own
                        // ordering **within** each group. A pin is not a second sort
                        // order — it lifts a row to the front and changes nothing
                        // else, which is what keeps the date headings correct under
                        // it.
                        sort.order(
                            index.items.filter { it.deletedAt == null }
                        ).sortedByDescending { it.pinned }
                    }
                    // P2-12. Deletion order, not capture order: what a user looks
                    // for in a trash is what they just deleted. Computed here
                    // beside the grid's ordering so the two live together.
                    val trashed = index.items
                        .filter { it.deletedAt != null }
                        .sortedByDescending { it.deletedAt }
                    // Whichever list is on screen. The selection, the viewer's
                    // pager and the delete confirmation all act on this one, so
                    // there is no path by which a set of IDs picked in the trash
                    // acts on the grid.
                    //
                    // P2-13: the album filter narrows the live list and nothing
                    // else — the ordering, the sections and the viewer's paging are
                    // the grid's own, over fewer rows.
                    //
                    // `folder-system`: the level being viewed narrows the live list
                    // and nothing else — the ordering, the sections and the viewer's
                    // paging are the grid's own, over fewer rows. A **search** is the
                    // one thing that reaches deeper: it takes the whole subtree, so a
                    // question asked inside a folder is answered about everything
                    // inside it rather than only about what is directly there.
                    val here = folderPath.lastOrNull()
                    // `vault-destinations`. **One more filter in the chain that already
                    // narrows**, and deliberately not a second list: the ordering, the
                    // date headings, the folder scoping, the search and the viewer's
                    // paging are all the grid's own over fewer rows, which is the
                    // property P2-13 and `folder-system` each preserved in turn.
                    //
                    // Media's chip and the destination collapse into **one** kind set
                    // rather than two filters: `MediaFilter.All` is Media's own kinds, so
                    // the chip is the narrower of the two wherever it applies and is the
                    // same set where it does not.
                    val visible = if (destination == VaultDestination.Media) {
                        mediaFilter.kinds
                    } else {
                        destination.kinds
                    }
                    // Memoised, because `VaultItem.kind` reads the filename's extension
                    // on every call and this runs over the whole live list. Once per
                    // change of list or destination, not once per frame — the rule the
                    // query's own filter below already follows.
                    val ofKind = remember(ordered, visible) {
                        ordered.filter { it.kind in visible }
                    }
                    val listed = when {
                        // **The trash is not narrowed by the destination.** It holds
                        // every kind at once and the bar is not drawn over it, so there
                        // is no destination for it to be on — the same reason the search
                        // field is withheld there.
                        trash -> trashed
                        // A search reaches **down**, where the level does not. Asked
                        // inside a folder it answers about everything in it,
                        // sub-folders included; asked at the home screen it answers
                        // about the whole vault, which is what it has always done and
                        // what the level query alone would silently narrow.
                        query.isNotEmpty() -> here?.let { at ->
                            val within = VaultFolders.subtree(index.folders, at)
                            ofKind.filter { it.folderKey in within }
                        } ?: ofKind
                        else -> VaultFolders.items(index.folders, ofKind, here)
                    }
                    // P2-15. The query is the **last** narrowing step, over the
                    // list the grid is already showing. Three things follow for
                    // free: a trashed item cannot match, because the grid's list is
                    // already the live rows and there is no second rule to keep in
                    // sync with the first; a search inside an album searches that
                    // album; and the ordering, the date sections and the viewer's
                    // paging are the grid's own over fewer rows.
                    //
                    // Memoised so it runs once per change rather than once per
                    // recomposition — a keystroke is one filter, not one per frame.
                    //
                    // `notes-as-documents` made this two passes. The first is the
                    // one that was always here: index-only, synchronous, no I/O, and
                    // it answers everything except a note whose match is past its
                    // preview. The second reads those notes' blobs **off the main
                    // thread** and widens the list when it lands. Results appear
                    // instantly and then grow, rather than the whole query waiting on
                    // a decrypt.
                    val names = remember(index.folders) {
                        // `folder-system`. The folder's *name* is what a query can
                        // match; the item carries a reference, so the name is looked
                        // up here rather than in the matcher.
                        index.folders.associate { it.key to it.name }
                    }
                    val fast = remember(listed, query, names) {
                        query.takeIf { it.isNotEmpty() }?.let { q ->
                            // **Named, not trailing.** `notes-as-documents` added
                            // `textOf` after `folderName`, so a trailing lambda binds
                            // to the note reader instead of the folder lookup — which
                            // compiles, keeps matching filenames and tags, and
                            // silently stops matching folder names. `SearchScanTest`
                            // fails the build if this call site loses its name again.
                            VaultSearch.filter(
                                listed,
                                q,
                                folderName = { item -> names[item.folderKey] },
                            )
                        } ?: listed
                    }
                    val shown by produceState(fast, fast, listed, query, names) {
                        value = fast
                        if (query.isEmpty()) return@produceState
                        val terms = VaultSearch.terms(query)
                        val vmk = VaultSession.vmk() ?: return@produceState
                        val deeper = withContext(Dispatchers.IO) {
                            listed.filter { item ->
                                VaultSearch.needsItsText(item, terms, names[item.folderKey]) &&
                                    runCatching {
                                        VaultSearch.textMatches(
                                            item,
                                            VaultNote.read(blobs, item, vmk),
                                            terms,
                                            names[item.folderKey],
                                        )
                                    }.getOrDefault(false)
                            }.map { it.itemId }.toSet()
                        }
                        if (deeper.isNotEmpty()) {
                            // Rebuilt from `listed` rather than appended, so the
                            // grid's ordering — and the date headings drawn from it —
                            // survive the widening.
                            val kept = fast.map { it.itemId }.toSet() + deeper
                            value = listed.filter { it.itemId in kept }
                        }
                    }
                    // `folder-system`. The folders at this level, with their live
                    // counts. The records are stored; the counts are derived, so the
                    // two can never disagree.
                    //
                    // Empty while searching: results come from the whole subtree, and
                    // a folder tile beside them would be a place, not a result.
                    //
                    // `vault-destinations`. **`ofKind` and not `index.items`** — a
                    // folder's count is what the destination the user is standing in
                    // would show inside it, so a folder of forty photographs and three
                    // documents reads forty on Media and three on Files.
                    //
                    // `children` lists from the *folders*, never from the items, so every
                    // folder is still listed at a count of zero. That is not incidental:
                    // `album-organisation` requires a recorded folder stay listed, and
                    // hiding a photograph folder from Files would make it invisible in
                    // three destinations out of four — a folder the user made and cannot
                    // find, which is that requirement's failure mode multiplied.
                    val folders = remember(index.folders, ofKind, here, query, destination) {
                        if (query.isNotEmpty()) {
                            emptyList()
                        } else {
                            VaultFolders.children(index.folders, ofKind, here, destination)
                        }
                    }
                    /** The folder being viewed, or null at the home screen. */
                    val folder = remember(index.folders, here) {
                        here?.let { id -> index.folders.firstOrNull { it.id == hex(id) } }
                    }
                    // P2-7. The settings drawer's state lives here rather than in
                    // `VaultGrid`, because back has to close it and back is handled
                    // at the activity. `dismissTop` stays ordinary Kotlin — closing
                    // a drawer suspends, and making the precedence function suspend
                    // to accommodate one branch would be the tail wagging the dog.
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    // `screen-off-lock-all-screens`. Composition state, so `resetForLock` cannot
                    // reach it: the drawer shuts with the keyring, or the next unlock would
                    // open onto it.
                    LaunchedEffect(keyringOpen) {
                        if (!keyringOpen) drawerState.snapTo(DrawerValue.Closed)
                    }
                    // `intruder-captures-at-home` D3. **One place takes captures in**: the keyring
                    // opening, whichever path opened it — passphrase, biometric, recovery, creation.
                    // Hooking each path would drift; this sees them all.
                    LaunchedEffect(keyringOpen) {
                        if (keyringOpen) ingestHomeCaptures() else capturesUnseen = false
                    }
                    // P1-16's read, moved here by P2-7. It used to fire on the
                    // navigation into the settings screen; the drawer opening is
                    // that same moment, so the countdown is exactly as fresh as it
                    // was. Nothing waits on the answer — no answer reads as
                    // entitled, per I4.
                    LaunchedEffect(drawerState.isOpen) {
                        if (drawerState.isOpen && BuildConfig.TRIAL) {
                            syncBilling()
                            Billing.refresh()
                            Billing.loadPrice()
                        }
                    }
                    // Runs a drawer action: shut the drawer, then do the thing. The
                    // four concealment controls and export all navigate away, and a
                    // drawer left standing behind the screen it opened would be
                    // waiting there on the way back.
                    val closingDrawer = { action: () -> Unit ->
                        {
                            scope.launch { drawerState.close() }
                            action()
                        }
                    }
                    // P2-6, extended by P2-7. One handler for the whole app. The
                    // drawer is checked first because it is drawn above every screen
                    // flag, so back must shut it before it touches anything
                    // underneath. Disabled when there is nothing to close, so back
                    // still leaves the activity from the grid, the setup screen and
                    // the lock screen — which is what back means there.
                    BackHandler(
                        enabled = fabExpanded || drawerState.isOpen || backDismissible,
                    ) {
                        // `settings-screen-and-fab`, ahead of the drawer for the
                        // drawer's own reason one layer up: the expanded set is drawn
                        // over the grid *and* over its scrim, so back must collapse it
                        // before it touches anything underneath. Collapsing invokes
                        // none of the four.
                        if (fabExpanded) {
                            fabExpanded = false
                        } else if (drawerState.isOpen) {
                            scope.launch { drawerState.close() }
                        } else {
                            dismissTop()
                        }
                    }
                    // `exif-strip-choice`. Over whichever screen the drawer was opened on. A
                    // sheet is a popup, so where it sits in this tree decides nothing about
                    // where it draws; the drawer has already closed behind it.
                    if (photoDetailsSheet) {
                        PhotoDetailsSheet(
                            preset = photoDetailsPreset,
                            custom = photoDetailsCustom,
                            stripOnImport = stripExifOnImport,
                            onPreset = ::choosePhotoDetailsPreset,
                            onCategory = ::setPhotoDetail,
                            onStripOnImport = ::chooseStripOnImport,
                            onDismiss = { photoDetailsSheet = false },
                        )
                    }
                    // `app-languages` D6. Drawn where Photo details is, for its reason. Opened from
                    // the home drawer and from first run's welcome (`welcome-language-choice`). A
                    // choice reaches here once the sheet has hidden, so the sheet is gone before
                    // anything redraws in the new language (`language-picker-redesign` D1).
                    if (languageSheet) {
                        LanguageSheet(
                            onChoose = {
                                languageSheet = false
                                chooseLanguage(it)
                            },
                            onDismiss = { languageSheet = false },
                        )
                    }
                    // `vault-list-refresh`, D9. The list and the grid, as the two slots of the
                    // one transition that grows a row into its vault. Defined here, outside the
                    // `when`, so both branches below hand the same two to [home].
                    //
                    // **One movable content, called from two branches.** The list is the
                    // `!unlocked` branch and the grid the `else` one, with other screens
                    // between them, so a transition placed in either would be a different
                    // composition from the other. When one recomposition drops one call and
                    // adds the other, the movable content — and its `AnimatedContent` —
                    // moves, and the transition runs. Every other branch still switches
                    // instantly.
                    val home = remember {
                        movableContentOf { target: HomeTarget,
                                           list: @Composable (@Composable (Int) -> Modifier) -> Unit,
                                           grid: @Composable (Modifier) -> Unit ->
                            VaultHomeTransition(
                                target = target,
                                openPosition = openPosition,
                                // §8's reduced motion, read as `RecorderCover` reads it: Compose
                                // does not honour the system's animator scale on its own.
                                reducedMotion = android.provider.Settings.Global.getFloat(
                                    contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
                                ) == 0f,
                                list = list,
                                grid = grid,
                            )
                        }
                    }
                    val homeList: @Composable (@Composable (Int) -> Modifier) -> Unit = { rowBounds ->
                        VaultListScreen(
                            vaults = vaultEntries,
                            onOpen = ::openVaultAt,
                            onEdit = { entry ->
                                vaultListProblem = null
                                editDraftName = entry.name
                                editDraftAppearance = VaultAppearance.of(entry.appearance)
                                passphraseStatus = null
                                editingVault = entry
                            },
                            onRemove = ::openDestroy,
                            onLock = ::lockVault,
                            // `home-screen-chrome`. The same drawer state the grid uses,
                            // so back closes whichever one is open without either screen
                            // knowing about the other.
                            drawerState = drawerState,
                            drawerContent = {
                                AppDrawer(
                                    // No vault open, so no export — it writes one out.
                                    onRecoverVault = closingDrawer { openRecoverVault() },
                                    onRestoreVault = closingDrawer { openBackup() },
                                    onImportVault = closingDrawer { importingArchive = true },
                                    vaultOpen = false,
                                    closing = closingDrawer,
                                )
                            },
                            onCreate = ::openNewVaultFlow,
                            problem = destroyProblem ?: vaultListProblem,
                            rowBounds = rowBounds,
                            // `intruder-captures-at-home` D7. The value the drawer's row reads.
                            capturesUnseen = capturesUnseen,
                        )
                    }
                    val homeGrid: @Composable (Modifier) -> Unit = { gridBounds ->
                        VaultGrid(
                            // `per-vault-intruder-capture` D7.
                            capturesUnseen = vaultCapturesUnseen,
                            items = shown,
                            // `vault-destinations`. The state and its two setters; the
                            // grid hosts the bar and the chips and decides nothing about
                            // where the user is.
                            destination = destination,
                            onDestination = ::goToDestination,
                            mediaFilter = mediaFilter,
                            onMediaFilter = { mediaFilter = it },
                            // `destination-view-modes`. Read for the destination being
                            // drawn; the setter persists it, so a mode chosen here survives
                            // the process that chose it.
                            viewMode = viewMode,
                            onViewMode = ::setViewMode,
                            thumbnailFor = ::thumbnailFor,
                            // P2-21. A note opens its editor, not the viewer:
                            // there is nothing to view, the body *is* the item.
                            // Everything else about the grid — long-press,
                            // selection, the trash's list — is untouched.
                            onOpen = { item: VaultItem ->
                                // Not in the trash: a trashed item is waiting out
                                // its window, not being written — the rule that
                                // already withholds rename and delete there.
                                // `notes-as-documents` moved a note's text into a
                                // blob, so `body` is null on every note written since
                                // — and this test, left behind, sent every one of them
                                // to the viewer, which cannot render a note and says
                                // so. The kind is what says a row is a note.
                                if (item.kind == VaultKind.NOTE && !trash) {
                                    editingNote = item
                                    noteScreen = true
                                } else {
                                    open = item
                                }
                            },
                            selected = selection,
                            onBeginSelection = { selection = setOf(it.itemId) },
                            onToggleSelection = { item ->
                                // Deselecting the last one ends the mode: a live
                                // bar over an empty selection is a bar whose two
                                // actions cannot do anything.
                                selection =
                                    if (item.itemId in selection) selection - item.itemId
                                    else selection + item.itemId
                            },
                            onSelectAll = { selection = shown.map { it.itemId }.toSet() },
                            onClearSelection = { selection = emptySet() },
                            onDeleteSelected = {
                                pendingDeleteSelection = true
                                // In the trash the same control destroys, and the
                                // confirmation says so — P2-12.
                                pendingPurge = trash
                                pendingDelete = shown.firstOrNull { it.itemId in selection }
                            },
                            onExportSelected = {
                                if (selection.isNotEmpty()) {
                                    exportSelection = selection
                                    exporting = true
                                }
                            },
                            // P4-2. Present only for a selection of exactly one
                            // shareable item; null everywhere else, so the control
                            // is absent rather than disabled (§7.19). A container
                            // carries one item, which is why two is not offered.
                            onShareSelected = shown
                                .singleOrNull { it.itemId in selection && selection.size == 1 }
                                ?.takeIf { !trash }
                                ?.let { item -> { beginShare(item) } },
                            // `file-import`. **The destination decides which picker.**
                            // `VaultGrid` has already decided whether the control is
                            // drawn at all — on Notes and Memos it is not — so the
                            // `null` branch here is unreachable through the UI and is
                            // written as doing nothing rather than as a default that
                            // would silently open the wrong picker for a destination
                            // added later.
                            onImport = {
                                if (!importing) when (destination.addAction) {
                                    AddAction.Media -> pickVisualMedia()
                                    AddAction.Files -> pickDocuments()
                                    null -> Unit
                                }
                            },
                            trash = trash,
                            // `folder-system`. The folders at this level, drawn
                            // above the items, and what the chrome names.
                            folders = folders,
                            folderName = folder?.name,
                            coverFor = { f ->
                                VaultFolders.coverFor(index.folders, index.items, f.folder)
                            },
                            onOpenFolder = { f ->
                                selection = emptySet()
                                // A folder is a new scope; a query typed against
                                // the level above is not a question about it.
                                query = ""
                                folderPath = folderPath + f.folder.folderId
                            },
                            onFolderSurface = { f -> folderSheet = f.folder },
                            onNoteOptions = { noteOptions = it },
                            onLeaveFolder = ::leaveFolder,
                            // P2-15. Opening the search narrows nothing until
                            // something is typed; leaving it clears the query.
                            query = query,
                            onQueryChange = { query = it },
                            // P2-16. The grid does not sort; it is told what the
                            // list is already in, so the headings are about the
                            // right date and a name ordering draws flat.
                            sort = sort,
                            onSort = { chosen ->
                                sort = chosen
                                GridPreference.setSort(this@MainActivity, chosen)
                            },
                            onMoveSelected = { movingToFolder = true },
                            onRemoveFromFolder = if (here == null) null else ({
                                val chosen = shown.filter { it.itemId in selection }
                                commitItems(chosen) { id, vmk ->
                                    VaultFolders.move(blobs, id, vmk, chosen, null)
                                }
                                selection = emptySet()
                            }),
                            onRestoreSelected = {
                                restoreItems(shown.filter { it.itemId in selection })
                                selection = emptySet()
                            },
                            onLeaveTrash = ::closeTrash,
                            drawerState = drawerState,
                            // `home-screen-chrome`. From the keyring entry, so no key is
                            // unwrapped to title a bar. Falls back only if the session and
                            // the composition disagree for a frame during a lock.
                            vaultName = vaultEntries
                                .firstOrNull { it.position == VaultKeyringSession.openVaultPosition() }
                                ?.name
                                ?: stringResource(R.string.identity_vault),
                            drawerContent = {
                                AppDrawer(
                                    // The grid is inside a vault; recovering one is
                                    // reached from the list, where the vaults are.
                                    onRecoverVault = null,
                                    vaultOpen = true,
                                    closing = closingDrawer,
                                )
                            },
                            // `settings-screen-and-fab`. The four actions the drawer
                            // used to hold as rows. Every handler below already
                            // existed; only the call site moved.
                            fabExpanded = fabExpanded,
                            onFabExpandedChange = { fabExpanded = it },
                            // P2-17. Drawn on every device; where there is no camera
                            // it is drawn unusable and says so —
                            // `unavailable-capability-states`.
                            cameraAvailable = VaultCamera.available(this@MainActivity),
                            onCamera = ::openCamera,
                            // P2-21. An empty editor; the note does not exist until
                            // Save.
                            onVoiceMemo = ::openVoiceMemo,
                            onNewNote = {
                                // `one-time-purchase`. A new note is an add, gated like
                                // the camera; opening an existing note is not.
                                if (mayAddToVault()) {
                                    editingNote = null
                                    noteScreen = true
                                }
                            },
                            // `first-run-onboarding`. Leaves the vault for the list
                            // of them, from whatever depth — the same exit back
                            // already takes, given a control so it does not depend
                            // on a gesture.
                            onHome = ::leaveVault,
                            // `per-vault-passphrases`. **The same act as the list's
                            // button now.** This icon used to reach the slot-sealing
                            // screen while carrying the list button's words, which is
                            // how an everyday control ended up able to destroy a vault.
                            // `folder-system`. Creates at the level being viewed,
                            // so a folder made inside a folder is a sub-folder
                            // without a second control.
                            onNewFolder = {
                                folderProblem = null
                                naming = FolderNaming.Create(here)
                            },
                            // `destination-settings-and-slideshow` D1. Files has no settings,
                            // so its header has no control and its neighbours close up.
                            onDestinationSettings = if (destination == VaultDestination.Files) {
                                null
                            } else {
                                { destinationSettings = true }
                            },
                            // P1-16. An import failure is about something the user
                            // just did, so it wins; the reminder is only ever days
                            // 23 and 28, and only ever a line of text.
                            // `delete-vault` 2.4. A return that did not finish says so
                            // where the user is standing — they never left the grid,
                            // because the confirmation was a dialog over it.
                            notice = destroyProblem
                                ?: notice
                                ?: Entitlement.reminder(this@MainActivity),
                            warnings = warnings,
                            digest = digest,
                            // `remove-originals-on-import`. Keep deletes nothing and
                            // is never asked again for these items.
                            originals = originalsOffer,
                            removingOriginals = removingOriginals,
                            originalsRemoved = originalsRemoved,
                            onKeepOriginals = { originalsOffer = null },
                            onRemoveOriginals = ::removeOriginals,
                            modifier = gridBounds,
                        )
                    }
                    when {
                        // P2-24. **First, ahead of every other branch**, including
                        // `!unlocked` below — see this flag's KDoc. `screen-off-lock-all-
                        // screens` put it here in fact: it had drifted below eleven
                        // screens, and a lock landing on any of them showed that screen
                        // instead of the cover.
                        coverShowing -> {
                            when (identity.cover) {
                                // `calculator-dots-methods` D4. The dots after its sequence or its
                                // hold, as on the music player; no sequence on Hold, then pattern.
                                Cover.Calculator -> CalculatorCover(
                                    onTrigger = ::coverTrigger,
                                    sequence = coverCalculatorMethod != CalculatorMethod.HoldThenPattern,
                                    showDots = coverDots,
                                    dotsError = coverDotsError,
                                    holdArea = coverHoldArea,
                                    onHold = ::showCoverDots,
                                )
                                // No grant callback reaches either cover. The dialog
                                // names the application, so a live cover that could raise
                                // it would introduce the app by its real name to whoever
                                // pressed the control. Setup asks; the covers state which
                                // Settings toggle restores it and offer no button.
                                // `cover-hold-dots` D4. The dots after the owner's hold in
                                // their area, and the error on them after a refusal.
                                Cover.MusicPlayer -> MusicCover(
                                    hasAccess = coverGrant,
                                    onTrigger = ::coverTrigger,
                                    showDots = coverDots,
                                    dotsError = coverDotsError,
                                    holdArea = coverHoldArea,
                                    onHold = ::showCoverDots,
                                )
                                Cover.VoiceRecorder -> RecorderCover(
                                    hasPermission = coverGrant,
                                    onTrigger = ::coverTrigger,
                                    showDots = coverDots,
                                    dotsError = coverDotsError,
                                    holdArea = coverHoldArea,
                                    onHold = ::showCoverDots,
                                )
                                // Unreachable: `coverShowing` is set from a non-null
                                // cover. Drawn as the vault rather than as nothing, so a
                                // future identity cannot strand a user on a blank screen.
                                null -> Unit
                            }
                            // `cover-hold-dots` D4. Back hides the dots and gives the
                            // cover back; it does not leave the app. After the cover, so it
                            // is registered after the cover's own Back handlers and is the one
                            // that answers while the dots are up.
                            BackHandler(coverDots, ::hideCoverDots)
                        }
                        // Ahead of every case but the cover: the kit is shown once, and a
                        // user who navigates away from it never sees it again.
                        // `first-run-onboarding`. The flow owns the kit during first
                        // run — step 2 draws it inside the frame. This branch still
                        // serves every other path that produces a mnemonic: a second
                        // vault, a recovery, an archive import.
                        // `new-vault-setup-flow`. Ahead of the kit branch, because the flow draws
                        // its vault's kit itself, as its last step and inside its frame. A kit put
                        // away by a lock falls through to that branch on the way back, as before.
                        vaultFlow != null && (flowStep != NewVaultStep.Kit || kit != null) -> {
                            val adding = vaultFlow == VaultFlow.Add
                            val steps = flowSteps()
                            val at = steps.indexOf(flowStep)
                            val vaultName = if (adding) editingVault?.name.orEmpty() else flowName.trim()
                            StepFrame(
                                position = at + 1,
                                total = steps.size,
                                // D2. No Back on a first step and none on the kit; no Cancel on the kit.
                                onBack = if (at > 0 && flowStep != NewVaultStep.Kit) ::vaultFlowBack else null,
                                onCancel = if (flowStep != NewVaultStep.Kit) ::cancelVaultFlow else null,
                                cancelDescription = stringResource(R.string.action_cancel),
                                enabled = !setupBusy,
                            ) {
                                VaultFlowBody(
                                    step = flowStep,
                                    // §8's reduced motion, read as the home transition reads it.
                                    reducedMotion = android.provider.Settings.Global.getFloat(
                                        contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
                                    ) == 0f,
                                ) { shown ->
                                    when (shown) {
                                        NewVaultStep.Name -> NameStep(
                                            name = flowName,
                                            onName = {
                                                flowName = it
                                                flowPass.vaultName = it
                                                flowNameError = null
                                            },
                                            appearance = flowAppearance,
                                            onAppearance = { flowAppearance = it },
                                            accepts = VaultKeyring::fits,
                                            error = flowNameError,
                                            onContinue = ::nameStepContinue,
                                        )
                                        NewVaultStep.Opens -> OpensStep(
                                            name = vaultName,
                                            own = flowOwn,
                                            onOwn = {
                                                flowOwn = it
                                                flowProblem = null
                                            },
                                            busy = setupBusy,
                                            problem = flowProblem,
                                            onContinue = {
                                                if (flowOwn) flowStep = NewVaultStep.Passphrase else commitNewVault()
                                            },
                                        )
                                        NewVaultStep.Passphrase -> if (adding) {
                                            editingVault?.let { entry ->
                                                AddPassphraseStep(
                                                    vault = entry,
                                                    list = flowListTyped,
                                                    onList = {
                                                        flowListTyped = it
                                                        flowListWrong = false
                                                    },
                                                    wrong = flowListWrong,
                                                    draft = flowPass,
                                                    busy = setupBusy,
                                                    onProve = ::proveAddList,
                                                )
                                            }
                                        } else {
                                            NewPassphraseStep(
                                                name = vaultName,
                                                draft = flowPass,
                                                onContinue = { flowStep = NewVaultStep.Capture },
                                            )
                                        }
                                        NewVaultStep.Capture -> VaultCaptureStep(
                                            name = vaultName,
                                            enabled = setupCaptureModes,
                                            hasFrontCamera = IntruderCapture.hasFrontCamera(this@MainActivity),
                                            onEnable = ::enableCaptureMode,
                                            onDisable = ::disableCaptureMode,
                                            autoResetRisk = !autoRevokeExempt,
                                            onKeepPermissions = ::openAutoRevokeSettings,
                                            problem = flowProblem ?: captureProblem,
                                            actionLabel = stringResource(
                                                if (adding) R.string.vault_passphrase_add_button else R.string.setup_create
                                            ),
                                            busyLabel = stringResource(
                                                if (adding) R.string.vault_passphrase_adding else R.string.vault_creating
                                            ),
                                            busy = setupBusy,
                                            onCommit = { if (adding) commitAdd() else commitNewVault() },
                                        )
                                        // D4. The kit at creation, unchanged, at the last position. Its own
                                        // Continue, behind the typed acknowledgement, ends the flow.
                                        // `recovery-practice` D5. Then the check, while Extra Security is on.
                                        NewVaultStep.Kit -> if (kitChecking) {
                                            KitCheckScreen(
                                                mnemonic = mnemonic,
                                                words = kit.orEmpty(),
                                                onCorrect = { kitPassed(::finishVaultFlow) },
                                                onSeeWords = ::kitSeeWords,
                                                framing = KitCheckFraming.Standalone,
                                            )
                                        } else {
                                            RecoveryKitScreen(
                                                words = kit.orEmpty(),
                                                onPrint = {
                                                    printRecoveryKit(kit.orEmpty(), KitOwner.Vault(recoveryKitVault ?: vaultName))
                                                },
                                                vaultName = recoveryKitVault,
                                                createdViewsLeft = recoveryKitCreationLeft,
                                                acknowledged = kitAcknowledged,
                                                onContinue = { kitContinue(gated = true, then = ::finishVaultFlow) },
                                            )
                                        }
                                    }
                                }
                            }
                            if (flowDiscarding) {
                                DiscardFlowDialog(
                                    title = stringResource(
                                        if (adding) R.string.vault_discard_add_title else R.string.vault_discard_new_title
                                    ),
                                    body = if (adding) {
                                        stringResource(R.string.vault_discard_add_body, vaultName)
                                    } else {
                                        stringResource(R.string.vault_discard_new_body)
                                    },
                                    onKeep = { flowDiscarding = false },
                                    onDiscard = ::closeVaultFlow,
                                )
                            }
                        }
                        // `recovery-practice` D5. A creation-form kit here is checked too, while
                        // Extra Security is on; a later view never is.
                        kit != null && onboardingStep == null && kitChecking && recoveryKitStatus == null -> KitCheckScreen(
                            mnemonic = mnemonic,
                            words = kit,
                            onCorrect = { kitPassed(::putAwayCreationKit) },
                            onSeeWords = ::kitSeeWords,
                            framing = KitCheckFraming.Standalone,
                        )
                        kit != null && onboardingStep == null -> RecoveryKitScreen(
                            words = kit,
                            onPrint = { printRecoveryKit(kit, heldKitOwner()) },
                            // `per-vault-passphrases`. Null for the keyring's own kit,
                            // which is every path that existed before this one.
                            vaultName = recoveryKitVault,
                            // `recovery-kit-three-views`. A later view: Done, and back, close it.
                            status = recoveryKitStatus,
                            createdViewsLeft = recoveryKitCreationLeft,
                            acknowledged = kitAcknowledged,
                            // `first-run-onboarding`. A kit the lock held is drawn
                            // here ahead of a paused flow, and putting it away is
                            // what resumes the flow. `resumeOnboarding` returns
                            // immediately on an install whose flow already finished,
                            // which is every other path here — a recovery, an
                            // archive import, a second vault.
                            onContinue = {
                                if (recoveryKitStatus != null) {
                                    closeKitView()
                                } else {
                                    kitContinue(gated = true, then = ::putAwayCreationKit)
                                }
                            },
                        )
                        // `cloud-mirror`. **Above `!hasVault` and `!keyringOpen`**,
                        // because restoring is what a replacement device does before
                        // it has either.
                        backupScreen -> BackupScreen(
                            // `backup-screen-status-first` D10. No vault open is a
                            // device with nothing to back up: the screen restores.
                            restoreMode = VaultSession.metadataBlobId() == null,
                            restoreIntoList = VaultKeyringSession.isOpen,
                            status = backupStatus ?: BackupStatus.Checking,
                            folderName = mirrorDestination,
                            fileDestination = CloudMirror.destination(index)?.let(CloudMirror::isFile) == true,
                            storeBytes = storeBytes(),
                            problem = mirrorProblem,
                            restoreState = restoreState,
                            destinations = { CloudMirror.destinations(this@MainActivity) },
                            onChooseDestination = ::chooseDestination,
                            onBackUpNow = ::mirrorNow,
                            onStop = ::turnOffMirror,
                            onChooseRestoreFile = ::chooseRestoreFile,
                            onChooseRestoreFolder = { pickMirrorFolder(restore = true) },
                            onBack = ::closeBackup,
                            // `switch-builds-without-losing-vaults` D1. Only with no list here.
                            restoreFile = restoreFile.takeIf { !hasVault },
                            onOpenVaultFile = ::openRestoreVaultFile,
                        )
                        importingArchive -> ArchiveImportScreen(
                            mnemonic = mnemonic,
                            onPickFile = ::pickArchiveFile,
                            onPickFolder = { pickMirrorFolder(restore = true, pick = BackupPick.IMPORT) },
                            onImport = ::importArchive,
                            onRestoreThisList = ::importThisListBackup,
                            onWords = ::importWithWords,
                            onRestoreAsList = ::importAsList,
                            onBackToWords = { importNeedsPassphrase = false; importInUse = false; importNoRoom = false },
                            onDone = ::finishImport,
                            onCancel = ::closeArchiveImport,
                            fileChosen = archiveFileName,
                            busy = busy,
                            failed = archiveFailed,
                            full = archiveFull,
                            progress = archiveProgress,
                            blocked = archiveBlocked,
                            kind = importKind,
                            folder = importFolder,
                            wordsFailed = importWordsFailed,
                            backupProblem = importProblem,
                            needsPassphrase = importNeedsPassphrase,
                            inUse = importInUse,
                            noRoom = importNoRoom,
                            sealing = importSealing,
                            done = importDone,
                        )
                        panicScreen -> PanicScreen(
                            enabled = panicEnabled,
                            busy = panicBusy,
                            problem = panicProblem,
                            settled = panicSettled,
                            onSet = { passphrase, phrase ->
                                runPanic { lifecycleOps.setPanic(passphrase, phrase) }
                            },
                            onChange = { current, passphrase, phrase ->
                                runPanic { lifecycleOps.changePanic(current, passphrase, phrase) }
                            },
                            onTurnOff = { current -> runPanic { lifecycleOps.clearPanic(current) } },
                            onForget = { passphrase -> runPanic { lifecycleOps.forgetPanic(passphrase) } },
                            onCancel = ::closePanic,
                            backupConfigured = CloudMirror.destination(index) != null,
                        )
                        // `recovery-kit-three-views`, D4. Before the words; the words themselves
                        // are the kit branch above, in its review form.
                        kitViewFor != null -> RecoveryKitViewScreen(
                            viewsLeft = kitViewsLeft(kitViewFor!!),
                            vaultName = kitViewFor!!.takeIf { it >= 0 }
                                ?.let { p -> vaultEntries.firstOrNull { it.position == p }?.name },
                            busy = kitViewBusy,
                            wrongPassphrase = kitViewWrong,
                            failed = kitViewFailed,
                            onShow = ::showKit,
                            onDone = ::closeKitView,
                        )
                        // P2-17. Ahead of the enrolment screen it is not: this one
                        // is the owner pointing the camera at something, and it is
                        // reached from the drawer over whatever list was showing.
                        // `camera-capture-feedback`. **`open == null` in the
                        // guard**, so tapping the receipt draws the viewer over the
                        // camera through the branch that already exists rather than
                        // a second copy of it here, and back — which clears `open`
                        // before it reaches the camera in `dismissTop` — lands on
                        // the viewfinder again. That is what a camera's thumbnail
                        // does everywhere, and the cost is the session reopening on
                        // the way back.
                        cameraScreen && open == null -> CameraScreen(
                            permitted = cameraPermitted,
                            onRequestPermission = ::requestCamera,
                            onRequestAudio = ::requestCameraAudio,
                            onPhoto = ::storePhoto,
                            beginRecording = ::beginRecording,
                            endRecording = ::endRecording,
                            receipt = lastCapture?.thumb?.let(::thumbnailFor),
                            captured = lastCapture != null,
                            saving = captureSaving,
                            onOpenReceipt = ::openCaptureReceipt,
                            onCancel = ::closeCamera,
                            problem = cameraProblem,
                        )
                        // `voice-memo`. Beside the camera and for its reason: this is
                        // the owner making something, reached from the grid's floating
                        // set over whatever list was showing.
                        voiceMemoScreen -> {
                            // D6. A Stop from the shade keeps the memo for listen-back only
                            // while this is on screen with the vault open.
                            DisposableEffect(Unit) {
                                VoiceMemo.screenShowing = true
                                onDispose { VoiceMemo.screenShowing = false }
                            }
                            VoiceMemoScreen(
                                permitted = voiceMemoPermitted,
                                onRequestPermission = ::requestMemoAudio,
                                memo = memoHere(),
                                changes = memoChanges,
                                onRecord = ::recordMemo,
                                onStop = ::stopVoiceMemo,
                                onSave = ::saveVoiceMemo,
                                onDiscard = ::discardMemo,
                                onBack = ::requestLeaveVoiceMemo,
                                onUnsavedChange = { memoUnsaved = it },
                                // `distribution-flavors`: no trial on `github`, so no line.
                                startsTrial = BuildConfig.TRIAL &&
                                    Entitlement.trialStartedAt(this@MainActivity) == 0L,
                                problem = voiceMemoProblem,
                                // `voice-memo-layout`, D2. Listen-back reads the held memo's
                                // blobs, under the memo's own key.
                                store = blobs,
                                notice = memoNotice,
                            )
                            if (confirmingMemoDiscard) {
                                DiscardMemoConfirmation(
                                    onConfirm = {
                                        confirmingMemoDiscard = false
                                        // Leaving loses it: the recording or the held memo,
                                        // every blob it wrote and its record.
                                        discardMemo()
                                        closeVoiceMemo()
                                    },
                                    onDismiss = { confirmingMemoDiscard = false },
                                )
                            }
                        }
                        // P2-2. Same position and the same reason as P2-1's:
                        // reached from settings, behind the kit branch so the new
                        // vault's own 24 words still come first.
                        // The decoy's own 24 words, named as the decoy's —
                        // `RecoveryKitScreen`'s `vaultName` form. Ahead of the frame
                        // and inside the flow: see `decoyShowingKit`.
                        decoying && decoyShowingKit && kitChecking -> KitCheckScreen(
                            mnemonic = mnemonic,
                            words = decoyKitWords.orEmpty(),
                            onCorrect = { kitPassed(::returnFromDecoy) },
                            onSeeWords = ::kitSeeWords,
                            framing = KitCheckFraming.Standalone,
                        )
                        decoying && decoyShowingKit -> RecoveryKitScreen(
                            words = decoyKitWords.orEmpty(),
                            // D6. The decoy is a list of its own, recovered from the unlock screen.
                            onPrint = { printRecoveryKit(decoyKitWords.orEmpty(), KitOwner.List(decoyName.trim())) },
                            vaultName = decoyName.trim(),
                            acknowledged = kitAcknowledged,
                            onContinue = { kitContinue(gated = true, then = ::returnFromDecoy) },
                        )
                        decoying -> DecoyFrame(
                            step = decoyStep,
                            onExit = ::closeDecoy,
                            onBack = if (DecoyFlow.canGoBack(decoyStep)) {
                                { decoyStep.previous?.let { decoyStep = it } }
                            } else {
                                null
                            },
                        ) {
                            when (decoyStep) {
                                DecoyFlow.Step.Intro -> DecoyIntroStep(
                                    onContinue = { decoyStep = DecoyFlow.Step.Photos },
                                    // Only where first run is the caller: from
                                    // Settings the user is not being walked into it.
                                    // Never on `github`, which has no trial.
                                    trialNotice = if (onboardingStep == null || !BuildConfig.TRIAL) null else
                                        stringResource(R.string.decoy_trial_notice),
                                )

                                DecoyFlow.Step.Photos -> DecoyPhotosStep(
                                    summary = decoySummary,
                                    declineReason = Decoy.CAPTURED_DURING_SETUP,
                                    onPickItems = ::pickDecoyItems,
                                    onContinue = { decoyStep = DecoyFlow.Step.Identity },
                                )

                                DecoyFlow.Step.Identity -> DecoyIdentityStep(
                                    name = decoyName,
                                    onName = { decoyName = it },
                                    onContinue = {
                                        decoyPassBytes?.fill(0)
                                        decoyPassBytes = it
                                        decoyStep = DecoyFlow.Step.Build
                                    },
                                )

                                DecoyFlow.Step.Build -> DecoyBuildStep(
                                    name = decoyName.trim(),
                                    summary = decoySummary,
                                    preserveCount = decoyPreserve.size,
                                    busy = busy,
                                    progress = setupProgress,
                                    problem = decoyProblem,
                                    onOpenPreserve = { decoySheet = DecoySheet.Preserve },
                                    onAuthorise = {
                                        decoyAuthFailed = false
                                        decoySheet = DecoySheet.Authorise
                                    },
                                )

                                DecoyFlow.Step.Handover -> DecoyHandoverStep(
                                    name = decoyName.trim(),
                                    itemCount = decoySummary?.importable ?: 0,
                                    onShowKit = { decoyShowingKit = true },
                                )
                            }

                            when (decoySheet) {
                                DecoySheet.Authorise -> DecoyAuthSheet(
                                    onConfirm = ::createDecoy,
                                    onCancel = { decoySheet = null },
                                    busy = busy,
                                    failed = decoyAuthFailed,
                                )

                                DecoySheet.Preserve -> DecoyPreserveSheet(
                                    count = decoyPreserve.size,
                                    onAdd = { decoyPreserve += it },
                                    onRemove = {
                                        decoyPreserve[it].fill(0)
                                        decoyPreserve.removeAt(it)
                                    },
                                    onDone = { decoySheet = null },
                                )

                                null -> Unit
                            }
                        }
                        // `vault-list-refresh`, D8. Reached from the row's menu, and ends
                        // on the list either way: Save writes the name and the appearance
                        // in one keyring save, back discards.
                        // `vault-passphrase-manage`, D6. Pushed over Edit vault and back onto it;
                        // its draft is held above, so it is still there on return.
                        editingVault != null && passphraseScreen != null -> {
                            val entry = editingVault!!
                            val state = PassphraseActState(busy, passphraseWrong, passphraseProblem)
                            when (passphraseScreen!!) {
                                // `new-vault-setup-flow`. Add is a flow, drawn above the kit branch.
                                PassphraseAct.Add -> Unit
                                PassphraseAct.Change -> ChangeVaultPassphraseScreen(
                                    vault = entry,
                                    state = state,
                                    onChange = { current, new -> changeVaultPassphrase(entry, current, new) },
                                    onBack = ::closePassphraseScreen,
                                )
                                PassphraseAct.Remove -> RemoveVaultPassphraseScreen(
                                    vault = entry,
                                    state = state,
                                    onRemove = { current -> removeVaultPassphrase(entry, current) },
                                    onBack = ::closePassphraseScreen,
                                )
                            }
                        }
                        editingVault != null -> EditVaultScreen(
                            vault = editingVault!!,
                            name = editDraftName,
                            appearance = editDraftAppearance,
                            onDraft = { name, appearance ->
                                editDraftName = name
                                editDraftAppearance = appearance
                            },
                            onPassphrase = { act ->
                                passphraseWrong = false
                                passphraseProblem = null
                                passphraseStatus = null
                                if (act == PassphraseAct.Add) openAddFlow() else passphraseScreen = act
                            },
                            status = passphraseStatus,
                            onSave = { name, appearance ->
                                val ok = VaultKeyringSession.editVault(
                                    blobs, editingVault!!.position, name, appearance,
                                )
                                vaultEntries = VaultKeyringSession.vaults()
                                if (ok) {
                                    editingVault = null
                                    vaultListProblem = null
                                } else {
                                    vaultListProblem = getString(R.string.vault_list_not_saved)
                                }
                            },
                            onBack = ::closeEditVault,
                            busy = busy,
                            problem = vaultListProblem,
                        )
                        // `per-vault-passphrases`. Beside the creation screen because
                        // both are reached from the list and both end on it.
                        recoveringVault -> RecoverVaultScreen(
                            mnemonic = mnemonic,
                            onRecover = ::recoverVaultOntoList,
                            onCancel = ::closeRecoverVault,
                            busy = busy,
                            failed = vaultRecoveryFailed,
                        )
                        // P2-13. A screen over the grid, reached from the
                        // selection bar, so it sits with the other screens rather
                        // than inside the grid that raised it.
                        // `folder-system`. One picker, two questions — a selection
                        // needing a destination, and a folder needing one. The
                        // folder's branch is first because it is drawn *over* the
                        // folder's own surface, which is what opened it.
                        movingFolder != null -> FolderPickerScreen(
                            count = 0,
                            movingFolder = movingFolder,
                            // Only folders made where this one was; a folder
                            // made nowhere is never a parent.
                            folders = index.folders.filter {
                                it.destination != null && it.destination == movingFolder?.destination
                            },
                            currentFolderId = movingFolder?.parentId,
                            problem = folderProblem,
                            onMove = { destination ->
                                val moving = movingFolder ?: return@FolderPickerScreen
                                folderWrite {
                                    VaultFolders.reparent(
                                        blobs, it.first, it.second, moving.folderId, destination
                                    )
                                } ?: return@FolderPickerScreen
                                movingFolder = null
                            },
                            onCancel = { movingFolder = null },
                        )
                        movingToFolder -> {
                            val chosen = shown.filter { it.itemId in selection }
                            FolderPickerScreen(
                                count = selection.size,
                                movingFolder = null,
                                folders = VaultFolders.accepting(index.folders, chosen),
                                mixedKinds = chosen.mapTo(mutableSetOf()) { VaultDestination.of(it.kind) }.size > 1,
                                currentFolderId = here,
                                problem = null,
                                onMove = { destination ->
                                    commitItems(chosen) { id, vmk ->
                                        VaultFolders.move(blobs, id, vmk, chosen, destination)
                                    }
                                    selection = emptySet()
                                    movingToFolder = false
                                },
                                onCancel = { movingToFolder = false },
                            )
                        }
                        // `viewer-chrome-and-editor`. The selection's picker, scoped
                        // to the one item the viewer is showing. Above the viewer in
                        // the `when`, so it is drawn over the item it is about.
                        movingItem != null -> FolderPickerScreen(
                            count = 1,
                            movingFolder = null,
                            folders = VaultFolders.accepting(index.folders, listOfNotNull(movingItem)),
                            currentFolderId = movingItem?.folderId,
                            problem = null,
                            onMove = { destination ->
                                val target = movingItem ?: return@FolderPickerScreen
                                commitItems(listOf(target)) { id, vmk ->
                                    VaultFolders.move(blobs, id, vmk, listOf(target), destination)
                                }
                                // The viewer's list is rebuilt from the index, so an
                                // item moved out of the folder being paged is no
                                // longer in it — leaving the viewer open on a list it
                                // has left is what closing here prevents.
                                movingItem = null
                                open = null
                            },
                            onCancel = { movingItem = null },
                        )
                        covering != null -> FolderCoverScreen(
                            folder = covering!!,
                            items = VaultFolders.items(
                                index.folders, ordered, covering?.folderId
                            ),
                            thumbnailFor = ::thumbnailFor,
                            onChoose = { chosen ->
                                val target = covering ?: return@FolderCoverScreen
                                commitIndex {
                                    VaultFolders.setCover(
                                        blobs,
                                        it.first,
                                        it.second,
                                        target.folderId,
                                        // The item's identity in bytes — the same
                                        // sixteen `itemId` prints as hex.
                                        chosen?.let { it.blobIds.firstOrNull() ?: it.noteId },
                                    )
                                }
                                covering = null
                            },
                            onBack = { covering = null },
                        )
                        deletingFolder != null -> FolderDeleteScreen(
                            folder = deletingFolder!!,
                            parentName = deletingFolder?.parentId?.let { parent ->
                                index.folders.firstOrNull { it.id == hex(parent) }?.name
                            },
                            onConfirm = {
                                val target = deletingFolder ?: return@FolderDeleteScreen
                                commitIndex {
                                    VaultFolders.delete(
                                        blobs, it.first, it.second, target.folderId
                                    )
                                }
                                deletingFolder = null
                            },
                            onCancel = { deletingFolder = null },
                        )
                        settingUpIdentity != null -> IdentitySetupScreen(
                            identity = settingUpIdentity!!,
                            onComplete = ::completeSetup,
                            onFinish = ::closeIdentitySetup,
                            onCancel = ::closeIdentitySetup,
                            // Handed on so the hosted cover draws its ordinary state.
                            // Not a gate: the sheet asked, and a refusal never gets
                            // this far.
                            grantGiven = coverGrant,
                            // `cover-hold-dots` D3. The hold steps' confirm, performed here.
                            onHoldConfirmed = { performHaptic(android.view.HapticFeedbackConstants.CONFIRM) },
                        )
                        identityScreen -> IdentityScreen(
                            current = identity,
                            onChoose = ::chooseIdentity,
                            onCancel = { identityScreen = false },
                            configured = { candidate ->
                                candidate.cover?.let {
                                    CoverTrigger.isConfigured(this@MainActivity, it)
                                } ?: true
                            },
                            calculatorMethod = CoverTrigger.calculatorMethod(this@MainActivity),
                        )
                        // `biometric-unlock-screen`. The enable prompt and the enrolment
                        // round trip both land back here, with the rows re-read.
                        // `face-down-lock-settings` D7. The drawer's face-down row opens it.
                        faceDownScreen -> FaceDownScreen(
                            available = faceDownAvailable,
                            on = faceDownOn,
                            lockAfterMs = faceDownLockAfter,
                            screenTimeoutMs = screenTimeoutMs,
                            onToggle = ::toggleFaceDown,
                            onLockAfter = ::chooseFaceDownLockAfter,
                            onBack = { faceDownScreen = false },
                        )
                        biometricScreen -> BiometricScreen(
                            layout = biometricLayout(),
                            state = biometricState(),
                            hasFingerprint = bioHasFingerprint,
                            hasFace = bioHasFace,
                            onToggle = ::toggleBiometric,
                            onSetUp = ::openBiometricEnrol,
                            onBack = { biometricScreen = false },
                        )
                        // P4-3. A received item on the ordinary viewer, with every
                        // optional act absent: it is not in the index yet, so
                        // renaming, deleting, exporting or foldering it would be
                        // acts on something that is not a vault item. Keeping it
                        // makes it one, and then all of them apply.
                        sharePreview != null -> VaultViewer(
                            items = listOf(sharePreview!!),
                            startIndex = 0,
                            store = blobs,
                            vmk = VaultSession.vmk()!!,
                            fullImageFor = ::decodeFull,
                            media = mediaSettings,
                            memos = memoSettings,
                            onDelete = null,
                            onRename = null,
                            onExport = null,
                            onBack = { sharePreview = null },
                            onRotate = null,
                            onEdit = null,
                            onShare = null,
                            onSetCover = null,
                            onMoveToFolder = null,
                            // Not in the index yet, so there is no row to repoint.
                            onUseFrame = null,
                        )
                        // `remote-pairing`. Above the choice and the contacts screen
                        // it is reached from, and above the send flow it can be
                        // reached from too.
                        remoteStep != null -> RemotePairingScreen(
                            step = remoteStep!!,
                            mnemonic = Mnemonic.fromResources(this@MainActivity),
                            onNameChange = ::remoteNameChange,
                            onWordsChange = ::remoteWordsChange,
                            onMake = ::remoteMake,
                            onSend = ::sendPairingFile,
                            onPick = ::pickPairingFile,
                            onCancel = ::cancelRemote,
                            onNewInvite = ::remoteNewInvite,
                            onDone = ::remoteDone,
                            onBack = ::closeRemote,
                        )
                        // P4-4. Above the contacts screen it is reached from, the
                        // ordering every sheet-over-screen pair here uses.
                        pairingScreen -> PairingScreen(
                            step = pairingStep,
                            publicKey = (identityState as? Identity.State.Ready)
                                ?.publicKey ?: ByteArray(0),
                            justSaved = pairingSaved,
                            permitted = cameraPermitted,
                            onRequestPermission = ::requestCamera,
                            onDecoded = ::pairingDecoded,
                            scanMessage = pairingMessage,
                            onScan = ::beginPairingScan,
                            name = pairingName,
                            onNameChange = { pairingName = it },
                            onSave = ::savePairedContact,
                            onBack = ::closePairing,
                        )
                        addingContact -> AddContactScreen(
                            onChoose = ::chooseAddRoute,
                            onBack = { addingContact = false },
                        )
                        contactsScreen -> ContactsScreen(
                            state = identityState,
                            waiting = pairingWaiting,
                            onResume = ::resumeRemote,
                            removing = removingContact,
                            onAdd = ::openAddContact,
                            onAskRemove = { removingContact = it },
                            onConfirmRemove = ::removeContact,
                            onDismissRemove = { removingContact = null },
                            confirmingNewIdentity = confirmingNewIdentity,
                            onAskNewIdentity = { confirmingNewIdentity = true },
                            onConfirmNewIdentity = ::startNewIdentity,
                            onDismissNewIdentity = { confirmingNewIdentity = false },
                            onBack = ::closeContacts,
                        )
                        // `third-party-notices`. Detail over list; the bar's back is the gesture's.
                        licencesScreen -> LicencesScreen(
                            components = notices,
                            open = licenceOpen,
                            onOpen = { licenceOpen = it },
                            onBack = { if (licenceOpen != null) licenceOpen = null else licencesScreen = false },
                        )
                        crashLogScreen -> CrashLogHook.Screen(
                            record = crashRecord,
                            onOpen = { crashRecord = it },
                            confirmingClear = confirmingCrashClear,
                            onAskClear = { confirmingCrashClear = true },
                            onDismissClear = { confirmingCrashClear = false },
                            // The bar's back, which is back's order without the dialog on top.
                            onBack = { if (crashRecord != null) crashRecord = null else closeCrashLog() },
                        )
                        openingShare -> ShareOpenScreen(
                            mnemonic = Mnemonic.fromResources(this@MainActivity),
                            words = shareWords,
                            onWordsChange = { updated -> shareWords = updated },
                            busy = shareOpenBusy,
                            failed = shareOpenFailed,
                            refusal = shareRefusal,
                            opened = shareOpened,
                            probing = shareProbing,
                            sealedHere = shareSealedHere,
                            identityUnreadable = shareIdentityUnreadable,
                            onContacts = {
                                endShareOpen()
                                openContacts()
                            },
                            onOpen = ::openShare,
                            onView = ::viewShare,
                            onKeep = ::keepShare,
                            onBack = ::endShareOpen,
                            // `one-time-purchase` D5. Keeping is an add; opening is not,
                            // and the screen is handed this rather than asking it.
                            keepAllowed = addAllowed(),
                            price = price,
                            onBuy = ::buy,
                            purchaseProblem = purchaseProblem,
                        )
                        sharingItem != null -> {
                            val item = sharingItem!!
                            ShareScreen(
                                itemName = item.filename,
                                itemSize = item.plaintextLength,
                                refusal = ShareSend.refusal(words(), item),
                                contacts = shareContacts,
                                selections = shareSelections,
                                onSelections = { shareSelections = it },
                                onPair = ::openAddContact,
                                code = shareCode,
                                sealedTo = shareSealedTo,
                                busy = shareBusy,
                                progress = shareProgress,
                                failed = shareFailed,
                                // `exif-strip-choice`. The mime comes from the name, which
                                // is where every other decision about an item's type comes
                                // from; a control with nothing behind it is not drawn.
                                strippable = ExifPolicy.strippable(
                                    ExportDestination.mimeOf(item.filename),
                                ),
                                removed = ExifPolicy.resolve(photoDetailsPreset, photoDetailsCustom),
                                onCreate = ::startShare,
                                onSend = ::sendShare,
                                onSave = ::saveShare,
                                saved = shareSaved,
                                onCopyCode = ::copyShareCode,
                                onBack = ::endShare,
                                // `one-time-purchase` D5. Read as it draws — see addAllowed.
                                trialEnded = !addAllowed(),
                                price = price,
                                onBuy = ::buy,
                                purchaseProblem = purchaseProblem,
                            )
                        }
                        exporting -> ExportScreen(
                            // P2-11. The selection's size when the bar opened this
                            // screen, the vault's when the drawer did.
                            itemCount = exportSelection?.size ?: ordered.size,
                            scoped = exportSelection != null,
                            // `intruder-captures-at-home` D9. Captures out of the keyring's store.
                            captures = exportCaptures,
                            removed = ExifPolicy.resolve(photoDetailsPreset, photoDetailsCustom),
                            onExport = ::startExport,
                            onPickFolder = ::pickExportFolder,
                            onCancel = ::closeExport,
                            folderChosen = exportTree?.lastPathSegment,
                            busy = busy,
                            failed = exportAuthFailed,
                            progress = exportProgress,
                            onArchive = ::startArchiveExport,
                            authorised = exportAuthorised,
                            vaultName = openVaultName(),
                            // D8. The home capture store belongs to the list; everything else
                            // exported from a protected vault takes that vault's own passphrase.
                            ownPassphrase = openVaultProtected() && !(exportCaptures && !captureVault),
                        )
                        recovering -> RecoverScreen(
                            mnemonic = mnemonic,
                            onRecover = ::attemptRecovery,
                            onCancel = ::closeRecover,
                            busy = busy,
                            failed = recoveryFailed,
                            inUse = recoveryInUse,
                            restoring = restoringOntoEmpty,
                            backupName = pendingBackup?.name,
                            backupFolder = pendingBackup?.folder == true,
                            onAddBackupFile = { addRecoverBackup(folder = false) },
                            onAddBackupFolder = { addRecoverBackup(folder = true) },
                            onRemoveBackup = if (restoringOntoEmpty) null else {
                                { pendingBackup = null; recoveryBackupProblem = null }
                            },
                            backupProblem = recoveryBackupProblem,
                            progress = recoveryProgress,
                            sealing = recoverySealing,
                        )
                        // `first-run-onboarding`. **Above the gate branches**, because
                        // the flow now owns step 1 — the passphrase, which runs before
                        // a vault exists — through step 8, which runs after unlock. A
                        // lock landing mid-flow still shows the lock screen: `lockVault`
                        // ends the flow first, so this stops matching before the gates
                        // are consulted.
                        // The welcome is **not** inside the frame: it is the door,
                        // not a step, and drawing it in the frame put "Step 1 of 8"
                        // on a screen that does not occupy one of the eight.
                        // `onboarding-language-screen`. First of all, before the welcome: every
                        // screen after it is read in the language chosen here.
                        onboardingStep == Onboarding.Step.Name && !languageSeen ->
                            OnboardingLanguageScreen(
                                onChoose = ::previewLanguage,
                                onContinue = { languageSeen = true },
                            )
                        onboardingStep == Onboarding.Step.Name && !welcomeSeen -> {
                            // Back returns to the language screen, the one before it.
                            BackHandler { languageSeen = false }
                            OnboardingWelcomeScreen(
                                onBegin = { welcomeSeen = true },
                                onRestore = { welcomeSeen = true; openBackup() },
                                onLanguage = { languageSheet = true },
                            )
                        }
                        onboardingStep != null -> OnboardingFrame(
                            step = onboardingStep!!,
                            // `recovery-practice` D5. The check's Back is the words, still on step 2.
                            onBack = if (onboardingStep == Onboarding.Step.Kit && kitChecking) {
                                ::kitSeeWords
                            } else {
                                onboardingStep!!.previousStep()
                                    ?.let { prev -> { if (!busy) advanceOnboarding(prev) } }
                            },
                            // `first-vault-naming` D5. Drawn, and inert, while the vault is being written.
                            backEnabled = !busy,
                        ) {
                            OnboardingStepContent(onboardingStep!!)
                        }
                        !hasVault -> VaultSetup(
                            onCreate = { bytes, _ -> createVault(bytes, firstName.trim(), firstAppearance) },
                            busy = busy,
                            progress = setupProgress,
                            name = firstName,
                            onName = {
                                firstName = it
                                firstNameError = null
                            },
                            appearance = firstAppearance,
                            onAppearance = { firstAppearance = it },
                            nameError = firstNameError,
                            onNameContinue = ::firstNameContinue,
                            problem = setupProblem,
                            onRestoreBackup = ::openBackup,
                            restored = restoredLine(),
                        )
                        !keyringOpen -> VaultUnlockScreen(
                            onUnlock = ::attemptUnlock,
                            busy = busy,
                            failed = unlockFailed,
                            // Absent unless there is a wrap to open, so the
                            // default install's lock screen is unchanged.
                            onBiometric = if (bioEnabled) ({ unlockWithBiometric() }) else null,
                            biometricLabel = bioButtonLabel,
                            // From held values — no binder read in composition.
                            biometricFace = biometricLayout() == BiometricLayout.Face,
                            biometricNotice = bioNotice?.let { stringResource(it) },
                            warnings = warnings + newerVersionWarning() + indexWarnings(),
                            onRecover = { recovering = true },
                        )
                        // `recovery-practice` D3. After a fingerprint unlock that was due a
                        // check, in place of the list and before it is drawn.
                        practiceShowing -> when (practiceState.stage) {
                            PracticeStage.Check -> PassphrasePracticeScreen(
                                state = practiceState,
                                words = BiometricWrap.practiceWords(biometricLayout()),
                                onCheck = ::practiceCheck,
                                onSkip = ::practiceSkip,
                                onSetNew = {
                                    practiceState.draft.clear()
                                    practiceState.confirmNeeded = false
                                    practiceState.stage = PracticeStage.NewPassphrase
                                },
                            )
                            PracticeStage.NewPassphrase -> PracticeNewPassphraseScreen(
                                state = practiceState,
                                words = BiometricWrap.practiceWords(biometricLayout()),
                                onSave = ::practiceSave,
                                onBack = ::practiceBack,
                            )
                        }
                        // `intruder-captures-at-home`. The Intruder capture screen, its viewer and
                        // the move, over the vault list they were opened from. Below the lock
                        // screen, so a lock landing on any of them shows the lock screen; the
                        // export screen, above, draws over them.
                        // `per-vault-intruder-capture` D6. The same surfaces, inside a vault, when the
                        // screen is that vault's: at home with no vault open, or in the vault it is of.
                        unlocked == captureVault && captureMoveFolders != null -> {
                            val moving = captureMoving.orEmpty()
                            FolderPickerScreen(
                                count = moving.size,
                                movingFolder = null,
                                // `album-organisation`'s kind rule, over the chosen vault's folders.
                                folders = VaultFolders.accepting(captureMoveFolders.orEmpty(), moving),
                                mixedKinds = moving.mapTo(mutableSetOf()) { VaultDestination.of(it.kind) }.size > 1,
                                currentFolderId = null,
                                problem = captureMoveProblem,
                                onMove = ::commitCaptureMove,
                                onCancel = ::closeMoveFolders,
                                busy = captureMoveBusy,
                                // Its top level is the chosen vault's, named — not "Home screen",
                                // which from here reads as the vault list.
                                rootName = captureMoveTarget?.name,
                            )
                        }
                        unlocked == captureVault && captureMoving != null -> VaultPickerScreen(
                            vaults = vaultEntries,
                            count = captureMoving.orEmpty().size,
                            onPick = ::pickMoveVault,
                            onBack = ::closeCaptureMove,
                            problem = captureMoveProblem,
                        )
                        unlocked == captureVault && intruderScreen && captureKey != null &&
                            captureRows.any { it.item != null && it.item.itemId == captureOpen?.itemId } -> {
                            // The held captures only — a row whose capture has left has nothing to show.
                            val held = captureRows.mapNotNull { it.item }
                            VaultViewer(
                                items = held,
                                startIndex = held.indexOfFirst { it.itemId == captureOpen?.itemId }.coerceAtLeast(0),
                                store = blobs,
                                vmk = captureKey!!,
                                fullImageFor = { decodeFull(it, captureKey) },
                                media = mediaSettings,
                                memos = memoSettings,
                                thumbnailFor = ::captureThumbnailFor,
                                // D12. Move to vault, Export and Delete; every other action absent.
                                onDelete = { captureDeleting = listOf(it) },
                                onMoveToVault = { startCaptureMove(listOf(it)) },
                                onExportAction = { startCaptureExport(listOf(it)) },
                                onRename = null,
                                onExport = null,
                                onRotate = null,
                                onEdit = null,
                                onShare = null,
                                onSetCover = null,
                                onMoveToFolder = null,
                                onBack = { captureOpen = null },
                            )
                        }
                        unlocked == captureVault && intruderScreen -> IntruderCaptureScreen(
                            rows = captureRows,
                            loaded = capturesLoaded,
                            enabledModes = captureModes,
                            // `biometric-unlock-screen`'s noun, for "Unlock screen: wrong …".
                            layout = biometricLayout().takeUnless { it == BiometricLayout.None } ?: BiometricLayout.Unnamed,
                            thumbnailFor = ::captureThumbnailFor,
                            selected = captureSelection,
                            onOpen = { captureOpen = it },
                            onBeginSelection = { captureSelection = setOf(it.itemId) },
                            onToggleSelection = { item ->
                                // Deselecting the last one ends the mode, as on the grid.
                                captureSelection =
                                    if (item.itemId in captureSelection) captureSelection - item.itemId
                                    else captureSelection + item.itemId
                            },
                            onClearSelection = { captureSelection = emptySet() },
                            onMoveSelected = { startCaptureMove(heldCaptures(captureSelection)) },
                            onExportSelected = { startCaptureExport(heldCaptures(captureSelection)) },
                            onDeleteSelected = { captureDeleting = heldCaptures(captureSelection).ifEmpty { null } },
                            onSettings = ::openCaptureSheet,
                            onBack = ::closeIntruderScreen,
                            notice = captureNotice,
                            subtitle = if (captureVault) openVaultName() else null,
                            lacking = lackingFor(captureModes).entries.groupBy({ it.value }, { it.key }),
                            onTurnBackOn = ::turnCaptureBackOn,
                            autoResetRisk = captureModes.isNotEmpty() && !autoRevokeExempt,
                            onKeepPermissions = ::openAutoRevokeSettings,
                        )
                        // `vault-keyring`. A keyring is open and no vault is: the
                        // list. Directly below the lock screen, because a lock landing
                        // here must show the lock screen and not a list of names, and
                        // above every other surface because none of them means anything
                        // without a vault open.
                        !unlocked -> home(HomeTarget.List, homeList, homeGrid)
                        // `settings-separation`. **Below `!unlocked`**, so a lock that
                        // lands while this is open shows the lock screen and not a
                        // purchase over a locked vault. It is a screen drawn over
                        // whichever list is showing, reached from one drawer row.
                        purchaseScreen -> PurchaseScreen(
                            daysRemaining = Entitlement.daysRemaining(this@MainActivity),
                            owned = owned,
                            pending = purchasePending,
                            trialStarted =
                                Entitlement.trialStartedAt(this@MainActivity) != 0L,
                            price = price,
                            onBuy = ::buy,
                            purchaseProblem = purchaseProblem,
                            onBack = { purchaseScreen = false },
                        )
                        // P2-14. A screen over the **viewer**, immediately ahead of
                        // it so the item stays open underneath: back clears the
                        // flag and the user is looking at the same item again.
                        // P2-21. Above the viewer for the reason rename is: it is a
                        // screen drawn over whatever list is showing, and the item
                        // behind it does not change while it is up.
                        noteScreen -> {
                            val note = editingNote
                            // The text comes from the blob now — read once, keyed on
                            // the row, so reopening the same note does not re-decrypt
                            // it on every recomposition.
                            val text = remember(note?.itemId) {
                                val vmk = VaultSession.vmk()
                                if (note == null || vmk == null) {
                                    ""
                                } else {
                                    runCatching { VaultNote.read(blobs, note, vmk) }.getOrDefault("")
                                }
                            }
                            NoteScreen(
                                title = note?.let { VaultNote.titleOf(it) } ?: "",
                                body = text,
                                onSave = { title, body -> saveNote(editingNote ?: note, title, body) },
                                onCancel = ::closeNote,
                                onDiscard = ::closeNote,
                                // P4-2, from the screen the note is open on. Null
                                // while the note has never been written: there is no
                                // row to seal yet. **Read inside the lambda**, not
                                // captured — the editor commits before it calls this,
                                // and the row that commit produced is the one to
                                // share, not the one that was there when the bar was
                                // composed.
                                onShare = if (note == null) {
                                    null
                                } else {
                                    { editingNote?.let(::beginShare) }
                                },
                                // `captures-folder-and-log`. P2-11's scoped export with
                                // this note as its scope — the same path the viewer's
                                // own export takes, not a second one. Reads
                                // `editingNote` for `onShare`'s reason: the editor
                                // commits before calling this, and the row that commit
                                // produced is the one to export.
                                onExport = if (note == null) {
                                    null
                                } else {
                                    {
                                        editingNote?.let { row ->
                                            exportSelection = setOf(row.itemId)
                                            closeNote()
                                            exporting = true
                                        }
                                    }
                                },
                                settings = noteSettings,
                            )
                        }
                        // `viewer-chrome-and-editor`. Above the viewer, so back
                        // leaves the editor and lands on the item it was editing.
                        // **The bitmap is decoded here and handed in**: the editor
                        // never sees a path, a stream or a key (I3).
                        editingImage != null -> {
                            val target = editingImage!!
                            val preview = remember(target.itemId) { previewFor(target) }
                            if (preview == null) {
                                // The one failure worth a surface of its own: an image
                                // that will not decode cannot be edited, and an empty
                                // editor would look like a broken one.
                                ViewerNotice(stringResource(R.string.editor_could_not_open))
                            } else {
                                EditorScreen(
                                    preview = preview,
                                    saving = busy,
                                    onSave = { stack -> saveEdit(target, stack) },
                                    onCancel = ::closeEditor,
                                )
                            }
                        }
                        item != null -> {
                            // `viewer-swipe-navigation`, D7. The viewer leaving composition —
                            // back, the gesture, export, the editor, a lock — is the viewer
                            // closing, and the session's positions go to the vault then. Ahead
                            // of the viewer so it is forgotten after it: the settled video
                            // reports where it stopped as its player is released.
                            DisposableEffect(Unit) { onDispose { flushResume() } }
                            VaultViewer(
                                items = shown,
                                startIndex = shown.indexOfFirst { it.itemId == item.itemId },
                                store = blobs,
                                vmk = VaultSession.vmk()!!,
                                fullImageFor = ::decodeFull,
                                media = mediaSettings,
                                memos = memoSettings,
                                // `slideshow`. On Media only, never in the trash; a received
                                // share is the viewer above and has none.
                                slideshow = destination == VaultDestination.Media && !trash,
                                // `viewer-filmstrip`, D6. The slideshow's gate, for its reason.
                                filmstrip = destination == VaultDestination.Media && !trash,
                                thumbnailFor = { it.thumb?.let(::thumbnailFor) },
                                resumeAt = ::resumeAt,
                                onPosition = ::holdResume,
                                // P2-12. Absent in the trash: the item is already
                                // deleted, and restore and destroy are on that
                                // screen's own bar.
                                onDelete = if (trash) null else ({ it: VaultItem -> pendingDelete = it }),
                                // P2-14. Absent in the trash for the same reason: a
                                // trashed item is waiting, not being organised.
                                onRename = if (trash) null else ({ it: VaultItem -> renaming = it }),
                                // P2-18. The identity page's one action, and **P2-11's
                                // scoped export reached from a new place** — the scope
                                // becomes this one item and `ExportScreen` takes the
                                // passphrase exactly as it does for a selection. There
                                // is no second export path and no lowered bar. Absent
                                // in the trash, like the two above.
                                onExport = if (trash) null else ({ it: VaultItem ->
                                    exportSelection = setOf(it.itemId)
                                    open = null
                                    exporting = true
                                }),
                                // `viewer-chrome-and-editor`. A turn writes one field on
                                // the row; nothing decrypts and no blob moves. Absent in
                                // the trash, like everything else that edits an item.
                                // `player-controls-and-menu`. **Withheld on anything that
                                // plays, and not for tidiness.** Rotating records a
                                // rotation `ImagePage` applies when it decodes and the
                                // player has no path for at all, so on a video this was a
                                // control that changed what is stored about the item and
                                // showed the user nothing. Gated the way `onEdit` below
                                // already is: null here, absent from the row, never drawn
                                // disabled.
                                onRotate = if (trash) null else ({ target: VaultItem ->
                                    commitItems(listOf(target)) { id, vmk ->
                                        VaultRotate.rotate(blobs, id, vmk, target)
                                    }
                                }).takeIf { !item.kind.isPlayable },
                                // Images only, and never in the trash — the control is
                                // absent rather than present and failing.
                                onEdit = if (trash) null else ({ target: VaultItem ->
                                    if (target.kind == VaultKind.IMAGE) editingImage = target
                                }).takeIf { item.kind == VaultKind.IMAGE },
                                // P4-2's send flow on the one item the viewer shows —
                                // the same act as the selection bar's control. Absent in
                                // the trash, where the item is waiting to be restored or
                                // destroyed; present for every kind, which is what the
                                // file kind joining §12's prelude bought.
                                onShare = if (trash) null else ({ target: VaultItem ->
                                    beginShare(target)
                                }),
                                // Absent at the vault root: an item in no folder has no
                                // cover to be.
                                onSetCover = if (trash || item.folderId == null) null else (
                                    { target: VaultItem ->
                                        val folder = target.folderId
                                        if (folder != null) {
                                            commitIndex {
                                                VaultFolders.setCover(
                                                    blobs, it.first, it.second, folder,
                                                    // The item's identity in bytes — the
                                                    // same sixteen `itemId` prints as
                                                    // hex, and the same expression the
                                                    // folder's own cover surface uses.
                                                    target.blobIds.firstOrNull()
                                                        ?: target.noteId,
                                                )
                                            }
                                        }
                                    }
                                    ),
                                onMoveToFolder = if (trash) null else ({ target: VaultItem ->
                                    movingItem = target
                                }),
                                // `video-frame-thumbnail`. Absent in the trash, like every
                                // other act that writes to an item; the viewer offers it only
                                // on a settled video.
                                onUseFrame = if (trash) null else ({ target: VaultItem, positionMs: Long ->
                                    useFrame(target, positionMs)
                                }),
                                frameOutcome = frameOutcome,
                                // Exactly what back does — `dismissTop`'s `open` branch.
                                onBack = { open = null },
                            )
                        }
                        else -> home(HomeTarget.Grid, homeList, homeGrid)
                    }

                    // `per-vault-passphrases`. Over the list as a sheet, and
                    // dismissible back onto it with nothing opened.
                    promptingVault?.let { entry ->
                        VaultPassphraseSheet(
                            vaultName = entry.name,
                            onOpen = { bytes -> openVaultWithPassphrase(entry, bytes) },
                            onCancel = ::closeVaultPrompt,
                            busy = busy,
                            failed = vaultPassphraseFailed,
                        )
                    }
                    // `intruder-captures-at-home` D5. The same sheet, over Move to vault, for a
                    // vault with its own passphrase. A wrong passphrase stays on it with its error;
                    // nothing is opened either way — the key reads the folders and is zeroed.
                    captureMovePrompt?.let { entry ->
                        VaultPassphraseSheet(
                            vaultName = entry.name,
                            onOpen = { bytes -> unlockMoveVault(entry, bytes) },
                            onCancel = {
                                captureMovePrompt = null
                                captureMoveFailed = false
                            },
                            busy = captureMoveBusy,
                            failed = captureMoveFailed,
                        )
                    }
                    // D10. There is no trash at home: the confirmation names the count and says
                    // the captures cannot be restored.
                    captureDeleting?.let { doomed ->
                        DeleteConfirmation(
                            items = doomed,
                            captures = true,
                            onConfirm = { deleteCaptures(doomed) },
                            onDismiss = { captureDeleting = null },
                        )
                    }

                    // The two naming sheets, outside the `when` for the folder
                    // sheet's reason: each is a field about something that stays on
                    // screen behind it — the grid for a folder, the open item for a
                    // rename. Above the folder sheet in the composition, because a
                    // rename is reached *from* that sheet.
                    naming?.let { what ->
                        FolderNameSheet(
                            creating = what is FolderNaming.Create,
                            initial = (what as? FolderNaming.Rename)?.folder?.name ?: "",
                            problem = folderProblem,
                            onConfirm = { typed ->
                                when (what) {
                                    is FolderNaming.Create -> folderWrite {
                                        VaultFolders.create(
                                            blobs, it.first, it.second, what.parentId, typed,
                                            destination,
                                        )
                                    }
                                    is FolderNaming.Rename -> folderWrite {
                                        VaultFolders.rename(
                                            blobs, it.first, it.second, what.folder.folderId, typed
                                        )
                                    }
                                }?.let { naming = null }
                            },
                            onCancel = { naming = null },
                        )
                    }

                    // `launcher-identity`. Outside the `when` because it is drawn
                    // *over* the chooser, not instead of it: the row that was tapped
                    // stays visible behind it, and dismissing returns to the list with
                    // nothing stored and nothing switched.
                    committingIdentity?.let { pending ->
                        IdentityCommitSheet(
                            identity = pending,
                            state = commitState,
                            onPrimary = ::commitIdentity,
                            onDismiss = ::closeCommitSheet,
                        )
                    }

                    renaming?.let { target ->
                        RenameSheet(
                            filename = target.filename,
                            onRename = { name ->
                                commitItems(listOf(target)) { id, vmk ->
                                    VaultRename.rename(blobs, id, vmk, target, name)
                                }
                                renaming = null
                            },
                            onCancel = { renaming = null },
                        )
                    }

                    // `folder-actions-sheet`. Outside the `when` for the delete
                    // dialog's reason one step further: a sheet is drawn *over* the
                    // grid, not instead of it, so the tile being acted on stays
                    // visible behind it. Every row that opens a screen dismisses this
                    // first — the sheet is how you reached that screen, not something
                    // to come back to.
                    folderSheet?.let { target ->
                        FolderSheet(
                            folder = target,
                            onRename = {
                                folderProblem = null
                                naming = FolderNaming.Rename(target)
                                folderSheet = null
                            },
                            onMove = {
                                folderProblem = null
                                movingFolder = target
                                folderSheet = null
                            },
                            // The one action that finishes here: six values, all of
                            // them on the sheet, so choosing is the whole of it.
                            onColour = { chosen ->
                                commitIndex {
                                    VaultFolders.setColour(
                                        blobs, it.first, it.second, target.folderId, chosen
                                    )
                                }
                                folderSheet = null
                            },
                            onCover = {
                                covering = target
                                folderSheet = null
                            },
                            onDelete = {
                                deletingFolder = target
                                folderSheet = null
                            },
                            onDismiss = { folderSheet = null },
                        )
                    }

                    // The folder sheet's twin, and beside it for its reason: drawn
                    // *over* the grid, so the note being acted on stays visible behind
                    // the acts. Every row closes it — move and delete because they
                    // raise a surface of their own, pin and colour because the result
                    // is the tile underneath and a sheet in front of that is a sheet in
                    // front of its own answer.
                    noteOptions?.let { target ->
                        NoteSheet(
                            note = target,
                            onPinned = { pinned ->
                                editNoteRow(target) { it.withPinned(pinned) }
                                noteOptions = null
                            },
                            onColour = { chosen ->
                                editNoteRow(target) { it.withColour(chosen) }
                                noteOptions = null
                            },
                            // The viewer's single-item picker, reached from a second
                            // place. One item, one destination, and no second path
                            // through `VaultFolders.move`.
                            onMove = {
                                movingItem = target
                                noteOptions = null
                            },
                            // P2-10's confirmation, unchanged and unscoped: a
                            // single-item delete is a selection of one, and this is
                            // the same trash with the same window.
                            onDelete = {
                                pendingDelete = target
                                noteOptions = null
                            },
                            onDismiss = { noteOptions = null },
                        )
                    }

                    // The header's `ic_tune` on Media, Notes and Memos: that screen's own
                    // settings, over the grid for [folderSheet]'s reason.
                    if (destinationSettings) {
                        DestinationSettingsSheet(
                            destination = destination,
                            onDismiss = { destinationSettings = false },
                            // `media-settings`. Applied as it is made, and kept for the device.
                            media = mediaSettings,
                            onMediaSettings = { next ->
                                mediaSettings = next
                                GridPreference.setMediaSettings(this@MainActivity, next)
                            },
                            notes = noteSettings,
                            onNoteSettings = { next ->
                                noteSettings = next
                                GridPreference.setNoteSettings(this@MainActivity, next)
                            },
                            memos = memoSettings,
                            onMemoSettings = { next ->
                                memoSettings = next
                                GridPreference.setMemoSettings(this@MainActivity, next)
                            },
                        )
                    }

                    // `intruder-captures-at-home`. Outside the `when` for [folderSheet]'s
                    // reason — a sheet is drawn *over* a screen, not instead of it — and
                    // opened only from the Intruder capture screen, so that is what it is over.
                    if (captureSheet) {
                        CaptureSheet(
                            enabled = captureModes,
                            hasFrontCamera = IntruderCapture.hasFrontCamera(this@MainActivity),
                            onEnable = ::enableCaptureMode,
                            onDisable = ::disableCaptureMode,
                            onDismiss = ::closeCapture,
                            problem = captureProblem,
                            lacking = lackingFor(captureModes),
                        )
                    }

                    // `delete-vault`. Beside the item confirmation for its reason: a
                    // dialog is asked *over* whatever raised it, so the vault list and
                    // the grid under the drawer both keep the user where they were. One
                    // dialog, both routes, the same two outcomes.
                    // `protected-vault-destroy-gate`. The gate first, then the choice, in the same
                    // modal; the backup line is what the gate's key read, never a read here.
                    destroyingVault?.let { target ->
                        VaultDestroyDialog(
                            name = target.name,
                            hasBackup = destroyBackup != null,
                            backupIsFile = destroyBackup
                                ?.let { CloudMirror.isFile(android.net.Uri.parse(it)) } == true,
                            onReturnThenDestroy = {
                                destroyKey?.copyOf()?.let { key ->
                                    closeDestroy()
                                    returnThenDestroy(target.position, key)
                                }
                            },
                            onDestroyEverything = {
                                destroyKey?.copyOf()?.let { key ->
                                    val backup = destroyBackup
                                    closeDestroy()
                                    destroyVault(target.position, target.name, key, backup)
                                }
                            },
                            onDismiss = ::closeDestroy,
                            step = destroyStep,
                            ownPassphrase = target.hasOwnPassphrase,
                            // The list route. Inside the open vault whoever is there has just
                            // entered its passphrase, so the way back in is not news to them.
                            showLost = target.position != VaultKeyringSession.openVaultPosition(),
                            checking = destroyChecking,
                            wrong = destroyWrong,
                            onSubmit = { bytes -> submitDestroyGate(target, bytes) },
                        )
                    }

                    // P2-10. Above the `when`, so the same dialog serves the grid
                    // and the viewer. `ordered` is in scope here and nowhere else,
                    // which is why the landing item is computed at this point
                    // rather than inside the delete itself.
                    pendingDelete?.let { target ->
                        // P2-11. The selection when the bar raised it, otherwise
                        // the one item the viewer is showing. A single-item delete
                        // is a selection of one, so there is one dialog and one
                        // confirmation for both.
                        val doomed =
                            if (pendingDeleteSelection) shown.filter { it.itemId in selection }
                            else listOf(target)

                        DeleteConfirmation(
                            items = doomed,
                            // P2-12. The trash's action destroys; the grid's and
                            // the viewer's move to the trash. One dialog, two
                            // texts, and the confirm below runs the operation the
                            // text promised.
                            permanent = pendingPurge,
                            onConfirm = {
                                // `viewer-swipe-navigation`, D9. Within the sequence the
                                // viewer is moving through, so a deleted video lands on the
                                // next video and the only one closes back to the photos.
                                val landing = landingAfterDelete(viewerSequence(shown, target), target)
                                if (pendingPurge) purgeItems(doomed) else deleteItems(doomed)
                                // Only when the viewer is what raised it. From the
                                // grid there is nothing open to move.
                                if (open != null) open = landing
                                selection = emptySet()
                                pendingDeleteSelection = false
                                pendingPurge = false
                                pendingDelete = null
                            },
                            onDismiss = {
                                // The selection survives a declined confirmation:
                                // the user said no to the delete, not to the
                                // twelve taps that built the set.
                                pendingDeleteSelection = false
                                pendingPurge = false
                                pendingDelete = null
                            },
                        )
                    }

                    // `language-picker-redesign` D2. Last, so it draws over whichever screen is
                    // showing; the box takes no touches, only the snackbar does.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(Spacing.lg),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        SnackbarHost(snackbars) { data ->
                            // The language snackbar carries its lines with their locales; a plain
                            // one (`recovery-practice`'s, [showMessage]) is its message alone.
                            val language = data.visuals as? LanguageSnack
                            Snackbar(
                                action = language?.let { visuals ->
                                    {
                                        TextButton(
                                            onClick = data::performAction,
                                            colors = ButtonDefaults.textButtonColors(contentColor = VaultColors.Primary),
                                        ) { Text(visuals.action) }
                                    }
                                },
                                containerColor = VaultColors.SurfaceContainerHigh,
                                contentColor = VaultColors.OnSurface,
                            ) {
                                if (language != null) Text(language.text) else Text(data.visuals.message)
                            }
                        }
                    }
                }
                IdentityLayer()
                }
            }
        }
    }

    /**
     * `recents-shows-identity` D2–D5. The identity picture: composed and laid out for the
     * whole session, invisible and beneath the live screen, and raised — instantly, with no
     * motion of its own — when the user leaves, so the recent-apps card shows the identity.
     *
     * | Identity and state       | Draws                              |
     * |--------------------------|------------------------------------|
     * | A cover is worn          | that cover, still                  |
     * | No cover, a vault exists | the unlock screen, still, empty    |
     * | No cover, no vault yet   | the welcome screen                 |
     *
     * Kept ready rather than built at leave time, so raising it is two modifiers and no
     * composition or library read stands between the leave and the committed frame.
     *
     * **Not composed while the live screen is the music player or the recorder.** A second
     * copy would build a second player and read the library twice for nothing; the live cover,
     * its dots taken down by [onUserLeaveHint], is the picture. The calculator and the unlock
     * screen keep their copy even while live, so a partly typed sequence or passphrase never
     * reaches the card — and the live one is still holding it when the user comes back.
     *
     * Inert in both states: every pointer event is consumed before anything inside it sees
     * one, it is cleared from the semantics tree, and focus cannot enter it.
     */
    @Composable
    private fun IdentityLayer() {
        val raised = leaving
        // D4. The frame that raises the picture is the one the flag comes off after.
        if (raised) SideEffect { liftSecureAfterCommit() }
        val cover = identity.cover
        if (coverShowing && (cover == Cover.MusicPlayer || cover == Cover.VoiceRecorder)) return
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(if (raised) 1f else -1f)
                .graphicsLayer { alpha = if (raised) 1f else 0f }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
                .clearAndSetSemantics {}
                .focusProperties {
                    canFocus = false
                    onEnter = { cancelFocusChange() }
                }
                .focusGroup(),
        ) {
            when {
                cover == Cover.Calculator -> CalculatorCover(onTrigger = {}, still = true)
                cover == Cover.MusicPlayer -> MusicCover(hasAccess = coverGrant, onTrigger = {}, still = true)
                cover == Cover.VoiceRecorder -> RecorderCover(hasPermission = coverGrant, onTrigger = {}, still = true)
                hasVault -> VaultUnlockScreen(
                    onUnlock = {},
                    busy = false,
                    failed = false,
                    onBiometric = if (bioEnabled) ({}) else null,
                    biometricLabel = bioButtonLabel,
                    biometricFace = biometricLayout() == BiometricLayout.Face,
                    onRecover = {},
                    still = true,
                )
                else -> OnboardingWelcomeScreen(onBegin = {}, onRestore = {}, onLanguage = {})
            }
        }
    }

    /**
     * Where the viewer lands once [target] is gone — P2-10.
     *
     * Computed from the ordering **before** the delete, because that is the list
     * the pager is currently showing. Removing item *i* shifts *i+1* into its
     * place, so the pager stays on the same page index and is already showing the
     * next item; nothing re-seeks it. The last item is the one exception —
     * `HorizontalPager` clamps to the new last page, which is the item before it —
     * and the only item is null, which closes the viewer.
     */
    private fun landingAfterDelete(ordered: List<VaultItem>, target: VaultItem): VaultItem? {
        val i = ordered.indexOfFirst { it.itemId == target.itemId }
        return when {
            i < 0 || ordered.size <= 1 -> null
            i == ordered.lastIndex -> ordered[i - 1]
            else -> ordered[i + 1]
        }
    }

    /**
     * P2-10. The index the delete committed becomes the activity's, which is a new
     * instance and therefore a recomposition — mutating the old one in place would
     * change nothing on screen.
     *
     * Synchronous, like the `MetadataDb.load` in [loadIndex]: the write is one
     * index blob and the deletes are unlinks. If an index ever grows large enough
     * for this to be felt, this call moves to a thread and assigns on the main one
     * — the delete itself is already safe to run anywhere.
     *
     * Deliberately **not** [loadIndex]: that re-reads the tamper digest
     * and starts a thumbnail backfill, none
     * of which a delete asked for.
     */
    private fun deleteItems(items: List<VaultItem>) =
        commitItems(items) { id, vmk -> VaultDeletion.delete(blobs, id, vmk, items) }

    /** P2-12. Back out of the trash, and back into the grid at its own date. */
    private fun restoreItems(items: List<VaultItem>) =
        commitItems(items) { id, vmk -> VaultDeletion.restore(blobs, id, vmk, items) }

    /** P2-12. The destruction, from the trash's own action. */
    private fun purgeItems(items: List<VaultItem>) =
        commitItems(items) { id, vmk -> VaultDeletion.purge(blobs, id, vmk, items) }

    /**
     * Runs one index-committing operation over [items] and adopts the result.
     *
     * P2-11: one call for the whole set, so fifty deletions are one index write
     * rather than fifty. The index the operation committed becomes the activity's,
     * which is a new instance and therefore a recomposition — mutating the old one
     * in place would change nothing on screen.
     *
     * Deliberately **not** [loadIndex]: that re-reads the tamper digest
     * and starts a thumbnail backfill, none
     * of which any of these asked for.
     */
    private fun commitItems(items: List<VaultItem>, op: (ByteArray, ByteArray) -> MetadataDb) {
        if (items.isEmpty()) return
        val vmk = VaultSession.vmk() ?: return
        val id = VaultSession.metadataBlobId() ?: return
        index = op(id, vmk)
    }

    /**
     * Runs an index write that touches folders and takes the result — `folder-system`.
     *
     * [commitItems]' shape for a write with no item list to be empty. The pair is the
     * index blob's ID and the VMK, in that order, which is what every `VaultFolders`
     * call wants and what a locked session cannot supply.
     */
    private fun commitIndex(op: (Pair<ByteArray, ByteArray>) -> MetadataDb) {
        val vmk = VaultSession.vmk() ?: return
        val id = VaultSession.metadataBlobId() ?: return
        index = op(id to vmk)
    }

    /**
     * Where [item] starts playing — the position this session holds for it, else its row's.
     * `viewer-swipe-navigation`, D8.
     */
    private fun resumeAt(item: VaultItem): Long? = synchronized(resumePending) {
        val id = item.itemId
        if (resumePending.containsKey(id)) resumePending[id] else item.resumeMs
    }

    /** A position from the viewer. Discarded once the vault has locked — D7. */
    private fun holdResume(item: VaultItem, positionMs: Long?) {
        if (!VaultSession.isUnlocked) return
        synchronized(resumePending) { resumePending[item.itemId] = positionMs }
    }

    /**
     * The viewer closed: this session's positions go to the vault in one commit, off the
     * main thread — D7. Nothing is written when none differs from its row.
     *
     * **The key is copied for the write and zeroed after it.** A lock landing mid-write
     * zeroes the session's own array in place, and an index sealed under a zeroed key is a
     * vault that no longer opens. The copy lives exactly as long as the write.
     */
    private fun flushResume() {
        val positions = synchronized(resumePending) { HashMap(resumePending) }
        if (positions.isEmpty()) return
        val vmk = VaultSession.vmk()?.copyOf() ?: return
        val id = VaultSession.metadataBlobId()?.copyOf() ?: run { vmk.fill(0); return }
        resumeWrites.launch {
            try {
                runCatching { VideoResume.record(blobs, id, vmk, positions) }
                    .onFailure { Log.w("VaultResume", "not written: ${it.javaClass.simpleName}") }
            } finally {
                vmk.fill(0)
                id.fill(0)
            }
        }
    }

    /**
     * [VaultLifecycle.beforeLock]: what is held goes to the vault while its key still exists.
     *
     * Any thread — auto-lock calls it from a background coroutine, which is why what it
     * writes is the viewer's once-a-second reports and never a read of the player. Drained
     * whether or not the write lands: after this there is no key to retry with.
     */
    private val writeResumeBeforeLock: () -> Unit = {
        // `destination-settings-and-slideshow` D5. A memo recording is **not** stopped: it is
        // sealed under its own key and never used this one. A memo stopped and held for
        // listen-back loses its key here and waits, sealed, for this vault's next unlock.
        VoiceMemo.lock()
        // A video recording is stopped by its screen leaving, which runs on the main
        // thread this may be blocking: the join below would wait for an end of stream
        // only the recorder's release can give.
        VaultCameraSession.active?.stopRecordingNow()
        recordingWrite?.let { runCatching { it.close() } }
        recordingThread?.join()
        // D5. A kit on screen is sealed under the keyring that is still open, so the words
        // outlive the lock only as ciphertext only that keyring can read.
        holdKit()
        val positions = synchronized(resumePending) {
            HashMap(resumePending).also { resumePending.clear() }
        }
        val vmk = VaultSession.vmk()
        val id = VaultSession.metadataBlobId()
        if (positions.isNotEmpty() && vmk != null && id != null) {
            VideoResume.record(blobs, id, vmk, positions)
        }
    }

    /**
     * Seals the kit on screen into [heldKit] — `screen-off-lock-all-screens`, D5. The
     * keyring's own kit or a vault's own, else the decoy's; a decoy's is named as the decoy's
     * when it comes back. Words, a newline, then the name: a word never holds a newline, and
     * the name is last so it may hold anything.
     *
     * The lists are dropped here and not left for the reset, which on a screen-off does not
     * run until the user returns.
     */
    private fun holdKit() {
        val words = recoveryKit ?: decoyKitWords ?: return
        val name = if (recoveryKit != null) recoveryKitVault else decoyName.trim()
        // `recovery-kit-three-views`. A later view's count rides between the words and the
        // name, empty at creation, so it comes back in the form it left in.
        // `vault-passphrase-manage`: a creation kit with fewer than three views rides as "c<left>".
        // `recovery-practice` D6: a decoy's kit rides as "d", so it prints as the list it is.
        val status = recoveryKitStatus?.takeIf { recoveryKit != null }?.left?.toString()
            ?: recoveryKitCreationLeft.takeIf { recoveryKit != null && it < VaultKeyring.KIT_VIEWS }?.let { "c$it" }
            ?: "d".takeIf { recoveryKit == null || recoveryKitDecoy }
            ?: ""
        val plain = (words.joinToString(" ") + "\n" + status + "\n" + name.orEmpty()).toByteArray(Charsets.UTF_8)
        try {
            VaultKeyringSession.sealHeld(plain)?.let { heldKit = it }
        } finally {
            plain.fill(0)
        }
        recoveryKit = null
        recoveryKitVault = null
        recoveryKitStatus = null
        recoveryKitCreationLeft = VaultKeyring.KIT_VIEWS
        decoyKitWords = null
    }

    /**
     * Offers [heldKit] to the keyring that just opened — D5. It opens only under the keyring
     * that sealed it; any other leaves it held, shows nothing and says nothing.
     */
    private fun restoreHeldKit() {
        val held = heldKit ?: return
        val plain = VaultKeyringSession.openHeld(held) ?: return
        try {
            val text = String(plain, Charsets.UTF_8)
            val cut = text.indexOf('\n')
            val cut2 = text.indexOf('\n', cut + 1)
            // Resuming spends nothing: the view was counted before the words were first drawn.
            val status = text.substring(cut + 1, cut2)
            recoveryKitStatus = status.toIntOrNull()?.let { KitStatus(it) }
            recoveryKitCreationLeft = status.removePrefix("c").takeIf { status.startsWith("c") }?.toIntOrNull()
                ?: VaultKeyring.KIT_VIEWS
            recoveryKitVault = text.substring(cut2 + 1).ifEmpty { null }
            recoveryKitDecoy = status == "d"
            recoveryKit = text.substring(0, cut).split(' ')
            heldKit = null
        } finally {
            plain.fill(0)
        }
    }

    /**
     * The frame on screen becomes [item]'s thumbnail — `video-frame-thumbnail`.
     *
     * On `Dispatchers.IO`: an exact-frame decode from an encrypted video and a rewrite of
     * the open pack are both too slow for the main thread. The index is reloaded after,
     * so the grid's tile and any folder whose cover this video is redraw from the new
     * reference, and the outcome goes back to the viewer, which says what happened.
     */
    private fun useFrame(item: VaultItem, positionMs: Long) {
        val vmk = VaultSession.vmk() ?: return
        val id = VaultSession.metadataBlobId() ?: return
        lifecycleScope.launch {
            val reloaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (VaultThumbnail.setFromFrame(blobs, id, vmk, item, positionMs)) {
                        MetadataDb.load(blobs, id, vmk)
                    } else {
                        null
                    }
                }.onFailure {
                    // The class only: a message can carry a blob ID.
                    Log.w("VaultThumbnail", "frame not set: ${it.javaClass.simpleName}")
                }.getOrNull()
            }
            // Locked while the write ran: a decrypted index must not come back into a
            // session that has closed.
            if (VaultSession.vmk() == null) return@launch
            reloaded?.let { index = it }
            frameOutcome = FrameOutcome(positionMs, reloaded != null)
        }
    }

    /**
     * Runs a folder write that can be refused, and reports the refusal —
     * `folder-system`.
     *
     * Returns the committed index, or **null when the write was refused**, which is
     * what tells the caller to leave its screen open. A refusal is a sentence in the
     * field the user is looking at ([folderProblem]), never a silent no-op: a name
     * that vanishes with nothing said reads as the app losing it.
     */
    private fun folderWrite(
        op: (Pair<ByteArray, ByteArray>) -> VaultFolders.Result,
    ): MetadataDb? {
        val vmk = VaultSession.vmk() ?: return null
        val id = VaultSession.metadataBlobId() ?: return null

        val result = op(id to vmk)
        val refusal = result.refusal
        if (refusal != null) {
            folderProblem = when (refusal) {
                VaultFolders.Refusal.Name ->
                    getString(R.string.folder_name_length, VaultFolders.MAX_NAME)
                VaultFolders.Refusal.Duplicate ->
                    getString(R.string.folder_name_duplicate)
                VaultFolders.Refusal.Cycle ->
                    getString(R.string.folder_cycle)
            }
            return null
        }
        folderProblem = null
        index = result.db ?: index
        return index
    }

    // --- back (P2-6) ----------------------------------------------------------

    /**
     * The screens back can close, in the order the composition's `when` picks them.
     *
     * There was an `onBackPressed` override here until P2-6. It was dead: predictive
     * back became mandatory at `targetSdk` 36 and the opt-out attribute was removed,
     * so at 37 the platform never called it and back in the viewer left the activity
     * instead of returning to the grid.
     *
     * The order below **is** the `when` order in [onCreate], and it has to stay that
     * way — dismissing a flag the `when` would not have reached closes a screen the
     * user cannot see. One handler reads this list rather than one per screen,
     * because per-screen handlers put the precedence in composition order, where it
     * is invisible and silently wrong whenever two flags are set.
     *
     * Not a navigation library: that is a new dependency, and a dependency is an I1
     * clearance run, to replace nine booleans that work.
     */
    private fun dismissTop() {
        when {
            // `recovery-practice` D5. Whichever creation kit the check stands in for, Back is
            // its words, with the acknowledgement kept and no view spent.
            kitChecking -> kitSeeWords()
            // The kit at creation is deliberately absent. Dismissing it by gesture before
            // it was written down would cost one of only three later views.
            // Back leaves the activity from both, which is what a gate should do.
            //
            // `recovery-kit-three-views`, D4. A later view is different: back is Done,
            // because the view was spent when the words appeared.
            recoveryKit != null && recoveryKitStatus != null -> closeKitView()
            // `recovery-practice` D3, D4. Back on the practice screen is Skip; on the new
            // passphrase it is the practice screen. Below a kit drawn over it.
            practiceShowing && recoveryKit == null -> practiceBack()
            // `new-vault-setup-flow`, in the `when`'s position: drawn above the kit branch.
            // Back is a step; on a first step it is Cancel; on the flow's own kit it does
            // nothing — the kit's rule, and the flow does not leave the app either.
            vaultFlow != null -> vaultFlowBack()
            // `first-run-onboarding`, in the `when`'s position. Back means **one step
            // back**, the same call the frame's Back button makes — one function, so
            // the two cannot drift apart again. It never ends the flow: every user
            // walks the eight steps.
            //
            // **Only where there is a previous step.** On the first drawn step after
            // the kit, [backDismissible] is false, so back reaches the activity's
            // default and the app leaves the foreground — what back does at the root
            // of any Android app. The flow is not finished; [resumeOnboarding] brings
            // it back at that step. Back from the passphrase and the kit falls through
            // to the branches below, as it always has: there is no earlier step to
            // return to from either.
            // `first-vault-naming` D5. While "Create vault" runs, back does nothing: the
            // vault is being written and cannot be left half made.
            onboardingStep == Onboarding.Step.Passphrase && busy -> Unit
            onboardingStep != null && onboardingStep!!.previousStep() != null ->
                advanceOnboarding(onboardingStep!!.previousStep())
            // Name and look is not the first screen: the welcome is before it, and back
            // returns there as the welcome's own back returns to the language screen.
            onboardingStep == Onboarding.Step.Name -> backToWelcome()
            // `cloud-mirror`, in the `when`'s position — above the archive import
            // for the same reason it is drawn above it. Nothing on it is a gate: a
            // user who opens Backup and thinks better of it goes back.
            backupScreen -> closeBackup()
            importingArchive -> closeArchiveImport()
            panicScreen -> closePanic()
            kitViewFor != null -> closeKitView()
            // P2-17, in the `when`'s position. Back leaves the camera; a recording
            // still running is stopped and discarded by the screen's own disposal,
            // which is also what a lock does to it.
            //
            // **`open == null`, matching the screen's own guard.** With the receipt's
            // item on screen the camera is behind the viewer, and back belongs to the
            // viewer: it falls through to the `open` branch below, which clears it and
            // brings the viewfinder back — `camera-capture-feedback`.
            cameraScreen && open == null -> closeCamera()
            // `voice-memo`. **Above the screen**, so back dismisses the confirmation
            // rather than stacking a second one behind it.
            confirmingMemoDiscard -> confirmingMemoDiscard = false
            // The gesture asks the same question the bar's back asks. Without this it
            // would throw away a recording the other back offers to keep — and the
            // screen's disposal would discard it silently on the way out.
            voiceMemoScreen -> requestLeaveVoiceMemo()
            // P4-4. The confirmation first, so back dismisses it rather than
            // stacking a second one behind it — `voice-memo`'s rule; then the
            // pairing screen, which is drawn over the list it returns to.
            removingContact != null -> removingContact = null
            confirmingNewIdentity -> confirmingNewIdentity = false
            remoteStep != null -> closeRemote()
            pairingScreen -> closePairing()
            addingContact -> addingContact = false
            contactsScreen -> closeContacts()
            // `debug-crash-log-screen`. Confirmation, then record, then the screen.
            confirmingCrashClear -> confirmingCrashClear = false
            crashRecord != null -> crashRecord = null
            crashLogScreen -> closeCrashLog()
            // `third-party-notices`. The licence, then the list, then the drawer's screen.
            licenceOpen != null -> licenceOpen = null
            licencesScreen -> licencesScreen = false
            // `decoy-flow-overhaul`. Back is a step, not an exit — until there is no
            // step behind it. The handover step is not backed out of either: the vault
            // exists by then, and the steps behind it describe work already done.
            // The 24 words are shown once and a gesture that dismissed them would
            // destroy the only copy — `RecoveryKitScreen`'s rule, which is why
            // `dismissTop` does not list that screen either.
            decoying && decoyShowingKit -> Unit
            decoying && decoySheet != null -> decoySheet = null
            decoying && DecoyFlow.canGoBack(decoyStep) ->
                decoyStep.previous?.let { decoyStep = it }
            decoying && decoyStep == DecoyFlow.Step.Handover -> Unit
            decoying -> closeDecoy()
            // `vault-passphrase-manage`. The passphrase screen first, back onto Edit vault.
            passphraseScreen != null -> closePassphraseScreen()
            editingVault != null -> closeEditVault()
            recoveringVault -> closeRecoverVault()
            promptingVault != null -> closeVaultPrompt()
            exporting -> closeExport()
            // `intruder-captures-at-home`, in the `when`'s position: the confirmation and the
            // passphrase sheet first, then the move one stage at a time, then the viewer, the
            // selection, and last the screen itself, back onto the vault list.
            captureDeleting != null -> captureDeleting = null
            captureMovePrompt != null -> {
                captureMovePrompt = null
                captureMoveFailed = false
            }
            captureMoveFolders != null -> closeMoveFolders()
            captureMoving != null -> closeCaptureMove()
            intruderScreen && captureOpen != null -> captureOpen = null
            intruderScreen && captureSelection.isNotEmpty() -> captureSelection = emptySet()
            intruderScreen -> closeIntruderScreen()
            // P2-24, in the `when`'s position — first, because the cover is the
            // first branch. Returns **false** from `dismissTop`'s perspective: back
            // on a cover leaves the app, as it would from the app the cover claims to
            // be, so nothing is dismissed and the system default runs.
            coverShowing -> Unit
            // P2-24. Abandoning setup enables nothing and changes no identity. From
            // the flow's last step the switch has already happened, and back is simply
            // how it is dismissed.
            settingUpIdentity != null -> closeIdentitySetup()
            // `launcher-identity`. Above the chooser it is drawn over, and it stores
            // nothing on the way out — including from a refusal state, where leaving is
            // the answer to a grant the user declined.
            committingIdentity != null -> closeCommitSheet()
            // P2-22. Back closes it having chosen nothing and having switched
            // nothing.
            identityScreen -> identityScreen = false
            // `biometric-unlock-screen`. Back changes nothing; the drawer's screen is shown.
            biometricScreen -> biometricScreen = false
            // `face-down-lock-settings`. Back changes nothing; the drawer's screen is shown.
            faceDownScreen -> faceDownScreen = false
            // `settings-separation`. Below every chooser, and above the grid's own
            // states because it is a screen drawn over whichever list is showing.
            purchaseScreen -> purchaseScreen = false
            recovering -> closeRecover()
            // P2-13. A screen over the grid, like the others above it: back closes
            // it and keeps the selection that opened it.
            destroyingVault != null -> closeDestroy()
            movingToFolder -> movingToFolder = false
            // `viewer-chrome-and-editor`, in the `when`'s position: both are screens
            // over the viewer, so back leaves them and lands on the item.
            movingItem != null -> movingItem = null
            editingImage != null -> closeEditor()
            // `folder-system`. The folder surfaces, each a screen over the grid:
            // back closes the one on top and leaves everything underneath alone.
            // The picker is above the folder's own surface because that surface is
            // what opens it, so back from a move lands back on the folder.
            movingFolder != null -> movingFolder = null
            naming != null -> naming = null
            covering != null -> covering = null
            deletingFolder != null -> deletingFolder = null
            folderSheet != null -> folderSheet = null
            // P2-14, in the `when`'s position: ahead of the viewer, so back leaves
            // the rename and returns to the item rather than closing both.
            // P2-21, in the `when`'s position — above the viewer, as the
            // composition draws it. The editor asks about unsaved text itself, so
            // back here only closes what the editor already agreed to close.
            noteScreen -> closeNote()
            renaming != null -> renaming = null
            open != null -> open = null
            // `vault-keyring`, and it is deliberately **not** last: back from a vault
            // with nothing else open leaves it for the list, which is where the user
            // came from. The branches below still win — a selection, a search and the
            // trash are all states of the vault and are ended before it is.
            // P2-11. After every screen above, because those are drawn over
            // whichever list is showing and a selection lives *on* it. Ending one
            // acts on nothing — that is the guarantee, not an implementation
            // detail.
            selection.isNotEmpty() -> selection = emptySet()
            // P2-15, in the trash's and the album filter's position and for their
            // reason: the search is a state of the grid a selection sits on, so
            // back ends the selection first and closes the search on the next
            // press. It matches nothing differently either way.
            query.isNotEmpty() -> query = ""
            // P2-12, and the one place this order is not the `when`'s order: the
            // trash *is* the list a selection sits on, so back ends the selection
            // first and leaves the screen on the next press. Restores and destroys
            // nothing either way.
            trash -> closeTrash()
            // `folder-system`, in the same position and for the same reason: the
            // level is a state of the grid the selection sits on, so back ends the
            // selection first and climbs on the next press. It moves nothing.
            //
            // **One level per press.** Nesting is the reason: a press that returned
            // to the home screen from four levels down would be back meaning
            // something different inside a folder from everywhere else in the app.
            folderPath.isNotEmpty() -> leaveFolder()
            // `vault-destinations`, second from last and for the reason every branch
            // above it is where it is: the destination is the state the level, the query,
            // the trash and the selection all sit *inside*, so back unwinds those first
            // and moves between destinations only when there is nothing left in this one.
            //
            // **Back returns to Media before it leaves the vault**, which is Android's
            // documented behaviour for a bottom bar — back goes to the start destination.
            // Without it, a user who tapped Memos and then pressed back would leave the
            // vault from a screen they never opened it on.
            //
            // `goToDestination` and not an assignment: it is what the bar calls, so the
            // saved place and the cleared selection cannot differ between the two ways in.
            destination != VaultDestination.Media -> goToDestination(VaultDestination.Media)
            // `vault-keyring`, and last: everything above is a state *of* the open
            // vault, so back ends those first and leaves the vault itself only when
            // there is nothing left inside it to leave. The list is where the user
            // came from, so this is back's honest meaning rather than a second lock.
            unlocked -> leaveVault()
        }
    }

    /** Whether [dismissTop] has anything to close — the `enabled` for the one handler. */
    private val backDismissible: Boolean
        get() = vaultFlow != null || (recoveryKit == null || recoveryKitStatus != null) && (
                recoveryKitStatus != null || kitViewFor != null ||
                destroyingVault != null ||
            // `first-run-onboarding`. Not on the first drawn step after the kit:
            // there is no step to go back to, so back must fall through to the
            // activity and leave the app. The passphrase and the kit are unchanged.
            onboardingStep.let { it != null && (it <= Onboarding.Step.Kit || it.previousStep() != null) } ||
            backupScreen ||
            importingArchive || panicScreen || cameraScreen || voiceMemoScreen ||
                decoying ||
                contactsScreen || pairingScreen || removingContact != null ||
                addingContact || remoteStep != null ||
                confirmingNewIdentity || crashLogScreen || licencesScreen ||
                // `face-down-lock-settings`. Back closes it onto the list it was opened over.
                faceDownScreen ||
                editingVault != null || recoveringVault || promptingVault != null ||
                exporting || recovering || open != null ||
                intruderScreen || captureMoving != null || captureDeleting != null ||
                noteScreen || purchaseScreen ||
                selection.isNotEmpty() || trash || movingToFolder ||
                movingItem != null || editingImage != null ||
                unlocked ||
                folderPath.isNotEmpty() || movingFolder != null || naming != null ||
                destination != VaultDestination.Media ||
                covering != null || deletingFolder != null ||
                folderSheet != null || renaming != null || query.isNotEmpty()
            )

    /**
     * Climbs one level — `folder-system`.
     *
     * Named once, because back and the chrome's own control run it: a leave that
     * cleared the query in one path and forgot it in the other is the drift the
     * "each screen's cancel action, named once" rule below exists to prevent.
     */
    private fun leaveFolder() {
        selection = emptySet()
        // The level above is a different scope; a query asked about this folder is
        // not a question about the one that holds it.
        query = ""
        folderPath = folderPath.dropLast(1)
    }

    /**
     * Moves to [to], putting down what this destination was holding and picking up what
     * that one was — `vault-destinations`.
     *
     * Named once, for [leaveFolder]'s reason: the bar and back both arrive here, and a
     * switch that saved the level in one path and forgot it in the other is exactly the
     * drift this file's "each screen's cancel action, named once" rule exists to prevent.
     *
     * **The selection does not travel.** It sits *on* a list, and the list is changing —
     * the same rule [leaveFolder] and [openTrash] already apply. Nothing is acted on.
     */
    private fun goToDestination(to: VaultDestination) {
        if (to == destination) return
        // `file-import` D4. The message existed to say where an import went; the user
        // moving is the point at which it has been read or does not matter. It clears on
        // any move and not only a move to the destination it named — a line about Media
        // is no more use standing on Notes than it was on Files.
        // Only the line this field put there: an import failure showing in the same
        // place is about something the user has not dealt with, and moving is not
        // dealing with it.
        if (notice != null && notice == importLanded) notice = null
        importLanded = null
        // `remove-originals-on-import`. Spent the same way, by moving.
        originalsRemoved = false
        places[destination] = Place(folderPath, query)
        selection = emptySet()
        destination = to
        val place = places[to]
        folderPath = place?.folderPath ?: emptyList()
        query = place?.query ?: ""
    }

    // Each screen's cancel action, named once. Back and the screen's own control run
    // the same code, so a flag one clears and the other forgets cannot exist.

    /** P2-12. Leaves the trash, taking any selection made there with it. */
    private fun closeTrash() {
        trash = false
        selection = emptySet()
    }

    /** P2-13. Opens the trash from wherever the user was, clearing the filter. */
    private fun openTrash() {
        selection = emptySet()
        // P2-15. One list at a time takes the query with it: search is not offered
        // over the trash, and a field left standing above it would be offering it.
        query = ""
        // One list at a time: a trash showing one folder would be a third list with
        // its own rules, and the outline asked for neither.
        folderPath = emptyList()
        trash = true
    }

    private fun closeArchiveImport() {
        importingArchive = false
        archiveFile = null
        archiveFileName = null
        importKind = null
        importFolder = false
        importDone = null
        resetImportOutcome()
    }

    private fun closePanic() {
        panicScreen = false
        panicProblem = null
    }

    /** `recovery-kit-three-views`. From a drawer row: -1 the list's kit, else a protected vault's. */
    private fun openKitView(position: Int) {
        kitViewWrong = false
        kitViewFailed = false
        kitViewBusy = false
        kitViewFor = position
    }

    /** Leaves the view screen and, in the review form, the words. */
    private fun closeKitView() {
        kitViewFor = null
        kitViewBusy = false
        kitViewWrong = false
        kitViewFailed = false
        if (recoveryKitStatus != null) {
            recoveryKit = null
            recoveryKitVault = null
            recoveryKitStatus = null
        }
    }

    /** Views left of the kit [position] names — the list's for -1, else that vault's own. */
    private fun kitViewsLeft(position: Int): Int =
        if (position < 0) VaultKeyringSession.listKitViewsLeft()
        else vaultEntries.firstOrNull { it.position == position }
            ?.let { VaultKeyring.KIT_VIEWS - it.kitViewsUsed } ?: 0

    /**
     * D4's sequence, off the main thread: the passphrase is checked (one derivation), the view
     * is spent and saved, and only then are the words encoded. A refusal returns to the view
     * screen with D4's message and spends nothing.
     */
    private fun showKit(passphrase: ByteArray) {
        val position = kitViewFor ?: return passphrase.fill(0)
        kitViewBusy = true
        kitViewWrong = false
        kitViewFailed = false
        thread {
            val view = runCatching {
                if (position < 0) lifecycleOps.viewListKit(mnemonic, passphrase)
                else lifecycleOps.viewVaultKit(mnemonic, position, passphrase)
            }.getOrElse {
                Log.w("VaultKeyring", "a recovery kit view failed", it)
                passphrase.fill(0)
                VaultLifecycle.KitView()
            }
            runOnUiThread {
                kitViewBusy = false
                vaultEntries = VaultKeyringSession.vaults()
                // A lock landed while this ran: the words must not be drawn over it.
                if (kitViewFor != position || !VaultKeyringSession.isOpen) return@runOnUiThread
                val words = view.words
                if (words == null) {
                    kitViewWrong = view.wrongPassphrase
                    kitViewFailed = !view.wrongPassphrase
                    return@runOnUiThread
                }
                recoveryKitVault = if (position < 0) null
                else vaultEntries.firstOrNull { it.position == position }?.name
                recoveryKitStatus = KitStatus(view.left)
                recoveryKit = words
                kitViewsSpent++
            }
        }
    }

    private fun closeCapture() {
        captureSheet = false
        captureProblem = null
    }

    /**
     * Leaves the decoy and reopens the vault the flow was started from.
     *
     * **This is the other half of "a decoy is a parallel install".** Entering its
     * passphrase at the lock screen reaches the decoy and nothing else, and the same
     * is true of the session `create` leaves behind — so finishing the flow used to
     * drop the user into the decoy with no way across and no statement that it had
     * happened. During first run that was worse than disorienting: the steps after
     * this one enrol things *against the open vault*, so the backup would have
     * mirrored the decoy and the panic credential would have been set on it.
     *
     * Going back needs the master passphrase, which the authorisation sheet already
     * collected and which [decoyReturnPass] holds for exactly this. The decoy is left
     * through the ordinary lock first, so "at most one vault's key in memory" holds
     * across the switch as it does everywhere else.
     */
    private fun returnFromDecoy() {
        // Taken before `closeDecoy`, which zeroes what it finds.
        val pass = decoyReturnPass
        val position = decoyReturnPosition
        decoyReturnPass = null
        val fromOnboarding = onboardingStep == Onboarding.Step.Decoy
        closeDecoy()

        if (pass == null) {
            // Nothing to get back with. The lock screen is the honest place to land:
            // leaving them in the decoy is the bug this function exists to fix.
            VaultLifecycle.lockNow()
            unlocked = false
            return
        }

        busy = true
        thread {
            // The round trip itself lives in `VaultLifecycle.reopen`, so a test can
            // call the same code this does. It leaves the decoy through the ordinary
            // lock and zeroes `pass`.
            val back = lifecycleOps.reopen(pass, position)
            if (!back.keyringOpen) {
                runOnUiThread {
                    busy = false
                    unlocked = false
                    vaultEntries = emptyList()
                }
                return@thread
            }

            // **The one place this can be written.** The bit belongs to the keyring the
            // user came back to, and until this line ran the session held the decoy's —
            // where recording it would have put the answer in front of exactly the
            // person the decoy exists to mislead. Idempotent, and a failure to re-seal
            // costs the row's state and nothing else, so it is logged rather than shown:
            // the vault is open and the flow is over either way.
            if (!VaultKeyringSession.markDecoyBuilt(blobs)) {
                Log.w("VaultDecoy", "the decoy was built and the keyring could not record it")
            }
            runOnUiThread { decoySetUp = VaultKeyringSession.hasDecoy() }

            val entries = VaultKeyringSession.vaults()
            val opened = back.vault
            if (opened == null) {
                // The keyring is open and no vault is, which is the vault list — the
                // right place when the flow was started without one open.
                runOnUiThread {
                    busy = false
                    // The list is the keyring open with no vault: `unlocked = true` drew the
                    // grid over the decoy's index, which the lock had not cleared from screen.
                    keyringOpen = true
                    unlocked = false
                    index = MetadataDb.empty()
                    open = null
                    vaultEntries = entries
                    if (fromOnboarding) completeOnboardingStep()
                }
                return@thread
            }

            finishOpening(opened)
            runOnUiThread {
                vaultEntries = entries
                // The step's work is done, so the flow moves on rather than redrawing
                // the step that raised it.
                if (fromOnboarding) completeOnboardingStep()
            }
        }
    }

    /**
     * Leaves the flow and takes everything it held with it.
     *
     * **The zeroing lives here rather than in a `DisposableEffect` on a step.** The
     * old screen zeroed its preserve list when its composition left, which was
     * correct only while the whole flow was one composition. Across five steps and
     * two sheets there are many ways for a composition to end without the flow
     * ending, and exactly one way for the flow to end — this function, which every
     * exit path calls.
     */
    private fun closeDecoy() {
        decoying = false
        decoyProblem = null
        decoySummary = null
        decoyPicked = emptyList()
        decoyPlan = emptyList()
        decoyStep = DecoyFlow.Step.Intro
        decoySheet = null
        decoyName = ""
        decoyPassBytes?.fill(0)
        decoyPassBytes = null
        decoyKitWords = null
        decoyShowingKit = false
        decoyReturnPass?.fill(0)
        decoyReturnPass = null
        decoyReturnPosition = -1
        decoyAuthFailed = false
        decoyPreserve.forEach { it.fill(0) }
        decoyPreserve.clear()
    }

    /** Raises the destroy dialog for [entry] at its gate — `protected-vault-destroy-gate` D3. */
    private fun openDestroy(entry: VaultKeyring.Entry) {
        destroyProblem = null
        closeDestroy()
        destroyingVault = entry
    }

    /**
     * Closes the destroy dialog and drops what its gate proved — D4. Every dismissal, every
     * outcome once it has its copy, back, and every lock run this.
     */
    private fun closeDestroy() {
        destroyGeneration++
        destroyKey?.fill(0)
        destroyKey = null
        destroyingVault = null
        destroyStep = DestroyStep.Gate
        destroyBackup = null
        destroyChecking = false
        destroyWrong = false
    }

    /**
     * The gate — D1. One derivation off the main thread for the passphrase that opens [target];
     * on success the backup line is read with the key it unsealed, and the choice is shown. A
     * result landing after the dialog closed, or after it moved to another vault, is zeroed and
     * dropped. A wrong passphrase takes no intruder capture, as Unvault Everything's does not.
     */
    private fun submitDestroyGate(target: VaultKeyring.Entry, passphrase: ByteArray) {
        val generation = destroyGeneration
        destroyChecking = true
        destroyWrong = false
        thread {
            val key = lifecycleOps.destroyKey(target.position, passphrase)
            val backup = key?.let { VaultKeyringSession.destinationAt(blobs, target.position, it) }
            runOnUiThread {
                if (generation != destroyGeneration) {
                    key?.fill(0)
                    return@runOnUiThread
                }
                destroyChecking = false
                if (key == null) {
                    destroyWrong = true
                    return@runOnUiThread
                }
                destroyKey = key
                destroyBackup = backup
                destroyStep = DestroyStep.Choice
            }
        }
    }

    /**
     * Destroys the vault at [position], and its backup with it — `delete-vault` D3,
     * `protected-vault-destroy-gate` D2.
     *
     * **The folder goes first, and it goes best effort.** A mirror destination holds
     * that vault's blobs *and the keyring blob*, so the folder plus the 24 words
     * reconstitutes the vault: leaving it standing would destroy the copy on the device
     * and leave a complete, openable one at a provider, having told the user the vault
     * was destroyed. A provider that cannot be reached does not keep the user in a vault
     * they have chosen to end, so the failure is logged and the removal proceeds.
     *
     * [key] is the vault's own, proven at the gate, and it is zeroed here once the removal has
     * run. [tree] is the destination the gate read; null is read again with [key] before the
     * entry goes, because after it there is no key left to read it with.
     */
    private fun destroyVault(position: Int, name: String, key: ByteArray, tree: String?) {
        busy = true
        destroyProblem = null
        thread {
            val ok = try {
                (tree ?: VaultKeyringSession.destinationAt(blobs, position, key))?.let { stored ->
                    val uri = android.net.Uri.parse(stored)
                    runCatching { CloudMirror.destroy(this, uri) }
                        .onFailure { Log.w("VaultDestroy", "a backup could not be reached", it) }
                    CloudMirror.releaseGrant(this, uri)
                }
                VaultKeyringSession.removeVault(blobs, position, key)
            } finally {
                key.fill(0)
            }
            runOnUiThread {
                busy = false
                unlocked = VaultSession.isUnlocked
                vaultEntries = VaultKeyringSession.vaults()
                if (!ok) {
                    // D2's second failure, and the recoverable one: whatever the vault
                    // still holds is intact and it is on the list to try again.
                    destroyProblem = getString(R.string.vault_destroy_failed, name)
                    return@runOnUiThread
                }
                vaultListProblem = null
                index = MetadataDb.empty()
            }
        }
    }

    /**
     * The return outcome — `delete-vault` D2, `protected-vault-destroy-gate` D5. The export runs
     * first, whole, on the vault being destroyed and no other.
     *
     * From the list that vault is not open, so it is opened with [key] — the gate's — through
     * the same [finishOpening] a tap runs, and the export screen shows once it has. A vault that
     * does not open reports as a tap would and arms nothing. [key] is zeroed here.
     *
     * Nothing is destroyed here. [watchExports] is what finishes it, and only once the
     * index is empty: `Unvaulter` removes each item as it verifies it, so an index with
     * anything left in it is an index whose items are still the only copy.
     */
    private fun returnThenDestroy(position: Int, key: ByteArray) {
        destroyProblem = null
        exportSelection = null
        val arm = { openedHere: Boolean ->
            returnOpenedVault = openedHere
            returnStarted = false
            destroyAfterExport = position
            exportAuthorised = true
            exporting = true
        }
        if (position == VaultKeyringSession.openVaultPosition()) {
            key.fill(0)
            arm(false)
            return
        }
        busy = true
        thread {
            val opened = try {
                VaultKeyringSession.openVaultWithKey(position, key)
            } finally {
                key.fill(0)
            }
            if (opened == null) {
                runOnUiThread {
                    busy = false
                    vaultListProblem = getString(R.string.vault_not_opened)
                }
                return@thread
            }
            finishOpening(opened, onOpened = { arm(true) })
        }
    }

    /** P4-3. The picker, suppressing auto-lock across the round trip as every picker does. */
    private fun pickShareFile() {
        AutoLock.awaitingResult(true)
        // Containers are opaque bytes with a neutral `.bin` name, sent as
        // `application/octet-stream`; §12 v2 gives the picker nothing to filter on.
        openShareFile.launch(arrayOf("application/octet-stream", "*/*"))
    }

    /**
     * P4-3. Stages the picked file, probes it with this install's identity, and
     * shows whichever question the probe leaves — `share-format-v2` D6.
     */
    private fun beginShareOpen(uri: android.net.Uri) {
        // `one-time-purchase`. Opening is never gated; the price is only for the keep
        // refusal's button, should the share turn out to be keepable.
        if (!Entitlement.importAllowed(this)) Billing.loadPrice()
        purchaseProblem = null
        shareWords = List(ShareCode.WORD_COUNT) { "" }
        shareOpenFailed = false
        shareRefusal = null
        shareOpened = null
        shareOpenBusy = false
        shareProbing = false
        shareSealedHere = false
        shareIdentityUnreadable = false
        val staged = runCatching { ShareOpen.stage(this, uri) }.getOrNull()
        shareStaged = staged
        openingShare = true
        if (staged == null) {
            shareOpenFailed = true
            return
        }
        // Decided **before** the file is read, so it is the same for every file —
        // not an oracle. `seed` never creates an identity: no file means nothing
        // can have been sealed here, and that is the code entry with no extra line.
        val seed = Identity.seed(this)
        if (seed == null) {
            shareIdentityUnreadable = Identity.file(this).isFile
            return
        }
        // Off the main thread: the probe decapsulates and authenticates up to one
        // 64 KiB chunk. Any failure to read is a file the probe did not open.
        shareProbing = true
        thread {
            val probe = runCatching { ShareOpen.probe(staged, seed) }
                .getOrDefault(ShareContainer.Probe.NOT_HERE)
            runOnUiThread {
                // The flow may have been left, or another file picked, meanwhile.
                if (shareStaged !== staged) return@runOnUiThread
                shareProbing = false
                when (probe) {
                    ShareContainer.Probe.SEALED_HERE -> shareSealedHere = true
                    ShareContainer.Probe.NEWER_VERSION -> shareRefusal = ShareOpen.Refusal.NewerVersion
                    ShareContainer.Probe.NOT_HERE -> Unit
                }
            }
        }
    }

    /**
     * P4-3. Derives the key and opens the container.
     *
     * The payload is sealed into the vault's blob store as it decrypts — I3, and
     * the reason this flow needs a vault at all. A refusal is not a failure and
     * carries its own words; everything cryptographic is one flag.
     */
    private fun openShare() {
        val staged = shareStaged ?: return
        val vmk = VaultSession.vmk() ?: return
        val contactMode = shareSealedHere
        // The code path normalises four words; the contact path takes this
        // install's identity seed and asks the user for nothing.
        val code = if (contactMode) {
            emptyList()
        } else {
            ShareCode(Mnemonic.fromResources(this)).normalise(shareWords.joinToString(" "))
                ?: return
        }
        val seed = if (contactMode) Identity.seed(this) ?: return else null

        shareOpenBusy = true
        shareOpenFailed = false
        thread {
            val outcome = runCatching {
                if (seed != null) {
                    ShareOpen.openWithIdentity(this, staged, seed, blobs, vmk)
                } else {
                    ShareOpen.open(this, staged, code, blobs, vmk)
                }
            }
            runOnUiThread {
                shareOpenBusy = false
                outcome.onSuccess {
                    when (it) {
                        is ShareOpen.Outcome.Opened -> shareOpened = it
                        is ShareOpen.Outcome.Refused -> shareRefusal = it.refusal
                    }
                }.onFailure { e ->
                    // §12's one named refusal arrives as an outcome above; here
                    // every cryptographic outcome is one flag, because saying more
                    // would say which part was wrong.
                    Log.i("VaultShare", "a share did not open: ${e.javaClass.simpleName}")
                    shareOpenFailed = true
                }
            }
        }
    }

    /** P4-3. Shows the received item on the ordinary viewer, without keeping it. */
    private fun viewShare() {
        val opened = shareOpened ?: return
        sharePreview = VaultItem(
            blobIds = listOf(opened.blobId),
            plaintextLength = opened.plaintextLength,
            thumb = null,
            filename = opened.filename,
            album = null,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = emptyList(),
        )
    }

    /**
     * P4-3. Adds the index row that turns the opened share into an ordinary item.
     *
     * The `canKeep` guard sits with the other early returns rather than relying on
     * `ShareOpen.keep`'s own check — `share-keep-gate`. The call below is wrapped in
     * `runCatching`, which would swallow that check and then run the success path:
     * `shareOpened` cleared, the flow swept, the index reloaded. The user would be
     * shown a kept share that was never kept, and the blob would be orphaned. The
     * engine's check stays as the assertion; this is what keeps it from firing.
     */
    private fun keepShare() {
        val opened = shareOpened ?: return
        if (!opened.canKeep) return
        // `one-time-purchase`. A kept share is an add, gated with the rest; the screen
        // does not offer it, and this is what keeps any other route from reaching keep.
        if (!Entitlement.importAllowed(this)) return
        val vmk = VaultSession.vmk() ?: return
        val indexId = VaultSession.metadataBlobId() ?: return
        thread {
            runCatching { ShareOpen.keep(blobs, indexId, vmk, opened) }
                // `one-time-purchase` D4. A kept share is an add.
                .onSuccess { Entitlement.startTrialIfNeeded(this) }
            runOnUiThread {
                shareOpened = null
                openingShare = false
                shareStaged = null
                runCatching { ShareOpen.sweep(this) }
                index = MetadataDb.load(blobs, indexId, vmk)
            }
        }
    }

    /**
     * P4-3. Leaves the receive flow.
     *
     * An opened-but-unkept share is a blob with no index row, so leaving deletes
     * it — that is what "if you leave without keeping it, nothing is saved" means,
     * and it is a deletion rather than a sweep because nothing else knows the blob
     * exists.
     */
    private fun endShareOpen() {
        shareOpened?.let { opened ->
            runCatching { ShareOpen.discard(blobs, opened) }
        }
        openingShare = false
        shareStaged = null
        shareWords = List(ShareCode.WORD_COUNT) { "" }
        shareOpenBusy = false
        shareOpenFailed = false
        shareRefusal = null
        shareOpened = null
        shareProbing = false
        shareSealedHere = false
        shareIdentityUnreadable = false
        runCatching { ShareOpen.sweep(this) }
    }

    /**
     * P4-2. Opens the send flow on one item. Seals nothing yet.
     *
     * `one-time-purchase` D5. **Opened after the trial too**, in its refused state —
     * the screen reads [addAllowed] as it draws, so a purchase made from it moves the
     * same item on to compose. The price is asked for here so the refusal's button
     * can name it.
     */
    private fun beginShare(item: VaultItem) {
        if (!Entitlement.importAllowed(this)) Billing.loadPrice()
        purchaseProblem = null
        shareCode = null
        shareSealedTo = null
        shareContacts = Identity.contacts(this)
        // A contact when there is one — chosen fresh on every open, never stored.
        shareSelections = ShareSelections.initial(shareContacts)
        shareFile = null
        shareSaved = null
        shareProgress = null
        shareFailed = null
        shareBusy = false
        sharingItem = item
    }

    /**
     * Seals the item under a fresh code — P4-2.
     *
     * The code is generated here, held in state for the one screen that shows it,
     * and dropped by [endShare]. It is never written anywhere: §7.19's "shown once
     * and is not saved" is this function and [endShare] together, and there is no
     * third place that could keep a copy.
     */
    private fun startShare(
        policy: ShareContainer.Policy,
        recipient: Identity.Contact?,
        /** `exif-strip-choice`. The share screen's own question, not the import preference's. */
        stripMetadata: Boolean,
    ) {
        val item = sharingItem ?: return
        // `one-time-purchase`. The screen does not offer compose after the trial; this
        // is the engine-side half, so nothing is sealed whatever reaches here.
        if (!Entitlement.importAllowed(this)) return
        val vmk = VaultSession.vmk() ?: return
        // **No code is generated on the contact path** — P4-5. The recipient's own
        // keypair is the key, which is the whole of what pairing bought.
        val words = if (recipient == null) {
            ShareCode(Mnemonic.fromResources(this)).generate()
        } else {
            null
        }

        shareBusy = true
        shareFailed = null
        shareProgress = 0L to item.plaintextLength
        thread {
            val sealed = runCatching {
                if (recipient == null) {
                    ShareSend.seal(
                        this, blobs, item, vmk, policy, words!!, stripMetadata,
                    ) { done, total ->
                        runOnUiThread { shareProgress = done to total }
                    }
                } else {
                    ShareSend.sealToContact(
                        this, blobs, item, vmk, policy, recipient.publicKey, stripMetadata,
                    ) { done, total ->
                        runOnUiThread { shareProgress = done to total }
                    }
                }
            }
            runOnUiThread {
                shareBusy = false
                shareProgress = null
                sealed.onSuccess {
                    shareFile = it
                    // One or the other, never both: the send state is chosen by
                    // which of these is set.
                    if (recipient == null) shareCode = words else shareSealedTo = recipient.name
                }.onFailure { e ->
                    Log.w("VaultShare", "the share could not be sealed", e)
                    // A cause and a way forward, and never "corrupt" — §6.6a.
                    // Nothing is wrong with the item; the write did not finish.
                    shareFailed = getString(R.string.share_create_failed)
                }
            }
        }
    }

    /**
     * Hands the container to whatever the user picks — P4-2.
     *
     * `ACTION_SEND` with a read grant on a content URI from the app's own
     * provider. No `queries` element and no package is named: the chooser is the
     * platform's, and this app never learns which messenger was chosen.
     */
    private fun sendShare() {
        val file = shareFile ?: return
        handOff(file)
    }

    /**
     * The one door out — a sealed file from the share directory, to the platform's chooser.
     * A share container and a pairing file both leave through here and nowhere else.
     */
    private fun handOff(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.shares", file
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(send, null))
    }

    /**
     * Opens the system's save dialog for the sealed container — the send route that
     * keeps this app's identity from the app it is then sent with (`FORMAT.md` §10
     * item 51). Auto-lock is suppressed across the round trip, as every picker does.
     */
    private fun saveShare() {
        val file = shareFile ?: return
        shareSaved = null
        AutoLock.awaitingResult(true)
        saveShareFile.launch(file.name)
    }

    /** P4-2. The clipboard entry is marked sensitive so the system does not preview it. */
    private fun copyShareCode() {
        val words = shareCode ?: return
        val clip = android.content.ClipData.newPlainText("", words.joinToString(" "))
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
    }

    /**
     * Leaves the send flow and takes the container with it — P4-2.
     *
     * The sweep here is the near half of §7.19's pair; [afterUnlock]'s is the far
     * one. Neither waits for a completion signal, because `ACTION_SEND` does not
     * report one.
     */
    private fun endShare() {
        sharingItem = null
        shareCode = null
        shareFile = null
        shareSaved = null
        shareProgress = null
        shareFailed = null
        shareBusy = false
        selection = emptySet()
        runCatching { ShareSend.sweep(this) }
    }

    /**
     * New vault — `new-vault-setup-flow`. Both "New vault" controls land here, at Name and look,
     * with nothing carried from an earlier run and the appearance of the position the vault will
     * land on: the keyring's first free.
     */
    private fun openNewVaultFlow() {
        purchaseScreen = false
        vaultListProblem = null
        closeVaultFlow()
        flowDefaultAppearance = VaultAppearance.defaultFor(
            (0 until VaultKeyring.CAPACITY).firstOrNull { p -> vaultEntries.none { it.position == p } } ?: 0
        )
        flowAppearance = flowDefaultAppearance
        vaultFlow = VaultFlow.New
    }

    /** Add a passphrase — from Edit vault's row, over the vault being edited. Its draft is untouched. */
    private fun openAddFlow() {
        val entry = editingVault ?: return
        closeVaultFlow()
        flowPass.vaultName = entry.name
        flowStep = NewVaultStep.Passphrase
        vaultFlow = VaultFlow.Add
    }

    /** D1. The steps the flow shows now — New vault's follow How it opens' selection. */
    private fun flowSteps(): List<NewVaultStep> =
        if (vaultFlow == VaultFlow.Add) NewVaultStep.ADD else NewVaultStep.shown(flowOwn)

    /**
     * Back — the frame's control and the gesture, one function. The dialog first; nothing while a
     * proof or a commit runs; nothing on the kit; Cancel on a first step; else the previous step,
     * with everything entered there still entered.
     */
    private fun vaultFlowBack() {
        if (setupBusy) return
        if (flowDiscarding) {
            flowDiscarding = false
            return
        }
        val steps = flowSteps()
        val at = steps.indexOf(flowStep)
        when {
            flowStep == NewVaultStep.Kit -> Unit
            at <= 0 -> cancelVaultFlow()
            else -> {
                flowProblem = null
                flowStep = steps[at - 1]
            }
        }
    }

    /** D6. Closes at once while nothing has been entered; otherwise asks. */
    private fun cancelVaultFlow() {
        if (setupBusy) return
        val entered = flowName.isNotEmpty() || flowAppearance != flowDefaultAppearance || flowOwn ||
            flowPass.new.isNotEmpty() || flowPass.again.isNotEmpty() || flowListTyped.isNotEmpty() ||
            setupCaptureModes.isNotEmpty()
        if (entered) flowDiscarding = true else closeVaultFlow()
    }

    /**
     * The one way a vault flow ends — Discard, its last step, and a lock. Every held passphrase
     * array is zeroed and the text is dropped; the draft modes go with them, unenrolled. A
     * permission granted on the capture step stays granted: it is Android's grant to the app (D5).
     */
    private fun closeVaultFlow() {
        vaultFlow = null
        flowStep = NewVaultStep.Name
        flowName = ""
        flowAppearance = flowDefaultAppearance
        flowOwn = false
        flowPass.clear()
        flowPass.vaultName = ""
        flowListTyped = ""
        flowListBytes?.fill(0)
        flowListBytes = null
        flowListWrong = false
        setupCaptureModes = emptySet()
        setupBusy = false
        flowNameError = null
        flowProblem = null
        flowDiscarding = false
        captureProblem = null
    }

    /**
     * Name and look's Continue — D4. The two checks that would otherwise only fail at the end, run
     * here so nobody walks four steps to be refused on the fifth. They run again at the commit.
     */
    /**
     * The first vault's Name and look Continue, in first run and on the no-vault setup
     * screen — `first-vault-naming` D4. New vault's checks that a first vault can meet; with
     * no keyring, "list full" cannot happen. True when the name is accepted.
     */
    private fun firstNameContinue(): Boolean {
        firstNameError = when {
            firstName.isBlank() -> getString(R.string.vault_name_empty)
            !mayAddToVault() -> getString(Entitlement.BLOCKED)
            else -> null
        }
        return firstNameError == null
    }

    private fun nameStepContinue() {
        flowNameError = when {
            flowName.isBlank() -> getString(R.string.vault_name_empty)
            !mayAddToVault() -> getString(Entitlement.BLOCKED)
            VaultKeyringSession.isFull() -> getString(R.string.vault_list_full)
            else -> null
        }
        if (flowNameError == null) flowStep = NewVaultStep.Opens
    }

    /**
     * Add a passphrase's step 1 Continue, after its key-free checks — D7. The list's passphrase is
     * proven on a copy, off the main thread; the bytes are kept for the commit, which proves again.
     */
    private fun proveAddList() {
        val bytes = flowListTyped.toByteArray(Charsets.UTF_8)
        flowListWrong = false
        setupBusy = true
        thread {
            val ok = runCatching { lifecycleOps.confirmsListPassphrase(bytes.copyOf()) }.getOrDefault(false)
            runOnUiThread {
                // A lock may have closed the flow under the derivation; then nothing is kept.
                if (vaultFlow != VaultFlow.Add || flowStep != NewVaultStep.Passphrase) {
                    bytes.fill(0)
                    return@runOnUiThread
                }
                setupBusy = false
                if (ok) {
                    flowListBytes?.fill(0)
                    flowListBytes = bytes
                    flowStep = NewVaultStep.Capture
                } else {
                    bytes.fill(0)
                    flowListWrong = true
                }
            }
        }
    }

    /** New vault's commit: How it opens' Create vault, or the capture step's. */
    private fun commitNewVault() {
        val own = flowOwn
        createVaultOnList(
            flowName.trim(),
            if (own) flowPass.new.toByteArray(Charsets.UTF_8) else null,
            flowAppearance,
            if (own) setupCaptureModes else emptySet(),
        )
    }

    /** The kit's Continue: the flow is done, on the list or on Edit vault, which [commitAdd] updated. */
    private fun finishVaultFlow() {
        recoveryKit = null
        recoveryKitVault = null
        recoveryKitCreationLeft = VaultKeyring.KIT_VIEWS
        closeVaultFlow()
        loadIndex()
        resumeOnboarding()
    }

    private fun closeEditVault() {
        editingVault = null
        vaultListProblem = null
        passphraseStatus = null
    }

    /**
     * Back from a passphrase screen onto Edit vault — `vault-passphrase-manage`, D6. Not while a
     * derivation runs: its result lands on Edit vault, and leaving under it would race that.
     */
    private fun closePassphraseScreen() {
        if (busy) return
        passphraseScreen = null
        passphraseWrong = false
        passphraseProblem = null
    }

    /**
     * Back on Edit vault after an act landed — D5. The entry is re-read so the badge's lock mark
     * and the section follow in the same frame as [status], and the list and the drawer follow too.
     */
    private fun passphraseActLanded(position: Int, status: Int) {
        vaultEntries = VaultKeyringSession.vaults()
        editingVault = vaultEntries.firstOrNull { it.position == position }
        passphraseScreen = null
        passphraseWrong = false
        passphraseProblem = null
        passphraseStatus = getString(status)
    }

    /** One act's outcome that did not land, onto its screen. */
    private fun passphraseActRefused(act: VaultLifecycle.PassphraseAct) {
        busy = false
        passphraseWrong = act == VaultLifecycle.PassphraseAct.WRONG_PASSPHRASE
        passphraseProblem = if (act == VaultLifecycle.PassphraseAct.NOT_SAVED) getString(R.string.vault_list_not_saved) else null
    }

    /**
     * Add a passphrase's commit, on the capture step's Add passphrase — `new-vault-setup-flow`, D7,
     * D8. The act proves the list's passphrase again, re-seals, and writes the chosen modes; then the
     * vault's kit, which spends no view, with Edit vault under it saying "Passphrase added." A list
     * passphrase found wrong here returns to step 1 with the error and the typed values kept.
     */
    private fun commitAdd() {
        val entry = editingVault ?: return
        val list = flowListBytes?.copyOf() ?: return
        val new = flowPass.new.toByteArray(Charsets.UTF_8)
        val modes = setupCaptureModes
        flowProblem = null
        setupBusy = true
        thread {
            var kitKey: ByteArray? = null
            try {
                val added = lifecycleOps.addVaultPassphrase(entry.position, list, new, modes)
                kitKey = added.kitKey
                val words = if (added.act == VaultLifecycle.PassphraseAct.DONE) kitKey?.let { mnemonic.encode(it) } else null
                if (words != null) TamperLog.append(blobs, TamperLog.RECOVERY_KIT_GENERATED)
                runOnUiThread {
                    setupBusy = false
                    when (added.act) {
                        VaultLifecycle.PassphraseAct.WRONG_PASSPHRASE -> {
                            flowListBytes?.fill(0)
                            flowListBytes = null
                            flowListWrong = true
                            flowStep = NewVaultStep.Passphrase
                        }
                        VaultLifecycle.PassphraseAct.NOT_SAVED -> flowProblem = getString(R.string.vault_list_not_saved)
                        VaultLifecycle.PassphraseAct.DONE -> {
                            passphraseActLanded(entry.position, R.string.vault_passphrase_added)
                            if (!added.enrolled) vaultListProblem = getString(R.string.vault_capture_not_enrolled, entry.name)
                            if (words != null) {
                                recoveryKitVault = entry.name
                                recoveryKitCreationLeft = VaultKeyring.KIT_VIEWS - (editingVault?.kitViewsUsed ?: 0)
                                recoveryKit = words
                                flowStep = NewVaultStep.Kit
                            } else {
                                closeVaultFlow()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w("VaultKeyring", "a vault passphrase could not be added", e)
                runOnUiThread {
                    setupBusy = false
                    flowProblem = getString(R.string.vault_list_not_saved)
                }
            } finally {
                list.fill(0)
                new.fill(0)
                kitKey?.fill(0)
            }
        }
    }

    /** Change passphrase — D2. The current one proven, then the re-seal; no kit (D3). */
    private fun changeVaultPassphrase(entry: VaultKeyring.Entry, current: ByteArray, new: ByteArray) {
        passphraseWrong = false
        passphraseProblem = null
        busy = true
        thread {
            try {
                val act = lifecycleOps.changeVaultPassphrase(entry.position, current, new)
                runOnUiThread {
                    if (act == VaultLifecycle.PassphraseAct.DONE) {
                        busy = false
                        passphraseActLanded(entry.position, R.string.vault_passphrase_changed)
                    } else {
                        passphraseActRefused(act)
                    }
                }
            } catch (e: Throwable) {
                Log.w("VaultKeyring", "a vault passphrase could not be changed", e)
                runOnUiThread { passphraseActRefused(VaultLifecycle.PassphraseAct.NOT_SAVED) }
            } finally {
                current.fill(0)
                new.fill(0)
            }
        }
    }

    /**
     * Remove passphrase — D4. The current one proven, the captures moved home, then the re-seal.
     * The list, the badge and the drawer's Intruder capture and Recovery kit rows follow the
     * refreshed entries; the home badge is recomputed from the store the captures landed in.
     */
    private fun removeVaultPassphrase(entry: VaultKeyring.Entry, current: ByteArray) {
        passphraseWrong = false
        passphraseProblem = null
        busy = true
        thread {
            try {
                val act = lifecycleOps.removeVaultPassphrase(entry.position, current)
                runOnUiThread {
                    if (act == VaultLifecycle.PassphraseAct.DONE) {
                        busy = false
                        passphraseActLanded(entry.position, R.string.vault_passphrase_removed)
                        ingestHomeCaptures()
                    } else {
                        passphraseActRefused(act)
                    }
                }
            } catch (e: Throwable) {
                Log.w("VaultKeyring", "a vault passphrase could not be removed", e)
                runOnUiThread { passphraseActRefused(VaultLifecycle.PassphraseAct.NOT_SAVED) }
            } finally {
                current.fill(0)
            }
        }
    }

    private fun openRecoverVault() {
        vaultListProblem = null
        vaultRecoveryFailed = false
        recoveringVault = true
    }

    private fun closeRecoverVault() {
        recoveringVault = false
        vaultRecoveryFailed = false
    }

    private fun closeVaultPrompt() {
        promptingVault = null
        vaultPassphraseFailed = false
    }

    private fun closeExport() {
        // `protected-vault-destroy-gate` D5. Back before the return starts returns the user where
        // they began: nothing is armed, and a vault the flow opened from the list is left again.
        if (destroyAfterExport != null && !returnStarted) {
            destroyAfterExport = null
            if (returnOpenedVault && VaultSession.isUnlocked) leaveVault()
        }
        exportAuthorised = false
        returnOpenedVault = false
        exporting = false
        exportCaptures = false
        exportAuthFailed = false
        // The scope belongs to the screen, not to the app. Leaving it and coming
        // back through the drawer must not inherit a selection's scope.
        exportSelection = null
    }

    private fun closeRecover() {
        recovering = false
        recoveryFailed = false
        recoveryInUse = false
        pendingBackup = null
        restoringOntoEmpty = false
        recoveryBackupProblem = null
        recoveryProgress = null
        recoverySealing = false
    }

    /** D8. "Add a backup file" and "Backup is on an SD card" on Recover's words step. */
    private fun addRecoverBackup(folder: Boolean) {
        if (folder) {
            pickMirrorFolder(restore = true, pick = BackupPick.RECOVER)
        } else {
            backupPick = BackupPick.RECOVER
            AutoLock.awaitingResult(true)
            openBackupFile.launch(arrayOf("*/*"))
        }
    }

    private fun recoverChose(uri: Uri, folder: Boolean) {
        backupPick = BackupPick.RESTORE
        recoveryBackupProblem = null
        recoveryFailed = false
        pendingBackup = ChosenBackup(
            uri, folder, if (folder) CloudMirror.folderLabel(this, uri) else displayName(uri),
        )
    }

    /**
     * Unregisters the before-lock hook — only this instance's, so a destroy that lands after
     * the next activity registered its own never clears that one.
     */
    override fun onDestroy() {
        if (VaultLifecycle.beforeLock === writeResumeBeforeLock) VaultLifecycle.beforeLock = null
        // `switch-builds-without-losing-vaults` D3. The next launch sweeps its blobs (D5).
        heldArchive?.vmk?.fill(0)
        heldArchive = null
        super.onDestroy()
    }

    /**
     * The cover is no longer the foreground screen, so the dots it was showing come down —
     * `cover-hold-dots` D4.
     *
     * Nothing else belongs here. P1-8.5 once cleared `ThumbnailCache` from it as the cache
     * half of a lock that did not exist yet; P1-10's `AutoLock` now performs the whole lock —
     * key and cache together — from `Application`, and two places clearing the same cache is
     * one place to forget when the policy changes.
     */
    override fun onStop() {
        super.onStop()
        // `cover-hold-dots` D4. Leaving the foreground takes the dots down; the next arrival
        // needs another hold.
        hideCoverDots()
    }

    /**
     * `recents-shows-identity` D1. The user is leaving by choice, so the identity picture goes
     * up, and the flag comes off once a frame of it is committed ([IdentityLayer]).
     *
     * Not while a result this app launched is outstanding: the photo picker, the file chooser
     * and a permission request each come back to the screen they left, and a cover drawn over
     * it while it animates out would flash at the user. The dots come down here as well as in
     * [onStop], because a live cover is its own picture and the card must not carry them.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (AutoLock.isAwaitingResult) return
        leaving = true
        hideCoverDots()
    }

    /**
     * `recents-shows-identity` D4. The flag back on, then the picture down — in that order,
     * so no frame of the live screen is ever drawn with the flag off. [onStart] and
     * [onResume] both, because a cancelled leave gesture resumes without stopping.
     */
    private fun returnFromLeave() {
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        leaving = false
    }

    /**
     * `recents-shows-identity` D4. Called by the composition that raised the picture: once
     * the frame holding it is submitted, the flag comes off so Android records it for the
     * card. A return that landed first wins, and the flag stays on.
     */
    private fun liftSecureAfterCommit() {
        val view = window.decorView
        view.viewTreeObserver.registerFrameCommitCallback {
            if (leaving) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        // The live-cover case changes no pixel once its dots are already down, and a callback
        // registered on a frame that draws nothing would wait for one that never comes.
        view.invalidate()
    }

    override fun onStart() {
        super.onStart()
        returnFromLeave()
        // P1-16. Play's answer arrives on its own schedule, and a purchase made in
        // Play's sheet lands while this activity is away. Reading it here is the
        // whole of the "purchase completed" path — no listener plumbing, no state
        // to keep in sync.
        syncBilling()
        // The vault may have been locked while the app was away — P1-10 makes
        // that the normal case rather than a rare one.
        unlocked = VaultSession.isUnlocked
        keyringOpen = VaultKeyringSession.isOpen
        decoySetUp = VaultKeyringSession.hasDecoy()
        vaultEntries = VaultKeyringSession.vaults()
        // `face-down-lock-settings` D4. No permission; `@Readable`. Absent reads as never.
        screenTimeoutMs = runCatching {
            android.provider.Settings.System.getLong(contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrDefault(Long.MAX_VALUE)
        // The grant a cover needs, re-read on every return to the foreground —
        // `cover-biometric-suppression`. Granting a permission in Settings does **not**
        // restart the process, so a value read once in `onCreate` leaves the music player
        // insisting it cannot read music that the system's own settings screen says it
        // can — an app visibly disagreeing with Settings, which is a thing a person
        // investigates. The same expression `requestCoverPerm` uses, so identity setup
        // refreshes too. Unconditional: it is a `checkSelfPermission` and a
        // `MusicLibrary.hasAccess`, and a guard would cost what the calls cost.
        coverGrant = coverGranted(settingUpIdentity?.cover ?: identity.cover)
        // `unavailable-capability-states`. Enrolment changes in Settings without a
        // process restart, so the biometric state is re-read on every return — the
        // same reason as the grant above.
        refreshBiometric()
        // `screen-off-lock-all-screens`. The app was locked while it was away — screen off,
        // home, recents — so every screen goes, by the reset the Lock button runs. The epoch
        // and not the keyring: Recover, Import archive and Restore backup are reached with
        // nothing open, so there is no keyring to have closed, and a picker round trip leaves
        // the epoch where it was, which keeps a chosen file on screen. No vault on the device
        // means no lock screen to return to, and ending onboarding there would lose it.
        val epoch = AutoLock.awayEpoch
        if (epoch != seenEpoch && hasVault) resetForLock()
        seenEpoch = epoch
        if (!keyringOpen) {
            open = null
            index = MetadataDb.empty()
            // `language-picker-redesign`. A language change's Undo drawn over a cover would
            // say what the cover hides.
            snackbars.currentSnackbarData?.dismiss()
            // The cover follows the lock, not the process — `cover-biometric-suppression`.
            //
            // ***Raise only, never lower.*** A plain assignment here would dismiss a cover
            // that is legitimately showing over a live session — the case
            // `cover-front-ends` requires of a launch — dropping the user onto the grid
            // with no trigger and evaporating the identity exactly when it matters.
            //
            // Inside this branch and not beside it, which is where the `!keyringOpen` half
            // of the condition comes from. **A return to the foreground is not a launch.**
            // `onCreate` raises the cover unconditionally, including over a live session.
            // Here the keyring decides, and P1-10 is why that matters: auto-lock is
            // deliberately not a grace period, so an ordinary trip through the background
            // has already locked by the time this runs and the cover goes up. What reaches
            // this branch with a session still live is one of `AutoLock`'s two
            // suppressions — a picker this app launched, or an import in flight — and
            // raising the cover on the way back from the photo picker would put a
            // calculator in front of the import the user just chose files for.
            //
            // Ahead of the prompt below, and that ordering is the fix: raised afterwards,
            // the cover would go up over a prompt that had already fired, which is the
            // whole defect.
            if (identity.cover != null) coverShowing = true
            // A fresh arrival at the locked state, so P1-9's one-shot prompt is
            // armed again. Without this, auto-lock would leave biometric unlock
            // reachable only by tapping the unlock screen's biometric control every
            // single time.
            bioPrompted = false
            autoPromptBiometric()
        }
        // `cover-hold-dots` D5. Arrival — the cover becoming the foreground screen — reads where
        // the worn cover's hold is, and the calculator's method (`calculator-dots-methods` D4).
        // After the raise above, so the cover is this arrival's.
        coverHoldArea = identity.cover?.let { CoverTrigger.holdFor(this, it) }
        coverCalculatorMethod = identity.cover?.takeIf { it == Cover.Calculator }?.let { CoverTrigger.calculatorMethod(this) }
    }

    /**
     * P2-20. Face-down listens only while the user is looking at the app.
     *
     * Resumed and not merely open (the keyring or a vault), deliberately: P1-10's
     * two suppressions — a picker this app launched, and in-flight import or export
     * work — both leave this activity not resumed, so the trigger is already off
     * during both and cannot park an unattended import on `ImportWorker`'s wait-for-unlock branch.
     * No third suppression case — `runtime-hardening` permits exactly two.
     *
     * It costs the feature nothing: turning a phone over pauses no activity.
     * Nothing in Android takes focus for a change of pose.
     */
    override fun onResume() {
        super.onResume()
        returnFromLeave()
        FaceDownLock.start(this, ::faceDownLock)
        // `per-vault-intruder-capture` D8, D9. A grant or revocation in Settings, the quick-settings
        // access switches and the unused-app exemption all change without a restart.
        if (CapturePermissions.observe(this)) recordNoticedPermissions()
        refreshCapturePermissions()
        // `one-time-purchase` D3. A pending payment that cleared, or a refund, happened
        // while the app was away; Play's documented place to notice is the return.
        if (BuildConfig.TRIAL) Billing.refresh()
    }

    override fun onPause() {
        super.onPause()
        FaceDownLock.stop()
    }

    // --- lifecycle -----------------------------------------------------------

    /**
     * Argon2id costs ~460 ms (P1-3) and the chaff write is far longer, so both
     * run off the main thread. Doing either inline is an ANR on first launch.
     */
    private fun createVault(passphrase: ByteArray, name: String, appearance: VaultAppearance) {
        // P1-16: creating a vault is adding to the app, and the gate goes before
        // any work — no chaff written, no Argon2id run, nothing to undo.
        if (!mayAddToVault()) {
            passphrase.fill(0)
            // `notice` is the grid's surface and there is no grid during first run,
            // so the refusal would be silent — the fields clear and nothing happens,
            // which is indistinguishable from the hang above.
            setupProblem = getString(Entitlement.BLOCKED)
            return
        }
        setupProblem = null
        busy = true
        setupProgress = 0 to 1
        // `switch-builds-without-losing-vaults` D4. A vault a `.vault` file brought in is the
        // first entry, in place of a new one.
        val held = heldArchive
        thread {
            try {
                // `first-vault-naming`. The name and look the user chose on Name and look.
                lifecycleOps.create(
                    passphrase,
                    firstVaultName = name,
                    firstVaultAppearance = appearance,
                    firstVaultKey = held?.vmk,
                ) { done, total ->
                    setupProgress = done to total
                }
                // The kit is derived from the open vault's VMK and shown once. It
                // is never written to disk — that is why nothing on disk reveals
                // whether an install has one.
                // `vault-keyring`. The kit is the **keyring's**, so 24 words recover
                // the list and every vault on it — one kit rather than one per vault.
                val words = VaultKeyringSession.withKey { mnemonic.encode(it) }
                TamperLog.append(blobs, TamperLog.RECOVERY_KIT_GENERATED)
                runOnUiThread {
                    if (held != null) {
                        held.vmk.fill(0)
                        heldArchive = null
                        heldFrom = null
                        // `one-time-purchase` D4. A vault with items has been added.
                        Entitlement.startTrialIfNeeded(this)
                    }
                    hasVault = true
                    unlocked = true
                    keyringOpen = true
                    // A keyring made a moment ago, so this is false — read rather than
                    // assumed, because every other keyring transition reads it and one
                    // that assumed would be the one that drifted.
                    decoySetUp = VaultKeyringSession.hasDecoy()
                    vaultEntries = VaultKeyringSession.vaults()
                    recoveryKit = words
                    firstName = ""
                    firstAppearance = VaultAppearance.defaultFor(0)
                    firstNameError = null
                    loadIndex()
                    // `first-run-onboarding`. **The step advances here and only
                    // here.** The kit is derived from the vault that was just
                    // created, so step 2 cannot be reached before this point — and
                    // leaving the flow on step 1 after a successful create is what
                    // made the screen redraw itself with empty fields and no way on.
                    if (onboardingStep == Onboarding.Step.Passphrase) {
                        advanceOnboarding(Onboarding.Step.Kit)
                    }
                }
            } catch (e: Exception) {
                Log.w("VaultSetup", "vault creation failed", e)
                runOnUiThread {
                    setupProblem = getString(R.string.setup_vault_not_created)
                }
            } finally {
                busy = false
                setupProgress = null
            }
        }
    }

    /**
     * P2-1. Creates a vault beside the one that is open.
     *
     * Three things happen here that the first-run path does not need. The
     * entitlement gate reports through this screen rather than the grid's notice,
     * because there is no grid on screen. One of the supplied passphrases must
     * open the vault list on screen — a real derivation and a key compare
     * through `ExportAuth.confirms`, the list's passphrase even inside a vault with
     * its own, since that is what reopens the list on the way back — because
     * sealing a slot can destroy a vault and a momentarily unlocked device is not
     * authority to do that. And a refusal has to be named, which is the one place
     * P2-1 permits a message beyond "that did not open a vault": both sentences
     * report only what the user just typed or what the lock screen would already
     * have told them.
     */
    // --- decoy tooling (P2-2) --------------------------------------------------

    private fun pickDecoyItems() {
        AutoLock.awaitingResult(true)
        pickDecoyMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    /**
     * Reads each chosen source's capture date and asks [Decoy] what to do with it.
     *
     * Nothing is written here. The plan is shown first — how many keep their own
     * date, how many get one, how many are left out and why — because the two gates
     * can refuse, and a refusal after fifty imports would be a refusal that cost
     * something.
     */
    private fun planDecoy(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        thread {
            try {
                val now = System.currentTimeMillis()
                val window = Decoy.setupWindow(decoyStartedAt, now)
                val taken = uris.take(Decoy.MAX_ITEMS)
                val dates = taken.map {
                    runCatching { SourceMetadata.describe(contentResolver, it).createdAt }.getOrNull()
                }
                val plan = Decoy.plan(dates, window, now, kotlin.random.Random.Default)
                val kept = plan.count { it is Decoy.Decision.Keep }
                val assigned = plan.count { it is Decoy.Decision.Assign }
                val chosen = plan.mapNotNull { it.date }
                val installDays =
                    (now - Decoy.installedAt(File(filesDir, "blobs"), now)) / Decoy.DAY

                runOnUiThread {
                    decoyPicked = taken
                    decoyPlan = plan
                    decoySummary = DecoySummary(
                        chosen = taken.size,
                        kept = kept,
                        assigned = assigned,
                        declined = plan.count { it is Decoy.Decision.Decline },
                        spreadWeeks = Decoy.spreadWeeks(chosen),
                        outsideWindow = Decoy.outsideWindow(chosen, window),
                        installDays = installDays,
                        truncated = uris.size > Decoy.MAX_ITEMS,
                    )
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Creates the decoy and fills it.
     *
     * **The creation and the history are one operation**, which is the whole reason
     * this flow creates rather than populates: the vault-created entry is entry 0 of
     * an append-only log, so a backdated history written into an existing vault
     * would put "created today" before "opened five months ago". Time running
     * backwards after entry 0 is something no honest vault can produce, so it would
     * prove the tool had run — the one marker P2-2 must not leave.
     */
    private fun createDecoy(auth: ByteArray) {
        // P1-16, as everywhere else that adds: creating and importing are both
        // adding to the app, and the gate goes before any work.
        if (!Entitlement.importAllowed(this)) {
            auth.fill(0)
            decoyProblem = getString(Entitlement.BLOCKED)
            decoySheet = null
            return
        }
        val plan = decoyPlan
        val sources = decoyPicked
        if (plan.isEmpty()) {
            auth.fill(0)
            decoyProblem = getString(R.string.decoy_choose_photos_first)
            decoySheet = null
            return
        }

        busy = true
        decoyProblem = null
        decoyAuthFailed = false
        setupProgress = 0 to 1
        thread {
            try {
                val slotsFile = File(filesDir, "vault.slots")
                // **Authorisation, and only that.** Sealing a slot is destructive and
                // a momentarily unlocked device is not authority to do it. What this
                // is no longer doing is standing in for preservation: the vault this
                // passphrase opens is excluded from the write by
                // `VaultLifecycle.create` from session state, whether it is typed
                // here or not, so a user who fails this gate has failed to authorise
                // and has not put their vault at risk.
                if (!ExportAuth.confirms(slotsFile, auth.copyOf())) {
                    auth.fill(0)
                    runOnUiThread { decoyAuthFailed = true }
                    return@thread
                }
                // Kept, not consumed — `decoyReturnPass`. `confirms` zeroes what it is
                // given, so it got a copy and the original is the way home.
                decoyReturnPass = auth
                decoyReturnPosition = VaultKeyringSession.openVaultPosition()
                runOnUiThread { decoySheet = null }

                val held = decoyPassBytes ?: run {
                    runOnUiThread { decoyProblem = getString(R.string.decoy_set_passphrase_first) }
                    return@thread
                }
                // **A copy, because `create` zeroes what it is given.** Handing over the
                // held array would leave nothing to retry with: a creation refused for
                // `PASSPHRASE_IN_USE` is one the user fixes by choosing another
                // passphrase, and they would have arrived at that message with the
                // field behind them already emptied and no way to act on it.
                val passphrase = held.copyOf()
                val preserve = decoyPreserve.toList()
                val chosenName = decoyName.trim()

                val rng = kotlin.random.Random.Default
                val now = System.currentTimeMillis()
                val window = Decoy.setupWindow(decoyStartedAt, now)
                val origin = Decoy.origin(
                    Decoy.installedAt(File(filesDir, "blobs"), now), window, now, rng
                )

                val outcome = lifecycleOps.create(
                    passphrase,
                    preserve,
                    // `decoy-flow-overhaul`. The one creation path that used to omit
                    // this, and so the one whose vaults were all called "My vault" —
                    // the migration placeholder, identical on every install that ran
                    // the flow.
                    firstVaultName = chosenName,
                    origin = origin,
                ) { done, total ->
                    setupProgress = done to total
                }
                if (!outcome.opened) {
                    val message = when (outcome.refusal) {
                        // Both of these are recoverable, and each names the step that
                        // recovers it — a refusal that leaves the user on a screen with
                        // no move is the defect, not the refusal itself.
                        VaultLifecycle.Refusal.PASSPHRASE_IN_USE ->
                            getString(R.string.decoy_passphrase_in_use)
                        VaultLifecycle.Refusal.NO_ROOM ->
                            getString(R.string.decoy_no_room)
                        // `slot-occupancy-map`. This install predates the record of
                        // which slots are filled, so the app knows a decoy exists and
                        // cannot tell where — and sealing without being told would
                        // stand a chance of writing over it. Recoverable on the step
                        // the user is already on: they go back and add the passphrase,
                        // and it is never asked for again.
                        VaultLifecycle.Refusal.DECOY_PASSPHRASE_NEEDED ->
                            getString(R.string.decoy_passphrase_needed)
                        // Restore refusals; creation never returns them.
                        VaultLifecycle.Refusal.BACKUP_NOT_OPENED, VaultLifecycle.Refusal.LIST_NOT_HERE,
                        VaultLifecycle.Refusal.BACKUP_INCOMPLETE, null -> getString(R.string.decoy_not_created)
                    }
                    runOnUiThread { decoyProblem = message }
                    return@thread
                }

                // The history, into the vault that was just created and opened, in
                // ascending order and never before its own first entry.
                Decoy.unlockHistory(origin, window, rng).forEach { at ->
                    TamperLog.append(blobs, TamperLog.UNLOCK_SUCCEEDED, timestamp = at)
                }

                val indexId = VaultSession.metadataBlobId() ?: return@thread
                val wm = WorkManager.getInstance(this)
                val blobRng = SecureRandom()
                sources.forEachIndexed { i, uri ->
                    val decision = plan[i]
                    if (decision is Decoy.Decision.Decline) return@forEachIndexed
                    val createdAt = decision.date ?: return@forEachIndexed
                    val blobId = ByteArray(BlobStore.BLOB_ID_BYTES).also { blobRng.nextBytes(it) }
                    val request = OneTimeWorkRequestBuilder<ImportWorker>()
                        .addTag(ImportWorker.IMPORT_TAG)
                        .build()
                    WorkJobs.register(
                        request.id,
                        WorkJobs.ImportJob(
                            uri, CopyOnWriteArrayList(), indexId,
                            // Only ever a fallback: the pipeline prefers the
                            // source's own date, so a kept date cannot be replaced
                            // from here even by mistake.
                            assignedCreatedAt = createdAt,
                            assignedImportedAt =
                                Decoy.assignedImportedAt(createdAt, origin, window, rng),
                        )
                    )
                    wm.enqueue(request)
                }

                // The words are held, not shown: the handover step comes first, and
                // the kit branch sits above the flow's in the `when`.
                val words = VaultKeyringSession.withKey { mnemonic.encode(it) }

                runOnUiThread {
                    // Consumed: `create` owns and zeroes what it was handed, and
                    // `closeDecoy` must not zero the same bytes twice.
                    decoyPreserve.clear()
                    decoyPassBytes?.fill(0)
                    decoyPassBytes = null
                    decoyPicked = emptyList()
                    decoyPlan = emptyList()
                    // The decoy step's label reads this count, so it has to be true
                    // by the time the flow is drawn again.
                    vaultEntries = VaultKeyringSession.vaults()
                    // The session switched keyrings inside `create`; these two say so.
                    // What they no longer do is *navigate* — the flow stays up and
                    // moves to its last step, which is where the switch gets said out
                    // loud. Before this, these lines were the whole of the app's
                    // account of it: the user was dropped on a one-vault list under a
                    // name they had never chosen, with a recovery kit nothing
                    // explained.
                    unlocked = true
                    open = null
                    decoyKitWords = words
                    decoyBuiltThisRun = true
                    decoyStep = DecoyFlow.Step.Handover
                    loadIndex()
                }
            } finally {
                busy = false
                setupProgress = null
            }
        }
    }

    /**
     * P1-12. Recovery is one outcome either way — the screen must not learn
     * whether the words were wrong or whether a vault was ever here.
     *
     * `recovery-own-slot-only`, D6. A failure caused by the words — they open nothing
     * here, or a backup file refused them — goes back to the words step
     * ([recoveryFailed]); a passphrase already in use stays on the passphrase step
     * ([recoveryInUse]).
     */
    private fun attemptRecovery(phrase: String, newPassphrase: ByteArray) {
        busy = true
        recoveryFailed = false
        recoveryInUse = false
        recoveryBackupProblem = null
        recoveryProgress = null
        recoverySealing = false
        val chosen = pendingBackup
        // D5.1. An empty store is the empty-phone restore, however it was reached.
        val fresh = restoringOntoEmpty || (chosen != null && CloudMirror.localEntries(blobsDir()).isEmpty())
        val source = chosen?.let { backupSource(it.uri, it.folder) }
        thread {
            val outcome = try {
                lifecycleOps.recover(
                    mnemonic, phrase, newPassphrase, fresh, source,
                    { done, total -> runOnUiThread { recoveryProgress = done to total } },
                    onSealing = { runOnUiThread { recoverySealing = true } },
                )
            } catch (e: Exception) {
                Log.w("BackupRestore", "the backup could not be read", e)
                null
            } finally {
                busy = false
            }
            Log.i("BackupRestore", "recovery ${if (outcome?.opened == true) "opened the keyring" else "did not open"}")
            runOnUiThread {
                recoveryProgress = null
                recoverySealing = false
                when {
                    outcome == null -> recoveryBackupProblem = getString(UNREADABLE_BACKUP)
                    outcome.opened -> {
                        // `one-time-purchase` D4. Words alone open what is already here; a
                        // backup brought in with them is an add.
                        if (chosen != null) Entitlement.startTrialIfNeeded(this)
                        pendingBackup = null
                        restoringOntoEmpty = false
                        // A restore is not a first run: the vault exists, and setup's first step
                        // is creating one. The flow ends and the person lands in what came back;
                        // every step it would have offered is in Settings.
                        if (onboardingStep != null) advanceOnboarding(null)
                        hasVault = true
                        recovering = false
                        // `vault-keyring`. Recovery opens the keyring and stops there, exactly
                        // as a passphrase does (`VaultLifecycle.recover`: "the keyring is held
                        // open and no vault is"), so the person lands on the list.
                        keyringOpen = true
                        unlocked = false
                        panicEnabled = VaultKeyringSession.hasPanicPhrase()
                        decoySetUp = VaultKeyringSession.hasDecoy()
                        vaultEntries = VaultKeyringSession.vaults()
                    }
                    outcome.refusal == VaultLifecycle.Refusal.PASSPHRASE_IN_USE -> recoveryInUse = true
                    // D5. Each backup refusal returns to the words, under the backup row.
                    outcome.refusal == VaultLifecycle.Refusal.BACKUP_NOT_OPENED ->
                        recoveryBackupProblem = getString(R.string.recovery_words_wrong_backup)
                    outcome.refusal == VaultLifecycle.Refusal.LIST_NOT_HERE ->
                        recoveryBackupProblem = getString(R.string.recovery_list_not_here)
                    outcome.refusal == VaultLifecycle.Refusal.BACKUP_INCOMPLETE ->
                        recoveryBackupProblem = getString(INCOMPLETE_BACKUP)
                    else -> recoveryFailed = true
                }
            }
        }
    }

    private fun pickExportFolder() {
        AutoLock.awaitingResult(true)
        pickExportTree.launch(null)
    }

    // --- first run (`first-run-onboarding`) -----------------------------------


    /**
     * The state every biometric surface reads: the held [bioState], with On and Off
     * taken from [bioEnabled] so a toggle shows without another binder read.
     */
    private fun biometricState(): BiometricState = when {
        !bioState.ready -> bioState
        bioEnabled -> BiometricState.On
        else -> BiometricState.Off
    }

    /**
     * Every biometric read, in one place — `unavailable-capability-states`. Called from
     * [onStart] and the enrolment callback; never from a composition.
     */
    private fun refreshBiometric() {
        bioState = BiometricWrap.state(this, bioEnabled)
        bioButtonLabel = BiometricWrap.buttonLabel(this)
        bioAllStrong = BiometricWrap.allStrong(this)
    }

    /** Which rows the biometric surfaces draw — `biometric-unlock-screen` D1, from held values. */
    private fun biometricLayout(): BiometricLayout =
        BiometricWrap.layout(biometricState(), bioHasFingerprint, bioHasFace, bioAllStrong)

    /**
     * Android's enrolment screen, limited to what the vault can use — `BIOMETRIC_STRONG`.
     * Auto-lock is held for the round trip as it is for the pickers. Where the action
     * does not resolve (some OEM builds), security settings, then Settings; if none
     * does, the hold is released so nothing is left suppressed.
     */
    private fun openBiometricEnrol() {
        AutoLock.awaitingResult(true)
        enrolThenEnable = true
        val intents = listOf(
            android.content.Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            ),
            android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            try {
                enrolBiometric.launch(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // The next link in the chain.
            }
        }
        enrolThenEnable = false
        AutoLock.awaitingResult(false)
    }

    /**
     * Back from step 1 to the welcome — `switch-builds-without-losing-vaults` D5: a vault a
     * `.vault` file brought in goes with it.
     */
    private fun backToWelcome() {
        discardHeldArchive()
        welcomeSeen = false
    }

    private fun beginOnboarding() {
        if (!Onboarding.inProgress(this)) return
        advanceOnboarding(Onboarding.Step.Name)
    }

    /**
     * Moves to [step], or ends the flow when there is none.
     *
     * The prefs write happens on **arrival**, not on completion, sealed under the
     * keyring open now — always the owner's. A lock or a restart on this step is
     * resumed here by [resumeOnboarding]. With no keyring open (the passphrase step)
     * nothing is written, and nothing needs to be: no vault restarts the flow anyway.
     *
     * This is the only thing that moves the flow forward. There is deliberately no
     * second route: the step's single forward action reaches it whether the step's
     * work was done or passed over, and the label on that action is what says which.
     */
    private fun advanceOnboarding(step: Onboarding.Step?) {
        clearOnboardingScreens()
        if (step == null) {
            onboardingStep = null
            Onboarding.finish(this)
            return
        }
        // Every step is drawn, on every device — `first-run-onboarding`. A step whose
        // hardware is missing says so on its own screen rather than being passed over.
        VaultKeyringSession.sealHeld(byteArrayOf(step.ordinal.toByte()))?.let { Onboarding.reached(this, it) }
        onboardingStep = step
        // `backup-screen-status-first` D13. The step shows where this vault stands.
        if (step == Onboarding.Step.Backup) {
            mirrorProblem = null
            refreshBackupStatus()
        }
    }

    /**
     * The step is done, whatever it decided. Move on.
     *
     * Passing a step enrols nothing, because a step's screen enrols nothing until
     * its own secondary control is used — there is no "declined" to record, and
     * recording one would be the tell `panic-pin` and `album-organisation`'s decoy
     * both exist to avoid.
     */
    private fun completeOnboardingStep() {
        advanceOnboarding(onboardingStep?.next)
    }

    /**
     * Takes the flow off screen for a lock, without ending it. The step stays
     * sealed in [Onboarding], and [resumeOnboarding] brings it back when the keyring
     * that reached it opens again.
     */
    private fun pauseOnboarding() {
        clearOnboardingScreens()
        onboardingStep = null
    }

    /**
     * Puts a paused flow back on screen at the step reached — `first-run-onboarding`.
     *
     * Runs on the keyring's rising edge, which covers a lock, a restart (the relaunch
     * unlocks through the lock screen) and an activity recreated with the keyring
     * open. [Onboarding.pausedStep] opens only under the keyring the step was sealed
     * under, so the decoy's keyring, or any other, resumes nothing and shows nothing.
     *
     * **A kit the lock held goes first.** If [restoreHeldKit] put back a kit that is
     * not step 2's, the app's kit branch draws it, and its Continue calls this again.
     *
     * **An interrupted kit step shows the same words again**, re-derived from the
     * open keyring by the expression [createVault] uses. Nothing new is generated, so
     * no view is spent and nothing is logged. Only while the paused step is the kit:
     * completing it seals the next step over it, and the window closes.
     *
     * **The vault goes back open too.** A fresh run holds open the vault [createVault]
     * made; an unlock lands on the list with none. Without it the decoy's
     * authorisation (`ExportAuth.confirms`) refuses the right passphrase and backup
     * has no vault to report on — the step would be back on screen and unable to act.
     */
    private fun resumeOnboarding() {
        if (onboardingStep != null || !Onboarding.inProgress(this)) return
        val paused = Onboarding.pausedStep(this) ?: return
        if (recoveryKit != null && paused != Onboarding.Step.Kit) return
        if (paused == Onboarding.Step.Kit && recoveryKit == null) {
            recoveryKit = VaultKeyringSession.withKey { mnemonic.encode(it) }
        }
        if (!VaultSession.isUnlocked) VaultKeyringSession.vaults().firstOrNull()?.let(::openVaultAt)
        advanceOnboarding(paused)
    }

    /**
     * Clears the flags the hosted screens use, so a step cannot leave one set and
     * have its screen drawn again from a higher branch after the flow moves on.
     */
    private fun clearOnboardingScreens() {
        captureSheet = false
        decoying = false
        backupScreen = false
        panicScreen = false
        identityScreen = false
        biometricScreen = false
        faceDownScreen = false
        settingUpIdentity = null
        committingIdentity = null
        commitState = CommitState.Commit
    }

    /**
     * The navigation drawer, in one place — `settings-separation`.
     *
     * Two screens host it: the vault list and the grid. `vault-only-drawer`: inside a
     * vault it draws only that vault's rows, under its name; on the list it draws the
     * app's settings and the kit-recovery row, which the grid has no use for.
     *
     * A function rather than the same twenty arguments written out twice: two copies
     * of this list are two lists that drift, and the drift would be a setting that
     * exists on one screen and not the other.
     *
     * @param closing wraps a row's action so the drawer shuts behind it. **Two rows
     *   are deliberately not wrapped**, and they are the two that carry a control
     *   rather than lead somewhere: the appearance chooser, which re-themes the grid
     *   *behind* the open sheet — P2-23's omission, preserved — and the face-down
     *   switch, which flips in place. A switch that dismissed the sheet it lives on
     *   would hide the state it had just changed, which is the one thing the user
     *   tapped it to see. Every row that goes somewhere still closes the sheet behind
     *   it, Biometric unlock's included.
     */
    @Composable
    private fun AppDrawer(
        onRecoverVault: (() -> Unit)?,
        onRestoreVault: (() -> Unit)? = null,
        onImportVault: (() -> Unit)? = null,
        vaultOpen: Boolean,
        closing: (() -> Unit) -> (() -> Unit),
    ) {
        VaultDrawerSheet(
            onRecoverVault = onRecoverVault,
            onRestoreVault = onRestoreVault,
            onImportVault = onImportVault,
            // The open vault's own rows, under its name. Null with none open, which
            // takes the group and Destroy Vault with it.
            vaultName = if (vaultOpen) openVaultName() else null,
            onTrash = closing(::openTrash),
            onBackup = closing(::openBackup),
            // `backup-screen-status-first` D12. Only read inside a vault; the list's
            // drawer has no Backup row.
            backupLine = backupDrawerLine(words(), backupStatus).first,
            backupAttention = backupDrawerLine(words(), backupStatus).second,
            onDestroyVault = closing {
                vaultEntries.firstOrNull { it.position == VaultKeyringSession.openVaultPosition() }
                    ?.let(::openDestroy)
            },
            // `distribution-flavors`: the `github` build has nothing to buy, so no row.
            showPurchase = BuildConfig.TRIAL,
            owned = owned,
            purchasePending = purchasePending,
            trialDaysRemaining = Entitlement.daysRemaining(this@MainActivity),
            trialStarted = Entitlement.trialStartedAt(this@MainActivity) != 0L,
            // Unreachable on `github`, where the row is not drawn; guarded all the same so
            // no route there can open the Purchase screen.
            onPurchase = closing { if (BuildConfig.TRIAL) purchaseScreen = true },
            currentIdentity = identity,
            onIdentity = closing { identityScreen = true },
            biometricState = biometricState(),
            // `biometric-unlock-screen` D6. A row that goes somewhere, so it closes the sheet.
            onBiometric = closing { biometricScreen = true },
            // P2-20. Beside the Biometric unlock row, gated on its own hardware: a device
            // with no strong biometric still has an accelerometer. Drawn either way.
            faceDownAvailable = faceDownAvailable,
            faceDownOn = faceDownOn,
            faceDownLockAfter = faceDownLockAfter,
            // `face-down-lock-settings` D6. Biometric unlock's idiom: a row that goes somewhere.
            onFaceDown = closing { faceDownScreen = true },
            decoySetUp = decoySetUp,
            onSetUpDecoy = closing(::openDecoy),
            onPanicCredential = closing(::openPanicCredential),
            // `recovery-kit-three-views`. Keyed on [kitViewsSpent] and [vaultEntries] — the
            // count lives in the session, which Compose cannot observe.
            listKitViewsLeft = (kitViewsSpent to vaultEntries).let { VaultKeyringSession.listKitViewsLeft() },
            onListKit = closing { openKitView(-1) },
            // `recovery-practice` D7. A switch: toggles in place, the sheet stays open.
            extraSecurityOn = extraSecurityOn,
            onToggleExtraSecurity = ::toggleExtraSecurity,
            vaultKitViewsLeft = if (vaultOpen) {
                vaultEntries.firstOrNull { it.position == VaultKeyringSession.openVaultPosition() }
                    ?.takeIf { it.hasOwnPassphrase }
                    ?.let { VaultKeyring.KIT_VIEWS - it.kitViewsUsed }
            } else null,
            onVaultKit = closing { openKitView(VaultKeyringSession.openVaultPosition()) },
            // `intruder-captures-at-home`. The home drawer's row.
            onIntruderCapture = closing(::openIntruderScreen),
            capturesUnseen = capturesUnseen,
            // `per-vault-intruder-capture`. The vault's own row, only in a vault with its own passphrase.
            onVaultIntruderCapture = if (openVaultProtected()) closing(::openVaultIntruderScreen) else null,
            vaultCapturesUnseen = vaultCapturesUnseen,
            onExport = closing {
                // The drawer's action is the whole vault, whatever is selected
                // behind it.
                exportSelection = null
                exporting = true
            },
            onOpenShare = closing(::pickShareFile),
            // `exif-strip-choice`. Ungated. The row names the preset in effect and opens the
            // Photo details sheet, so it closes the drawer like every row that leads somewhere.
            photoDetailsPreset = photoDetailsPreset,
            onPhotoDetails = closing { photoDetailsSheet = true },
            onLanguage = closing { languageSheet = true },
            // P4-4. An app setting: pairing needs no vault key and writes nothing
            // into a vault.
            onContacts = closing(::openContacts),
            // `debug-crash-log-screen`. Null in the release build, which takes the row and its
            // rule with it. The count is re-read whenever the screen opens or closes — reading
            // [crashLogScreen] here is what recomposes it — so a clear shows on the row.
            onCrashLog = if (CrashLogHook.available) closing(::openCrashLog) else null,
            crashLogIcon = CrashLogHook.icon,
            crashLogLine = crashLogScreen.let { CrashLogHook.summary(this@MainActivity) },
            // `third-party-notices`. A row that goes somewhere, so it closes the sheet.
            onLicences = closing {
                licenceOpen = null
                licencesScreen = true
            },
            licenceCount = notices.size,
            // P2-23's exception, unchanged: choosing does not close the sheet, so a
            // second thought costs one tap on a control still there.
            currentAppearance = appearance,
            onChooseAppearance = ::chooseAppearance,
        )
    }

    /**
     * P2-2, from the drawer and from first run's decoy step.
     *
     * Resets through [closeDecoy] rather than clearing two fields by hand: the flow
     * now carries a step position, a name, a passphrase and a preserve list, and an
     * entry point that reset some of them would open the flow part-filled from a
     * previous run — with the previous decoy's name already in the field.
     */
    private fun openDecoy() {
        closeDecoy()
        decoyStartedAt = System.currentTimeMillis()
        decoying = true
    }

    /** P2-3, from the drawer. */
    private fun openPanicCredential() {
        panicProblem = null
        panicEnabled = VaultKeyringSession.hasPanicPhrase()
        panicScreen = true
    }

    /**
     * The home drawer's `Intruder capture` — `intruder-captures-at-home`. Holds the capture store
     * key for as long as the screen is open, and marks what it shows as seen: opening the screen is
     * looking, opening the drawer is not (D7).
     */
    private fun openIntruderScreen() {
        val key = VaultKeyringSession.captureStoreKey() ?: return
        captureKey?.fill(0)
        captureKey = key
        captureSelection = emptySet()
        captureNotice = null
        captureModes = enabledCaptureModes()
        capturesLoaded = false
        intruderScreen = true
        reloadCaptures(markSeen = true)
    }

    /**
     * The open vault's Intruder capture — `per-vault-intruder-capture` D6. Holds the vault's capture
     * store key, derived from the session's VMK, for as long as the screen is open; opening it is
     * looking, and clears the vault's badge (D7).
     */
    private fun openVaultIntruderScreen() {
        val vmk = VaultSession.vmk() ?: return
        captureKey?.fill(0)
        captureKey = VaultKeys.captureStoreKey(vmk)
        captureVault = true
        captureSelection = emptySet()
        captureNotice = null
        captureModes = enabledCaptureModes()
        capturesLoaded = false
        intruderScreen = true
        reloadCaptures(markSeen = true)
    }

    /** Whether the open vault has its own passphrase, and so its own capture. */
    private fun openVaultProtected(): Boolean =
        vaultEntries.firstOrNull { it.position == VaultKeyringSession.openVaultPosition() }?.hasOwnPassphrase == true

    /** Back from the screen, to the vault list. The key goes, and so do the decoded thumbnails. */
    private fun closeIntruderScreen() {
        closeCaptureMove()
        intruderScreen = false
        captureVault = false
        captureOpen = null
        captureSelection = emptySet()
        captureDeleting = null
        captureNotice = null
        captureRows = emptyList()
        captureIndex = MetadataDb.empty()
        captureKey?.fill(0)
        captureKey = null
        ThumbnailCache.clear()
    }

    /**
     * Reads the store's index and log into the timeline — D4. A keyring with no store shows an
     * empty timeline and writes nothing: the store is made by the first capture taken in (D1).
     *
     * With [markSeen], the newest capture's `importedAt` becomes the high-water mark in one store
     * commit, which clears the badge — D7.
     */
    private fun reloadCaptures(markSeen: Boolean = false) {
        val key = captureKey ?: return
        thread {
            val loaded = runCatching {
                val id = VaultKeys.metadataBlobId(key)
                if (!blobs.fileOf(id).exists()) return@runCatching MetadataDb.empty() to emptyList<LogEntry>()
                var db = MetadataDb.load(blobs, id, key)
                val newest = db.items.maxOfOrNull { it.importedAt }
                if (markSeen && newest != null && (db.capturesSeenAt ?: 0L) < newest) {
                    db = MetadataDb.commit(blobs, id, key) { it.capturesSeenAt = newest; it }
                }
                val entries = db.logBlobId?.let { TamperLog.scan(blobs, it, key).entries }.orEmpty()
                db to entries
            }.getOrNull()
            runOnUiThread {
                // The screen may have been left, or the app locked, while this ran.
                if (captureKey !== key || loaded == null) return@runOnUiThread
                val (db, entries) = loaded
                captureIndex = db
                captureRows = CaptureLog.rows(entries, db.items, captureModes)
                capturesLoaded = true
                if (captureVault) vaultCapturesUnseen = unseenIn(db) else capturesUnseen = unseenIn(db)
                // A capture that has left cannot stay selected or open.
                val held = db.items.mapTo(HashSet()) { it.itemId }
                captureSelection = captureSelection.filterTo(HashSet()) { it in held }
                captureOpen = captureOpen?.takeIf { it.itemId in held }
            }
        }
    }

    /** Whether [db] holds a capture imported after the high-water mark — D7. */
    private fun unseenIn(db: MetadataDb): Boolean {
        val seen = db.capturesSeenAt ?: 0L
        return db.items.any { it.importedAt > seen }
    }

    /**
     * Takes in the captures pending for this keyring — D3. Called from one effect, on the keyring
     * opening, whichever way it opened. On a background thread; the badge is set from what landed.
     *
     * Only on the destination keyring: any other leaves every capture pending. A keyring that has
     * never had a capture and has none pending gets no store.
     */
    private fun ingestHomeCaptures() {
        capturesUnseen = false
        val ring = VaultKeyringSession.keyringBlobId() ?: return
        thread {
            val key = VaultKeyringSession.captureStoreKey() ?: return@thread
            try {
                // `per-vault-intruder-capture` D8. The permission changes noticed since, into the home
                // log where a home mode needs them and queued for each enrolled vault that does.
                runCatching {
                    val home = if (CaptureStore.destinationIs(this, key)) {
                        Capture.Mode.entries.filterTo(mutableSetOf()) { Capture.enabled(this, it) }
                    } else {
                        emptySet()
                    }
                    CaptureIngest.recordPermissions(blobs, key, CapturePermissions.transitions(this), home)
                }.onFailure { Log.w("VaultCapture", "permission changes not recorded: ${it.javaClass.simpleName}") }
                val taken = runCatching { CaptureStore.takeIn(this, blobs, key) }.getOrNull()
                if (taken == null) {
                    // Nothing taken in here — not the destination, or nothing pending — but the store
                    // may still hold captures a vault's removed passphrase moved home
                    // (`vault-passphrase-manage` D4), and an unseen one badges whatever the destination.
                    val id = VaultKeys.metadataBlobId(key)
                    val unseen = blobs.fileOf(id).exists() &&
                        runCatching { unseenIn(MetadataDb.load(blobs, id, key)) }.getOrDefault(false)
                    runOnUiThread {
                        if (VaultKeyringSession.keyringBlobId()?.contentEquals(ring) == true) capturesUnseen = unseen
                    }
                    return@thread
                }
                val (store, landed) = taken
                val id = store.metadataBlobId
                // Photo rows have their thumbnails as the screen opens. Its own `ThumbnailStore`:
                // the activity's stages a vault's packs, and a vault may open while this runs.
                val db = runCatching {
                    MetadataDb.commit(blobs, id, key) { db -> Thumbnails.backfill(blobs, ThumbnailStore(blobs), db, key); db }
                }.getOrNull() ?: return@thread
                // `one-time-purchase` D4. Captures that landed are an add; ingest is never gated.
                if (landed > 0) Entitlement.startTrialIfNeeded(this)
                val unseen = unseenIn(db)
                runOnUiThread {
                    if (VaultKeyringSession.keyringBlobId()?.contentEquals(ring) == true) capturesUnseen = unseen
                }
            } finally {
                key.fill(0)
            }
        }
    }

    /** A thumbnail out of the capture store, under its key. */
    private fun captureThumbnailFor(item: VaultItem): Bitmap? {
        val ref = item.thumb ?: return null
        val key = captureKey ?: return null
        return ThumbnailCache.get(ref) { runCatching { thumbs.read(captureIndex, ref, key) }.getOrNull() }
    }

    /** The captures [ids] names, in the timeline's order. */
    private fun heldCaptures(ids: Set<String>): List<VaultItem> =
        captureRows.mapNotNull { it.item }.filter { it.itemId in ids }

    // --- Move to vault — D5 --------------------------------------------------------------------

    private fun startCaptureMove(items: List<VaultItem>) {
        if (items.isEmpty()) return
        closeCaptureMove()
        captureNotice = null
        captureMoving = items
    }

    /** A vault picked on Move to vault: its passphrase sheet, or straight to its folders. */
    private fun pickMoveVault(entry: VaultKeyring.Entry) {
        captureMoveProblem = null
        // `per-vault-intruder-capture` D6. From a vault's timeline, that vault's key is the session's.
        if (entry.hasOwnPassphrase && !isSessionVault(entry)) {
            captureMoveFailed = false
            captureMovePrompt = entry
            return
        }
        openMoveFolders(entry, null)
    }

    /** The protected vault's passphrase, from its sheet. A wrong one stays on the sheet. */
    private fun unlockMoveVault(entry: VaultKeyring.Entry, passphrase: ByteArray) {
        captureMoveFailed = false
        captureMoveBusy = true
        thread {
            val kek = try {
                VaultKeyringSession.entryPassphraseKek(passphrase, entry.position, entry.kdfSet)
            } finally {
                passphrase.fill(0)
            }
            val folders = kek?.let { k ->
                VaultKeyringSession.withVaultKey(entry.position, k) { vmk, id -> MetadataDb.load(blobs, id, vmk).folders }
            }
            runOnUiThread {
                captureMoveBusy = false
                if (folders == null || captureMoving == null) {
                    kek?.fill(0)
                    captureMoveFailed = folders == null
                    // `per-vault-intruder-capture` D3. The same lock, so the same capture.
                    if (folders == null) fireVaultCaptures(entry.indexBlobId)
                    return@runOnUiThread
                }
                captureMovePrompt = null
                captureMoveKek?.fill(0)
                captureMoveKek = kek
                captureMoveTarget = entry
                captureMoveFolders = folders
            }
        }
    }

    /** Opens [entry] for the move — its key unsealed only to read its folders, then zeroed. */
    private fun openMoveFolders(entry: VaultKeyring.Entry, kek: ByteArray?) {
        captureMoveBusy = true
        thread {
            val folders = withMoveVaultKey(entry, kek) { vmk, id ->
                MetadataDb.load(blobs, id, vmk).folders
            }
            runOnUiThread {
                captureMoveBusy = false
                if (captureMoving == null) return@runOnUiThread
                if (folders == null) {
                    captureMoveProblem = getString(R.string.intruder_move_failed)
                    return@runOnUiThread
                }
                captureMoveTarget = entry
                captureMoveFolders = folders
            }
        }
    }

    /** Whether [entry] is the vault open in this session, from whose timeline a move was started. */
    private fun isSessionVault(entry: VaultKeyring.Entry): Boolean =
        captureVault && entry.position == VaultKeyringSession.openVaultPosition()

    /**
     * [VaultKeyringSession.withVaultKey], or — for the vault whose timeline the move started from —
     * the session's own key, which is not the block's to zero (D6).
     */
    private fun <T> withMoveVaultKey(entry: VaultKeyring.Entry, kek: ByteArray?, block: (ByteArray, ByteArray) -> T): T? {
        if (isSessionVault(entry)) {
            val open = VaultSession.openVault() ?: return null
            return block(open.vmk, open.metadataBlobId.copyOf())
        }
        return VaultKeyringSession.withVaultKey(entry.position, kek, block)
    }

    /** Back from the folder picker, to Move to vault. Nothing is written; the KEK goes. */
    private fun closeMoveFolders() {
        captureMoveFolders = null
        captureMoveTarget = null
        captureMoveProblem = null
        captureMoveKek?.fill(0)
        captureMoveKek = null
    }

    /** Leaves the move at whatever stage it is. Nothing is written; no vault key is left. */
    private fun closeCaptureMove() {
        closeMoveFolders()
        captureMovePrompt = null
        captureMoveFailed = false
        captureMoving = null
    }

    /**
     * The move's commit, in D5's order: every capture is sealed into the vault and the vault's index
     * committed **before** any is removed from the store, so an interruption leaves a copy in both
     * places and never in neither. Refused, before anything is written, wherever an import is.
     */
    private fun commitCaptureMove(folderId: ByteArray?) {
        val items = captureMoving ?: return
        val target = captureMoveTarget ?: return
        val key = captureKey ?: return
        if (captureMoveBusy) return
        if (!Entitlement.importAllowed(this)) {
            captureMoveProblem = getString(Entitlement.BLOCKED)
            return
        }
        val kek = captureMoveKek
        // The vault's name at this moment, which the `0x47` keeps — D6.
        val name = vaultEntries.firstOrNull { it.position == target.position }?.name ?: target.name
        captureMoveBusy = true
        captureMoveProblem = null
        thread {
            val moved = withMoveVaultKey(target, kek) { vmk, indexId ->
                // Every blob ID each write claims, so a failure part-way removes what it wrote.
                val claimed = mutableListOf<ByteArray>()
                try {
                    val now = System.currentTimeMillis()
                    val rows = items.map { item ->
                        val plain = blobs.reader(item.blobIds, key, item.plaintextLength).use {
                            it.readRange(0, item.plaintextLength.toInt())
                        }
                        val out = try {
                            blobs.writeItem(java.io.ByteArrayInputStream(plain), vmk, claim = { claimed += it })
                        } finally {
                            plain.fill(0)
                        }
                        VaultItem(
                            blobIds = out.blobIds,
                            plaintextLength = out.plaintextLength,
                            // The vault backfills it on its next open, as for any thumbless item.
                            thumb = null,
                            filename = item.filename,
                            album = null,
                            folderId = folderId,
                            createdAt = item.createdAt,
                            importedAt = now,
                            tags = emptyList(),
                        )
                    }
                    MetadataDb.commit(blobs, indexId, vmk) { db -> db.items += rows }
                    true
                } catch (e: Exception) {
                    Log.w("VaultCapture", "move failed: ${e.javaClass.simpleName}")
                    claimed.forEach { blobs.delete(it) }
                    false
                }
            } == true
            val removed = moved && runCatching {
                CaptureStore.remove(blobs, OpenVault(key, VaultKeys.metadataBlobId(key), -1), items, TamperLog.LEFT_MOVED, name)
            }.isSuccess
            runOnUiThread {
                captureMoveBusy = false
                if (!moved) {
                    captureMoveProblem = getString(R.string.intruder_move_failed)
                    return@runOnUiThread
                }
                // Into the vault on screen behind the timeline: its grid gains the items now.
                if (isSessionVault(target)) loadIndex()
                closeCaptureMove()
                captureOpen = null
                captureSelection = emptySet()
                captureNotice = getString(R.string.intruder_left_moved, name)
                // `one-time-purchase` D4. Moving into a vault is an add.
                Entitlement.startTrialIfNeeded(this)
                if (!removed) Log.w("VaultCapture", "moved, but still in the store")
                reloadCaptures()
            }
        }
    }

    // --- Export and delete — D9, D10 -----------------------------------------------------------

    private fun startCaptureExport(items: List<VaultItem>) {
        if (items.isEmpty()) return
        captureNotice = null
        exportCaptures = true
        exportSelection = items.mapTo(HashSet()) { it.itemId }
        exporting = true
    }

    /** Confirmed: one store commit removes them and records each as deleted. */
    private fun deleteCaptures(items: List<VaultItem>) {
        val key = captureKey ?: return
        captureDeleting = null
        thread {
            val ok = runCatching {
                CaptureStore.remove(blobs, OpenVault(key, VaultKeys.metadataBlobId(key), -1), items, TamperLog.LEFT_DELETED)
            }.isSuccess
            runOnUiThread {
                if (captureKey !== key) return@runOnUiThread
                if (ok) {
                    captureOpen = null
                    captureSelection = emptySet()
                    captureNotice = getString(R.string.intruder_left_deleted)
                }
                reloadCaptures()
            }
        }
    }

    private fun openCaptureSheet() {
        captureProblem = null
        captureModes = enabledCaptureModes()
        captureSheet = true
    }

    /**
     * The open vault's name, for the surfaces that must say which vault they change.
     *
     * From the keyring entry, so no key is unwrapped to label a sheet. The fallback
     * covers the frame in which the session and the composition disagree during a
     * lock.
     */
    private fun openVaultName(): String =
        vaultEntries.firstOrNull { it.position == VaultKeyringSession.openVaultPosition() }
            ?.name
            ?: getString(R.string.this_vault)

    /**
     * The step's screen — purpose-built for first run, **not** the Settings screen.
     *
     * The first version of this flow hosted `CaptureScreen`, `DecoyScreen`,
     * `BackupScreen`, `PanicScreen` and `IdentityScreen` directly. Those are
     * reference material for someone who went looking in Settings: dense, neutral,
     * mechanism-first. Shown in sequence they read as a settings menu with a
     * counter on it, which is exactly what it was. Settings keeps them unchanged;
     * these are written for someone who has never seen the app.
     *
     * The heavy steps still hand off to the real enrolment screens — a decoy needs
     * a photo picker and two passphrases, an identity needs its rehearsal — but the
     * step that *introduces* them is its own.
     */
    /** `switch-builds-without-losing-vaults` D4. "From family.vault · 214 items", or null. */
    @Composable
    private fun restoredLine(): String? = heldFrom?.let { (name, items) ->
        pluralStringResource(R.plurals.restore_from_file, items, name, items)
    }

    @Composable
    private fun OnboardingStepContent(step: Onboarding.Step) {
        when (step) {
            Onboarding.Step.Name -> NameStep(
                name = firstName,
                onName = {
                    firstName = it
                    firstNameError = null
                },
                appearance = firstAppearance,
                onAppearance = { firstAppearance = it },
                accepts = VaultKeyring::fits,
                error = firstNameError,
                onContinue = { if (firstNameContinue()) advanceOnboarding(Onboarding.Step.Passphrase) },
                restored = restoredLine(),
            )

            Onboarding.Step.Passphrase -> PassphraseStepScreen(
                onCreate = { bytes -> createVault(bytes, firstName.trim(), firstAppearance) },
                busy = busy,
                progress = setupProgress,
                problem = setupProblem,
            )

            // `recovery-practice` D5. Two screens in one step: the words, then the check, which
            // always runs here — the switch that turns it off is not reachable yet.
            Onboarding.Step.Kit -> if (kitChecking) {
                KitCheckScreen(
                    mnemonic = mnemonic,
                    words = recoveryKit.orEmpty(),
                    onCorrect = {
                        kitPassed {
                            recoveryKit = null
                            loadIndex()
                            completeOnboardingStep()
                        }
                    },
                    onSeeWords = ::kitSeeWords,
                    framing = KitCheckFraming.Onboarding,
                )
            } else {
                RecoveryKitStepScreen(
                    words = recoveryKit.orEmpty(),
                    onPrint = { printRecoveryKit(recoveryKit.orEmpty(), KitOwner.List()) },
                    acknowledged = kitAcknowledged,
                    onContinue = { kitContinue(gated = false) {} },
                )
            }

            Onboarding.Step.Fingerprint -> FingerprintStepScreen(
                layout = biometricLayout(),
                state = biometricState(),
                hasFingerprint = bioHasFingerprint,
                hasFace = bioHasFace,
                // The row is a switch, so it turns off as well as on; off raises nothing.
                onToggle = ::toggleBiometric,
                onSetUp = ::openBiometricEnrol,
                onContinue = ::completeOnboardingStep,
            )

            Onboarding.Step.Capture -> CaptureStepScreen(
                enabled = captureModes,
                hasFrontCamera = IntruderCapture.hasFrontCamera(this@MainActivity),
                onEnable = ::enableCaptureMode,
                onDisable = ::disableCaptureMode,
                problem = captureProblem,
                onContinue = ::completeOnboardingStep,
                autoResetRisk = !autoRevokeExempt,
                onKeepPermissions = ::openAutoRevokeSettings,
            )

            Onboarding.Step.Decoy -> DecoyStepScreen(
                // **Not the keyring's vault count.** That was the test here and it
                // was wrong in both directions: a decoy is a second *slot* with a
                // keyring of its own, so after the flow the session is on that
                // keyring and its count is 1 — the step redrew as untouched and read
                // as a failure. And a user who had added a second vault from the
                // list read as configured without ever having built a decoy.
                //
                // Still not a stored flag: that is the tell the decoy exists to
                // avoid, and no step may record what it enrolled. This is in memory
                // for this process only: a restart that resumes this step draws it
                // as untouched, and its "Not now" still moves the flow forward.
                configured = decoyBuiltThisRun,
                // `distribution-flavors`: no trial on `github`, so no trial fact.
                showsTrial = BuildConfig.TRIAL,
                onSetUp = ::openDecoy,
                onContinue = ::completeOnboardingStep,
            )

            // The size is read here, when the step is reached, so a decoy built at
            // step 5 is in the figure. That is the whole reason backup follows it.
            Onboarding.Step.Backup -> BackupStepScreen(
                status = backupStatus ?: BackupStatus.Checking,
                folderName = mirrorDestination,
                storeBytes = storeBytes(),
                problem = mirrorProblem,
                destinations = { CloudMirror.destinations(this) },
                onChooseDestination = ::chooseDestination,
                onContinue = ::completeOnboardingStep,
            )

            Onboarding.Step.Panic -> PanicStepScreen(
                enabled = panicEnabled,
                onSetUp = { panicProblem = null; panicScreen = true },
                backupConfigured = CloudMirror.destination(index) != null,
                onContinue = ::completeOnboardingStep,
            )

            Onboarding.Step.Identity -> IdentityStepScreen(
                onChoose = { identityScreen = true },
                onContinue = ::completeOnboardingStep,
            )
        }
    }

    // --- the mirror (`cloud-mirror`) ------------------------------------------

    private fun blobsDir() = File(filesDir, "blobs")

    /**
     * The blobs this vault's index references — `settings-separation`. Null with no
     * vault open, which is a vault that is not mirrored.
     */
    private fun mirrorNames(): Set<String>? {
        val id = VaultSession.metadataBlobId() ?: return null
        // `per-vault-intruder-capture`. The vault's own capture store travels with it.
        val captures = VaultSession.vmk()?.let { CloudMirror.captureStoreOf(blobs, it) }
        return CloudMirror.vaultNames(index, id, VaultKeyringSession.keyringBlobId(), captures)
    }

    /**
     * What this vault would put in a folder, in bytes — not what the store holds.
     *
     * The screen says "this vault is currently N on disk", and since the mirror
     * carries one vault's blobs that sentence has to be about one vault's blobs.
     */
    private fun storeBytes(): Long {
        val names = mirrorNames() ?: return 0L
        return CloudMirror.localEntries(blobsDir())
            .filterKeys { it in names }
            .values.sumOf { it.size }
    }

    private fun openBackup() {
        // `switch-builds-without-losing-vaults` D5. Choosing again lets a held vault go.
        if (!hasVault) discardHeldArchive()
        clearRestoreFile()
        mirrorProblem = null
        restoringBackup = false
        restoreState = RestoreState.Idle
        backupScreen = true
        refreshBackupStatus()
    }

    private fun closeBackup() {
        // A `.vault` being read is not left half read.
        if (restoreFile != null && restoreState is RestoreState.Copying) return
        clearRestoreFile()
        backupScreen = false
        restoringBackup = false
        mirrorProblem = null
        restoreState = RestoreState.Idle
    }

    /**
     * A row of the "Back up to" list — `backup-to-cloud-apps` D1, D2. A cloud app opens its
     * Save sheet; a removable volume opens the folder picker at its root.
     */
    private fun chooseDestination(destination: CloudMirror.Destination) {
        restoringBackup = false
        mirrorProblem = null
        AutoLock.awaitingResult(true)
        when (destination) {
            is CloudMirror.Destination.CloudApp -> {
                backupAuthority = destination.authority
                createBackupFile.launch(BACKUP_FILE_NAME)
            }
            is CloudMirror.Destination.Volume -> pickVolumeTree.launch(destination.intent)
        }
    }

    /**
     * Stores [uri] as this vault's destination, handing back the grant on the one it
     * replaces. The old backup is left where it is, as "Stop backing up" leaves it.
     */
    private fun replaceDestination(uri: android.net.Uri) {
        val old = CloudMirror.destination(index)
        commitIndex { (id, vmk) ->
            MetadataDb.commit(blobs, id, vmk) { db ->
                CloudMirror.setDestination(db, uri)
                db
            }
        }
        if (old != null && old != uri) CloudMirror.releaseGrant(this, old)
    }

    /** `backup-to-cloud-apps` D2. The saved file is the destination; it is written now. */
    private fun adoptBackupFile(uri: android.net.Uri) {
        if (mirrorNames() == null) {
            mirrorProblem = getString(R.string.backup_open_vault_first_file)
            CloudMirror.releaseGrant(this, uri)
            return
        }
        replaceDestination(uri)
        mirrorNow()
    }

    private fun pickMirrorFolder(restore: Boolean, pick: BackupPick = BackupPick.RESTORE) {
        restoringBackup = restore
        backupPick = pick
        mirrorProblem = null
        AutoLock.awaitingResult(true)
        pickMirrorTree.launch(null)
    }

    /**
     * Where the open vault's backup stands — `backup-screen-status-first` D1, D2.
     *
     * Lists the folder and compares it with this vault's files: the comparison a run
     * makes, counted instead of carried out. Nothing is stored. A folder that cannot
     * be listed is [BackupStatus.Unreachable], never "everything to copy" — the
     * listing says which it is.
     *
     * A run in progress owns the status until it ends, and a vault left or locked
     * before the listing answers is not written into.
     */
    private fun refreshBackupStatus() {
        val id = VaultSession.metadataBlobId() ?: return
        val names = mirrorNames() ?: return
        val tree = CloudMirror.destination(index)
        if (tree == null) {
            mirrorDestination = null
            backupStatus = BackupStatus.Off
            return
        }
        if (backupStatus !is BackupStatus.Copying) backupStatus = BackupStatus.Checking
        if (CloudMirror.isFile(tree)) {
            refreshFileStatus(tree, names, id)
            return
        }
        thread {
            val label = CloudMirror.folderLabel(this, tree)
            val listing = runCatching { SafDestination(this, tree).listing() }.getOrNull()
            val status = if (listing == null) {
                BackupStatus.Unreachable()
            } else {
                val local = CloudMirror.localEntries(blobsDir()).filterKeys { it in names }
                val here = CloudMirror.status(
                    local, listing.mapValues { CloudMirror.Entry(it.value.size, it.value.modified) },
                )
                if (here.pending == 0) BackupStatus.UpToDate(here.lastCopied)
                else BackupStatus.Behind(here.pending, here.lastCopied)
            }
            runOnUiThread {
                if (VaultSession.metadataBlobId()?.contentEquals(id) != true) return@runOnUiThread
                if (backupStatus is BackupStatus.Copying) return@runOnUiThread
                mirrorDestination = label
                backupStatus = status
            }
        }
    }

    /**
     * [refreshBackupStatus] for a cloud app's backup file — `backup-to-cloud-apps` D5.
     *
     * The file's own modified time and size against this vault's blobs: a blob newer than
     * the file is a change still to copy, and a file of the wrong size is one whose write
     * did not finish. Nothing is stored.
     */
    private fun refreshFileStatus(uri: android.net.Uri, names: Set<String>, id: ByteArray) {
        thread {
            val info = CloudMirror.fileInfo(this, uri)
            val label = CloudMirror.fileLabel(this, uri, info?.name)
            val vmk = VaultSession.vmk()
            val record = vmk?.let { BackupFile.readRecord(blobs, it) }
            // A fresh instance: the digest takes the chaff list out and puts it back.
            val digest = vmk?.let { runCatching { BackupFile.contentDigest(MetadataDb.load(blobs, id, it)) }.getOrNull() }
                ?: ByteArray(0)
            val status = if (info == null) {
                BackupStatus.Unreachable(file = true)
            } else {
                val local = CloudMirror.localEntries(blobsDir()).filterKeys { it in names }
                val here = BackupFile.status(local, record, info.size, hex(id), digest)
                if (here.pending == 0) BackupStatus.UpToDate(here.lastCopied)
                else BackupStatus.Behind(here.pending, here.lastCopied)
            }
            runOnUiThread {
                if (VaultSession.metadataBlobId()?.contentEquals(id) != true) return@runOnUiThread
                if (backupStatus is BackupStatus.Copying) return@runOnUiThread
                mirrorDestination = label
                backupStatus = status
            }
        }
    }

    /**
     * `cloud-mirror` D9. A folder holding anything that is not one of our blobs is
     * another install's mirror, and mirror semantics would delete it. Refused
     * before the destination is stored, so nothing is written and mirroring stays
     * off.
     */
    private fun adoptMirrorFolder(tree: android.net.Uri) {
        val names = mirrorNames()
        if (names == null) {
            mirrorProblem = getString(R.string.backup_open_vault_first_folder)
            return
        }
        val before = backupStatus
        backupStatus = BackupStatus.Checking
        thread {
            val local = CloudMirror.localEntries(blobsDir()).keys
            val remote = SafDestination(this, tree).names().keys
            val refusal = CloudMirror.destinationRefusal(names, local, remote)
            runOnUiThread {
                // `settings-separation`. Two refusals, because the remedies differ:
                // one folder per vault, and one folder per install.
                mirrorProblem = when (refusal) {
                    CloudMirror.Refusal.AnotherVault ->
                        getString(R.string.backup_folder_another_vault)
                    CloudMirror.Refusal.AnotherInstall ->
                        getString(R.string.backup_folder_another_install)
                    null -> null
                }
                if (refusal != null) {
                    // Nothing was stored, so the vault stands where it stood.
                    backupStatus = before
                    return@runOnUiThread
                }
                replaceDestination(tree)
                mirrorNow()
            }
        }
    }

    /**
     * One run, on screen — `backup-screen-status-first` D9.
     *
     * The folder is listed first: [CloudMirror.run] folds an unreachable folder into
     * an empty one and would report every file as failed, which is the wrong fault.
     * Otherwise the status follows the run file by file, and the end is either the
     * files that did not transfer or a fresh listing.
     */
    private fun mirrorNow() {
        val tree = CloudMirror.destination(index) ?: return
        val names = mirrorNames() ?: return
        val id = VaultSession.metadataBlobId() ?: return
        mirrorProblem = null
        backupStatus = BackupStatus.Copying(0, 0, bytes = CloudMirror.isFile(tree))
        // `backup-to-cloud-apps` D4. A backup file is the whole vault, written by the
        // foreground worker so the person can leave; [watchMirror] follows its progress.
        if (CloudMirror.isFile(tree)) {
            MirrorWorker.enqueueNow(this)
            return
        }
        thread {
            val label = CloudMirror.folderLabel(this, tree)
            val reachable = runCatching { SafDestination(this, tree).listing() }.getOrNull() != null
            val result = if (!reachable) null else runCatching {
                CloudMirror.run(this, tree, blobsDir(), names) { done, total ->
                    runOnUiThread {
                        if (backupStatus is BackupStatus.Copying) {
                            backupStatus = BackupStatus.Copying(done.toLong(), total.toLong())
                        }
                    }
                }
            }.getOrNull()
            runOnUiThread {
                if (VaultSession.metadataBlobId()?.contentEquals(id) != true) return@runOnUiThread
                mirrorDestination = label
                when {
                    result == null -> backupStatus = BackupStatus.Unreachable()
                    result.failed > 0 -> backupStatus = BackupStatus.Partial(result.failed)
                    else -> {
                        backupStatus = BackupStatus.Checking
                        refreshBackupStatus()
                    }
                }
            }
        }
    }

    private fun turnOffMirror() {
        commitIndex { (id, vmk) ->
            MetadataDb.commit(blobs, id, vmk) { db ->
                CloudMirror.clearDestination(this, db)
                db
            }
        }
        mirrorDestination = null
        mirrorProblem = null
        backupStatus = BackupStatus.Off
    }

    /**
     * `cloud-mirror` restore. Copies a mirror into an empty store, then hands off
     * to the **existing** recovery flow — the 24 words rebuild the slot array from
     * CSPRNG, which is why the slot file never needed uploading.
     */
    private fun adoptRestoreFolder(tree: android.net.Uri) {
        restoringBackup = false
        // `backup-to-cloud-apps` D13. With the list open, the folder is one of its vaults.
        if (VaultKeyringSession.isOpen) {
            restoreFolderIntoList(tree)
            return
        }
        if (CloudMirror.localEntries(blobsDir()).isNotEmpty()) {
            restoreState = RestoreState.Problem(
                getString(R.string.restore_device_not_empty)
            )
            return
        }
        // Listed first so a folder with no backup in it says so here; the copy itself runs on
        // Recover's step 2, once the words have opened it.
        thread {
            val hasBackup = runCatching {
                SafDestination(this, tree).names().keys.any(::isBlobName)
            }.getOrDefault(false)
            val label = CloudMirror.folderLabel(this, tree)
            runOnUiThread {
                if (!hasBackup) {
                    restoreState = RestoreState.Problem(getString(R.string.restore_no_backup_in_folder))
                    return@runOnUiThread
                }
                hasVault = lifecycleOps.vaultExists()
                restoreOntoEmpty(ChosenBackup(tree, folder = true, name = label))
            }
        }
    }

    /**
     * `backup-screen-status-first` D12. The drawer's Backup row says where the open
     * vault stands without being opened, so a run the worker finished in the
     * background is listed again when it ends. Each finished run is refreshed for
     * once: the LiveData carries a week of history and re-emits on every change.
     */
    private val seenMirrorRuns = mutableSetOf<java.util.UUID>()
    private var mirrorHistoryRead = false

    /**
     * `backup-to-cloud-apps` D4. A backup file run reports bytes as progress and its
     * ending as output — no space, unreachable, written — and the status follows both. The
     * first emission is history from earlier sessions and is only remembered, never shown:
     * a result from a vault no longer open is not this vault's.
     */
    private fun watchMirror() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(MirrorWorker.MIRROR_TAG)
            .observe(this) { infos ->
                if (!mirrorHistoryRead) {
                    mirrorHistoryRead = true
                    infos.filter { it.state.isFinished }.forEach { seenMirrorRuns.add(it.id) }
                }
                if (VaultSession.metadataBlobId() == null) return@observe

                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let { p ->
                    val total = p.getLong(MirrorWorker.PROGRESS_TOTAL, -1L)
                    if (total > 0) {
                        backupStatus = BackupStatus.Copying(
                            p.getLong(MirrorWorker.PROGRESS_DONE, 0L), total, bytes = true,
                        )
                    }
                }

                val finished = infos.filter {
                    it.state == WorkInfo.State.SUCCEEDED && seenMirrorRuns.add(it.id)
                }
                val last = finished.lastOrNull() ?: return@observe
                when (last.outputData.getString(MirrorWorker.RESULT)) {
                    MirrorWorker.RESULT_NO_SPACE ->
                        backupStatus = BackupStatus.NoSpace(last.outputData.getLong(MirrorWorker.NEEDED, 0L))
                    MirrorWorker.RESULT_UNREACHABLE ->
                        backupStatus = BackupStatus.Unreachable(file = true)
                    else -> {
                        if (backupStatus is BackupStatus.Copying) backupStatus = BackupStatus.Checking
                        refreshBackupStatus()
                    }
                }
            }
    }

    /**
     * P1-14. Re-authenticates, then asks where the archive goes.
     *
     * The passphrase is held in memory across the picker round-trip and taken once
     * by the worker — it cannot go in the worker's input, which WorkManager writes
     * to disk. If the user cancels the picker it is cleared.
     */
    private fun startArchiveExport(authorising: ByteArray, forTheFile: ByteArray) {
        busy = true
        exportAuthFailed = false
        thread {
            // `protected-vault-destroy-gate` D8. The passphrase that opens this vault authorises,
            // through a real derivation: its own where it has one, the list's otherwise. The
            // archive is sealed under the other one.
            val ok = try {
                ExportAuth.confirmsOpenVault(File(filesDir, "vault.slots"), authorising)
            } finally {
                busy = false
            }
            runOnUiThread {
                if (!ok) {
                    forTheFile.fill(0)
                    exportAuthFailed = true
                    return@runOnUiThread
                }
                ArchivePassphrase.offer(forTheFile)
                AutoLock.awaitingResult(true)
                createArchive.launch("vault-${System.currentTimeMillis()}.vault")
            }
        }
    }

    private fun startArchive(target: Uri) {
        val request = OneTimeWorkRequestBuilder<ArchiveWorker>()
            // The same tag P1-13 uses: auto-lock's rule is long-running work
            // the user can see in the shade, and this is that, not a third kind.
            .addTag(ExportWorker.EXPORT_TAG)
            .build()
        // Beside the passphrase, in memory. The destination carries a filename the
        // user chose, and WorkManager would have written it down.
        WorkJobs.register(request.id, WorkJobs.ArchiveJob(target))
        WorkManager.getInstance(this).enqueue(request)
        exporting = false
    }

    private fun pickArchiveFile() {
        AutoLock.awaitingResult(true)
        openArchive.launch(arrayOf("*/*"))
    }

    /**
     * `backup-file-recover-and-import`, D3 and D4. A file or folder chosen on Import, read for
     * its kind: a `.vault` or a backup by the file's structure, never its name; then a backup
     * of this list if the open list's key opens it, and another list's otherwise. A folder is
     * a backup by the route it came in.
     */
    private fun importChose(uri: Uri, folder: Boolean) {
        archiveFile = uri
        importFolder = folder
        archiveFileName = if (folder) CloudMirror.folderLabel(this, uri) else displayName(uri)
        resetImportOutcome()
        importKind = null
        thread {
            val kind = runCatching {
                if (folder) {
                    val own = VaultKeyringSession.keyringBlobId()?.let { hex(it) }
                    if (own != null && own in SafDestination(this, uri).names()) ImportKind.THIS_LIST
                    else ImportKind.OTHER_LIST
                } else {
                    when {
                        !isBackupFile(uri) -> ImportKind.VAULT_FILE
                        opensWithThisList(uri) -> ImportKind.THIS_LIST
                        else -> ImportKind.OTHER_LIST
                    }
                }
            }.onFailure { Log.w("BackupRestore", "the chosen file could not be read", it) }.getOrNull()
            runOnUiThread {
                if (archiveFile != uri) return@runOnUiThread
                importKind = kind
                if (kind == null) importProblem = getString(UNREADABLE_BACKUP)
            }
        }
    }

    /** Whether the open list's own key opens the backup file at [uri]. */
    private fun opensWithThisList(uri: Uri): Boolean {
        val key = VaultKeyringSession.backupKey() ?: return false
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BackupFile.open(input, key)?.also { it.macKey.fill(0) } != null
            } ?: false
        } finally {
            key.fill(0)
        }
    }

    private fun resetImportOutcome() {
        archiveFailed = false
        archiveFull = false
        archiveBlocked = null
        importWordsFailed = false
        importNeedsPassphrase = false
        importInUse = false
        importNoRoom = false
        importSealing = false
        importProblem = null
    }

    /** D4.2. This list's backup: restored onto the open list, as the Restore row does it. */
    private fun importThisListBackup() {
        val uri = archiveFile ?: return
        resetImportOutcome()
        busy = true
        val progress: (Int, Int) -> Unit = { done, total -> archiveProgress = done to total }
        val finish: (String?) -> Unit = { problem ->
            busy = false
            archiveProgress = null
            if (problem == null) {
                closeArchiveImport()
                vaultEntries = VaultKeyringSession.vaults()
            } else {
                importProblem = problem
            }
        }
        if (importFolder) restoreFolderIntoList(uri, progress, finish)
        else restoreFileIntoList(uri, progress, finish)
    }

    /**
     * D4.3 and D4.4. Another list's backup, with that list's words: back to its list if it is
     * on this phone, or on to the passphrase step if it is not. Nothing is written until the
     * words open the backup.
     */
    private fun importWithWords(phrase: String) {
        val uri = archiveFile ?: return
        val key = mnemonic.decode(phrase) ?: run { importWordsFailed = true; return }
        resetImportOutcome()
        busy = true
        val source = backupSource(uri, importFolder)
        thread {
            val outcome = runCatching {
                lifecycleOps.restoreIntoList(key, source) { done, total ->
                    runOnUiThread { archiveProgress = done to total }
                }
            }.onFailure {
                key.fill(0)
                Log.w("BackupRestore", "the backup could not be read", it)
            }.getOrNull()
            runOnUiThread {
                busy = false
                archiveProgress = null
                when {
                    outcome == null -> importProblem = getString(UNREADABLE_BACKUP)
                    outcome.opened -> {
                        Entitlement.startTrialIfNeeded(this) // `one-time-purchase` D4
                        importDone = ImportDone.VAULT_RESTORED
                    }
                    outcome.refusal == VaultLifecycle.Refusal.LIST_NOT_HERE -> importNeedsPassphrase = true
                    outcome.refusal == VaultLifecycle.Refusal.BACKUP_INCOMPLETE -> importProblem = getString(INCOMPLETE_BACKUP)
                    else -> importWordsFailed = true
                }
            }
        }
    }

    /** D4.5. Another list's backup, its list not on this phone: it comes back as that list. */
    private fun importAsList(phrase: String, newPassphrase: ByteArray) {
        val uri = archiveFile ?: return newPassphrase.fill(0)
        val key = mnemonic.decode(phrase) ?: run {
            newPassphrase.fill(0)
            importNeedsPassphrase = false
            importWordsFailed = true
            return
        }
        importInUse = false
        importNoRoom = false
        importProblem = null
        busy = true
        val source = backupSource(uri, importFolder)
        thread {
            val outcome = runCatching {
                lifecycleOps.restoreAsList(
                    key, source, newPassphrase,
                    { done, total -> runOnUiThread { archiveProgress = done to total } },
                    onSealing = { runOnUiThread { importSealing = true } },
                )
            }.onFailure { Log.w("BackupRestore", "the backup could not be read", it) }.getOrNull()
            runOnUiThread {
                busy = false
                archiveProgress = null
                importSealing = false
                when {
                    outcome?.opened == true -> {
                        Entitlement.startTrialIfNeeded(this) // `one-time-purchase` D4
                        importDone = ImportDone.LIST_RESTORED
                    }
                    outcome?.refusal == VaultLifecycle.Refusal.PASSPHRASE_IN_USE -> importInUse = true
                    outcome?.refusal == VaultLifecycle.Refusal.NO_ROOM -> importNoRoom = true
                    outcome?.refusal == VaultLifecycle.Refusal.BACKUP_NOT_OPENED -> {
                        importNeedsPassphrase = false
                        importWordsFailed = true
                    }
                    else -> {
                        importNeedsPassphrase = false
                        importProblem =
                            getString(
                                if (outcome?.refusal == VaultLifecycle.Refusal.BACKUP_INCOMPLETE) INCOMPLETE_BACKUP
                                else UNREADABLE_BACKUP
                            )
                    }
                }
            }
        }
    }

    private fun finishImport() {
        closeArchiveImport()
        vaultEntries = VaultKeyringSession.vaults()
    }

    /**
     * P1-14. Imports an archive into the open list — `recovery-own-slot-only`, D5.
     *
     * One outcome for every failure — a wrong passphrase, a corrupt file and a
     * file that was never an archive are indistinguishable, by design. A full list
     * is its own message, because it says how to make room.
     */
    private fun importArchive(archivePassphrase: ByteArray) {
        val source = archiveFile ?: return
        // P1-16. An archive import is an import and it creates a vault, so it is
        // gated with the other two. The check is here rather than on the screen or
        // the file picker so the screen keeps its shape in every state and the
        // archive is left unread and unconsumed. Archive *export* takes no check —
        // that half is a read, and I4 forbids gating it.
        if (!Entitlement.importAllowed(this)) {
            archivePassphrase.fill(0)
            archiveBlocked = getString(Entitlement.BLOCKED)
            return
        }
        busy = true
        archiveFailed = false
        archiveFull = false
        archiveBlocked = null
        thread {
            val outcome = try {
                contentResolver.openInputStream(source)!!.use { input ->
                    lifecycleOps.importArchive(input, archivePassphrase, getString(R.string.imported_vault)) { done, total ->
                        archiveProgress = done to total
                    }
                }
            } catch (e: Exception) {
                Log.w("VaultArchive", "import failed", e)
                archivePassphrase.fill(0)
                VaultLifecycle.Outcome(opened = false, derivations = 0, slotsAttempted = 0)
            } finally {
                busy = false
                archiveProgress = null
            }
            runOnUiThread {
                when {
                    // Back onto the list, where the new vault is the last row. No vault
                    // is open after an import, so `unlocked` stays false: setting it drew
                    // a grid over nothing.
                    outcome.opened -> {
                        // `one-time-purchase` D4. An archive import adds a vault's items.
                        Entitlement.startTrialIfNeeded(this)
                        closeArchiveImport()
                        vaultEntries = VaultKeyringSession.vaults()
                    }
                    outcome.refusal == VaultLifecycle.Refusal.NO_ROOM -> archiveFull = true
                    else -> archiveFailed = true
                }
            }
        }
    }

    /**
     * P1-13. The passphrase is re-derived for real before anything leaves — see
     * [ExportAuth]. Never a fingerprint, never a device PIN.
     */
    private fun startExport(passphrase: ByteArray, target: ExportTarget, stripExif: Boolean) {
        busy = true
        exportAuthFailed = false
        thread {
            val captures = exportCaptures
            // `per-vault-intruder-capture` D6. A vault's captures are under the vault's own
            // export confirmation, as everything else exported from it is.
            val vaultCaptures = captures && captureVault
            val homeCaptures = captures && !vaultCaptures
            val authorised = exportAuthorised
            val ok = try {
                when {
                    // `protected-vault-destroy-gate` D5. The return run: the gate derived this
                    // vault's passphrase moments ago, for this vault, and this screen asked none.
                    authorised -> true.also { passphrase.fill(0) }
                    // The home store belongs to the list, so the list's passphrase opens it.
                    homeCaptures -> ExportAuth.confirms(File(filesDir, "vault.slots"), passphrase)
                    // D8. A vault, its selection or its own captures: the passphrase that opens it.
                    else -> ExportAuth.confirmsOpenVault(File(filesDir, "vault.slots"), passphrase)
                }
            } finally {
                busy = false
            }
            runOnUiThread {
                if (!ok) {
                    exportAuthFailed = true
                    return@runOnUiThread
                }
                // `intruder-captures-at-home` D9. The store's index, from the key the screen holds.
                val id = if (captures) {
                    captureKey?.let { VaultKeys.metadataBlobId(it) }
                } else {
                    VaultSession.metadataBlobId()
                } ?: return@runOnUiThread
                val request = OneTimeWorkRequestBuilder<ExportWorker>()
                    .addTag(ExportWorker.EXPORT_TAG)
                    .build()
                WorkJobs.register(
                    request.id,
                    WorkJobs.ExportJob(
                        id,
                        if (target == ExportTarget.Folder) exportTree else null,
                        // `exif-strip-choice`. What the strip removes, fixed for this export.
                        if (stripExif) ExifPolicy.resolve(photoDetailsPreset, photoDetailsCustom) else null,
                        // P2-11. Null for the whole vault. In memory only — an
                        // `itemId` is a blob ID in hex, and WorkManager writes a
                        // request's input `Data` to disk.
                        exportSelection,
                        captureStore = captures && !vaultCaptures,
                        vaultCaptureStore = vaultCaptures,
                    )
                )
                WorkManager.getInstance(this).enqueue(request)
                if (authorised) returnStarted = true
                // Those rows are leaving. A selection of departed items is a set
                // the user can no longer see, and both its actions are
                // destructive.
                selection = emptySet()
                if (captures) {
                    captureSelection = emptySet()
                    captureOpen = null
                }
            }
        }
    }

    /** Progress from WorkManager, so it survives leaving the screen. */
    private fun watchExports() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(ExportWorker.EXPORT_TAG)
            .observe(this) { infos ->
                val running = infos.firstOrNull { !it.state.isFinished }
                val data = running?.progress ?: infos.lastOrNull()?.outputData
                exportProgress = data?.let {
                    val total = it.getInt(ExportWorker.PROGRESS_TOTAL, 0)
                    if (total == 0 && running == null) null
                    else Triple(
                        it.getInt(ExportWorker.PROGRESS_DONE, 0),
                        total,
                        it.getInt(ExportWorker.PROGRESS_FAILED, 0),
                    )
                }
                if (running == null && infos.any { it.state.isFinished }) {
                    // `intruder-captures-at-home`. A capture export's rows now read "Exported".
                    if (intruderScreen) reloadCaptures()
                    loadIndex()
                    finishReturnThenDestroy()
                }
            }
    }

    /**
     * The second half of [returnThenDestroy] — `delete-vault` D2.
     *
     * **The test is the index, not the worker's counters.** `Unvaulter` removes an item
     * from the index only after writing it out, reading it back and comparing it, so an
     * empty index *is* the statement that everything came out. Reading a failure count
     * instead would trust a number about the work; this trusts the work.
     *
     * A vault with anything left is kept, and what is left is named as a count. Those
     * items are the only copy at that moment, and destroying them to honour a choice the
     * user made about a different outcome is the one thing this must never do.
     */
    private fun finishReturnThenDestroy() {
        val position = destroyAfterExport ?: return
        if (!returnStarted) return
        destroyAfterExport = null
        returnStarted = false

        // `protected-vault-destroy-gate` D5. The export ran on the open vault, which is the
        // target. A vault left in the meantime has an empty index only because it is closed,
        // and no key to destroy it with; it stays, whole.
        val key = VaultSession.vmk()?.takeIf { VaultKeyringSession.openVaultPosition() == position }?.copyOf() ?: return
        val left = index.items.size
        if (left > 0) {
            key.fill(0)
            destroyProblem = resources.getQuantityString(R.plurals.vault_destroy_items_left, left, left)
            return
        }
        val name = vaultEntries.firstOrNull { it.position == position }?.name ?: getString(R.string.that_vault)
        destroyVault(position, name, key, null)
    }

    /**
     * Prints the kit through the system print service — no permission, no
     * dependency.
     *
     * **The spooler keeps a copy.** It is a system component this app cannot
     * reach or wipe, which is recorded in `FORMAT.md` §10 rather than hidden.
     * Printing is what P1-12 asked for; the honest response is to build it and
     * say what it costs.
     */
    private fun printRecoveryKit(words: List<String>, owner: KitOwner) {
        if (words.isEmpty()) return
        (getSystemService(PRINT_SERVICE) as PrintManager).print(
            getString(R.string.kit_print_job),
            RecoveryKitPrintAdapter(this, words, owner, System.currentTimeMillis()),
            null,
        )
    }

    /**
     * Whose kit the app's kit branch is showing — `recovery-practice` D6. The list's own, a held
     * decoy kit (a list, under the decoy's name), or a vault's own kit at creation or in a view.
     */
    private fun heldKitOwner(): KitOwner = when {
        recoveryKitVault == null -> KitOwner.List()
        recoveryKitDecoy -> KitOwner.List(recoveryKitVault)
        else -> KitOwner.Vault(recoveryKitVault!!)
    }

    /**
     * Continue on a creation kit — `recovery-practice` D5. The check comes next when it runs:
     * always where [gated] is false (first run's step 2), otherwise while Extra Security is on.
     */
    private fun kitContinue(gated: Boolean, then: () -> Unit) {
        if (!gated || ExtraSecurity.isOn(this)) kitChecking = true else then()
    }

    /** The check passed: the kit's own Continue, as before the check existed. */
    private fun kitPassed(then: () -> Unit) {
        kitChecking = false
        kitAcknowledged = false
        then()
    }

    /** "See the words again" and Back: the same words, acknowledged, no view spent. */
    private fun kitSeeWords() {
        kitChecking = false
        kitAcknowledged = true
    }

    /** The app's kit branch's Continue at creation, after the check where it runs. */
    private fun putAwayCreationKit() {
        recoveryKit = null
        recoveryKitVault = null
        recoveryKitDecoy = false
        recoveryKitCreationLeft = VaultKeyring.KIT_VIEWS
        loadIndex()
        resumeOnboarding()
    }

    // --- passphrase practice — `recovery-practice` D2–D4 --------------------------------

    /**
     * D2. One derivation against the home slot through [VaultLifecycle.confirmsListPassphrase],
     * off the main thread, and **nothing else**: no tamper-log entry, no intruder capture, no
     * panic and no unlock limit. The panic phrase opens the panic slot, not the home one, so it
     * reads as wrong. A lock landing while it runs drops the answer unwritten.
     */
    private fun practiceCheck() {
        val state = practiceState
        if (state.checking || state.entry.text.isEmpty()) return
        val entry = state.entry.text.toByteArray(Charsets.UTF_8)
        state.checking = true
        thread {
            val right = runCatching { lifecycleOps.confirmsListPassphrase(entry) }.getOrDefault(false)
            runOnUiThread {
                if (!practiceShowing || !state.checking) return@runOnUiThread
                state.checking = false
                val now = System.currentTimeMillis()
                if (right) {
                    practice.recordRight(now, firstTry = !state.hadWrong)
                    val days = practice.nextInterval()
                    closePractice()
                    showMessage(resources.getQuantityString(R.plurals.practice_right, days, days))
                } else {
                    practice.recordWrong()
                    state.markWrong()
                }
            }
        }
    }

    /** D1. Skip, and Back on the practice screen: the step is kept, due again in 1 day. */
    private fun practiceSkip() {
        if (practiceState.checking) return
        practice.skip(System.currentTimeMillis())
        closePractice()
    }

    private fun practiceBack() {
        when (practiceState.stage) {
            PracticeStage.Check -> practiceSkip()
            PracticeStage.NewPassphrase -> if (!practiceState.saving) {
                practiceState.draft.clear()
                practiceState.confirmNeeded = false
                practiceState.stage = PracticeStage.Check
            }
        }
    }

    private fun closePractice() {
        practiceShowing = false
        practiceState.clear()
    }

    /**
     * D4. The screen has run the floor and the match; this raises a fresh strong-biometric
     * confirmation on the wrap's decrypt cipher, then seals the open list's home slot under the
     * new passphrase. A cancelled or failed prompt writes nothing and keeps the fields.
     */
    private fun practiceSave() {
        val state = practiceState
        if (state.saving) return
        state.confirmNeeded = false
        val cipher = try {
            bio.decryptCipher()
        } catch (e: Exception) {
            Log.i("VaultBio", "wrap unusable for the confirmation: ${e.javaClass.simpleName}")
            state.confirmNeeded = true
            return
        }
        authenticate(
            getString(R.string.practice_confirm_title),
            getString(R.string.action_cancel),
            cipher,
            onError = { if (practiceShowing) state.confirmNeeded = true },
        ) {
            if (!practiceShowing || state.stage != PracticeStage.NewPassphrase) return@authenticate
            val chosen = state.draft.new.toByteArray(Charsets.UTF_8)
            state.saving = true
            thread {
                val outcome = runCatching { lifecycleOps.resealListPassphrase(chosen) }.getOrElse {
                    Log.w("VaultKeyring", "the list passphrase could not be resealed", it)
                    chosen.fill(0)
                    VaultLifecycle.Outcome(opened = false, derivations = 0, slotsAttempted = 0)
                }
                runOnUiThread {
                    if (!practiceShowing) return@runOnUiThread
                    state.saving = false
                    when {
                        outcome.opened -> {
                            practice.start(System.currentTimeMillis())
                            closePractice()
                            showMessage(getString(R.string.practice_changed))
                        }
                        outcome.refusal == VaultLifecycle.Refusal.PASSPHRASE_IN_USE ->
                            state.draft.newError = getString(R.string.kit_passphrase_in_use)
                        // Nothing was written; the fields stay for another try.
                        else -> state.draft.newError = getString(R.string.panic_not_saved)
                    }
                }
            }
        }
    }

    /** A plain snackbar on the app's one host. */
    private fun showMessage(text: String) {
        snackbars.currentSnackbarData?.dismiss()
        lifecycleScope.launch { snackbars.showSnackbar(text) }
    }

    /** D7. The drawer's switch: on restarts practice at 1 day, off clears it. */
    private fun toggleExtraSecurity() {
        val on = !extraSecurityOn
        ExtraSecurity.set(this, on)
        extraSecurityOn = on
        if (on) {
            if (bio.hasRecord()) practice.start(System.currentTimeMillis())
        } else {
            practice.clear()
        }
    }

    /**
     * `panic-phrase`. Runs one panic-phrase operation off the main thread — each spends
     * one to three ~460 ms derivations — and puts its refusal under the field it is about.
     */
    private fun runPanic(op: () -> VaultLifecycle.PanicResult) {
        panicBusy = true
        panicProblem = null
        thread {
            val result = runCatching(op).getOrElse {
                Log.w("Panic", "a panic phrase operation failed", it)
                VaultLifecycle.PanicResult.NOT_SAVED
            }
            runOnUiThread {
                panicBusy = false
                panicEnabled = VaultKeyringSession.hasPanicPhrase()
                panicProblem = panicProblemFor(result)
                if (result == VaultLifecycle.PanicResult.DONE ||
                    result == VaultLifecycle.PanicResult.CLEARED
                ) {
                    panicSettled++
                }
            }
        }
    }

    /** D8's sentences, each under the field it concerns. */
    private fun panicProblemFor(result: VaultLifecycle.PanicResult): PanicProblem? = when (result) {
        VaultLifecycle.PanicResult.DONE -> null
        VaultLifecycle.PanicResult.WRONG_PASSPHRASE ->
            PanicProblem(PanicField.PASSPHRASE, getString(R.string.panic_wrong_passphrase))
        VaultLifecycle.PanicResult.WRONG_PANIC_PHRASE ->
            PanicProblem(PanicField.CURRENT, getString(R.string.panic_wrong_phrase))
        // The core's byte backstop, reached only if a phrase got past the screen's own
        // check — so it says what the screen would have said rather than quoting bytes.
        VaultLifecycle.PanicResult.TOO_SHORT ->
            PanicProblem(PanicField.PHRASE, getString(R.string.passphrase_too_weak))
        VaultLifecycle.PanicResult.TOO_CLOSE -> PanicProblem(
            PanicField.PHRASE,
            getString(R.string.panic_too_close),
        )
        VaultLifecycle.PanicResult.IN_USE ->
            PanicProblem(PanicField.PHRASE, getString(R.string.panic_in_use))
        VaultLifecycle.PanicResult.NO_ROOM ->
            PanicProblem(PanicField.ACTION, getString(R.string.panic_no_room))
        VaultLifecycle.PanicResult.CLEARED -> PanicProblem(
            PanicField.ACTION,
            getString(R.string.panic_cleared),
        )
        VaultLifecycle.PanicResult.NOT_SAVED ->
            PanicProblem(PanicField.ACTION, getString(R.string.panic_not_saved))
    }

    // --- intruder capture (P2-4) ----------------------------------------------

    /**
     * The modes on **for this keyring** — `intruder-captures-at-home` D8. A mode reads on only when
     * it is enabled and the open keyring is the destination; on any other keyring every switch
     * reads off, and nothing says another destination exists.
     */
    private fun enabledCaptureModes(): Set<Capture.Mode> {
        val key = VaultKeyringSession.captureStoreKey() ?: return emptySet()
        // `per-vault-intruder-capture` D5. Inside a vault's timeline, that vault's enrolment.
        if (captureVault) {
            return try {
                val id = VaultKeys.metadataBlobId(key)
                val indexId = VaultSession.metadataBlobId()
                if (indexId == null || !blobs.fileOf(id).exists()) emptySet()
                else runCatching { CaptureStore.enrolment(MetadataDb.load(blobs, id, key), indexId)?.modes }.getOrNull().orEmpty()
            } finally {
                key.fill(0)
            }
        }
        val ours = try {
            CaptureStore.destinationIs(this, key)
        } finally {
            key.fill(0)
        }
        return if (!ours) emptySet() else Capture.Mode.entries.filterTo(mutableSetOf()) { Capture.enabled(this, it) }
    }

    /** A vault flow's capture step is showing — D5: the switches there are the flow's draft. */
    private val flowCapturing: Boolean
        get() = vaultFlow != null && flowStep == NewVaultStep.Capture

    /**
     * Turns a mode on. Requests exactly the permission it needs — `CAMERA` for
     * photo, `RECORD_AUDIO` for audio, both for video — and only that. If they are
     * already held, enrolment finishes without a dialog. A keyring must be open,
     * because the destination key is derived from it.
     */
    private fun enableCaptureMode(mode: Capture.Mode) {
        captureProblem = null
        val needed = when (mode) {
            Capture.Mode.PHOTO -> arrayOf(android.Manifest.permission.CAMERA)
            Capture.Mode.AUDIO -> arrayOf(android.Manifest.permission.RECORD_AUDIO)
            Capture.Mode.VIDEO -> arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
            )
        }
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            finishCaptureEnable(mode)
            return
        }
        // `per-vault-intruder-capture` D8. Where Android will no longer ask, its settings page for
        // this app is the only place the permission is granted; coming back finishes the enable.
        if (CapturePermissions.needed(mode).any { CapturePermissions.wontAsk(this, it) }) {
            pendingSettingsMode = mode
            openAppSettings()
            return
        }
        CapturePermissions.needed(mode).forEach { CapturePermissions.markAsked(this, it) }
        pendingCaptureMode = mode
        AutoLock.awaitingResult(true)
        requestCapturePerms.launch(missing.toTypedArray())
    }

    /**
     * "Turn back on" — D8. Asks again for each permission an enabled mode lacks, or opens Android's
     * settings page for the app where Android no longer asks. The modes stay on throughout.
     */
    private fun turnCaptureBackOn() {
        val missing = captureModes.flatMap { CapturePermissions.needed(it) }.distinct()
            .filterNot { CapturePermissions.held(this, it) }
        if (missing.isEmpty()) return
        if (missing.any { CapturePermissions.wontAsk(this, it) }) {
            openAppSettings()
            return
        }
        missing.forEach { CapturePermissions.markAsked(this, it) }
        pendingCaptureMode = null
        AutoLock.awaitingResult(true)
        requestCapturePerms.launch(missing.map(CapturePermissions::manifestName).toTypedArray())
    }

    /** "Keep permissions" — D9. Android's page for the unused-app reset, for this app. */
    private fun openAutoRevokeSettings() {
        AutoLock.awaitingResult(true)
        startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_AUTO_REVOKE_PERMISSIONS,
                android.net.Uri.fromParts("package", packageName, null),
            )
        )
    }

    /**
     * D8, D9. Re-read on every resume: the permissions, the exemption, and a mode whose permission
     * was being granted on Android's settings page.
     */
    private fun refreshCapturePermissions() {
        capturePermsHeld = listOf(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_MICROPHONE)
            .filterTo(mutableSetOf()) { CapturePermissions.held(this, it) }
        autoRevokeExempt = runCatching { packageManager.isAutoRevokeWhitelisted }.getOrDefault(true)
        pendingSettingsMode?.let { mode ->
            pendingSettingsMode = null
            if (CapturePermissions.missing(this, mode) == null) finishCaptureEnable(mode)
        }
    }

    /** Each of [modes] lacking a permission, with the first it lacks. */
    private fun lackingFor(modes: Set<Capture.Mode>): Map<Capture.Mode, Int> =
        modes.mapNotNull { m -> CapturePermissions.needed(m).firstOrNull { it !in capturePermsHeld }?.let { m to it } }.toMap()

    /**
     * D8. A change noticed while the keyring is open is recorded at once rather than at the next
     * opening — and, inside a protected vault, taken into its timeline — so turning a permission
     * back on shows its row straight away.
     */
    private fun recordNoticedPermissions() {
        if (!keyringOpen) return
        val vault = if (openVaultProtected()) VaultSession.openVault() else null
        thread {
            val key = VaultKeyringSession.captureStoreKey() ?: return@thread
            try {
                val home = if (CaptureStore.destinationIs(this, key)) {
                    Capture.Mode.entries.filterTo(mutableSetOf()) { Capture.enabled(this, it) }
                } else {
                    emptySet()
                }
                CaptureIngest.recordPermissions(blobs, key, CapturePermissions.transitions(this), home)
                if (vault != null) CaptureIngest.ingestVault(blobs, OpenVault(key, VaultKeys.metadataBlobId(key), -1), vault)
            } catch (e: Exception) {
                Log.w("VaultCapture", "permission changes not recorded: ${e.javaClass.simpleName}")
            } finally {
                key.fill(0)
            }
            runOnUiThread { if (intruderScreen) reloadCaptures() }
        }
    }

    /**
     * Records the open keyring as the destination and marks the mode on — `intruder-captures-at-home`
     * D2. The destination is the capture keypair of this keyring's capture store key, derived here
     * while the keyring is open, so a locked-screen capture later has a key to seal to.
     *
     * Moving the destination here from another keyring turns that keyring's modes off first: the
     * switches read off on this keyring (D8), so the one just turned on is the only one this
     * keyring asked for.
     */
    private fun finishCaptureEnable(mode: Capture.Mode) {
        // `new-vault-setup-flow`, D5. On a vault flow's capture step the mode goes into the draft,
        // written only with the commit.
        if (flowCapturing) {
            setupCaptureModes = setupCaptureModes + mode
            return
        }
        if (captureVault) {
            enrolVault(captureModes + mode)
            return
        }
        val key = VaultKeyringSession.captureStoreKey() ?: return
        try {
            if (!CaptureStore.destinationIs(this, key)) {
                Capture.Mode.entries.forEach { Capture.setEnabled(this, it, false) }
            }
            val kp = VaultKeys.captureKeypair(key)
            try {
                Capture.setDestination(this, kp.publicKey)
            } finally {
                kp.zero()
            }
        } finally {
            key.fill(0)
        }
        Capture.setEnabled(this, mode, true)
        captureModes = enabledCaptureModes()
    }

    private fun disableCaptureMode(mode: Capture.Mode) {
        captureProblem = null
        if (flowCapturing) {
            setupCaptureModes = setupCaptureModes - mode
            return
        }
        if (captureVault) {
            enrolVault(captureModes - mode)
            return
        }
        Capture.setEnabled(this, mode, false)
        captureModes = enabledCaptureModes()
    }

    /**
     * Writes the open vault's enrolment — `per-vault-intruder-capture` D5: its capture store's
     * public key and [modes], into the keyring's capture store under the vault's index ID. No mode
     * removes it. The home switches are untouched.
     */
    private fun enrolVault(modes: Set<Capture.Mode>) {
        val vaultKey = captureKey ?: return
        val indexId = VaultSession.metadataBlobId() ?: return
        val key = VaultKeyringSession.captureStoreKey() ?: return
        try {
            val kp = VaultKeys.captureKeypair(vaultKey)
            try {
                CaptureStore.enrol(blobs, CaptureStore.open(blobs, key), indexId, kp.publicKey, modes)
            } finally {
                kp.zero()
            }
        } catch (e: Exception) {
            Log.w("VaultCapture", "enrolment not saved: ${e.javaClass.simpleName}")
        } finally {
            key.fill(0)
        }
        captureModes = enabledCaptureModes()
        reloadCaptures()
    }

    /**
     * Fires the enabled captures after a failed unlock, at [stage] — `cover-fingerprint-dots` D6.
     * Silent and best-effort, off the main thread so the lock screen's "that did not open" is not
     * delayed by a few seconds of recording. Nothing here touches the UI.
     *
     * Queued on [CAPTURES], one capture at a time, so a burst of attempts gives one capture each.
     * Each is sealed with its stage record and the moment it was taken (D6a), so only the
     * destination keyring learns where the attempt failed and when.
     */
    private fun fireIntruderCaptures(stage: CaptureRecord.Stage) {
        if (!Capture.anyEnabled(this)) return
        val destination = Capture.destinationKey(this) ?: return
        val app = applicationContext
        CAPTURES.execute {
            for (mode in Capture.Mode.entries) {
                if (!Capture.enabled(app, mode)) continue
                val takenAt = System.currentTimeMillis()
                val media = IntruderCapture.capture(app, mode) ?: continue
                val plain = CaptureRecord.prepend(stage.blankIf(IntruderCapture.isBlank(mode, media)), takenAt, media)
                media.fill(0)
                try {
                    val sealed = Capture.seal(destination, plain)
                    val blobId = Capture.newBlobId()
                    blobs.writeSealed(blobId, sealed)
                    Capture.add(app, mode, blobId, sealed.size, blobs)
                } catch (e: Exception) {
                    Log.i("VaultCapture", "seal/store failed: ${e.javaClass.simpleName}")
                } finally {
                    plain.fill(0)
                }
            }
        }
    }

    /**
     * A wrong passphrase on the sheet of the vault whose index is [indexBlobId] —
     * `per-vault-intruder-capture` D3. Governed by that vault's own modes, read from its enrolment
     * in the keyring's capture store; the home switches are not consulted. Each capture is sealed
     * to the vault's capture store and queued beside the enrolment, so only that vault opens it.
     *
     * On [CAPTURES], after the sheet has shown its error; nothing here touches the UI.
     */
    private fun fireVaultCaptures(indexBlobId: ByteArray) {
        // ponytail: the keyring's capture store key is held for the burst (a few seconds of
        // recording), so a lock landing mid-capture still queues what was taken.
        val key = VaultKeyringSession.captureStoreKey() ?: return
        val app = applicationContext
        val id = indexBlobId.copyOf()
        CAPTURES.execute {
            try {
                val storeId = VaultKeys.metadataBlobId(key)
                if (!blobs.fileOf(storeId).exists()) return@execute
                val enrolment = runCatching { CaptureStore.enrolment(MetadataDb.load(blobs, storeId, key), id) }
                    .getOrNull() ?: return@execute
                val keyringStore = OpenVault(key, storeId, -1)
                for (mode in Capture.Mode.entries) {
                    if (mode !in enrolment.modes) continue
                    val takenAt = System.currentTimeMillis()
                    val media = IntruderCapture.capture(app, mode) ?: continue
                    val stage = CaptureRecord.Stage(CaptureRecord.STAGE_VAULT_PASSPHRASE)
                        .blankIf(IntruderCapture.isBlank(mode, media))
                    val plain = CaptureRecord.prepend(stage, takenAt, media)
                    media.fill(0)
                    try {
                        val sealed = Capture.seal(enrolment.publicKey, plain)
                        val blobId = Capture.newBlobId()
                        blobs.writeSealed(blobId, sealed)
                        CaptureStore.queue(blobs, keyringStore, id, mode, blobId, sealed.size)
                    } catch (e: Exception) {
                        Log.i("VaultCapture", "vault seal/store failed: ${e.javaClass.simpleName}")
                    } finally {
                        plain.fill(0)
                    }
                }
            } finally {
                key.fill(0)
            }
        }
    }

    /**
     * Memos stopped while this vault was locked — `destination-settings-and-slideshow` D5.
     * [MemoIngest], skipping the memo recording right now: its record is still being written.
     */
    private fun ingestMemos(vault: OpenVault): Int =
        MemoIngest.ingest(this, blobs, vault, skip = VoiceMemo.current?.pendingId)
            .also { if (it > 0) Entitlement.startTrialIfNeeded(this) }

    // --- pairing and contacts (P4-4) -------------------------------------------

    /**
     * The contact list, from the drawer. Reads the store on the way in — see
     * [identityState] — and creates an identity if this install has never paired.
     */
    private fun openContacts() {
        identityState = Identity.state(this)
        pairingWaiting = Identity.pairingSessions(this).orEmpty()
        removingContact = null
        contactsScreen = true
    }

    private fun closeContacts() {
        contactsScreen = false
        removingContact = null
    }

    // --- crash log (debug-crash-log-screen) ------------------------------------

    private fun openCrashLog() {
        crashRecord = null
        confirmingCrashClear = false
        crashLogScreen = true
    }

    private fun closeCrashLog() {
        crashLogScreen = false
        crashRecord = null
        confirmingCrashClear = false
    }

    /**
     * Pairing, from the contact list's one primary action.
     *
     * Refuses to open against an unreadable store: there is no code to show and
     * nowhere to put what a scan found, and the list already says so with the one
     * action that fixes it.
     */
    private fun openPairing() {
        if (identityState !is Identity.State.Ready) return
        pairingStep = PairingStep.Show
        pairingScanned = null
        pairingName = ""
        pairingMessage = null
        pairingSaved = null
        cameraPermitted = VaultCamera.permitted(this)
        pairingScreen = true
    }

    private fun closePairing() {
        pairingScreen = false
        pairingScanned = null
        pairingName = ""
        pairingMessage = null
        pairingSaved = null
        identityState = Identity.state(this)
        // `remote-pairing` D6: back to a send flow lists whoever was just paired.
        if (sharingItem != null) shareContacts = Identity.contacts(this)
    }

    /** The show state's primary action. Asks for `CAMERA` the way the camera screen does. */
    private fun beginPairingScan() {
        pairingMessage = null
        cameraPermitted = VaultCamera.permitted(this)
        pairingStep = PairingStep.Scan
        if (!cameraPermitted) requestCamera()
    }

    /**
     * What a decoded frame means — `DESIGN.md` §7.21.
     *
     * Four outcomes and only one of them moves: the two named refusals from
     * `FORMAT.md` §13, the two that are not failures at all, and the accepted
     * scan. **None of the first four stops the scan** — the message is drawn under
     * a viewfinder that is still running, because the next code is the one that
     * works.
     */
    private fun pairingDecoded(payload: ByteArray) {
        if (pairingStep != PairingStep.Scan) return
        when (val parsed = Identity.PairingCode.parse(payload)) {
            Identity.PairingCode.Parsed.NotAPairingCode ->
                pairingMessage = getString(R.string.pairing_not_a_code)
            Identity.PairingCode.Parsed.NewerApp ->
                pairingMessage = getString(R.string.pairing_newer_app)
            is Identity.PairingCode.Parsed.Key ->
                when (val outcome = Identity.inspect(this, parsed.publicKey)) {
                    Identity.AddOutcome.OwnCode ->
                        pairingMessage = getString(R.string.pairing_own_code)
                    is Identity.AddOutcome.AlreadyKnown ->
                        pairingMessage = getString(R.string.pairing_already_known, outcome.name)
                    else -> {
                        pairingScanned = parsed.publicKey
                        pairingName = ""
                        pairingMessage = null
                        pairingStep = PairingStep.Name
                    }
                }
        }
    }

    /**
     * Stores the scanned key under the typed name and returns to the code, which
     * is where the sentence about the other person's scan is.
     */
    private fun savePairedContact() {
        val key = pairingScanned ?: return
        val outcome = Identity.add(this, key, pairingName)
        pairingScanned = null
        pairingStep = PairingStep.Show
        identityState = Identity.state(this)
        when (outcome) {
            Identity.AddOutcome.Added -> pairingSaved = pairingName.trim()
            is Identity.AddOutcome.AlreadyKnown ->
                pairingMessage = getString(R.string.pairing_already_known, outcome.name)
            else -> Unit
        }
        pairingName = ""
    }

    private fun removeContact() {
        val contact = removingContact ?: return
        Identity.remove(this, contact.publicKey)
        removingContact = null
        identityState = Identity.state(this)
    }

    /**
     * The one action against an unreadable store, and the only thing that deletes
     * it — behind §6.7's confirmation, because it discards every contact.
     */
    private fun startNewIdentity() {
        confirmingNewIdentity = false
        Identity.startNewIdentity(this)
        identityState = Identity.state(this)
        pairingWaiting = Identity.pairingSessions(this).orEmpty()
    }

    // --- remote pairing (`remote-pairing`) ---------------------------------------

    /** **Add a contact**, from the contact list or the send flow's code warning. */
    private fun openAddContact() {
        addingContact = true
    }

    /**
     * One of the three rows. The choice closes as the route opens, so back from the
     * route returns to whatever the choice was opened over — the list, or the send
     * flow with its selections intact.
     *
     * An unreadable store goes to the contact list, which says so and holds the one
     * action that fixes it.
     */
    private fun chooseAddRoute(route: AddRoute) {
        addingContact = false
        identityState = Identity.state(this)
        if (identityState !is Identity.State.Ready) {
            openContacts()
            return
        }
        when (route) {
            AddRoute.Together -> openPairing()
            AddRoute.NotTogether -> remoteStep = RemoteStep.Naming("")
            AddRoute.HaveInvite -> {
                remoteInvite = null
                remoteStep = RemoteStep.Opening(false, "", List(ShareCode.WORD_COUNT) { "" }, null)
            }
        }
    }

    /** A Waiting row: back to the step the session is at. */
    private fun resumeRemote(session: Identity.PairingSession) {
        remoteStep = when (session) {
            is Identity.PairingSession.Inviting -> RemoteStep.Inviting(
                session.sid, session.name, session.words.split(" "), session.endsAt, sent = true, problem = null,
            )
            is Identity.PairingSession.Confirming ->
                RemoteStep.Confirming(session.sid, session.name, alreadyKnownAs = null, sent = false)
            is Identity.PairingSession.Answering ->
                RemoteStep.Replying(session.sid, session.name, session.endsAt, sent = true, problem = null)
        }
    }

    private fun remoteNameChange(name: String) {
        remoteStep = when (val step = remoteStep) {
            is RemoteStep.Naming -> step.copy(name = name)
            is RemoteStep.Opening -> step.copy(name = name)
            else -> step
        }
    }

    private fun remoteWordsChange(words: List<String>) {
        (remoteStep as? RemoteStep.Opening)?.let { remoteStep = it.copy(words = words, problem = null) }
    }

    /** Step 1's primary on either side: make the invite, or the reply. */
    private fun remoteMake() {
        when (val step = remoteStep) {
            is RemoteStep.Naming -> makeInvite(step.name)
            is RemoteStep.Opening -> answerInvite(step)
            else -> Unit
        }
    }

    private fun makeInvite(name: String) {
        val words = ShareCode(Mnemonic.fromResources(this)).generate()
        remoteStep = when (val made = RemotePairing.makeInvite(this, name, words)) {
            is RemotePairing.Invited.Made -> RemoteStep.Inviting(
                made.session.sid, made.session.name, words, made.session.endsAt, sent = false, problem = null,
            )
            RemotePairing.Invited.BadName -> remoteStep
            RemotePairing.Invited.Unreadable -> RemoteStep.Ended(RemoteEnd.Unreadable, name, inviter = true, number = 1)
        }
    }

    /** The failed-reply and ended states' **Make a new invite**, with the name kept. */
    private fun remoteNewInvite() {
        val step = remoteStep as? RemoteStep.Ended ?: return
        makeInvite(step.name)
    }

    private fun answerInvite(step: RemoteStep.Opening) {
        val invite = remoteInvite ?: return
        val words = ShareCode(Mnemonic.fromResources(this)).normalise(step.words.joinToString(" ")) ?: return
        remoteStep = when (val answered = RemotePairing.answerInvite(this, invite, words, step.name)) {
            is RemotePairing.Answered.Made -> RemoteStep.Replying(
                answered.session.sid, answered.session.name, answered.session.endsAt, sent = false, problem = null,
            )
            RemotePairing.Answered.NotAnInvite -> {
                remoteInvite = null
                step.copy(picked = false, problem = getString(R.string.remote_not_an_invite))
            }
            RemotePairing.Answered.OwnInvite ->
                RemoteStep.Ended(RemoteEnd.OwnInvite, step.name, inviter = false, number = 1)
            RemotePairing.Answered.BadName -> step
            RemotePairing.Answered.Unreadable ->
                RemoteStep.Ended(RemoteEnd.Unreadable, step.name, inviter = false, number = 1)
        }
    }

    private fun waitingSession(sid: ByteArray): Identity.PairingSession? =
        Identity.pairingSessions(this)?.firstOrNull { it.sid.contentEquals(sid) }

    /**
     * Hands this step's file to the share sheet, through the `shares/` provider under a
     * fresh `<8 hex>.bin` name — the one outbound route, as a share container takes it.
     * The file is rebuilt from the stored session each time, so it is the same bytes
     * however often it is sent.
     */
    private fun sendPairingFile() {
        val step = remoteStep ?: return
        val bytes = when (step) {
            is RemoteStep.Inviting ->
                (waitingSession(step.sid) as? Identity.PairingSession.Inviting)?.let(RemotePairing::inviteFile)
            is RemoteStep.Confirming ->
                (waitingSession(step.sid) as? Identity.PairingSession.Confirming)
                    ?.let { RemotePairing.confirmationFile(this, it) }
            is RemoteStep.Replying ->
                (waitingSession(step.sid) as? Identity.PairingSession.Answering)?.reply
            else -> return
        }
        if (bytes == null) {
            // The session outlived its 24 hours while the screen was open.
            remoteStep = RemoteStep.Ended(RemoteEnd.Expired, step.name, step.inviter, step.number)
            return
        }
        val dir = ShareSend.directory(this).also { it.mkdirs() }
        val file = java.io.File(dir, ShareSend.containerName())
        file.writeBytes(bytes)
        remoteStep = when (step) {
            is RemoteStep.Inviting -> step.copy(sent = true, problem = null)
            is RemoteStep.Confirming -> step.copy(sent = true)
            is RemoteStep.Replying -> step.copy(sent = true, problem = null)
            else -> step
        }
        handOff(file)
    }

    /** The picker, suppressing auto-lock across the round trip as every picker does. */
    private fun pickPairingFile() {
        AutoLock.awaitingResult(true)
        // Pairing files are opaque `.bin` bytes, like containers: nothing to filter on.
        openPairingFile.launch(arrayOf("application/octet-stream", "*/*"))
    }

    /**
     * What a picked file means for the step on screen. At most a few kilobytes are
     * read: the largest pairing file is 1,280 bytes, and anything longer is the wrong
     * file whatever else it is.
     */
    private fun pairingFilePicked(uri: android.net.Uri) {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(RemotePairing.REPLY_BYTES + 1)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrNull() ?: ByteArray(0)

        remoteStep = when (val step = remoteStep) {
            is RemoteStep.Opening ->
                if (RemotePairing.isInvite(bytes)) {
                    remoteInvite = bytes
                    step.copy(picked = true, problem = null)
                } else {
                    step.copy(problem = getString(R.string.remote_not_an_invite))
                }
            is RemoteStep.Inviting -> when (val opened = RemotePairing.openReply(this, step.sid, bytes)) {
                is RemotePairing.Opened.Saved ->
                    RemoteStep.Confirming(step.sid, step.name, opened.alreadyKnownAs, sent = false)
                RemotePairing.Opened.WrongFile ->
                    step.copy(problem = getString(R.string.remote_wrong_reply, step.name))
                else -> ended(opened, step.name, inviter = true, number = 2)
            }
            is RemoteStep.Replying -> when (val opened = RemotePairing.openConfirmation(this, step.sid, bytes)) {
                is RemotePairing.Opened.Saved -> RemoteStep.Answered(step.name, opened.alreadyKnownAs)
                RemotePairing.Opened.WrongFile ->
                    step.copy(problem = getString(R.string.remote_wrong_confirmation, step.name))
                else -> ended(opened, step.name, inviter = false, number = 2)
            }
            else -> step
        }
    }

    private fun ended(opened: RemotePairing.Opened, name: String, inviter: Boolean, number: Int) =
        RemoteStep.Ended(
            when (opened) {
                RemotePairing.Opened.Ended ->
                    if (inviter) RemoteEnd.ReplyDidNotMatch else RemoteEnd.ConfirmationDidNotMatch
                RemotePairing.Opened.Expired -> RemoteEnd.Expired
                RemotePairing.Opened.OwnKey -> RemoteEnd.OwnInvite
                RemotePairing.Opened.Unreadable -> RemoteEnd.Unreadable
                else -> RemoteEnd.AlreadyEnded
            },
            name, inviter, number,
        )

    /** **Cancel invite** / **Cancel**: the session and its secrets go, and the flow closes. */
    private fun cancelRemote() {
        when (val step = remoteStep) {
            is RemoteStep.Inviting -> RemotePairing.end(this, step.sid)
            is RemoteStep.Replying -> RemotePairing.end(this, step.sid)
            else -> Unit
        }
        closeRemote()
    }

    /** **Done**: a finished inviter's session ends here; an unreadable store goes to the list that explains it. */
    private fun remoteDone() {
        val step = remoteStep
        if (step is RemoteStep.Confirming) RemotePairing.end(this, step.sid)
        closeRemote()
        if (step is RemoteStep.Ended && step.reason == RemoteEnd.Unreadable && !contactsScreen) openContacts()
    }

    /**
     * Leaves the flow. A session still waiting stays waiting — it is listed under
     * Waiting — and whatever it returns to re-reads the store.
     */
    private fun closeRemote() {
        remoteStep = null
        remoteInvite = null
        identityState = Identity.state(this)
        pairingWaiting = Identity.pairingSessions(this).orEmpty()
        if (sharingItem != null) shareContacts = Identity.contacts(this)
    }

    // --- the camera (P2-17) ----------------------------------------------------

    /**
     * Opens the camera screen, asking for `CAMERA` if it has never been granted.
     *
     * The entitlement gate is [mayAddToVault]'s, the same one the pickers pass: a
     * capture puts an item in the vault, so it is subject to whatever an import is
     * subject to. Reads are never gated (I4) and this is not a read.
     */
    private fun openCamera() {
        if (!mayAddToVault()) return
        cameraProblem = null
        // The receipt is this session's — `lastCapture`'s note.
        lastCapture = null
        captureSaving = false
        cameraPermitted = VaultCamera.permitted(this)
        cameraScreen = true
        if (!cameraPermitted) requestCamera()
    }

    private fun requestCamera() {
        AutoLock.awaitingResult(true)
        requestCameraPerms.launch(arrayOf(android.Manifest.permission.CAMERA))
    }

    /**
     * P2-17. Asked for when video is chosen and never for photography. A denial is
     * not reported and not retried: the recording is silent, which is a video the
     * user still wanted.
     */
    private fun requestCameraAudio() {
        if (VaultCamera.audioPermitted(this)) return
        AutoLock.awaitingResult(true)
        requestCameraPerms.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
    }

    private fun closeCamera() {
        cameraScreen = false
        cameraProblem = null
        lastCapture = null
        captureSaving = false
    }

    /**
     * Opens the receipt — `camera-capture-feedback`, and the convention exactly: a
     * camera's corner thumbnail opens that item, and back comes straight back here.
     *
     * ***It puts the grid where the item actually is first.*** The viewer pages the
     * list the grid is showing, and that list is narrowed by destination, by the
     * media chip, by the folder and by any search in effect — so a photograph taken
     * while the grid was on Notes, or filtered to Videos, is not in it, and opening
     * would show a different item entirely. Each of those four is put back to
     * something that holds the capture. The folder is the one it was taken in,
     * recorded at the shutter rather than guessed from the row.
     */
    private fun openCaptureReceipt() {
        val item = lastCapture ?: return
        if (!destination.holds(item)) {
            VaultDestination.of(item.kind)?.let(::goToDestination)
        }
        if (destination == VaultDestination.Media) mediaFilter = MediaFilter.All
        folderPath = lastCapturePath
        query = ""
        open = item
    }

    /**
     * The row [written] became, found in the index it was committed into.
     *
     * By blob rather than by position: `commitCapture` appends, but a lookup that
     * depends on that is a lookup that breaks the day anything sorts on the way in.
     */
    private fun capturedItem(db: MetadataDb, written: WrittenItem): VaultItem? {
        val first = written.blobIds.firstOrNull() ?: return null
        return db.items.lastOrNull { it.blobIds.firstOrNull()?.contentEquals(first) == true }
    }

    // --- the voice memo (`voice-memo`) -----------------------------------------

    /**
     * Opens the memo screen, asking for `RECORD_AUDIO` if it has never been granted.
     *
     * The entitlement gate is [mayAddToVault]'s, the same one the camera and the
     * pickers pass: a memo puts an item in the vault, so it is subject to whatever an
     * import is subject to. Reads are never gated (I4) and this is not a read.
     */
    private fun openVoiceMemo() {
        if (!mayAddToVault()) return
        voiceMemoProblem = null
        memoNotice = null
        voiceMemoPermitted = VaultCamera.audioPermitted(this)
        voiceMemoScreen = true
        if (!voiceMemoPermitted) requestMemoAudio()
    }

    private fun requestMemoAudio() {
        AutoLock.awaitingResult(true)
        requestMemoPerm.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    /** Back, from the bar or the gesture. Asks first when leaving would lose something. */
    private fun requestLeaveVoiceMemo() {
        if (memoUnsaved) confirmingMemoDiscard = true else closeVoiceMemo()
    }

    private fun closeVoiceMemo() {
        voiceMemoScreen = false
        voiceMemoProblem = null
        memoNotice = null
        confirmingMemoDiscard = false
        memoUnsaved = false
    }

    /**
     * Record — `voice-memo`, `destination-settings-and-slideshow` D5 and D6. The first time,
     * notifications are asked for first and the recording starts when the dialog closes,
     * whatever the answer.
     */
    private fun recordMemo() {
        voiceMemoProblem = null
        val ask = android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !memoNotificationsAsked
        if (ask) {
            memoNotificationsAsked = true
            AutoLock.awaitingResult(true)
            requestMemoNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMemo()
        }
    }

    /** Asked once a process, so a refusal is not asked again at every press of Record. */
    private var memoNotificationsAsked = false

    /**
     * Starts the memo, sealed to the open vault's capture key, in the folder on screen, and the
     * foreground service that keeps it going with the screen off.
     */
    private fun startMemo() {
        val vmk = VaultSession.vmk() ?: return
        val kp = VaultKeys.captureKeypair(vmk)
        val publicKey = kp.publicKey
        kp.zero()
        val problem = VoiceMemo.start(this, blobs, publicKey, folderPath.lastOrNull(), memoSettings)
        voiceMemoProblem = problem
        if (problem != null) return
        MemoRecordingService.start(this)
        memoNotice = if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            getString(R.string.memo_notifications_off)
        } else {
            null
        }
    }

    /**
     * Stop — `voice-memo-layout`, D1. Finishes the sealed write and holds the memo, so it can be
     * heard before it is kept. Off the main thread: the write is joined.
     */
    private fun stopVoiceMemo() {
        if (memoHere() == null) return
        thread {
            val stopped = VoiceMemo.stop(this, blobs, keepKey = true)
            runOnUiThread {
                MemoRecordingService.stop(this)
                if (stopped == null) voiceMemoProblem = getString(R.string.memo_too_short)
            }
        }
    }

    /**
     * Keep it — D5. The held memo is read under its memo key and sealed again under the vault's
     * into an item, in the folder it was started in. Its memo-key blobs and record go only once
     * the index holds the item, so a lock or a failed commit in between leaves it pending.
     */
    private fun saveVoiceMemo() {
        if (memoHere() == null) return
        val vmk = VaultSession.vmk()?.copyOf() ?: return
        val indexId = VaultSession.metadataBlobId()?.copyOf() ?: run { vmk.fill(0); return }
        busy = true
        thread {
            val committed = try {
                val (memo, written) = VoiceMemo.reseal(blobs, vmk) ?: return@thread
                runCatching {
                    commitCapture(indexId, vmk, written, VoiceMemo::filename, memo.folderId)
                }.onSuccess {
                    VoiceMemo.saved(this, blobs, memo)
                }.onFailure {
                    MemoIngest.forget(blobs, written.blobIds)
                }.getOrNull()
            } catch (e: Exception) {
                Log.w("VaultMemo", "not saved: ${e.javaClass.simpleName}")
                null
            } finally {
                vmk.fill(0)
                indexId.fill(0)
                runOnUiThread { busy = false }
            }
            runOnUiThread {
                if (committed != null) {
                    index = committed
                    closeVoiceMemo()
                } else if (VaultSession.vmk() != null) {
                    voiceMemoProblem = getString(COULD_NOT_STORE)
                }
            }
        }
    }

    /**
     * Throw the memo away — `voice-memo`. The recording stops if it is still going, and every
     * blob it wrote and its record are deleted. Nothing partially recorded ever becomes an item.
     */
    private fun discardMemo() {
        if (memoHere() == null) return
        thread {
            VoiceMemo.discard(this, blobs)
            runOnUiThread { MemoRecordingService.stop(this) }
        }
    }

    /**
     * Throw a camera recording away — P2-17.
     *
     * The mirror of [endRecording] with the commit removed: the write end is closed
     * so the encrypting thread's read ends, the thread is joined so nothing is still
     * claiming blobs, and every blob it claimed is deleted rather than left
     * untracked. Nothing partially recorded ever becomes an item.
     */
    private fun discardRecording() {
        val write = recordingWrite ?: return
        recordingWrite = null
        val worker = recordingThread
        recordingThread = null
        thread {
            runCatching { write.close() }
            worker?.join()
            recordingWritten = null
            discardCapture(recordingBlobs.toList())
            recordingBlobs.clear()
        }
    }

    /**
     * Writes one captured photograph into the vault — P2-17.
     *
     * `ByteArrayInputStream` into [BlobStore.writeItem], which is what
     * [CaptureIngest] does with an opened intruder capture and what an import does
     * with a source stream. **The JPEG is plaintext until this returns**, so it is
     * zeroed in a `finally`, and it is never handed to anything else in the clear.
     */
    private fun storePhoto(jpeg: ByteArray) {
        val vmk = VaultSession.vmk()
        val indexId = VaultSession.metadataBlobId()
        if (vmk == null || indexId == null) return
        // The folder the shutter was pressed in — read now, for `importAll`'s reason.
        val destination = folderPath.lastOrNull()
        busy = true
        // `camera-capture-feedback`. The receipt waits from here, and the folder it
        // opens into is the one the shutter was pressed in.
        captureSaving = true
        lastCapturePath = folderPath
        thread {
            val claimed = mutableListOf<ByteArray>()
            val outcome = runCatching {
                val written = blobs.writeItem(ByteArrayInputStream(jpeg), vmk, claimed)
                commitCapture(indexId, vmk, written, { VaultCamera.filename(VaultCamera.Mode.PHOTO, it) }, destination) to written
            }
            jpeg.fill(0)
            if (outcome.isFailure) discardCapture(claimed)
            val committed = outcome.getOrNull()
            runOnUiThread {
                busy = false
                captureSaving = false
                if (committed != null) {
                    index = committed.first
                    // Null only if the row cannot be found, which is the same as not
                    // having been written: the receipt says nothing rather than
                    // pointing at an item that is not there.
                    lastCapture = capturedItem(committed.first, committed.second)
                }
                cameraProblem = if (committed == null) getString(COULD_NOT_STORE) else null
            }
        }
    }

    /**
     * Opens the pipe a recording streams into — P2-17.
     *
     * The recorder writes the returned descriptor; a thread here reads the other end
     * straight into [BlobStore.writeItem], which encrypts as the recording proceeds.
     * **Nothing is buffered and nothing is staged**: there is no file, and no copy of
     * the clip in memory. `IntruderCapture`'s 8 MiB ceiling belongs to a four-second
     * clip held in a `ByteArrayOutputStream` and does not come with the pipe.
     */
    private fun beginRecording(): android.os.ParcelFileDescriptor? {
        val vmk = VaultSession.vmk() ?: return null
        if (recordingWrite != null) return null
        val pipe = runCatching { android.os.ParcelFileDescriptor.createPipe() }.getOrNull()
            ?: return null
        val read = pipe[0]
        recordingWrite = pipe[1]
        recordingWritten = null
        recordingBlobs.clear()
        recordingThread = thread(name = "vault-record") {
            recordingWritten = runCatching {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                    blobs.writeItem(input, vmk, recordingBlobs)
                }
            }.getOrNull()
        }
        return pipe[1]
    }

    /**
     * The recording is over — P2-17. Closes the write end, which ends the stream the
     * encrypting thread is reading, waits for it, and commits what it produced.
     *
     * **An interrupted recording is discarded, blobs and all.** A lock while
     * recording lands here with a zeroed VMK and no session: there is nothing to
     * commit the item to, so the blobs written so far are deleted rather than left
     * untracked. Nothing partially recorded ever becomes an item.
     */
    private fun endRecording() {
        // The folder the recording was started in — read now, for `importAll`'s reason.
        val destination = folderPath.lastOrNull()
        busy = true
        // `camera-capture-feedback`, exactly as the shutter does it: the receipt
        // waits from the moment the recording stops, and lands on the clip.
        captureSaving = true
        lastCapturePath = folderPath
        finishRecording { recorded ->
            if (recorded != null) {
                commitRecording(recorded, destination)
            } else {
                busy = false
                captureSaving = false
                // A recording ended by a lock is not a failure to report: the screen
                // it would be reported on is gone, and the vault is closed.
                if (VaultSession.vmk() != null) cameraProblem = getString(COULD_NOT_STORE)
            }
        }
    }

    /**
     * Ends the recording's write — `voice-memo-layout`, D1. Closing the write end is
     * what ends the encrypting read; the join must not happen on the main thread.
     *
     * [onDone] runs on the main thread with the sealed item and the blobs it claimed,
     * or null when there is nothing to keep — a zero-length write, or no key left to
     * keep it under — in which case every claimed blob is already deleted.
     */
    private fun finishRecording(onDone: (Recorded?) -> Unit) {
        val write = recordingWrite ?: return onDone(null)
        recordingWrite = null
        val worker = recordingThread
        recordingThread = null
        thread {
            runCatching { write.close() }
            worker?.join()
            val written = recordingWritten
            recordingWritten = null
            // The worker is joined, so nothing is still appending to the list.
            val claimed = recordingBlobs.toList()
            recordingBlobs.clear()
            val recorded = if (written == null || written.plaintextLength == 0L ||
                VaultSession.vmk() == null
            ) {
                discardCapture(claimed)
                null
            } else {
                Recorded(written, claimed)
            }
            runOnUiThread { onDone(recorded) }
        }
    }

    /**
     * Makes a finished recording an item — P2-17's commit, now separate from the
     * write so a memo can be heard between the two. A commit that fails, or that
     * finds the vault gone, deletes what the recording wrote.
     */
    private fun commitRecording(recorded: Recorded, destination: ByteArray?) {
        busy = true
        captureSaving = true
        lastCapturePath = folderPath
        val name: (Long) -> String = { VaultCamera.filename(VaultCamera.Mode.VIDEO, it) }
        thread {
            val vmk = VaultSession.vmk()
            val indexId = VaultSession.metadataBlobId()
            val committed = if (vmk == null || indexId == null) {
                discardCapture(recorded.claimed)
                null
            } else {
                runCatching {
                    commitCapture(indexId, vmk, recorded.written, name, destination)
                }.onFailure { discardCapture(recorded.claimed) }.getOrNull()
            }
            runOnUiThread {
                busy = false
                captureSaving = false
                if (committed != null) {
                    index = committed
                    lastCapture = capturedItem(committed, recorded.written)
                }
                if (committed == null && VaultSession.vmk() != null) {
                    cameraProblem = getString(COULD_NOT_STORE)
                }
            }
        }
    }

    /**
     * The index row for a stored capture, with its thumbnail — P2-17.
     *
     * The thumbnail is decoded **before** the commit and staged inside it, which is
     * `ImportWorker`'s shape and P2-9's rule: a full-frame decode inside the commit
     * would hold the index lock across the slowest thing in the operation.
     *
     * `createdAt` is the capture instant and not a stand-in for one — this app
     * watched it happen — so P1-6 is satisfied rather than waived, and P2-16's
     * capture-date headings say the right thing with no special case.
     */
    private fun commitCapture(
        indexId: ByteArray,
        vmk: ByteArray,
        written: WrittenItem,
        /**
         * The item's name, given the capture instant. A function rather than a
         * `VaultCamera.Mode`: `voice-memo` commits through here too, and a memo is
         * not a camera mode.
         */
        nameAt: (Long) -> String,
        /** The folder the capture was started from — `folder-system`, P2-13's album. */
        folderId: ByteArray?,
    ): MetadataDb {
        val at = System.currentTimeMillis()
        val provisional = VaultItem(
            blobIds = written.blobIds,
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = nameAt(at),
            album = null,
            folderId = folderId,
            createdAt = at,
            importedAt = at,
            tags = emptyList(),
        )
        val made = Thumbnails.generate(blobs, provisional, vmk)
        return MetadataDb.commit(blobs, indexId, vmk) { db ->
            // Two shutter taps inside one second produce one name; this is the
            // suffixing an export already does, not a uniqueness rule P2-14 declined.
            val name = VaultCamera.uniqueFilename(
                db.items.mapTo(HashSet()) { it.filename },
                provisional.filename,
            )
            val item = provisional.withFilename(name)
            val thumbs = ThumbnailStore(blobs)
            db.items += if (made.webp != null) {
                item.withThumb(thumbs.stage(db, made.webp), made.durationMs)
                    .also { thumbs.flush(db, vmk) }
            } else {
                item
            }
            db
            // `one-time-purchase` D4. A photo, a video or a memo is an add like an import.
        }.also { Entitlement.startTrialIfNeeded(this) }
    }

    /** A capture that did not become an item leaves nothing in the pool. */
    private fun discardCapture(claimed: List<ByteArray>) {
        claimed.forEach {
            blobs.discardPartial(it)
            blobs.delete(it)
        }
    }

    private fun attemptUnlock(passphrase: ByteArray) {
        busy = true
        unlockFailed = false
        newerVersion = false
        indexUnreadable = false
        // `panic-phrase`. Nothing about panic is decided here any more: a panic phrase
        // is an ordinary credential with a slot of its own, and `unlock` recognises it
        // from the keyring it opens.
        thread {
            val outcome = try {
                // **No `ingest` here — `vault-keyring`.** P2-4's capture ingest is
                // per vault and runs where a vault is opened (`openVaultAt`); an
                // unlock opens a keyring and has none to ingest into.
                lifecycleOps.unlock(
                    passphrase,
                    // `first-run-onboarding`, D5. A paused flow ends with the wipe:
                    // after a panic nothing may greet anyone with setup screens, and a
                    // step left in `shared_prefs/` would say setup was running when the
                    // panic fired. A local `commit`, inside the wipe's silent removals.
                    destroyWrap = { disableWrap(); Onboarding.finish(this@MainActivity) },
                    // `cloud-mirror`. Enqueued, never performed here: a provider that
                    // hangs would make a panic unlock measurably slower than an
                    // ordinary one, which `panic-pin` forbids. `enqueue` returns as
                    // soon as WorkManager has the request.
                    destroyMirror = { MirrorDestroyWorker.enqueue(this@MainActivity) },
                    // `vault-keyring`. A migration changes what the slot's 48 bytes
                    // mean, and §4.1's wrap holds a copy of the old ones. It is retired
                    // rather than silently left to fail on the next fingerprint, and
                    // the notice says so where the user will meet it.
                    onMigrated = {
                        if (bio.isEnabled()) {
                            disableWrap()
                            runOnUiThread {
                                bioEnabled = false
                                bioNotice = BIO_AFTER_MIGRATION
                            }
                        }
                    },
                )
            } catch (e: MetadataDb.NewerVersionException) {
                // P2-25. `VaultLifecycle.open` loads the index, so the rollback case
                // can surface here as well as in `loadIndex`. The passphrase was
                // right; the build is old. Reported as itself and never as a failed
                // unlock, which would tell the user their passphrase was wrong.
                Log.w("VaultIndex", "index written by a newer build", e)
                runOnUiThread { newerVersion = true }
                return@thread
            } finally {
                busy = false
            }
            // P2-4. A failed unlock is the intruder trigger. Fired here, off its own
            // thread, and nothing about it reaches the UI — no shutter, no notice,
            // no change to the screen the person is looking at.
            if (!outcome.opened) fireIntruderCaptures(CaptureRecord.Stage(CaptureRecord.STAGE_PASSPHRASE))
            runOnUiThread {
                // `vault-keyring`. A passphrase opens a keyring and stops there: no
                // vault is open, no index is loaded, and no vault key has been
                // unwrapped. The list is what the user lands on.
                keyringOpen = outcome.opened
                // `panic-phrase`. The state belongs to whichever keyring just opened.
                panicEnabled = VaultKeyringSession.hasPanicPhrase()
                decoySetUp = VaultKeyringSession.hasDecoy()
                if (outcome.opened) {
                    vaultEntries = VaultKeyringSession.vaults()
                    // `recovery-practice` D1. Typing it is practice in itself: the next check
                    // moves one current interval ahead, and no practice screen is shown.
                    practice.notePassphraseUnlock(System.currentTimeMillis())
                    // **`afterUnlock` does not run here — `vault-keyring`.** Every part
                    // of it is per *vault*: the trash purge, orphan adoption and the
                    // reload all begin `VaultSession.vmk() ?: return`, so running them
                    // with only a keyring open would be three silent no-ops and a
                    // trash that never expired. It runs when a vault is opened, in
                    // `openVaultAt`. The partial sweep is the store's rather than a
                    // vault's, and goes with it because the two have always been one
                    // pass and splitting them buys nothing.
                }
                // Set only on failure, and identical for a wrong passphrase and
                // for a slot array holding no vault — the screen has no way to
                // tell them apart because this code does not either.
                unlockFailed = !outcome.opened
            }
        }
    }

    /**
     * Opens one vault from the list — `vault-keyring`.
     *
     * **This is where a vault key comes into existence.** Unlocking did not produce
     * one; this unwraps exactly one entry, hands it to `VaultSession`, and runs the
     * per-vault work that used to run at unlock — the chaff churn, the intruder
     * ingest, the tamper-log open entry — because all three are about *a vault*, and
     * before this there was none open to do them for.
     */
    /**
     * Creates a vault on the open keyring — `per-vault-passphrases`.
     *
     * **Nothing here writes to the slot array**, which is the whole difference from the
     * screen this replaced. There is no passphrase to prove, no list of vaults to
     * preserve, and no slot to lose: an entry on a keyring already open can overwrite
     * nothing.
     *
     * [passphrase] null means the vault opens with the passphrase that opened the list.
     * Supplied, it seals the entry under a KEK derived from it and the position the
     * vault lands on — which the session chooses, so the derivation is handed to it as
     * a lambda rather than performed here.
     *
     * A protected vault's kit is issued **before it reaches the list**: the words are
     * the only other way in, and a kit shown after the vault exists is a kit the user
     * can walk past.
     */
    private fun createVaultOnList(
        name: String,
        passphrase: ByteArray?,
        appearance: VaultAppearance,
        /** `new-vault-setup-flow`, D8. The capture step's modes, enrolled once the vault exists. */
        modes: Set<Capture.Mode>,
    ) {
        // P1-16, as every other creation path does it: the gate goes before any work. Said where
        // the flow says it on its first step, under the name (D4).
        if (!mayAddToVault()) {
            passphrase?.fill(0)
            flowNameError = getString(Entitlement.BLOCKED)
            flowStep = NewVaultStep.Name
            return
        }
        flowProblem = null
        setupBusy = true
        thread {
            // Held so the kit can be derived from the same KEK that sealed the entry —
            // the vault's key is not reachable any other way once it is sealed.
            var entryKek: ByteArray? = null
            var vmk: ByteArray? = null
            try {
                val position = if (passphrase == null) {
                    VaultKeyringSession.createVault(blobs, name, appearance)
                } else {
                    VaultKeyringSession.createVault(blobs, name, appearance) { chosen, set ->
                        // The position and the parameter set are the session's choice,
                        // which is why this is a lambda and not a value computed above.
                        // A copy, because `createVault` zeroes the KEK it is handed on its
                        // way out — holding the same array left the kit below unsealing
                        // under zeros, and a protected vault reached the list with no kit.
                        // The `finally` below zeroes the copy.
                        checkNotNull(VaultKeyringSession.entryPassphraseKek(passphrase, chosen, set))
                            .also { entryKek = it.copyOf() }
                    }
                }

                if (position == null) {
                    runOnUiThread {
                        setupBusy = false
                        if (VaultKeyringSession.isFull()) {
                            flowNameError = getString(R.string.vault_list_full)
                            flowStep = NewVaultStep.Name
                        } else {
                            flowProblem = getString(R.string.vault_not_created)
                        }
                    }
                    return@thread
                }

                vmk = entryKek?.let { VaultKeyringSession.vaultKeyForKit(position, it) }
                // D8. The vault exists by now; a failed enrolment is said on the list, never
                // undone, and the kit is shown either way.
                val enrolled = modes.isEmpty() || runCatching {
                    val indexId = VaultKeyringSession.vaults().first { it.position == position }.indexBlobId
                    VaultLifecycle.enrolFirst(blobs, indexId, checkNotNull(vmk), modes)
                }.onFailure { Log.w("VaultCapture", "enrolment not saved: ${it.javaClass.simpleName}") }.isSuccess
                val words = vmk?.let { mnemonic.encode(it) }
                if (words != null) TamperLog.append(blobs, TamperLog.RECOVERY_KIT_GENERATED)

                runOnUiThread {
                    setupBusy = false
                    // D4, 4.10. Re-read now, so the list behind the kit already carries the vault.
                    vaultEntries = VaultKeyringSession.vaults()
                    if (!enrolled) vaultListProblem = getString(R.string.vault_capture_not_enrolled, name)
                    if (words != null) {
                        recoveryKitVault = name
                        recoveryKit = words
                        flowStep = NewVaultStep.Kit
                    } else {
                        // List-opened: the flow ends on the list.
                        closeVaultFlow()
                    }
                }
            } catch (e: Throwable) {
                Log.w("VaultKeyring", "a vault could not be created", e)
                runOnUiThread {
                    setupBusy = false
                    flowProblem = getString(R.string.vault_not_created)
                }
            } finally {
                passphrase?.fill(0)
                entryKek?.fill(0)
                vmk?.fill(0)
            }
        }
    }

    /**
     * Opens a vault sealed under its own passphrase — `per-vault-passphrases`.
     *
     * [openVaultAt]'s tail with one Argon2id in front of it. The derivation runs off the
     * main thread for the reason every other one does: it is ~460 ms, and the indicator
     * the sheet shows is driven by [busy] alone, never by the outcome.
     */
    private fun openVaultWithPassphrase(entry: VaultKeyring.Entry, passphrase: ByteArray) {
        vaultPassphraseFailed = false
        busy = true
        thread {
            // Under the set the entry records. None recorded — an entry sealed before
            // keyring version 5 — fails as a wrong passphrase does; its own kit opens it.
            val kek = try {
                VaultKeyringSession.entryPassphraseKek(passphrase, entry.position, entry.kdfSet)
            } finally {
                passphrase.fill(0)
            }
            val opened = try {
                kek?.let { VaultKeyringSession.openVaultWithPassphrase(entry.position, it) }
            } finally {
                kek?.fill(0)
            }
            if (opened == null) {
                runOnUiThread {
                    busy = false
                    vaultPassphraseFailed = true
                    // `per-vault-intruder-capture` D3. After the error is shown, off the main thread.
                    fireVaultCaptures(entry.indexBlobId)
                }
                return@thread
            }
            runOnUiThread {
                promptingVault = null
                vaultPassphraseFailed = false
            }
            finishOpening(opened)
        }
    }

    /**
     * Puts a vault back on the list from its **own** 24 words —
     * `per-vault-passphrases`.
     *
     * One outcome for every failure: a wrong checksum, a vault whose blobs are not here,
     * and words that belong to a keyring rather than a vault all report the same thing.
     * Telling them apart would say what is and is not on this device.
     */
    private fun recoverVaultOntoList(phrase: String, name: String) {
        vaultRecoveryFailed = false
        busy = true
        thread {
            val vmk = mnemonic.decode(phrase)
            val position = if (vmk == null) null else try {
                lifecycleOps.recoverVault(name, vmk)
            } finally {
                vmk.fill(0)
            }
            runOnUiThread {
                busy = false
                if (position == null) {
                    vaultRecoveryFailed = true
                } else {
                    recoveringVault = false
                    vaultEntries = VaultKeyringSession.vaults()
                }
            }
        }
    }

    private fun openVaultAt(entry: VaultKeyring.Entry) {
        vaultListProblem = null
        // `per-vault-passphrases`. A locked row asks before it opens. Nothing is
        // derived here and nothing is attempted — the keyring key cannot open this
        // entry, so trying first would only be a wasted AEAD and a worse message.
        if (entry.hasOwnPassphrase) {
            vaultPassphraseFailed = false
            promptingVault = entry
            return
        }
        busy = true
        thread {
            val opened = VaultKeyringSession.openVault(entry.position)
            if (opened == null) {
                runOnUiThread {
                    busy = false
                    vaultListProblem = getString(R.string.vault_not_opened)
                }
                return@thread
            }
            finishOpening(opened)
        }
    }

    /**
     * The half of opening a vault that is the same however it was unwrapped —
     * `per-vault-passphrases`.
     *
     * Shared by [openVaultAt] and [openVaultWithPassphrase] so the two cannot drift.
     * One of them quietly skipping the chaff churn, the memo ingest or the tamper-log
     * entry would leave a vault whose owner uses a passphrase silently not doing the
     * per-unlock work — a difference visible in modification times.
     *
     * Runs on the caller's background thread, as the code it came from did.
     */
    private fun finishOpening(opened: OpenVault, relinked: Boolean = false, onOpened: (() -> Unit)? = null) {
        // `backup-to-cloud-apps` D11. A backup written after this vault last locked left its
        // record waiting for the key that is back now.
        runCatching { BackupAuto.applyPendingRecord(blobs, opened.metadataBlobId, opened.vmk) }
        try {
            lifecycleOps.open(opened, memos = ::ingestMemos)
        } catch (e: MetadataDb.NewerVersionException) {
            // P2-25, on the path that now loads the index. The passphrase was
            // right and the build is old; reported as itself.
            Log.w("VaultIndex", "index written by a newer build", e)
            VaultKeyringSession.leaveVault()
            runOnUiThread {
                busy = false
                newerVersion = true
            }
            return
        } catch (e: MetadataDb.NotAnIndexException) {
            // `index-pointer-recovery`. The entry's pointer does not name an index.
            // Every blob of this vault is still in the pool, so the honest response is
            // to look for the index rather than to declare a loss — and the repair is
            // possible right here, because editing a keyring entry needs the keyring
            // key the session already holds and not the passphrase.
            //
            // Before this the exception was an `EOFException` that escaped on this
            // thread and killed the process, so the vault's owner got a crash on every
            // launch and never reached anything that could look for them.
            Log.w("VaultIndex", "the index pointer does not name an index", e)
            if (relinkIndex(opened)) {
                // Found and rewritten. Open again, on the corrected pointer.
                return finishOpening(opened, relinked = true, onOpened = onOpened)
            }
            VaultKeyringSession.leaveVault()
            runOnUiThread {
                busy = false
                indexUnreadable = true
            }
            return
        }
        // D5. The vault's capture public key, for the memo screen to know whose memo it is — and a
        // memo still recording, sealed to this vault: the unlock goes back to it.
        val kp = VaultKeys.captureKeypair(opened.vmk)
        openCaptureKey = kp.publicKey
        kp.zero()
        val recordingHere = VoiceMemo.current?.recording == true && memoHere() != null
        runOnUiThread {
            busy = false
            openPosition = VaultKeyringSession.openVaultPosition()
            unlocked = true
            if (recordingHere) {
                voiceMemoProblem = null
                voiceMemoPermitted = true
                voiceMemoScreen = true
            }
            indexRelinked = relinked
            loadIndex()
            // `backup-screen-status-first` D12. The drawer's Backup row reads this.
            backupStatus = null
            refreshBackupStatus()
            // In the same frame as the grid, so a screen raised over it never shows the grid first.
            onOpened?.invoke()
        }
        // `per-vault-intruder-capture` D4. A vault with its own passphrase takes in what its sheet
        // captured while it was locked, into its own capture store.
        ingestVaultCaptures(opened)
        // The per-vault housekeeping the unlock used to do, now that there is a
        // vault to do it for. Off the main thread, as it always was.
        afterUnlock()
    }

    /**
     * D4, D7. Takes this vault's queued captures and notices in from the keyring's capture store,
     * backfills their thumbnails, and sets the vault's badge from its own capture store. On the
     * caller's background thread. A vault without its own passphrase has no capture and no badge.
     */
    private fun ingestVaultCaptures(opened: OpenVault) {
        val position = VaultKeyringSession.openVaultPosition()
        val protectedVault = VaultKeyringSession.vaults().firstOrNull { it.position == position }?.hasOwnPassphrase == true
        runOnUiThread { vaultCapturesUnseen = false }
        if (!protectedVault) return
        val unseen = runCatching {
            VaultKeyringSession.captureStoreKey()?.let { ringKey ->
                try {
                    val landed = CaptureIngest.ingestVault(blobs, OpenVault(ringKey, VaultKeys.metadataBlobId(ringKey), -1), opened)
                    if (landed > 0) Entitlement.startTrialIfNeeded(this)
                } finally {
                    ringKey.fill(0)
                }
            }
            val key = VaultKeys.captureStoreKey(opened.vmk)
            try {
                val id = VaultKeys.metadataBlobId(key)
                if (!blobs.fileOf(id).exists()) return@runCatching false
                val db = MetadataDb.commit(blobs, id, key) { db -> Thumbnails.backfill(blobs, ThumbnailStore(blobs), db, key); db }
                unseenIn(db)
            } finally {
                key.fill(0)
            }
        }.onFailure { Log.w("VaultCapture", "vault captures not taken in: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        runOnUiThread {
            if (VaultKeyringSession.openVaultPosition() == position) vaultCapturesUnseen = unseen
        }
    }

    /**
     * Finds this vault's index and points its keyring entry at it —
     * `index-pointer-recovery`. Returns whether it did.
     *
     * **The search reads and the repair writes exactly once**, and only on a hit: a
     * vault whose index is genuinely gone must not be written to on the way to being
     * reported, because a build that reads more may open it tomorrow.
     *
     * `openVaultPosition` rather than a parameter — the session knows which vault it
     * just tried to open, and threading a position through two call sites for one
     * failure path would be two places to get it wrong.
     */
    private fun relinkIndex(opened: OpenVault): Boolean {
        val position = VaultKeyringSession.openVaultPosition()
        if (position < 0) return false

        val found = try {
            MetadataDb.findIndex(blobs, opened.vmk)
        } catch (e: MetadataDb.NewerVersionException) {
            // The index is there and this build cannot read it. That is its own
            // message and not a failure to find anything.
            Log.w("VaultIndex", "the index found was written by a newer build", e)
            VaultKeyringSession.leaveVault()
            runOnUiThread {
                busy = false
                newerVersion = true
            }
            return false
        } ?: return false

        if (!VaultKeyringSession.relinkIndex(blobs, position, found)) return false
        found.copyInto(opened.metadataBlobId)
        Log.w("VaultIndex", "the index was found again and the entry relinked")
        return true
    }

    /**
     * Leaves the open vault for the list — `vault-keyring`.
     *
     * The keyring stays open; this vault's key is zeroed and its decrypted thumbnails
     * go with it. Everything that is a view *of* the vault goes too, for the reason
     * [lockVault] clears the same things: a folder path or a selection over a vault
     * nothing can read is a list of rows nothing can read.
     */
    private fun leaveVault() {
        // `viewer-swipe-navigation`. Leaving drops this vault's key exactly as a lock does,
        // so what is held goes to it first.
        writeResumeBeforeLock()
        // `per-vault-intruder-capture` D6. The vault's timeline and its key go with the vault.
        if (captureVault) closeIntruderScreen()
        vaultCapturesUnseen = false
        VaultKeyringSession.leaveVault()
        unlocked = false
        vaultEntries = VaultKeyringSession.vaults()
        index = MetadataDb.empty()
        open = null
        trash = false
        folderPath = emptyList()
        selection = emptySet()
        query = ""
        // `vault-destinations`. Same argument as the level above it: where the user was
        // looking is a view of rows this session can no longer read. The next vault opens
        // on Media showing everything, whichever destination this one was left on.
        destination = VaultDestination.Default
        mediaFilter = MediaFilter.Default
        places.clear()
        purchaseScreen = false
        destinationSettings = false
        // `backup-screen-status-first`. The status is this vault's, and this vault is
        // no longer open.
        backupStatus = null
        mirrorDestination = null
    }

    /**
     * P2-25's message, as a list so it appends to the lock screen's warnings with
     * no second surface. Empty when it does not apply, which is always except after
     * a rollback.
     */
    private fun newerVersionWarning(): List<String> =
        if (newerVersion) listOf(getString(NEWER_VERSION)) else emptyList()

    /**
     * The two index warnings — `index-pointer-recovery`.
     *
     * Beside [newerVersionWarning] and in the same list, because they are the same
     * class of event: the passphrase was right and the vault still did not open the
     * way it should have. A second surface for that would be a second place to keep
     * correct.
     */
    private fun indexWarnings(): List<String> = buildList {
        if (indexRelinked) add(getString(INDEX_RELINKED))
        if (indexUnreadable) add(getString(INDEX_UNREADABLE))
    }

    /**
     * Face-down's lock — `face-down-lock-settings` D5. The Lock button's lock, after one confirm
     * haptic: this locks a phone the user is not looking at, so the hand is told. Through the
     * view rather than a `Vibrator`, so the phone's own touch-feedback setting governs it — since
     * Android 13 only privileged apps can play past it — and no permission is needed.
     */
    private fun faceDownLock() {
        performHaptic(android.view.HapticFeedbackConstants.CONFIRM)
        lockVault()
    }

    private fun lockVault() {
        lifecycleOps.lock()
        VaultKeyringSession.lock()
        resetForLock()
        // The user chose to lock. Throwing a prompt at them the instant they do
        // is the app arguing with them; the biometric control is right there.
        bioPrompted = true
    }

    /**
     * Every screen goes with the lock — `screen-off-lock-all-screens`.
     *
     * **One reset, and every lock runs it**: the Lock button and face-down through
     * [lockVault], screen-off and every other departure through `onStart`'s epoch check.
     * Two resets were how the screens drawn above the lock branch came to survive a
     * screen-off: the button's list was the only one that grew, and it had not grown to
     * cover them either. A flag added to this activity belongs here or in
     * `LockResetScanTest`'s list of state that is not the session's.
     *
     * Only screen state. The key has already gone by the time this runs, by whichever
     * path locked.
     */
    private fun resetForLock() {
        // `intruder-captures-at-home`. The capture store key and a move's KEK go with the
        // keyring, and so does every surface that showed what they opened.
        closeIntruderScreen()
        capturesLoaded = false
        captureMoveBusy = false
        capturesUnseen = false
        vaultCapturesUnseen = false
        exportCaptures = false
        // `first-run-onboarding`. A lock landing mid-flow pauses the flow: the lock
        // screen is what must be drawn, and unlocking the keyring that reached the
        // step resumes it there. Called before the flags below, so the `when` never
        // has both a pending step and a locked vault.
        pauseOnboarding()
        unlocked = false
        openPosition = -1
        keyringOpen = false
        // `backup-screen-status-first`. The open vault's backup status goes with its key.
        backupStatus = null
        // `backup-to-cloud-apps` D7. A restore in progress is abandoned by a lock.
        pendingBackup = null
        restoringOntoEmpty = false
        recoveryInUse = false
        recoveryBackupProblem = null
        decoySetUp = false
        vaultEntries = emptyList()
        editingVault = null
        // Edit vault's unsaved name is text the user typed, and does not outlive the session.
        editDraftName = ""
        editDraftAppearance = VaultAppearance.of(0)
        passphraseScreen = null
        passphraseWrong = false
        passphraseProblem = null
        passphraseStatus = null
        closeDestroy()
        recoveringVault = false
        promptingVault = null
        vaultPassphraseFailed = false
        vaultRecoveryFailed = false
        vaultListProblem = null
        // P2-12. A trash view over a locked vault is a list of items nothing can
        // read; it goes with the session, like the open item and the selection.
        trash = false
        // P2-13, `folder-system`. Same argument: a level is a view of rows nothing
        // can read, and the folder surfaces are screens about them.
        folderPath = emptyList()
        movingFolder = null
        naming = null
        folderSheet = null
        covering = null
        deletingFolder = null
        folderProblem = null
        // P2-15. Same argument, and one more: a query is text the user wrote about
        // what they were looking for, which does not outlive the unlocked session.
        query = ""
        // `vault-destinations`. Same argument again, for where they were looking and for
        // what each destination was left standing in.
        destination = VaultDestination.Default
        mediaFilter = MediaFilter.Default
        places.clear()
        movingToFolder = false
        // `viewer-chrome-and-editor`. The picker's scope and the editor's subject are
        // both one item the session was showing, and neither outlives the session.
        // **The editor discards rather than saves**: an unsaved edit is a plaintext
        // derivative of a vault item, and I3 does not let one survive a lock.
        movingItem = null
        closeEditor()
        // P2-14. Text the user typed about their own content does not outlive the
        // unlocked session, and neither does the item it was being typed about.
        renaming = null
        // P2-21. Text the user typed about their own content does not outlive the
        // unlocked session — P2-14's rule for the rename field, and this field holds
        // more of it. Unsaved work is lost, which is what locking means.
        editingNote = null
        noteScreen = false
        // P2-17. A lock destroys a camera recording in progress: `discardRecording` closes
        // its pipe and deletes the blobs it claimed. **Not a memo** — `destination-settings-
        // and-slideshow` D5: a memo keeps recording through the lock under its own key, and
        // one held for listen-back already lost its key in the before-lock hook and waits,
        // sealed, for the next unlock. Only the screen goes.
        discardRecording()
        voiceMemoScreen = false
        voiceMemoProblem = null
        confirmingMemoDiscard = false
        memoUnsaved = false
        open = null
        // P2-11. A selection is a set of items the user could see when they made
        // it; behind a lock screen it is a set they cannot.
        selection = emptySet()
        // `remote-pairing`. The contact chooser, a pairing in progress and a send's choices are
        // screens of the session; the invite bytes in particular do not outlive a lock. A
        // waiting session itself is on disk and is listed again when Contacts next opens.
        addingContact = false
        remoteStep = null
        remoteInvite = null
        pairingWaiting = emptyList()
        shareSelections = ShareSelections(recipient = null)
        // `settings-separation`. The Purchase screen goes with the session because
        // it was drawn over a list that is no longer there. The expanded action set goes
        // because the grid it was floating over is gone, and re-appearing expanded on
        // the next unlock would be the app resuming a decision the user ended by
        // locking.
        purchaseScreen = false
        destinationSettings = false
        fabExpanded = false
        index = MetadataDb.empty()
        digest = emptyList()
        // `remove-originals-on-import`. An offer is about what this session imported;
        // shown after the next unlock it could stand in front of whoever is watching that
        // unlock, in whichever vault it opens.
        originalsOffer = null
        gallerySources.clear()
        removingOriginals = false
        originalsRemoved = false
        // `screen-off-lock-all-screens`. The screens drawn **above** the lock branch, which
        // is why each has to go here rather than being hidden by it. The kit's words were
        // sealed by the before-lock hook; what is dropped here is the screen.
        recoveryKit = null
        recoveryKitCreationLeft = VaultKeyring.KIT_VIEWS
        recoveryKitVault = null
        recoveryKitDecoy = false
        // `recovery-practice`. The check goes with the kit, so a resumed kit shows its words
        // with an empty acknowledgement (D5); the practice screens go with what was typed,
        // writing nothing, so the next unlock decides afresh (D1, 4.4).
        kitChecking = false
        kitAcknowledged = false
        closePractice()
        closeKitView()
        closeBackup()
        closeArchiveImport()
        closePanic()
        closeCamera()
        closeDecoy()
        // `new-vault-setup-flow`. Closed without asking, its passphrases zeroed (D6).
        closeVaultFlow()
        settingUpIdentity = null
        identityScreen = false
        biometricScreen = false
        faceDownScreen = false
        sharePreview = null
        closePairing()
        closeContacts()
        closeCrashLog()
        licencesScreen = false
        licenceOpen = null
        endShareOpen()
        endShare()
        closeExport()
        closeRecover()
        // And the sheets drawn outside the `when`, which no branch order can hide.
        photoDetailsSheet = false
        languageSheet = false
        snackbars.currentSnackbarData?.dismiss()
        closeCommitSheet()
        noteOptions = null
        captureSheet = false
        pendingDelete = null
        pendingDeleteSelection = false
        pendingPurge = false
        confirmingNewIdentity = false
        // `delete-vault` D2 reads an **empty index** as "everything came out", and the lock
        // has just emptied it. A destroy left armed across a lock would read the lock as a
        // finished export — so it goes with the screen that armed it.
        destroyAfterExport = null
        returnStarted = false
        destroyProblem = null
        // What this session imported, for `originalsOffer`'s reason.
        importLanded = null
    }

    // --- biometric unlock (P1-9) ---------------------------------------------

    /**
     * The prompt, and the only place in the app that shows one.
     *
     * `BIOMETRIC_STRONG` alone. `DEVICE_CREDENTIAL` appears nowhere in this app:
     * a vault that opens to a four-digit device PIN is a vault with a four-digit
     * passphrase, whatever the passphrase says. The key spec refuses it too, so
     * the two settings agree rather than merely coexisting.
     *
     * [negative] is the button that backs out: "Use passphrase", because every prompt this
     * raises sits over the vault's own screens. None is ever raised over a cover —
     * `cover-unlock-methods` removed the one that was.
     */
    private fun authenticate(
        title: String,
        negative: String,
        cipher: Cipher,
        /**
         * `cover-fingerprint-dots` D6. A biometric the prompt read and rejected. The unlock
         * prompt passes intruder capture; the enable prompt, raised from inside the vault,
         * passes nothing.
         */
        onFailed: (() -> Unit)? = null,
        /**
         * `recovery-practice` D4. The prompt ended without a match — cancelled, locked out or
         * dismissed. The practice confirmation says so under its button.
         */
        onError: (() -> Unit)? = null,
        onOpened: (Cipher) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onOpened)
                }

                // Nothing on screen: the prompt's own UI already says "not recognised" and
                // counts lockout. Duplicating that on the screen behind it would say it twice,
                // and neither copy would be the one the user is looking at.
                override fun onAuthenticationFailed() {
                    onFailed?.invoke()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError?.invoke()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(negative)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * Fires the unlock prompt once per arrival at the unlock screen, and **never while a
     * cover stands in front of it** — `cover-biometric-suppression`.
     *
     * `BiometricPrompt` is a system-owned sheet carrying this app's own title and its own
     * negative button. Raised over the calculator it reads "Unlock vault" and "Use
     * passphrase" to whoever is holding the phone, automatically, before a single control
     * has been touched. That is the entire fact the cover exists to withhold, and it
     * defeated the cover, the launcher alias, the recents label and §10's
     * plaintext-preferences reasoning in one frame.
     *
     * ***The position of the `coverShowing` guard is load-bearing.*** It sits with the
     * other early returns, ahead of `bioPrompted = true`, because a suppression *after*
     * that assignment burns the one-shot: the prompt would be withheld over the cover and
     * then never offered on the unlock screen the trigger opens, leaving biometric unlock
     * reachable only by tapping the unlock screen's biometric control. That is the
     * failure `onStart`'s reset of this same flag already fixed once.
     *
     * The prompt is not lost, only deferred — [coverTrigger] fires it when a correct
     * trigger takes the cover down.
     */
    private fun autoPromptBiometric() {
        if (coverShowing || bioPrompted || unlocked || !hasVault || !bioEnabled) return
        bioPrompted = true
        unlockWithBiometric()
    }

    /**
     * Opens the vault from the wrap — the same `OpenVault` a passphrase produces,
     * handed to the same [VaultLifecycle.open], so the session, the §6 chaff
     * churn and the partial sweep are identical to a passphrase unlock.
     */
    private fun unlockWithBiometric() {
        val cipher = try {
            bio.decryptCipher()
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException (enrolment changed), a wiped
            // alias, a truncated file — one outcome, and it is never a crash.
            Log.i("VaultBio", "wrap unusable: ${e.javaClass.simpleName}")
            biometricLost()
            return
        }
        authenticate(
            getString(R.string.bio_prompt_unlock_title),
            getString(R.string.bio_prompt_use_passphrase),
            cipher,
            // `cover-fingerprint-dots` D6: a rejected finger or face at the unlock screen is a
            // failed unlock, captured as a wrong passphrase is.
            onFailed = { fireIntruderCaptures(CaptureRecord.Stage(CaptureRecord.STAGE_UNLOCK_BIOMETRIC)) },
        ) { authenticated ->
            busy = true
            thread {
                // `vault-keyring`. The wrap seals the **slot's** payload, so what
                // comes back is a keyring reference, exactly as a passphrase unlock
                // yields one — and it lands on the list for the same reason, with no
                // vault open and no vault key unwrapped.
                //
                // A wrap written before the migration names a vault no slot points at.
                // It cannot be migrated here — that needs a KEK and this path has no
                // passphrase — so it is treated as a biometric unlock that did not
                // work, which sends the user to the passphrase field that fixes it.
                val opened = runCatching { bio.read(authenticated) }.getOrNull()
                val resolved = opened != null &&
                    VaultKeyringSession.resolveFromWrap(blobs, opened)
                runOnUiThread {
                    busy = false
                    if (resolved) {
                        keyringOpen = true
                        decoySetUp = VaultKeyringSession.hasDecoy()
                        vaultEntries = VaultKeyringSession.vaults()
                        // `recovery-practice` D1. Decided here, before the list is drawn, so the
                        // list never appears and is then covered. Never during first run.
                        if (onboardingStep == null && practice.isDue(System.currentTimeMillis())) {
                            practiceState.clear()
                            practiceShowing = true
                        }
                        // `cover-biometric-suppression`. With the guard in
                        // `autoPromptBiometric` the prompt is raised only after the
                        // cover is already down, and the biometric control lives on a
                        // screen the cover covers — so this is nearly unreachable.
                        // Nearly is not never: the prompt is asynchronous, so a user
                        // can raise it, background the app, and return to an `onStart`
                        // that re-raises the cover while this callback is in flight.
                        // Without this line they land on a cover with the vault open
                        // behind it and no way forward but the trigger again.
                        //
                        // Not a weakening. The only path to this prompt was the
                        // trigger, and whoever answered it has done strictly more than
                        // the trigger asks.
                        coverShowing = false
                    } else {
                        biometricLost()
                    }
                }
            }
        }
    }

    /** Enable: wrap the open vault behind a prompt of its own. */
    private fun enableBiometric() {
        // `vault-keyring`. **The keyring's reference, not the open vault's.** §4.1's
        // wrap is a second way to open what a *slot* opens, and a slot holds a keyring
        // — a wrap holding one vault would open it outside every keyring, unlisted and
        // unreachable afterwards.
        val vault = VaultKeyringSession.wrapPayload() ?: return
        val cipher = try {
            bio.encryptCipher()
        } catch (e: Exception) {
            Log.i("VaultBio", "could not create a wrap key: ${e.javaClass.simpleName}")
            disableWrap()
            bioEnabled = false
            return
        }
        authenticate(getString(BiometricWrap.ENABLE_LABEL), getString(R.string.bio_prompt_use_passphrase), cipher) { authenticated ->
            thread {
                val ok = runCatching { bio.write(authenticated, vault) }.isSuccess
                runOnUiThread {
                    bioEnabled = ok.also { if (!it) disableWrap() }
                    if (ok) practice.start(System.currentTimeMillis())
                }
            }
        }
    }

    /** P2-20. The same shape as [toggleBiometric], with no hardware to negotiate. */
    /**
     * Commits a new note or an edit — P2-21.
     *
     * Null [note] is a creation; non-null is an edit in place. Both go through
     * `MetadataDb.commit` inside `VaultNote`, never a bare load/save.
     *
     * A refusal returns null and leaves the editor open with the text still in it.
     * `NoteScreen` has already checked both the title and the cap, so reaching this
     * branch means something changed underneath; losing the typing to it would be
     * the failure the confirmation exists to prevent.
     */
    private fun saveNote(note: VaultItem?, title: String, body: String) {
        val vmk = VaultSession.vmk() ?: return
        val id = VaultSession.metadataBlobId() ?: return

        val before = index.items.mapNotNull { it.noteId?.let { id -> id.joinToString("") { b -> "%02x".format(b) } } }.toSet()
        val updated = if (note == null) {
            // The folder the editor was opened from — a note begun inside one belongs
            // in it, the rule the camera already followed. Read here rather than held
            // from the moment the editor opened because the editor covers the grid:
            // there is no way to navigate out of a folder while it is on screen.
            VaultNote.add(blobs, id, vmk, title, body, folderPath.lastOrNull())
                // `one-time-purchase` D4. Creation is an add; an edit (below) is not.
                ?.also { Entitlement.startTrialIfNeeded(this) }
        } else {
            VaultNote.update(blobs, id, vmk, note, title, body)
        } ?: return

        index = updated
        // **The editor stays open.** `notes-as-documents` replaced the Save button
        // with an autosave, so this runs while the user is still typing — closing
        // here would shut the screen out from under them 800ms after they paused.
        //
        // The row it is editing is re-read either way: after the first save of a new
        // note the editor is no longer editing nothing, and without this the next
        // autosave would insert a second note rather than update the first.
        editingNote = updated.items.firstOrNull { item ->
            val id = item.noteId?.let { n -> n.joinToString("") { b -> "%02x".format(b) } } ?: return@firstOrNull false
            if (note != null) id == note.noteId?.let { n -> n.joinToString("") { b -> "%02x".format(b) } } else id !in before
        }
    }

    /**
     * One property of one note, changed and committed.
     *
     * Colour and pin are row fields and touch neither the text nor its blob, so they
     * are an index write and nothing more — which is why they do not go through
     * `VaultNote.update` and do not rewrite a megabyte to move a boolean.
     *
     * **It takes the note.** It used to read the editor's, which was the only place
     * either field could be changed from. Both are written from the grid's sheet now,
     * where the editor is closed and `editingNote` is null — reading it there would
     * write nothing at all and return as if it had worked.
     */
    private fun editNoteRow(note: VaultItem, change: (VaultItem) -> VaultItem) {
        val vmk = VaultSession.vmk() ?: return
        val id = VaultSession.metadataBlobId() ?: return
        index = MetadataDb.commit(blobs, id, vmk) { db ->
            val i = db.items.indexOfFirst { it.itemId == note.itemId }
            if (i >= 0) db.items[i] = change(db.items[i])
            db
        }
    }

    /**
     * The item decoded for the editor, at no more than the screen's own size —
     * `viewer-chrome-and-editor`.
     *
     * **Downscaled on purpose.** The editor previews with this and saves from the
     * full-resolution source, so this only has to be as good as the screen: decoding
     * a 48 MP photograph at full size to show it on a 1080p display costs ~190 MB of
     * heap to draw pixels nobody can see, and every slider drag re-renders from it.
     *
     * Composed with the item's rotation and its EXIF, like every other read — an
     * editor that started from an image lying on its side would have the user
     * straighten what the app had already turned.
     */
    private fun previewFor(item: VaultItem): Bitmap? {
        val vmk = VaultSession.vmk() ?: return null
        return runCatching {
            blobs.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
                val bytes = r.readRange(0, item.plaintextLength.toInt())
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight)
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: return@runCatching null
                val degrees = Thumbnailer.exifDegrees(bytes) + item.rotation
                Thumbnailer.oriented(decoded, degrees).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
        }.getOrNull()
    }

    /** The power-of-two sample that brings the longest edge under the preview cap. */
    private fun sampleFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= EDITOR_PREVIEW_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Commits an edit as a **new** item — `viewer-chrome-and-editor`.
     *
     * **The original is never touched.** It is what the user put in the vault, and an
     * editor that overwrote it would make a slider into permanent loss with no undo
     * across a lock. The copy keeps the original's `createdAt`, because it is still
     * that photograph, and takes its own `importedAt`, because it arrived now.
     *
     * **I3 all the way through.** The source is read through the chunk reader into
     * memory, the stack is applied once at full resolution, the result encodes into a
     * `ByteArrayOutputStream`, and the bytes go straight into the blob store — the
     * shape [storePhoto] uses for a capture. The plaintext array is zeroed in a
     * `finally`, and no file, cache entry or `Uri` exists at any point.
     *
     * Off the main thread, like every other write in this class: applying a stack to
     * a full-resolution bitmap and re-encoding it is not frame work.
     */
    private fun saveEdit(item: VaultItem, stack: ImageEdits.Stack) {
        val vmk = VaultSession.vmk() ?: return
        val indexId = VaultSession.metadataBlobId() ?: return
        if (stack.isEmpty) {
            closeEditor()
            return
        }

        busy = true
        thread {
            val claimed = mutableListOf<ByteArray>()
            val outcome = runCatching {
                val source = blobs.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
                    val bytes = r.readRange(0, item.plaintextLength.toInt())
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("the item did not decode")
                    val degrees = Thumbnailer.exifDegrees(bytes) + item.rotation
                    Thumbnailer.oriented(decoded, degrees).also {
                        if (it !== decoded) decoded.recycle()
                    }
                }

                val edited = ImageEdits.apply(source, stack)
                val jpeg = ByteArrayOutputStream().also {
                    edited.compress(Bitmap.CompressFormat.JPEG, EDITOR_QUALITY, it)
                }.toByteArray()
                if (edited !== source) edited.recycle()
                source.recycle()

                try {
                    val written = blobs.writeItem(ByteArrayInputStream(jpeg), vmk, claimed)
                    commitEdit(indexId, vmk, item, written)
                } finally {
                    jpeg.fill(0)
                }
            }

            runOnUiThread {
                busy = false
                outcome
                    .onSuccess {
                        index = it
                        closeEditor()
                    }
                    .onFailure { e ->
                        Log.w(TAG_EDIT, "an edit could not be saved", e)
                        // The blobs written before the failure belong to nothing.
                        claimed.forEach { runCatching { blobs.discardPartial(it) } }
                        closeEditor()
                    }
            }
        }
    }

    /**
     * The edited copy's row — `viewer-chrome-and-editor`.
     *
     * The name is the original's, suffixed the way a second capture in one second is
     * suffixed, so two edits of one photograph never collide. **The rotation is not
     * carried**: the turn is already in these pixels, applied before the stack was,
     * and carrying it as well would turn the copy twice.
     */
    private fun commitEdit(
        indexId: ByteArray,
        vmk: ByteArray,
        original: VaultItem,
        written: WrittenItem,
    ): MetadataDb {
        val at = System.currentTimeMillis()
        val provisional = VaultItem(
            blobIds = written.blobIds,
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = original.filename,
            album = null,
            folderId = original.folderId,
            createdAt = original.createdAt,
            importedAt = at,
            tags = emptyList(),
        )
        val made = Thumbnails.generate(blobs, provisional, vmk)
        return MetadataDb.commit(blobs, indexId, vmk) { db ->
            val name = VaultCamera.uniqueFilename(
                db.items.mapTo(HashSet()) { it.filename },
                original.filename,
            )
            var row = provisional.withFilename(name)
            made.webp?.let { webp ->
                row = row.withThumb(thumbs.stage(db, webp), made.durationMs)
            }
            db.items += row
            db
        }
    }

    /**
     * Leaves the editor, keeping nothing — `viewer-chrome-and-editor`.
     *
     * **There is no draft.** An unsaved edit is a plaintext derivative of a vault
     * item; one that survived a lock would be exactly the artifact I3 forbids, so
     * closing discards. Called by back, by the discard control, and by the lock.
     */
    private fun closeEditor() {
        editingImage = null
    }

    private fun closeNote() {
        noteScreen = false
        editingNote = null
    }

    private fun toggleFaceDown() {
        faceDownOn = !faceDownOn
        GridPreference.setFaceDown(this, faceDownOn)
    }

    /** `face-down-lock-settings` D2. Stored at once; the effect keyed on it re-arms the detector. */
    private fun chooseFaceDownLockAfter(ms: Long) {
        faceDownLockAfter = ms
        GridPreference.setFaceDownLockAfter(this, ms)
    }

    /**
     * Choose a launcher identity — P2-22.
     *
     * Choosing the one already shown returns having written nothing and disabled
     * nothing, so a stray tap on the current row cannot make a launcher redraw its
     * entry. `LauncherIdentity.switchTo` persists and enables, and disables nothing:
     * disabling the alias this activity came through would finish it, so the outgoing
     * entry goes when the app leaves the foreground. That is the whole of why this can
     * be called from a screen the user is looking at — `identity-switch-done-step`.
     *
     * The task description is re-applied immediately rather than at the next start,
     * so recents follows the choice without a relaunch.
     */
    /**
     * Whether the platform is currently in night mode — P2-23.
     *
     * Read from this activity's own configuration rather than from a system
     * service, because that is what `isSystemInDarkTheme()` reads in composition and
     * the two must not answer differently on the same frame.
     */
    private fun systemIsDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Pick the window theme from the stored choice — P2-23.
     *
     * Called before `super.onCreate`, which is early enough for `windowBackground`
     * and is the only reason this is a separate step from the composition's theme.
     * Not a `values-night/` qualifier: that resolves from the platform's flag, and
     * a user who forced Light on a dark-system phone is exactly the person who would
     * then get the dark ground on every launch.
     */
    /**
     * `app-languages` D3. The composition, in the app's language without a recreate. On
     * 33+ the platform's per-app language arrives as a configuration change this activity
     * handles, and `LocalConfiguration` already carries it. On 30–32 nothing arrives, so
     * the configuration handed down is the framework's with the chosen locales on top —
     * recomputed on a choice ([languageEpoch]) and on every configuration change, which
     * would otherwise hand the device's language back.
     */
    private fun setLocalizedContent(content: @Composable () -> Unit) = setContent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            content()
        } else {
            val base = LocalConfiguration.current
            val configuration = remember(base, languageEpoch) {
                Configuration(base).apply { setLocales(AppLanguage.locales(this@MainActivity)) }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, content = content)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.apply(this)
        // A change of the device's language renames the launcher entry; recents follows it.
        applyTaskDescription()
        // The runtime warnings are held as sentences, written once at start; a language
        // change in place rewrites them, or they stay in the language they were made in.
        warnings = RuntimeWarnings.check(this).map { it.message }
    }

    /**
     * First run's language screen: applied at once, in place, by [chooseLanguage]'s path but
     * with no Undo bar — the redrawn screen is the confirmation, and every other language is a
     * tap away on it (`onboarding-language-screen` D3).
     */
    private fun previewLanguage(choice: AppLanguage.Choice?) {
        AppLanguage.set(this, choice)
        languageEpoch++
    }

    /**
     * The Language sheet's choice, made once the sheet has closed — applied in place (D3), then
     * `language-picker-redesign` D2's snackbar: the new language named in the new language, and
     * Undo in the language that was on screen before, so whoever cannot read what they landed in
     * can still find the way back. Undo restores the previous choice, System default included,
     * and posts nothing further. A change while a snackbar shows replaces it, so Undo always
     * means "back to before the latest tap".
     *
     * Both lines are read from resources configured for their language rather than from this
     * activity's, which on 33+ still hold the old language until the configuration change lands.
     */
    private fun chooseLanguage(choice: AppLanguage.Choice?) {
        val previous = AppLanguage.current(this)
        val device = AppLanguage.systemLocales(this)
        val previousTag = previous?.tag ?: AppLanguage.systemResolved(this).tag
        val undo = AppLanguage.localized(this, previous?.let { LocaleList(it.locale) } ?: device)
            .getString(R.string.undo)
        val now = AppLanguage.localized(this, choice?.let { LocaleList(it.locale) } ?: device)
        val name = choice?.ownName ?: now.getString(R.string.language_system_default)
        val message = now.getString(R.string.language_changed, name)
        val text = buildAnnotatedString {
            append(message)
            // The language's own name carries its own locale, as on the sheet.
            val at = message.indexOf(name)
            if (choice != null && at >= 0) {
                addStyle(SpanStyle(localeList = ComposeLocaleList(choice.tag)), at, at + name.length)
            }
        }
        val action = buildAnnotatedString {
            withStyle(SpanStyle(localeList = ComposeLocaleList(previousTag))) { append(undo) }
        }

        AppLanguage.set(this, choice)
        languageEpoch++
        snackbars.currentSnackbarData?.dismiss()
        lifecycleScope.launch {
            if (snackbars.showSnackbar(LanguageSnack(text, action)) == SnackbarResult.ActionPerformed) {
                AppLanguage.set(this@MainActivity, previous)
                languageEpoch++
            }
        }
    }

    /** D2's snackbar, carrying its lines with their locales — `SnackbarVisuals` holds plain strings. */
    private class LanguageSnack(val text: AnnotatedString, val action: AnnotatedString) : SnackbarVisuals {
        override val message = text.text
        override val actionLabel = action.text
        override val withDismissAction = false
        override val duration = SnackbarDuration.Long
    }

    private fun applyWindowTheme() {
        val dark = ThemeChoice.resolve(GridPreference.appearance(this), systemIsDark())
        setTheme(if (dark) R.style.Theme_Vault else R.style.Theme_Vault_Light)
    }

    /**
     * Bar icon colours from the **resolved** scheme — P2-23.
     *
     * `SystemBarStyle.dark` means light icons for a dark ground; `.light` takes the
     * scrim arguments it uses on the API levels that need them. Re-applied on a
     * change so the bars follow without a relaunch.
     */
    private fun applyEdgeToEdge() {
        val dark = ThemeChoice.resolve(appearance, systemIsDark())
        val ground = (if (dark) VaultPalette.Dark else VaultPalette.Light).surface.toArgb()
        val style = if (dark) {
            SystemBarStyle.dark(ground)
        } else {
            SystemBarStyle.light(ground, ground)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /**
     * Choose a colour scheme — P2-23.
     *
     * Choosing the one already in effect writes nothing and re-themes nothing.
     * The palette follows immediately because [appearance] is Compose state; the
     * bars are told separately because they are not part of the composition; and the
     * *window* background is not re-applied, deliberately — it is behind the app's
     * own ground from here on and setting it would be a repaint nobody sees.
     */
    /** `exif-strip-choice`. The sheet's import switch, applied as it is flipped. */
    private fun chooseStripOnImport(on: Boolean) {
        stripExifOnImport = on
        GridPreference.setStripExifOnImport(this, on)
    }

    /** `exif-strip-choice`. Privacy or Custom. Custom's own selection survives a return to Privacy. */
    private fun choosePhotoDetailsPreset(preset: PhotoDetailsPreset) {
        photoDetailsPreset = preset
        GridPreference.setPhotoDetailsPreset(this, preset)
    }

    /** `exif-strip-choice`. One category under Custom; on means removed. */
    private fun setPhotoDetail(detail: PhotoDetail, removed: Boolean) {
        photoDetailsCustom = if (removed) photoDetailsCustom + detail else photoDetailsCustom - detail
        GridPreference.setPhotoDetailsCustom(this, photoDetailsCustom)
    }

    private fun chooseAppearance(chosen: ThemeChoice) {
        if (chosen != appearance) {
            appearance = chosen
            GridPreference.setAppearance(this, chosen)
            applyEdgeToEdge()
        }
    }

    // --- P2-24: the covers ---------------------------------------------------

    /**
     * A cover offered a trigger — P2-24.
     *
     * ***Everything about this is silent on the cover.*** `CoverTrigger.attempt` checks the
     * rate limiter first and returns the same [Outcome] for a wrong trigger and a limited one;
     * this treats both the same. No toast, no haptic, no visual change on hidden dots — every
     * one of those would confirm that a trigger exists and therefore that a vault does.
     *
     * ***The trigger is not the credential.*** On a match this dismisses the cover and nothing
     * more — on shown dots as anywhere else (`cover-hold-dots`): the ordinary `when` then decides, which means the
     * unlock screen and the passphrase. With a session already live it lands on the grid,
     * because the passphrase has already been given — the cover was never an
     * authentication step and must not become one by accident.
     *
     * ***It is still not the credential after `cover-biometric-suppression`.*** The call
     * to [autoPromptBiometric] below authenticates nothing: it dismisses a screen, and the
     * prompt that screen was suppressing is then free to fire against the same wrap, the
     * same Keystore key and the same strong-biometric binding it always used. Nothing here
     * derives key material, and a user with no wrap reaches the passphrase field exactly
     * as before.
     *
     * It fires **only** inside the accepted branch. `Rejected` and `Limited` never raise it,
     * which keeps a wrong trigger and a rate-limited one the same event to a bystander — a
     * prompt on either would be the confirmation the whole silence rule exists to withhold.
     *
     * ***A rejected or limited pattern*** fires intruder capture, flagged for shown dots and for
     * the limiter; on the music player's and voice recorder's hidden dots the cover still changes
     * in no way, and only the system's camera or microphone indicator shows. On shown dots it shows
     * the error state (`cover-hold-dots` D4), because whoever sees them has already found the way
     * to them.
     *
     * ***On the calculator*** (`calculator-dots-methods` D4) a sequence fires nothing, right or
     * wrong, except that a right one on **Button sequence, then pattern** brings up the dots and
     * leaves the cover up. A pattern counts only on dots its sequence or its hold brought up —
     * there is no grid while they are hidden, and a stray one is ignored rather than judged.
     */
    private fun coverTrigger(input: Trigger) {
        val cover = identity.cover ?: return
        // D4. While the error stands, the dots judge nothing.
        if (coverDotsError) return
        val dotsShown = coverDots
        if (cover == Cover.Calculator && input is Trigger.Pattern && !dotsShown) return
        when (val outcome = CoverTrigger.attempt(this, cover, input)) {
            Outcome.Accepted -> {
                // The first of two stages: the dots, and the cover stays.
                if (input is Trigger.Keys && CoverTrigger.calculatorMethod(this) == CalculatorMethod.SequenceThenPattern) {
                    showCoverDots()
                    return
                }
                coverDots = false
                coverShowing = false
                // The one moment the cover is known to be coming down, and already a moment
                // the screen changes — so the deferred prompt adds no observable event that
                // was not there anyway.
                autoPromptBiometric()
            }
            // Rejected and Limited are the same event: for the calculator's sequence nothing at
            // all; for a pattern the same capture, and on shown dots the same error.
            Outcome.Rejected, Outcome.Limited -> {
                if (input is Trigger.Keys) return
                val flags = (if (dotsShown) CaptureRecord.FLAG_DOTS_SHOWN else 0) or
                    (if (outcome == Outcome.Limited) CaptureRecord.FLAG_LIMITED else 0)
                fireIntruderCaptures(
                    CaptureRecord.Stage(CaptureRecord.STAGE_COVER_PATTERN, flags, CaptureRecord.coverCode(cover)),
                )
                if (dotsShown) showCoverDotsError()
            }
        }
    }

    // --- `cover-hold-dots`: the dots the owner's hold — or the calculator's sequence — brings up

    /**
     * D4. The owner held their area on the live cover, or entered the calculator's sequence on
     * **Button sequence, then pattern** (`calculator-dots-methods`): the dots come up. No haptic —
     * the dots are the response, and nothing on a live cover buzzes.
     */
    private fun showCoverDots() {
        if (!coverShowing || coverDots) return
        coverDots = true
    }

    /**
     * D4. A refused pattern on shown dots: the error state for [DOTS_ERROR_MS] with the reject
     * haptic, then the dots go — each try takes another hold.
     */
    private val endCoverDotsError = Runnable { hideCoverDots() }

    private fun showCoverDotsError() {
        coverDotsError = true
        performHaptic(android.view.HapticFeedbackConstants.REJECT)
        coverHandler.postDelayed(endCoverDotsError, DOTS_ERROR_MS)
    }

    /** The dots down — Back, the error's end, and leaving the foreground. */
    private fun hideCoverDots() {
        coverHandler.removeCallbacks(endCoverDotsError)
        coverDots = false
        coverDotsError = false
    }

    private fun performHaptic(constant: Int) {
        window.decorView.performHapticFeedback(constant)
    }

    /**
     * Whether [cover] has the runtime grant it needs — `music-recorder-covers`.
     *
     * The calculator needs none. The music player needs to read the device's audio
     * library; the recorder needs the microphone, which this app already declares for
     * intruder capture and voice memos and which is granted separately here.
     */
    private fun coverGranted(cover: Cover?): Boolean = when (cover) {
        Cover.MusicPlayer -> MusicLibrary.hasAccess(this)
        Cover.VoiceRecorder -> checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        Cover.Calculator, null -> true
    }

    /**
     * Ask for the grant the cover being set up needs.
     *
     * ***The one place either cover's permission is requested, and it is reachable
     * only from identity setup.*** A cover never asks: the system dialog reads the
     * **application** label, which is the vault's real name and which no alias label
     * reaches, so a player that asked for music the first time it opened would
     * introduce the app by name to whoever was holding the phone. Setup asks instead,
     * from inside the vault, where the user already knows what the app is.
     *
     * **It used to be reachable from a live cover**, on the theory that a grant is
     * lost only when the user revokes it themselves. That was wrong twice over. The
     * theory undercounted — "Clear storage" strips runtime grants, and so does
     * Android's own hibernation of an app nobody has opened for months, which is
     * precisely what a cover identity is. And the consequence was worse than the
     * premise: the recorder's record control, the first thing anybody presses, put
     * "Allow Vault to record audio?" on screen. Both covers now state which Settings
     * toggle restores the grant and offer no control that could ask for it.
     *
     * What remains outside this change: the dialog raised from setup still carries the
     * application label, and so does the App info screen a user reaches by following
     * that instruction. Both are inside `FORMAT.md` §10's recorded Settings exposure.
     * Closing them means changing the application label itself, which is a naming
     * decision and not this function's to make.
     */
    private fun requestCoverGrant() {
        val permission = coverPermission(coverBeingSetUp()) ?: return
        AutoLock.awaitingResult(true)
        requestCoverPerm.launch(permission)
    }

    /**
     * The cover the grant question is currently about: the one being committed to, then
     * the one being set up, then whatever is worn.
     */
    private fun coverBeingSetUp(): Cover? =
        committingIdentity?.cover ?: settingUpIdentity?.cover ?: identity.cover

    /** The runtime permission a cover needs, or null where it needs none. */
    private fun coverPermission(cover: Cover?): String? = when (cover) {
        Cover.MusicPlayer -> MusicLibrary.permission
        Cover.VoiceRecorder -> android.Manifest.permission.RECORD_AUDIO
        Cover.Calculator, null -> null
    }

    /**
     * Store the confirmed trigger, **then** switch identity — P2-24.
     *
     * Strictly in that order, and the switch is skipped entirely if the trigger is
     * refused for being under its floor.
     *
     * Reached only from the setup flow's confirming step, which has already required
     * the gesture to be performed twice on the real cover and the two performances to
     * match. Nothing else in the app stores a trigger, and there is no longer any way
     * to produce one except by performing it.
     *
     * `cover-hold-dots`: the pattern covers' pattern is stored with the hold [area] that brings
     * up their dots, and without one it is not stored. `calculator-dots-methods` D6: the
     * calculator's [trigger], [area] and second-stage [dots] are what its chosen method needs, and
     * `set` removes whatever another method left behind.
     */
    private fun completeSetup(trigger: Trigger?, area: Int?, dots: Trigger.Pattern?) {
        val chosen = settingUpIdentity ?: return
        val cover = chosen.cover
        if (cover != null) {
            // No trigger, no switch, and in that order. `set` refuses one under its
            // floor, and a cover enabled without a trigger is one anybody could open —
            // the failure the whole flow exists to prevent.
            if (trigger == null || !CoverTrigger.set(this, cover, trigger, area, dots)) return
        }
        // `first-run-onboarding` 8.2. **Before the alias switches**, and that order
        // is the whole point: the switch changes which launcher entry exists, and an
        // install that flipped the entry with the flow still marked in progress would
        // relaunch behind a cover and land back in onboarding. `advanceOnboarding(null)`
        // is deliberately not used — it clears `settingUpIdentity`, and the Done step
        // below needs that to survive.
        if (onboardingStep == Onboarding.Step.Identity) {
            onboardingStep = null
            Onboarding.finish(this)
        }
        switchIdentity(chosen)
        // `settingUpIdentity` deliberately stays set: the flow's last step describes
        // the way back in, and that description is only true once the launcher entry
        // has changed. `closeIdentitySetup` clears it when the user dismisses.
    }

    private fun closeIdentitySetup() {
        settingUpIdentity = null
        // `first-run-onboarding`. Dismissing the identity setup during first run
        // ends the flow rather than falling back to the chooser underneath — the
        // user has answered the last step, whichever way they answered it.
        if (onboardingStep == Onboarding.Step.Identity) completeOnboardingStep()
    }

    /**
     * A row was tapped. Open its commit sheet — the switch has not begun.
     *
     * Every identity goes through the sheet, including the one already shown and the
     * vault's own: it is the only place the cost of a switch is stated, so no choice may
     * skip it. Choosing what is already active stays a no-op — `switchIdentity` holds
     * that guard, so a stray tap still writes nothing and disables nothing.
     */
    private fun chooseIdentity(chosen: LauncherIdentity) {
        identityScreen = false
        biometricScreen = false
        faceDownScreen = false
        // Read before the sheet opens, so its button knows whether a grant is owed —
        // `music-recorder-covers`.
        coverGrant = coverGranted(chosen.cover)
        commitState = CommitState.Commit
        committingIdentity = chosen
    }

    /**
     * The commit sheet's one button, whatever it currently says.
     *
     * Three outcomes and no fourth: a settings page for a refusal that will not be
     * asked again, trigger setup for a cover that has what it needs, and otherwise the
     * system dialog — raised **here**, by the press that chose the cover, with no screen
     * of ours in between. `cover-front-ends` forbids one.
     */
    private fun commitIdentity() {
        val chosen = committingIdentity ?: return
        if (commitState == CommitState.Blocked) {
            openAppSettings()
            return
        }
        if (coverGranted(chosen.cover)) {
            committingIdentity = null
            settingUpIdentity = chosen
            return
        }
        requestCoverGrant()
    }

    /** Dismissed. Nothing was stored and nothing was switched — the sheet writes nowhere. */
    private fun closeCommitSheet() {
        committingIdentity = null
        commitState = CommitState.Commit
    }

    /**
     * This app's own settings page, for a grant Android will no longer ask for.
     *
     * `awaitingResult` for `requestCoverGrant`'s reason: leaving the app must not
     * auto-lock the session the user is standing in the middle of a settings flow
     * inside. The page carries the **application** label — `FORMAT.md` §10's recorded
     * exposure, and unchanged by this route existing.
     */
    private fun openAppSettings() {
        AutoLock.awaitingResult(true)
        startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null),
            )
        )
    }

    private fun switchIdentity(chosen: LauncherIdentity) {
        if (chosen == identity) return
        LauncherIdentity.switchTo(this, chosen)
        identity = chosen
        coverGrant = coverGranted(chosen.cover)
        applyTaskDescription()
    }


    /**
     * Put the chosen identity's name and icon on the recents entry — P2-22.
     *
     * Recents reads the **running activity's** label, and the running component is
     * always this activity whichever alias routed to it — so without this the task
     * switcher keeps saying the application's name and one gesture undoes the
     * feature. The card's picture is the identity itself ([IdentityLayer],
     * `recents-shows-identity`), so after this nothing in recents names the app.
     *
     * `TaskDescription.Builder` arrived at API 33 and `minSdk` is 30, so this is one
     * version branch to the two-argument constructor below it — in preference to a
     * lint suppression that would sit in the file for the life of the app.
     */
    private fun applyTaskDescription() {
        // `app-languages` D5. In the device's language, not the app's: the launcher resolves
        // the alias label in the device's, and recents must say what the launcher says.
        val label = AppLanguage.localized(this, AppLanguage.systemLocales(this)).getString(identity.label)
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder()
                .setLabel(label)
                .setIcon(identity.iconRes)
                .build()
        } else {
            @Suppress("DEPRECATION")
            ActivityManager.TaskDescription(label, identity.iconRes)
        }
        setTaskDescription(description)
    }

    private fun toggleBiometric() {
        if (bioEnabled) {
            disableWrap()
            bioEnabled = false
        } else {
            enableBiometric()
        }
    }

    /** Every biometric failure ends here: both halves deleted, passphrase intact. */
    /**
     * P1-16. The one purchase. Play owns the sheet, the payment and the network;
     * this hands off to it and the answer comes back through [Billing.onChange].
     * Called from the Purchase screen, the send refusal and the keep refusal alike.
     */
    private fun buy() {
        purchaseProblem = null
        Billing.purchase(this) { code ->
            purchaseProblem =
                getString(R.string.purchase_unavailable, code)
        }
    }

    private fun biometricLost() {
        disableWrap()
        bioEnabled = false
        bioNotice = BIO_INVALIDATED
    }

    // --- data ---------------------------------------------------------------

    private fun loadIndex() {
        val vmk = VaultSession.vmk() ?: return
        val id = VaultSession.metadataBlobId() ?: return
        index = try {
            MetadataDb.load(blobs, id, vmk)
        } catch (e: MetadataDb.NewerVersionException) {
            // P2-25's rollback case. The vault is intact and this build simply
            // cannot read it, so it locks back down and says which it is — nothing
            // is written, deleted or repaired, and the message is not the one a
            // corrupt index gets.
            Log.w("VaultIndex", "index written by a newer build", e)
            newerVersion = true
            lockVault()
            return
        }
        // Written by the unlock that just happened; empty after a fresh create and
        // after a lock. Both unlock paths land here.
        digest = TamperLog.digest
        // Anything an interrupted import left without a thumbnail (FORMAT.md §8).
        //
        // `MetadataDb.backgroundWrite` and not a bare `thread`: it is the same thread on
        // the same schedule, held where the index's own serialisation lives so that
        // `MetadataDb.awaitQuiescent` can wait for it. A bare thread could not be waited
        // for, and a device-test teardown that deleted `blobs/` in front of one wrote a
        // dead vault's index over a live vault's — see that function.
        MetadataDb.backgroundWrite {
            // P2-9. On its own thread, at unlock, while imports may be committing.
            // The backfill mutates the in-memory index and the pack table, so both
            // the work and the save belong inside one commit rather than saving the
            // activity's copy over whatever landed meanwhile.
            MetadataDb.commit(blobs, id, vmk) { db ->
                val filled = Thumbnails.backfill(blobs, thumbs, db, vmk) > 0
                if (filled) {
                    runOnUiThread { index = db }
                }
            }
        }
    }

    /**
     * Imports through **WorkManager**, not through [MediaImport].
     *
     * P1-8 wired this to `MediaImport`, which writes a blob in one pass and has
     * no resume: killing the app mid-import lost the whole file. P1-7 had already
     * built the resumable pipeline; nothing connected it, and nothing noticed,
     * because P1-8's own process-kill clause could not be run without an unlock
     * screen. Corrected here, which is the only reason that clause can now pass.
     *
     * One request per URI, so a batch that dies halfway keeps what finished —
     * `ImportWorker` saves the index per completed item for the same reason.
     */
    private fun importAll(uris: List<Uri>, fromGallery: Boolean = false) {
        if (uris.isEmpty()) return
        val id = VaultSession.metadataBlobId() ?: return
        // `remove-originals-on-import`. A new import makes the removed line old news.
        originalsRemoved = false
        // `file-import` D4. What the vault held before this batch, so the observer can
        // name where the batch went. Only when no batch is running — see [importBaseline].
        if (importBaseline == null) importBaseline = index.items.map { it.itemId }.toSet()
        // P2-13. Read **now**, not when each item lands: navigating while a batch
        // runs must not move items that are still arriving. It rides in `WorkJobs`
        // in memory — a folder is text the user wrote about their own content, and
        // `work.db` is readable with `run-as`.
        val destination = folderPath.lastOrNull()

        val wm = WorkManager.getInstance(this)
        uris.forEach { uri ->
            val request = OneTimeWorkRequestBuilder<ImportWorker>()
                .addTag(ImportWorker.IMPORT_TAG)
                .build()
            // Registered before the enqueue, so the worker can never start and find
            // nothing. Held in memory only — Gate 1 found the URI, the blob ID and
            // the index ID in WorkManager's database when they travelled as input
            // data, and `WorkJobs` documents why they no longer do.
            WorkJobs.register(
                request.id,
                WorkJobs.ImportJob(
                    uri,
                    CopyOnWriteArrayList(),
                    id,
                    folderId = destination,
                ),
            )
            // Before the enqueue, for the reason the job is: the observer must never
            // see a running import it does not recognise as this batch's.
            importBatch += request.id
            if (fromGallery) gallerySources[request.id] = uri
            wm.enqueue(request)
        }
    }

    /** Reloads the index as imports land, so the grid fills in as they finish. */
    private fun watchImports() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(ImportWorker.IMPORT_TAG)
            .observe(this) { infos ->
                // This batch, never the whole history. `infos` carries every import
                // this install has run in the last week.
                val mine = infos.filter { it.id in importBatch }
                importing = mine.any { !it.state.isFinished }
                if (infos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    // P1-16. The trial starts at the first add that actually landed —
                    // this is the import route of `one-time-purchase` D4's list — not
                    // at install, not at the pick, not at an enqueue, and not at one
                    // that failed. Idempotent, so the batch case and every later add
                    // leave the date where it is.
                    Entitlement.startTrialIfNeeded(this)
                    // `cloud-mirror` D6. Beside the trial for the same reason: this
                    // is the branch that means bytes actually landed in `blobs/`. A
                    // FAILED import enqueues nothing, and `enqueue` is unique work,
                    // so a twenty-item batch produces one run rather than twenty.
                    // `backup-to-cloud-apps` D10. The import's index save has already told
                    // `BackupAuto`; nothing to enqueue here.
                    loadIndex()
                }

                // An import that silently did not happen, in an app that may hold
                // the only copy of something, is the worst failure mode available.
                val failed = mine.count { it.state == WorkInfo.State.FAILED }
                // `file-import` D4. Once the whole batch is finished, and only then:
                // a batch half-landed has not yet decided where it went.
                if (!importing) {
                    noteWhereTheBatchLanded(failed)
                    offerOriginals(mine)
                    // Finished, so it is no longer this batch. Left in place the
                    // count would keep reporting a failure the user has already been
                    // told about, on every later observation.
                    if (mine.isNotEmpty()) importBatch.clear()
                }
                notice = if (failed > 0) {
                    resources.getQuantityString(R.plurals.imports_failed, failed, failed)
                } else {
                    // The landed-elsewhere line, or null in the ordinary case where the
                    // items appeared under the user's thumb and said so themselves.
                    importLanded
                }
            }
    }

    /**
     * `remove-originals-on-import`. Once the whole batch has finished — so I6 has ruled on
     * every item in it — offers the originals of the photo-picker imports that verified.
     *
     * An import that failed is not counted: the failure line already speaks for it. One
     * that landed but cannot be offered — unverified, cloud-only, another profile's — is
     * counted, so the card can say it stays.
     */
    private fun offerOriginals(finished: List<WorkInfo>) {
        val photos = mutableListOf<Uri>()
        val videos = mutableListOf<Uri>()
        var unavailable = 0
        finished.forEach { info ->
            val picked = gallerySources.remove(info.id) ?: return@forEach
            if (info.state != WorkInfo.State.SUCCEEDED) return@forEach
            // Asked now, while the picker's grant is still live.
            val mime = runCatching { contentResolver.getType(picked) }.getOrNull()
            val original = OriginalRemoval.mediaStoreUri(picked, mime)
                ?.takeIf { info.outputData.getBoolean("deletable", false) }
            when {
                original == null -> unavailable++
                mime?.startsWith("video/") == true -> videos += original
                else -> photos += original
            }
        }
        val offer = OriginalRemoval.Offer(photos, videos, unavailable)
        val waiting = originalsOffer
        if (waiting == null && offer.isEmpty) return
        originalsOffer = waiting?.plus(offer) ?: offer
        originalsRemoved = false
    }

    /** Hands the offered originals to Android's delete confirmation. */
    private fun removeOriginals() {
        val offer = originalsOffer ?: return
        if (removingOriginals) return
        // ponytail: one request per tap; past Android's 2 000 the rest stay offered.
        val sent = offer.uris.take(OriginalRemoval.MAX_PER_REQUEST)
        val request = runCatching { MediaStore.createDeleteRequest(contentResolver, sent) }
            .getOrNull() ?: return
        originalsSent = sent
        removingOriginals = true
        AutoLock.awaitingResult(true)
        deleteOriginals.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    /**
     * Names where a finished import batch went, when it went nowhere near the user —
     * `file-import` D4, `DESIGN.md` §7.2a.
     *
     * ***An item's kind is decided by its filename, so a picker cannot promise a
     * destination.*** A `.jpg` chosen from Files is an image and lands on Media. Without
     * this line the user taps `+` on Files, picks a photograph, and watches nothing happen
     * on the screen they are looking at — which is the feature reading as broken.
     *
     * **Silent whenever any item landed here.** The items appearing under the thumb is the
     * feedback, and a message on top of it is noise. Silent too when the batch failed:
     * [watchImports] writes the failure and that one outranks this.
     *
     * **The user is not moved.** They chose where to stand; a line naming the destination
     * is cheaper than a navigation they then have to undo.
     */
    private fun noteWhereTheBatchLanded(failed: Int) {
        val baseline = importBaseline ?: return
        // Consumed whatever the answer is: the batch is finished, so this baseline can
        // only produce one message, and a stale one would count the next batch's items.
        importBaseline = null
        if (failed > 0) return

        // The decision itself is a free function in `VaultDestination.kt`: it has no
        // state, and a decision a test can read is one that can be argued with.
        importLanded =
            landedElsewhere(words(), index.items.filter { it.itemId !in baseline }, destination)
    }

    /**
     * Deletes staged partials that no unfinished work claims.
     *
     * A partial can be orphaned with no failure event at all — WorkManager's job
     * simply dropped. Keyed on WorkManager's own view rather than on file age: an
     * age heuristic eventually truncates a live import of a very large file, which
     * is the one thing a cleanup here must never do.
     */
    /**
     * The work every unlock does off the main thread — P2-12 named it.
     *
     * All three unlock paths (passphrase, biometric, recovery) route here, which is
     * why the purge lives in it rather than in each of them.
     *
     * **The purge unlinks blobs.** A purge interrupted between its commit and its
     * unlinks leaves blobs no row references — but the same commit that removed the
     * rows already recorded them as this vault's owned chaff
     * (`blob-ownership-by-record`), so nothing here has to go looking for them.
     * Each step is wrapped on its own, so a failure in one does not skip the
     * others and none of them can block an unlock.
     */
    private fun afterUnlock() {
        val purged = runCatching { purgeExpiredTrash() }.getOrDefault(0)
        runCatching { sweepPartials() }
        // P4-2. A share container outlives its send by design — `ACTION_SEND`
        // reports no completion and a messenger may read the URI after the sending
        // activity returned — so this is what bounds its life to one lock cycle.
        runCatching { ShareSend.sweep(this) }
        // P4-3's half of the same rule: a staged container is ciphertext nobody
        // needs once the flow that picked it is over. The ledger is deliberately
        // **not** swept — it is what makes a single-view share single-view.
        runCatching { ShareOpen.sweep(this) }
        // Only when something went: the index on screen is otherwise the one the
        // unlock already loaded, and a reload would restart a thumbnail backfill
        // nobody asked for.
        if (purged > 0) runOnUiThread { loadIndex() }
    }

    /**
     * P2-12. Destroys trashed items whose window has expired.
     *
     * @return how many went, so the screen is reloaded only when the list it is
     *   showing actually changed.
     */
    private fun purgeExpiredTrash(): Int {
        val vmk = VaultSession.vmk() ?: return 0
        val id = VaultSession.metadataBlobId() ?: return 0
        return TrashPurge.run(blobs, id, vmk)
    }

    private fun sweepPartials() {
        // Read from the in-memory job table, not from a tag. The tag used to be
        // `blob:<hex>`, which put a real blob's ID into WorkManager's database in
        // plaintext — the Gate 1 finding this change exists to close.
        //
        // After a process death the table is empty, so every partial is an orphan.
        // That is correct rather than merely convenient: the source URI's read
        // grant died with the process (P1-8.5), so none of them could be resumed.
        // Keyed on the table and never on file age, for the reason the sweep has
        // always had — an age heuristic eventually truncates a live import of a
        // very large file.
        // `destination-settings-and-slideshow` D5. A memo's last blob is a partial until it
        // stops — the one recording now, and one cut off by the process dying that waits for
        // its vault. Neither is an orphan.
        val swept = blobs.sweepUnclaimed {
            WorkJobs.claimedBlobIds() + MemoPending.claimedBlobIds(this) +
                VoiceMemo.current?.pendingBlobHex().orEmpty()
        }
        if (swept > 0) Log.i("VaultSweep", "swept $swept orphaned partial(s)")
    }

    private fun thumbnailFor(ref: ThumbRef): Bitmap? {
        val vmk = VaultSession.vmk() ?: return null
        return ThumbnailCache.get(ref) {
            runCatching { thumbs.read(index, ref, vmk) }.getOrNull()
        }
    }

    /**
     * Full-size decode for the viewer. In memory only — never a file (I3).
     *
     * Turned upright by [Thumbnailer.oriented], which is where the reason lives: the
     * decoder returns the sensor's pixel buffer and the angle is in the EXIF beside
     * it. The grid's tiles get the same treatment when their thumbnail is built.
     */
    private fun decodeFull(item: VaultItem, key: ByteArray? = VaultSession.vmk()): Bitmap? {
        val vmk = key ?: return null
        return runCatching {
            blobs.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
                val bytes = r.readRange(0, item.plaintextLength.toInt())
                // `media-settings`. Full resolution, or the screen's when Highest display
                // quality is off — never below the screen's long edge.
                val screenLongEdge = resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
                val decoded = decodeForViewer(bytes, mediaSettings.fullQuality, screenLongEdge)
                    ?: return@runCatching null
                // The composed angle: what the item's own bytes say, plus the turn
                // the user stored. `viewer-chrome-and-editor` — an imported
                // photograph is upright at rotation 0 and turns from there.
                val degrees = Thumbnailer.exifDegrees(bytes) + item.rotation
                Thumbnailer.oriented(decoded, degrees).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
        }.getOrNull()
    }
}
