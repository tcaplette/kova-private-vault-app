package com.app.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.app.vault.AppLanguage
import com.app.vault.BiometricWrap
import com.app.vault.LauncherIdentity
import com.app.vault.PhotoDetailsPreset
import com.app.vault.R
import com.app.vault.ThemeChoice
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The navigation drawer — two drawers in one sheet, and which one is drawn depends on
 * where it is opened: `settings-separation`, `vault-only-drawer`.
 *
 * ***This sheet held the app's settings once before, and it went badly.*** P2-7
 * replaced a settings *screen* with it and nine later tasks put a setting in it
 * because it was the only surface with room; it ended at 454 lines and twenty-six
 * parameters, with an unbounded album list in the middle of it, and a user with ten
 * albums found the panic credential ten rows further down than a user with none.
 * `settings-screen` moved everything back onto a screen and forbade this outright.
 *
 * **So why it is back, and why this is not that.** What failed then was that nothing
 * decided what belonged here — the drawer was where things landed. There is a rule
 * now, and it is in the spec: *a setting belongs to the open vault if changing it
 * changes only that vault, and to the install otherwise.* Each scope has its own
 * drawer, so neither kind can be read as the other.
 *
 * **Inside a vault** — [vaultName] non-null — the sheet opens with the home drawer's top,
 * the Purchase row and its rule (`vault-drawer-trial-and-destroy-pinned`): the trial's
 * state is the one app row the owner needs wherever they are working, and it changes no
 * vault. Under the rule the sheet is about that vault and nothing else: its name as the
 * heading, then Trash, Backup, Intruder capture (in a vault with its own passphrase), Open a
 * share… and Unvault Everything.
 * Places first; then the ones that keep watch over this vault; then what brings things in;
 * then what takes everything out. One rule, under the Purchase row and nowhere among the
 * vault's rows, because they are one group. No colour scheme chooser, because it
 * re-themes the whole app. With several vaults on one keyring, a row with no name over it
 * reads as the app's, so the heading is the subject and not decoration. Unvault
 * Everything holds the drawer's one primary colour.
 *
 * **Destroy Vault is not in the scroll.** It is pinned below it, in the slot the home
 * drawer gives the colour scheme chooser, as a full-width filled pill in `Danger` red
 * with white words — at the sheet's bottom edge however few rows there are and however
 * far they are scrolled, and never 8dp from the export.
 *
 * **On the home screen** — [vaultName] null — the sheet is the app's, in three groups
 * separated by rules, with the colour scheme chooser pinned below the scroll, alone:
 *
 * 1. **The entitlement** — the Purchase row, alone, and drawn by the same call as the
 *    vault drawer's first row. It is the row that is read rather than tapped, and the
 *    only one here that survives every gate on every device, so it is a first row by
 *    decision instead of by whatever happened to be drawn.
 * 2. **The install** — what the app is and what it does on this device, in the order the
 *    settings screen fixed: how it presents itself — App identity, then Language
 *    (`app-languages`) — then the concealment and hardware controls, then Photo details
 *    and Contacts.
 * 3. **Recovering a vault with its kit**, last, below its own rule. A way *in* to a vault
 *    that is not on the keyring yet. A group whose rows are all absent takes its rule
 *    with it: a rule above nothing is a rule about a group that is not there.
 *
 * The debug build adds a fourth, **Crash log**, below its own rule after the third —
 * `debug-crash-log-screen`. The release build has no such row.
 *
 * ***Intruder capture is in the home drawer*** — `intruder-captures-at-home`. Its mode flags
 * and its single destination are install preferences, it **fires at a failed unlock**, when
 * no vault is open, its destination is the keyring open at enrolment, and its captures are
 * held in that keyring's capture store, which no vault holds. Changing it changes no vault,
 * so the rule places it with the app's settings, directly after the panic credential.
 *
 * ***A vault with its own passphrase has an Intruder capture row of its own*** —
 * `per-vault-intruder-capture`. It guards that vault's passphrase, its modes are that vault's,
 * and its captures land in that vault: changing it changes only that vault, so the rule places
 * it in the vault's drawer, after Backup. It is the vault's own setting, not the home row drawn
 * twice. A vault without its own passphrase has no lock for it to guard, and no row — absent,
 * not disabled.
 *
 * The unbounded list that broke it the first time is gone too — the folders are on
 * the home screen since `folder-system`, so the row count here is fixed and the same
 * on every install.
 *
 * **The panic credential's and intruder capture's rows read identically whether or not
 * they are set up** — same position, same words, same glyph. A row that changed once one
 * existed would answer the question those features exist to leave unanswered.
 *
 * ***The decoy's row does change, and that is the point.*** A decoy is built once, and
 * a sheet that goes on offering to build one offers a second decoy to the person who
 * already has one. What makes it safe to say is **where the state is kept**: a bit in
 * the sealed keyring block, which the decoy's own keyring does not carry. Opened with the
 * decoy's passphrase, this row reads "Set up a decoy" exactly as it does on an install
 * that has none — so the row says something to its owner and nothing to whoever the decoy
 * was handed to. A device-wide preference would have said it to both.
 *
 * **Each hardware gate is its own, and none hides its row** — `unavailable-capability-states`.
 * A device with no strong biometric still has an accelerometer. A row the device cannot
 * use is drawn in [DrawerRow]'s unavailable form with its reason, because a row that
 * vanishes leaves the user unable to tell whether the feature does not exist, is broken,
 * or is somewhere else. Biometric unlock is not gated here at all: its row opens its own
 * screen on every device, and that screen's rows say what this device can use.
 *
 * The `Ltr` wrap below is the inner half of the end-edge flip and is unchanged — see
 * the note at the `CompositionLocalProvider`.
 */
