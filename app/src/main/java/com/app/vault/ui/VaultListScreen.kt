package com.app.vault.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import com.app.vault.Capture
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import android.icu.text.BreakIterator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import com.app.vault.FolderColour
import com.app.vault.VaultAppearance
import com.app.vault.ui.theme.Radius
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.app.vault.PassphraseStrength
import com.app.vault.R
import com.app.vault.VaultKeyring
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import kotlinx.coroutines.launch

/**
 * The vaults one master passphrase opened — `vault-keyring`.
 *
 * ***There was no such screen, deliberately, until now.*** P2-1 held that nothing may
 * disclose how many vaults an install holds, and enforced it over every source file.
 * That rule was right about everyone **without** the passphrase and wrong about the
 * one person with it: it made ten vaults mean ten passphrases, which is a memory test
 * the owner eventually fails, and a forgotten passphrase is a destroyed vault.
 *
 * What is disclosed here is one keyring's contents to the person who opened it.
 * Nothing on this screen says how many keyrings exist, which slot anything came from,
 * or whether another passphrase would open anything at all — and `MultiVaultScanTest`
 * still fails the build for a slot index reaching a UI source.
 *
 * **No key is unwrapped to draw this.** The names come from the keyring; each vault's
 * key stays sealed in its own entry until that vault is opened. The list is the
 * cheapest possible thing that could be shown, and that is a security property rather
 * than a performance one.
 *
 * **A screen and not a sheet.** It is where the app *is* between unlocking and opening
 * something — the one surface with nothing behind it.
 */
