package com.example.p2paudio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.p2paudio.logging.AppLogger
import com.example.p2paudio.audio.PlaybackLatencyPreset
import com.example.p2paudio.model.AudioStreamDiagnostics
import com.example.p2paudio.model.AudioStreamSource
import com.example.p2paudio.model.AudioStreamState
import com.example.p2paudio.model.ConnectionDiagnostics
import com.example.p2paudio.model.FailureCode
import com.example.p2paudio.model.NetworkPathType
import com.example.p2paudio.service.AudioReceiveService
import com.example.p2paudio.service.AudioSendService
import com.example.p2paudio.transport.TransportMode
import com.example.p2paudio.ui.MainUiState
import com.example.p2paudio.ui.MainViewModel
import com.example.p2paudio.ui.SetupMode
import com.example.p2paudio.ui.SetupStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<MainViewModel>()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppLogger.i("MainActivity", "projection_permission_granted", "Projection permission granted")
            viewModel.onProjectionPermissionResult(result.data)
        } else {
            AppLogger.w("MainActivity", "projection_permission_denied", "Projection permission denied")
            viewModel.onProjectionPermissionResult(null)
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLogger.i("MainActivity", "record_permission_granted", "RECORD_AUDIO permission granted")
        } else {
            AppLogger.w("MainActivity", "record_permission_denied", "RECORD_AUDIO permission denied")
        }
        viewModel.onRecordAudioPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            viewModel.commands.collect { command ->
                when (command) {
                    MainViewModel.UiCommand.RequestRecordAudioPermission -> {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }

                    is MainViewModel.UiCommand.RequestProjectionPermission -> {
                        projectionLauncher.launch(command.captureIntent)
                    }

                    is MainViewModel.UiCommand.StartProjectionService -> {
                        runCatching {
                            startForegroundSendService(command.permissionResultData)
                        }.onFailure { error ->
                            viewModel.onProjectionServiceStartFailed(error)
                        }
                    }

                    MainViewModel.UiCommand.StartUdpReceiveService -> {
                        runCatching {
                            startForegroundReceiveService()
                        }.onFailure { error ->
                            viewModel.onUdpReceiveServiceStartFailed(error)
                        }
                    }

                    MainViewModel.UiCommand.StopProjectionService -> {
                        stopForegroundSendService()
                    }

                    MainViewModel.UiCommand.StopUdpReceiveService -> {
                        stopForegroundReceiveService()
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val uiState by viewModel.uiState.collectAsState(initial = MainUiState())
                    MainScreen(
                        uiState = uiState,
                        onSelectTransportMode = viewModel::selectTransportMode,
                        onSelectReceiverLatencyPreset = viewModel::selectReceiverLatencyPreset,
                        onChooseSender = viewModel::beginSenderFlow,
                        onContinueSender = viewModel::startSenderFlowRequested,
                        onChooseListener = viewModel::beginListenerFlow,
                        onProcessInitPayload = viewModel::createConfirmFromInit,
                        onProcessConfirmPayload = viewModel::applyConfirm,
                        onSharePayload = ::sharePayload,
                        onVerificationMatch = viewModel::approveVerificationAndConnect,
                        onVerificationMismatch = viewModel::rejectVerificationAndRestart,
                        onStop = viewModel::stopSession
                    )
                }
            }
        }
    }

    private fun sharePayload(payload: String) {
        if (payload.isBlank()) {
            return
        }
        AppLogger.i(
            "MainActivity",
            "payload_share_launch",
            "Launching payload share sheet",
            context = mapOf("length" to payload.length)
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.flow_share_payload)))
    }

    private fun startForegroundSendService(permissionResultData: Intent) {
        AppLogger.i("MainActivity", "start_foreground_service", "Starting AudioSendService")
        val intent = Intent(this, AudioSendService::class.java).apply {
            action = AudioSendService.ACTION_START_CAPTURE
            putExtra(AudioSendService.EXTRA_PROJECTION_DATA, permissionResultData)
        }
        startForegroundService(intent)
    }

    private fun stopForegroundSendService() {
        AppLogger.i("MainActivity", "stop_foreground_service", "Stopping AudioSendService")
        stopService(Intent(this, AudioSendService::class.java))
    }

    private fun startForegroundReceiveService() {
        AppLogger.i("MainActivity", "start_receive_service", "Starting AudioReceiveService")
        val intent = Intent(this, AudioReceiveService::class.java).apply {
            action = AudioReceiveService.ACTION_START_RECEIVE
        }
        startForegroundService(intent)
    }

    private fun stopForegroundReceiveService() {
        AppLogger.i("MainActivity", "stop_receive_service", "Stopping AudioReceiveService")
        stopService(Intent(this, AudioReceiveService::class.java))
    }
}

