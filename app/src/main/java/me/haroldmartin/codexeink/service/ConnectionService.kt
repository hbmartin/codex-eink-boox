package me.haroldmartin.codexeink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.haroldmartin.codexeink.CodexEinkApplication
import me.haroldmartin.codexeink.Connectivity
import me.haroldmartin.codexeink.MainActivity
import me.haroldmartin.codexeink.R

class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private val controller by lazy { (application as CodexEinkApplication).container.controller }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundNotification(Connectivity.Connecting, null)
        stateJob = scope.launch {
            var previousAttentionKey: String? = null
            var previousActiveThreadId: String? = null
            var previousConnectivity: Connectivity? = null
            controller.state.collectLatest { state ->
                startForegroundNotification(state.connectivity, state.environmentName)
                val approvalKey = state.pendingApproval?.requestId
                val questionKey = state.pendingQuestion?.requestId
                val attentionKey = approvalKey ?: questionKey
                if (attentionKey != null && attentionKey != previousAttentionKey) {
                    postAttentionNotification(
                        title = if (approvalKey != null) "Codex needs approval" else "Codex has a question",
                        body = state.pendingApproval?.title
                            ?: state.pendingQuestion?.questions?.firstOrNull()?.prompt
                            ?: "Open Codex Eink to respond.",
                    )
                }
                if (
                    previousActiveThreadId != null &&
                    previousActiveThreadId == state.selectedThreadId &&
                    !state.activeTurn &&
                    state.connectivity == Connectivity.Connected
                ) {
                    postAttentionNotification(
                        title = "Codex task finished",
                        body = "Open Codex Eink to review the result.",
                    )
                }
                if (state.connectivity == Connectivity.Failed && previousConnectivity != Connectivity.Failed) {
                    postAttentionNotification(
                        title = "Codex connection failed",
                        body = state.error ?: "Open Codex Eink to reconnect.",
                    )
                }
                previousAttentionKey = attentionKey
                previousActiveThreadId = state.selectedThreadId.takeIf { state.activeTurn }
                previousConnectivity = state.connectivity
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    val container = (application as CodexEinkApplication).container
                    container.preferences.setAlwaysConnected(false)
                    controller.disconnect(forgetDevice = false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> scope.launch {
                val container = (application as CodexEinkApplication).container
                val hasProfile = withContext(Dispatchers.IO) {
                    container.credentialStore.hasStoredProfile()
                }
                if (hasProfile) {
                    withContext(Dispatchers.IO) { controller.connectStored() }
                } else {
                    container.preferences.setAlwaysConnected(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification(connectivity: Connectivity, environment: String?) {
        val notification = connectionNotification(connectivity, environment)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, CONNECTION_NOTIFICATION_ID, notification, type)
    }

    private fun connectionNotification(
        connectivity: Connectivity,
        environment: String?,
    ): Notification {
        val title = when (connectivity) {
            Connectivity.Connected -> getString(R.string.connection_notification_title)
            Connectivity.Connecting, Connectivity.Reconnecting ->
                getString(R.string.connection_notification_reconnecting)
            else -> getString(R.string.connection_notification_attention)
        }
        val body = environment ?: connectivity.name.replaceFirstChar(Char::uppercase)
        return NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicConnectionNotification(connectivity))
            .setContentIntent(openAppIntent())
            .addAction(0, "Disconnect", stopServiceIntent())
            .build()
    }

    private fun postAttentionNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, ATTENTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicAttentionNotification())
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(ATTENTION_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun publicConnectionNotification(connectivity: Connectivity): Notification =
        NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(connectivity.name.replaceFirstChar(Char::uppercase))
            .setContentIntent(openAppIntent())
            .build()

    private fun publicAttentionNotification(): Notification =
        NotificationCompat.Builder(this, ATTENTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Open the app to review a Codex update.")
            .setContentIntent(openAppIntent())
            .build()

    private fun stopServiceIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.connection_channel_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ATTENTION_CHANNEL_ID,
                getString(R.string.attention_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.attention_channel_description)
            },
        )
    }

    companion object {
        private const val CONNECTION_CHANNEL_ID = "codex_connection"
        private const val ATTENTION_CHANNEL_ID = "codex_attention"
        private const val CONNECTION_NOTIFICATION_ID = 1001
        private const val ATTENTION_NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "me.haroldmartin.codexeink.action.STOP_CONNECTION"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