@Composable
fun VaultListScreen(
    vaults: List<VaultKeyring.Entry>,
    onOpen: (VaultKeyring.Entry) -> Unit,
    onEdit: (VaultKeyring.Entry) -> Unit,
    onRemove: (VaultKeyring.Entry) -> Unit,
    onLock: () -> Unit,
    /**
     * The side drawer's state and content — `home-screen-chrome`.
     *
     * **The same drawer the vault's own screen opens**, so the two levels do not teach
     * different habits one tap apart. Settings and the appearance control were reachable
     * only from inside a vault before this: you had to open something to change the app.
     *
     * Owned by the activity for the reason the grid's is — back has to be able to close
     * it, and a state a screen owns is a state back cannot see.
     */
    drawerState: DrawerState,
    drawerContent: @Composable () -> Unit,
    /** The bar's new-vault action. Was a button under the list. */
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set when the last write was refused — a full keyring, or a name that would not take. */
    problem: String? = null,
    /**
     * `vault-list-refresh`, D9. The modifier each row's surface carries, by position — the
     * shared bounds the open vault grows out of. Nothing outside `VaultHomeTransition`.
     */
    rowBounds: @Composable (position: Int) -> Modifier = { Modifier },
    /**
     * `intruder-captures-at-home` D7. The open keyring's capture store holds captures the
     * user has not seen. One value, held by the activity and rendered here and on the home
     * drawer's Intruder capture row, so neither can show a state the other does not.
     */
    capturesUnseen: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    // `home-screen-chrome`. The grid's host, mirrored the same way and for the same
    // reason: `ModalNavigationDrawer` opens from the leading edge and takes no side
    // parameter, so the host is mirrored and the sheet wraps its own content back.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = drawerContent,
        scrimColor = VaultColors.Scrim.copy(alpha = HOME_SCRIM_ALPHA),
        modifier = modifier,
    ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    VaultScaffold(
        title = stringResource(R.string.decoy_vaults_label),
        // The leading slot is lock, not back: there is nothing behind this screen and
        // locking is the one way out of it. The grid's header makes the same choice
        // for the same reason.
        leadingAction = {
            // `Spacing.touchTarget`, not `IconButton`'s own 40dp — a component default is
            // not a design decision, and 40dp is under the minimum this app's chrome
            // requires of every interactive control. The two actions in this bar already
            // set it; the lock was left on the default and a device test caught it.
            IconButton(
                onClick = onLock,
                modifier = Modifier
                    .padding(start = Spacing.xs)
                    .size(Spacing.touchTarget),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = stringResource(R.string.vault_list_lock),
                    tint = VaultColors.OnSurface,
                )
            }
        },
        // `home-screen-chrome`. Two actions and the drawer last, which is where the
        // grid's bar keeps it — the control that opens everything is in the same place
        // one level up and one level down.
        actions = {
            IconButton(
                onClick = onCreate,
                modifier = Modifier.size(Spacing.touchTarget),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_box),
                    contentDescription = stringResource(R.string.vault_new),
                    tint = VaultColors.OnSurface,
                )
            }
            IconButton(
                onClick = { scope.launch { drawerState.open() } },
                modifier = Modifier.size(Spacing.touchTarget),
            ) {
                // `intruder-captures-at-home` D7. **The badge is named by the badge, not by a
                // conditional on the icon's own description.** §6.4 forbids meaning held by
                // colour alone, and switching the icon's `contentDescription` on the flag is a
                // name `ScreenChromeTest.everyHeaderControlIsNamed` cannot read. Two
                // unconditional descriptions instead: the control keeps its own, and the dot
                // carries its own. No count: a number here is the disclosure the digest line
                // stopped reporting, in a smaller shape.
                BadgedBox(
                    badge = {
                        if (capturesUnseen) {
                            val waiting = stringResource(R.string.drawer_capture_waiting)
                            Badge(
                                containerColor = VaultColors.Error,
                                modifier = Modifier.semantics { contentDescription = waiting },
                            )
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu),
                        contentDescription = stringResource(R.string.menu),
                        tint = VaultColors.OnSurface,
                    )
                }
            }
        },
        largeTitle = true,
    ) {
        // `vault-list-refresh`, D7. Said once, here, because this is where a user decides
        // what one passphrase is worth — **above** the vaults, so it is read before one is
        // chosen and does not drift down as the list grows. It is the trade
        // `vault-keyring` made and it is not a footnote.
        //
        // `per-vault-passphrases`. **It stops claiming everything the moment that is
        // untrue.** A sentence that overstates what one passphrase opens is the
        // sentence a user relies on when deciding what to put where.
        Text(
            text = if (vaults.none { it.hasOwnPassphrase }) {
                stringResource(R.string.vault_list_lead)
            } else {
                stringResource(R.string.vault_list_lead_locked)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.lg),
        )

        if (vaults.isEmpty()) {
            // Not a failure, and it must not read as one: the passphrase worked, and
            // what it opened is empty. A screen that said nothing here would look
            // exactly like an unlock that silently did not happen.
            // True of a list that never held a vault and of one whose last vault was
            // removed — "yet" would be false on the second.
            Text(
                text = stringResource(R.string.vault_list_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurface,
            )
            Text(
                text = stringResource(R.string.vault_list_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            // `home-screen-chrome`. **The empty state keeps an action of its own**, in
            // words, even though the bar now carries one. §6.5 requires text plus one
            // action, and a 24dp glyph in the bar is the only affordance on an otherwise
            // blank screen — which is an offer nobody finds.
            Button(
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            ) {
                // **Not "first", for the sentence above's reason.** A list whose last
                // vault was removed reaches this state too, and the vault offered there
                // is not that person's first.
                Text(stringResource(R.string.vault_list_create), style = MaterialTheme.typography.labelLarge)
            }
        }

        // Which row has its menu open, by `position` — stable across an edit, which a
        // name is not. Hoisted rather than remembered per row: a `remember` inside a
        // `forEach` has no key, so it follows the index rather than the vault when the
        // list changes under it.
        var menuFor by remember { mutableStateOf<Int?>(null) }

        // `vault-list-refresh`, D5. **One grouped block**, the Android 16 Settings list:
        // each row a `surfaceContainer` surface, 2dp apart, the block's two ends rounded
        // large and the joins between rows small — one object made of rows rather than
        // rows flush on the page, which is what made this screen read as a settings page.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.groupGap)) {
        vaults.forEachIndexed { index, entry ->
            val top = if (index == 0) Radius.groupOuter else Radius.groupInner
            val bottom = if (index == vaults.lastIndex) Radius.groupOuter else Radius.groupInner
            // `per-vault-passphrases`. **The word, not the glyph.** A vault with its own
            // passphrase is *named* Locked, and the row reads correctly with the badge and
            // the palette both disregarded (§6.4). Every row has a second line, so every
            // row is the same height and the plain one says what opens it too.
            val supporting = if (entry.hasOwnPassphrase) {
                stringResource(R.string.vault_row_locked)
            } else {
                stringResource(R.string.vault_row_list_passphrase)
            }
            Surface(
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                color = VaultColors.SurfaceContainer,
                modifier = rowBounds(entry.position),
            ) {
            ListItem(
                headlineContent = {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                },
                supportingContent = {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                    )
                },
                // The vault's own badge, not a padlock on every row: a glyph every row
                // carries tells no row apart. A protected vault's badge adds a lock mark,
                // which repeats the word above and never replaces it.
                leadingContent = {
                    VaultBadge(
                        name = entry.name,
                        appearance = VaultAppearance.of(entry.appearance),
                        locked = entry.hasOwnPassphrase,
                    )
                },
                // ***One glyph, and the acts behind it.***
                //
                // The two controls this replaced were themselves a fix: as two
                // `TextButton`s they took 155dp of a 320dp row, which put the row's own
                // centre *inside* "Rename", so a thumb aimed at the middle of a row to
                // open a vault renamed it instead. Two 48dp glyphs took 96dp and moved
                // the centre back onto the row body. **One takes 48dp**, so this carries
                // that argument further rather than reversing it.
                //
                // **`screen-chrome` permits this and says why.** Its overflow rule is
                // about the *grid's header* — "a menu SHALL NOT be used to hold an act the
                // surface exists to perform", and this surface exists to **open** a vault.
                // Opening stays on the face of the row, where a tap already does it.
                // Editing and destroying are the requirement's "occasional acts on the
                // object in front of the user", which is the case it explicitly allows.
                //
                // **The trigger names its subject, the items do not need to.** §6.4's
                // argument was that a bare label reads identically on every row; a menu
                // is already scoped to the row that opened it, and "Options for My vault"
                // is what a screen reader reaches first.
                //
                // Remove is below a divider. `destructive-nav-separation`: the one control
                // in this app that destroys a vault does not sit flush against the one that
                // edits it. It is still not tinted — §6.4 keeps meaning off colour and the
                // confirmation screen is what states the consequence.
                trailingContent = {
                    IconButton(
                        onClick = { menuFor = entry.position },
                        modifier = Modifier
                            // The row reserves the 24dp glyph and the 48dp target reaches into
                            // the padding either side of it, as Android Settings' rows do. The
                            // glyph lands exactly where `ListItem` puts a 24dp trailing icon, and
                            // the supporting line gets the 24dp back — without it "Locked — has
                            // its own passphrase" wrapped at 411dp and that row grew a third line.
                            .layout { measurable, constraints ->
                                val p = measurable.measure(constraints)
                                val inset = Spacing.md.roundToPx()
                                layout(p.width - 2 * inset, p.height) { p.place(-inset, 0) }
                            }
                            .size(Spacing.touchTarget),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.vault_row_options, entry.name),
                            tint = VaultColors.OnSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuFor == entry.position,
                        onDismissRequest = { menuFor = null },
                        // ***`SurfaceContainerHigh`, not the row's tone.*** A menu has to be
                        // distinguishable from what it floats over. Seen in a capture, not
                        // reasoned about.
                        containerColor = VaultColors.SurfaceContainerHigh,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.vault_edit),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = VaultColors.OnSurface,
                                )
                            },
                            onClick = {
                                menuFor = null
                                onEdit(entry)
                            },
                        )
                        HorizontalDivider(color = VaultColors.OutlineVariant)
                        // `ic_delete_forever`'s subject, in words. The trash is a place you
                        // go and this destroys a vault — the distinction the grid's
                        // selection bar already draws between the two marks.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.action_remove),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = VaultColors.OnSurface,
                                )
                            },
                            onClick = {
                                menuFor = null
                                onRemove(entry)
                            },
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    // Transparent, so the grouped surface underneath is the row's tone.
                    containerColor = Color.Transparent,
                    headlineColor = VaultColors.OnSurface,
                ),
                modifier = Modifier
                    .clickable { onOpen(entry) }
                    // The state travels with the name, so a screen reader announces
                    // both. An icon-only distinction would be unreachable here, which
                    // is the other half of §6.4's argument.
                    .semantics { contentDescription = "${entry.name}, $supporting" },
            )
            }
        }
        }

        if (problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
    }
    }
    }
}

