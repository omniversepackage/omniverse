package com.yodesla.omniverse.app

import android.content.Context
import android.util.Log
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.yodesla.omniverse.core.brand.BrandConfig
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.KidsTimeController
import com.yodesla.omniverse.core.data.KidsUsageStore
import com.yodesla.omniverse.core.data.NewEpisodesDetector
import com.yodesla.omniverse.core.data.ProfileRepository
import com.yodesla.omniverse.core.data.SyncEngine
import com.yodesla.omniverse.core.data.SyncProgress
import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.data.SyncStage
import com.yodesla.omniverse.core.data.SourceHealthProbe
import com.yodesla.omniverse.core.data.SourceHealthStore
import com.yodesla.omniverse.core.data.SourceWarning
import com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl
import com.yodesla.omniverse.core.data.impl.EpgRepositoryImpl
import com.yodesla.omniverse.core.data.impl.ProfileRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SourceRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SyncEngineImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.net.OkHttpHttpClient
import com.yodesla.omniverse.feature.vod.CommunitySkipLookup
import com.yodesla.omniverse.core.update.UpdateChecker
import com.yodesla.omniverse.android.update.ApkInstaller
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.m3u.M3uSourceFactory
import com.yodesla.omniverse.core.source.xtream.XtreamSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manual dependency graph (no DI framework: faster cold start, nothing reflective).
 * One per process, created lazily by OmniverseApp.
 */
class AppGraph(context: Context, val brand: BrandConfig) {
    val clock = Clock { System.currentTimeMillis() }
    private val io = Dispatchers.IO

    /** Background work that must outlive screens (sync). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val userAgent = "${brand.appName.replace(' ', '-')}/${BuildConfig.VERSION_NAME} (Android TV)"

    /** Public/distribution build (Task 75): brand.json's publicBuild or the -PpublicBuild=true
     *  build flag. Drives text service marks and the Rotten Tomatoes default. */
    val publicBuild: Boolean = brand.publicBuild || BuildConfig.PUBLIC_BUILD

    /**
     * Task 101: opening the database — schema check plus every pending migration — is the most
     * expensive thing in a cold start, so it no longer happens on the main thread. [warmDb] opens it
     * on [appScope] the moment the graph is built (which [init] starts immediately), and a screen that
     * asks for [db] before that finishes simply joins the open instead of paying for it.
     */
    private val dbLazy = lazy {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val opened = OmniverseDb(
            AndroidSqliteDriver(
                schema = OmniverseDb.Schema,
                context = context,
                name = "omniverse.db",
                callback = object : AndroidSqliteDriver.Callback(OmniverseDb.Schema) {
                    override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // WAL: screens keep reading smoothly while sync writes; far faster bulk inserts.
                        db.enableWriteAheadLogging()
                        db.execSQL("PRAGMA synchronous = NORMAL")
                    }
                },
            ),
        )
        StartupTrace.mark("database open + migrations took ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        opened
    }
    val db: OmniverseDb get() = dbLazy.value
    private val http = OkHttpHttpClient(OkHttpHttpClient.defaultOkHttp(), userAgent)
    val communitySkipLookup: CommunitySkipLookup = TheIntroDbSkipLookup(http)

    /** Null when the brand has no update manifest (updates disabled, prompt never shows). */
    val updateChecker: UpdateChecker? = brand.updateManifestUrl?.takeIf { it.isNotBlank() }?.let {
        UpdateChecker(http, it, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT)
    }
    val apkInstaller = ApkInstaller(context.applicationContext, http)

    private val secretBox = KeystoreSecretBox()

