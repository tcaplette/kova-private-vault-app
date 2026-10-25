package com.app.vault.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.app.vault.Decoy
import com.app.vault.DecoyFlow
import com.app.vault.R
import com.app.vault.VaultKeyring
import com.app.vault.PassphraseStrength
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * What the flow found in what the user chose — computed by the activity from
 * [Decoy], rendered here.
 *
 * The screen makes no decisions. It shows the ones already made, including the
 * two gates, so what is refused is refused by arithmetic that has a test rather
 * than by a condition written twice.
 */
class DecoySummary(
    val chosen: Int,
    val kept: Int,
    val assigned: Int,
    val declined: Int,
    val spreadWeeks: Long,
    val outsideWindow: Boolean,
    /** How old this install is. The ceiling on any history the vault can claim. */
    val installDays: Long,
    val truncated: Boolean,
) {
    val importable: Int get() = kept + assigned
    val enough: Boolean get() = importable >= Decoy.MIN_ITEMS
    val spreadOk: Boolean get() = spreadWeeks >= Decoy.MIN_SPREAD_WEEKS
    val ready: Boolean get() = enough && spreadOk && outsideWindow
}

/** Which sheet is over the flow, if any. */
enum class DecoySheet { Authorise, Preserve }

/**
 * The shell every decoy step is drawn in — `decoy-tooling`. [StepFrame] with the decoy's count
 * and its Cancel's spoken name.
 *
 * Not a reuse of `OnboardingFrame`: that one reads `Onboarding.Step`, whose total is first run's
 * eight, and this flow is reachable from Settings where first run is not running at all.
 */
@Composable
fun DecoyFrame(
    step: DecoyFlow.Step,
    onExit: () -> Unit,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    StepFrame(
        position = step.position,
        total = DecoyFlow.TOTAL,
        onBack = onBack,
        // Absent on the last step: the vault exists by then.
        onCancel = if (step != DecoyFlow.Step.Handover) onExit else null,
        cancelDescription = stringResource(R.string.decoy_leave),
        content = content,
    )
}

/**
 * One step's body: heading, lead, content, one pinned forward action.
 *
 * `OnboardingScaffold`'s arrangement without its icon block. The forward action is
 * pinned rather than scrolled for that file's reason — a Continue the user has to
 * hunt for is the same bug as no Continue at all — and its label says the step's
 * outcome rather than "Next", so a step whose work is undone reads as undone.
 */
@Composable
private fun DecoyStep(
    title: String,
    lead: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl),
        ) {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = VaultColors.OnSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = lead,
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))
            content()
            Spacer(Modifier.height(Spacing.lg))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        ) {
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Spacing.touchTarget),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// --- step 1: what you are making --------------------------------------------

/**
 * Before the picker, never after it — the honesty about who sees these photographs
 * has to arrive while the user is still choosing them, and the trial cost has to
 * arrive before it is spent.
 */
@Composable
fun DecoyIntroStep(
    onContinue: () -> Unit,
    /**
     * `first-run-onboarding` 5.2. What building a decoy costs, where first run is
     * the caller. Null from Settings, where the user is not being walked into it.
     */
    trialNotice: String?,
) {
    DecoyStep(
        title = stringResource(R.string.decoy_intro_title),
        lead = stringResource(R.string.decoy_intro_lead),
        actionLabel = stringResource(R.string.decoy_start),
        onAction = onContinue,
    ) {
        Fact(
            stringResource(R.string.decoy_fact_used),
            stringResource(R.string.decoy_fact_used_body),
        )
        Fact(
            stringResource(R.string.decoy_fact_photos),
            stringResource(R.string.decoy_fact_photos_body),
        )
        Fact(
            stringResource(R.string.decoy_fact_own),
            stringResource(R.string.decoy_fact_own_body),
        )
        trialNotice?.let {
            Fact(stringResource(R.string.decoy_fact_cost), it)
        }
    }
}

// --- step 2: choose the photos ----------------------------------------------