/** The grid's scrim, at the grid's value: one drawer, one weight. */
private const val HOME_SCRIM_ALPHA = 0.6f

/**
 * The steps of New vault and of Add a passphrase — `new-vault-setup-flow`, D1.
 *
 * New vault walks [Name], [Opens], and with its own passphrase [Passphrase], [Capture] and [Kit].
 * Add a passphrase walks [ADD]. The order is the enum's, so a step's position is its index among
 * the steps shown and the transition's direction is the comparison of two ordinals.
 */
enum class NewVaultStep {
    Name, Opens, Passphrase, Capture, Kit;

    companion object {
        /** D1. Two steps while the list row is selected, five while its own passphrase is. */
        fun shown(own: Boolean): List<NewVaultStep> = if (own) entries else listOf(Name, Opens)

        /** Add a passphrase: its passphrase, its capture, its kit. */
        val ADD = listOf(Passphrase, Capture, Kit)
    }
}

/**
 * The live badge as a step's hero — D3. The thing being made is the step's picture, so it sits
 * where the glyph tile goes, at the badge preview's 72dp.
 */
@Composable
private fun BadgeHero(name: String, appearance: VaultAppearance, locked: Boolean = false) {
    VaultBadge(name = name, appearance = appearance, locked = locked, size = Spacing.badgePreview)
}

/**
 * The step's title takes focus when the step appears — D10 — so a screen reader reads where it
 * is rather than staying on a control of the step it left.
 */
@Composable
private fun arrivalFocus(): Modifier {
    val requester = remember { FocusRequester() }
    // ponytail: a failed request leaves focus where it was, which is the platform's default.
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return Modifier.focusRequester(requester).focusable()
}

/**
 * Step 1 of New vault, Name and look — D4. The live badge, the name, the colour and icon
 * choosers. [error] is the refusal under the name: empty, a full list, or the entitlement gate,
 * in the words the creation act uses. Continue is always enabled; the host refuses.
 */
@Composable
fun NameStep(
    name: String,
    onName: (String) -> Unit,
    appearance: VaultAppearance,
    onAppearance: (VaultAppearance) -> Unit,
    accepts: (String) -> Boolean,
    error: String?,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        title = stringResource(R.string.vault_flow_name_title),
        lead = stringResource(R.string.vault_flow_name_lead),
        icon = R.drawable.ic_lock,
        hero = { BadgeHero(name, appearance) },
        titleModifier = arrivalFocus(),
        onContinue = onContinue,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { if (accepts(it) || it.isEmpty()) onName(it) },
            singleLine = true,
            label = { Text(stringResource(R.string.vault_name_label)) },
            isError = error != null,
            supportingText = error?.let {
                { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        VaultAppearanceChooser(
            name = name,
            appearance = appearance,
            onChange = onAppearance,
            modifier = Modifier.padding(top = Spacing.section),
        )
    }
}

/**
 * Step 2 of New vault, How it opens — D4. Two rows in one group, the list row the default. The
 * second row's detail is where a user learns capture comes with a passphrase, before they could
 * ask why a list-opened vault has no capture step. The action names what pressing it does:
 * **Create vault** ends the flow here, **Continue** walks on.
 */
@Composable
fun OpensStep(
    name: String,
    own: Boolean,
    onOwn: (Boolean) -> Unit,
    busy: Boolean,
    /** A list-opened create that did not land — said here, where it was pressed. */
    problem: String?,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        title = stringResource(R.string.vault_flow_opens_title, name),
        lead = stringResource(R.string.vault_flow_opens_lead),
        icon = R.drawable.ic_lock,
        titleModifier = arrivalFocus(),
        onContinue = onContinue,
        continueEnabled = !busy,
        continueLabel = stringResource(
            when {
                own -> R.string.action_continue
                busy -> R.string.vault_creating
                else -> R.string.setup_create
            }
        ),
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            ChoiceRow(
                name = stringResource(R.string.vault_opens_list),
                detail = stringResource(R.string.vault_opens_list_detail),
                selected = !own,
                onClick = { onOwn(false) },
                enabled = !busy,
            )
            ChoiceRow(
                name = stringResource(R.string.vault_opens_own),
                detail = stringResource(R.string.vault_opens_own_detail),
                selected = own,
                onClick = { onOwn(true) },
                enabled = !busy,
            )
        }
        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Step 3 of New vault, its passphrase — D4. The field with the floor, the meter, the second entry
 * with its live match line, and the vault's name counted as guessable. Continue runs the key-free
 * checks and derives nothing: nothing exists until the capture step's action.
 */
@Composable
fun NewPassphraseStep(name: String, draft: NewPassphrase, onContinue: () -> Unit) {
    val tooWeak = PassphraseTooWeak
    val mismatch = stringResource(R.string.passphrase_mismatch)
    NoClipboard {
        OnboardingScaffold(
            title = stringResource(R.string.vault_flow_passphrase_title, name),
            lead = stringResource(R.string.vault_flow_passphrase_lead, name),
            icon = R.drawable.ic_vpn_key,
            titleModifier = arrivalFocus(),
            onContinue = { if (draft.check(tooWeak, mismatch)) onContinue() },
        ) {
            NewPassphraseFields(draft, enabled = true)
        }
    }
}

/**
 * The intruder capture step of both flows — D4, D5. The vault's name in the title and the lead,
 * where its captures go, the per-mode permission fact, and first run's rows. [actionLabel] is the
 * commit — "Create vault" or "Add passphrase" — whatever the switches say, because it commits
 * them as they are, all-off included; [busyLabel] while it runs.
 */
@Composable
fun VaultCaptureStep(
    name: String,
    enabled: Set<Capture.Mode>,
    hasFrontCamera: Boolean,
    onEnable: (Capture.Mode) -> Unit,
    onDisable: (Capture.Mode) -> Unit,
    autoResetRisk: Boolean,
    onKeepPermissions: () -> Unit,
    problem: String?,
    actionLabel: String,
    busyLabel: String,
    busy: Boolean,
    onCommit: () -> Unit,
) {
    OnboardingScaffold(
        title = stringResource(R.string.vault_flow_capture_title, name),
        lead = stringResource(R.string.vault_flow_capture_lead, name),
        icon = R.drawable.ic_photo_camera,
        titleModifier = arrivalFocus(),
        onContinue = onCommit,
        continueEnabled = !busy,
        continueLabel = if (busy) busyLabel else actionLabel,
    ) {
        Fact(
            stringResource(R.string.vault_capture_fact, name),
            stringResource(R.string.vault_capture_fact_body, name),
        )
        Fact(
            stringResource(R.string.onboarding_fact_permission),
            stringResource(R.string.onboarding_fact_permission_body),
        )
        Spacer(Modifier.height(Spacing.sm))
        CaptureModeRows(enabled, hasFrontCamera, onEnable, onDisable, autoResetRisk, onKeepPermissions, problem)
    }
}

/**
 * The step body of a vault flow, changing with Material's shared-axis X transition — D10.
 * Forward slides in from the end in 250ms, back from the start in 175ms (exits are shorter);
 * with the system's animations off it crossfades instead. Kept out of [StepFrame], so the decoy
 * flow swaps its body exactly as before.
 */
@Composable
fun VaultFlowBody(
    step: NewVaultStep,
    reducedMotion: Boolean,
    content: @Composable (NewVaultStep) -> Unit,
) {
    val distance = with(LocalDensity.current) { Spacing.sharedAxis.roundToPx() }
    // "From the end" is the right in a left-to-right layout and the left in a right-to-left one.
    val end = if (LocalLayoutDirection.current == LayoutDirection.Ltr) distance else -distance
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(CROSSFADE_MS)) togetherWith fadeOut(tween(CROSSFADE_MS))
            } else {
                val forward = targetState > initialState
                val ms = if (forward) ENTER_MS else BACK_MS
                // Material's shared axis: the outgoing fades in the first third while both
                // slide, and the incoming fades in over the rest — a fade-through, never both
                // bodies legible at once.
                val out = ms * 35 / 100
                (slideInHorizontally(tween(ms, easing = FastOutSlowInEasing)) { if (forward) end else -end } +
                    fadeIn(tween(ms - out, delayMillis = out, easing = LinearOutSlowInEasing))) togetherWith
                    (slideOutHorizontally(tween(ms, easing = FastOutSlowInEasing)) { if (forward) -end else end } +
                        fadeOut(tween(out, easing = FastOutLinearInEasing)))
            }.using(null)
        },
        label = "vault-flow",
    ) { shown -> content(shown) }
}

