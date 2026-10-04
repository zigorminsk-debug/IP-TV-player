package com.iptvplayer.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptvplayer.app.data.repo.UpdateRepository
import com.iptvplayer.app.di.ServiceLocator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Activity-scoped view model: app settings + self-update flow. */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container = ServiceLocator.instance

    val settingsFlow = container.settings.settings
    val updateState: StateFlow<UpdateRepository.UpdateState> = container.updates.state

    fun autoCheck() {
        viewModelScope.launch { container.updates.maybeAutoCheck() }
    }

    fun checkNow() {
        viewModelScope.launch { container.updates.check(force = true) }
    }

    fun download() {
        val st = updateState.value
        if (st is UpdateRepository.UpdateState.Available) {
            viewModelScope.launch { container.updates.download(st.manifest) }
        }
    }

    fun install() {
        val st = updateState.value
        if (st is UpdateRepository.UpdateState.ReadyToInstall) {
            container.updates.install(st.file, st.manifest)
        }
    }

    fun openInstallPermissionSettings() {
        container.updates.openInstallPermissionSettings()
    }

    fun resetUpdateState() {
        container.updates.reset()
    }

    fun setTheme(theme: String) {
        viewModelScope.launch { container.settings.setTheme(theme) }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoUpdateApp(enabled) }
    }
}
