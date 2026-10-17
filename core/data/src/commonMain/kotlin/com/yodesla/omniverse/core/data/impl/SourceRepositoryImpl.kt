package com.yodesla.omniverse.core.data.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yodesla.omniverse.core.data.SecretBox
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SourceRepositoryImpl(
    private val db: OmniverseDb,
    private val factories: List<SourceFactory>,
    private val io: CoroutineDispatcher,
    private val newId: () -> String,
    /** Encrypts configs at rest (they contain provider passwords). */
    private val secrets: SecretBox = SecretBox.None,
) : SourceRepository {

    private fun sealConfig(config: SourceConfig): String = secrets.seal(SourceConfigCodec.encode(config))

    private val lock = Mutex()
    private val cache = HashMap<SourceId, ContentSource>()

    override fun sources(): Flow<List<SourceSummary>> =
        db.catalogQueries.allSources().asFlow().mapToList(io).map { rows ->
            rows.map {
                SourceSummary(
                    id = SourceId(it.id),
                    kind = runCatching { SourceKind.valueOf(it.kind) }.getOrDefault(SourceKind.XTREAM),
                    name = it.name,
                    lastSyncLiveMs = it.last_sync_live_ms,
                    lastSyncVodMs = it.last_sync_vod_ms,
                    lastSyncEpgMs = it.last_sync_epg_ms,
                )
            }
        }

    override suspend fun add(config: SourceConfig): SourceId = withContext(io) {
        val cfg = if (config.id.value.isBlank()) SourceConfigCodec.withId(config, SourceId(newId())) else config
        db.transaction {
            val sort = (db.storeQueries.maxSourceSort().executeAsOneOrNull()?.max ?: -1L) + 1
            db.catalogQueries.upsertSource(
                cfg.id.value, SourceConfigCodec.kindOf(cfg), cfg.displayName, sealConfig(cfg),
                sort, null, null, null, null, 0,
            )
        }
        cfg.id
    }

    override suspend fun update(config: SourceConfig) {
        withContext(io) {
            db.storeQueries.updateSourceConfig(config.displayName, sealConfig(config), config.id.value)
        }
        lock.withLock { cache.remove(config.id) }
    }

    /** Rename the saved source without changing its connection or credentials. */
    suspend fun rename(id: SourceId, displayName: String): Boolean {
        val name = displayName.trim()
        if (name.isEmpty()) return false
        val current = config(id) ?: return false
        val renamed = when (current) {
            is SourceConfig.Xtream -> SourceConfig.Xtream(id, name, current.server, current.username, current.password, current.liveFormat, current.epgUrlOverride)
            is SourceConfig.M3u -> SourceConfig.M3u(id, name, current.playlistUrl, current.epgUrl, current.userAgent)
            is SourceConfig.Plex -> SourceConfig.Plex(id, name, current.server, current.token, current.machineId, current.clientId)
        }
        update(renamed)
        return true
    }

    override suspend fun remove(id: SourceId) {
        withContext(io) { db.storeQueries.deleteSourceAndCatalog(id.value) }
        lock.withLock { cache.remove(id) }
    }

    override suspend fun config(id: SourceId): SourceConfig? = withContext(io) {
        val row = db.storeQueries.sourceById(id.value).executeAsOneOrNull() ?: return@withContext null
        val stored = row.config_json
        if (stored.startsWith(SecretBox.PREFIX)) {
            // Undecryptable (key lost, e.g. device restore): the user must sign in again.
            secrets.open(stored)?.let { SourceConfigCodec.decode(it) }
        } else {
            // Legacy plaintext from an older build: use it, and re-save it sealed right away.
            val cfg = SourceConfigCodec.decode(stored)
            if (cfg != null && secrets !== SecretBox.None) {
                db.storeQueries.updateSourceConfig(row.name, sealConfig(cfg), id.value)
            }
            cfg
        }
    }

    override suspend fun contentSource(id: SourceId): ContentSource? {
        lock.withLock { cache[id]?.let { return it } }
        val cfg = config(id) ?: return null
        val source = factories.firstNotNullOfOrNull { it.create(cfg) } ?: return null
        return lock.withLock { cache.getOrPut(id) { source } }
    }

    override suspend fun probe(config: SourceConfig): AccountInfo {
        val source = factories.firstNotNullOfOrNull { it.create(config) }
            ?: throw SourceException.Unsupported("No handler for this kind of source")
        return source.accountInfo()
    }
}