private const val BACK_MS = 175
private const val CROSSFADE_MS = 150

/**
 * Cancel once something has been entered — D6. [title] and [body] are New vault's or Add a
 * passphrase's. Discard takes the error role in colour and in its word; both actions are 48dp.
 */
@Composable
fun DiscardFlowDialog(title: String, body: String, onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(contentColor = VaultColors.Error),
                modifier = Modifier.heightIn(min = Spacing.touchTarget),
            ) { Text(stringResource(R.string.editor_discard)) }
        },
        dismissButton = {
            TextButton(
                onClick = onKeep,
                modifier = Modifier.heightIn(min = Spacing.touchTarget),
            ) { Text(stringResource(R.string.editor_keep_editing)) }
        },
    )
}

/**
 * The passphrase of one vault, asked for at the moment it is opened —
 * `per-vault-passphrases`.
 *
 * **A sheet, on `FolderNameSheet`'s shape, for that sheet's reason:** the question is
 * one field and the answer returns to exactly where it was asked. Nothing about the
 * list moves underneath it.
 *
 * **This may say why it failed, and the lock screen may not.** The lock screen is silent
 * because a more precise message there would say whether a vault is in a slot at all.
 * That reasoning does not reach here — the user is looking at a vault they can already
 * see, named, on a list they already opened — so the message names the vault and the
 * field keeps what was typed, which is the difference between correcting a passphrase
 * and retyping one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPassphraseSheet(
    vaultName: String,
    onOpen: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf("") }

    NoClipboard {
        ModalBottomSheet(
            onDismissRequest = onCancel,
            modifier = modifier,
            containerColor = VaultColors.Surface,
        ) {
            Column(
                modifier = Modifier
                    .imePadding()
                    // Scrollable for `FolderNameSheet`'s reason: with the keyboard up on
                    // a short screen the button otherwise lifts past the top of the
                    // window, and a tap aimed at it dismisses the sheet instead.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenInset)
                    .padding(bottom = Spacing.section),
            ) {
                Text(
                    text = stringResource(R.string.vault_open_named, vaultName),
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColors.OnSurface,
                )
                Text(
                    text = stringResource(R.string.vault_has_own_passphrase),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )

                PassphraseField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = stringResource(R.string.passphrase_label),
                    imeAction = ImeAction.Done,
                    enabled = !busy,
                    modifier = Modifier.padding(top = Spacing.md),
                )

                // One indicator, driven by whether an attempt is running and never by
                // its result — `DESIGN.md` §8.1. The height is reserved so the sheet
                // does not resize under the thumb when it appears.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.xl)
                        .padding(top = Spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        LinearProgressIndicator(
                            color = VaultColors.Primary,
                            trackColor = VaultColors.SurfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (failed && !busy) {
                    Text(
                        text = stringResource(R.string.vault_open_wrong, vaultName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.Error,
                        // Announced rather than only coloured — `DESIGN.md` §6.4, and
                        // an error a screen reader never reaches is an error nobody
                        // using one can act on.
                        modifier = Modifier
                            .padding(top = Spacing.sm)
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }

                Button(
                    onClick = { onOpen(typed.toByteArray(Charsets.UTF_8)) },
                    enabled = !busy && typed.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                ) {
                    Text(
                        stringResource(if (busy) R.string.vault_opening else R.string.vault_open),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * A vault's badge — `vault-appearance`, D3.
 *
 * A circle in the vault's colour (or `surfaceContainerHigh` for No colour) carrying the
 * chosen glyph or the name's first character, uppercased. **Decorative to a screen reader**:
 * the name beside it is what is announced, and the letter only repeats it. A vault with its
 * own passphrase adds a lock mark over the bottom-end corner, which repeats the word
 * "Locked" in its row and never replaces it.
 *
 * One composable for the row (40dp) and both previews (72dp).
 */
