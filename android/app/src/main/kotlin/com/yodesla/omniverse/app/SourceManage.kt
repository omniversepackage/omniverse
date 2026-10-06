package com.yodesla.omniverse.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.SourceStatusInput
import com.yodesla.omniverse.core.data.SourceStatusKeys
import com.yodesla.omniverse.core.data.SourceWarning
import com.yodesla.omniverse.core.data.SourceWarningSeverity
import com.yodesla.omniverse.core.data.decodeError
import com.yodesla.omniverse.core.data.decodeSync
import com.yodesla.omniverse.core.data.retryAction
import com.yodesla.omniverse.core.data.sourceStatus
import com.yodesla.omniverse.core.data.statusText
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.feature.onboarding.PlexServerChoice
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings > Sources, under each source: "Libraries" (switch this source's movie/show libraries
 * on or off) and, for Plex, "Plex servers" (add or remove servers on the same Plex account without
 * signing in again). Library switches are a local view preference; nothing changes on the server.
 */
@Composable
fun SourceExtras(graph: AppGraph, id: SourceId, onReenterLogin: () -> Unit = {}) {
    var panel by remember(id) { mutableStateOf<String?>(null) } // "libs" | "servers" | null
    val isPlex by produceState(false, id) { value = graph.sources.config(id) is SourceConfig.Plex }
    val libs by remember(graph, id) {
        combine(
            graph.catalog.categories(id, ContentKind.VOD, includeHidden = true).map { l -> l.map { ContentKind.VOD to it } },
            graph.catalog.categories(id, ContentKind.SERIES, includeHidden = true).map { l -> l.map { ContentKind.SERIES to it } },
        ) { a, b -> a + b }
    }.collectAsState(initial = emptyList())
    val off by remember(graph) { graph.userData.hiddenCategoryKeys() }.collectAsState(initial = emptySet())
    fun key(kind: ContentKind, cat: String) = "${kind.name}|${id.value}|$cat"
    val scope = rememberCoroutineScope()
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val counts by remember(graph, id) { graph.catalog.counts(id) }.collectAsState(initial = emptyMap())
    val errRaw by remember(graph, id) { graph.userData.setting(SourceStatusKeys.error(id)) }.collectAsState(initial = null)
    val syncRaw by remember(graph, id) { graph.userData.setting(SourceStatusKeys.sync(id)) }.collectAsState(initial = null)
    val (errKind, failedAt) = decodeError(errRaw)
    val status = sourceStatus(SourceStatusInput(counts, decodeSync(syncRaw), errKind, failedAt, isPlex))
    // Task 105: what the weekly health pass recorded about THIS source (expiry, login, count drops).
    val healthWarnings by remember(graph, id) {
        graph.sourceHealth.warnings().map { list -> list.filter { it.sourceId == id.value } }
    }.collectAsState(initial = emptyList())

    Column(Modifier.padding(top = OmniSpacing.s).widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        Text(status.statusText(graph.clock.nowMs()), style = t.caption, color = c.textSecondary)
        if (healthWarnings.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                healthWarnings.forEach { w -> SourceWarningChip(w) }
            }
        }
        when (status.retryAction()) {
            "Retry" -> OmniButton("Retry", { scope.launch { graph.syncSourceNow(id) } })
            // Expired Xtream/M3U login: the add-source form, pre-filled, saving over this same source.
            "Re-enter login" -> OmniButton("Re-enter login", onReenterLogin)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
            if (libs.isNotEmpty()) {
                val onCount = libs.count { (k, cat) -> key(k, cat.remoteId.value) !in off }
                OmniButton(if (panel == "libs") "Done" else "Libraries  ·  $onCount of ${libs.size} on",
                    { panel = if (panel == "libs") null else "libs" })
            }
            if (isPlex) OmniButton(if (panel == "servers") "Done" else "Plex servers", { panel = if (panel == "servers") null else "servers" })
        }
        if (panel == "libs") {
            Text("Switched-off libraries leave the category list and All. Nothing changes on the server.", style = t.caption, color = c.textSecondary)
            libs.forEach { (kind, cat) ->
                val on = key(kind, cat.remoteId.value) !in off
                FocusCard(
                    onClick = { scope.launch { graph.userData.setCategoryHidden(id, kind, cat.remoteId.value, hidden = on) } },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Row(Modifier.padding(horizontal = OmniSpacing.m).fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                        Text(if (on) "On" else "Off", style = t.body, color = if (on) c.accent else c.textTertiary)
                        Text(cat.name, style = t.body, color = if (on) c.textPrimary else c.textSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (kind == ContentKind.VOD) "Movies" else "Shows", style = t.caption, color = c.textSecondary)
                    }
                }
            }
        }
        if (panel == "servers") PlexServersPanel(graph)
    }
}

/**
 * Task 105: one health chip under a source's status line — "Expires in 5 days", "Login failed",
 * "Channels dropped 60%". The wording comes from the core health rules, never from the provider, and
 * a severe problem is tinted with the same red Live uses.
 */
@Composable
private fun SourceWarningChip(w: SourceWarning) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val tone = if (w.severity == SourceWarningSeverity.SEVERE) c.live else c.accent
    Text(
        w.text,
        style = t.caption,
        color = tone,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(tone.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = OmniSpacing.s, vertical = 4.dp),
    )
}

/** Every server the remembered Plex account can reach, with Add / Remove. */@Composable
private fun PlexServersPanel(graph: AppGraph) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // null = still loading; empty list + noAccount = sign-in not remembered.
    var servers by remember { mutableStateOf<List<PlexServerChoice>?>(null) }
    var noAccount by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        servers = null
        val found = runCatching { graph.plexLinker.accountServers() }.getOrNull()
        noAccount = found == null
        servers = found.orEmpty()
    }
    val added by remember(graph) {
        graph.sources.sources().map { list ->
            list.filter { it.kind == SourceKind.PLEX }.mapNotNull { s -> (graph.sources.config(s.id) as? SourceConfig.Plex)?.let { it.machineId to s.id } }.toMap()
        }
    }.collectAsState(initial = emptyMap())

    when {
        servers == null -> Text("Looking up your Plex servers…", style = t.body, color = c.textSecondary)
        noAccount -> Text("Sign in to Plex once more (+ Add a source › Plex) and Omniverse will remember it, so you can add or remove servers here any time.",
            style = t.body, color = c.textSecondary)
        servers!!.isEmpty() -> Text("No reachable servers on this Plex account right now.", style = t.body, color = c.textSecondary)
        else -> servers!!.forEach { choice ->
            val machine = (choice.config as SourceConfig.Plex).machineId
            val existing = added[machine]
            FocusCard(
                onClick = {
                    if (busy != null) return@FocusCard
                    busy = machine
                    scope.launch {
                        message = try {
                            if (existing != null) { graph.sources.remove(existing); "Removed ${choice.name}." }
                            else {
                                val newId = graph.sources.add(choice.config)
                                graph.appScope.launch { graph.syncSourceNow(newId) }
                                "Added ${choice.name}. Its libraries are loading now."
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Couldn't change ${choice.name}. Try again." }
                        busy = null
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Row(Modifier.padding(horizontal = OmniSpacing.m).fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    Text(choice.name, style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        when { busy == machine -> "Working…"; existing != null -> "Added  ·  OK to remove"; else -> "OK to add" },
                        style = t.caption, color = if (existing != null) c.accent else c.textSecondary,
                    )
                }
            }
        }
    }
    message?.let { Text(it, style = t.caption, color = c.accent) }
}
