package com.app.vault

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.app.vault.ui.VaultScaffold
import com.app.vault.ui.theme.JetBrainsMono
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Reading and clearing [DebugCrashLog]'s records on the device — `debug-crash-log-screen`.
 *
 * **Debug source set only**, for [DebugCrashLog]'s reason: the release build gets a stub of the
 * same name (`src/release`) that says "not available" and draws nothing, so the screen, the parser
 * and the code that deletes records are not in the release APK at all (D1).
 *
 * The activity holds which step is showing — list, record, confirmation — and routes back through
 * its one handler. This object owns the files (D3).
 */
object CrashLogHook {

    const val available = true

    val icon: Int = R.drawable.ic_bug_report

    /** The drawer row's supporting line. */
    fun summary(context: Context): String {
        // ponytail: listFiles() on the main thread while the drawer composes — at most 20 entries,
        // under a millisecond, debug build only. Move to produceState if the cap ever grows.
        val n = DebugCrashLog.directory(context).listFiles()?.size ?: 0
        return if (n == 0) "None recorded" else "$n recorded"
    }

    @Composable
    fun Screen(
        /** The open record's file name, or null for the list. */
        record: String?,
        onOpen: (String) -> Unit,
        confirmingClear: Boolean,
        onAskClear: () -> Unit,
        onDismissClear: () -> Unit,
        onBack: () -> Unit,
        /** The device test passes a directory of its own, so real records are never evicted. */
        dir: File = DebugCrashLog.directory(LocalContext.current),
    ) {
        var reload by remember { mutableIntStateOf(0) }
        val records by produceState<List<CrashRecord>?>(null, dir, reload) {
            value = withContext(Dispatchers.IO) { read(dir) }
        }
        val scope = rememberCoroutineScope()
        // One frame on an empty surface while ≤20 small files are read; a spinner would flash.
        val all = records ?: return

        val open = record?.let { name -> all.firstOrNull { it.file == name } }
        if (open != null) RecordView(open, onBack) else ListView(all, onOpen, onAskClear, onBack)

        if (confirmingClear && all.isNotEmpty()) {
            ClearConfirmation(
                count = all.size,
                onConfirm = {
                    scope.launch {
                        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
                        reload++
                        onDismissClear()
                    }
                },
                onDismiss = onDismissClear,
            )
        }
    }
}

/**
 * One file in the crash directory. [type] is null when it does not have [DebugCrashLog]'s format;
 * it is still listed, and [trace] is then the file's raw text.
 */
internal class CrashRecord(
    val file: String,
    val at: Long,
    val type: String?,
    val message: String?,
    val facts: List<Pair<String, String>>,
    val trace: String,
) {
    val time: String get() = TIME.format(Instant.ofEpochMilli(at))
    val title: String get() = type?.substringAfterLast('.') ?: "Unreadable record"
}

private val TIME: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

private val NAME = Regex("""crash-(\d+)-\d+\.txt""")

private val FACTS = listOf("version" to "Version", "device" to "Device", "android" to "Android", "thread" to "Thread")

/** Newest first. A file that does not parse sorts by its modification time. */
internal fun read(dir: File): List<CrashRecord> =
    (dir.listFiles() ?: emptyArray())
        .filter { it.isFile }
        .map { parse(it.name, it.readText(), it.lastModified()) }
        .sortedByDescending { it.at }

internal fun parse(name: String, text: String, modified: Long): CrashRecord {
    val at = NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
    val header = text.substringBefore("\n\n", "").lines()
        .associate { it.substringBefore(':').trim() to it.substringAfter(':', "").trim() }
    val trace = text.substringAfter("\n\n", "")
    if (at == null || trace.isBlank() || !header.keys.containsAll(FACTS.map { it.first } + "time")) {
        return CrashRecord(name, modified, null, null, emptyList(), text)
    }
    // `Throwable.toString()` is `class: message`, or the bare class with no message; the message
    // runs to the first frame, so a multi-line one is kept whole.
    val first = trace.substringBefore("\n\tat ")
    return CrashRecord(
        file = name,
        at = at,
        type = first.substringBefore(": "),
        message = first.substringAfter(": ", "").ifBlank { null },
        facts = FACTS.map { (key, label) -> label to header.getValue(key) },
        trace = trace.trimEnd(),
    )
}