@Composable
fun VaultBadge(
    name: String,
    appearance: VaultAppearance,
    locked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = Spacing.badge,
) {
    val glyph = if (appearance.colour == FolderColour.None) VaultColors.OnSurfaceVariant else VaultColors.OnBadge
    Box(modifier = modifier.size(size).clearAndSetSemantics { }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(VaultColors.folder(appearance.colour), CircleShape),
        ) {
            val icon = appearance.iconRes
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = glyph,
                    // 24dp on the 40dp badge — the icon's own size — and in proportion on
                    // the preview.
                    modifier = Modifier.size(size * ICON_SHARE),
                )
            } else {
                Text(
                    text = firstGrapheme(name),
                    // On the type scale at both sizes rather than scaled by a ratio.
                    style = if (size >= Spacing.badgePreview) MaterialTheme.typography.headlineLarge
                        else MaterialTheme.typography.titleLarge,
                    color = glyph,
                )
            }
        }
        if (locked) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // Over the corner, not inside it: the mark sits across the badge's edge
                    // the way a status mark sits on an avatar.
                    .offset(x = Spacing.xs, y = Spacing.xs)
                    .size(Spacing.badgeMark)
                    .background(VaultColors.SurfaceContainer, CircleShape),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = VaultColors.OnSurface,
                    modifier = Modifier.size(Spacing.badgeMarkGlyph),
                )
            }
        }
    }
}

/** 24dp of a 40dp badge. */
private const val ICON_SHARE = 0.6f

/**
 * The name's first user-perceived character, uppercased — a whole grapheme, so an emoji or
 * an accent written as two code points is never cut in half.
 */
internal fun firstGrapheme(name: String): String {
    val text = name.trim()
    if (text.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance().apply { setText(text) }
    return text.substring(0, it.next()).uppercase(Locale.getDefault())
}

/** The 72dp live preview at the top of New vault and Edit vault. */
@Composable
private fun BadgePreview(name: String, appearance: VaultAppearance, locked: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md),
    ) {
        VaultBadge(name = name, appearance = appearance, locked = locked, size = Spacing.badgePreview)
    }
}

/**
 * The colour row and the icon grid — `vault-appearance`, D8. New vault and Edit vault both
 * draw it, which is the second surface §6 asks for before a piece is lifted out.
 *
 * The colours are the folders' row itself, so the app has one colour vocabulary. The icon
 * grid is 25 cells at 48dp — the letter, then `VaultAppearance.ICONS` in their stored order —
 * six to a row so its columns line up under the six swatches. The chosen cell is drawn as the
 * badge will be and carries a check mark: a mark, not only a tint (§6.4). Every cell names
 * itself and reports whether it is selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VaultAppearanceChooser(
    name: String,
    appearance: VaultAppearance,
    onChange: (VaultAppearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `SettingsHeading` and `ColourRow` each inset themselves by the screen inset, for the
    // sheets they were written for; here they sit inside a scaffold that already has. Bled
    // back out, so the headings, the swatches and the grid line up with the name field.
    Column(modifier = modifier.bleed(Spacing.screenInset)) {
        SettingsHeading(stringResource(R.string.vault_colour))
        ColourRow(appearance.colour) { onChange(appearance.copy(colour = it)) }
        SettingsHeading(stringResource(R.string.vault_icon))
        FlowRow(
            maxItemsInEachRow = FolderColour.entries.size,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenInset, vertical = Spacing.md),
        ) {
            IconCell(
                label = stringResource(R.string.vault_icon_letter),
                chosen = appearance.icon == 0,
                colour = appearance.colour,
                onClick = { onChange(appearance.copy(icon = 0)) },
            ) { tint ->
                Text(
                    text = firstGrapheme(name).ifEmpty { stringResource(R.string.vault_icon_letter_sample) },
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                )
            }
            VaultAppearance.ICONS.forEachIndexed { i, (label, res) ->
                IconCell(
                    label = stringResource(label),
                    chosen = appearance.icon == i + 1,
                    colour = appearance.colour,
                    onClick = { onChange(appearance.copy(icon = i + 1)) },
                ) { tint ->
                    Icon(painter = painterResource(res), contentDescription = null, tint = tint)
                }
            }
        }
    }
}

@Composable
private fun IconCell(
    label: String,
    chosen: Boolean,
    colour: FolderColour,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    // The chosen cell is drawn as the badge will be; the rest sit quietly on the container.
    val fill = if (chosen) VaultColors.folder(colour) else VaultColors.SurfaceContainer
    val tint = if (chosen && colour != FolderColour.None) VaultColors.OnBadge else VaultColors.OnSurfaceVariant
    Box(
        modifier = Modifier
            .size(Spacing.touchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                selected = chosen
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(fill)
                .clickable(role = Role.RadioButton, onClick = onClick),
        ) { glyph(tint) }
        if (chosen) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                // The cell's own semantics say it is selected; this would say it twice.
                contentDescription = null,
                tint = VaultColors.OnSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = Spacing.xs, y = Spacing.xs)
                    .size(Spacing.badgeMark)
                    .background(VaultColors.Surface, CircleShape),
            )
        }
    }
}

/**
 * Widens a block by [inset] on each side — undoes the scaffold's inset for pieces that inset
 * themselves. Shared with the Biometric unlock screen's rows and the App identity screen's
 * window heading.
 */
// ponytail: a layout bleed rather than a second copy of SettingsHeading/ColourRow without padding.
internal fun Modifier.bleed(inset: Dp) = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    if (!constraints.hasBoundedWidth) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val p = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(p.width - extra, p.height) { p.place(-inset.roundToPx(), 0) }
}