/**
 * The picker and what came back of it.
 *
 * **Every gate says what to do about it**, in the same text that reports it. A
 * readout that says "8 weeks is the minimum" and stops has told the user they have
 * failed and not how to stop failing, which on a screen with a disabled button is
 * a dead end. Both gates read correctly with colour disregarded — `DESIGN.md` §6.4.
 */
@Composable
fun DecoyPhotosStep(
    summary: DecoySummary?,
    @StringRes declineReason: Int?,
    onPickItems: () -> Unit,
    onContinue: () -> Unit,
) {
    DecoyStep(
        title = stringResource(R.string.decoy_photos_title),
        lead = stringResource(R.string.decoy_photos_lead, Decoy.MIN_ITEMS, Decoy.MAX_ITEMS, Decoy.MIN_SPREAD_WEEKS),
        actionLabel = when {
            summary == null -> stringResource(R.string.decoy_choose_to_continue)
            !summary.ready -> stringResource(R.string.decoy_not_plausible)
            else -> stringResource(R.string.action_continue)
        },
        onAction = onContinue,
        actionEnabled = summary?.ready == true,
    ) {
        OutlinedButton(
            onClick = onPickItems,
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.touchTarget),
        ) {
            Text(
                text = stringResource(if (summary == null) R.string.decoy_choose_photos else R.string.decoy_choose_again),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        if (summary == null) {
            // §6.5: text plus one action, and the action is the button above.
            Text(
                text = stringResource(R.string.decoy_nothing_chosen),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md),
            )
            return@DecoyStep
        }

        Text(
            text = listOfNotNull(
                pluralStringResource(R.plurals.decoy_summary_in, summary.importable, summary.importable),
                pluralStringResource(R.plurals.decoy_summary_kept, summary.kept, summary.kept).let { kept ->
                    if (summary.assigned > 0) {
                        stringResource(
                            R.string.decoy_summary_dates_assigned, kept,
                            pluralStringResource(R.plurals.decoy_summary_assigned, summary.assigned, summary.assigned),
                        )
                    } else {
                        stringResource(R.string.decoy_summary_dates, kept)
                    }
                },
                if (summary.declined > 0) {
                    pluralStringResource(
                        R.plurals.decoy_summary_declined, summary.declined, summary.declined,
                        stringResource(declineReason ?: Decoy.CAPTURED_DURING_SETUP),
                    )
                } else null,
                if (summary.truncated) stringResource(R.string.decoy_summary_truncated, Decoy.MAX_ITEMS) else null,
            ).joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )

        GateLine(
            passing = summary.enough,
            text = if (summary.enough) {
                stringResource(R.string.decoy_gate_enough, summary.importable, Decoy.MIN_ITEMS)
            } else {
                stringResource(R.string.decoy_gate_not_enough, summary.importable, Decoy.MIN_ITEMS, Decoy.MIN_ITEMS - summary.importable)
            },
        )

        GateLine(
            passing = summary.spreadOk,
            text = if (summary.spreadOk) {
                pluralStringResource(R.plurals.decoy_gate_spread, summary.spreadWeeks.toInt(), summary.spreadWeeks, Decoy.MIN_SPREAD_WEEKS)
            } else {
                pluralStringResource(R.plurals.decoy_gate_not_spread, summary.spreadWeeks.toInt(), summary.spreadWeeks, Decoy.MIN_SPREAD_WEEKS)
            },
        )

        // Said plainly rather than quietly capped. A vault cannot be older than the
        // app holding it, and the oldest file in the store dates the install for
        // anyone who lists the directory without any passphrase at all.
        Text(
            text = if (summary.installDays >= Decoy.MIN_SPREAD_WEEKS * 7) {
                (summary.installDays / 30).toInt().let { pluralStringResource(R.plurals.decoy_age_months, it, it) }
            } else {
                summary.installDays.toInt().let { pluralStringResource(R.plurals.decoy_age_install, it, it) }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

/**
 * One gate, stated whether it passes or fails.
 *
 * Glyph **and** words — `DESIGN.md` §6.4. The tint repeats what the shape and the
 * sentence already say, so the line survives being read in greyscale.
 */
@Composable
private fun GateLine(passing: Boolean, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(
                if (passing) R.drawable.ic_check_circle else R.drawable.ic_close
            ),
            contentDescription = stringResource(if (passing) R.string.decoy_requirement_met else R.string.decoy_requirement_not_met),
            tint = if (passing) VaultColors.Primary else VaultColors.Error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (passing) VaultColors.OnSurfaceVariant else VaultColors.Error,
        )
    }
}

// --- step 3: name it and lock it --------------------------------------------

/**
 * What this vault is called and how it opens — one decision, three fields.
 *
 * **The name is asked for here and nowhere else, and the flow proposes none.** Every
 * other vault in the app is named by the person who made it; before this change the
 * decoy was the one creation path that skipped the question and silently inherited
 * the migration placeholder, so every decoy on every install was called the same
 * thing. A suggested default would reintroduce exactly that — an identical string
 * across every install that ran the flow, which is the marker `decoy-tooling` exists
 * to avoid.
 */
@Composable
fun DecoyIdentityStep(
    name: String,
    onName: (String) -> Unit,
    /**
     * Hands the chosen passphrase forward as bytes the caller owns and zeroes.
     *
     * **The two passphrase fields are local to this step and are not restored by
     * Back.** They cannot be hoisted: `KeyMaterialScanTest` fails the build on a
     * `passphrase: String` parameter, because a `String` cannot be zeroed and lives
     * until GC — and hoisting them into the activity would be that rule broken with
     * a longer lifetime than the one it was written for. The name is hoisted and does
     * come back, because a name is not key material.
     *
     * Retyping a passphrase on returning to this step is also simply what every
     * platform does with a password field, and it is the safer of the two behaviours.
     */
    onContinue: (ByteArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    // Which field the message belongs under, so an error never floats above the
    // form as a summary of something the user cannot see — §8's error-placement.
    var problemField by remember { mutableStateOf<String?>(null) }

    // D8: the decoy's own name is guessable, exactly as a real vault's is. The name is
    // entered on this same step, above these fields, so it is re-read as it is typed.
    val forbidden = remember(name) {
        PassphraseStrength.APP_WORDS + name.trim().takeIf { it.isNotEmpty() }.orEmpty()
    }
    val verdict = rememberPassphraseVerdict(passphrase, forbidden)

    val nameEmpty = stringResource(R.string.vault_name_empty)
    val nameTooLong = stringResource(R.string.vault_name_too_long)
    val tooWeak = PassphraseTooWeak
    val mismatch = stringResource(R.string.passphrase_mismatch)
    NoClipboard {
        DecoyStep(
            title = stringResource(R.string.decoy_name_title),
            lead = stringResource(R.string.decoy_name_lead),
            actionLabel = stringResource(R.string.action_continue),
            onAction = {
                val bytes = passphrase.toByteArray(Charsets.UTF_8)
                problemField = when {
                    name.isBlank() -> "name"
                    !VaultKeyring.fits(name.trim()) -> "name"
                    // Re-checked here rather than trusting the meter, which is debounced.
                    !PassphraseStrength.clears(passphrase, forbidden) -> "passphrase"
                    passphrase != confirm -> "confirm"
                    else -> null
                }
                problem = when (problemField) {
                    // The same refusal `CreateVaultScreen` gives, in the same words:
                    // one act, named the same way wherever it is reached from.
                    "name" ->
                        if (name.isBlank()) nameEmpty
                        else nameTooLong
                    "passphrase" -> tooWeak
                    "confirm" -> mismatch
                    else -> null
                }
                if (problem == null) {
                    passphrase = ""
                    confirm = ""
                    onContinue(bytes)
                } else {
                    bytes.fill(0)
                }
            },
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { onName(it); problem = null },
                label = { Text(stringResource(R.string.vault_name_label)) },
                singleLine = true,
                isError = problemField == "name" && problem != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            FieldProblem(problem, problemField, "name")
            Text(
                text = stringResource(R.string.decoy_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )

            PassphraseField(
                value = passphrase,
                onValueChange = { passphrase = it; problem = null },
                label = stringResource(R.string.decoy_passphrase_label),
                imeAction = ImeAction.Next,
                isError = problemField == "passphrase" && problem != null,
                supporting = PassphraseRequirement,
                modifier = Modifier.padding(top = Spacing.lg),
            )
            FieldProblem(problem, problemField, "passphrase")
            PassphraseStrengthMeter(
                verdict = verdict,
                modifier = Modifier.padding(top = Spacing.sm),
            )

            PassphraseField(
                value = confirm,
                onValueChange = { confirm = it; problem = null },
                label = stringResource(R.string.passphrase_again_label),
                imeAction = ImeAction.Done,
                isError = problemField == "confirm" && problem != null,
                modifier = Modifier.padding(top = Spacing.md),
            )
            FieldProblem(problem, problemField, "confirm")

            if (passphrase.isNotEmpty() && passphrase == confirm) {
                Box(modifier = Modifier.padding(top = Spacing.sm)) { PassphraseMatchLine() }
            }

            Text(
                text = stringResource(R.string.decoy_passphrase_note),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.lg),
            )
        }
    }
}

/** An error, under the field it belongs to and announced when it appears. */
@Composable
private fun FieldProblem(problem: String?, field: String?, self: String) {
    if (problem == null || field != self) return
    Text(
        text = problem,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.Error,
        modifier = Modifier
            .padding(top = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

// --- step 4: confirm and build ----------------------------------------------

/**
 * The last step before anything is written, and therefore the one that has to be
 * honest about what is about to happen.
 *
 * **The overwrite warning is not here.** It lives inside the other-vaults sheet,
 * which is where it becomes true. Standing it above every user's screen was the old
 * flow's central defect: it described a risk to the vault the reader had open, which
 * `VaultLifecycle.create` now makes structurally impossible, and it did so in a
 * sentence asking for a plural that the screen then served with one field.
 */
@Composable
fun DecoyBuildStep(
    name: String,
    summary: DecoySummary?,
    preserveCount: Int,
    busy: Boolean,
    progress: Pair<Int, Int>?,
    problem: String?,
    onOpenPreserve: () -> Unit,
    onAuthorise: () -> Unit,
) {
    if (busy) {
        val (done, total) = progress ?: (0 to 1)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl),
        ) {
            Spacer(Modifier.height(Spacing.xxl))
            Text(
                text = stringResource(R.string.decoy_building, name),
                style = MaterialTheme.typography.headlineMedium,
                color = VaultColors.OnSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.decoy_building_body),
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                color = VaultColors.Primary,
                trackColor = VaultColors.SurfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xl),
            )
        }
        return
    }

    DecoyStep(
        title = stringResource(R.string.decoy_ready_title),
        lead = stringResource(R.string.decoy_ready_lead),
        actionLabel = stringResource(R.string.decoy_confirm_passphrase),
        onAction = onAuthorise,
    ) {
        Fact(
            stringResource(R.string.decoy_fact_made),
            (summary?.importable ?: 0).let { pluralStringResource(R.plurals.decoy_fact_made_body, it, name, it) },
        )
        Fact(
            stringResource(R.string.decoy_fact_this_vault),
            stringResource(R.string.decoy_fact_this_vault_body),
        )
        Fact(
            stringResource(R.string.decoy_fact_end_up),
            stringResource(R.string.decoy_fact_end_up_body),
        )

        // Progressive disclosure. Closed by default, because the risk behind it is
        // real only for a user who has sealed a second slot on this device — a
        // previous decoy, a restored archive, a recovered vault — and stating it to
        // everyone else describes a danger that does not exist for them.
        DisclosureRow(
            label = stringResource(R.string.decoy_other_vaults),
            supporting = if (preserveCount == 0) {
                stringResource(R.string.decoy_other_vaults_hint)
            } else {
                pluralStringResource(R.plurals.decoy_entries_added, preserveCount, preserveCount)
            },
            onClick = onOpenPreserve,
        )

        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

/** A row that opens something optional. Text carries it; the chevron repeats it. */
@Composable
private fun DisclosureRow(label: String, supporting: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = VaultColors.SurfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
            )
            .heightIn(min = Spacing.touchTarget + Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            // The card's shape, not the default pill: a pill's round ends shave the first
            // and last lines' opening letters once the text wraps.
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.OnSurface,
                )
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColors.OnSurfaceVariant,
                )
            }
        }
    }
}