@Composable
private fun MainScreen(
    uiState: MainUiState,
    onSelectTransportMode: (TransportMode) -> Unit,
    onSelectReceiverLatencyPreset: (PlaybackLatencyPreset) -> Unit,
    onChooseSender: () -> Unit,
    onContinueSender: () -> Unit,
    onChooseListener: () -> Unit,
    onProcessInitPayload: (String) -> Unit,
    onProcessConfirmPayload: (String) -> Unit,
    onSharePayload: (String) -> Unit,
    onVerificationMatch: () -> Unit,
    onVerificationMismatch: () -> Unit,
    onStop: () -> Unit
) {
    var transientMessage by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val initCopiedText = stringResource(R.string.flow_sender_payload_copied)
    val confirmCopiedText = stringResource(R.string.flow_receiver_payload_copied)
    val canStartNewFlow = uiState.setupStep == SetupStep.ENTRY && uiState.streamState == AudioStreamState.IDLE
    val canStopSession = !canStartNewFlow
    val hideSetupCards = uiState.streamState in setOf(
        AudioStreamState.STREAMING,
        AudioStreamState.INTERRUPTED,
        AudioStreamState.FAILED,
        AudioStreamState.ENDED
    )
    val expirySeconds = rememberExpirySeconds(uiState.payloadExpiresAtUnixMs)

    LaunchedEffect(transientMessage) {
        if (transientMessage.isNotBlank()) {
            delay(1800)
            transientMessage = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF4F8FF),
                        Color(0xFFFFF6EC)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeroCard(
                canStopSession = canStopSession,
                onStop = onStop,
                setupMode = uiState.setupMode,
                transportMode = uiState.transportMode
            )

            JourneyCard(uiState = uiState)

            ConnectionOverviewCard(
                uiState = uiState,
                expirySeconds = expirySeconds
            )

            AudioStreamCard(
                diagnostics = uiState.audioStreamDiagnostics,
                receiverLatencyPreset = uiState.receiverLatencyPreset
            )

            if (transientMessage.isNotBlank()) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    text = transientMessage,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF145A72)
                )
            }

            if (!hideSetupCards) {
                when (uiState.setupStep) {
                    SetupStep.ENTRY -> EntryActionsCard(
                        transportMode = uiState.transportMode,
                        receiverLatencyPreset = uiState.receiverLatencyPreset,
                        onSelectTransportMode = onSelectTransportMode,
                        onSelectReceiverLatencyPreset = onSelectReceiverLatencyPreset,
                        onChooseSender = onChooseSender,
                        onChooseListener = onChooseListener,
                        canStartNewFlow = canStartNewFlow
                    )

                    SetupStep.SENDER_PREPARE -> SenderPrepareCard(
                        onContinueSender = onContinueSender,
                        onStop = onStop
                    )

                    SetupStep.PATH_DIAGNOSING -> DiagnosingCard()

                    SetupStep.SENDER_SHOW_INIT -> SenderShowInitCard(
                        uiState = uiState,
                        expirySeconds = expirySeconds,
                        onCopyPayload = {
                            clipboardManager.setText(AnnotatedString(uiState.initPayload))
                            transientMessage = initCopiedText
                        },
                        onSharePayload = { onSharePayload(uiState.initPayload) },
                        onProcessConfirmPayload = onProcessConfirmPayload
                    )

                    SetupStep.SENDER_VERIFY_CODE -> VerificationCard(
                        code = uiState.verificationCode,
                        onVerificationMatch = onVerificationMatch,
                        onVerificationMismatch = onVerificationMismatch
                    )

                    SetupStep.LISTENER_SCAN_INIT -> ListenerScanCard(
                        transportMode = uiState.transportMode,
                        onProcessInitPayload = onProcessInitPayload,
                        onStop = onStop
                    )

                    SetupStep.LISTENER_SHOW_CONFIRM -> ListenerShowConfirmCard(
                        uiState = uiState,
                        expirySeconds = expirySeconds,
                        onCopyPayload = {
                            clipboardManager.setText(AnnotatedString(uiState.confirmPayload))
                            transientMessage = confirmCopiedText
                        },
                        onSharePayload = { onSharePayload(uiState.confirmPayload) }
                    )

                    SetupStep.LISTENER_WAIT_FOR_CONNECTION -> ListenerWaitingForConnectionCard(
                        code = uiState.verificationCode,
                        expirySeconds = expirySeconds,
                        transportMode = uiState.transportMode,
                        onStop = onStop
                    )

                    SetupStep.LISTENER_UDP_WAITING -> UdpListenerWaitingCard(onStop = onStop)
                }
            } else if (uiState.streamState != AudioStreamState.FAILED) {
                ConnectedTipsCard(uiState = uiState)
            }

            TroubleshootingDetailsCard(uiState = uiState)
        }
    }
}