@Composable
private fun ListView(
    records: List<CrashRecord>,
    onOpen: (String) -> Unit,
    onAskClear: () -> Unit,
    onBack: () -> Unit,
) {
    VaultScaffold(
        title = "Crash log",
        onBack = onBack,
        // Owns its scroll so the list is lazy and keeps its position; the rows carry
        // ListItem's own 16dp inset, which is the screen inset.
        scrollable = false,
        actions = {
            // Words, not a glyph, for a destructive action — and only while there is
            // anything to clear.
            if (records.isNotEmpty()) {
                TextButton(onClick = onAskClear) {
                    Text("Clear all", style = MaterialTheme.typography.labelLarge, color = VaultColors.Error)
                }
            }
        },
    ) {
        if (records.isEmpty()) {
            EmptyState()
            return@VaultScaffold
        }
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "Newest first. The 20 most recent are kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screenInset, vertical = Spacing.sm),
                )
            }
            items(records, key = { it.file }) { r ->
                ListItem(
                    overlineContent = {
                        Text(
                            if (r.type == null) r.file else r.time,
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        )
                    },
                    headlineContent = { Text(r.title, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = r.type?.let {
                        {
                            Text(
                                r.message ?: "No message",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            // The row says where it goes; the chevron repeats it.
                            contentDescription = null,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = VaultColors.Surface,
                        overlineColor = VaultColors.OnSurfaceVariant,
                        headlineColor = VaultColors.OnSurface,
                        supportingColor = VaultColors.OnSurfaceVariant,
                        trailingIconColor = VaultColors.OnSurfaceVariant,
                    ),
                    modifier = Modifier.clickable { onOpen(r.file) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.screenInset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            tint = VaultColors.OnSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "No crashes recorded",
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
        )
        Text(
            "When the app crashes, the record appears here.",
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordView(r: CrashRecord, onBack: () -> Unit) {
    // The scaffold's own scroll, which is saved across rotation.
    VaultScaffold(title = r.title, onBack = onBack) {
        if (r.type != null) {
            Text(
                r.message ?: "No message",
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurface,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.related),
                modifier = Modifier.padding(vertical = Spacing.section),
            ) {
                (listOf("Time" to r.time) + r.facts).forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = VaultColors.OnSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurface)
                    }
                }
            }
        }
        Text(
            if (r.type != null) "Stack trace" else r.file,
            style = MaterialTheme.typography.titleSmall,
            color = VaultColors.OnSurface,
            modifier = Modifier
                .padding(bottom = Spacing.sm)
                .semantics { heading() },
        )
        // Soft-wrapped, so every frame stays on a phone screen without panning sideways and a
        // screen reader reads it in order. Not selectable: screens sit in NoClipboard on purpose.
        Box(
            Modifier
                .fillMaxWidth()
                .background(VaultColors.SurfaceContainer, RoundedCornerShape(Radius.container))
                .padding(Spacing.md),
        ) {
            Text(
                r.trace,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 18.sp),
                color = VaultColors.OnSurface,
            )
        }
    }
}

/** `DeleteConfirmation`'s shape, words and colours. */
@Composable
private fun ClearConfirmation(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(Radius.container),
        containerColor = VaultColors.SurfaceContainerHigh,
        titleContentColor = VaultColors.OnSurface,
        textContentColor = VaultColors.OnSurfaceVariant,
        title = {
            Text(
                if (count == 1) "Clear 1 crash record?" else "Clear $count crash records?",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                if (count == 1) "It is deleted from this device and cannot be recovered."
                else "They are deleted from this device and cannot be recovered.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear", style = MaterialTheme.typography.labelLarge, color = VaultColors.Error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep", style = MaterialTheme.typography.labelLarge, color = VaultColors.OnSurface)
            }
        },
    )
}
