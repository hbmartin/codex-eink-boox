package me.haroldmartin.codexeink.data

interface CredentialStore {
    fun hasStoredProfile(): Boolean

    fun read(): ConnectionProfile?

    fun write(profile: ConnectionProfile)

    fun clear()
}
