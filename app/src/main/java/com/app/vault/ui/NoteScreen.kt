package com.app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.res.stringResource
import com.app.vault.NoteSettings
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.vault.FolderColour
import com.app.vault.NoteMarkdown
import com.app.vault.R
import com.app.vault.VaultItem
import com.app.vault.VaultNote
import com.app.vault.VaultRename
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import kotlinx.coroutines.delay

/**
 * The note editor — P2-21, rebuilt by `notes-as-documents`.
 *
 * **It stopped being a form.** P2-21 drew two `OutlinedTextField`s and a Save button,
 * which is what a dialog looks like. A note is a document: a borderless title, a
 * borderless body, and nothing drawn around either. Apple Notes and Keep both draw
 * nothing around the text, and the difference is mostly what you take away.
 *
 * **Reading and editing are the same screen, not two modes with a switch.** The body
 * is rendered — headings sized, checkboxes tickable, attachments inline — until the
 * text itself is tapped, and then it is a field. That shape is *required* rather than
 * chosen: a checkbox has to be tappable, and a `BasicTextField` cannot give a span its
 * own click. Tapping a checkbox toggles it without opening the field at all, which is
 * what a person expects from a list they are working through.
 *
 * **Autosave, no Save button.** 800ms after typing stops and again on the way out. A
 * Save button on a note is a question about work already done, and the discard
 * confirmation that used to guard it went with it. An empty untitled note is
 * discarded rather than written, so a stray tap leaves no debris.
 *
 * **No overflow menu**, which is an app-wide rule rather than a preference here:
 * `ScreenChromeTest.noOverflowMenuAnywhere` deletes it everywhere except the grid's
 * sort control, the viewer's menu and a note tile's own sheet. Undo and redo are
 * named bar actions.
 *
 * **This screen is for writing, and it holds nothing else.** The colour swatches were
 * a row under the body and pin was a mark in the bar — decoration and filing, on the
 * surface where the words are. Both left, together with move and delete, and all four
 * are on [NoteSheet], reached from the note's own tile. Nothing you do *to* a note is
 * on the screen you write it on any more.
 *
 * **Three bar actions: attach, undo, redo.** Each acts on the note being written, which
 * is what earns a place here — attach puts an image in it, and the two arrows step its
 * text. Attach was on the format bar, where a scrolling row hid it.
 *
 * **Share is the fourth, at the trailing end.** It is the one act *on* the note offered
 * from the screen the note is open on, and it is here because a note has no viewer to
 * carry it: what a photograph gets from the viewer's bottom row, a note gets from this
 * bar. It commits before it hands the note over, so what is sealed is the text on
 * screen and not the text as it stood at the last autosave.
 */
