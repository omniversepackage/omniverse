package com.yodesla.omniverse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.yodesla.omniverse.android.parental.LockableCategory
import com.yodesla.omniverse.android.parental.ParentalSettingsSection
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Settings > Parental controls: every LIVE/VOD/SERIES category of every source, hidden ones included. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ParentalSection(graph: AppGraph) {
    val categories by remember(graph) {
        graph.sources.sources().flatMapLatest { sources ->
            val flows = sources.flatMap { src ->
                listOf(ContentKind.LIVE, ContentKind.VOD, ContentKind.SERIES).map { kind ->
                    graph.catalog.categories(src.id, kind, includeHidden = true)
                }
            }
            if (flows.isEmpty()) flowOf(emptyList())
            else combine(flows) { lists ->
                lists.flatMap { it.map { c -> LockableCategory(c.kind.name, c.sourceId.value, c.remoteId.value, c.name) } }
            }
        }
    }.collectAsState(initial = emptyList())
    ParentalSettingsSection(graph.parental, categories)
}