@Composable
private fun AudioStreamCard(
    diagnostics: AudioStreamDiagnostics,
    receiverLatencyPreset: PlaybackLatencyPreset
) {
    if (!diagnostics.hasContent()) {
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "音声ストリーム",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = audioSourceLabel(diagnostics),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(
                    R.string.audio_stream_latency_format,
                    stringResource(receiverLatencyPreset.labelResId)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "形式: ${diagnostics.sampleRate} Hz / ${channelLabel(diagnostics.channels)} / ${diagnostics.bitsPerSample}bit / ${diagnostics.frameDurationMs}ms (${diagnostics.frameSamplesPerChannel} samples)",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "自動調整: ${bufferingModeLabel(diagnostics)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "バッファ: 初期 ${diagnostics.startupTargetFrames} / 目標 ${diagnostics.targetPrebufferFrames} / 現在 ${diagnostics.queueDepthFrames} / AudioTrack ${diagnostics.audioTrackBufferFrames} フレーム",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "揺らぎ: 約 ${diagnostics.estimatedJitterMs}ms / 補間 ${diagnostics.insertedSilenceFrames} / 遅着 ${diagnostics.staleFrameDrops} / 溢れ ${diagnostics.queueOverflowDrops} / underrun ${diagnostics.audioTrackUnderruns}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeroCard(
    canStopSession: Boolean,
    onStop: () -> Unit,
    setupMode: SetupMode,
    transportMode: TransportMode
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.main_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.main_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (canStopSession) {
                    FilledTonalButton(onClick = onStop) {
                        Text(stringResource(R.string.action_stop_session))
                    }
                }
            }

            Text(
                text = currentRoleLabel(setupMode),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = currentTransportLabel(transportMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JourneyCard(uiState: MainUiState) {
    val steps = remember(uiState.setupMode, uiState.transportMode) {
        journeyLabels(uiState.setupMode, uiState.transportMode)
    }
    val activeStepIndex = journeyActiveIndex(uiState)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.flow_tips_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            steps.forEachIndexed { index, label ->
                JourneyStepRow(
                    number = index + 1,
                    label = stringResource(label),
                    isActive = index == activeStepIndex,
                    isCompleted = index < activeStepIndex
                )
            }
        }
    }
}

@Composable
private fun JourneyStepRow(
    number: Int,
    label: String,
    isActive: Boolean,
    isCompleted: Boolean
) {
    val containerColor = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        isCompleted -> Color(0xFFE6F7EA)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        isCompleted -> Color(0xFF176C2E)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = containerColor,
                    shape = MaterialTheme.shapes.small
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isActive) {
                Text(
                    text = stringResource(R.string.status_next_action_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ConnectionOverviewCard(
    uiState: MainUiState,
    expirySeconds: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.status_connection_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(uiState.streamState.labelResId()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor(uiState.streamState)
            )
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium
            )

            if (uiState.failure != null) {
                Text(
                    text = stringResource(
                        R.string.status_failure_format,
                        stringResource(uiState.failure.code.labelResId())
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (uiState.payloadExpiresAtUnixMs > 0L) {
                val expiryTextRes = if (expirySeconds > 0) {
                    R.string.status_qr_expiry_remaining
                } else {
                    R.string.status_qr_expiry_expired
                }
                Text(
                    text = if (expirySeconds > 0) {
                        stringResource(expiryTextRes, expirySeconds)
                    } else {
                        stringResource(expiryTextRes)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (expirySeconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            if (uiState.activeSessionId.isNotBlank()) {
                Text(
                    text = stringResource(R.string.status_session_id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(
                        text = uiState.activeSessionId,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider()
            Text(
                text = stringResource(R.string.status_next_action_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(recommendedActionRes(uiState)),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun EntryActionsCard(
    transportMode: TransportMode,
    receiverLatencyPreset: PlaybackLatencyPreset,
    onSelectTransportMode: (TransportMode) -> Unit,
    onSelectReceiverLatencyPreset: (PlaybackLatencyPreset) -> Unit,
    onChooseSender: () -> Unit,
    onChooseListener: () -> Unit,
    canStartNewFlow: Boolean
) {
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("entry_actions_card"),
        number = 1,
        title = stringResource(R.string.flow_entry_title),
        description = stringResource(R.string.flow_entry_description)
    ) {
        TransportModeSelector(
            selectedMode = transportMode,
            enabled = canStartNewFlow,
            onSelectMode = onSelectTransportMode
        )

        if (transportMode == TransportMode.UDP_OPUS) {
            Text(
                text = stringResource(R.string.transport_mode_udp_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ReceiverLatencySelector(
            selectedPreset = receiverLatencyPreset,
            enabled = canStartNewFlow,
            onSelectPreset = onSelectReceiverLatencyPreset
        )

        ChecklistBlock(
            items = listOf(
                stringResource(R.string.flow_checklist_item_network),
                stringResource(R.string.flow_checklist_item_nearby)
            )
        )

        RoleActionCard(
            title = stringResource(R.string.action_start_sender),
            description = when (transportMode) {
                TransportMode.WEBRTC -> stringResource(R.string.flow_entry_sender_description)
                TransportMode.UDP_OPUS -> stringResource(R.string.transport_mode_udp_sender_unavailable)
            },
            enabled = canStartNewFlow,
            onClick = onChooseSender,
            testTag = "entry_start_sender_button"
        )

        RoleActionCard(
            title = stringResource(R.string.action_start_listener_scan),
            description = when (transportMode) {
                TransportMode.WEBRTC -> stringResource(R.string.flow_entry_listener_description)
                TransportMode.UDP_OPUS -> stringResource(R.string.transport_mode_udp_listener_description)
            },
            enabled = canStartNewFlow,
            onClick = onChooseListener,
            testTag = "entry_start_listener_button"
        )
    }
}

@Composable
private fun TransportModeSelector(
    selectedMode: TransportMode,
    enabled: Boolean,
    onSelectMode: (TransportMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.transport_mode_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedMode == TransportMode.WEBRTC) {
                Button(onClick = { onSelectMode(TransportMode.WEBRTC) }, enabled = enabled) {
                    Text(stringResource(R.string.transport_mode_webrtc))
                }
            } else {
                OutlinedButton(onClick = { onSelectMode(TransportMode.WEBRTC) }, enabled = enabled) {
                    Text(stringResource(R.string.transport_mode_webrtc))
                }
            }

            if (selectedMode == TransportMode.UDP_OPUS) {
                Button(onClick = { onSelectMode(TransportMode.UDP_OPUS) }, enabled = enabled) {
                    Text(stringResource(R.string.transport_mode_udp))
                }
            } else {
                OutlinedButton(onClick = { onSelectMode(TransportMode.UDP_OPUS) }, enabled = enabled) {
                    Text(stringResource(R.string.transport_mode_udp))
                }
            }
        }
    }
}

@Composable
private fun ReceiverLatencySelector(
    selectedPreset: PlaybackLatencyPreset,
    enabled: Boolean,
    onSelectPreset: (PlaybackLatencyPreset) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.receiver_latency_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.receiver_latency_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PlaybackLatencyPreset.entries.forEach { preset ->
            val buttonText = stringResource(preset.labelResId)
            if (selectedPreset == preset) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectPreset(preset) },
                    enabled = enabled
                ) {
                    Text(buttonText)
                }
            } else {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectPreset(preset) },
                    enabled = enabled
                ) {
                    Text(buttonText)
                }
            }
        }
        Text(
            text = stringResource(
                R.string.receiver_latency_selected_format,
                stringResource(selectedPreset.labelResId)
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(selectedPreset.descriptionResId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SenderPrepareCard(
    onContinueSender: () -> Unit,
    onStop: () -> Unit
) {
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sender_prepare_card"),
        number = 1,
        title = stringResource(R.string.flow_sender_prepare_title),
        description = stringResource(R.string.flow_sender_prepare_description)
    ) {
        ChecklistBlock(
            items = listOf(
                stringResource(R.string.flow_checklist_item_network),
                stringResource(R.string.flow_checklist_item_permission),
                stringResource(R.string.flow_checklist_item_android)
            )
        )
        Text(
            text = stringResource(R.string.flow_sender_prepare_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            onClick = onContinueSender
        ) {
            Text(stringResource(R.string.flow_sender_prepare_primary))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStop
        ) {
            Text(stringResource(R.string.action_stop_session))
        }
    }
}

@Composable
private fun DiagnosingCard() {
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("path_diagnosing_step_card"),
        number = 2,
        title = stringResource(R.string.flow_diagnosing_title),
        description = stringResource(R.string.flow_diagnosing_description)
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.status_path_diagnosing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SenderShowInitCard(
    uiState: MainUiState,
    expirySeconds: Int,
    onCopyPayload: () -> Unit,
    onSharePayload: () -> Unit,
    onProcessConfirmPayload: (String) -> Unit
) {
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sender_init_step_card"),
        number = 2,
        title = stringResource(R.string.flow_sender_step_title),
        description = stringResource(R.string.flow_sender_step_description)
    ) {
        if (uiState.initPayload.isBlank()) {
            Text(
                text = stringResource(R.string.flow_sender_waiting_code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            if (expirySeconds > 0) {
                Text(
                    text = stringResource(R.string.status_qr_expiry_remaining, expirySeconds),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            PayloadDetailsSection(
                payloadValue = uiState.initPayload,
                onCopyPayload = onCopyPayload,
                onSharePayload = onSharePayload
            )
            PayloadInputSection(
                title = stringResource(R.string.flow_sender_received_payload_title),
                placeholder = stringResource(R.string.flow_sender_received_payload_placeholder),
                submitLabel = stringResource(R.string.flow_sender_apply_received_payload),
                textFieldTag = "sender_payload_input",
                onSubmit = onProcessConfirmPayload
            )
        }
    }
}

@Composable
private fun ListenerScanCard(
    transportMode: TransportMode,
    onProcessInitPayload: (String) -> Unit,
    onStop: () -> Unit
) {
    val titleRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_step_title
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_step_title
    }
    val descriptionRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_step_description
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_step_description
    }
    val waitingRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_waiting_code
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_waiting_code
    }
    val payloadTitleRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_payload_entry_title
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_payload_entry_title
    }
    val payloadPlaceholderRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_payload_entry_placeholder
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_payload_entry_placeholder
    }
    val submitLabelRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_apply_init_payload
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_apply_connection_code
    }
    val tipRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_scan_tip
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_scan_tip
    }
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("listener_scan_step_card"),
        number = 2,
        title = stringResource(titleRes),
        description = stringResource(descriptionRes)
    ) {
        Text(
            text = stringResource(waitingRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PayloadInputSection(
            title = stringResource(payloadTitleRes),
            placeholder = stringResource(payloadPlaceholderRes),
            submitLabel = stringResource(submitLabelRes),
            textFieldTag = "listener_payload_input",
            onSubmit = onProcessInitPayload
        )
        Text(
            text = stringResource(tipRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStop
        ) {
            Text(stringResource(R.string.action_stop_session))
        }
    }
}

@Composable
private fun ListenerShowConfirmCard(
    uiState: MainUiState,
    expirySeconds: Int,
    onCopyPayload: () -> Unit,
    onSharePayload: () -> Unit
) {
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("listener_confirm_step_card"),
        number = 3,
        title = stringResource(R.string.flow_receiver_confirm_title),
        description = stringResource(R.string.flow_receiver_confirm_description)
    ) {
        if (expirySeconds > 0) {
            Text(
                text = stringResource(R.string.status_qr_expiry_remaining, expirySeconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        VerificationCodeBlock(code = uiState.verificationCode)
        PayloadDetailsSection(
            payloadValue = uiState.confirmPayload,
            onCopyPayload = onCopyPayload,
            onSharePayload = onSharePayload
        )
    }
}

@Composable
private fun ListenerWaitingForConnectionCard(
    code: String,
    expirySeconds: Int,
    transportMode: TransportMode,
    onStop: () -> Unit
) {
    val titleRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_auto_connect_title
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_auto_connect_title
    }
    val descriptionRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_auto_connect_description
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_auto_connect_description
    }
    val hintRes = when (transportMode) {
        TransportMode.WEBRTC -> R.string.flow_receiver_auto_connect_hint
        TransportMode.UDP_OPUS -> R.string.flow_udp_receiver_auto_connect_hint
    }
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("listener_wait_connection_card"),
        number = 3,
        title = stringResource(titleRes),
        description = stringResource(descriptionRes)
    ) {
        if (expirySeconds > 0) {
            Text(
                text = stringResource(R.string.status_qr_expiry_remaining, expirySeconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        VerificationCodeBlock(code = code)
        Text(
            text = stringResource(hintRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStop
        ) {
            Text(stringResource(R.string.action_stop_session))
        }
    }
}

@Composable
private fun UdpListenerWaitingCard(onStop: () -> Unit) {
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("udp_listener_waiting_card"),
        number = 2,
        title = stringResource(R.string.flow_udp_listener_title),
        description = stringResource(R.string.flow_udp_listener_description)
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.flow_udp_listener_waiting_tip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStop
        ) {
            Text(stringResource(R.string.action_stop_session))
        }
    }
}

@Composable
private fun VerificationCard(
    code: String,
    onVerificationMatch: () -> Unit,
    onVerificationMismatch: () -> Unit
) {
    StepCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sender_verify_card"),
        number = 3,
        title = stringResource(R.string.flow_verification_title),
        description = stringResource(R.string.flow_verification_description)
    ) {
        VerificationCodeBlock(code = code)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("verification_match_button"),
                onClick = onVerificationMatch
            ) {
                Text(stringResource(R.string.flow_verification_match))
            }
            FilledTonalButton(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("verification_mismatch_button"),
                onClick = onVerificationMismatch
            ) {
                Text(stringResource(R.string.flow_verification_mismatch))
            }
        }
    }
}

@Composable
private fun ConnectedTipsCard(uiState: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.flow_connected_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(connectedTipRes(uiState)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TroubleshootingDetailsCard(uiState: MainUiState) {
    if (!uiState.connectionDiagnostics.hasContent() && uiState.failure == null && uiState.activeSessionId.isBlank()) {
        return
    }

    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.flow_details_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.flow_details_hide else R.string.flow_details_show
                        )
                    )
                }
            }

            if (expanded) {
                if (uiState.activeSessionId.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.status_session_id),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer {
                        Text(text = uiState.activeSessionId, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (uiState.connectionDiagnostics.hasContent()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.status_network_path_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(uiState.connectionDiagnostics.pathType.labelResId()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(
                            R.string.status_local_candidates_format,
                            uiState.connectionDiagnostics.localCandidatesCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.connectionDiagnostics.selectedCandidatePairType.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.status_selected_pair_format,
                                uiState.connectionDiagnostics.selectedCandidatePairType
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uiState.connectionDiagnostics.failureHint.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.status_failure_hint_format,
                                uiState.connectionDiagnostics.localizedHintText()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                uiState.failure?.let { failure ->
                    HorizontalDivider()
                    Text(
                        text = stringResource(
                            R.string.status_failure_format,
                            stringResource(failure.code.labelResId())
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleActionCard(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(testTag),
                enabled = enabled,
                onClick = onClick
            ) {
                Text(title)
            }
        }
    }
}

@Composable
private fun ChecklistBlock(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.flow_checklist_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        items.forEach { item ->
            Text(
                text = "• $item",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VerificationCodeBlock(code: String) {
    if (code.isBlank()) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.flow_verification_code_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = code,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StepCard(
    modifier: Modifier = Modifier,
    number: Int,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            content()
        }
    }
}

@Composable
private fun PayloadDetailsSection(
    payloadValue: String,
    onCopyPayload: () -> Unit,
    onSharePayload: (() -> Unit)? = null
) {
    if (payloadValue.isBlank()) {
        return
    }

    var expanded by remember(payloadValue) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.flow_payload_details_hide else R.string.flow_payload_details_show
                    )
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onSharePayload != null) {
                    TextButton(onClick = onSharePayload) {
                        Text(stringResource(R.string.flow_share_payload))
                    }
                }
                TextButton(onClick = onCopyPayload) {
                    Text(stringResource(R.string.flow_copy_payload))
                }
            }
        }
        Text(
            text = stringResource(R.string.flow_payload_chars, payloadValue.length),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (expanded) {
            SelectionContainer {
                Text(
                    text = payloadValue,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PayloadInputSection(
    title: String,
    placeholder: String,
    submitLabel: String,
    textFieldTag: String,
    onSubmit: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var payloadInput by rememberSaveable(title) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                payloadInput = clipboardManager.getText()?.text?.toString().orEmpty()
            }
        ) {
            Text(stringResource(R.string.flow_paste_from_clipboard))
        }
        OutlinedTextField(
            value = payloadInput,
            onValueChange = { payloadInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(textFieldTag),
            placeholder = { Text(placeholder) },
            minLines = 4,
            maxLines = 8
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            enabled = payloadInput.isNotBlank(),
            onClick = { onSubmit(payloadInput.trim()) }
        ) {
            Text(submitLabel)
        }
    }
}

@Composable
private fun rememberExpirySeconds(expiresAtUnixMs: Long): Int {
    var secondsRemaining by remember(expiresAtUnixMs) {
        mutableStateOf(expirySeconds(expiresAtUnixMs))
    }

    LaunchedEffect(expiresAtUnixMs) {
        if (expiresAtUnixMs <= 0L) {
            secondsRemaining = 0
            return@LaunchedEffect
        }

        while (true) {
            val remaining = expirySeconds(expiresAtUnixMs)
            secondsRemaining = remaining
            if (remaining <= 0) {
                break
            }
            delay(1_000)
        }
    }

    return secondsRemaining
}

private fun expirySeconds(expiresAtUnixMs: Long): Int {
    if (expiresAtUnixMs <= 0L) {
        return 0
    }
    val remainingMs = expiresAtUnixMs - System.currentTimeMillis()
    return if (remainingMs <= 0L) 0 else ((remainingMs + 999L) / 1_000L).toInt()
}

@Composable
private fun statusColor(state: AudioStreamState) = when (state) {
    AudioStreamState.STREAMING -> Color(0xFF176C2E)
    AudioStreamState.FAILED -> MaterialTheme.colorScheme.error
    AudioStreamState.CONNECTING,
    AudioStreamState.CAPTURING -> Color(0xFFAD5D0C)
    AudioStreamState.INTERRUPTED -> Color(0xFF8B4D16)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun currentRoleLabel(setupMode: SetupMode): String = when (setupMode) {
    SetupMode.SENDER -> "現在は送信側を案内しています"
    SetupMode.LISTENER -> "現在は受信側を案内しています"
    SetupMode.NONE -> "まずは送信側か受信側を選ぶだけで始められます"
}

private fun currentTransportLabel(transportMode: TransportMode): String = when (transportMode) {
    TransportMode.WEBRTC -> "現在の転送モード: WebRTC"
    TransportMode.UDP_OPUS -> "現在の転送モード: UDP + Opus"
}

private fun audioSourceLabel(diagnostics: AudioStreamDiagnostics): String = when (diagnostics.source) {
    AudioStreamSource.WEBRTC_RECEIVE -> "受信: WebRTC PCM"
    AudioStreamSource.UDP_OPUS_RECEIVE -> "受信: UDP + Opus"
    AudioStreamSource.NONE -> "受信: 待機中"
}

private fun bufferingModeLabel(diagnostics: AudioStreamDiagnostics): String {
    return if (diagnostics.targetPrebufferFrames > diagnostics.basePrebufferFrames) {
        "安定優先 (${diagnostics.basePrebufferFrames} → ${diagnostics.targetPrebufferFrames})"
    } else {
        "低遅延優先 (${diagnostics.targetPrebufferFrames} フレーム)"
    }
}

private fun channelLabel(channels: Int): String = when (channels) {
    1 -> "モノ"
    2 -> "ステレオ"
    else -> "${channels}ch"
}

private fun journeyLabels(setupMode: SetupMode, transportMode: TransportMode): List<Int> {
    if (transportMode == TransportMode.UDP_OPUS && setupMode == SetupMode.LISTENER) {
        return listOf(
            R.string.flow_tips_udp_listener_step_1,
            R.string.flow_tips_udp_listener_step_2,
            R.string.flow_tips_udp_listener_step_3
        )
    }
    return when (setupMode) {
        SetupMode.SENDER -> listOf(
            R.string.flow_tips_sender_step_1,
            R.string.flow_tips_sender_step_2,
            R.string.flow_tips_sender_step_3
        )
        SetupMode.LISTENER -> listOf(
            R.string.flow_tips_listener_step_1,
            R.string.flow_tips_listener_step_2,
            R.string.flow_tips_listener_step_3
        )
        SetupMode.NONE -> listOf(
            R.string.flow_tips_default_step_1,
            R.string.flow_tips_default_step_2,
            R.string.flow_tips_default_step_3
        )
    }
}

private fun journeyActiveIndex(uiState: MainUiState): Int = when (uiState.setupStep) {
    SetupStep.ENTRY -> 0
    SetupStep.SENDER_PREPARE -> 0
    SetupStep.PATH_DIAGNOSING,
    SetupStep.SENDER_SHOW_INIT,
    SetupStep.LISTENER_SCAN_INIT -> 1
    SetupStep.SENDER_VERIFY_CODE,
    SetupStep.LISTENER_SHOW_CONFIRM,
    SetupStep.LISTENER_WAIT_FOR_CONNECTION -> 2
    SetupStep.LISTENER_UDP_WAITING -> 1
}

private fun connectedTipRes(uiState: MainUiState): Int = when {
    uiState.streamState == AudioStreamState.STREAMING &&
        uiState.setupMode == SetupMode.LISTENER &&
        uiState.transportMode == TransportMode.UDP_OPUS -> R.string.flow_connected_udp_listener_tip
    uiState.streamState == AudioStreamState.STREAMING && uiState.setupMode == SetupMode.SENDER -> R.string.flow_connected_sender_tip
    uiState.streamState == AudioStreamState.STREAMING && uiState.setupMode == SetupMode.LISTENER -> R.string.flow_connected_listener_tip
    else -> R.string.flow_connected_generic_tip
}

private fun recommendedActionRes(uiState: MainUiState): Int {
    if (uiState.streamState == AudioStreamState.STREAMING) {
        return R.string.status_next_action_connected
    }
    if (uiState.streamState == AudioStreamState.INTERRUPTED) {
        return R.string.status_next_action_recovering
    }
    if (uiState.streamState == AudioStreamState.CONNECTING && uiState.setupStep == SetupStep.ENTRY) {
        return R.string.status_next_action_connecting
    }
    uiState.failure?.let { failure ->
        return when (failure.code) {
            FailureCode.PERMISSION_DENIED -> R.string.status_next_action_permission
            FailureCode.AUDIO_CAPTURE_NOT_SUPPORTED -> R.string.status_next_action_capture_not_supported
            FailureCode.USB_TETHER_UNAVAILABLE -> R.string.status_next_action_usb_enable_tethering
            FailureCode.USB_TETHER_DETECTED_BUT_NOT_REACHABLE -> R.string.status_next_action_usb_replug
            FailureCode.NETWORK_INTERFACE_NOT_USABLE -> R.string.status_next_action_check_interface
            else -> R.string.status_next_action_restart
        }
    }
    return when (uiState.setupStep) {
        SetupStep.ENTRY -> R.string.status_next_action_entry
        SetupStep.SENDER_PREPARE -> R.string.status_next_action_sender_prepare
        SetupStep.PATH_DIAGNOSING -> R.string.status_next_action_diagnosing
        SetupStep.SENDER_SHOW_INIT -> R.string.status_next_action_show_init
        SetupStep.SENDER_VERIFY_CODE -> R.string.status_next_action_verify
        SetupStep.LISTENER_SCAN_INIT -> when (uiState.transportMode) {
            TransportMode.WEBRTC -> R.string.status_next_action_scan_init
            TransportMode.UDP_OPUS -> R.string.status_next_action_udp_scan_init
        }
        SetupStep.LISTENER_SHOW_CONFIRM -> R.string.status_next_action_show_confirm
        SetupStep.LISTENER_WAIT_FOR_CONNECTION -> when (uiState.transportMode) {
            TransportMode.WEBRTC -> R.string.status_next_action_wait_connection_code
            TransportMode.UDP_OPUS -> R.string.status_next_action_udp_wait_connection_code
        }
        SetupStep.LISTENER_UDP_WAITING -> R.string.status_next_action_udp_waiting
    }
}

private fun AudioStreamState.labelResId(): Int = when (this) {
    AudioStreamState.IDLE -> R.string.state_idle
    AudioStreamState.CAPTURING -> R.string.state_capturing
    AudioStreamState.CONNECTING -> R.string.state_connecting
    AudioStreamState.STREAMING -> R.string.state_streaming
    AudioStreamState.INTERRUPTED -> R.string.state_interrupted
    AudioStreamState.FAILED -> R.string.state_failed
    AudioStreamState.ENDED -> R.string.state_ended
}

private fun FailureCode.labelResId(): Int = when (this) {
    FailureCode.PERMISSION_DENIED -> R.string.failure_code_permission_denied
    FailureCode.AUDIO_CAPTURE_NOT_SUPPORTED -> R.string.failure_code_audio_capture_not_supported
    FailureCode.WEBRTC_NEGOTIATION_FAILED -> R.string.failure_code_webrtc_negotiation_failed
    FailureCode.PEER_UNREACHABLE -> R.string.failure_code_peer_unreachable
    FailureCode.NETWORK_CHANGED -> R.string.failure_code_network_changed
    FailureCode.USB_TETHER_UNAVAILABLE -> R.string.failure_code_usb_tether_unavailable
    FailureCode.USB_TETHER_DETECTED_BUT_NOT_REACHABLE -> R.string.failure_code_usb_tether_not_reachable
    FailureCode.NETWORK_INTERFACE_NOT_USABLE -> R.string.failure_code_network_interface_not_usable
    FailureCode.SESSION_EXPIRED -> R.string.failure_code_session_expired
    FailureCode.INVALID_PAYLOAD -> R.string.failure_code_invalid_payload
}

private fun ConnectionDiagnostics.hasContent(): Boolean {
    return pathType != NetworkPathType.UNKNOWN ||
        localCandidatesCount > 0 ||
        selectedCandidatePairType.isNotBlank() ||
        failureHint.isNotBlank()
}

@Composable
private fun ConnectionDiagnostics.localizedHintText(): String = when (failureHint) {
    "usb_tether_check" -> stringResource(R.string.status_hint_usb_tether_check)
    "wifi_lan_check" -> stringResource(R.string.status_hint_wifi_check)
    "network_interface_check" -> stringResource(R.string.status_hint_network_interface_check)
    "peer_disconnected" -> stringResource(R.string.status_peer_disconnected)
    else -> stringResource(R.string.status_hint_generic_connection_check)
}

private fun NetworkPathType.labelResId(): Int = when (this) {
    NetworkPathType.WIFI_LAN -> R.string.status_network_path_wifi
    NetworkPathType.USB_TETHER -> R.string.status_network_path_usb
    NetworkPathType.UNKNOWN -> R.string.status_network_path_unknown
}
