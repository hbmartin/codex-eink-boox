package me.haroldmartin.codexeink

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.haroldmartin.codexeink.data.AppPreferences
import me.haroldmartin.codexeink.data.KeystoreCredentialStore
import me.haroldmartin.codexeink.protocol.ProtocolCodexController

class CodexEinkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val credentialStore = KeystoreCredentialStore(this)
        container = AppContainer(
            credentialStore = credentialStore,
            preferences = AppPreferences(this),
            controller = ProtocolCodexController(
                credentialStore = credentialStore,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ),
        )
    }
}
