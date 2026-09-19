package com.loopa.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.loopa.app.LoopaApp
import com.loopa.app.data.Prefs
import com.loopa.app.update.UpdateState
import com.loopa.app.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@UnstableApi
class SettingsViewModel(private val app: LoopaApp) : ViewModel() {

    private val prefs = Prefs(app)
    private val updater = Updater(app)
    private val repo = app.repository

    val installedVersion: String = updater.installedVersionName()
    val installedCode: Long = updater.installedVersionCode()

    private val _updateUrl = MutableStateFlow(prefs.updateUrl)
    val updateUrl: StateFlow<String> = _updateUrl.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _refreshMessage = MutableStateFlow<String?>(null)
    val refreshMessage: StateFlow<String?> = _refreshMessage.asStateFlow()

    fun setUpdateUrl(value: String) {
        _updateUrl.value = value
        prefs.updateUrl = value
    }

    fun update() = viewModelScope.launch {
        val url = _updateUrl.value.trim()
        if (url.isBlank() || !url.startsWith("http")) {
            _state.value = UpdateState.Failed("Wklej adres, spod którego mam pobrać nową wersję.")
            return@launch
        }

        if (!updater.canInstall()) {
            _state.value = UpdateState.NeedsInstallPermission
            return@launch
        }

        _state.value = UpdateState.Checking
        // Adres moze wskazywac na manifest JSON - wtedy da sie sprawdzic wersje
        // przed pobraniem calego pliku. Zwykly link do APK po prostu pobieramy.
        val info = updater.fetchInfo(url)
        if (info != null && info.versionCode in 1..installedCode) {
            _state.value = UpdateState.UpToDate(info.versionName)
            return@launch
        }

        val apkUrl = info?.apkUrl ?: url
        _state.value = UpdateState.Downloading(0f)
        updater.download(apkUrl) { progress ->
            _state.value = UpdateState.Downloading(progress)
        }.onSuccess { file ->
            _state.value = UpdateState.ReadyToInstall(file, info)
            updater.install(file)
        }.onFailure { error ->
            _state.value = UpdateState.Failed(error.message ?: "Pobieranie się nie powiodło.")
        }
    }

    fun grantInstallPermission() {
        updater.requestInstallPermission()
        _state.value = UpdateState.Idle
    }

    fun installAgain() {
        (_state.value as? UpdateState.ReadyToInstall)?.let { updater.install(it.file) }
    }

    fun dismissState() { _state.value = UpdateState.Idle }

    /**
     * Uzupelnia brakujace dlugosci filmow. TikTok przez oEmbed ich nie podaje,
     * a bez nich suwaki petli nie maja wlasciwej skali.
     */
    fun refreshMetadata() = viewModelScope.launch {
        _refreshing.value = true
        var fixed = 0
        runCatching {
            val tracks = repo.allTracksOnce()
            tracks.forEach { track ->
                if (track.durationMs <= 0L) {
                    repo.refreshMetadata(track.id)
                    fixed++
                }
            }
        }
        _refreshing.value = false
        _refreshMessage.value = if (fixed == 0) {
            "Wszystkie filmy mają już znaną długość."
        } else {
            "Odświeżono $fixed filmów."
        }
    }

    fun consumeRefreshMessage() { _refreshMessage.value = null }
}
