package me.haroldmartin.codexeink

import me.haroldmartin.codexeink.data.AppPreferences
import me.haroldmartin.codexeink.data.CredentialStore

class AppContainer(
    val credentialStore: CredentialStore,
    val preferences: AppPreferences,
    val controller: CodexController,
)
