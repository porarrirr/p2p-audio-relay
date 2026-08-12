package com.example.p2paudio.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.p2paudio.R
import com.example.p2paudio.audio.AndroidPcmPlayer
import com.example.p2paudio.audio.AndroidPcmSender
import com.example.p2paudio.audio.PlaybackBufferConfig
import com.example.p2paudio.audio.PlaybackLatencyPreset
import com.example.p2paudio.capture.AndroidAudioCaptureManager
import com.example.p2paudio.capture.AudioCaptureRuntime
import com.example.p2paudio.logging.AppLogger
import com.example.p2paudio.model.AudioStreamDiagnostics
import com.example.p2paudio.model.AudioStreamSource
import com.example.p2paudio.model.AudioStreamState
import com.example.p2paudio.model.ConnectionDiagnostics
import com.example.p2paudio.model.FailureCode
import com.example.p2paudio.model.NetworkPathType
import com.example.p2paudio.model.PairingConfirmPayload
import com.example.p2paudio.model.PairingInitPayload
import com.example.p2paudio.model.SessionFailure
import com.example.p2paudio.model.UdpConfirmPayload
import com.example.p2paudio.protocol.ConnectionCodeClient
import com.example.p2paudio.protocol.ConnectionCodeClientException
import com.example.p2paudio.protocol.ConnectionCodeCodec
import com.example.p2paudio.protocol.PairingPayloadValidator
import com.example.p2paudio.protocol.QrPayloadCodec
import com.example.p2paudio.protocol.VerificationCode
import com.example.p2paudio.service.AudioReceiveService
import com.example.p2paudio.service.AudioSendService
import com.example.p2paudio.transport.PairingAudioTransport
import com.example.p2paudio.transport.TransportMode
import com.example.p2paudio.transport.UdpListenerTransportException
import com.example.p2paudio.transport.UdpOpusListenerTransport
import com.example.p2paudio.webrtc.PeerConnectionController
import com.example.p2paudio.webrtc.WebRtcFactoryProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val captureRuntime = AudioCaptureRuntime
    private val receiverLatencyPreferences = application.getSharedPreferences(
        RECEIVER_LATENCY_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val initialReceiverLatencyPreset = loadReceiverLatencyPreset()
    private var pcmPlayer = createWebRtcPcmPlayer(initialReceiverLatencyPreset)
    private var udpPcmPlayer = createUdpPcmPlayer(initialReceiverLatencyPreset)
    private var pcmSender: AndroidPcmSender? = null
    private var playbackMessageShown = false
    private var waitingForCaptureServiceStart = false

    private val _uiState = MutableStateFlow(
        MainUiState(
            statusMessage = text(R.string.status_ready),
            receiverLatencyPreset = initialReceiverLatencyPreset
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _commands = MutableSharedFlow<UiCommand>()
    val commands: SharedFlow<UiCommand> = _commands.asSharedFlow()

    private val peerController: PairingAudioTransport = PeerConnectionController(
        factory = WebRtcFactoryProvider.create(application),
        stateListener = { state, message ->
            AppLogger.i(
                "MainViewModel",
                "stream_state_update",
                "Stream state updated",
                context = mapOf(
                    "state" to state.name,
                    "message" to (message ?: "")
                )
            )
            _uiState.update {
                val nextMessage = when {
                    state == AudioStreamState.STREAMING && it.statusMessage.isNotBlank() -> it.statusMessage
                    message != null -> localizePeerMessage(message)
                    else -> stateToStatusLabel(state)
                }
                it.copy(streamState = state, statusMessage = nextMessage)
            }
            when (state) {
                AudioStreamState.STREAMING -> {
                    if (_uiState.value.transportMode == TransportMode.WEBRTC &&
                        _uiState.value.setupMode == SetupMode.SENDER
                    ) {
                        startPcmSenderIfReady()
                    }
                }
                AudioStreamState.FAILED,
                AudioStreamState.ENDED -> stopPcmSender()
                else -> Unit
            }
        },
        pcmFrameListener = { frame ->
            onRemoteFrameReceived(frame, pcmPlayer, text(R.string.status_receiving_remote_audio))
        },
        diagnosticsListener = { diagnostics ->
            _uiState.update { it.copy(connectionDiagnostics = diagnostics) }
            AppLogger.d(
                "MainViewModel",
                "connection_diagnostics_update",
                "Connection diagnostics updated",
                context = mapOf(
                    "pathType" to diagnostics.pathType.name,
                    "localCandidatesCount" to diagnostics.localCandidatesCount,
                    "selectedPairType" to diagnostics.selectedCandidatePairType,
                    "failureHint" to diagnostics.failureHint
                )
            )
        }
    )
    private val udpListenerTransport = UdpOpusListenerTransport(
        context = application,
        stateListener = { state, message ->
            _uiState.update {
                it.copy(
                    streamState = state,
                    statusMessage = message ?: stateToStatusLabel(state)
                )
            }
        },
        pcmFrameListener = { frame, arrivalRealtimeMs ->
            onRemoteFrameReceived(
                frame = frame,
                player = udpPcmPlayer,
                statusMessage = text(R.string.status_udp_receiving_audio),
                arrivalRealtimeMs = arrivalRealtimeMs
            )
        },
        diagnosticsListener = { diagnostics ->
            _uiState.update { it.copy(connectionDiagnostics = diagnostics) }
        }
    )

    init {
        viewModelScope.launch {
            captureRuntime.events.collect { event ->
                when (event) {
                    AudioCaptureRuntime.Event.Started -> onCaptureServiceStarted()
                    is AudioCaptureRuntime.Event.StartFailed -> onCaptureServiceStartFailed(event.error)
                    AudioCaptureRuntime.Event.Stopped -> Unit
                }
            }
        }
    }

    fun beginSenderFlow() {
        if (_uiState.value.transportMode == TransportMode.UDP_OPUS) {
            val message = text(R.string.status_udp_android_sender_unavailable)
            resetToEntry(
                statusMessage = message,
                failure = null
            )
            return
        }
        AppLogger.i("MainViewModel", "sender_flow_guidance", "Sender guidance selected")
        _uiState.update {
            it.copy(
                setupMode = SetupMode.SENDER,
                setupStep = SetupStep.SENDER_PREPARE,
                payloadExpiresAtUnixMs = 0L,
                initPayload = "",
                confirmPayload = "",
                localSenderFingerprint = "",
                verificationCode = "",
                pendingAnswerSdp = "",
                activeSessionId = "",
                audioStreamDiagnostics = AudioStreamDiagnostics(),
                failure = null,
                statusMessage = text(R.string.status_sender_prepare)
            )
        }
    }

    fun beginListenerFlow() {
        if (_uiState.value.transportMode == TransportMode.UDP_OPUS) {
            beginUdpListenerFlow()
            return
        }
        AppLogger.i("MainViewModel", "listener_flow_start", "Listener flow selected")
        _uiState.update {
            it.copy(
                setupMode = SetupMode.LISTENER,
                setupStep = SetupStep.LISTENER_SCAN_INIT,
                payloadExpiresAtUnixMs = 0L,
                initPayload = "",
                confirmPayload = "",
                localSenderFingerprint = "",
                verificationCode = "",
                pendingAnswerSdp = "",
                activeSessionId = "",
                audioStreamDiagnostics = AudioStreamDiagnostics(),
                failure = null,
                statusMessage = text(R.string.status_listener_ready_to_scan)
            )
        }
    }

    fun startSenderFlowRequested() {
        if (_uiState.value.transportMode == TransportMode.UDP_OPUS) {
            beginSenderFlow()
            return
        }
        AppLogger.i("MainViewModel", "sender_flow_start", "Sender flow requested")
        if (!captureRuntime.isSupported()) {
            recoverToEntry(
                SessionFailure(
                    FailureCode.AUDIO_CAPTURE_NOT_SUPPORTED,
                    text(R.string.error_audio_capture_android10)
                )
            )
            return
        }

        _uiState.update {
            it.copy(
                setupMode = SetupMode.SENDER,
                setupStep = SetupStep.PATH_DIAGNOSING,
                payloadExpiresAtUnixMs = 0L,
                initPayload = "",
                confirmPayload = "",
                verificationCode = "",
                pendingAnswerSdp = "",
                activeSessionId = "",
                failure = null,
                statusMessage = text(R.string.status_path_diagnosing)
            )
        }

        if (hasRecordAudioPermission()) {
            requestProjectionPermission()
            return
        }
        AppLogger.i("MainViewModel", "request_record_audio_permission", "Requesting RECORD_AUDIO permission")
        viewModelScope.launch { _commands.emit(UiCommand.RequestRecordAudioPermission) }
    }

    fun selectTransportMode(mode: TransportMode) {
        val current = _uiState.value
        if (current.transportMode == mode) {
            return
        }
        if (current.streamState !in setOf(AudioStreamState.IDLE, AudioStreamState.ENDED, AudioStreamState.FAILED)) {
            return
        }
        if (current.setupStep != SetupStep.ENTRY) {
            return
        }
        _uiState.update {
            it.copy(
                transportMode = mode,
                statusMessage = when (mode) {
                    TransportMode.WEBRTC -> text(R.string.status_ready)
                    TransportMode.UDP_OPUS -> text(R.string.status_udp_ready)
                },
                audioStreamDiagnostics = AudioStreamDiagnostics(),
                failure = null
            )
        }
    }

    fun requestProjectionPermission() {
        AppLogger.i("MainViewModel", "request_projection_permission", "Requesting projection permission")
        val manager = getApplication<Application>().getSystemService(MediaProjectionManager::class.java)
        val captureIntent = manager.createScreenCaptureIntent()
        viewModelScope.launch { _commands.emit(UiCommand.RequestProjectionPermission(captureIntent)) }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        if (granted) {
            AppLogger.i("MainViewModel", "record_permission_granted", "RECORD_AUDIO permission granted")
            requestProjectionPermission()
            return
        }
        AppLogger.w("MainViewModel", "record_permission_denied", "RECORD_AUDIO permission denied")
        recoverToEntry(
            SessionFailure(
                FailureCode.PERMISSION_DENIED,
                text(R.string.error_permission_denied)
            )
        )
    }

    fun onProjectionPermissionResult(resultData: Intent?) {
        if (resultData == null) {
            AppLogger.w("MainViewModel", "projection_permission_denied", "Projection permission denied by user")
            recoverToEntry(
                SessionFailure(
                    FailureCode.PERMISSION_DENIED,
                    text(R.string.error_permission_denied)
                )
            )
            return
        }

        AppLogger.i("MainViewModel", "capture_service_start_requested", "Requesting capture foreground service start")
        waitingForCaptureServiceStart = true
        viewModelScope.launch { _commands.emit(UiCommand.StartProjectionService(resultData)) }
    }

    fun onProjectionServiceStartFailed(error: Throwable?) {
        val cause = error ?: IllegalStateException("Foreground service start failed")
        onCaptureServiceStartFailed(cause)
    }

    fun onUdpReceiveServiceStartFailed(error: Throwable) {
        AppLogger.e(
            "MainViewModel",
            "udp_receive_service_start_failed",
            "Failed to start receiver foreground service",
            context = mapOf("reason" to (error.message ?: "unknown")),
            throwable = error
        )
        recoverToEntry(
            SessionFailure(
                FailureCode.WEBRTC_NEGOTIATION_FAILED,
                error.message ?: text(R.string.error_webrtc_negotiation_failed)
            )
        )
    }

    private fun onCaptureServiceStarted() {
        if (!waitingForCaptureServiceStart) {
            return
        }
        waitingForCaptureServiceStart = false
        AppLogger.i("MainViewModel", "capture_started", "Audio capture started")
        _uiState.update {
            it.copy(
                streamState = AudioStreamState.CAPTURING,
                statusMessage = text(R.string.status_path_diagnosing)
            )
        }
        createInitPayload()
    }

    private fun onCaptureServiceStartFailed(error: Throwable) {
        waitingForCaptureServiceStart = false
        AppLogger.e(
            "MainViewModel",
            "capture_start_failed",
            "Failed to start audio capture from foreground service",
            context = mapOf("reason" to (error.message ?: "unknown")),
            throwable = error
        )
        val code = classifyCaptureStartFailure(error)
        recoverToEntry(
            SessionFailure(
                code,
                error.message ?: text(R.string.error_capture_start_failed)
            )
        )
    }

    private fun createInitPayload() {
        AppLogger.i("MainViewModel", "init_generation_start", "Creating local init payload")
        viewModelScope.launch {
            val result = peerController.createOfferSession()
            result.onSuccess { local ->
                val payload = PairingInitPayload(
                    sessionId = local.sessionId,
                    senderDeviceName = Build.MODEL ?: "android",
                    senderPubKeyFingerprint = local.localFingerprint,
                    offerSdp = local.offerSdp,
                    expiresAtUnixMs = System.currentTimeMillis() + PAYLOAD_TTL_MS
                )

                _uiState.update {
                    it.copy(
                        setupMode = SetupMode.SENDER,
                        setupStep = SetupStep.SENDER_SHOW_INIT,
                        payloadExpiresAtUnixMs = payload.expiresAtUnixMs,
                        activeSessionId = payload.sessionId,
                        localSenderFingerprint = local.localFingerprint,
                        initPayload = QrPayloadCodec.encodeInit(payload),
                        confirmPayload = "",
                        verificationCode = "",
                        pendingAnswerSdp = "",
                        statusMessage = text(R.string.status_init_generated),
                        failure = null
                    )
                }
                AppLogger.i(
                    "MainViewModel",
                    "init_generation_success",
                    "Init payload generated",
                    context = mapOf(
                        "sessionId" to payload.sessionId,
                        "offerLength" to payload.offerSdp.length,
                        "fingerprintHead" to local.localFingerprint.take(16)
                    )
                )
            }.onFailure {
                AppLogger.e(
                    "MainViewModel",
                    "init_generation_failed",
                    "Init payload generation failed",
                    context = mapOf("reason" to (it.message ?: "unknown")),
                    throwable = it
                )
                recoverToEntry(
                    negotiationFailure(it, text(R.string.error_create_offer_failed))
                )
            }
        }
    }

    fun selectReceiverLatencyPreset(preset: PlaybackLatencyPreset) {
        val current = _uiState.value
        if (current.receiverLatencyPreset == preset) {
            return
        }
        if (current.setupStep != SetupStep.ENTRY || current.streamState != AudioStreamState.IDLE) {
            return
        }

        AppLogger.i(
            "MainViewModel",
            "receiver_latency_preset_changed",
            "Updated Android receiver latency preset",
            context = mapOf(
                "from" to current.receiverLatencyPreset.name,
                "to" to preset.name
            )
        )

        pcmPlayer.stop()
        udpPcmPlayer.stop()
        playbackMessageShown = false
        pcmPlayer = createWebRtcPcmPlayer(preset)
        udpPcmPlayer = createUdpPcmPlayer(preset)
        saveReceiverLatencyPreset(preset)
        _uiState.update {
            it.copy(
                receiverLatencyPreset = preset,
                audioStreamDiagnostics = AudioStreamDiagnostics()
            )
        }
    }

    fun createConfirmFromInit(initRaw: String) {
        val transportMode = _uiState.value.transportMode
        AppLogger.i(
            "MainViewModel",
            "confirm_generation_start",
            "Creating confirm payload from listener input",
            context = mapOf(
                "inputLength" to initRaw.length,
                "transport" to if (ConnectionCodeCodec.looksLikeConnectionCode(initRaw)) "connection_code" else "init_payload"
            )
        )
        beginListenerFlow()
        viewModelScope.launch {
            if (transportMode == TransportMode.UDP_OPUS) {
                createUdpConfirmFromConnectionCode(initRaw)
                return@launch
            }

            if (ConnectionCodeCodec.looksLikeConnectionCode(initRaw)) {
                createConfirmFromConnectionCode(initRaw)
            } else {
                createConfirmFromInitPayload(initRaw)
            }
        }
    }

    private suspend fun createUdpConfirmFromConnectionCode(connectionCodeRaw: String) {
        if (!ConnectionCodeCodec.looksLikeConnectionCode(connectionCodeRaw)) {
            val message = text(R.string.error_udp_connection_code_required)
            resetToEntry(
                statusMessage = message,
                failure = SessionFailure(FailureCode.INVALID_PAYLOAD, message)
            )
            return
        }

        val connectionCode = try {
            ConnectionCodeCodec.decode(connectionCodeRaw)
        } catch (error: IllegalArgumentException) {
            AppLogger.w(
                "MainViewModel",
                "udp_connection_code_decode_failed",
                "UDP connection code decode failed",
                context = mapOf("reason" to (error.message ?: "unknown"))
            )
            val message = text(R.string.error_invalid_connection_code)
            resetToEntry(
                statusMessage = message,
                failure = SessionFailure(FailureCode.INVALID_PAYLOAD, message)
            )
            return
        }

        if (connectionCode.expiresAtUnixMs <= System.currentTimeMillis()) {
            recoverToEntry(SessionFailure(FailureCode.SESSION_EXPIRED, text(R.string.error_session_expired)))
            return
        }

        val initRaw = try {
            ConnectionCodeClient.fetchInitPayload(connectionCode)
        } catch (error: Exception) {
            val failure = if (error is ConnectionCodeClientException) {
                error.failure
            } else {
                SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_connection_code))
            }
            AppLogger.w(
                "MainViewModel",
                "udp_connection_code_fetch_failed",
                "Failed to fetch UDP init payload via connection code",
                context = mapOf(
                    "failureCode" to failure.code.name,
                    "reason" to failure.message
                )
            )
            recoverToEntry(failure)
            return
        }

        val init = try {
            QrPayloadCodec.decodeUdpInit(initRaw)
        } catch (error: Exception) {
            AppLogger.w(
                "MainViewModel",
                "udp_init_decode_failed",
                "Fetched UDP init payload decode failed",
                context = mapOf("reason" to (error.message ?: "unknown"))
            )
            recoverToEntry(
                SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_connection_code))
            )
            return
        }

        PairingPayloadValidator.validateUdpInit(init, System.currentTimeMillis())?.let { failure ->
            AppLogger.w(
                "MainViewModel",
                "udp_init_validation_failed",
                "Fetched UDP init payload validation failed",
                context = mapOf(
                    "sessionId" to init.sessionId,
                    "failureCode" to failure.code.name,
                    "reason" to failure.message
                )
            )
            recoverToEntry(failure)
            return
        }

        try {
            udpListenerTransport.startListening(advertiseService = false)
        } catch (error: UdpListenerTransportException) {
            recoverToEntry(error.failure)
            return
        } catch (error: Exception) {
            recoverToEntry(
                SessionFailure(
                    FailureCode.PEER_UNREACHABLE,
                    error.message ?: text(R.string.error_peer_unreachable)
                )
            )
            return
        }

        requestStartUdpReceiveService()

        val confirmPayload = UdpConfirmPayload(
            sessionId = init.sessionId,
            receiverDeviceName = Build.MODEL ?: "android",
            receiverPort = UdpOpusListenerTransport.UDP_PORT,
            expiresAtUnixMs = minOf(
                System.currentTimeMillis() + PAYLOAD_TTL_MS,
                connectionCode.expiresAtUnixMs
            )
        )
        val encodedConfirmPayload = QrPayloadCodec.encodeUdpConfirm(confirmPayload)

        try {
            ConnectionCodeClient.submitConfirmPayload(connectionCode, encodedConfirmPayload)
        } catch (error: Exception) {
            val failure = if (error is ConnectionCodeClientException) {
                error.failure
            } else {
                SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_connection_code))
            }
            AppLogger.w(
                "MainViewModel",
                "udp_connection_code_submit_failed",
                "Failed to submit UDP confirm payload via connection code",
                context = mapOf(
                    "sessionId" to init.sessionId,
                    "failureCode" to failure.code.name,
                    "reason" to failure.message
                )
            )
            recoverToEntry(failure)
            return
        }

        _uiState.update {
            it.copy(
                streamState = AudioStreamState.CONNECTING,
                setupMode = SetupMode.LISTENER,
                setupStep = SetupStep.LISTENER_WAIT_FOR_CONNECTION,
                payloadExpiresAtUnixMs = connectionCode.expiresAtUnixMs,
                activeSessionId = init.sessionId,
                confirmPayload = "",
                verificationCode = "",
                statusMessage = text(R.string.status_udp_connection_code_connecting),
                failure = null
            )
        }
        AppLogger.i(
            "MainViewModel",
            "udp_connection_code_confirm_submitted",
            "UDP confirm payload submitted to Windows peer via connection code",
            context = mapOf(
                "sessionId" to init.sessionId,
                "host" to connectionCode.host,
                "port" to connectionCode.port
            )
        )
    }

    private suspend fun createConfirmFromInitPayload(initRaw: String) {
        runCatching {
            val init = QrPayloadCodec.decodeInit(initRaw)
            PairingPayloadValidator.validateInit(init, System.currentTimeMillis())?.let { failure ->
                AppLogger.w(
                    "MainViewModel",
                    "init_validation_failed",
                    "Init payload validation failed",
                    context = mapOf(
                        "sessionId" to init.sessionId,
                        "failureCode" to failure.code.name,
                        "reason" to failure.message
                    )
                )
                recoverToEntry(failure, payloadRole = PayloadRole.INIT)
                return
            }

            val answerResult = peerController.createAnswerForOffer(init.offerSdp)
            answerResult.onSuccess { local ->
                val confirmPayload = PairingConfirmPayload(
                    sessionId = init.sessionId,
                    receiverDeviceName = Build.MODEL ?: "android",
                    receiverPubKeyFingerprint = local.localFingerprint,
                    answerSdp = local.answerSdp,
                    expiresAtUnixMs = System.currentTimeMillis() + PAYLOAD_TTL_MS
                )
                val verificationCode = VerificationCode.fromSessionAndFingerprints(
                    sessionId = init.sessionId,
                    senderFingerprint = init.senderPubKeyFingerprint,
                    receiverFingerprint = local.localFingerprint
                )
                _uiState.update {
                    it.copy(
                        setupMode = SetupMode.LISTENER,
                        setupStep = SetupStep.LISTENER_SHOW_CONFIRM,
                        payloadExpiresAtUnixMs = confirmPayload.expiresAtUnixMs,
                        activeSessionId = init.sessionId,
                        confirmPayload = QrPayloadCodec.encodeConfirm(confirmPayload),
                        verificationCode = verificationCode,
                        statusMessage = text(R.string.status_confirm_generated),
                        failure = null
                    )
                }
                AppLogger.i(
                    "MainViewModel",
                    "confirm_generation_success",
                    "Confirm payload generated",
                    context = mapOf(
                        "sessionId" to init.sessionId,
                        "answerLength" to confirmPayload.answerSdp.length,
                        "fingerprintHead" to local.localFingerprint.take(16)
                    )
                )
            }.onFailure {
                AppLogger.e(
                    "MainViewModel",
                    "confirm_generation_failed",
                    "Confirm payload generation failed",
                    context = mapOf("reason" to (it.message ?: "unknown")),
                    throwable = it
                )
                recoverToEntry(
                    negotiationFailure(it, text(R.string.error_create_answer_failed))
                )
            }
        }.onFailure {
            AppLogger.w(
                "MainViewModel",
                "init_decode_failed",
                "Init payload decode failed",
                context = mapOf("reason" to (it.message ?: "unknown"))
            )
            recoverToEntry(
                SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_init_payload)),
                payloadRole = PayloadRole.INIT
            )
        }
    }

    private suspend fun createConfirmFromConnectionCode(connectionCodeRaw: String) {
        val connectionCode = try {
            ConnectionCodeCodec.decode(connectionCodeRaw)
        } catch (error: IllegalArgumentException) {
            AppLogger.w(
                "MainViewModel",
                "connection_code_decode_failed",
                "Connection code decode failed",
                context = mapOf("reason" to (error.message ?: "unknown"))
            )
            val message = text(R.string.error_invalid_connection_code)
            resetToEntry(
                statusMessage = message,
                failure = SessionFailure(FailureCode.INVALID_PAYLOAD, message)
            )
            return
        }

        if (connectionCode.expiresAtUnixMs <= System.currentTimeMillis()) {
            recoverToEntry(SessionFailure(FailureCode.SESSION_EXPIRED, text(R.string.error_session_expired)))
            return
        }

        val initRaw = try {
            ConnectionCodeClient.fetchInitPayload(connectionCode)
        } catch (error: Exception) {
            val failure = if (error is ConnectionCodeClientException) {
                error.failure
            } else {
                SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_connection_code))
            }
            AppLogger.w(
                "MainViewModel",
                "connection_code_fetch_failed",
                "Failed to fetch init payload via connection code",
                context = mapOf(
                    "failureCode" to failure.code.name,
                    "reason" to failure.message
                )
            )
            recoverToEntry(failure)
            return
        }

        runCatching {
            val init = QrPayloadCodec.decodeInit(initRaw)
            PairingPayloadValidator.validateInit(init, System.currentTimeMillis())?.let { failure ->
                AppLogger.w(
                    "MainViewModel",
                    "connection_code_init_validation_failed",
                    "Fetched init payload validation failed",
                    context = mapOf(
                        "sessionId" to init.sessionId,
                        "failureCode" to failure.code.name,
                        "reason" to failure.message
                    )
                )
                recoverToEntry(failure, payloadRole = PayloadRole.INIT)
                return
            }

            val answerResult = peerController.createAnswerForOffer(init.offerSdp)
            answerResult.onSuccess { local ->
                val confirmPayload = PairingConfirmPayload(
                    sessionId = init.sessionId,
                    receiverDeviceName = Build.MODEL ?: "android",
                    receiverPubKeyFingerprint = local.localFingerprint,
                    answerSdp = local.answerSdp,
                    expiresAtUnixMs = System.currentTimeMillis() + PAYLOAD_TTL_MS
                )
                val encodedConfirmPayload = QrPayloadCodec.encodeConfirm(confirmPayload)
                val verificationCode = VerificationCode.fromSessionAndFingerprints(
                    sessionId = init.sessionId,
                    senderFingerprint = init.senderPubKeyFingerprint,
                    receiverFingerprint = local.localFingerprint
                )

                try {
                    ConnectionCodeClient.submitConfirmPayload(connectionCode, encodedConfirmPayload)
                } catch (error: Exception) {
                    val failure = if (error is ConnectionCodeClientException) {
                        error.failure
                    } else {
                        SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_connection_code))
                    }
                    AppLogger.w(
                        "MainViewModel",
                        "connection_code_submit_failed",
                        "Failed to submit confirm payload via connection code",
                        context = mapOf(
                            "sessionId" to init.sessionId,
                            "failureCode" to failure.code.name,
                            "reason" to failure.message
                        )
                    )
                    recoverToEntry(failure)
                    return
                }

                _uiState.update {
                    it.copy(
                        streamState = AudioStreamState.CONNECTING,
                        setupMode = SetupMode.LISTENER,
                        setupStep = SetupStep.LISTENER_WAIT_FOR_CONNECTION,
                        payloadExpiresAtUnixMs = connectionCode.expiresAtUnixMs,
                        activeSessionId = init.sessionId,
                        confirmPayload = "",
                        verificationCode = verificationCode,
                        statusMessage = text(R.string.status_connection_code_connecting),
                        failure = null
                    )
                }
                AppLogger.i(
                    "MainViewModel",
                    "connection_code_confirm_submitted",
                    "Confirm payload submitted to Windows peer via connection code",
                    context = mapOf(
                        "sessionId" to init.sessionId,
                        "host" to connectionCode.host,
                        "port" to connectionCode.port
                    )
                )
            }.onFailure {
                AppLogger.e(
                    "MainViewModel",
                    "connection_code_answer_failed",
                    "Confirm payload generation failed for connection code flow",
                    context = mapOf("reason" to (it.message ?: "unknown")),
                    throwable = it
                )
                recoverToEntry(
                    negotiationFailure(it, text(R.string.error_create_answer_failed))
                )
            }
        }.onFailure {
            AppLogger.w(
                "MainViewModel",
                "connection_code_init_decode_failed",
                "Fetched init payload decode failed",
                context = mapOf("reason" to (it.message ?: "unknown"))
            )
            recoverToEntry(
                SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_init_payload)),
                payloadRole = PayloadRole.INIT
            )
        }
    }

    fun applyConfirm(confirmRaw: String) {
        AppLogger.i(
            "MainViewModel",
            "confirm_apply_prepare",
            "Preparing remote confirm payload",
            context = mapOf(
                "confirmLength" to confirmRaw.length,
                "sessionId" to uiState.value.activeSessionId
            )
        )
        viewModelScope.launch {
            runCatching {
                val confirm = QrPayloadCodec.decodeConfirm(confirmRaw)
                val currentSession = uiState.value.activeSessionId
                PairingPayloadValidator.validateConfirm(
                    payload = confirm,
                    expectedSessionId = currentSession,
                    nowUnixMs = System.currentTimeMillis()
                )?.let { failure ->
                    AppLogger.w(
                        "MainViewModel",
                        "confirm_validation_failed",
                        "Confirm payload validation failed",
                        context = mapOf(
                            "sessionId" to currentSession,
                            "failureCode" to failure.code.name,
                            "reason" to failure.message
                        )
                    )
                    recoverToEntry(failure, payloadRole = PayloadRole.CONFIRM)
                    return@launch
                }

                val senderFingerprint = uiState.value.localSenderFingerprint
                if (senderFingerprint.isBlank()) {
                    recoverToEntry(
                        SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_confirm_payload)),
                        payloadRole = PayloadRole.CONFIRM
                    )
                    return@launch
                }

                val verificationCode = VerificationCode.fromSessionAndFingerprints(
                    sessionId = currentSession,
                    senderFingerprint = senderFingerprint,
                    receiverFingerprint = confirm.receiverPubKeyFingerprint
                )
                _uiState.update {
                    it.copy(
                        setupMode = SetupMode.SENDER,
                        setupStep = SetupStep.SENDER_VERIFY_CODE,
                        payloadExpiresAtUnixMs = 0L,
                        pendingAnswerSdp = confirm.answerSdp,
                        verificationCode = verificationCode,
                        statusMessage = text(R.string.status_verification_ready),
                        failure = null
                    )
                }
            }.onFailure {
                AppLogger.w(
                    "MainViewModel",
                    "confirm_decode_failed",
                    "Confirm payload decode failed",
                    context = mapOf("reason" to (it.message ?: "unknown"))
                )
                recoverToEntry(
                    SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_confirm_payload)),
                    payloadRole = PayloadRole.CONFIRM
                )
            }
        }
    }

    fun approveVerificationAndConnect() {
        val answerSdp = uiState.value.pendingAnswerSdp
        if (answerSdp.isBlank()) {
            recoverToEntry(
                SessionFailure(FailureCode.INVALID_PAYLOAD, text(R.string.error_invalid_confirm_payload)),
                payloadRole = PayloadRole.CONFIRM
            )
            return
        }
        AppLogger.i(
            "MainViewModel",
            "confirm_apply_start",
            "Applying confirmed remote answer",
            context = mapOf("sessionId" to uiState.value.activeSessionId)
        )
        viewModelScope.launch {
            peerController.applyRemoteAnswer(answerSdp)
                .onSuccess {
                    AppLogger.i(
                        "MainViewModel",
                        "confirm_apply_success",
                        "Remote answer applied after verification",
                        context = mapOf("sessionId" to uiState.value.activeSessionId)
                    )
                    _uiState.update {
                        it.copy(
                                setupMode = SetupMode.SENDER,
                                setupStep = SetupStep.ENTRY,
                                payloadExpiresAtUnixMs = 0L,
                                initPayload = "",
                                confirmPayload = "",
                                pendingAnswerSdp = "",
                                statusMessage = text(R.string.status_answer_applied)
                        )
                    }
                }
                .onFailure {
                    AppLogger.e(
                        "MainViewModel",
                        "confirm_apply_failed",
                        "Remote answer apply failed",
                        context = mapOf("reason" to (it.message ?: "unknown")),
                        throwable = it
                    )
                    recoverToEntry(
                        negotiationFailure(it, text(R.string.error_apply_answer_failed))
                    )
                }
        }
    }

    fun rejectVerificationAndRestart() {
        val mismatch = text(R.string.status_verification_mismatch)
        resetToEntry(
            statusMessage = mismatch,
            failure = SessionFailure(FailureCode.INVALID_PAYLOAD, mismatch)
        )
    }

    fun stopSession() {
        AppLogger.i(
            "MainViewModel",
            "session_stop",
            "Stopping current session",
            context = mapOf("sessionId" to uiState.value.activeSessionId)
        )
        waitingForCaptureServiceStart = false
        stopPcmSender()
        pcmPlayer.stop()
        udpPcmPlayer.stop()
        playbackMessageShown = false
        captureRuntime.stop()
        peerController.close()
        udpListenerTransport.close()
        stopUdpReceiveServiceDirectly()
        requestStopUdpReceiveService()
        stopProjectionServiceDirectly()
        requestStopProjectionService()
        _uiState.value = entryState(statusMessage = text(R.string.status_session_ended))
    }

    override fun onCleared() {
        stopSession()
        super.onCleared()
    }

    private fun startPcmSenderIfReady() {
        if (pcmSender != null) {
            AppLogger.d("MainViewModel", "sender_already_running", "PCM sender already running")
            return
        }
        val audioRecord = captureRuntime.currentAudioRecord() ?: return
        val sender = AndroidPcmSender(
            audioRecord = audioRecord,
            sampleRate = AndroidAudioCaptureManager.SAMPLE_RATE,
            channels = 2,
            bitsPerSample = 16,
            frameDurationMs = 20,
            sendFrame = { frame -> peerController.sendPcmFrame(frame) }
        )
        sender.start()
        pcmSender = sender
        AppLogger.i("MainViewModel", "sender_started", "PCM sender started")
        _uiState.update { it.copy(statusMessage = text(R.string.status_streaming_captured_audio)) }
    }

    private fun stopPcmSender() {
        if (pcmSender != null) {
            AppLogger.i("MainViewModel", "sender_stopped", "PCM sender stopped")
        }
        pcmSender?.stop()
        pcmSender = null
    }

    private fun recoverToEntry(failure: SessionFailure, payloadRole: PayloadRole? = null) {
        val localizedMessage = localizeFailureMessage(failure, payloadRole)
        AppLogger.e(
            "MainViewModel",
            "session_failure",
            "Recoverable setup failure; reset to entry",
            context = mapOf(
                "code" to failure.code.name,
                "message" to localizedMessage,
                "sessionId" to uiState.value.activeSessionId
            )
        )
        resetToEntry(
            statusMessage = localizedMessage,
            failure = failure.copy(message = localizedMessage)
        )
    }

    private fun resetToEntry(statusMessage: String, failure: SessionFailure?) {
        waitingForCaptureServiceStart = false
        stopPcmSender()
        pcmPlayer.stop()
        udpPcmPlayer.stop()
        playbackMessageShown = false
        captureRuntime.stop()
        peerController.close()
        udpListenerTransport.close()
        stopUdpReceiveServiceDirectly()
        requestStopUdpReceiveService()
        stopProjectionServiceDirectly()
        requestStopProjectionService()
        _uiState.value = entryState(statusMessage = statusMessage, failure = failure)
    }

    private fun stopProjectionServiceDirectly() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, AudioSendService::class.java))
    }

    private fun stopUdpReceiveServiceDirectly() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, AudioReceiveService::class.java))
    }

    private fun requestStopProjectionService() {
        viewModelScope.launch { _commands.emit(UiCommand.StopProjectionService) }
    }

    private fun requestStartUdpReceiveService() {
        viewModelScope.launch { _commands.emit(UiCommand.StartUdpReceiveService) }
    }

    private fun requestStopUdpReceiveService() {
        viewModelScope.launch { _commands.emit(UiCommand.StopUdpReceiveService) }
    }

    private fun localizePeerMessage(message: String): String = when (message) {
        "Peer disconnected" -> text(R.string.status_peer_disconnected)
        "ICE connection failed" -> text(R.string.status_ice_connection_failed)
        else -> message
    }

    private fun stateToStatusLabel(state: AudioStreamState): String = when (state) {
        AudioStreamState.IDLE -> text(R.string.state_idle)
        AudioStreamState.CAPTURING -> text(R.string.state_capturing)
        AudioStreamState.CONNECTING -> text(R.string.state_connecting)
        AudioStreamState.STREAMING -> text(R.string.state_streaming)
        AudioStreamState.INTERRUPTED -> text(R.string.state_interrupted)
        AudioStreamState.FAILED -> text(R.string.state_failed)
        AudioStreamState.ENDED -> text(R.string.state_ended)
    }

    private fun localizeFailureMessage(failure: SessionFailure, payloadRole: PayloadRole?): String {
        return when (failure.code) {
            FailureCode.PERMISSION_DENIED -> text(R.string.error_permission_denied)
            FailureCode.AUDIO_CAPTURE_NOT_SUPPORTED -> text(R.string.error_audio_capture_not_supported)
            FailureCode.WEBRTC_NEGOTIATION_FAILED -> text(R.string.error_webrtc_negotiation_failed)
            FailureCode.PEER_UNREACHABLE -> text(R.string.error_peer_unreachable)
            FailureCode.NETWORK_CHANGED -> text(R.string.error_network_changed)
            FailureCode.USB_TETHER_UNAVAILABLE -> text(R.string.error_usb_tether_unavailable)
            FailureCode.USB_TETHER_DETECTED_BUT_NOT_REACHABLE -> text(R.string.error_usb_tether_not_reachable)
            FailureCode.NETWORK_INTERFACE_NOT_USABLE -> text(R.string.error_network_interface_not_usable)
            FailureCode.SESSION_EXPIRED -> text(R.string.error_session_expired)
            FailureCode.INVALID_PAYLOAD -> when (payloadRole) {
                PayloadRole.INIT -> text(R.string.error_invalid_init_payload)
                PayloadRole.CONFIRM -> text(R.string.error_invalid_confirm_payload)
                null -> text(R.string.error_invalid_payload)
            }
        }
    }

    private fun negotiationFailure(error: Throwable, fallbackMessage: String): SessionFailure {
        val diagnostics = _uiState.value.connectionDiagnostics
        val message = error.message ?: fallbackMessage
        if (diagnostics.pathType == NetworkPathType.USB_TETHER && diagnostics.localCandidatesCount == 0) {
            return SessionFailure(FailureCode.USB_TETHER_UNAVAILABLE, text(R.string.error_usb_tether_unavailable))
        }
        if (diagnostics.pathType == NetworkPathType.USB_TETHER) {
            return SessionFailure(FailureCode.USB_TETHER_DETECTED_BUT_NOT_REACHABLE, text(R.string.error_usb_tether_not_reachable))
        }
        if (diagnostics.localCandidatesCount == 0) {
            return SessionFailure(FailureCode.NETWORK_INTERFACE_NOT_USABLE, text(R.string.error_network_interface_not_usable))
        }
        return SessionFailure(FailureCode.WEBRTC_NEGOTIATION_FAILED, message)
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String {
        return getApplication<Application>().getString(resId, *args)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun beginUdpListenerFlow() {
        AppLogger.i("MainViewModel", "udp_listener_flow_start", "UDP Opus listener guidance selected")
        waitingForCaptureServiceStart = false
        stopPcmSender()
        pcmPlayer.stop()
        udpPcmPlayer.stop()
        playbackMessageShown = false
        captureRuntime.stop()
        peerController.close()
        stopUdpReceiveServiceDirectly()
        requestStopUdpReceiveService()
        stopProjectionServiceDirectly()
        requestStopProjectionService()
        _uiState.update {
            it.copy(
                setupMode = SetupMode.LISTENER,
                setupStep = SetupStep.LISTENER_SCAN_INIT,
                payloadExpiresAtUnixMs = 0L,
                initPayload = "",
                confirmPayload = "",
                localSenderFingerprint = "",
                verificationCode = "",
                pendingAnswerSdp = "",
                activeSessionId = "",
                audioStreamDiagnostics = AudioStreamDiagnostics(),
                failure = null,
                streamState = AudioStreamState.IDLE,
                statusMessage = text(R.string.status_udp_listener_ready_to_scan)
            )
        }
    }

    private fun onRemoteFrameReceived(
        frame: com.example.p2paudio.audio.PcmFrame,
        player: AndroidPcmPlayer,
        statusMessage: String,
        arrivalRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        player.enqueue(frame, arrivalRealtimeMs)
        if (!playbackMessageShown) {
            playbackMessageShown = true
            AppLogger.i(
                "MainViewModel",
                "receiver_first_frame",
                "First remote audio frame received",
                context = mapOf(
                    "sampleRate" to frame.sampleRate,
                    "channels" to frame.channels,
                    "bitsPerSample" to frame.bitsPerSample
                )
            )
            _uiState.update { it.copy(statusMessage = statusMessage) }
        }
    }

    private fun onAudioStreamDiagnosticsChanged(diagnostics: AudioStreamDiagnostics) {
        _uiState.update { it.copy(audioStreamDiagnostics = diagnostics) }
    }

    private fun entryState(statusMessage: String, failure: SessionFailure? = null): MainUiState {
        return MainUiState(
            statusMessage = statusMessage,
            failure = failure,
            transportMode = _uiState.value.transportMode,
            receiverLatencyPreset = _uiState.value.receiverLatencyPreset
        )
    }

    private fun createWebRtcPcmPlayer(preset: PlaybackLatencyPreset): AndroidPcmPlayer {
        return createPcmPlayer(
            source = AudioStreamSource.WEBRTC_RECEIVE,
            config = preset.webrtcConfig
        )
    }

    private fun createUdpPcmPlayer(preset: PlaybackLatencyPreset): AndroidPcmPlayer {
        return createPcmPlayer(
            source = AudioStreamSource.UDP_OPUS_RECEIVE,
            config = preset.udpOpusConfig
        )
    }

    private fun createPcmPlayer(
        source: AudioStreamSource,
        config: PlaybackBufferConfig
    ): AndroidPcmPlayer {
        return AndroidPcmPlayer(
            source = source,
            startupPrebufferFrames = config.startupPrebufferFrames,
            steadyPrebufferFrames = config.steadyPrebufferFrames,
            maxQueueFrames = config.maxQueueFrames,
            minTrackBufferFrames = config.minTrackBufferFrames,
            diagnosticsListener = ::onAudioStreamDiagnosticsChanged
        )
    }

    private fun loadReceiverLatencyPreset(): PlaybackLatencyPreset {
        return PlaybackLatencyPreset.fromStorageValue(
            receiverLatencyPreferences.getString(RECEIVER_LATENCY_PREFERENCE_KEY, null)
        )
    }

    private fun saveReceiverLatencyPreset(preset: PlaybackLatencyPreset) {
        receiverLatencyPreferences
            .edit()
            .putString(RECEIVER_LATENCY_PREFERENCE_KEY, preset.name)
            .apply()
    }

    sealed interface UiCommand {
        object RequestRecordAudioPermission : UiCommand
        data class RequestProjectionPermission(val captureIntent: Intent) : UiCommand
        data class StartProjectionService(val permissionResultData: Intent) : UiCommand
        object StartUdpReceiveService : UiCommand
        object StopProjectionService : UiCommand
        object StopUdpReceiveService : UiCommand
    }

    private enum class PayloadRole {
        INIT,
        CONFIRM
    }

    companion object {
        private const val PAYLOAD_TTL_MS = 600_000L
        private const val RECEIVER_LATENCY_PREFERENCES_NAME = "playback_preferences"
        private const val RECEIVER_LATENCY_PREFERENCE_KEY = "receiver_latency_preset"
    }
}

internal fun classifyCaptureStartFailure(error: Throwable?): FailureCode = when {
    isRecordPermissionFailure(error) -> FailureCode.PERMISSION_DENIED
    isUnsupportedCaptureFailure(error) -> FailureCode.AUDIO_CAPTURE_NOT_SUPPORTED
    else -> FailureCode.WEBRTC_NEGOTIATION_FAILED
}

private fun isRecordPermissionFailure(error: Throwable?): Boolean {
    if (error == null) return false
    val message = error.message.orEmpty()
    if (error is SecurityException) return true
    if (message.contains("RECORD_AUDIO", ignoreCase = true)) return true
    if (message.contains("Media projections require a foreground service", ignoreCase = true)) return true
    if (message.contains("FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION", ignoreCase = true)) return true
    return isRecordPermissionFailure(error.cause)
}

private fun isUnsupportedCaptureFailure(error: Throwable?): Boolean {
    if (error == null) return false
    val message = error.message.orEmpty()
    if (message.contains("AudioPlaybackCapture requires Android 10+", ignoreCase = true)) return true
    return isUnsupportedCaptureFailure(error.cause)
}