/**
 * Edit vault — `vault-list-refresh`, D8. The row's menu reaches it; it replaced the rename
 * sheet.
 *
 * **A screen, not a sheet.** A preview, a name field and two choosers is a keyboard plus two
 * pickers, and the convention for editing a thing's name and look together — Google Contacts'
 * Edit contact, 1Password's Edit vault — is a screen.
 *
 * Save writes the name and the appearance in **one** keyring save, so either both change or
 * neither does. Back discards.
 *
 * **The Passphrase section sits below Save** — `vault-passphrase-manage`, D5. Save commits what
 * is above it; each passphrase act commits on its own screen. Below the button, one button cannot
 * read as doing both. The draft ([name], [appearance]) is the caller's, so it survives the round
 * trip to a passphrase screen and back.
 */
@Composable
fun EditVaultScreen(
    vault: VaultKeyring.Entry,
    name: String,
    appearance: VaultAppearance,
    onDraft: (String, VaultAppearance) -> Unit,
    onSave: (String, VaultAppearance) -> Unit,
    onBack: () -> Unit,
    onPassphrase: (PassphraseAct) -> Unit,
    busy: Boolean,
    problem: String?,
    /** "Passphrase added." and its siblings, after an act — shown until Edit vault is left. */
    status: String?,
    modifier: Modifier = Modifier,
) {
    var local by remember { mutableStateOf<String?>(null) }

    val nameEmpty = stringResource(R.string.vault_name_empty)
    VaultScaffold(title = stringResource(R.string.vault_edit), modifier = modifier, onBack = onBack) {
        // The lock mark follows the entry, so it appears or goes in the frame the act lands —
        // the visible cause and effect of the status line below.
        BadgePreview(name, appearance, locked = vault.hasOwnPassphrase)

        OutlinedTextField(
            value = name,
            onValueChange = { if (VaultKeyring.fits(it) || it.isEmpty()) { onDraft(it, appearance); local = null } },
            singleLine = true,
            label = { Text(stringResource(R.string.vault_name_label)) },
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
        )

        VaultAppearanceChooser(
            name = name,
            appearance = appearance,
            onChange = { onDraft(name, it) },
            modifier = Modifier.padding(top = Spacing.section),
        )

        (local ?: problem)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Button(
            onClick = {
                local = if (name.isBlank()) nameEmpty else null
                if (local == null) onSave(name.trim(), appearance)
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl),
        ) {
            Text(stringResource(if (busy) R.string.action_saving else R.string.action_save), style = MaterialTheme.typography.labelLarge)
        }

        PassphraseSection(
            protected = vault.hasOwnPassphrase,
            status = status,
            enabled = !busy,
            onPassphrase = onPassphrase,
            modifier = Modifier.padding(top = Spacing.section),
        )
    }
}

/** The three passphrase acts Edit vault offers — `vault-passphrase-manage`, D5. */
enum class PassphraseAct { Add, Change, Remove }

/**
 * Edit vault's Passphrase section — D5. What opens the vault now, the acts for that state as one
 * grouped block on the vault list's pattern, and after an act the line saying what was done,
 * announced politely. Remove is not tinted: its own screen states the consequence (§6.4).
 */
@Composable
private fun PassphraseSection(
    protected: Boolean,
    status: String?,
    enabled: Boolean,
    onPassphrase: (PassphraseAct) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.vault_passphrase_heading),
            style = MaterialTheme.typography.titleSmall,
            color = VaultColors.OnSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(if (protected) R.string.vault_passphrase_state_own else R.string.vault_passphrase_state_list),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        // Icon and words, the match line's shape: a done act reads as done without the colour.
        status?.let {
            Box(
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) { PassphraseMatchLine(it) }
        }

        val rows = if (protected) {
            listOf(
                Triple(PassphraseAct.Change, stringResource(R.string.vault_passphrase_change), null),
                Triple(PassphraseAct.Remove, stringResource(R.string.vault_passphrase_remove), stringResource(R.string.vault_passphrase_remove_supporting)),
            )
        } else {
            listOf(Triple(PassphraseAct.Add, stringResource(R.string.vault_passphrase_add), stringResource(R.string.vault_passphrase_add_supporting)))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.groupGap),
            modifier = Modifier.padding(top = Spacing.md),
        ) {
            rows.forEachIndexed { index, (act, label, supporting) ->
                val top = if (index == 0) Radius.groupOuter else Radius.groupInner
                val bottom = if (index == rows.lastIndex) Radius.groupOuter else Radius.groupInner
                Surface(
                    shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                    color = VaultColors.SurfaceContainer,
                ) {
                    ListItem(
                        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = supporting?.let {
                            { Text(it, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurfaceVariant) }
                        },
                        trailingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                // The label carries the meaning; the chevron repeats "opens a screen".
                                contentDescription = null,
                                tint = VaultColors.OnSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = VaultColors.OnSurface,
                        ),
                        // The whole row is the target, and a ListItem is 56dp or more.
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled, role = Role.Button) { onPassphrase(act) },
                    )
                }
            }
        }
    }
}

/**
 * What a passphrase screen was told after its button — `vault-passphrase-manage`, D6. [wrong] puts
 * the refusal under the field that proves (the list's passphrase, or the current one); [problem] is
 * a save that did not land, said above the button.
 */
class PassphraseActState(val busy: Boolean, val wrong: Boolean, val problem: String?)

/**
 * Step 1 of Add a passphrase — `new-vault-setup-flow`, D7, in the flow's step body. The badge as
 * it will be (locked) as the hero; what the passphrase will do; the vault list's passphrase as
 * proof; the new one with the floor and the meter, its name counted as guessable; the second
 * entry with the live match line; and what an earlier backup still does.
 *
 * Continue runs every check that needs no key, then [onProve] — the host proves the list's
 * passphrase off the main thread, and [busy] reads "Checking…" while it does. [wrong] is a list
 * passphrase the host found wrong, said under its field. The typed values are the host's, so
 * Back from the capture step finds them as they were left.
 */