    /**
     * Task 101: everything below that touches [db] is `by lazy`. Building the graph on the main
     * thread must stay cheap — a repository constructor that opens a flow would open the database,
     * and that is exactly the startup cost [dbLazy] moved off the main thread. The first screen that
     * actually reads one of these joins the background open instead.
     */
    val sources: SourceRepositoryImpl by lazy {
        SourceRepositoryImpl(
            db, listOf(XtreamSourceFactory(http), M3uSourceFactory(http), com.yodesla.omniverse.core.source.plex.PlexSourceFactory(http)), io,
            newId = { UUID.randomUUID().toString() },
            secrets = secretBox,
        )
    }
    val catalog: CatalogRepositoryImpl by lazy { CatalogRepositoryImpl(db, io, tmdbArt = com.yodesla.omniverse.core.data.metadata.TmdbArt(db)) }
    val epg: EpgRepositoryImpl by lazy { EpgRepositoryImpl(db, io) }
    val search: SearchRepositoryImpl by lazy { SearchRepositoryImpl(db, io) }
    /** Local profiles (task 59). "default" owns all pre-profiles data; seeded here for fresh installs. */
    val profiles: ProfileRepository by lazy {
        ProfileRepositoryImpl(db, io, clock, newId = { UUID.randomUUID().toString() })
    }
    /** Active profile id, kept hot so UserDataRepositoryImpl's profileId() lambda never suspends. */
    @Volatile var currentProfileCache = ProfileRepository.DEFAULT_ID
        private set
    private val activeProfileIds: Flow<String> by lazy { profiles.current().map { it.id }.distinctUntilChanged() }
    val userData: UserDataRepositoryImpl by lazy {
        UserDataRepositoryImpl(db, io, clock, profileId = { currentProfileCache }, profileIds = activeProfileIds)
    }

    /**
     * Task 106: the Kids daily allowance and bedtime clock. Limits and usage are profile-scoped
     * settings, so the active profile decides them; the device offset decides where local midnight
     * and bedtime fall.
     */
    val kidsUsage: KidsUsageStore by lazy {
        KidsUsageStore(
            userData,
            clock,
            offsetMinutes = { java.util.TimeZone.getDefault().getOffset(clock.nowMs()) / 60_000 },
            elapsedMs = { android.os.SystemClock.elapsedRealtime() },
        )
    }
    val kidsTime: KidsTimeController by lazy { KidsTimeController(kidsUsage) }

    /** Task 101: the DB open (schema + migrations) plus the profile seeding, on a background thread,
     *  started the instant the graph is built — before the first activity can ask for a screen. */
    private fun warmDb() {
        db
        StartupTrace.mark("database warm-up done")
    }

    init {
        StartupTrace.mark("AppGraph built (database not opened yet)")
        // Task 101: the database open runs here, on a background thread, while the activity is still
        // being created — the first frame is composed against a DB that is already opening.
        appScope.launch {
            warmDb()
            profiles.ensureDefault()
            userData.migrateLegacyProfileSettings()
            // A sync stage left behind by a crash is not a live sync (L4).
            userData.clearStaleSyncStages()
            currentProfileCache = profiles.currentProfile().id
            StartupTrace.mark("profiles ready")
        }
        appScope.launch { profiles.current().collect { currentProfileCache = it.id } }
    }

    /** Switch the active profile; the shell restarts the activity right after. */
    suspend fun switchProfileTo(id: String) {
        profiles.setCurrent(id)
        currentProfileCache = profiles.currentProfile().id
    }