// --- step 5: what they will see ---------------------------------------------

/**
 * The switch, announced.
 *
 * **This step exists because the switch already happened.** `VaultLifecycle.create`
 * holds the new keyring and opens its vault before it returns, so by the time
 * anything is drawn the user's own vault is closed and their own list is gone.
 * Before this change the flow simply reset to the vault list and said nothing,
 * leaving the user looking at a one-vault list under a name they had never chosen.
 *
 * Putting them back is not available: the master passphrase was zeroed at the moment
 * it was used, and retaining it to reopen afterwards would keep a second master key
 * in memory for the sake of a navigation convenience. So the switch is stated — and
 * stating it costs nothing, because the user is already standing in the thing they
 * need to be shown.
 */
@Composable
fun DecoyHandoverStep(
    name: String,
    itemCount: Int,
    onShowKit: () -> Unit,
) {
    DecoyStep(
        title = stringResource(R.string.decoy_handover_title),
        lead = stringResource(R.string.decoy_handover_lead),
        actionLabel = stringResource(R.string.decoy_show_words),
        onAction = onShowKit,
    ) {
        VaultListPreview(name = name)

        Fact(
            stringResource(R.string.decoy_fact_no_way_across),
            stringResource(R.string.decoy_fact_no_way_across_body),
        )
        Fact(
            stringResource(R.string.decoy_fact_nothing_names),
            stringResource(R.string.decoy_fact_nothing_names_body),
        )
        Fact(
            stringResource(R.string.decoy_fact_inside),
            pluralStringResource(R.plurals.decoy_fact_inside_body, itemCount, itemCount),
        )
        Fact(
            stringResource(R.string.decoy_fact_back),
            stringResource(R.string.decoy_fact_back_body),
        )
    }
}