@Composable
fun AddPassphraseStep(
    vault: VaultKeyring.Entry,
    list: String,
    onList: (String) -> Unit,
    wrong: Boolean,
    draft: NewPassphrase,
    busy: Boolean,
    onProve: () -> Unit,
) {
    var listEmpty by remember { mutableStateOf(false) }
    val emptyLine = stringResource(R.string.vault_passphrase_list_empty)
    val wrongList = stringResource(R.string.kit_wrong_list)
    val listShown = if (listEmpty) emptyLine else wrongList.takeIf { wrong && !busy }
    val tooWeak = PassphraseTooWeak
    val mismatch = stringResource(R.string.passphrase_mismatch)

    NoClipboard {
        OnboardingScaffold(
            title = stringResource(R.string.vault_passphrase_add),
            lead = stringResource(R.string.vault_passphrase_add_lead, vault.name),
            icon = R.drawable.ic_vpn_key,
            hero = { BadgeHero(vault.name, VaultAppearance.of(vault.appearance), locked = true) },
            titleModifier = arrivalFocus(),
            continueEnabled = !busy,
            continueLabel = stringResource(if (busy) R.string.destroy_checking else R.string.action_continue),
            onContinue = {
                // Every check that needs no key, before any derivation — D7.
                listEmpty = list.isEmpty()
                if (draft.check(tooWeak, mismatch) && !listEmpty) onProve()
            },
        ) {
            PassphraseField(
                value = list,
                onValueChange = { onList(it); listEmpty = false },
                label = stringResource(R.string.vault_passphrase_list_label),
                imeAction = ImeAction.Next,
                enabled = !busy,
                isError = listShown != null,
                supporting = listShown ?: stringResource(R.string.vault_passphrase_list_supporting),
                announce = listShown != null,
            )
            NewPassphraseFields(draft, enabled = !busy, modifier = Modifier.padding(top = Spacing.md))
            EarlierBackups(stringResource(R.string.vault_passphrase_add_backups, vault.name))
        }
    }
}

/**
 * Change passphrase — D6. The kit does not change, and the list's passphrase still won't open it.
 * The current passphrase, then the new one twice, then what an earlier backup still does.
 */
@Composable
fun ChangeVaultPassphraseScreen(
    vault: VaultKeyring.Entry,
    state: PassphraseActState,
    onChange: (current: ByteArray, new: ByteArray) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf("") }
    var currentError by remember { mutableStateOf<String?>(null) }
    val draft = rememberNewPassphrase(vault.name)
    val currentEmpty = stringResource(R.string.vault_passphrase_current_empty)
    val wrongVault = stringResource(R.string.kit_wrong_vault)
    val currentShown = currentError ?: wrongVault.takeIf { state.wrong && !state.busy }
    val tooWeak = PassphraseTooWeak
    val mismatch = stringResource(R.string.passphrase_mismatch)

    PassphraseActScreen(
        title = stringResource(R.string.vault_passphrase_change),
        vault = vault,
        locked = true,
        onBack = onBack,
        modifier = modifier,
    ) {
        Lead(stringResource(R.string.vault_passphrase_change_lead, vault.name))
        PassphraseField(
            value = current,
            onValueChange = { current = it; currentError = null },
            label = stringResource(R.string.vault_passphrase_current_label),
            imeAction = ImeAction.Next,
            enabled = !state.busy,
            isError = currentShown != null,
            supporting = currentShown,
            announce = currentShown != null,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        NewPassphraseFields(draft, enabled = !state.busy, modifier = Modifier.padding(top = Spacing.md))
        EarlierBackups(stringResource(R.string.vault_passphrase_change_backups, vault.name))
        ActButton(
            label = stringResource(if (state.busy) R.string.vault_passphrase_changing else R.string.vault_passphrase_change),
            state = state,
            onClick = {
                currentError = if (current.isEmpty()) currentEmpty else null
                if (draft.check(tooWeak, mismatch) && currentError == null) {
                    onChange(current.toByteArray(Charsets.UTF_8), draft.new.toByteArray(Charsets.UTF_8))
                }
            },
        )
    }
}

/**
 * Remove passphrase — D6. The three things removing it does, stated before the field; then the
 * current passphrase. This screen is the confirmation: no dialog follows it.
 */
@Composable
fun RemoveVaultPassphraseScreen(
    vault: VaultKeyring.Entry,
    state: PassphraseActState,
    onRemove: (current: ByteArray) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf("") }
    var currentError by remember { mutableStateOf<String?>(null) }
    val currentEmpty = stringResource(R.string.vault_passphrase_current_empty)
    val wrongVault = stringResource(R.string.kit_wrong_vault)
    val currentShown = currentError ?: wrongVault.takeIf { state.wrong && !state.busy }

    PassphraseActScreen(
        title = stringResource(R.string.vault_passphrase_remove),
        vault = vault,
        locked = false,
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(top = Spacing.lg)) {
            PassphraseFact(
                stringResource(R.string.vault_passphrase_remove_opens),
                stringResource(R.string.vault_passphrase_remove_opens_body, vault.name),
            )
            PassphraseFact(
                stringResource(R.string.vault_passphrase_remove_kit),
                stringResource(R.string.vault_passphrase_remove_kit_body, vault.name),
            )
            PassphraseFact(
                stringResource(R.string.vault_passphrase_remove_captures),
                stringResource(R.string.vault_passphrase_remove_captures_body),
            )
        }
        PassphraseField(
            value = current,
            onValueChange = { current = it; currentError = null },
            label = stringResource(R.string.vault_passphrase_current_label),
            imeAction = ImeAction.Done,
            enabled = !state.busy,
            isError = currentShown != null,
            supporting = currentShown,
            announce = currentShown != null,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        ActButton(
            label = stringResource(if (state.busy) R.string.vault_passphrase_removing else R.string.vault_passphrase_remove),
            state = state,
            onClick = {
                currentError = if (current.isEmpty()) currentEmpty else null
                if (currentError == null) onRemove(current.toByteArray(Charsets.UTF_8))
            },
        )
    }
}

/** The frame the three share: Back, no clipboard, and the badge as the act will leave it. */
@Composable
private fun PassphraseActScreen(
    title: String,
    vault: VaultKeyring.Entry,
    locked: Boolean,
    onBack: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    NoClipboard {
        // The scaffold scrolls, and its content insets are `safeDrawing`, which includes the IME:
        // with the keyboard up the button scrolls into view above it.
        VaultScaffold(title = title, modifier = modifier, onBack = onBack) {
            BadgePreview(vault.name, VaultAppearance.of(vault.appearance), locked = locked)
            content()
        }
    }
}

@Composable
private fun Lead(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.lg),
    )
}