@Composable
fun NoteScreen(
    title: String,
    body: String,
    onSave: (title: String, body: String) -> Unit,
    onCancel: () -> Unit,
    /** Discards an untitled, empty note instead of saving it. */
    onDiscard: () -> Unit = onCancel,
    /**
     * P4-2's send flow on the note being written.
     *
     * **Null until there is a row to share** — a note that has never been written is
     * not a vault item yet, and there is nothing to seal. The control is *disabled*
     * rather than absent, which is undo and redo's idiom on this bar: it becomes live
     * at the first autosave, and a control that appeared mid-sentence would move the
     * two beside it.
     */
    onShare: (() -> Unit)?,
    /**
     * `captures-folder-and-log`. P2-11's scoped export on the note being written,
     * beside the share control and following its idiom exactly — **null until there is
     * a row to export**, disabled rather than absent, so it becomes live at the first
     * autosave without the two controls beside it moving.
     *
     * **On every note, not on particular ones.** Share is offered on all of them, and
     * two neighbouring controls following different rules is a difference the user
     * cannot account for. Gating it on a filename would also mean a renamed note
     * losing its export.
     */
    onExport: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** `notes-settings`: moving checked items and the incognito keyboard. */
    settings: NoteSettings = NoteSettings(),
) {
    // **Seeded once, and the parameters are an initial value rather than a source.**
    // Keyed on `title`/`body` these reset mid-sentence: the first autosave gives the
    // host a row, the host passes the saved title and text back down, the keys change
    // and everything typed in the meantime is replaced by what was written 800ms ago.
    // The editor covers the grid and there is no way to reach a different note without
    // leaving it, so there is nothing a re-seed could be for.
    var typedTitle by remember { mutableStateOf(title) }
    // **A `TextFieldState`, not a `TextFieldValue`** — `note-list-continuation-ime`. The
    // list rules run as an `InputTransformation` on the buffer the editor is about to
    // commit, so nothing writes a second value after the fact for a keyboard to overwrite.
    val bodyState = remember { TextFieldState(body) }
    var editing by remember { mutableStateOf(body.isEmpty() && title.isEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }

    /**
     * **Editing is sticky, and nothing takes it away while the screen is up.**
     *
     * It used to close on the body losing focus, which is wrong twice over. Tapping
     * the *title* takes focus off the body, so typing a title dropped the note back to
     * the rendered view — and a rendered empty note is zero height, so there was then
     * nothing left on screen to tap to get back in. That is the reported bug: a title
     * you can type, a body you cannot, and "Start writing" flashing as the field
     * appeared and vanished.
     *
     * Reading is what you arrive at; the moment you touch the text you are editing
     * until you leave. Apple Notes behaves the same way, and it means a checkbox is
     * tappable exactly when a checkbox is what you are looking at.
     */
    val history = remember { NoteHistory(body) }
    val focus = remember { FocusRequester() }

    val bodyText = bodyState.text.toString()
    val size = VaultNote.sizeOf(bodyText)
    val overCap = size > VaultNote.MAX_BODY_BYTES
    val empty = typedTitle.isBlank() && bodyText.isBlank()

    // Read at the moment the save fires rather than captured when the effect started
    // — a debounce that wrote stale text would be worse than no autosave at all.
    val latest = rememberUpdatedState(Triple(typedTitle, bodyText, overCap))

    // Set by [leave], which has already committed. Without it the safety net below
    // fires *after* the host has cleared the row being edited, so it takes the
    // new-note path and inserts a **second copy** — which is exactly what pressing
    // back produced.
    var left by remember { mutableStateOf(false) }

    fun commit() {
        if (left) return
        val (t, b, over) = latest.value
        if (over) return
        if (t.isBlank() && b.isBlank()) return
        if (VaultRename.normalise(t, VaultNote.EXTENSION) == null) return
        onSave(t, b)
    }

    // 800ms after typing stops. Keyed on the text, so each keystroke restarts it and
    // a pause is what writes.
    //
    // **The history is recorded here too, and that is the fix for an undo that did
    // nothing.** It was recorded in `onValueChange`, once per keystroke, so one tap
    // stepped back one letter — `NoteHistory`'s own contract says entries are pushed
    // when the text has settled, and this is the pause that settles it. A state
    // restored by undo re-enters this effect and is dropped on the way in:
    // `record` ignores a text equal to the one it is already on, which is what keeps
    // redo reachable.
    LaunchedEffect(typedTitle, bodyText) {
        delay(AUTOSAVE_MS)
        history.record(bodyText)
        commit()
    }
    // The safety net: the screen going away without [leave] — a process death, a
    // lock, another screen taking over — must not lose what was typed.
    DisposableEffect(Unit) { onDispose { commit() } }

    val leave = {
        if (empty) {
            left = true
            onDiscard()
        } else {
            commit()
            left = true
            onCancel()
        }
    }

    LaunchedEffect(editing) { if (editing) focus.requestFocus() }

    // The toolbar is the scaffold's bottom bar, supplied through the composition local
    // that exists so a screen can fill that slot without naming an inset modifier of
    // its own — `ScreenChromeTest` keeps those in the wrapper alone.
    CompositionLocalProvider(
        LocalScaffoldBottomBar provides if (editing) {
            { FormatBar(state = bodyState) }
        } else {
            null
        },
    ) {
    val titleLabel = stringResource(R.string.note_title)
    val noteLabel = stringResource(R.string.note_body)
    VaultScaffold(
        title = stringResource(if (title.isEmpty()) R.string.fab_new_note else R.string.note_edit),
        modifier = modifier,
        onBack = leave,
        actions = {
            // **The caret survives the step.** These used to hand back a bare
            // `TextFieldValue`, whose selection defaults to 0 — so undo scrolled a
            // long note to its first line and left the change it had just made off
            // the bottom of the screen. Pressing it looked like pressing nothing.
            IconButton(
                onClick = { history.undo()?.let { bodyState.restore(it) } },
                enabled = history.canUndo,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_undo),
                    contentDescription = stringResource(R.string.undo),
                    tint = VaultColors.OnSurface,
                )
            }
            IconButton(
                onClick = { history.redo()?.let { bodyState.restore(it) } },
                enabled = history.canRedo,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_redo),
                    contentDescription = stringResource(R.string.redo),
                    tint = VaultColors.OnSurface,
                )
            }
            // The trailing end of the bar, where the act on the whole document goes.
            // **Committed first**, so the container carries the words on screen: the
            // autosave is 800ms behind a hand that is still typing, and a share is
            // exactly the moment that gap would show. Over the cap nothing commits,
            // so nothing is offered to seal either.
            IconButton(
                onClick = { commit(); onShare?.invoke() },
                enabled = onShare != null && !overCap,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = stringResource(R.string.share_securely),
                    tint = VaultColors.OnSurface,
                )
            }
            // `captures-folder-and-log`. Beside share, and committed first for the
            // same reason: the autosave is 800ms behind a hand that is still typing,
            // and an export is exactly the moment that gap would show.
            IconButton(
                onClick = { commit(); onExport?.invoke() },
                enabled = onExport != null && !overCap,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_output),
                    contentDescription = stringResource(R.string.export),
                    tint = VaultColors.OnSurface,
                )
            }
            // **No overflow.** `ScreenChromeTest.noOverflowMenuAnywhere` permits one in
            // three named files and this is not one of them — actions here are named
            // and visible, which is P2-7's rule rather than an exception to it. Back is
            // at the leading end of this bar and the acts on the document at the
            // trailing one; undo and redo belong to the act of writing.
        },
    ) {
        IncognitoInput(settings.incognito) {
        BasicTextField(
            value = typedTitle,
            onValueChange = { typedTitle = it; problem = null },
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = VaultColors.OnSurface),
            cursorBrush = SolidColor(VaultColors.Primary),
            singleLine = true,
            decorationBox = { field ->
                if (typedTitle.isEmpty()) {
                    Text(
                        titleLabel,
                        style = MaterialTheme.typography.headlineMedium,
                        color = VaultColors.OnSurfaceVariant,
                    )
                }
                field()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md, bottom = Spacing.sm)
                .semantics { contentDescription = titleLabel },
        )
        }

        if (editing) {
            // **The list rules live in `inputTransformation`, not in a value callback.**
            // They run on the buffer the editor is about to commit, so the marker is part
            // of the same edit the keyboard made rather than a second write landing after
            // it. The old `onValueChange` wrote a *second* value once the editor had
            // already committed one, and an input method keeps its own copy of the text:
            // a value it never issued is a value its next sync overwrites, which the
            // device log caught doing exactly that 2ms after each marker appeared.
            IncognitoInput(settings.incognito) {
            BasicTextField(
                state = bodyState,
                inputTransformation = NoteEdits.ListContinuation,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = VaultColors.OnSurface,
                    lineHeight = BodyLineHeight,
                ),
                cursorBrush = SolidColor(VaultColors.Primary),
                keyboardOptions = KeyboardOptions.Default,
                lineLimits = TextFieldLineLimits.MultiLine(),
                decorator = TextFieldDecorator { field ->
                    if (bodyText.isEmpty()) {
                        Text(
                            stringResource(R.string.note_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = VaultColors.OnSurfaceVariant,
                        )
                    }
                    field()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = noteLabel },
            )
            }
        } else {
            NoteBlocks(
                text = bodyText,
                // A tap on a checkbox toggles it and does **not** open the field.
                // Working through a list is not editing it.
                onToggle = { line ->
                    // One edit and one history entry for the tick and the move together,
                    // so one undo puts back both — D3.
                    val next = NoteMarkdown.toggle(bodyText, line, settings.moveChecked)
                    bodyState.edit { replace(0, length, next) }
                    history.record(next)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // A rendered empty note is one empty paragraph and therefore no
                    // height at all — nothing to tap, which is half of why the body
                    // was unreachable. The tap target is the page, not the glyphs.
                    .heightIn(min = TapTarget)
                    .clickableNoRipple { editing = true },
            )
        }

        // Appears only when it matters — a counter over every note anyone ever writes
        // is a number nobody needs, and this is a ceiling met once.
        if (overCap) {
            Text(
                text = stringResource(R.string.note_too_long, size, VaultNote.MAX_BODY_BYTES),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }

        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
    }
}

