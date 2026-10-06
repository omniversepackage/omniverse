package com.yodesla.omniverse.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class LibraryRow(val sourceId: String, val sourceName: String, val kind: ContentKind, val id: String, val name: String) {
    val key get() = "${kind.name}|$sourceId|$id"
}

/**
 * Settings > Libraries: switch whole Movies/Shows libraries (Plex sections, IPTV VOD/series
 * categories) off per profile. Off libraries leave the category rail and every "All" view.
 * Nothing is changed on the server; this is a local view preference, not a parental lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun LibrariesSection(graph: AppGraph) {
    val rows by remember(graph) {
        graph.sources.sources().flatMapLatest { sources ->
            val flows = sources.flatMap { src ->
                listOf(ContentKind.VOD, ContentKind.SERIES).map { kind ->
                    graph.catalog.categories(src.id, kind, includeHidden = true)
                        .map { cats -> cats.map { LibraryRow(src.id.value, src.name, kind, it.remoteId.value, it.name) } }
                }
            }
            if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList().flatten() }
        }
    }.collectAsState(initial = emptyList())
    val off by remember(graph) { graph.userData.hiddenCategoryKeys() }.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    val c = OmniTheme.colors
    val t = OmniTheme.type
    Column(Modifier.padding(top = OmniSpacing.l, bottom = OmniSpacing.xl).widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        Text("Libraries", style = t.title, color = c.accent)
        Text("Turn whole movie or show libraries off. They disappear from the category list and All, and nothing changes on the server.",
            style = t.caption, color = c.textSecondary)
        rows.groupBy { it.sourceId }.forEach { (sourceId, libs) ->
            val hiddenCount = libs.count { it.key in off }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                Text(libs.first().sourceName, style = t.body, color = c.textPrimary)
                Text("${libs.size - hiddenCount} of ${libs.size} on", style = t.caption, color = c.textSecondary)
                OmniButton(if (open == sourceId) "Done" else "Choose libraries", { open = if (open == sourceId) null else sourceId })
            }
            if (open == sourceId) libs.forEach { lib ->
                val on = lib.key !in off
                FocusCard(
                    onClick = { scope.launch { graph.userData.setCategoryHidden(com.yodesla.omniverse.core.model.SourceId(lib.sourceId), lib.kind, lib.id, hidden = on) } },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(44.dp),
                ) {
                    Row(Modifier.padding(horizontal = OmniSpacing.m).fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                        Text(if (on) "On" else "Off", style = t.body, color = if (on) c.accent else c.textTertiary)
                        Text(lib.name, style = t.body, color = if (on) c.textPrimary else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(if (lib.kind == ContentKind.VOD) "Movies" else "Shows", style = t.caption, color = c.textSecondary)
                    }
                }
            }
        }
    }
}
