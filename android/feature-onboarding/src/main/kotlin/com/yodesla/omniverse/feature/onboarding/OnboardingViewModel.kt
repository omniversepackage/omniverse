package com.yodesla.omniverse.feature.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yodesla.omniverse.core.brand.ExperienceMode
import com.yodesla.omniverse.core.brand.FeatureFlags
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SyncEngine
import com.yodesla.omniverse.core.data.SyncProgress
import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.data.SyncStage
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import java.net.URI
import java.net.URLDecoder

enum class Step { WELCOME, ADD_SOURCE, VALIDATING, SYNCING, DONE }

enum class SourceTab { XTREAM, M3U, PLEX }

/** A Plex server the user can pick after linking; [config] is ready to save. */
data class PlexServerChoice(val name: String, val config: SourceConfig.Plex)

/**
 * plex.tv PIN link (implemented in the app on top of core:source-plex). The user types [createCode]'s
 * code at plex.tv/link on any device; we never see their Plex password.
 */
interface PlexLinker {
    suspend fun createCode(): String
    /** null while waiting for the user; then the servers they can reach (reachable address resolved). */
    suspend fun poll(): List<PlexServerChoice>?
}

@Immutable
data class PlexLinkUi(
    val code: String? = null,
    val servers: List<PlexServerChoice> = emptyList(),
    val selectedMachineIds: Set<String> = emptySet(),
    val addedCount: Int = 0,
    val skippedCount: Int = 0,
    val failed: Boolean = false,
    /** Short technical reason under the error (no secrets: source exceptions are redacted). */
    val detail: String? = null,
)

@Immutable
data class FormState(
    val tab: SourceTab = SourceTab.XTREAM,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val name: String = "",
    val passwordVisible: Boolean = false,
)

/** Structured account facts; the screen turns them into text with string resources. */
@Immutable
data class AccountUi(
    val expiresAtMs: Long?,
    val maxConnections: Int?,
)

@Immutable
data class ProgressUi(
    @StringRes val stageText: Int,
    val done: Int,
    @StringRes val unit: Int?,
    val canStartWatching: Boolean,
    @StringRes val note: Int? = null,
)

@Immutable
data class OnboardingUiState(
    val step: Step = Step.WELCOME,
    val mode: ExperienceMode = ExperienceMode.SIMPLE,
    val form: FormState = FormState(),
    @StringRes val error: Int? = null,
    val account: AccountUi? = null,
    val progress: ProgressUi? = null,
    /** "Set up from your phone": address + code shown on the TV while the form is open. */
    val phoneSetup: PhoneSetupUi? = null,
    val plex: PlexLinkUi = PlexLinkUi(),
    /** Non-null while re-entering an existing source's login (L3): the name of that source. */
    val editingName: String? = null,
)

@Immutable
data class PhoneSetupUi(val address: String, val code: String)

/**
 * Pasted-server parser: normalizes the address and lifts username/password out of a
 * pasted /player_api.php or /get.php URL. Pure, so it is unit-tested directly.
 */
data class XtreamFields(val server: String, val username: String, val password: String)

private val Scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val XtreamSuffix = Regex("""(?:/player_api\.php|/get\.php).*$""", RegexOption.IGNORE_CASE)

object UrlNormalize {
    /** Trims, prefixes http:// when no scheme was typed, strips one trailing slash. */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isNotEmpty() && !Scheme.containsMatchIn(s)) s = "http://$s"
        return s.trimEnd('/')
    }

    fun isHttp(s: String): Boolean = s.startsWith("http://") || s.startsWith("https://")

    /** [normalize] plus stripping a pasted /player_api.php... or /get.php... suffix. */
    fun parseXtream(raw: String, username: String, password: String): XtreamFields {
        var s = raw.trim()
        if (s.isNotEmpty() && !Scheme.containsMatchIn(s)) s = "http://$s"
        var user = username
        var pass = password
        XtreamSuffix.find(s)?.let { m ->
            val query = m.value.substringAfter('?', "")
            if (query.isNotEmpty()) {
                for (pair in query.split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq <= 0) continue
                    val key = pair.substring(0, eq)
                    val value = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))
                    when (key) {
                        "username" -> if (user.isBlank()) user = value
                        "password" -> if (pass.isBlank()) pass = value
                    }
                }
            }
            s = s.removeRange(m.range)
        }
        return XtreamFields(s.trimEnd('/'), user.trim(), pass.trim())
    }

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
}

