package me.haroldmartin.codexeink

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.haroldmartin.codexeink.data.ConnectionProfile
import me.haroldmartin.codexeink.pairing.PairingCodeParser
import me.haroldmartin.codexeink.pairing.QrCodeDecoder
import me.haroldmartin.codexeink.service.ConnectionService

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CodexEinkApplication
    private val controller = app.container.controller

    val state: StateFlow<CodexUiState> = controller.state
    private val mutableHasStoredProfile = MutableStateFlow<Boolean?>(null)
    val hasStoredProfile: StateFlow<Boolean?> = mutableHasStoredProfile.asStateFlow()
    val alwaysConnected = app.container.preferences.alwaysConnected.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = false,
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            mutableHasStoredProfile.value = app.container.credentialStore.hasStoredProfile()
            controller.connectStored()
        }
    }

    fun saveAndConnect(profile: ConnectionProfile) = launch {
        controller.saveAndConnect(profile)
        mutableHasStoredProfile.value = app.container.credentialStore.hasStoredProfile()
    }

    fun pair(code: String) = launch { controller.pair(code) }

    fun pairQr(uri: Uri) = launch {
        try {
            QrCodeDecoder.decode(getApplication<Application>().contentResolver, uri)
                ?.let(PairingCodeParser::parse)
                ?.let { controller.pair(it) }
        } finally {
            getApplication<Application>().contentResolver.delete(uri, null, null)
        }
    }

    fun refresh() = launch { controller.refreshThreads() }

    fun selectThread(threadId: String) = launch { controller.selectThread(threadId) }

    fun send(text: String) = launch { controller.send(text) }

    fun interrupt() = launch { controller.interrupt() }

    fun answerApproval(requestId: String, decision: String) = launch {
        controller.answerApproval(requestId, decision)
    }

    fun answerQuestion(requestId: String, answers: Map<String, String>) = launch {
        controller.answerQuestion(requestId, answers)
    }

    fun disconnect(forgetDevice: Boolean) = launch {
        controller.disconnect(forgetDevice)
        app.container.preferences.setAlwaysConnected(false)
        ConnectionService.stop(getApplication())
        if (forgetDevice) {
            mutableHasStoredProfile.value = false
        }
    }

    fun setAlwaysConnected(enabled: Boolean) {
        viewModelScope.launch {
            app.container.preferences.setAlwaysConnected(enabled)
            if (enabled) {
                ConnectionService.start(getApplication())
            } else {
                ConnectionService.stop(getApplication())
            }
        }
    }

    fun onAppForegrounded() = launch { controller.connectStored() }

    fun onAppBackgrounded() = launch {
        if (!app.container.preferences.alwaysConnected.first()) {
            controller.disconnect(forgetDevice = false)
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
