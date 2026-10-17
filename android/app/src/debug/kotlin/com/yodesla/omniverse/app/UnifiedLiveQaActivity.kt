package com.yodesla.omniverse.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.source.SourceConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Emulator-only fixtures. Never included in a release APK. */
class UnifiedLiveQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.HARDWARE !in setOf("ranchu", "goldfish")) { finish(); return }
        lifecycleScope.launch {
            val graph = (application as OmniverseApp).graph
            val first = graph.sources.sources().first().firstOrNull { it.id.value != "qa-live-backup" } ?: return@launch finish()
            val cfg = graph.sources.config(first.id) as? SourceConfig.Xtream ?: return@launch finish()
            if (!cfg.server.contains(":8765")) return@launch finish()
            if (intent.getBooleanExtra("discovery", false)) seedDiscovery(graph, first.id)
            graph.sources.add(SourceConfig.Xtream(SourceId("qa-live-backup"), "QA Backup", cfg.server, cfg.username, cfg.password, cfg.liveFormat, cfg.epgUrlOverride))
            startActivity(Intent(this@UnifiedLiveQaActivity, MainActivity::class.java))
            finish()
        }
    }
    private suspend fun seedDiscovery(graph: AppGraph, source: SourceId) {
        // Synthetic charts exist only in this emulator-only, debug-only fixture.
        for (key in listOf("auto_refresh_sources", "auto_refresh_discovery")) graph.userData.putSetting(key, "off|6|180")
        graph.userData.putSetting("movies_shows_view", "horizontal")
        graph.userData.putSetting("metadata_wikidata", "false")
        val q = graph.db.storeQueries
        for (kind in listOf("VOD", "SERIES")) q.upsertCategory(source.value, kind, "qa-netflix", "Netflix", null, 0, 1)
        for (i in 1..12) {
            q.upsertVod(source.value, "qa-m$i", "QA Movie $i", null, "qa-netflix", 7.5, 2026, 1000, "mp4", "90000$i", i.toLong(), 1, null, null)
            q.upsertSeries(source.value, "qa-s$i", "QA Show $i", null, null, "qa-netflix", null, null, 7.5, 2026, 1000, i.toLong(), 1, null, "90000$i")
        }
        suspend fun feed(key: String, ids: List<Int>, rank: Boolean = false, types: List<String> = listOf("movie", "tv")) {
            val titles = ids.flatMap { i -> types.map { kind ->
                "{\"id\":\"90000$i\",\"kind\":\"$kind\",\"date\":\"2026-10-11\",\"rank\":${if (rank) (if (i == 2) 3 else 7) else 0}}"
            } }
            graph.userData.putSetting("discovery_cache_$key", "{\"at\":1,\"titles\":[${titles.joinToString(",")}]}")
        }
        feed("day", listOf(1, 2, 3)); feed("week", listOf(1, 4, 5, 6)); feed("recent", listOf(2, 7, 8, 9, 10))
        feed("netflix_official_movie", listOf(2, 5), true, listOf("movie"))
        feed("netflix_official_tv", listOf(2, 5), true, listOf("tv"))
    }
}