    /** App-wide Multiview session (Task 67): the 1-4 channels queued from the Guide/Live lists. It
     *  lives here (one per process) so it survives navigation — Back from Multiview keeps it. */
    val multiviewSession = com.yodesla.omniverse.feature.live.multiview.MultiviewSession()
    /** Saved Multiview groups (settings key `multiview_groups`), shared by the Live picker and the
     *  Multiview tile menu's "Save as group". */
    val multiviewGroups: com.yodesla.omniverse.feature.live.multiview.MultiviewGroupStore by lazy {
        com.yodesla.omniverse.feature.live.multiview.MultiviewGroupStore(userData)
    }
    /** Opt-in (Settings, default OFF) Wikidata/Wikipedia gap-filler for exact TMDB ids. Posters stay off:
     *  Wikipedia film posters are usually non-free images, not safe to redistribute to friends' builds. */
    /** Rotten Tomatoes critics/audience scores for the Movies/Shows spotlight. */
    val rottenTomatoes = com.yodesla.omniverse.core.data.metadata.RottenTomatoes(
        http,
        enabled = { com.yodesla.omniverse.core.data.metadata.RottenTomatoes.enabledFor(
            userData.setting(com.yodesla.omniverse.core.data.metadata.RottenTomatoes.SETTING_KEY).first(), publicBuild) },
    )
    val metadataEnricher = com.yodesla.omniverse.core.data.metadata.MetadataEnricher(
        lookup = com.yodesla.omniverse.core.data.metadata.WikidataMetadata(http, allowPosters = false)::lookup,
        enabled = { userData.setting(com.yodesla.omniverse.core.data.metadata.MetadataEnricher.SETTING_KEY).first() == "true" },
    )
    /** TMDB artwork/details (task 84d): backdrops, title logos, episode stills, US certification.
     *  No API key in the build (BuildConfig.TMDB_API_KEY empty) or the Metadata switch off = zero
     *  requests; only the public TMDB id is ever sent. Cached art keeps showing from the local DB. */
    val tmdbEnricher: com.yodesla.omniverse.core.data.metadata.TmdbEnricher by lazy {
        com.yodesla.omniverse.core.data.metadata.TmdbEnricher(
            db, clock,
            BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() }?.let { com.yodesla.omniverse.core.data.metadata.Tmdb(http, it, io = io) },
            enabled = { userData.setting(com.yodesla.omniverse.core.data.metadata.MetadataEnricher.SETTING_KEY).first() == "true" },
            io = io,
        )
    }
    val parental: com.yodesla.omniverse.android.parental.ParentalControls by lazy {
        com.yodesla.omniverse.android.parental.ParentalControls(userData)
    }
    /** Live Sports: public schedule/scores/logos/clips (league + date requests only). */
    val sportsFeed = com.yodesla.omniverse.core.data.sports.EspnSportsFeed(http)
    /** Task 103: the teams this profile follows, and where its own guides carry their games. */
    val followedTeams by lazy { com.yodesla.omniverse.core.data.sports.FollowedTeamsStore(userData) }
    val myTeams by lazy { com.yodesla.omniverse.core.data.sports.MyTeamsRepository(epg, clock, followedTeams) }
    /** A Kids profile applies the parental locks as a hard filter: the PIN session unlock does not
     *  apply there, so locked categories stay hidden with no unlock offered. Always true for adults. */
    private val kidsVisibility: kotlinx.coroutines.flow.Flow<com.yodesla.omniverse.core.data.Visibility> by lazy {
        kotlinx.coroutines.flow.combine(parental.lockedKeys(), profiles.current().map { it.isKids }) { locked, isKids ->
            kidsHardFilter(isKids, locked)
        }
    }
    /** Task 84h: the profile's category-language filter (Settings > Sources). Empty set = off ("All"). */
    private val categoryLanguageFilter: Flow<Set<com.yodesla.omniverse.core.data.categories.CategoryLanguage>> by lazy {
        userData.setting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_FILTER_KEY)
            .map { com.yodesla.omniverse.core.data.categories.parseCategoryLanguageFilter(it) }
    }
    private val categoryLanguageKeepUntagged: Flow<Boolean> by lazy {
        userData.setting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_KEEP_UNTAGGED_KEY).map { it != "false" }
    }
    private val categoryLanguageFilterVod: Flow<Boolean> by lazy {
        userData.setting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_FILTER_VOD_KEY).map { it == "true" }
    }

    /** Every category name this profile's sources hold, as (KIND, sourceId, categoryId, name). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val allCategoryNames: Flow<List<LangCat>> by lazy {
        sources.sources().flatMapLatest { srcs ->
            if (srcs.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else srcs.flatMap { s ->
                listOf(
                    com.yodesla.omniverse.core.model.ContentKind.LIVE,
                    com.yodesla.omniverse.core.model.ContentKind.VOD,
                    com.yodesla.omniverse.core.model.ContentKind.SERIES,
                ).map { kind ->
                    catalog.categories(s.id, kind, includeHidden = true).map { cats ->
                        cats.map { LangCat(kind.name, s.id.value, it.remoteId.value, it.name) }
                    }
                }
            }.reduce { acc, f -> acc.combine(f) { a, b -> a + b } }
        }
    }

    /**
     * Task 84h: "KIND|sourceId|categoryId" keys of the categories the language filter hides right now.
     * LIVE keys are in whenever the filter is on; VOD/SERIES keys only with the "Also filter Movies &
     * Shows" switch. Recomputed whenever a source's categories or the filter settings change.
     */
    val categoryLanguageDeny: Flow<Set<String>> by lazy {
        kotlinx.coroutines.flow.combine(allCategoryNames, categoryLanguageFilter, categoryLanguageKeepUntagged, categoryLanguageFilterVod) { cats, allowed, keepUntagged, filterVod ->
            if (allowed.isEmpty()) emptySet() else buildSet {
                for (c in cats) {
                    if (c.kind != "LIVE" && !filterVod) continue
                    if (!com.yodesla.omniverse.core.data.categories.categoryLanguageAllowed(
                            com.yodesla.omniverse.core.data.categories.categoryLanguage(c.name), allowed, keepUntagged,
                        )
                    ) add("${c.kind}|${c.sourceId}|${c.categoryId}")
                }
            }
        }
    }

    /** The Live chip's content ("English only · 42 hidden"), null while the filter is off. */
    val categoryLanguageSummary: Flow<com.yodesla.omniverse.core.data.categories.CategoryLanguageSummary?> by lazy {
        kotlinx.coroutines.flow.combine(categoryLanguageFilter, categoryLanguageDeny) { allowed, deny ->
            if (allowed.isEmpty()) null else com.yodesla.omniverse.core.data.categories.CategoryLanguageSummary(
                com.yodesla.omniverse.core.data.categories.categoryLanguageFilterLabel(allowed),
                deny.count { it.startsWith("LIVE|") },
            )
        }
    }

    /** Parental locks plus libraries the viewer switched off (Settings > Libraries). For browse-style
     *  surfaces (Home, Search); parental-only checks keep using [parental] directly. Task 84h adds the
     *  category-language filter here too, so filtered categories vanish from Home, Search and browse. */
    val browseVisibility: kotlinx.coroutines.flow.Flow<com.yodesla.omniverse.core.data.Visibility> by lazy {
        kotlinx.coroutines.flow.combine(parental.visibility, userData.hiddenCategoryKeys(), kidsVisibility, categoryLanguageDeny) { allowed, off, kids, langOff ->
            { kind: String, sourceId: String, categoryId: String -> allowed(kind, sourceId, categoryId) && "$kind|$sourceId|$categoryId" !in off && "$kind|$sourceId|$categoryId" !in langOff && kids(kind, sourceId, categoryId) }
        }
    }
    /**
     * The ONE content-visibility policy (audit 73 M1+M2): parental PIN AND the Kids hard filter,
     * plus the category-language filter (task 84h — LIVE keys always, VOD/SERIES keys only when the
     * "Also filter Movies & Shows" switch is on). Route gating, Movies/Shows and the Live / Guide /
     * Multiview surfaces all share this object — a Kids profile never sees locked categories
     * anywhere, PIN session or not, and no surface can disagree with the gate that keeps its route open.
     */
    val vodVisibility: kotlinx.coroutines.flow.Flow<com.yodesla.omniverse.core.data.Visibility> by lazy {
        kotlinx.coroutines.flow.combine(parental.visibility, kidsVisibility, categoryLanguageDeny) { allowed, kids, langOff ->
            { kind: String, sourceId: String, categoryId: String -> allowed(kind, sourceId, categoryId) && kids(kind, sourceId, categoryId) && "$kind|$sourceId|$categoryId" !in langOff }
        }
    }
    /** Task 84h: Live's Favorites / Recently watched obey parental + Kids but NEVER the language
     *  filter — a favourite always stays reachable. */
    val favoritesVisibility: kotlinx.coroutines.flow.Flow<com.yodesla.omniverse.core.data.Visibility> by lazy {
        kotlinx.coroutines.flow.combine(parental.visibility, kidsVisibility) { allowed, kids ->
            { kind: String, sourceId: String, categoryId: String -> allowed(kind, sourceId, categoryId) && kids(kind, sourceId, categoryId) }
        }
    }
    /** SQL-side category exclusions for Movies/Shows: locked categories even during a PIN session in Kids mode. */
    val vodExcludedCategoryKeys: kotlinx.coroutines.flow.Flow<Set<String>> by lazy {
        kotlinx.coroutines.flow.combine(parental.excludedCategoryKeys, parental.lockedKeys(), profiles.current().map { it.isKids }, categoryLanguageDeny) { off, locked, isKids, langOff ->
            (if (isKids) off + locked else off) + langOff.filterNot { it.startsWith("LIVE|") }
        }
    }
    val plexLinker: AppPlexLinker by lazy { AppPlexLinker(http, userData, secrets = secretBox) }
    /** Task 91: app-level Now Playing holder. The active player publishes a snapshot here (at most
     *  every 1 s; null when nothing plays) and registers its control hooks while it plays. */
    val nowPlaying = MutableStateFlow<com.yodesla.omniverse.core.model.NowPlaying?>(null)
    val nowPlayingControls = MutableStateFlow<com.yodesla.omniverse.core.model.NowPlayingControls?>(null)
    /** Phone remote (task 76): LAN search + play + D-pad control from a phone. Lives here so the
     *  server survives navigation; runs only while its toggle is On or the Settings screen is open.
     *  Task 91: also serves GET /now from the holder above. */
    val phoneRemote: com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteController by lazy {
        com.yodesla.omniverse.feature.home.phoneremote.PhoneRemoteController(
            search, catalog, browseVisibility, userData, clock, appScope, nowPlaying = nowPlaying,
        )
    }
    private val syncEngine: SyncEngineImpl by lazy { SyncEngineImpl(db, sources, clock, io = io) }
    private val newEpisodes: NewEpisodesDetector by lazy { NewEpisodesDetector(sources, userData, clock) }

    /**
     * Task 105: the recorded source-health facts. Settings' warning chips and the Home banner both read
     * this store, so what the viewer is told and what the weekly check found can never drift apart.
     */
    val sourceHealth: SourceHealthStore by lazy { SourceHealthStore(userData, clock) }

    /** Task 105: one weekly pass over every source — its own account state, its last good sync, and
     *  today's item counts against the counts from the pass before. */
    val sourceHealthProbe: SourceHealthProbe by lazy {
        SourceHealthProbe(
            store = sourceHealth,
            clock = clock,
            sources = { sources.sources().first().map { it.id to it.name } },
            contentSource = { id -> sources.contentSource(id) },
            counts = { id -> catalog.counts(id).first() },
            lastSuccessSyncMs = { id ->
                sources.sources().first().firstOrNull { it.id == id }
                    ?.let { listOfNotNull(it.lastSyncLiveMs, it.lastSyncVodMs, it.lastSyncEpgMs).maxOrNull() }
            },
        )
    }

    /** Set while a pass is running, so the worker and the startup check never overlap. */
    private val healthRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Task 105: run the health pass (or report the last one's warnings when it is not due yet).
     * Provider failures are recorded per source, never thrown: one dead source cannot stop the others.
     */
    suspend fun checkSourceHealth(force: Boolean = false): List<SourceWarning> {
        if (!force && !sourceHealth.isDue()) return sourceHealth.warnings().first()
        if (!healthRunning.compareAndSet(false, true)) return sourceHealth.warnings().first()
        return try {
            sourceHealthProbe.checkAll()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "source health check failed: ${e.message}")
            AppLog.log("source health check failed: ${e.message}")
            sourceHealth.warnings().first()
        } finally {
            healthRunning.set(false)
        }
    }

    /**
     * Task 105: the startup health check. It runs on the same throttled startup scope as the startup
     * sync and waits for the same gate, so a weekly check that fell behind never competes with Home's
     * first frames — and it only runs at all when the last check is older than a week.
     */
    fun checkSourceHealthAtStartup() {
        startupSyncScope.launch {
            val wait = startupGate.syncWaitMs()
            if (wait > 0) delay(wait)
            if (sourceHealth.isDue()) checkSourceHealth()
        }
    }
    /**
     * The app's only sync entry point (audit M8). Every completed run — background worker, Settings
     * refresh, onboarding, "add a Plex server" — also imports that source's own in-progress items,
     * so a catalog can never appear without the Continue Watching state Plex already holds, and
     * then re-checks the shows this profile watches for episodes newer than its snapshot
     * (task 71 "New episodes"). The progress import runs first, so a watch made on the provider is
     * never flagged as unseen.
     */
    val sync: SyncEngine by lazy {
        RemoteProgressSyncEngine(
            RemoteProgressSyncEngine(syncEngine, ::importRemoteProgress) { id, e ->
                Log.w(TAG, "remote progress import for ${id.value}: ${e.message}")
                AppLog.log("remote progress import for ${id.value} failed: ${e.message}")
            },
            { id -> newEpisodes.refresh(id) },
        ) { id, e ->
            Log.w(TAG, "new episodes refresh for ${id.value}: ${e.message}")
            AppLog.log("new episodes refresh for ${id.value} failed: ${e.message}")
        }
    }

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    /** Latest sync event, for a subtle "Updating channels…" indicator. */
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress

    /**
     * Task 101: Home rows first, startup sync after. The gate opens when the first frame is drawn and
     * Home's cached rows land — or [StartupSyncGate.DEFAULT_MAX_DELAY_MS] after that frame, so a Home
     * with nothing cached still gets its catalog refreshed.
     */
    private val startupGate = StartupSyncGate({ android.os.SystemClock.elapsedRealtime() })

    /**
     * Task 101: the startup sync runs one-at-a-time on a single IO thread. A sync is a long burst of
     * bulk inserts; holding it to one thread leaves the rest of the disk and CPU free for the reads
     * Home and the nav rail make while the first frames are coming up.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val startupSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Task 101: the first Compose frame landed (called once from the shell). */
    fun firstFrameDrawn() {
        StartupTrace.firstFrame()
        startupGate.firstFrameDrawn()
    }

    /** Task 101: Home has cached rows on screen, so the startup sync may start now. */
    fun homeRowsShown(rows: Int, cards: Int) {
        val first = startupGate.homeRowsVisibleAtMs == null
        startupGate.homeRowsVisible()
        if (first) StartupTrace.mark("Home rows visible: $rows rows / $cards cards")
    }

    /**
     * Syncs every source whose data is stale. Safe to call often: fresh stages are skipped.
     * Task 101: on a cold start this waits for Home's cached rows (at most 2 s after the first frame)
     * and then runs on the throttled startup scope; every later call runs straight away.
     */
    fun syncIfStale() {
        startupSyncScope.launch {
            val wait = startupGate.syncWaitMs()
            if (wait > 0) {
                StartupTrace.mark("startup sync waiting $wait ms for Home rows")
                delay(wait)
            }
            syncAllStale()
        }
    }

    /** Suspending form for the background worker: returns when every stale source is done. */
    suspend fun syncAllStale() {
        // A parser change needs fresh catalog rows even when their last sync is still recent.
        // Rev 3 retries the rev 2 Xtream series TMDB repair, which did not actually pass force
        // to the sync engine and incorrectly marked the refresh complete.
        val refreshCatalog = userData.setting(CATALOG_REV_KEY).first() != CATALOG_REV
        val catalogRefreshed = syncSourcesForCatalogRevision(
            sources.sources().first().map { it.id }, refreshCatalog, sync::isStale, ::runSync,
        )
        if (refreshCatalog && catalogRefreshed) userData.putSetting(CATALOG_REV_KEY, CATALOG_REV)
    }

    /**
     * Sync [id] right now (Settings "add a Plex server", manual refresh) through the same path the
     * background worker uses, remote-progress import included. Returns true when the run completed.
     */
    suspend fun syncSourceNow(id: SourceId, scope: SyncScope = SyncScope.ALL, force: Boolean = true): Boolean =
        runSync(id, scope, force)

    private suspend fun runSync(id: SourceId, scope: SyncScope = SyncScope.ALL, force: Boolean = false): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var stage: Any? = null
        var sawSeriesStage = false
        var failed = false
        var finished = false
        sync.sync(id, scope, force).collect { p ->
            _syncProgress.value = p
            if (p.stage == SyncStage.SERIES) sawSeriesStage = true
            if (p.error != null) failed = true
            if (p.finished) finished = true
            if (p.stage != stage || p.finished) {
                Log.i(TAG, "sync +${android.os.SystemClock.elapsedRealtime() - t0} ms ${p.stage} done=${p.done} skipped=${p.skipped}${if (p.finished) " FINISHED" else ""}")
                AppLog.log("sync ${id.value} ${p.stage} done=${p.done} skipped=${p.skipped}${if (p.finished) " FINISHED" else ""}")
                stage = p.stage
            }
            if (p.error != null) {
                Log.w(TAG, "sync ${p.stage}: ${p.error?.message} | cause: ${p.error?.cause}")
                AppLog.log("sync ${id.value} ${p.stage} failed: ${p.error?.message}")
            }
        }
        // The remote-progress import lives in [sync] itself (audit M8), so no caller here can skip it.
        // A concurrent run emits only FINALIZE. Keep the revision pending until this caller
        // observes the series stage itself, so a skipped run cannot acknowledge the repair.
        return finished && !failed && (!force || sawSeriesStage)
    }

    /** Ask [id]'s source for its own in-progress items and merge them (newer-wins) into user data. */
    private suspend fun importRemoteProgress(id: SourceId) {
        val source = sources.contentSource(id) ?: return
        val items = source.remoteProgress()
        if (items.isEmpty()) return
        for (item in items) {
            runCatching {
                userData.importRemoteProgress(
                    ContentKey(id, item.kind, item.id), item.parentId,
                    item.positionMs, item.durationMs, item.lastViewedAtMs,
                )
            }
        }
    }

    /** DEV ONLY: point a fresh install at the mock provider so the app is testable without a real login. */
    fun devBootstrap(mockBase: String) {
        if (mockBase.isEmpty()) return
        appScope.launch {
            if (sources.sources().first().isEmpty()) {
                sources.add(SourceConfig.Xtream(SourceId(""), "Mock provider", mockBase, "test", "test"))
            }
            syncIfStale()
        }
    }

    companion object {
        const val TAG = "Omniverse"
        const val CATALOG_REV_KEY = "catalog_rev"
        const val CATALOG_REV = "3"

        /** Startup profile picker already answered in this process (survives the switch-restart; a
         *  fresh process asks again per the setting). */
        @Volatile var profileGateResolved = false
    }
}

