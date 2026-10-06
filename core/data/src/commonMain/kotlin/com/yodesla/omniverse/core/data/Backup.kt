package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.database.OmniverseDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One-file backup of the viewer's personal setup (task 60), so a new TV takes one step:
 * My List, watch progress, Continue-Watching dismissals, collections and their titles, item and
 * title display overrides, libraries the viewer switched off, viewer-defined skip points and every
 * non-secret `setting` row (category order, skip modes, guide reminders and the CW dismissal log
 * all live in `setting`).
 *
 * Source credentials are not backed up. The file carries only source id, name and kind; restore
 * maps viewer rows onto the uniquely matching sources already configured on the destination TV.
 *
 * Nothing secret is ever written: [isSecretSetting] drops keys named like a token / PIN /
 * password / secret (plus the known parental and Plex-account keys) and any value the
 * [SecretBox] sealed. On import the same rule is applied again, so a hand-edited file cannot
 * smuggle credentials into the settings table.
 */
class BackupCodec(
    private val db: OmniverseDb,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock { 0L },
) {
    private val q get() = db.userOpsQueries
    private val base get() = db.userDataQueries

    /** Export the whole personal setup as a versioned JSON document. */
    suspend fun export(): String = withContext(io) {
        val settings = q.backupSettings().executeAsList()
        val kept = settings.filter { isAllowedSetting(it.key) && !isSecretSetting(it.key, it.value_) }
        val file = BackupFile(
            format = BACKUP_FORMAT,
            version = BACKUP_VERSION,
            exportedMs = clock.nowMs(),
            complete = true,
            sections = REQUIRED_SECTIONS,
            profiles = q.backupProfiles().executeAsList().map {
                BackupProfile(it.id, it.name, it.avatar.toInt(), it.is_kids != 0L, it.is_guest != 0L, it.sort, it.created_ms)
            },
            activeProfileId = base.getSetting(ProfileRepository.CURRENT_KEY).executeAsOneOrNull(),
            sources = q.backupSourceRefs().executeAsList().map { BackupSource(it.id, it.kind, it.name) },
            favorites = q.backupFavorites().executeAsList().map {
                BackupFavorite(it.profile_id, it.list_id, it.source_id, it.kind, it.remote_id, it.sort_index, it.added_ms)
            },
            progress = q.backupProgress().executeAsList().map {
                BackupProgress(it.profile_id, it.source_id, it.kind, it.remote_id, it.parent_id, it.position_ms, it.duration_ms, it.updated_ms, it.completed != 0L)
            },
            hidden = q.backupHidden().executeAsList().map { BackupHidden(it.profile_id, it.source_id, it.kind, it.remote_id) },
            itemOverrides = q.backupItemOverrides().executeAsList().map {
                BackupItemOverride(it.profile_id, it.source_id, it.kind, it.remote_id, it.display_title, it.sort_title, it.edition_label, sanitizeUrl(it.custom_poster_url))
            },
            titleOverrides = q.backupTitleOverrides().executeAsList().map {
                BackupTitleOverride(it.profile_id, it.content_kind, it.id_namespace, it.external_id, it.display_title, it.sort_title, sanitizeUrl(it.custom_poster_url))
            },
            collections = q.backupCollections().executeAsList().map {
                BackupCollection(it.id, it.profile_id, it.name, it.content_kind, it.pinned_home != 0L, it.mode, it.smart_filter_json, it.sort_order)
            },
            collectionMemberships = q.backupCollectionMemberships().executeAsList().map {
                BackupMembership(it.profile_id, it.collection_id, it.source_id, it.kind, it.remote_id, it.sort_index)
            },
            collectionTitleMemberships = q.backupCollectionTitleMemberships().executeAsList().map {
                BackupTitleMembership(it.profile_id, it.collection_id, it.content_kind, it.id_namespace, it.external_id, it.sort_index)
            },
            skipPoints = q.backupSkipPoints().executeAsList().map {
                BackupSkipPoint(it.profile_id, it.source_id, it.kind, it.remote_id, it.point_type, it.position_ms)
            },
            settings = kept.map { BackupSetting(it.key, it.value_) },
            settingsSkipped = settings.size - kept.size,
        )
        jsonFormat.encodeToString(BackupFile.serializer(), file)
    }

    /**
     * Read a backup file back in. [replace] clears the tables the file carries first (secret
     * settings rows are never cleared); otherwise rows merge, and where a table has an update
     * time the newer row wins, so restoring an old file cannot roll back a newer watch.
     * A malformed or foreign file changes nothing.
     */
    suspend fun import(json: String, replace: Boolean): ImportResult = withContext(io) {
        val parsed = try {
            jsonFormat.decodeFromString(BackupFile.serializer(), json)
        } catch (e: Exception) {
            return@withContext ImportResult(error = "That file isn't a readable backup.")
        }
        if (parsed.format != BACKUP_FORMAT) return@withContext ImportResult(error = "That file isn't an Omniverse backup.")
        if (parsed.version != BACKUP_VERSION) return@withContext ImportResult(
            error = "It came from backup format v${parsed.version}; this build reads v$BACKUP_VERSION.",
        )
        validate(parsed)?.let { return@withContext ImportResult(error = it) }
        val currentSources = q.backupSourceRefs().executeAsList()
        // L5 (audit 73): "no source matches" and "two sources match" need different advice —
        // re-add a missing source vs rename one of the duplicates. null = ambiguous, "" = missing.
        val mapping = parsed.sources.associate { old ->
            val exact = currentSources.firstOrNull { it.id == old.id }
            val matches = currentSources.filter { it.kind == old.kind && it.name == old.name }
            old.id to when {
                exact != null -> exact.id
                matches.size == 1 -> matches.single().id
                matches.size > 1 -> null
                else -> ""
            }
        }
        val ambiguous = parsed.sources.filter { mapping.getValue(it.id) == null }.map { it.name }
        if (ambiguous.isNotEmpty()) return@withContext ImportResult(
            error = "This TV has more than one source named ${ambiguous.joinToString(", ")}. " +
                "Rename one of them so the backup knows which to restore into.",
            sourceNames = parsed.sources.map { it.name },
        )
        if (mapping.values.any { it == "" }) return@withContext ImportResult(
            error = "Re-add each source shown in this backup before restoring it.",
            sourceNames = parsed.sources.map { it.name },
        )
        val sourceMap = mapping.mapValues { (_, mapped) -> mapped as String }

        var written = 0
        var skipped = 0
        var settingsSkipped = 0
        db.transaction {
            if (replace) {
                q.clearFavorites()
                q.clearProgress()
                q.clearHidden()
                q.clearItemOverrides()
                q.clearTitleOverrides()
                q.clearCollections()
                q.clearCollectionMemberships()
                q.clearCollectionTitleMemberships()
                q.clearSkipPoints()
                q.clearProfiles()
                q.backupSettings().executeAsList().filter { isAllowedSetting(it.key) }
                    .forEach { q.deleteSettingByKey(it.key) }
            }

            parsed.profiles.forEach { p ->
                q.upsertBackupProfile(p.id, p.name, p.avatar.toLong(), if (p.isKids) 1 else 0, if (p.isGuest) 1 else 0, p.sort, p.createdMs)
                written++
            }

            // Newer-wins tables: read what is already here once, then keep the winner.
            val haveProgress = if (replace) emptyMap() else q.backupProgress().executeAsList()
                .associate { RowKey(it.profile_id, it.source_id, it.kind, it.remote_id) to it.updated_ms }
            val haveFavorite = if (replace) emptyMap() else q.backupFavorites().executeAsList()
                .associate { RowKey(it.profile_id, it.list_id, it.source_id, it.kind, it.remote_id) to it.added_ms }

            parsed.favorites.forEach { row ->
                if (row.profileId.isBlank() || row.listId.isBlank() || row.sourceId.isBlank() || row.kind.isBlank() || row.remoteId.isBlank()) {
                    skipped++
                } else {
                    val mappedSource = sourceMap.getValue(row.sourceId)
                    val key = RowKey(row.profileId, row.listId, mappedSource, row.kind, row.remoteId)
                    val held = haveFavorite[key]
                    if (held != null && held >= row.addedMs) skipped++
                    else {
                        base.addFavorite(row.profileId, row.listId, mappedSource, row.kind, row.remoteId, row.sortIndex, row.addedMs)
                        written++
                    }
                }
            }

            parsed.progress.forEach { row ->
                if (row.profileId.isBlank() || row.sourceId.isBlank() || row.kind.isBlank() || row.remoteId.isBlank()) {
                    skipped++
                } else {
                    val mappedSource = sourceMap.getValue(row.sourceId)
                    val key = RowKey(row.profileId, mappedSource, row.kind, row.remoteId)
                    val held = haveProgress[key]
                    if (held != null && held >= row.updatedMs) skipped++
                    else {
                        base.upsertProgress(
                            row.profileId, mappedSource, row.kind, row.remoteId, row.parentId,
                            row.positionMs, row.durationMs, row.updatedMs, if (row.completed) 1L else 0L,
                        )
                        written++
                    }
                }
            }

            parsed.hidden.forEach { row ->
                if (row.profileId.isBlank() || row.sourceId.isBlank() || row.kind.isBlank() || row.remoteId.isBlank()) skipped++
                else {
                    q.addHidden(row.profileId, sourceMap.getValue(row.sourceId), row.kind, row.remoteId)
                    written++
                }
            }

            parsed.itemOverrides.forEach { row ->
                if (row.profileId.isBlank() || row.sourceId.isBlank() || row.kind.isBlank() || row.remoteId.isBlank()) skipped++
                else {
                    q.upsertItemOverride(row.profileId, sourceMap.getValue(row.sourceId), row.kind, row.remoteId, row.displayTitle, row.sortTitle, row.editionLabel, row.customPosterUrl)
                    written++
                }
            }

            parsed.titleOverrides.forEach { row ->
                if (row.profileId.isBlank() || row.contentKind.isBlank() || row.namespace.isBlank() || row.externalId.isBlank()) skipped++
                else {
                    q.upsertTitleOverride(row.profileId, row.contentKind, row.namespace, row.externalId, row.displayTitle, row.sortTitle, row.customPosterUrl)
                    written++
                }
            }

            parsed.collections.forEach { row ->
                if (row.id.isBlank() || row.profileId.isBlank() || row.name.isBlank() || row.contentKind.isBlank() || row.mode.isBlank()) skipped++
                else {
                    q.upsertBackupCollection(row.id, row.profileId, row.name, row.contentKind, if (row.pinnedHome) 1L else 0L, row.mode, remapSmartFilterSource(row.smartFilterJson, sourceMap), row.sortOrder)
                    written++
                }
            }

            parsed.collectionMemberships.forEach { row ->
                if (row.profileId.isBlank() || row.collectionId.isBlank() || row.sourceId.isBlank() || row.kind.isBlank() || row.remoteId.isBlank()) skipped++
                else {
                    q.addCollectionMembership(row.profileId, row.collectionId, sourceMap.getValue(row.sourceId), row.kind, row.remoteId, row.sortIndex)
                    written++
                }
            }

            parsed.collectionTitleMemberships.forEach { row ->
                if (row.profileId.isBlank() || row.collectionId.isBlank() || row.contentKind.isBlank() || row.namespace.isBlank() || row.externalId.isBlank()) skipped++
                else {
                    q.addCollectionTitleMembership(row.profileId, row.collectionId, row.contentKind, row.namespace, row.externalId, row.sortIndex)
                    written++
                }
            }

            parsed.skipPoints.forEach { row ->
                if (row.profileId.isBlank() || row.sourceId.isBlank() || row.kind.isBlank() || row.remoteId.isBlank() || row.pointType.isBlank()) skipped++
                else {
                    q.upsertLocalSkipPoint(row.profileId, sourceMap.getValue(row.sourceId), row.kind, row.remoteId, row.pointType, row.positionMs)
                    written++
                }
            }

            parsed.settings.forEach { row ->
                if (!isAllowedSetting(row.key) || isSecretSetting(row.key, row.value)) settingsSkipped++
                else {
                    base.putSetting(remapSettingKey(row.key, sourceMap), remapSettingValue(row.key, row.value, sourceMap))
                    written++
                }
            }
            parsed.activeProfileId?.takeIf { id -> parsed.profiles.any { it.id == id } }
                ?.let { base.putSetting(ProfileRepository.CURRENT_KEY, it) }

            // L10 (audit 73): Replace clears the profile table, so a null/unknown activeProfileId
            // would leave CURRENT_KEY pointing at a profile this restore removed — current() then
            // silently falls back to whichever profile the DB lists first. Never leave it dangling.
            val liveProfileIds = q.backupProfiles().executeAsList().map { it.id }
            val currentId = base.getSetting(ProfileRepository.CURRENT_KEY).executeAsOneOrNull()
            if (currentId == null || currentId !in liveProfileIds) {
                val restored = parsed.activeProfileId?.takeIf { it in liveProfileIds }
                    ?: ProfileRepository.DEFAULT_ID.takeIf { it in liveProfileIds }
                    ?: liveProfileIds.firstOrNull()
                restored?.let { base.putSetting(ProfileRepository.CURRENT_KEY, it) }
            }
        }
        ImportResult(
            ok = true,
            rowsWritten = written,
            rowsSkipped = skipped,
            settingsSkipped = settingsSkipped,
            sourceNames = parsed.sources.map { it.name },
        )
    }

    /**
     * What a file WOULD restore, without touching the database: for the confirm screen, so the
     * viewer sees "this backup is from: X, Y" and how much is in it before choosing Merge/Replace.
     * Null when the file is malformed, foreign or from an unsupported version.
     */
    fun preview(json: String): BackupPreview? {
        val parsed = try { jsonFormat.decodeFromString(BackupFile.serializer(), json) } catch (e: Exception) { return null }
        if (parsed.format != BACKUP_FORMAT || parsed.version != BACKUP_VERSION || validate(parsed) != null) return null
        return BackupPreview(
            exportedMs = parsed.exportedMs,
            sourceNames = parsed.sources.map { it.name },
            profiles = parsed.profiles.size,
            favorites = parsed.favorites.size,
            progress = parsed.progress.size,
            collections = parsed.collections.size,
            settings = parsed.settings.size,
            settingsSkipped = parsed.settingsSkipped,
        )
    }

    /**
     * True when a `setting` row must never leave the device: keys that read like a credential
     * (token / PIN / password / secret / keystore …), the known parental and Plex-account keys,
     * and any value the [SecretBox] sealed.
     */
    fun isSecretSetting(key: String, value: String): Boolean {
        val k = key.lowercase()
        return k in secretKeyNames || secretKeyParts.any { k.contains(it) } || value.startsWith(SecretBox.PREFIX)
    }

    private fun validate(file: BackupFile): String? {
        if (!file.complete || file.sections != REQUIRED_SECTIONS) return "That backup is incomplete. Nothing was changed."
        if (file.profiles.isEmpty() || file.profiles.size > 20) return "That backup has no valid profiles."
        val profileIds = file.profiles.map { it.id }
        if (profileIds.any { it.isBlank() } || profileIds.distinct().size != profileIds.size ||
            file.profiles.any { it.name.isBlank() || it.name.length > 40 || it.avatar !in 0 until ProfileRepository.AVATAR_COUNT })
            return "That backup has invalid profile data."
        val sourceIds = file.sources.map { it.id }.toSet()
        val kinds = setOf("LIVE", "VOD", "SERIES", "EPISODE", "PLEX_MEDIA")
        fun row(profile: String, source: String, kind: String, remote: String) =
            profile in profileIds && source in sourceIds && kind in kinds && remote.isNotBlank()
        if (file.favorites.any { !row(it.profileId, it.sourceId, it.kind, it.remoteId) || it.listId.isBlank() || it.addedMs < 0 } ||
            file.progress.any { !row(it.profileId, it.sourceId, it.kind, it.remoteId) || it.positionMs < 0 || (it.durationMs != null && it.durationMs <= 0) || it.updatedMs < 0 } ||
            file.hidden.any { it.profileId !in profileIds || it.sourceId !in sourceIds || it.kind.isBlank() || it.remoteId.isBlank() } ||
            file.itemOverrides.any { !row(it.profileId, it.sourceId, it.kind, it.remoteId) || sanitizeUrl(it.customPosterUrl) != it.customPosterUrl } ||
            file.titleOverrides.any { it.profileId !in profileIds || it.contentKind !in setOf("VOD", "SERIES") || it.namespace.isBlank() || it.externalId.isBlank() || sanitizeUrl(it.customPosterUrl) != it.customPosterUrl } ||
            file.skipPoints.any { !row(it.profileId, it.sourceId, it.kind, it.remoteId) || it.pointType.isBlank() || it.positionMs < 0 })
            return "That backup contains invalid viewer data. Nothing was changed."
        val collectionIds = file.collections.map { it.id }.toSet()
        if (file.collections.any { it.id.isBlank() || it.profileId !in profileIds || it.name.isBlank() || it.contentKind !in setOf("VOD", "SERIES") || it.mode !in setOf("manual", "smart") } ||
            file.collectionMemberships.any { it.collectionId !in collectionIds || !row(it.profileId, it.sourceId, it.kind, it.remoteId) } ||
            file.collectionTitleMemberships.any { it.collectionId !in collectionIds || it.profileId !in profileIds || it.contentKind !in setOf("VOD", "SERIES") || it.namespace.isBlank() || it.externalId.isBlank() } ||
            file.settings.any { !isAllowedSetting(it.key) || isSecretSetting(it.key, it.value) || !settingBelongsToKnownProfile(it.key, profileIds) })
            return "That backup contains invalid collections or settings. Nothing was changed."
        fun <T> duplicates(values: List<T>) = values.size != values.distinct().size
        if (duplicates(file.favorites.map { listOf(it.profileId, it.listId, it.sourceId, it.kind, it.remoteId) }) ||
            duplicates(file.progress.map { listOf(it.profileId, it.sourceId, it.kind, it.remoteId) }))
            return "That backup contains duplicate rows. Nothing was changed."
        return null
    }

    private fun isAllowedSetting(key: String): Boolean = key in safeSettingNames || safeSettingPrefixes.any(key::startsWith)

    private fun settingBelongsToKnownProfile(key: String, profileIds: List<String>): Boolean = when {
        key.startsWith("search_history::") || key.startsWith("category_order_") ||
            key.startsWith("home_layout::") || key.startsWith("home_density::") ||
            key.startsWith("sports_followed_teams::") || key.startsWith("sports_hide_scores::") -> key.substringAfterLast("::", "") in profileIds
        key.startsWith("cw_dismissed_") -> key.removePrefix("cw_dismissed_") in profileIds
        key.startsWith("kids_") -> key.substringAfterLast("::", "") in profileIds
        else -> true
    }

    private fun sanitizeUrl(value: String?): String? {
        val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = url.lowercase()
        val authority = lower.substringAfter("://", "").substringBefore('/')
        return url.takeUnless {
            '@' in authority || sensitiveUrlParts.any { part -> lower.contains(part) }
        }
    }

    /**
     * A `picture_mode_<sourceId>_<itemId>` key carries a source id inside it, so it follows the same
     * source mapping as the rows in every other table. Keys with no source part are left alone.
     */
    private fun remapSettingKey(key: String, sourceMap: Map<String, String>): String {
        if (!key.startsWith("picture_mode_")) return key
        val tail = key.removePrefix("picture_mode_")
        if ('_' !in tail) return key
        val source = tail.substringBefore('_')
        val mapped = sourceMap[source] ?: return key
        return "picture_mode_${mapped}_${tail.substringAfter('_')}"
    }

    private fun remapSettingValue(key: String, value: String, sourceMap: Map<String, String>): String = when {
        key.startsWith("category_order_") || key.startsWith("cw_dismissed_") -> value.lines().joinToString("\n") { line ->
            val source = line.substringBefore('|')
            sourceMap[source]?.let { mapped -> mapped + line.removePrefix(source) } ?: line
        }
        else -> value
    }

    /**
     * M3 (audit 73): a smart collection pins a source inside smart_filter_json, which the row-level
     * source remap never reached — the pin kept pointing at the exporting device's source id. Run
     * the pin through the same remap; a pin that maps to nothing on this TV is dropped (the
     * collection still restores, just unfiltered) instead of silently filtering on a stale id.
     */
    private fun remapSmartFilterSource(json: String?, sourceMap: Map<String, String>): String? {
        val filter = json?.let { SmartCollectionFilter.decode(it) } ?: return json
        val pinned = filter.sourceId ?: return json
        return when (val mapped = sourceMap[pinned]) {
            null -> filter.copy(sourceId = null, categoryId = null).encode()
            pinned -> json
            else -> filter.copy(sourceId = mapped).encode()
        }
    }

    private data class RowKey(val a: String, val b: String, val c: String, val d: String, val e: String = "")

    companion object {
        const val BACKUP_FORMAT = "omniverse-backup"
        const val BACKUP_VERSION = 2

        private val secretKeyParts = listOf("token", "pin", "password", "secret", "credential", "keystore", "apikey", "api_key", "private_key")
        private val secretKeyNames = setOf("plex_account_token_sealed", "parental_pin", "parental_locked", "parental_attempts")
        private val safeSettingNames = setOf(
            ProfileRepository.PICKER_KEY, "experience_mode", "guide_reminders", "skip_intro_mode",
            "skip_credits_mode", "skip_community_auto", "skip_local_analysis", "metadata_wikidata",
            "rt_scores", "sports_online", "live_view", "pref_audio_language", "pref_subtitles",
            // L1 made these profile-scoped; the bare names stay listed so a backup written by an
            // older build still imports (the next launch migrates them to the default profile).
            "home_layout", "home_density",
        )
        private val safeSettingPrefixes = listOf(
            "category_order_", "search_history::", "cw_dismissed_", "picture_mode_",
            "home_layout::", "home_density::", "kids_",
            // Task 103: followed teams and the score switch travel with the profile that owns them.
            "sports_followed_teams::", "sports_hide_scores::",
        )
        private val sensitiveUrlParts = listOf("x-plex-token=", "token=", "auth=", "password=", "apikey=", "api_key=")
        private val REQUIRED_SECTIONS = setOf(
            "profiles", "sources", "favorites", "progress", "hidden", "itemOverrides", "titleOverrides",
            "collections", "collectionMemberships", "collectionTitleMemberships", "skipPoints", "settings",
        )

        internal val jsonFormat = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Summary of a backup file, for the restore confirmation screen. */
data class BackupPreview(
    val exportedMs: Long,
    val sourceNames: List<String>,
    val profiles: Int,
    val favorites: Int,
    val progress: Int,
    val collections: Int,
    val settings: Int,
    val settingsSkipped: Int,
)

/** Result of a restore: [error] non-null means nothing was changed. */
data class ImportResult(
    val ok: Boolean = false,
    val error: String? = null,
    val rowsWritten: Int = 0,
    val rowsSkipped: Int = 0,
    val settingsSkipped: Int = 0,
    val sourceNames: List<String> = emptyList(),
)

@Serializable
data class BackupFile(
    val format: String,
    val version: Int,
    val complete: Boolean,
    val sections: Set<String>,
    val exportedMs: Long = 0,
    val profiles: List<BackupProfile>,
    val activeProfileId: String? = null,
    val sources: List<BackupSource> = emptyList(),
    val favorites: List<BackupFavorite> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    val hidden: List<BackupHidden> = emptyList(),
    val itemOverrides: List<BackupItemOverride> = emptyList(),
    val titleOverrides: List<BackupTitleOverride> = emptyList(),
    val collections: List<BackupCollection> = emptyList(),
    val collectionMemberships: List<BackupMembership> = emptyList(),
    val collectionTitleMemberships: List<BackupTitleMembership> = emptyList(),
    val skipPoints: List<BackupSkipPoint> = emptyList(),
    val settings: List<BackupSetting> = emptyList(),
    val settingsSkipped: Int = 0,
)

@Serializable
data class BackupProfile(
    val id: String,
    val name: String,
    val avatar: Int,
    val isKids: Boolean,
    /** Task 84b: the "no profile" Guest state travels so a restore reproduces it. */
    val isGuest: Boolean = false,
    val sort: Long,
    val createdMs: Long,
)

/** A source the backup was taken against. Name + kind only — never its config. */
@Serializable
data class BackupSource(val id: String, val kind: String, val name: String)

@Serializable
data class BackupFavorite(
    val profileId: String,
    val listId: String,
    val sourceId: String,
    val kind: String,
    val remoteId: String,
    val sortIndex: Long,
    val addedMs: Long,
)

@Serializable
data class BackupProgress(
    val profileId: String,
    val sourceId: String,
    val kind: String,
    val remoteId: String,
    val parentId: String? = null,
    val positionMs: Long,
    val durationMs: Long? = null,
    val updatedMs: Long,
    val completed: Boolean = false,
)

@Serializable
data class BackupHidden(val profileId: String, val sourceId: String, val kind: String, val remoteId: String)

@Serializable
data class BackupItemOverride(
    val profileId: String,
    val sourceId: String,
    val kind: String,
    val remoteId: String,
    val displayTitle: String? = null,
    val sortTitle: String? = null,
    val editionLabel: String? = null,
    val customPosterUrl: String? = null,
)

@Serializable
data class BackupTitleOverride(
    val profileId: String,
    val contentKind: String,
    val namespace: String,
    val externalId: String,
    val displayTitle: String? = null,
    val sortTitle: String? = null,
    val customPosterUrl: String? = null,
)

@Serializable
data class BackupCollection(
    val id: String,
    val profileId: String,
    val name: String,
    val contentKind: String,
    val pinnedHome: Boolean = false,
    val mode: String = "manual",
    val smartFilterJson: String? = null,
    val sortOrder: Long = 0,
)

@Serializable
data class BackupMembership(
    val profileId: String,
    val collectionId: String,
    val sourceId: String,
    val kind: String,
    val remoteId: String,
    val sortIndex: Long,
)

@Serializable
data class BackupTitleMembership(
    val profileId: String,
    val collectionId: String,
    val contentKind: String,
    val namespace: String,
    val externalId: String,
    val sortIndex: Long,
)

@Serializable
data class BackupSkipPoint(
    val profileId: String,
    val sourceId: String,
    val kind: String,
    val remoteId: String,
    val pointType: String,
    val positionMs: Long,
)

@Serializable
data class BackupSetting(val key: String, val value: String)