@Composable
fun VaultDrawerSheet(
    /**
     * Recovering a vault from its own 24 words — `home-screen-chrome`.
     *
     * A way **in** to something rather than an action on what is on screen, which is
     * what the home drawer's third group holds — `drawer-row-order` moved it there from
     * the top; only Open-source licences comes after it. Null where it does not apply, and where it is null the group and its rule
     * are both absent.
     */
    onRecoverVault: (() -> Unit)? = null,
    /**
     * `backup-to-cloud-apps` D13. Brings back a vault on this list from its own backup — the
     * second and later vaults after a restore, whose entries came back with the keyring and
     * whose files did not. Null wherever [onRecoverVault] is.
     */
    onRestoreVault: (() -> Unit)? = null,
    /**
     * `recovery-own-slot-only`, D5. Adds a vault from a `.vault` file to this list. Null
     * wherever [onRecoverVault] is, so it is only in the list's drawer.
     */
    onImportVault: (() -> Unit)? = null,
    /**
     * The open vault's name, or null with none open — the one gate on which drawer is
     * drawn. Non-null draws the vault's drawer under this name and nothing of the app's;
     * null draws the app's and none of the vault's rows. The home screen passes null:
     * there is no vault there for a trash to belong to.
     */
    vaultName: String? = null,
    /** P2-12. This vault's trash, and no other vault's. */
    onTrash: () -> Unit = {},
    /** `cloud-mirror`. Where this vault is copied. */
    onBackup: () -> Unit = {},
    /**
     * `backup-screen-status-first` D12. Where this vault's backup stands, in a word or
     * two under the row — so a user who skipped setup sees "Off" without opening it.
     */
    backupLine: String? = null,
    /** The two faults — the folder unreachable, files that did not copy. Dot and words. */
    backupAttention: Boolean = false,
    /**
     * `delete-vault`. Raises the confirmation, which names the vault and holds the two
     * outcomes. **"Destroy Vault", not "Delete"** — delete is this app's word for an
     * item, which comes back from the trash for thirty days, and the trash is in this
     * same drawer.
     */
    onDestroyVault: () -> Unit = {},
    /** I4's state, in words on the row that opens [PurchaseScreen]. */
    owned: Boolean,
    trialDaysRemaining: Int,
    trialStarted: Boolean,
    onPurchase: () -> Unit,
    /** `one-time-purchase` D2. A payment Play holds that has not cleared. */
    purchasePending: Boolean = false,
    /** P2-22. Device-wide, named on the row that changes it. */
    currentIdentity: LauncherIdentity,
    onIdentity: () -> Unit,
    /**
     * `app-languages` D6. The Language sheet. The install's, so the home drawer's only: the
     * language belongs to the install, not to a vault.
     */
    onLanguage: () -> Unit = {},
    /** The row's state line, in words. */
    biometricState: BiometricState,
    /** `biometric-unlock-screen` D6. The Biometric unlock screen, in every state. */
    onBiometric: () -> Unit,
    /**
     * P2-20. Whether the device has an accelerometer, and whether the lock is on. Without
     * one the row is drawn unavailable, saying why.
     */
    faceDownAvailable: Boolean,
    faceDownOn: Boolean,
    faceDownLockAfter: Long,
    onFaceDown: () -> Unit,
    /**
     * Whether a decoy has been built from the open keyring — `VaultKeyringSession.hasDecoy`.
     *
     * The row is a state when it is true and an action when it is false, and
     * [onSetUpDecoy] is only reachable in the second case. False with no keyring open,
     * which is the same answer the sheet gives on an install that has no decoy.
     */
    decoySetUp: Boolean,
    /** P2-2. The flow, from the row that offers it while there is none. */
    onSetUpDecoy: () -> Unit,
    /** P2-3. Identical in the home drawer whether or not it is enrolled. */
    onPanicCredential: () -> Unit,
    /**
     * `recovery-kit-three-views`. The open list's own kit: its views left, named on the row after
     * Panic credential. The count is sealed in this list's keyring, so a decoy's drawer shows the
     * decoy's own.
     */
    listKitViewsLeft: Int = 0,
    onListKit: () -> Unit = {},
    /**
     * The open vault's own kit's views left, or null when the vault has no passphrase of its
     * own — and so no kit of its own and no row.
     */
    vaultKitViewsLeft: Int? = null,
    onVaultKit: () -> Unit = {},
    /**
     * `intruder-captures-at-home`. A row in the home drawer, after the panic credential,
     * opening the Intruder capture screen. No state line: it reads the same whether or not
     * capture is set up, apart from the badge.
     */
    onIntruderCapture: () -> Unit,
    /**
     * `intruder-captures-at-home` D7. The open keyring's capture store holds captures the user
     * has not seen. Supplied from above and read here and on the home screen's drawer control,
     * so the two cannot show different states.
     */
    capturesUnseen: Boolean = false,
    /**
     * `per-vault-intruder-capture` D11. The open vault's Intruder capture row, after Backup — null
     * when the vault has no passphrase of its own, and then no row.
     */
    onVaultIntruderCapture: (() -> Unit)? = null,
    /** D7. The open vault's capture store holds captures not yet seen; the grid's control reads the same value. */
    vaultCapturesUnseen: Boolean = false,
    /** P1-13. The trust anchor: a row in the vault's drawer, and this sheet's one primary colour. */
    onExport: () -> Unit,
    /**
     * P4-3. Opens a received share file through the system picker, into the open vault — a
     * row in the vault's drawer, because the open vault is what it changes.
     */
    onOpenShare: () -> Unit = {},
    /**
     * `exif-strip-choice`. The preset in effect, named on the row. The row opens the Photo
     * details sheet.
     */
    photoDetailsPreset: PhotoDetailsPreset = PhotoDetailsPreset.PRIVACY,
    onPhotoDetails: () -> Unit = {},
    /**
     * P4-4. The contact list, and the pairing screen behind it — a row in the home
     * drawer.
     *
     * Pairing needs no vault key, writes nothing into a vault, and belongs to the
     * install; a row that came and went with whichever vault happened to be unlocked
     * would be saying something false about what an identity is.
     */
    onContacts: () -> Unit = {},
    /**
     * `debug-crash-log-screen`. The debug build's stored crash records — the home drawer's last
     * row, under its own rule. Null in the release build, which draws neither the row nor the rule.
     */
    onCrashLog: (() -> Unit)? = null,
    /**
     * `third-party-notices`. Opens Open-source licences — the home drawer's fourth group, alone
     * under its own rule after recovery. About the install, not a vault, so the vault's drawer
     * has no such row. Null draws neither the row nor its rule.
     */
    onLicences: (() -> Unit)? = null,
    /** How many components the licences screen lists, for the row's supporting line. */
    licenceCount: Int = 0,
    /** The row's glyph — a debug resource, so it is passed in rather than named here. */
    crashLogIcon: Int = 0,
    /** How many records are stored, in words. */
    crashLogLine: String? = null,
    /**
     * The colour scheme in effect. **Pinned below the home drawer's scroll, not a row in
     * it** — a tap re-themes the screen behind the open sheet, which is the one thing a
     * screen could not do, and it is why P2-23 put it here rather than with the settings.
     * Not in the vault's drawer: it re-themes the whole app.
     *
     * Choosing does not close the sheet: the screen re-themes under the user's finger,
     * and a second thought costs one tap on a control still there.
     */
    currentAppearance: ThemeChoice,
    onChooseAppearance: (ThemeChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier,
        drawerContainerColor = VaultColors.SurfaceContainerHigh,
        drawerContentColor = VaultColors.OnSurface,
    ) {
        // The inner half of the end-edge flip. The host provides Rtl so the sheet
        // lands on the trailing edge; this puts the *content* back to Ltr. Without
        // it every row in here mirrors — label on the right, glyph on the left —
        // and FLAG_SECURE means no screenshot would ever show you that.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = Spacing.screenInset),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 1. The entitlement ---------------------------------------
                    //
                    // **First in both drawers, and alone above its rule.** It is the one row
                    // here that is read without being tapped — `Trial, 12 days left` answers
                    // the question a drawer gets opened with when nobody is going anywhere in
                    // particular, and that question is asked inside a vault as often as on the
                    // home screen — `vault-drawer-trial-and-destroy-pinned`. One call site, so
                    // the two drawers cannot word the trial differently.
                    //
                    // Its own group, not the head of what follows: the rows below describe
                    // this install or one vault, and this one describes the account behind
                    // them and survives a reinstall.
                    DrawerRow(
                        label = stringResource(
                            R.string.drawer_purchase,
                            purchaseState(owned, purchasePending, trialStarted, trialDaysRemaining),
                        ),
                        icon = R.drawable.ic_workspace_premium,
                        onClick = onPurchase,
                    )
                    Rule()

                    if (vaultName != null) {
                        // The vault's drawer ------------------------------------
                        //
                        // `vault-only-drawer`. Every row that is for the open vault, and
                        // only those, under the vault's name. Places first, then the one
                        // that keeps watch over it, then what brings things in, then what
                        // takes everything out. The one that does not come back is not in
                        // the scroll — it is pinned below it.
                        GroupHeading(vaultName)
                        DrawerRow(stringResource(R.string.drawer_trash), R.drawable.ic_delete, onTrash)
                        DrawerRow(
                            stringResource(R.string.drawer_backup),
                            R.drawable.ic_cloud_upload,
                            onBackup,
                            supporting = backupLine,
                            badged = backupAttention,
                        )
                        // `per-vault-intruder-capture`. The vault's own capture, in a vault with
                        // a passphrase of its own; no state line, the badge when captures wait.
                        onVaultIntruderCapture?.let {
                            DrawerRow(
                                stringResource(R.string.drawer_intruder_capture),
                                R.drawable.ic_photo_camera,
                                it,
                                badged = vaultCapturesUnseen,
                            )
                        }
                        DrawerRow(stringResource(R.string.drawer_open_share), R.drawable.ic_share, onOpenShare)
                        vaultKitViewsLeft?.let {
                            DrawerRow(kitViewsLabel(it), R.drawable.ic_description, onVaultKit)
                        }
                        // §6.2: always a text label, never styled as destructive, and the
                        // drawer's one primary colour — on the row, not a filled button, so
                        // it reads as one of the vault's rows rather than the sheet's end.
                        DrawerRow(
                            label = stringResource(R.string.drawer_unvault_everything),
                            icon = R.drawable.ic_output,
                            onClick = onExport,
                            primary = true,
                        )
                    } else {
                        // 2. The install ---------------------------------------
                        //
                        // The settings screen's order, kept, minus the entitlement that
                        // led it: how the app presents itself, then the hardware and the
                        // concealment.
                        DrawerRow(
                            label = stringResource(R.string.drawer_app_identity, stringResource(currentIdentity.label)),
                            icon = R.drawable.ic_apps,
                            onClick = onIdentity,
                        )
                        // `app-languages` D6. After App identity: both are how the app presents
                        // itself. The value is the chosen language's own name — what someone who
                        // cannot read this screen looks for — or System default.
                        val language = LocalContext.current.let { context ->
                            remember(LocalConfiguration.current) { AppLanguage.current(context) }
                        }
                        DrawerRow(
                            label = stringResource(
                                R.string.drawer_language,
                                language?.ownName ?: stringResource(R.string.language_system_default),
                            ),
                            icon = R.drawable.ic_language,
                            onClick = onLanguage,
                        )
                        // `biometric-unlock-screen` D6. A row that opens a screen, not a
                        // switch: which biometric opens the vault, and why the others do
                        // not, takes more than a row holds. The state in words beneath
                        // it, and usable in every state — on a device where nothing is
                        // supported the screen is where each row says why.
                        // `ic_fingerprint`, Material's fingerprint and Android's generic
                        // biometric mark, because the row names the feature, not a sensor.
                        DrawerRow(
                            label = stringResource(BiometricWrap.NAME),
                            icon = R.drawable.ic_fingerprint,
                            onClick = onBiometric,
                            supporting = stringResource(BiometricWrap.drawerLine(biometricState)),
                        )
                        // P2-20, `face-down-lock-settings` D6. The Biometric unlock row's idiom:
                        // the state in words, no switch, and a screen behind it — Lock after is a
                        // choice among seven. Never itself gated; with no accelerometer it says
                        // so, and the screen's switch says why.
                        DrawerRow(
                            label = stringResource(R.string.drawer_face_down),
                            icon = R.drawable.ic_screen_lock_portrait,
                            onClick = onFaceDown,
                            supporting = faceDownDrawerLine(faceDownAvailable, faceDownOn, faceDownLockAfter),
                        )
                        // Two rows in one position, and never both. Built: a state, with no
                        // click and a switch that does not move — there is nothing to turn
                        // off, because a decoy is a vault someone opens with its own
                        // passphrase and not a mode this app runs in. Not built: the action
                        // that builds one, which is what the row has always been.
                        if (decoySetUp) {
                            DrawerRow(
                                label = stringResource(R.string.drawer_decoy),
                                icon = R.drawable.ic_visibility_off,
                                supporting = stringResource(R.string.drawer_decoy_set_up),
                                checked = true,
                            )
                        } else {
                            DrawerRow(stringResource(R.string.drawer_set_up_decoy), R.drawable.ic_visibility_off, onSetUpDecoy)
                        }
                        DrawerRow(stringResource(R.string.drawer_panic), R.drawable.ic_vpn_key, onPanicCredential)
                        // `intruder-captures-at-home`. Directly after the panic credential, the
                        // last of the concealment rows. No state line; the badge, when captures
                        // are unseen, is the same value the home screen's drawer control reads.
                        DrawerRow(
                            stringResource(R.string.drawer_intruder_capture),
                            R.drawable.ic_photo_camera,
                            onIntruderCapture,
                            badged = capturesUnseen,
                        )
                        DrawerRow(kitViewsLabel(listKitViewsLeft), R.drawable.ic_description, onListKit)
                        // `exif-strip-choice`. An install setting, not a vault one: one
                        // selection for the device, naming no vault, which is the trade every
                        // other entry in `GridPreference` makes for the same I8 reason.
                        //
                        // **A row that opens a sheet, not a switch.** It names the preset in
                        // effect, the identity and appearance rows' idiom, and nothing else —
                        // `photo-details-cleanup`: the sheet explains, the row shows the value.
                        DrawerRow(
                            label = stringResource(R.string.drawer_photo_details, stringResource(photoDetailsPreset.label)),
                            icon = R.drawable.ic_location_off,
                            onClick = onPhotoDetails,
                        )
                        // P4-4, last of the install's rows — see [onContacts]. `ic_qr_code`
                        // and not `ic_share`: that glyph names handing a sealed container to
                        // another app, this one names the code two phones exchange before
                        // any container exists.
                        DrawerRow(stringResource(R.string.drawer_contacts), R.drawable.ic_qr_code, onContacts)

                        // 3. Recovering a vault --------------------------------
                        //
                        // Under its own rule, after the install. It is the only row in the
                        // sheet that is about neither this install nor a vault already open — a way
                        // *in* to one that is not on the keyring yet — so it is where a
                        // drawer puts what you reached for deliberately rather than what
                        // you might brush on the way past.
                        if (onRecoverVault != null) {
                            Rule()
                            DrawerRow(
                                stringResource(R.string.drawer_recover_vault),
                                R.drawable.ic_lock,
                                onRecoverVault,
                            )
                            onRestoreVault?.let {
                                DrawerRow(stringResource(R.string.drawer_restore_vault), R.drawable.ic_history, it)
                            }
                            onImportVault?.let {
                                DrawerRow(stringResource(R.string.drawer_import_vault), R.drawable.ic_input, it)
                            }
                        }

                        // 4. Open-source licences ------------------------------
                        //
                        // `third-party-notices`. Alone, under its own rule, after recovery: it
                        // describes what the install is made of, and is reached for on purpose.
                        if (onLicences != null) {
                            Rule()
                            DrawerRow(
                                label = stringResource(R.string.licences_title),
                                icon = R.drawable.ic_info,
                                onClick = onLicences,
                                supporting = pluralStringResource(R.plurals.licences_drawer_line, licenceCount, licenceCount),
                            )
                        }

                        // `debug-crash-log-screen`. Debug build only, last, under its own
                        // rule: a developer's tool, not a setting of the install.
                        if (onCrashLog != null) {
                            Rule()
                            // English, as the debug-only screen it opens: it ships in no release
                            // build — `app-languages`.
                            DrawerRow("Crash log", crashLogIcon, onCrashLog, supporting = crashLogLine)
                        }
                    }

                    // Breathing room under the last row inside the scroll, so it
                    // does not sit against the pinned control or the sheet's edge.
                    Spacer(Modifier.height(Spacing.md))
                }

                // The pinned slot below the scroll, which holds one thing in each drawer.
                //
                // Home: the colour scheme chooser, alone there. An app setting, so absent
                // from the vault's drawer.
                //
                // Vault: Destroy Vault — `vault-drawer-trial-and-destroy-pinned`. The one
                // act in the app with no way back goes where iOS and Android Settings put
                // theirs: last, apart, at the surface's edge, in red. Pinned, it never
                // scrolls with the rows and is never a thumb's slip from the export, and it
                // stays at the sheet's bottom however few rows the vault has. A filled pill
                // in `Danger` with its words in `OnDanger` — never colour alone: the label
                // says *Destroy*, and the confirmation behind it names the vault.
                if (vaultName == null) {
                    AppearanceControl(
                        current = currentAppearance,
                        onChoose = onChooseAppearance,
                    )
                } else {
                    Button(
                        onClick = onDestroyVault,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VaultColors.Danger,
                            contentColor = VaultColors.OnDanger,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.md)
                            // Material's 40dp raised to §7.1's touch floor.
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_forever),
                            // The label carries the meaning; the glyph repeats it.
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.drawer_destroy_vault))
                    }
                }

                // Breathing room under the last thing drawn, so it does not sit against
                // the sheet's bottom edge.
                Spacer(Modifier.height(Spacing.section))
            }
        }
    }
}


