package com.app.vault.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.app.vault.NoteMarkdown
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * A note's Markdown, drawn — `notes-as-documents`.
 *
 * [NoteMarkdown] does the reading and knows nothing about Compose; this does the
 * drawing and knows nothing about the syntax. The split is what keeps every parsing
 * rule checkable on the JVM.
 *
 * **A checkbox is tapped, not typed.** Tapping one calls [onToggle] with the line it
 * sits on, and the caller rewrites that line — which is why every block carries its
 * line index out of the parser.
 *
 * **`remove-note-attachments` took the image block out.** A note carries no attachments,
 * so there is no attachment to resolve, no thumbnail to look up and no placeholder for a
 * reference that resolves to nothing. A body that still holds `![](vault:<hex>)` arrives
 * here as an ordinary paragraph and is drawn as the literal Markdown it is.
 */
@Composable
fun NoteBlocks(
    text: String,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = NoteMarkdown.parse(text)
    // `destination-settings-and-slideshow` D3. A ticked item that moves to the bottom of its
    // list slides there, so the eye can follow it — §8's enter, 250ms `EaseOut`. Compose does
    // not honour the animator scale on its own, so at zero the move is a snap.
    val context = LocalContext.current
    val motion = remember {
        val off = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        BoundsTransform { _, _ -> if (off) snap() else tween(MOVE_MS, easing = EaseOut) }
    }
    // A checklist line is keyed by its words, so the same item is the same node after it
    // moves; every other block by its line, which a move within a list never changes.
    val seen = HashMap<String, Int>()
    LookaheadScope {
        Column(modifier) {
            blocks.forEach { block ->
                val base = if (block is NoteMarkdown.Block.Check) "c:" + block.content.text else "l:${block.line}"
                val occurrence = seen.merge(base, 1, Int::plus)!!
                key("$base#$occurrence") {
                    Box(Modifier.animateBounds(this@LookaheadScope, boundsTransform = motion)) {
                        Block(block, onToggle)
                    }
                }
            }
        }
    }
}

/** One block, drawn. */
@Composable
private fun Block(block: NoteMarkdown.Block, onToggle: (Int) -> Unit) {
    when (block) {
        is NoteMarkdown.Block.Heading -> Text(
            text = styled(block.content),
            style = if (block.level == 1) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
        )

        is NoteMarkdown.Block.Bullet -> Marked("•", styled(block.content))

        is NoteMarkdown.Block.Numbered -> Marked("${block.number}.", styled(block.content))

        is NoteMarkdown.Block.Check -> Checkable(block) { onToggle(block.line) }

        is NoteMarkdown.Block.Paragraph -> Text(
            text = styled(block.content),
            style = MaterialTheme.typography.bodyLarge,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(vertical = Spacing.xs / 2),
        )
    }
}

/** §8's enter, for a checklist line moving to its new place. */
private const val MOVE_MS = 250

/** A bullet or a number, and the line beside it, aligned on the text's own left edge. */
@Composable
private fun Marked(mark: String, content: AnnotatedString) {
    Row(Modifier.padding(vertical = Spacing.xs / 2)) {
        Text(
            text = mark,
            style = MaterialTheme.typography.bodyLarge,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.width(MarkWidth),
        )
        Text(content, style = MaterialTheme.typography.bodyLarge, color = VaultColors.OnSurface)
    }
}

/**
 * A checklist item. The whole row is the target, not the box — a 20dp square is a
 * precision tap nobody should have to make.
 */
@Composable
private fun Checkable(block: NoteMarkdown.Block.Check, onToggle: () -> Unit) {
    val spoken = if (block.done) stringResource(R.string.note_completed, block.content.text) else block.content.text
    Row(
        Modifier
            .fillMaxWidth()
            .height(Spacing.touchTarget)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = Spacing.md)
                .size(BoxSize)
                .clip(RoundedCornerShape(Radius.control))
                .background(if (block.done) VaultColors.Primary else VaultColors.SurfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (block.done) {
                Text("✓", style = MaterialTheme.typography.labelLarge, color = VaultColors.OnPrimary)
            }
        }
        Text(
            text = styled(block.content),
            style = MaterialTheme.typography.bodyLarge,
            color = if (block.done) VaultColors.OnSurfaceVariant else VaultColors.OnSurface,
            textDecoration = if (block.done) TextDecoration.LineThrough else null,
        )
    }
}

/**
 * An attachment, inline.
 *
 * A reference that resolves to nothing draws a placeholder saying so, in words — an
 * empty box would read as a broken screen, and an exception would lose the note.
 */
@Composable
private fun Attachment(itemId: String, bitmap: Bitmap?, onOpen: () -> Unit) {
    Box(
        Modifier
            .padding(vertical = Spacing.sm)
            .fillMaxWidth()
            .height(AttachmentHeight)
            .clip(RoundedCornerShape(Radius.container))
            .background(VaultColors.SurfaceContainerHigh)
            .clickable(enabled = bitmap != null, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.note_attached_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(AttachmentHeight),
            )
        } else {
            val missing = stringResource(R.string.note_attachment_missing_label, itemId)
            Text(
                text = stringResource(R.string.note_attachment_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(Spacing.md).semantics {
                    contentDescription = missing
                },
            )
        }
    }
}

/** [NoteMarkdown.Inline] as an `AnnotatedString`. The one place spans become styles. */
@Composable
private fun styled(content: NoteMarkdown.Inline): AnnotatedString = buildAnnotatedString {
    append(content.text)
    content.spans.forEach { span ->
        addStyle(
            when (span.style) {
                NoteMarkdown.Style.Bold -> SpanStyle(fontWeight = FontWeight.W600)
                NoteMarkdown.Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                NoteMarkdown.Style.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            },
            span.from,
            span.to,
        )
    }
}

private val MarkWidth = 24.dp
private val BoxSize = 22.dp
private val AttachmentHeight = 200.dp