/**
 * The vault list as the person handed this device would first meet it.
 *
 * A representation rather than the live screen: `VaultListScreen` wants callbacks
 * that open, rename and destroy, and none of them mean anything here. What it has to
 * be faithful about is the one thing the user is being shown — the name they chose,
 * alone on a list, which is what the decoy's keyring actually holds.
 */
@Composable
private fun VaultListPreview(name: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = VaultColors.SurfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(Spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.decoy_vaults_label),
            style = MaterialTheme.typography.labelMedium,
            color = VaultColors.OnSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.touchTarget)
                .padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurface,
            )
        }
    }
}

// --- the two sheets ---------------------------------------------------------

/**
 * Sheet A — confirming it is you.
 *
 * `VaultPassphraseSheet`'s shape, because it is the same act: one field, one
 * question, a time-invariant indicator driven by whether an attempt is running and
 * never by its outcome.
 *
 * **It is authorisation and it says so.** Sealing a slot is destructive and a
 * momentarily unlocked device is not authority to do it — `DESIGN.md` §6.7's
 * confirm-once, spent here. What it is *not* is preservation: the vault this
 * passphrase opens is excluded from the write by `VaultLifecycle.create` whether it
 * is typed or not, so nothing about the answer decides that vault's fate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecoyAuthSheet(
    onConfirm: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
    failed: Boolean,
) {
    var typed by remember { mutableStateOf("") }

    NoClipboard {
        ModalBottomSheet(
            onDismissRequest = onCancel,
            containerColor = VaultColors.Surface,
        ) {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenInset)
                    .padding(bottom = Spacing.section),
            ) {
                Text(
                    text = stringResource(R.string.decoy_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColors.OnSurface,
                )
                Text(
                    text = stringResource(R.string.decoy_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )

                PassphraseField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = stringResource(R.string.panic_your_passphrase),
                    imeAction = ImeAction.Done,
                    enabled = !busy,
                    modifier = Modifier.padding(top = Spacing.md),
                )

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
                        text = stringResource(R.string.decoy_confirm_wrong),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.Error,
                        modifier = Modifier
                            .padding(top = Spacing.sm)
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }

                Button(
                    onClick = { onConfirm(typed.toByteArray(Charsets.UTF_8)) },
                    enabled = !busy && typed.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.touchTarget)
                        .padding(top = Spacing.md),
                ) {
                    Text(
                        stringResource(if (busy) R.string.decoy_checking else R.string.decoy_build),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Sheet B — the other vaults on this device.
 *
 * **This is where the overwrite warning is true, and the only place it appears.**
 *
 * **Every entry is accepted in silence**, and that is
 * not a convenience: "that one opened a vault" is an occupancy oracle for anyone
 * holding one passphrase and this device. So the rows below are numbered and never
 * described — the count is a count of what was typed, which the person typing
 * already knows, and never a count of vaults.
 *
 * What changes from the old single field is only that a plural request is now served
 * by something plural. Each entry is its own row and can be taken back off the list,
 * which is what "enter the passphrase of every vault you want to keep" always
 * needed and never had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecoyPreserveSheet(
    count: Int,
    onAdd: (ByteArray) -> Unit,
    onRemove: (Int) -> Unit,
    onDone: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }

    NoClipboard {
        ModalBottomSheet(
            onDismissRequest = onDone,
            containerColor = VaultColors.Surface,
        ) {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenInset)
                    .padding(bottom = Spacing.section),
            ) {
                Text(
                    text = stringResource(R.string.decoy_preserve_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColors.OnSurface,
                )
                Text(
                    text = stringResource(R.string.decoy_preserve_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                Text(
                    text = stringResource(R.string.decoy_preserve_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )

                PassphraseField(
                    value = entry,
                    onValueChange = { entry = it },
                    label = stringResource(R.string.decoy_preserve_label),
                    imeAction = ImeAction.Done,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
                OutlinedButton(
                    onClick = {
                        onAdd(entry.toByteArray(Charsets.UTF_8))
                        entry = ""
                    },
                    enabled = entry.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.touchTarget)
                        .padding(top = Spacing.sm),
                ) {
                    Text(stringResource(R.string.decoy_preserve_add), style = MaterialTheme.typography.labelLarge)
                }

                if (count == 0) {
                    Text(
                        text = stringResource(R.string.decoy_preserve_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                } else {
                    repeat(count) { i ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(Spacing.touchTarget)
                                .padding(top = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Numbered and nothing else. A row that said anything
                            // about what this entry opened would be the oracle.
                            val removeLabel = stringResource(R.string.decoy_preserve_remove, i + 1)
                            Text(
                                text = stringResource(R.string.decoy_preserve_entry, i + 1),
                                style = MaterialTheme.typography.bodyLarge,
                                color = VaultColors.OnSurface,
                            )
                            TextButton(
                                onClick = { onRemove(i) },
                                modifier = Modifier
                                    .height(Spacing.touchTarget)
                                    .semantics {
                                        contentDescription = removeLabel
                                    },
                            ) {
                                Text(stringResource(R.string.action_remove), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.touchTarget)
                        .padding(top = Spacing.lg),
                ) {
                    Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// --- shared -----------------------------------------------------------------

/**
 * One statement: what it is, then what it means. Both in words, never a glyph.
 *
 * A third copy of `OnboardingSteps`' and `BackupScreen`'s, which are both file-private
 * for the same reason this is: fifteen lines of `Column` is cheaper duplicated than
 * promoted into a shared file every screen then has to import.
 */
@Composable
private fun Fact(heading: String, body: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.padding(bottom = Spacing.md),
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            color = VaultColors.OnSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
        )
    }
}
