package me.haroldmartin.codexeink

import androidx.annotation.StringRes

@get:StringRes
internal val Connectivity.labelResource: Int
    get() = when (this) {
        Connectivity.Disconnected -> R.string.connectivity_disconnected
        Connectivity.Connecting -> R.string.connectivity_connecting
        Connectivity.Connected -> R.string.connectivity_connected
        Connectivity.Reconnecting -> R.string.connectivity_reconnecting
        Connectivity.HostOffline -> R.string.connectivity_host_offline
        Connectivity.SignedOut -> R.string.connectivity_signed_out
        Connectivity.Revoked -> R.string.connectivity_revoked
        Connectivity.Incompatible -> R.string.connectivity_incompatible
        Connectivity.Failed -> R.string.connectivity_failed
    }
