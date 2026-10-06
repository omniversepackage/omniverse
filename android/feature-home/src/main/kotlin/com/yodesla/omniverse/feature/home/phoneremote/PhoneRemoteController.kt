package com.yodesla.omniverse.feature.home.phoneremote

import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the phone-remote server for the whole app (created once in AppGraph), so it survives
 * navigation. The server runs only while the persisted toggle is On or the Settings screen is open.
 * A fresh token + 6-digit code are minted every time the server starts, so turning the toggle off
 * and on rotates them.
 */
class PhoneRemoteController(
    private val search: SearchRepository,
    private val catalog: CatalogRepository,
    visibility: kotlinx.coroutines.flow.Flow<Visibility>,
    private val userData: UserDataRepository,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Task 91: app-level Now Playing holder (AppGraph), served read-only by GET /now. */
    private val nowPlaying: StateFlow<NowPlaying?> = MutableStateFlow(null),
) {
    @Volatile private var currentVisibility: Visibility = { _, _, _ -> true }
    @Volatile private var enabled: Boolean = false
    @Volatile private var lastEnabled: Boolean = false
    @Volatile private var screenVisible: Boolean = false
    @Volatile private var server: PhoneRemoteServer? = null

    private val _state = MutableStateFlow(PhoneRemoteUiState())
    val state: StateFlow<PhoneRemoteUiState> = _state.asStateFlow()

    private val _actions = MutableSharedFlow<PhoneRemoteAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<PhoneRemoteAction> = _actions.asSharedFlow()

    init {
        visibility.onEach { currentVisibility = it }.launchIn(scope)
        userData.setting(TOGGLE_KEY).onEach { raw ->
            enabled = raw == "1"
            reconcile()
        }.launchIn(scope)
    }

    /** Settings screen open/closed: keeps the server alive while the screen is shown even if the
     *  toggle is Off. */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        reconcile()
    }

    /** Persisted toggle (default Off). Rotates the token/code because the server restarts. */
    fun setEnabled(on: Boolean) {
        scope.launch { userData.putSetting(TOGGLE_KEY, if (on) "1" else "0") }
    }

    private fun reconcile() {
        _state.update { it.copy(enabled = enabled) }
        if (enabled != lastEnabled) {
            server?.stop()
            server = null
            lastEnabled = enabled
        }
        val desired = enabled || screenVisible
        if (desired && server == null) startServer()
        else if (!desired && server != null) {
            server?.stop()
            server = null
            _state.update { it.copy(active = false, url = null, code = null, connected = false, error = null) }
        }
    }

    private fun startServer() {
        val host = PhoneRemoteServer.lanAddress()
        if (host == null) {
            _state.update { it.copy(active = false, url = null, code = null, connected = false, error = "No network connection") }
            return
        }
        val token = PhoneRemoteServer.newToken()
        val code = PhoneRemoteServer.newCode()
        val s = PhoneRemoteServer(
            token = token,
            code = code,
            onAction = { a -> _actions.tryEmit(a) },
            onSearch = { q -> PhoneRemoteSearch.run(search, catalog, currentVisibility, q, clock.nowMs()) },
            onConnected = { _state.update { it.copy(connected = true) } },
            onNowPlaying = { nowPlaying.value },
        )
        val port = s.start(scope, host)
        if (port == null) {
            _state.update { it.copy(active = false, url = null, code = null, connected = false, error = "Could not start (port busy)") }
            return
        }
        server = s
        _state.update { it.copy(active = true, url = "http://$host:$port/?k=$token", code = code, connected = false, error = null) }
    }

    companion object {
        const val TOGGLE_KEY = "phone_remote"
    }
}
