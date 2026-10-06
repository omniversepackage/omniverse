package com.yodesla.omniverse.app

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.BackupCodec
import com.yodesla.omniverse.core.data.BackupPreview
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Backup & restore (task 60). One click writes the viewer's personal setup — lists,
 * watch progress, Continue-Watching dismissals, collections, display overrides, switched-off
 * libraries and every non-secret setting — into `Downloads/omniverse-backup-YYYYMMDD-HHMM.json`.
 * On a new TV the same screen lists those files, says which sources the backup was taken from,
 * and restores it (Merge keeps the newer watch, Replace starts from the file alone).
 *
 * Sources are never in the file: their configs hold provider logins, so the viewer re-adds them.
 * Writing goes through MediaStore, which needs no storage permission on Android 10+; on older
 * boxes the file lands in the app's own external Downloads folder instead.
 */
@Immutable
private data class BackupEntry(
    val name: String,
    val addedMs: Long,
    val uri: Uri? = null,
    val json: String? = null,
    val preview: BackupPreview? = null,
)

@Composable
fun BackupSection(graph: AppGraph, requirePin: (() -> Unit) -> Unit = { it() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val codec = remember(graph) { BackupCodec(graph.db, io = Dispatchers.IO, clock = graph.clock) }
    val c = OmniTheme.colors
    val t = OmniTheme.type

    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var pending by remember { mutableStateOf<BackupEntry?>(null) }
    val firstFr = remember { FocusRequester() }

    fun save() {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching {
                val json = codec.export()
                writeFile(context, json)
            }
            message = result.fold(
                onSuccess = { name -> "Saved $name. Copy it to the new TV (USB stick, or the Downloader app) and restore it there." },
                onFailure = { e -> "Couldn't save the backup: ${e.message ?: "unknown error"}" },
            )
            busy = false
        }
    }

    fun list() {
        if (busy) return
        listing = true
        pending = null
        busy = true
        scope.launch {
            files = listFiles(context)
            busy = false
        }
    }

    fun open(entry: BackupEntry) {
        if (busy) return
        busy = true
        scope.launch {
            val text = readFile(context, entry)
            val preview = text?.let { withContext(Dispatchers.IO) { codec.preview(it) } }
            busy = false
            if (text == null || preview == null) {
                message = "That file isn't a readable Omniverse backup."
            } else {
                pending = entry.copy(json = text, preview = preview)
                message = null
            }
        }
    }

    fun runRestore(text: String, replace: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val result = codec.import(text, replace)
            busy = false
            pending = null
            listing = false
            message = if (result.error != null) result.error else {
                val from = if (result.sourceNames.isEmpty()) "no sources" else result.sourceNames.joinToString(", ")
                "Restored ${result.rowsWritten} items (backup from: $from). " +
                    "Re-add your sources to bring the titles back." +
                    if (result.settingsSkipped > 0) " ${result.settingsSkipped} secret settings were skipped." else ""
            }
            // Every screen reads user data from the DB; recreating the activity reloads them all.
            if (result.ok) context.activity()?.recreate()
        }
    }

    // M4 (audit 73): Restore / Replace rewrite every profile's data. They run only after the
    // parental PIN (when one is set) — the same gate flow profile admin uses, wired by TvRoot.
    fun restore(entry: BackupEntry, replace: Boolean) {
        val text = entry.json ?: return
        if (busy) return
        requirePin { runRestore(text, replace) }
    }

    // No auto-focus on appear: in the two-pane Settings this section is shown as soon as its category is
    // highlighted, and grabbing focus here pulled the D-pad out of the category list (Kory: "can't scroll
    // past Backup & restore"). Entering the pane (OK / Right) focuses its first row as usual.

    Column(
        Modifier.padding(top = OmniSpacing.l, bottom = OmniSpacing.xl).widthIn(max = 960.dp),
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
    ) {
        Text("Backup & restore", style = t.title, color = c.accent)
        Text(
            "Save your lists, watch progress, collections and settings to one file in the Downloads folder. " +
                "Sources are not included — you re-add them on the new TV, then restore the file and everything else comes back.",
            style = t.caption, color = c.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            OmniButton(if (busy) "Working…" else "Save backup", { save() }, Modifier.focusRequester(firstFr), primary = true)
            OmniButton("Restore…", { list() })
        }
        message?.let { Text(it, style = t.body, color = c.accent, modifier = Modifier.widthIn(max = 900.dp)) }

        val chosen = pending
        if (chosen != null) {
            val preview = chosen.preview
            // Not a card: a dead focus stop on a D-pad remote is worse than none. The three
            // choices below are what the viewer is meant to land on.
            Column(Modifier.widthIn(max = 900.dp).padding(horizontal = OmniSpacing.xs), verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs)) {
                Text(chosen.name, style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        preview?.exportedMs?.takeIf { it > 0 }?.let { "saved " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                        preview?.profiles?.let { "$it profiles" },
                        preview?.favorites?.let { "$it in My List" },
                        preview?.progress?.let { "$it watched" },
                        preview?.collections?.let { "$it collections" },
                        preview?.settings?.let { "$it settings" },
                        preview?.sourceNames?.takeIf { it.isNotEmpty() }?.let { "from: ${it.joinToString(", ")}" },
                    ).joinToString("  ·  "),
                    style = t.caption, color = c.textSecondary,
                )
            }
            Text("Merge keeps whatever this TV already has when it is newer. Replace starts from the file alone.", style = t.caption, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                OmniButton("Merge", { restore(chosen, replace = false) }, primary = true)
                OmniButton("Replace", { restore(chosen, replace = true) })
                OmniButton("Cancel", { pending = null })
            }
        } else if (listing) {
            if (busy) Text("Looking in Downloads…", style = t.caption, color = c.textSecondary)
            if (!busy && files.isEmpty()) Text("No backup files found in Downloads.", style = t.caption, color = c.textSecondary)
            files.forEach { entry ->
                FocusCard(
                    onClick = { open(entry) },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(44.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = OmniSpacing.m).fillMaxWidth().height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                    ) {
                        Text(entry.name, style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.addedMs)), style = t.caption, color = c.textSecondary)
                    }
                }
            }
        }
    }
}