class OnboardingViewModel(
    private val sources: SourceRepository,
    private val sync: SyncEngine,
    private val userData: UserDataRepository,
    private val features: FeatureFlags,
    private val newId: () -> String,
    /** Where the first sync runs. The app passes its own scope so "Start watching" doesn't cancel it. */
    private val syncScope: CoroutineScope? = null,
    /** Adding a second source: the experience mode was already chosen, go straight to the form. */
    skipWelcome: Boolean = false,
    private val plexLinker: PlexLinker? = null,
    /** Re-entering the login of an existing source (L3): the form is pre-filled and saving UPDATES
     *  this source instead of adding a new one. Plex sources ignore it (they re-link, not retype). */
    private val editSourceId: SourceId? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var currentId: SourceId? = null
    private var syncJob: Job? = null
    /** Set once [editSourceId] is found and its details are in the form. */
    private var editingId: SourceId? = null
    private var editingBase: SourceConfig? = null

    val allowM3u: Boolean get() = features.allowM3u
    val allowPlex: Boolean get() = plexLinker != null
    private var plexJob: Job? = null

    init {
        // Provider-locked / simple-only builds skip the mode choice entirely.
        if (!features.allowFullControlMode || skipWelcome) {
            _state.update { it.copy(step = Step.ADD_SOURCE, mode = ExperienceMode.SIMPLE) }
        }
        if (editSourceId != null) startEditing(editSourceId)
    }

    /**
     * L3: put the source's own details into the form — server and username prefilled, password left
     * empty (it was never readable back out of the keystore in plain text). Saving then updates this
     * source rather than adding a duplicate.
     */
    private fun startEditing(id: SourceId) {
        _state.update { it.copy(step = Step.ADD_SOURCE, mode = ExperienceMode.SIMPLE) }
        viewModelScope.launch {
            val cfg = runCatching { sources.config(id) }.getOrNull()
            val form = when (cfg) {
                is SourceConfig.Xtream -> FormState(tab = SourceTab.XTREAM, server = cfg.server, username = cfg.username, name = cfg.displayName)
                is SourceConfig.M3u -> FormState(tab = SourceTab.M3U, m3uUrl = cfg.playlistUrl, epgUrl = cfg.epgUrl.orEmpty(), name = cfg.displayName)
                else -> null
            }
            if (cfg == null || form == null) return@launch // Plex, or already removed: plain add flow
            editingId = id
            editingBase = cfg
            _state.update { it.copy(form = form, editingName = cfg.displayName) }
        }
    }

    // ------------------------------------------------- step 1: welcome

    fun chooseMode(mode: ExperienceMode) = viewModelScope.launch {
        userData.putSetting(MODE_KEY, mode.name.lowercase())
        _state.update { it.copy(step = Step.ADD_SOURCE, mode = mode, error = null) }
    }

    // ------------------------------------------------- step 2: add source

    fun setTab(tab: SourceTab) {
        if (tab == SourceTab.M3U && !allowM3u) return
        if (tab == SourceTab.PLEX && !allowPlex) return
        _state.update { it.copy(form = it.form.copy(tab = tab), error = null) }
        if (tab == SourceTab.PLEX) startPlexLink() else stopPlexLink()
    }

    // ------------------------------------------------- Plex (plex.tv/link)

    /** New code, then poll every 2 s (codes last ~15 min on plex.tv) until the user approves. */
    fun startPlexLink() {
        val linker = plexLinker ?: return
        plexJob?.cancel()
        _state.update { it.copy(plex = PlexLinkUi()) }
        plexJob = viewModelScope.launch {
            try {
                val code = linker.createCode()
                _state.update { it.copy(plex = PlexLinkUi(code = code)) }
                repeat(PLEX_POLLS) {
                    delay(2_000)
                    val servers = linker.poll() ?: return@repeat
                    if (servers.size == 1) { addSource(servers.single().config); return@launch }
                    _state.update { it.copy(plex = it.plex.copy(servers = servers, selectedMachineIds = emptySet(), failed = servers.isEmpty(), detail = if (servers.isEmpty()) "Linked, but no Plex server answered on this network." else null)) }
                    return@launch
                }
                _state.update { it.copy(plex = it.plex.copy(failed = true, detail = "The code expired.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(plex = it.plex.copy(failed = true, detail = "${e.javaClass.simpleName}: ${e.message?.take(160)}")) }
            }
        }
    }

    fun togglePlexServer(choice: PlexServerChoice) = _state.update { s ->
        val id = choice.config.machineId
        val selected = s.plex.selectedMachineIds
        s.copy(plex = s.plex.copy(selectedMachineIds = if (id in selected) selected - id else selected + id))
    }

    /** One Plex account may expose the owner's server plus several friends' shared servers. */
    fun addSelectedPlexServers() {
        val plex = state.value.plex
        val selected = plex.servers.distinctBy { it.config.machineId }
            .filter { it.config.machineId in plex.selectedMachineIds }
        if (selected.isEmpty() || state.value.step != Step.ADD_SOURCE) return
        stopPlexLink()
        _state.update { it.copy(step = Step.VALIDATING, error = null, progress = null, account = null) }
        viewModelScope.launch {
            val added = mutableListOf<SourceId>()
            var skipped = 0
            var errorRes = R.string.onboarding_err_network
            try {
                val existing = sources.sources().first().filter { it.kind == SourceKind.PLEX }
                    .mapNotNull { sources.config(it.id) as? SourceConfig.Plex }
                    .map { it.machineId }.toSet()
                for (choice in selected) {
                    val config = choice.config
                    if (config.machineId in existing) { skipped++; errorRes = R.string.onboarding_plex_already_added; continue }
                    try {
                        val info = sources.probe(config)
                        when (info.status) {
                            AccountStatus.AUTH_FAILED, AccountStatus.BANNED, AccountStatus.DISABLED -> throw SourceException.AuthFailed()
                            AccountStatus.EXPIRED -> throw SourceException.Expired()
                            AccountStatus.ACTIVE, AccountStatus.UNKNOWN -> Unit
                        }
                        added += sources.add(config)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SourceException) {
                        skipped++; errorRes = friendlyErrorRes(e)
                    } catch (e: Exception) {
                        skipped++
                    }
                }
                if (added.isEmpty()) {
                    _state.update { it.copy(step = Step.ADD_SOURCE, error = errorRes) }
                    return@launch
                }
                currentId = added.first()
                _state.update { s -> s.copy(step = Step.SYNCING, plex = s.plex.copy(addedCount = added.size, skippedCount = skipped)) }
                startPlexSync(added)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = Step.ADD_SOURCE, error = R.string.onboarding_err_network) }
            }
        }
    }

    private fun stopPlexLink() { plexJob?.cancel(); plexJob = null }

    fun onServerChange(v: String) = patchForm { it.copy(server = v) }
    fun onUsernameChange(v: String) = patchForm { it.copy(username = v) }
    fun onPasswordChange(v: String) = patchForm { it.copy(password = v) }
    fun onPlaylistChange(v: String) = patchForm { it.copy(m3uUrl = v) }
    fun onEpgChange(v: String) = patchForm { it.copy(epgUrl = v) }
    fun onNameChange(v: String) = patchForm { it.copy(name = v) }
    fun togglePassword() = patchForm { it.copy(passwordVisible = !it.passwordVisible) }

    private fun patchForm(f: (FormState) -> FormState) = _state.update { s -> s.copy(form = f(s.form), error = null) }

    /** @StringRes error, or null when the form is valid. */
    fun validate(): Int? {
        val f = state.value.form
        return when (f.tab) {
            SourceTab.PLEX -> null // linked via plex.tv, nothing typed
            SourceTab.XTREAM -> {
                val x = UrlNormalize.parseXtream(f.server, f.username, f.password)
                when {
                    x.server.isEmpty() -> R.string.onboarding_err_server
                    !UrlNormalize.isHttp(x.server) -> R.string.onboarding_err_scheme
                    x.username.isEmpty() -> R.string.onboarding_err_username
                    x.password.isEmpty() -> R.string.onboarding_err_password
                    else -> null
                }
            }
            SourceTab.M3U -> {
                val url = UrlNormalize.normalize(f.m3uUrl)
                when {
                    url.isEmpty() -> R.string.onboarding_err_playlist
                    !UrlNormalize.isHttp(url) -> R.string.onboarding_err_scheme
                    else -> null
                }
            }
        }
    }

    // ------------------------------------------------- step 3: validating

    fun submit() {
        if (state.value.step != Step.ADD_SOURCE) return
        val err = validate()
        if (err != null) {
            _state.update { it.copy(error = err) }
            return
        }
        val f = state.value.form
        // Re-entering a login keeps the source's own id (and its live-format / user-agent details).
        val id = editingId ?: SourceId(newId())
        val config: SourceConfig = if (f.tab == SourceTab.XTREAM) {
                val x = UrlNormalize.parseXtream(f.server, f.username, f.password)
                val base = editingBase as? SourceConfig.Xtream
                SourceConfig.Xtream(
                    id,
                    displayName(f.name, x.server),
                    x.server,
                    x.username,
                    x.password,
                    liveFormat = base?.liveFormat ?: "ts",
                    epgUrlOverride = base?.epgUrlOverride,
                )
            } else {
                val url = UrlNormalize.normalize(f.m3uUrl)
                val base = editingBase as? SourceConfig.M3u
                SourceConfig.M3u(
                    id,
                    displayName(f.name, url),
                    url,
                    epgUrl = UrlNormalize.normalize(f.epgUrl).ifEmpty { null },
                    userAgent = base?.userAgent,
                )
            }
        addSource(config)
    }

    /** Log in BEFORE saving, then save (add, or update when re-entering a login) and start the sync. */
    private fun addSource(config: SourceConfig) {
        stopPlexLink()
        _state.update { it.copy(error = null, step = Step.VALIDATING, progress = null, account = null) }
        viewModelScope.launch {
            try {
                // Log in BEFORE saving: bad details (or the app dying mid-check) never leave a dead source.
                val info = sources.probe(config)
                // Xtream reports a bad login as auth=0 (a status), not an HTTP error.
                when (info.status) {
                    AccountStatus.AUTH_FAILED, AccountStatus.BANNED, AccountStatus.DISABLED -> throw SourceException.AuthFailed()
                    AccountStatus.EXPIRED -> throw SourceException.Expired()
                    AccountStatus.ACTIVE, AccountStatus.UNKNOWN -> Unit
                }
                if (editingId != null) sources.update(config) else sources.add(config)
                currentId = config.id
                _state.update {
                    it.copy(
                        step = Step.SYNCING,
                        account = AccountUi(info.expiresAtMs, info.maxConnections),
                    )
                }
                startSync(config.id)
            } catch (e: SourceException) {
                currentId = null
                _state.update { it.copy(step = Step.ADD_SOURCE, error = friendlyErrorRes(e)) }
            } catch (e: Exception) {
                // Never leak a raw stack to the user.
                currentId = null
                _state.update { it.copy(step = Step.ADD_SOURCE, error = R.string.onboarding_err_network) }
            }
        }
    }

    private fun displayName(name: String, url: String): String =
        name.trim().ifEmpty { UrlNormalize.hostOf(url)?.ifEmpty { null } ?: url }

    // ------------------------------------------------- step 4: syncing

    /** Max channel count ever reported by the LIVE_CHANNELS stage. */
    private var liveChannelsDone = 0

    private fun startSync(id: SourceId) {
        syncJob = (syncScope ?: viewModelScope).launch {
            try {
                sync.sync(id, SyncScope.ALL, force = true).collect { p ->
                    onProgress(p)
                }
                // Only a sync that is still on the progress screen may finish into DONE.
                _state.update { if (it.step == Step.SYNCING) it.copy(step = Step.DONE) else it }
            } catch (e: SourceException) {
                _state.update { it.copy(step = Step.SYNCING, error = friendlyErrorRes(e), progress = null) }
            }
        }
    }

    /** Sync selected Plex servers in sequence so a large shared library cannot overload the TV. */
    private fun startPlexSync(ids: List<SourceId>) {
        syncJob = (syncScope ?: viewModelScope).launch {
            var completed = 0
            for (id in ids) {
                var fatal = false
                try {
                    sync.sync(id, SyncScope.ALL, force = true).collect { p ->
                        val stageError = p.error
                        if (p.finished && stageError != null && isFatal(stageError)) {
                            sources.remove(id)
                            fatal = true
                        } else {
                            val note = if (p.error != null) R.string.onboarding_note_retry_later else null
                            _state.update { s ->
                                if (s.step != Step.SYNCING) s else s.copy(progress = ProgressUi(
                                    stageText = stageTextRes(p.stage), done = p.done, unit = unitRes(p.stage),
                                    canStartWatching = completed > 0, note = note,
                                ))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SourceException) {
                    if (isFatal(e)) { sources.remove(id); fatal = true }
                }
                if (!fatal) {
                    completed++
                    if (currentId == null) currentId = id
                    _state.update { s ->
                        if (s.step != Step.SYNCING) s else s.copy(progress = s.progress?.copy(canStartWatching = true))
                    }
                } else if (currentId == id) currentId = null
            }
            _state.update { s ->
                if (s.step != Step.SYNCING) s else if (completed > 0) s.copy(step = Step.DONE)
                else s.copy(step = Step.ADD_SOURCE, error = R.string.onboarding_err_auth, progress = null)
            }
        }
    }

    private fun onProgress(p: SyncProgress) {
        if (p.stage == SyncStage.LIVE_CHANNELS) liveChannelsDone = max(liveChannelsDone, p.done)
        val pastLive = p.stage.ordinal > SyncStage.LIVE_CHANNELS.ordinal
        val stageError = p.error
        if (p.finished && stageError != null && isFatal(stageError)) {
            // Login rejected mid-sync: back to the form, never to "all set". A source that already
            // existed (re-entered login) is kept — removing it would delete the viewer's source.
            syncJob?.cancel()
            if (editingId == null) viewModelScope.launch { sources.remove(id = p.sourceId) }
            currentId = null
            _state.update { it.copy(step = Step.ADD_SOURCE, error = friendlyErrorRes(stageError), progress = null) }
            return
        }
        val note = if (stageError != null && !isFatal(stageError)) R.string.onboarding_note_retry_later else null
        _state.update {
            it.copy(
                step = if (p.finished) Step.DONE else it.step,
                error = null,
                progress = ProgressUi(
                    stageText = stageTextRes(p.stage),
                    done = p.done,
                    unit = unitRes(p.stage),
                    canStartWatching = liveChannelsDone > 0 && pastLive,
                    note = note,
                ),
            )
        }
    }

    /** Fatal errors abort the whole sync; anything else is a stage hiccup. */
    private fun isFatal(e: SourceException): Boolean = e is SourceException.AuthFailed || e is SourceException.Expired

    @StringRes
    fun friendlyErrorRes(e: SourceException): Int = when (e) {
        is SourceException.AuthFailed -> R.string.onboarding_err_auth
        is SourceException.Expired -> R.string.onboarding_err_expired
        is SourceException.Network -> R.string.onboarding_err_network
        is SourceException.Http, is SourceException.BadResponse -> R.string.onboarding_err_http
        else -> R.string.onboarding_err_network
    }

    @StringRes
    private fun stageTextRes(stage: SyncStage): Int = when (stage) {
        SyncStage.ACCOUNT -> R.string.onboarding_stage_account
        SyncStage.LIVE_CATEGORIES, SyncStage.LIVE_CHANNELS -> R.string.onboarding_stage_channels
        SyncStage.EPG -> R.string.onboarding_stage_guide
        SyncStage.VOD_CATEGORIES, SyncStage.VOD -> R.string.onboarding_stage_movies
        SyncStage.SERIES_CATEGORIES, SyncStage.SERIES -> R.string.onboarding_stage_shows
        SyncStage.FINALIZE -> R.string.onboarding_stage_finalize
    }

    @StringRes
    private fun unitRes(stage: SyncStage): Int? = when (stage) {
        SyncStage.LIVE_CATEGORIES, SyncStage.LIVE_CHANNELS -> R.string.onboarding_unit_channels
        SyncStage.EPG -> R.string.onboarding_unit_programmes
        SyncStage.VOD_CATEGORIES, SyncStage.VOD -> R.string.onboarding_unit_movies
        SyncStage.SERIES_CATEGORIES, SyncStage.SERIES -> R.string.onboarding_unit_shows
        else -> null
    }

    // ------------------------------------------------- actions

    /** Fatal sync error → back to the form, fields kept. */
    fun retryFromSync() {
        syncJob?.cancel()
        _state.update { it.copy(step = Step.ADD_SOURCE, error = null, progress = null) }
    }

    /** Early exit from the syncing screen; the sync keeps running in the ViewModel scope. */
    fun startWatching() {
        _state.update { it.copy(step = Step.DONE) }
    }

    // ------------------------------------------------- set up from your phone

    private var phoneServer: PhoneSetupServer? = null

    /** Called while the Add-source form is visible. The address is null when there's no LAN. */
    fun startPhoneSetup() {
        if (phoneServer != null) return
        val server = PhoneSetupServer(onSubmit = { f -> viewModelScope.launch { applyFromPhone(f) } })
        val port = server.start(viewModelScope) ?: return
        phoneServer = server
        val host = PhoneSetupServer.lanAddress() ?: return
        _state.update { it.copy(phoneSetup = PhoneSetupUi("http://$host:$port", server.code)) }
    }

    fun stopPhoneSetup() {
        phoneServer?.stop()
        phoneServer = null
        _state.update { it.copy(phoneSetup = null) }
    }

    /** Fills the form exactly as if typed on the TV, then submits (same validation, same errors). */
    internal fun applyFromPhone(f: PhoneSetupFields) {
        if (state.value.step != Step.ADD_SOURCE) return
        val useM3u = f.m3uUrl.isNotBlank() && allowM3u
        _state.update {
            it.copy(
                form = if (useM3u) it.form.copy(tab = SourceTab.M3U, m3uUrl = f.m3uUrl, name = f.name)
                else it.form.copy(tab = SourceTab.XTREAM, server = f.server, username = f.username, password = f.password, name = f.name),
                error = null,
            )
        }
        submit()
    }

    override fun onCleared() {
        phoneServer?.stop()
        super.onCleared()
    }

    /** The source this onboarding run created (null before validation succeeds). */
    val activeSourceId: SourceId? get() = currentId

    companion object {
        /** 2 s × 450 = 15 min, how long a plex.tv PIN stays valid. */
        private const val PLEX_POLLS = 450
        const val MODE_KEY = "experience_mode"
    }
}
