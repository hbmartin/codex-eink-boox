package me.haroldmartin.codexeink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.haroldmartin.codexeink.pairing.PairingCodeParser
import me.haroldmartin.codexeink.ui.CodexEinkRoot
import me.haroldmartin.einkui.EinkTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val alwaysConnected by viewModel.alwaysConnected.collectAsStateWithLifecycle()
            val hasStoredProfile by viewModel.hasStoredProfile.collectAsStateWithLifecycle()
            var pendingQrCapture by remember { mutableStateOf<Uri?>(null) }
            val captureQr = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.TakePicture(),
            ) { captured ->
                val capture = pendingQrCapture
                pendingQrCapture = null
                if (captured && capture != null) {
                    viewModel.pairQr(capture)
                } else if (capture != null) {
                    contentResolver.delete(capture, null, null)
                }
            }
            val launchQrCapture = {
                createQrCaptureUri()?.let { capture ->
                    pendingQrCapture = capture
                    captureQr.launch(capture)
                }
                Unit
            }
            val cameraPermission = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) launchQrCapture()
            }
            val notificationPermission = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) viewModel.setAlwaysConnected(true)
            }

            EinkTheme {
                Box(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasStoredProfile == null) {
                        Text("Opening Codex Eink…", style = EinkTheme.typography.body)
                    } else {
                        CodexEinkRoot(
                            state = state,
                            hasStoredProfile = hasStoredProfile == true,
                            alwaysConnected = alwaysConnected,
                            onSaveProfile = viewModel::saveAndConnect,
                            onPair = viewModel::pair,
                            onScanQr = {
                                if (
                                    checkSelfPermission(Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    launchQrCapture()
                                } else {
                                    cameraPermission.launch(Manifest.permission.CAMERA)
                                }
                            },
                            onAlwaysConnectedChange = { enabled ->
                                if (
                                    enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setAlwaysConnected(enabled)
                                }
                            },
                            onRefresh = viewModel::refresh,
                            onSelectThread = viewModel::selectThread,
                            onSend = viewModel::send,
                            onInterrupt = viewModel::interrupt,
                            onApproval = viewModel::answerApproval,
                            onQuestion = viewModel::answerQuestion,
                            onDisconnect = viewModel::disconnect,
                        )
                    }
                }
            }
        }

        intent?.dataString?.let(PairingCodeParser::parse)?.let(viewModel::pair)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(PairingCodeParser::parse)?.let(viewModel::pair)
    }

    override fun onStop() {
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
        super.onStop()
    }

    private fun createQrCaptureUri(): Uri? = runCatching {
        val directory = File(cacheDir, "qr").apply { mkdirs() }
        val capture = File.createTempFile("pairing-", ".jpg", directory)
        FileProvider.getUriForFile(this, "$packageName.fileprovider", capture)
    }.getOrNull()
}