/**
 * The one post-sync progress import (audit M8): wraps a [SyncEngine] so that every completed run of
 * any entry point pulls that source's own in-progress items (Plex onDeck) into user data.
 *
 * - Import happens once per completed run, after the final progress event.
 * - A collector that cancels (screen left, app backgrounded) never imports: the run was not completed.
 * - An import failure is reported through [onImportFailed] and never fails the sync itself.
 */
internal class RemoteProgressSyncEngine(
    private val delegate: SyncEngine,
    private val importProgress: suspend (SourceId) -> Unit,
    private val onImportFailed: (SourceId, Throwable) -> Unit = { _, _ -> },
) : SyncEngine {
    override fun sync(sourceId: SourceId, scope: SyncScope, force: Boolean): Flow<SyncProgress> = flow {
        delegate.sync(sourceId, scope, force).collect { emit(it) }
        runCatching { importProgress(sourceId) }.onFailure { onImportFailed(sourceId, it) }
    }

    override suspend fun isStale(sourceId: SourceId, scope: SyncScope): Boolean = delegate.isStale(sourceId, scope)
}

/**
 * The Kids hard filter (audit 73 M1+M2): on a Kids profile locked categories stay denied even
 * during a PIN-unlocked session — the unlock belongs to the parental policy, never to Kids.
 * Extracted from [AppGraph.kidsVisibility] so the exact policy the Live / Guide / Multiview wiring
 * and the route gate share is unit-testable.
 */
