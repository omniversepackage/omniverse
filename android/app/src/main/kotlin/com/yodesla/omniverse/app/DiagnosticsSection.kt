package com.yodesla.omniverse.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
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
 * Settings › About & updates (tasks 78 + 104): the on-device crash log. Crash reports live in
 * filesDir/crash (see CrashLog.kt) and never leave the device on their own — this screen shows how
 * many there are, opens the latest one in a scrollable D-pad-focusable panel, offers the latest as a
 * plain .txt in Downloads for whoever is helping, and offers to clear them.
 * Same extraSections slot as LibrariesSection / BackupSection.
 */
@Composable
fun DiagnosticsSection(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val crashDir = remember { CrashLog.dirOf(context) }
    val c = OmniTheme.colors
    val t = OmniTheme.type

    var files by remember { mutableStateOf<List<CrashInfo>>(emptyList()) }
    var openName by remember { mutableStateOf<String?>(null) }
    var openText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val detailFr = remember { FocusRequester() }

    suspend fun refresh() { files = CrashLog.list(crashDir) }
    LaunchedEffect(Unit) { refresh() }

    fun openCrash(info: CrashInfo) {
        message = null
        openName = info.name
        openText = null
        scope.launch { openText = CrashLog.read(crashDir, info.name) }
    }

    fun deleteAll() {
        scope.launch {
            CrashLog.deleteAll(crashDir)
            openName = null; openText = null
            refresh()
            message = "Crash logs deleted."
        }
    }

    /** Task 104: hand the newest report over as a plain .txt in Downloads (MediaStore, no permission). */
    fun saveLatest() {
        val info = files.firstOrNull() ?: return
        message = null
        scope.launch {
            val text = CrashLog.read(crashDir, info.name)
            if (text == null) { message = "Could not read that report."; return@launch }
            message = runCatching { saveToDownloads(context, text, info.epochMs) }
                .fold({ "Saved to Downloads: $it" }, { "Could not save to Downloads: ${it.message}" })
        }
    }

    LaunchedEffect(openName) { if (openName != null) detailFr.requestFocusWhenReady() }

    Column(
        Modifier.padding(top = OmniSpacing.l, bottom = OmniSpacing.xl).widthIn(max = 960.dp),
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
    ) {
        Text(stringResource(R.string.diagnostics_title), style = t.title, color = c.accent)
        Text(stringResource(R.string.diagnostics_note), style = t.caption, color = c.textSecondary)
        Text(
            "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})  ·  " +
                stringResource(if (graph.publicBuild) R.string.diagnostics_public_build else R.string.diagnostics_dev_build),
            style = t.caption, color = c.textSecondary,
        )
        // Task 104: the count lives on one focusable row, and that row opens the newest report.
        FocusCard(
            onClick = { files.firstOrNull()?.let { openCrash(it) } },
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(44.dp),
        ) {
            Row(
                Modifier.padding(horizontal = OmniSpacing.m).fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
            ) {
                Text(
                    stringResource(R.string.diagnostics_crash_reports, files.size),
                    style = t.body, color = c.textPrimary, modifier = Modifier.weight(1f),
                )
                Text(
                    if (files.isEmpty()) stringResource(R.string.diagnostics_no_crashes)
                    else stringResource(
                        R.string.diagnostics_view_latest,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(files.first().epochMs)),
                    ),
                    style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            OmniButton(stringResource(R.string.diagnostics_save), { saveLatest() }, primary = files.isNotEmpty())
            OmniButton(stringResource(R.string.diagnostics_delete), { deleteAll() })
        }
        message?.let { Text(it, style = t.body, color = c.accent, modifier = Modifier.widthIn(max = 900.dp)) }

        val open = openName
        if (open != null) {
            // Full report: a FocusCard so the D-pad lands on it and Up/Down scroll the text.
            // 16sp monospace — readable from the couch.
            FocusCard(
                onClick = { openName = null; openText = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp).focusRequester(detailFr),
                glass = false,
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = OmniSpacing.m, vertical = OmniSpacing.s),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
                ) {
                    Text(
                        openText ?: "Reading…",
                        style = t.title.copy(fontSize = 16.sp, lineHeight = 23.sp),
                        fontFamily = FontFamily.Monospace,
                        color = c.textPrimary,
                    )
                }
            }
            OmniButton(stringResource(R.string.diagnostics_close), { openName = null; openText = null })
        } else {
            files.forEach { info ->
                FocusCard(
                    onClick = { openCrash(info) },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(44.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = OmniSpacing.m).fillMaxWidth().height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                    ) {
                        Text(
                            info.summary.ifBlank { info.name },
                            style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.epochMs)),
                            style = t.caption, color = c.textSecondary,
                        )
                     }
                }
            }
        }
    }
}

private val crashFileNameFormat = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

/**
 * Task 104: write one crash report into Downloads. Android 10+ uses MediaStore, which needs no
 * storage permission; older Fire OS / TV boxes fall back to the app's own Downloads folder
 * (same split BackupSection uses).
 */
@Suppress("NewApi")
private suspend fun saveToDownloads(context: Context, text: String, epochMs: Long): String = withContext(Dispatchers.IO) {
    val name = "omniverse-crash-${crashFileNameFormat.format(Date(epochMs))}.txt"
    if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android refused to create the file")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            ?: error("Android refused to open the file for writing")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("No writable Downloads folder")
        File(dir, name).writeText(text)
    }
    name
}