@Composable
private fun EarlierBackups(body: String) {
    Column(modifier = Modifier.padding(top = Spacing.section)) {
        PassphraseFact(stringResource(R.string.vault_passphrase_backups_heading), body)
    }
}

/** One statement: what it is, then what it means — the app's `Fact` shape. */
@Composable
private fun PassphraseFact(heading: String, body: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .padding(bottom = Spacing.md)
            .semantics(mergeDescendants = true) { },
    ) {
        Text(text = heading, style = MaterialTheme.typography.titleSmall, color = VaultColors.OnSurface)
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurfaceVariant)
    }
}

@Composable
private fun ActButton(label: String, state: PassphraseActState, onClick: () -> Unit) {
    state.problem?.takeIf { !state.busy }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.Error,
            modifier = Modifier
                .padding(top = Spacing.sm)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Button(
        onClick = onClick,
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The new passphrase twice, with its own refusals — shared by Add, Change and New vault.
 *
 * Plain state, so a flow can hold it in the activity and Back finds it as it was left (D4); the
 * single-screen acts hold it in a `remember`. [vaultName] is counted as guessable and may change
 * while the draft is held — New vault's name is typed two steps earlier and can be edited.
 */
class NewPassphrase(vaultName: String = "") {
    var vaultName by mutableStateOf(vaultName)
    val forbidden: List<String> get() = PassphraseStrength.APP_WORDS + vaultName.trim()
    var new by mutableStateOf("")
    var again by mutableStateOf("")
    var newError by mutableStateOf<String?>(null)
    var againError by mutableStateOf<String?>(null)

    /** The floor with the vault's name as guessable, then the match — key-free, before derivation. */
    fun check(tooWeak: String, mismatch: String): Boolean {
        newError = if (!PassphraseStrength.clears(new, forbidden)) tooWeak else null
        againError = if (newError == null && new != again) mismatch else null
        return newError == null && againError == null
    }

    /** Back to empty — Discard, the end of the flow, and a lock. */
    fun clear() {
        new = ""
        again = ""
        newError = null
        againError = null
    }
}

@Composable
private fun rememberNewPassphrase(vaultName: String): NewPassphrase = remember(vaultName) { NewPassphrase(vaultName) }

@Composable
internal fun NewPassphraseFields(draft: NewPassphrase, enabled: Boolean, modifier: Modifier = Modifier) {
    val verdict = rememberPassphraseVerdict(draft.new, draft.forbidden)
    Column(modifier = modifier) {
        PassphraseField(
            value = draft.new,
            onValueChange = { draft.new = it; draft.newError = null },
            label = stringResource(R.string.kit_new_passphrase_label),
            imeAction = ImeAction.Next,
            enabled = enabled,
            isError = draft.newError != null,
            supporting = draft.newError ?: PassphraseRequirement,
            announce = draft.newError != null,
        )
        PassphraseStrengthMeter(verdict = verdict, modifier = Modifier.padding(top = Spacing.sm))
        PassphraseField(
            value = draft.again,
            onValueChange = { draft.again = it; draft.againError = null },
            label = stringResource(R.string.vault_passphrase_new_again),
            imeAction = ImeAction.Done,
            enabled = enabled,
            isError = draft.againError != null,
            supporting = draft.againError,
            announce = draft.againError != null,
            modifier = Modifier.padding(top = Spacing.md),
        )
        if (draft.again.isNotEmpty() && draft.again == draft.new) {
            Box(modifier = Modifier.padding(top = Spacing.sm)) { PassphraseMatchLine() }
        }
    }
}

/** Which of the two home surfaces [VaultHomeTransition] shows. */
enum class HomeTarget { List, Grid }

/**
 * The vault list's row growing into the open vault, and back — `vault-list-refresh`, D9.
 *
 * `SharedTransitionLayout` + `AnimatedContent` + `sharedBounds`, Compose's container
 * transform. Every row's surface carries `vault-<position>`; the grid carries the same key
 * for [openPosition], so the open vault's screen grows out of that row in 250ms `EaseOut`
 * and shrinks back into it in 150ms `EaseIn` (§8). The content fades through inside the
 * bounds.
 *
 * **Reduced motion removes it**: no `AnimatedContent` at all, so the vault's screen is there
 * on the next frame and the list is gone on the same one — `None` transitions still hold the
 * outgoing screen for frames of their own.
 */
@Composable
fun VaultHomeTransition(
    target: HomeTarget,
    openPosition: Int,
    reducedMotion: Boolean,
    list: @Composable (rowBounds: @Composable (Int) -> Modifier) -> Unit,
    grid: @Composable (Modifier) -> Unit,
) {
    // Opening is the enter; leaving is the exit, and exits are shorter (§8).
    val opening = target == HomeTarget.Grid
    val duration = if (opening) ENTER_MS else EXIT_MS
    val easing = if (opening) EaseOut else EaseIn
    if (reducedMotion) {
        when (target) {
            HomeTarget.List -> list { Modifier }
            HomeTarget.Grid -> grid(Modifier)
        }
        return
    }
    SharedTransitionLayout {
        AnimatedContent(
            targetState = target,
            transitionSpec = {
                // No `SizeTransform`: both screens fill the window, and its default spring would
                // carry the container past §8's 250ms after the bounds had already arrived.
                (fadeIn(tween(duration, easing = easing)) togetherWith fadeOut(tween(duration, easing = easing)))
                    .using(null)
            },
            label = "vault-home",
        ) { shown ->
            val visibility = this
            @Composable
            fun bounds(position: Int): Modifier =
                if (position < 0) {
                    Modifier
                } else {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState("vault-$position"),
                        animatedVisibilityScope = visibility,
                        enter = fadeIn(tween(duration, easing = easing)),
                        exit = fadeOut(tween(duration, easing = easing)),
                        boundsTransform = { _, _ -> tween(duration, easing = easing) },
                        // Laid out inside the growing bounds, so the vault's screen is the
                        // container growing — not a full-size screen sliding behind a window,
                        // which is what the default scale mode draws.
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    )
                }
            when (shown) {
                HomeTarget.List -> list { position -> bounds(position) }
                HomeTarget.Grid -> grid(bounds(openPosition))
            }
        }
    }
}

private const val ENTER_MS = 250
private const val EXIT_MS = 150
