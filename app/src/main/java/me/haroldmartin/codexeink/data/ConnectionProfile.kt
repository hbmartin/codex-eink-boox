package me.haroldmartin.codexeink.data

enum class TransportMode {
    ManagedRelay,
    DirectDiagnostic,
}

data class ConnectionProfile(
    val displayName: String,
    val endpoint: String,
    val credential: String,
    val mode: TransportMode,
)