internal fun kidsHardFilter(isKids: Boolean, lockedKeys: Set<String>): com.yodesla.omniverse.core.data.Visibility =
    { kind, sourceId, categoryId -> !isKids || "$kind|$sourceId|$categoryId" !in lockedKeys }

/** Task 84h: one row of [AppGraph.allCategoryNames] — a category name to classify, with its key parts. */
internal data class LangCat(val kind: String, val sourceId: String, val categoryId: String, val name: String)

/** Keep the parser-repair revision pending if a source failed or another sync held its lock. */
internal suspend fun syncSourcesForCatalogRevision(
    sourceIds: List<SourceId>,
    refreshCatalog: Boolean,
    isStale: suspend (SourceId, SyncScope) -> Boolean,
    runSync: suspend (SourceId, SyncScope, Boolean) -> Boolean,
): Boolean {
    var catalogRefreshed = true
    for (id in sourceIds) {
        if (refreshCatalog) {
            if (!runSync(id, SyncScope.VOD_AND_SERIES, true)) catalogRefreshed = false
            if (isStale(id, SyncScope.LIVE_AND_EPG)) runSync(id, SyncScope.LIVE_AND_EPG, false)
        } else if (isStale(id, SyncScope.ALL)) {
            runSync(id, SyncScope.ALL, false)
        }
    }
    return catalogRefreshed
}