/**
 * A history state, put back under the caret that was there.
 *
 * Clamped rather than kept: the restored text can be shorter than the one on screen,
 * and a selection past its end is an index out of bounds. The caret lands where the
 * edit was, which is the only place a person is looking after pressing undo.
 */
private fun TextFieldState.restore(text: String) {
    val caret = selection.start.coerceIn(0, text.length)
    edit {
        replace(0, length, text)
        selection = TextRange(caret)
    }
}

/**
 * A tap that opens the editor, with no ripple.
 *
 * The rendered body is text, not a control — a ripple across a paragraph would say it
 * is a button, and it is a page you are putting a caret into.
 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

private val BodyLineHeight = 26.sp

/** The rendered body's minimum tap area — see the `heightIn` above. */
private val TapTarget = 200.dp
private const val AUTOSAVE_MS = 800L

/**
 * Everything a note can have done to it without opening it — reached from the
 * three-dot mark on its tile.
 *
 * ***The four acts on this sheet were in four places.*** Colour was a row of swatches
 * under the editor's body, pin was a mark in the editor's bar, and move and delete
 * were selection-bar actions you first had to long-press a tile to reach. Nothing
 * about a note was where the note was: to tint a tile you opened the note, scrolled
 * past what you had written, and chose a colour on a screen the colour does not touch.
 *
 * **A sheet, on [FolderSheet]'s shape and for its reasons** — it is the same question
 * one row down. A dialog blocks until it is answered; this offers a set of actions
 * over the grid, dismisses on a drag or a tap outside having done nothing, and leaves
 * the tile it is about visible behind it. §6.7's one dialog is still the item delete,
 * which this raises rather than replaces.
 *
 * **A three-dot mark and not a long-press**, which is the one place this parts from
 * the folder sheet. A note is an item, and a long-press on an item begins a selection
 * — a gesture the grid has used since P2-11 and which is how a user moves twelve
 * things at once. The mark is the only route in, so it is drawn rather than hidden:
 * `ScreenChromeTest` grants it the exemption it grants the viewer's menu, on the same
 * terms — N acts on *one object*, never the screen's own actions in hiding.
 *
 * **Every row finishes and closes the sheet**, colour included. Pinning reorders the
 * grid and colouring repaints a tile; both are changes to the thing behind the sheet,
 * and a sheet that stays up over them is a sheet in front of its own result.
 *
 * **Delete is last and outlined.** §6.1 allows one primary-coloured action per surface
 * and nothing here earns it. It is not tinted destructive either: the note moves to
 * the trash and waits out its window, and the confirmation is what states that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(
    note: VaultItem,
    onPinned: (Boolean) -> Unit,
    onMove: () -> Unit,
    onColour: (FolderColour) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = VaultColors.Surface,
    ) {
        // The note being acted on, named — the sheet covers the grid, and a set of
        // verbs with no subject is a set of verbs about whatever the user remembers
        // touching. An untitled note is named by [VaultNote.titleOf], which is the
        // same string its tile draws, so the sheet and the tile agree.
        Text(
            text = VaultNote.titleOf(note),
            style = MaterialTheme.typography.titleLarge,
            color = VaultColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = Spacing.screenInset,
                end = Spacing.screenInset,
                bottom = Spacing.sm,
            ),
        )

        // The label states the act, not the state: a row reading "Pinned" would be a
        // status the user then has to work out how to change.
        SheetRow(
            label = stringResource(if (note.pinned) R.string.note_unpin else R.string.note_pin),
            icon = R.drawable.ic_push_pin,
            onClick = { onPinned(!note.pinned) },
        )
        SheetRow(
            label = stringResource(R.string.move_to_folder),
            icon = R.drawable.ic_drive_file_move,
            onClick = onMove,
        )

        NoteColourRow(current = note.colour, onChoose = onColour)

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.screenInset,
                    end = Spacing.screenInset,
                    top = Spacing.section,
                    bottom = Spacing.section,
                ),
        ) {
            Text(stringResource(R.string.note_delete), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One act on the note, named and glyphed — the folder sheet's row, one level down. */
