package me.haroldmartin.codexeink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.haroldmartin.codexeink.CodexEinkApplication

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val app = context.applicationContext as CodexEinkApplication
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val shouldStart = app.container.preferences.alwaysConnected.first()
                if (shouldStart && app.container.credentialStore.hasStoredProfile()) {
                    ConnectionService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
