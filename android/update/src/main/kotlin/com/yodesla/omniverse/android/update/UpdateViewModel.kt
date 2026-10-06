package com.yodesla.omniverse.android.update

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.update.UpdateCheck
import com.yodesla.omniverse.core.update.UpdateChecker
import com.yodesla.omniverse.core.update.UpdateManifest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val progress: Float) : UpdateUiState
    data class NeedsPermission(val file: File) : UpdateUiState
    data class ReadyToInstall(val file: File) : UpdateUiState
    data class Error(val messageRes: Int) : UpdateUiState
}

/**
 * Drives the in-app update flow (PLAN.md P3.7).
 * [checker] is null when BrandConfig.updateManifestUrl is null — updates are disabled and the
 * prompt is always [UpdateUiState.Hidden]. Check failures are silent (log only); download and
 * install failures show a retryable error.
 */
class UpdateViewModel(
    private val checker: UpdateChecker?,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var manifest: UpdateManifest? = null
    private var job: Job? = null

    fun checkNow() {
        val checker = checker ?: return
        job?.cancel()
        job = viewModelScope.launch {
            when (val result = checker.check()) {
                is UpdateCheck.UpToDate -> _state.value = UpdateUiState.Hidden
                is UpdateCheck.Available -> {
                    manifest = result.manifest
                    _state.value = UpdateUiState.Available(result.manifest)
                }
                is UpdateCheck.Failed -> Log.i(TAG, "Update check failed: ${result.reason}")
            }
        }
    }

    fun accept() {
        val m = manifest ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(0f)
            val file = try {
                installer.download(m) { p -> _state.value = UpdateUiState.Downloading(p) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                Log.w(TAG, "Update download failed", e)
                _state.value = UpdateUiState.Error(
                    if (e is SourceException.BadResponse) R.string.update_error_damaged else R.string.update_error_generic,
                )
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                _state.value = UpdateUiState.Error(R.string.update_error_generic)
                return@launch
            }
            if (installer.canInstall()) {
                installer.install(file)
                _state.value = UpdateUiState.ReadyToInstall(file)
            } else {
                _state.value = UpdateUiState.NeedsPermission(file)
            }
        }
    }

    fun later() {
        job?.cancel()
        _state.value = if (manifest?.mandatory == true) UpdateUiState.Available(manifest!!) else UpdateUiState.Hidden
    }

    /** "Open settings": the system "install unknown apps" screen for this app. */
    fun openSettings() = installer.openInstallPermissionSettings()

    /**
     * Called by the "Install" button and whenever the app resumes (e.g. back from settings).
     * Still no permission: stay on the explanation (the user may just have backed out).
     */
    fun retryInstall() {
        when (val s = _state.value) {
            is UpdateUiState.NeedsPermission -> if (installer.canInstall()) {
                installer.install(s.file)
                _state.value = UpdateUiState.ReadyToInstall(s.file)
            }
            is UpdateUiState.ReadyToInstall -> installer.install(s.file)
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "UpdateViewModel"
    }
}