@Composable
private fun SheetRow(label: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                // The label carries the meaning; the glyph repeats it.
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = VaultColors.Surface,
            headlineColor = VaultColors.OnSurface,
            leadingIconColor = VaultColors.OnSurfaceVariant,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * The six colours a note can take, in place — see [NoteSheet].
 *
 * **The folder sheet's row, drawn identically and deliberately.** The same six values
 * tint a folder's tile and a note's tile, from the same palette call; two different
 * pickers for one set of values would be two things to learn. It is a copy rather
 * than a shared composable because the two sheets are the only callers and a private
 * row in each is smaller than a component neither of them owns.
 *
 * **The current one carries a tick and not only a tint**, §6.4: on a row whose entire
 * content is colour, a selection shown by colour alone is shown by nothing. Each
 * swatch names its colour to a screen reader, the same rule read the other way.
 */
@Composable
private fun NoteColourRow(current: FolderColour, onChoose: (FolderColour) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenInset, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FolderColour.entries.forEach { colour ->
            val colourName = stringResource(colour.label)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Spacing.touchTarget)
                    .background(VaultColors.folder(colour), CircleShape)
                    .clickable { onChoose(colour) }
                    .semantics {
                        contentDescription = colourName
                        selected = colour == current
                    },
            ) {
                if (colour == current) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_circle),
                        // The swatch's own semantics say which colour this is and
                        // that it is selected; this glyph would say it twice.
                        contentDescription = null,
                        tint = VaultColors.OnSurface,
                    )
                }
            }
        }
    }
}

/**
 * The incognito keyboard — `notes-settings`, D4. While [on], every input method started inside
 * [content] is asked not to learn from what is typed: the request is passed on with
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` set on its `EditorInfo`. Suggestions and autocorrect are
 * untouched — the flag stops learning, not help. An IME may ignore it; the row says so.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun IncognitoInput(on: Boolean, content: @Composable () -> Unit) {
    if (!on) return content()
    val interceptor = remember {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing = nextHandler.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    // After the field fills `info`, so its own options are kept and the flag added.
                    override fun createInputConnection(outAttributes: EditorInfo) =
                        request.createInputConnection(outAttributes).also {
                            outAttributes.imeOptions =
                                outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        }
                },
            )
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