private const val FILE_PREFIX = "omniverse-backup"

private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

/** Android 10+: MediaStore Downloads, which needs no storage permission. Older: the app's own folder. */
private suspend fun writeFile(context: Context, json: String): String = withContext(Dispatchers.IO) {
    val name = "$FILE_PREFIX-${fileNameFormat.format(Date())}.json"
    if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android refused to create the file")
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            ?: error("Android refused to open the file for writing")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } else {
        val dir = legacyDir(context) ?: error("No writable Downloads folder")
        File(dir, name).writeText(json)
    }
    name
}

@Suppress("NewApi")
private suspend fun listFiles(context: Context): List<BackupEntry> = withContext(Dispatchers.IO) {
    val out = mutableListOf<BackupEntry>()
    if (Build.VERSION.SDK_INT >= 29) {
        val cursor = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$FILE_PREFIX-%.json'",
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )
        cursor?.use {
            val idAt = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameAt = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateAt = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (it.moveToNext()) {
                out.add(
                    BackupEntry(
                        name = it.getString(nameAt),
                        addedMs = it.getLong(dateAt) * 1000L,
                        uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(idAt)),
                    ),
                )
            }
        }
    } else {
        legacyDir(context)?.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".json") }?.forEach { f ->
            out.add(BackupEntry(f.name, f.lastModified(), Uri.fromFile(f)))
        }
        out.sortByDescending { it.addedMs }
    }
    out
}

private suspend fun readFile(context: Context, entry: BackupEntry): String? = withContext(Dispatchers.IO) {
    runCatching {
        val uri = entry.uri ?: return@runCatching null
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }.getOrNull()
}

private fun legacyDir(context: Context): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