/**
 * The line between two groups of rows.
 *
 * Its own composable so every divider is one decision: an inset that disagreed between
 * them would read as different kinds of separation. The vault's drawer draws one, under
 * the Purchase row; its own rows are one group and draw none among them.
 */
@Composable
private fun Rule() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = Spacing.sm),
        color = VaultColors.OnSurfaceVariant.copy(alpha = DIVIDER_ALPHA),
    )
}

/** Visible in both schemes without competing with the labels either side of it. */
private const val DIVIDER_ALPHA = 0.3f

/** The face-down row's reason on a device with no accelerometer — `vault-lifecycle`. */
internal val NO_MOTION_SENSOR = R.string.face_down_no_sensor

/**
 * One drawer row: glyph, label, whole row tappable.
 *
 * The glyph sits **beside** the label, never instead of it — §6.4 keeps meaning off
 * shape and colour alone, and a row identified only by a picture is a row a screen
 * reader announces as nothing. `ListItem` is already at least 48dp tall, which is
 * §7.1's touch target without inflating anything.
 */
@Composable
private fun DrawerRow(
    label: String,
    icon: Int,
    /**
     * What the row does, or null where it does nothing.
     *
     * Null is a **state** row: not clickable, and its switch does not move. Deliberately
     * not `enabled = false` on the control — a greyed switch is [unavailable]'s, and says
     * the device cannot do this. A built decoy is not that: it is on, it is true, and
     * there is nothing behind it to change.
     */
    onClick: (() -> Unit)? = null,
    /** The state in words, under the label. Absent on the rows that are pure actions. */
    supporting: String? = null,
    /** The state as a control, at the end of the row. Absent where the row is an action. */
    checked: Boolean? = null,
    /**
     * `intruder-captures-at-home` D7. Something is waiting behind this row.
     *
     * Drawn as a dot **and** said in the row's supporting line, because §6.4 forbids
     * meaning carried by colour alone and a dot is exactly that.
     */
    badged: Boolean = false,
    /**
     * `vault-group-in-drawer`. The drawer's one primary-coloured row — Unvault Everything —
     * takes the primary colour on its label and glyph.
     */
    primary: Boolean = false,
    /**
     * `unavailable-capability-states` — the device cannot use this. [supporting] is the
     * reason. Label and glyph drop to `OnSurfaceVariant`, the colour `ChoiceRow` gives a
     * disabled method. Where [checked] is set the row's usable form is a switch, so the
     * switch stays, **greyed out and off** in Material's disabled colours — the owner's
     * direction of 2026-10-02, and what Android's own Settings draws: the same control
     * in the same place, unable to move, with the reason beneath it. Not clickable, and
     * one merged node marked disabled, so TalkBack reads "label, reason, disabled". No
     * text at 38% alpha: that drops the reason under 4.5:1, and the reason is the one
     * thing on the row that has to be read. The supporting line wraps and `ListItem`
     * grows to hold it at any font scale.
     */
    unavailable: Boolean = false,
) {
    val tint = when {
        unavailable -> VaultColors.OnSurfaceVariant
        primary -> VaultColors.Primary
        else -> null
    }
    ListItem(
        headlineContent = {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = (supporting ?: stringResource(R.string.drawer_capture_waiting).takeIf { badged })
            ?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                // The label carries the meaning; the glyph repeats it. Null so a
                // screen reader reads the row once rather than twice.
                contentDescription = null,
            )
        },
        trailingContent = if (unavailable) {
            checked?.let { { Switch(checked = false, onCheckedChange = null, enabled = false) } }
        } else if (badged && checked == null) {
            { Badge(containerColor = VaultColors.Error) }
        } else checked?.let { on ->
            {
                Switch(
                    checked = on,
                    // The whole row is the target as well, so the switch is the smaller
                    // of two ways to say the same thing rather than the only one — §7.1
                    // is satisfied by the row, not by the control.
                    onCheckedChange = onClick?.let { click -> { click() } },
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = VaultColors.SurfaceContainerHigh,
            headlineColor = tint ?: VaultColors.OnSurface,
            supportingColor = VaultColors.OnSurfaceVariant,
            leadingIconColor = tint ?: VaultColors.OnSurfaceVariant,
        ),
        modifier = when {
            unavailable -> Modifier.semantics(mergeDescendants = true) { disabled() }
            onClick != null -> Modifier.clickable(onClick = onClick)
            else -> Modifier
        },
    )
}

/**
 * The open vault's name over its group — the one heading in the sheet.
 *
 * Material's drawer section heading: `titleSmall` on `OnSurfaceVariant`, starting where
 * the rows' glyphs start. It says **whose** the rows under it are, which is what lets a
 * vault's trash sit in a drawer that is otherwise the install's. One line: a vault name
 * can be long, and a heading that wraps reads as two.
 */
@Composable
private fun GroupHeading(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleSmall,
        color = VaultColors.OnSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .semantics { heading() },
    )
}
