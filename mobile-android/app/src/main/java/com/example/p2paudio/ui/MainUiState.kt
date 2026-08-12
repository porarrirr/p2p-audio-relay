package com.example.p2paudio.ui

import com.example.p2paudio.audio.PlaybackLatencyPreset
import com.example.p2paudio.model.AudioStreamState
import com.example.p2paudio.model.AudioStreamDiagnostics
import com.example.p2paudio.model.ConnectionDiagnostics
import com.example.p2paudio.model.SessionFailure
import com.example.p2paudio.transport.TransportMode

data class MainUiState(
    val streamState: AudioStreamState = AudioStreamState.IDLE,
    val statusMessage: String = "",
    val transportMode: TransportMode = TransportMode.WEBRTC,
    val setupMode: SetupMode = SetupMode.NONE,
    val setupStep: SetupStep = SetupStep.ENTRY,
    val payloadExpiresAtUnixMs: Long = 0L,
    val initPayload: String = "",
    val confirmPayload: String = "",
    val localSenderFingerprint: String = "",
    val verificationCode: String = "",
    val pendingAnswerSdp: String = "",
    val activeSessionId: String = "",
    val receiverLatencyPreset: PlaybackLatencyPreset = PlaybackLatencyPreset.default,
    val failure: SessionFailure? = null,
    val audioStreamDiagnostics: AudioStreamDiagnostics = AudioStreamDiagnostics(),
    val connectionDiagnostics: ConnectionDiagnostics = ConnectionDiagnostics()
)

enum class SetupMode {
    NONE,
    SENDER,
    LISTENER
}

enum class SetupStep {
    ENTRY,
    SENDER_PREPARE,
    PATH_DIAGNOSING,
    SENDER_SHOW_INIT,
    SENDER_VERIFY_CODE,
    LISTENER_SCAN_INIT,
    LISTENER_SHOW_CONFIRM,
    LISTENER_WAIT_FOR_CONNECTION,
    LISTENER_UDP_WAITING
}
