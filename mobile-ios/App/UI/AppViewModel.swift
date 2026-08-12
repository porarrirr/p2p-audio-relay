import Foundation
import UIKit

@MainActor
final class AppViewModel: ObservableObject {
    enum SetupStep {
        case entry
        case senderShowInit
        case senderVerifyCode
        case listenerInput
        case listenerShowConfirm
        case listenerWaitForConnection
    }

    @Published var streamState: AudioStreamState = .idle
    @Published var statusMessage: String = L10n.tr("status.ready")
    @Published var setupStep: SetupStep = .entry
    @Published var transportMode: TransportMode = .webRtc
    @Published var receiverLatencyPreset: PlaybackLatencyPreset
    @Published var initPayloadRaw: String = ""
    @Published var confirmPayloadRaw: String = ""
    @Published var verificationCode: String = ""
    @Published var activeSessionId: String = ""
    @Published var payloadExpiresAtUnixMs: Int64 = 0
    @Published var needsBroadcastStartHint = false
    @Published var connectionDiagnostics = ConnectionDiagnostics(pathType: NetworkPathClassifier.classifyFromLocalInterfaces())
    @Published var audioStreamDiagnostics = AudioStreamDiagnostics()
    @Published var listenerInputRaw: String = ""

    let logStore: LogStore

    private lazy var sessionController: WebRTCSessionController = {
        WebRTCSessionController(
            stateHandler: { [weak self] state, message in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.log(
                        .info,
                        "AppViewModel",
                        "WebRTC state update",
                        metadata: [
                            "state": state.rawValue,
                            "message": message ?? ""
                        ]
                    )
                    self.streamState = state
                    if state == .streaming && self.didShowReceivingMessage {
                        self.statusMessage = L10n.tr("status.receiving_remote_audio")
                    } else {
                        self.statusMessage = message ?? self.stateLabel(state)
                    }
                }
            },
            pcmFrameHandler: { [weak self] frame in
                Task { @MainActor [weak self] in
                    self?.onRemoteFrameReceived(
                        frame,
                        player: self?.webRtcPlayer,
                        statusMessage: L10n.tr("status.receiving_remote_audio"),
                        arrivalRealtimeMs: Self.realtimeNowMs()
                    )
                }
            },
            logHandler: { [weak self] level, category, message, metadata in
                Task { @MainActor [weak self] in
                    self?.log(level, category, message, metadata: metadata)
                }
            },
            diagnosticsHandler: { [weak self] diagnostics in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.connectionDiagnostics = diagnostics
                    self.log(
                        .debug,
                        "AppViewModel",
                        "Connection diagnostics updated",
                        metadata: [
                            "pathType": diagnostics.pathType.rawValue,
                            "localCandidatesCount": String(diagnostics.localCandidatesCount),
                            "selectedPairType": diagnostics.selectedCandidatePairType,
                            "failureHint": diagnostics.failureHint
                        ]
                    )
                }
            }
        )
    }()

    private lazy var replayKitController = ReplayKitAudioCaptureController { [weak self] level, category, message, metadata in
        Task { @MainActor [weak self] in
            self?.log(level, category, message, metadata: metadata)
        }
    }

    private lazy var webRtcPlayer: PcmPlayer = makeWebRtcPlayer(receiverLatencyPreset)
    private lazy var udpPlayer: PcmPlayer = makeUdpPlayer(receiverLatencyPreset)
    private lazy var udpListenerTransport = UdpOpusListenerTransport(
        stateHandler: { [weak self] state, message in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.streamState = state
                self.statusMessage = message ?? self.stateLabel(state)
            }
        },
        pcmFrameHandler: { [weak self] frame, arrivalRealtimeMs in
            Task { @MainActor [weak self] in
                self?.onRemoteFrameReceived(
                    frame,
                    player: self?.udpPlayer,
                    statusMessage: L10n.tr("status.udp_receiving_audio"),
                    arrivalRealtimeMs: arrivalRealtimeMs
                )
            }
        },
        diagnosticsHandler: { [weak self] diagnostics in
            Task { @MainActor [weak self] in
                self?.connectionDiagnostics = diagnostics
            }
        }
    )

    private var isConsumingReplayKitFrames = false
    private var didShowReceivingMessage = false
    private var localSenderFingerprint = ""
    private var pendingAnswerSdp = ""
    private var senderStartRequestToken = 0

    init(logStore: LogStore? = nil) {
        self.logStore = logStore ?? LogStore()
        self.receiverLatencyPreset = PlaybackLatencyPreset.fromStorageValue(
            UserDefaults.standard.string(forKey: Self.receiverLatencyStorageKey)
        )
    }

    func beginListenerFlow() {
        log(.info, "AppViewModel", "Listener flow selected", metadata: ["transportMode": transportMode.rawValue])
        prepareListenerInputState(input: "")
    }

    func startSenderFlow() {
        if transportMode == .udpOpus {
            resetToEntry(status: L10n.tr("status.udp_sender_unavailable"), asFailure: true)
            return
        }

        invalidatePendingSenderStart()
        let requestToken = senderStartRequestToken
        log(.info, "AppViewModel", "Start sender flow requested")
        attemptStartSenderFlow(requestToken: requestToken, attempt: 0)
    }

    func selectTransportMode(_ mode: TransportMode) {
        guard transportMode != mode else {
            return
        }
        guard setupStep == .entry, [.idle, .ended, .failed].contains(streamState) else {
            return
        }

        transportMode = mode
        statusMessage = mode == .webRtc ? L10n.tr("status.ready") : L10n.tr("status.udp_ready")
        connectionDiagnostics = ConnectionDiagnostics(pathType: NetworkPathClassifier.classifyFromLocalInterfaces())
        audioStreamDiagnostics = AudioStreamDiagnostics()
    }

    func selectReceiverLatencyPreset(_ preset: PlaybackLatencyPreset) {
        guard receiverLatencyPreset != preset else {
            return
        }
        guard setupStep == .entry, [.idle, .ended, .failed].contains(streamState) else {
            return
        }

        webRtcPlayer.stop()
        udpPlayer.stop()
        didShowReceivingMessage = false
        receiverLatencyPreset = preset
        webRtcPlayer = makeWebRtcPlayer(preset)
        udpPlayer = makeUdpPlayer(preset)
        audioStreamDiagnostics = AudioStreamDiagnostics()
        UserDefaults.standard.set(preset.rawValue, forKey: Self.receiverLatencyStorageKey)
    }

    func createConfirm(from rawInput: String) {
        let trimmed = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            let fallbackKey = transportMode == .udpOpus
                ? "error.connection_code_required"
                : "error.invalid_init_payload"
            resetToEntry(status: L10n.tr(fallbackKey), asFailure: true)
            return
        }

        listenerInputRaw = trimmed
        prepareListenerInputState(input: trimmed)

        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                switch self.transportMode {
                case .webRtc:
                    if ConnectionCodeCodec.looksLikeConnectionCode(trimmed) {
                        try await self.createConfirmFromConnectionCode(trimmed)
                    } else {
                        try await self.createConfirmFromInitPayload(trimmed)
                    }
                case .udpOpus:
                    try await self.createUdpConfirmFromConnectionCode(trimmed)
                }
            } catch {
                self.handleListenerFlowError(error, inputLength: trimmed.count)
            }
        }
    }

    func prepareConfirmForVerification(from confirmRaw: String) {
        log(
            .info,
            "AppViewModel",
            "Prepare confirm for verification",
            metadata: [
                "confirmLength": String(confirmRaw.count),
                "sessionId": activeSessionId
            ]
        )

        do {
            let payload = try QrPayloadCodec.decodeConfirm(confirmRaw)
            try PairingPayloadValidator.validateConfirm(
                payload,
                expectedSessionId: activeSessionId,
                nowUnixMs: Self.nowMs()
            )
            guard !localSenderFingerprint.isEmpty else {
                resetToEntry(status: L10n.tr("error.invalid_confirm_payload"), asFailure: true)
                return
            }

            verificationCode = VerificationCode.fromSessionAndFingerprints(
                sessionId: activeSessionId,
                senderFingerprint: localSenderFingerprint,
                receiverFingerprint: payload.receiverPubKeyFingerprint
            )
            pendingAnswerSdp = payload.answerSdp
            setupStep = .senderVerifyCode
            payloadExpiresAtUnixMs = 0
            statusMessage = L10n.tr("status.verification_ready")
        } catch {
            resetToEntry(status: message(from: error, fallbackKey: "error.invalid_confirm_payload"), asFailure: true)
        }
    }

    func approveVerificationAndConnect() {
        guard !pendingAnswerSdp.isEmpty else {
            resetToEntry(status: L10n.tr("error.invalid_confirm_payload"), asFailure: true)
            return
        }

        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.applyAnswerAsync(self.pendingAnswerSdp)
                self.pendingAnswerSdp = ""
                self.setupStep = .senderShowInit
                self.payloadExpiresAtUnixMs = 0
                self.statusMessage = L10n.tr("status.answer_applied")
            } catch {
                let failure = self.negotiationFailure(error: error, fallbackKey: "error.apply_answer_failed")
                self.resetToEntry(status: failure.message, asFailure: true)
            }
        }
    }

    func rejectVerificationAndRestart() {
        resetToEntry(status: L10n.tr("status.verification_mismatch"), asFailure: true)
    }

    func endSession() {
        log(
            .info,
            "AppViewModel",
            "Ending session",
            metadata: ["sessionId": activeSessionId]
        )
        resetToEntry(status: L10n.tr("status.session_ended"), asFailure: false)
    }

    private func attemptStartSenderFlow(requestToken: Int, attempt: Int) {
        guard requestToken == senderStartRequestToken else {
            return
        }

        let snapshot = replayKitController.refreshBroadcastState()
        guard snapshot.isActive else {
            if attempt < Self.senderStartRetryAttempts {
                if attempt == 0 {
                    log(
                        .info,
                        "AppViewModel",
                        "ReplayKit broadcast inactive, retrying",
                        metadata: ["maxRetryAttempts": String(Self.senderStartRetryAttempts)]
                    )
                }
                DispatchQueue.main.asyncAfter(
                    deadline: .now() + .milliseconds(Self.senderStartRetryIntervalMs)
                ) { [weak self] in
                    self?.attemptStartSenderFlow(requestToken: requestToken, attempt: attempt + 1)
                }
                return
            }

            streamState = .idle
            setupStep = .entry
            needsBroadcastStartHint = true
            statusMessage = L10n.tr("status.start_broadcast_first")
            log(
                .warning,
                "AppViewModel",
                "ReplayKit broadcast is not active",
                metadata: [
                    "attempts": String(attempt + 1),
                    "hasSharedDefaults": String(snapshot.hasSharedDefaults),
                    "hasSharedContainer": String(snapshot.hasSharedContainer)
                ]
            )
            return
        }

        if attempt > 0 {
            log(
                .info,
                "AppViewModel",
                "ReplayKit broadcast became active after retry",
                metadata: ["attempts": String(attempt + 1)]
            )
        }

        continueSenderFlowAfterReplayKitCheck()
    }

    private func continueSenderFlowAfterReplayKitCheck() {
        shutdownActiveRuntime(clearBroadcastFlag: false)
        needsBroadcastStartHint = false
        setupStep = .senderShowInit
        streamState = .capturing
        statusMessage = L10n.tr("status.capturing_replaykit_audio")

        if !isConsumingReplayKitFrames {
            log(.info, "AppViewModel", "Start consuming ReplayKit frames")
            replayKitController.startConsumingFrames { [weak self] frame in
                Task { @MainActor [weak self] in
                    _ = self?.sessionController.sendPcmFrame(frame)
                }
            }
            isConsumingReplayKitFrames = true
        }

        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let local = try await self.createOfferAsync()
                let sessionId = UUID().uuidString
                let payload = PairingInitPayload(
                    sessionId: sessionId,
                    senderDeviceName: UIDevice.current.name,
                    senderPubKeyFingerprint: local.fingerprint,
                    offerSdp: local.sdp,
                    expiresAtUnixMs: Self.nowMs() + Self.payloadTTLms
                )

                self.initPayloadRaw = try QrPayloadCodec.encodeInit(payload)
                self.localSenderFingerprint = local.fingerprint
                self.activeSessionId = sessionId
                self.payloadExpiresAtUnixMs = payload.expiresAtUnixMs
                self.statusMessage = L10n.tr("status.init_generated")
                self.log(
                    .info,
                    "AppViewModel",
                    "Init payload generated",
                    metadata: [
                        "sessionId": sessionId,
                        "offerLength": String(payload.offerSdp.count),
                        "fingerprintHead": String(local.fingerprint.prefix(16))
                    ]
                )
            } catch {
                let failure = self.negotiationFailure(error: error, fallbackKey: "error.webrtc_negotiation_failed")
                self.resetToEntry(status: failure.message, asFailure: true)
            }
        }
    }

    private func createConfirmFromInitPayload(_ initRaw: String) async throws {
        let payload = try QrPayloadCodec.decodeInit(initRaw)
        try PairingPayloadValidator.validateInit(payload, nowUnixMs: Self.nowMs())

        let local = try await createAnswerAsync(for: payload.offerSdp)
        let confirmPayload = PairingConfirmPayload(
            sessionId: payload.sessionId,
            receiverDeviceName: UIDevice.current.name,
            receiverPubKeyFingerprint: local.fingerprint,
            answerSdp: local.sdp,
            expiresAtUnixMs: Self.nowMs() + Self.payloadTTLms
        )

        confirmPayloadRaw = try QrPayloadCodec.encodeConfirm(confirmPayload)
        activeSessionId = payload.sessionId
        payloadExpiresAtUnixMs = confirmPayload.expiresAtUnixMs
        setupStep = .listenerShowConfirm
        verificationCode = VerificationCode.fromSessionAndFingerprints(
            sessionId: payload.sessionId,
            senderFingerprint: payload.senderPubKeyFingerprint,
            receiverFingerprint: local.fingerprint
        )
        statusMessage = L10n.tr("status.confirm_generated")
        log(
            .info,
            "AppViewModel",
            "Confirm payload generated",
            metadata: [
                "sessionId": payload.sessionId,
                "answerLength": String(confirmPayload.answerSdp.count),
                "fingerprintHead": String(local.fingerprint.prefix(16))
            ]
        )
    }

    private func createConfirmFromConnectionCode(_ connectionCodeRaw: String) async throws {
        let connectionCode = try ConnectionCodeCodec.decode(connectionCodeRaw)
        if connectionCode.expiresAtUnixMs <= Self.nowMs() {
            throw SessionFailure(code: .sessionExpired, message: L10n.tr("error.session_expired"))
        }

        let initRaw = try await ConnectionCodeClient.fetchInitPayload(connectionCode)
        let payload = try QrPayloadCodec.decodeInit(initRaw)
        try PairingPayloadValidator.validateInit(payload, nowUnixMs: Self.nowMs())

        let local = try await createAnswerAsync(for: payload.offerSdp)
        let confirmPayload = PairingConfirmPayload(
            sessionId: payload.sessionId,
            receiverDeviceName: UIDevice.current.name,
            receiverPubKeyFingerprint: local.fingerprint,
            answerSdp: local.sdp,
            expiresAtUnixMs: min(Self.nowMs() + Self.payloadTTLms, connectionCode.expiresAtUnixMs)
        )
        let encodedConfirm = try QrPayloadCodec.encodeConfirm(confirmPayload)
        try await ConnectionCodeClient.submitConfirmPayload(connectionCode, confirmPayload: encodedConfirm)

        streamState = .connecting
        setupStep = .listenerWaitForConnection
        activeSessionId = payload.sessionId
        payloadExpiresAtUnixMs = connectionCode.expiresAtUnixMs
        confirmPayloadRaw = ""
        verificationCode = VerificationCode.fromSessionAndFingerprints(
            sessionId: payload.sessionId,
            senderFingerprint: payload.senderPubKeyFingerprint,
            receiverFingerprint: local.fingerprint
        )
        statusMessage = L10n.tr("status.connection_code_connecting")
        log(
            .info,
            "AppViewModel",
            "Confirm payload submitted via connection code",
            metadata: [
                "sessionId": payload.sessionId,
                "host": connectionCode.host,
                "port": String(connectionCode.port)
            ]
        )
    }

    private func createUdpConfirmFromConnectionCode(_ connectionCodeRaw: String) async throws {
        let connectionCode = try ConnectionCodeCodec.decode(connectionCodeRaw)
        if connectionCode.expiresAtUnixMs <= Self.nowMs() {
            throw SessionFailure(code: .sessionExpired, message: L10n.tr("error.session_expired"))
        }

        let initRaw = try await ConnectionCodeClient.fetchInitPayload(connectionCode)
        let payload = try QrPayloadCodec.decodeUdpInit(initRaw)
        try PairingPayloadValidator.validateUdpInit(payload, nowUnixMs: Self.nowMs())
        try udpListenerTransport.startListening()

        let confirmPayload = UdpConfirmPayload(
            sessionId: payload.sessionId,
            receiverDeviceName: UIDevice.current.name,
            receiverPort: UdpOpusListenerTransport.udpPort,
            expiresAtUnixMs: min(Self.nowMs() + Self.payloadTTLms, connectionCode.expiresAtUnixMs)
        )
        let encodedConfirm = try QrPayloadCodec.encodeUdpConfirm(confirmPayload)
        try await ConnectionCodeClient.submitConfirmPayload(connectionCode, confirmPayload: encodedConfirm)

        streamState = .connecting
        setupStep = .listenerWaitForConnection
        activeSessionId = payload.sessionId
        payloadExpiresAtUnixMs = connectionCode.expiresAtUnixMs
        confirmPayloadRaw = ""
        verificationCode = ""
        statusMessage = L10n.tr("status.udp_connection_code_connecting")
        log(
            .info,
            "AppViewModel",
            "UDP confirm payload submitted via connection code",
            metadata: [
                "sessionId": payload.sessionId,
                "host": connectionCode.host,
                "port": String(connectionCode.port)
            ]
        )
    }

    private func handleListenerFlowError(_ error: Error, inputLength: Int) {
        let fallbackKey = transportMode == .udpOpus ? "error.invalid_connection_code" : "error.invalid_init_payload"
        let status = message(from: error, fallbackKey: fallbackKey)
        var metadata: [String: String] = [
            "reason": status,
            "inputLength": String(inputLength),
            "transportMode": transportMode.rawValue
        ]
        if let failure = error as? SessionFailure {
            metadata["failureCode"] = failure.code.rawValue
        }
        log(.warning, "AppViewModel", "Listener flow failed", metadata: metadata)
        resetToEntry(status: status, asFailure: true)
    }

    private func onRemoteFrameReceived(
        _ frame: PcmFrame,
        player: PcmPlayer?,
        statusMessage: String,
        arrivalRealtimeMs: UInt64
    ) {
        player?.enqueue(frame, arrivalRealtimeMs: arrivalRealtimeMs)
        if !didShowReceivingMessage {
            didShowReceivingMessage = true
            log(
                .info,
                "AppViewModel",
                "First remote audio frame received",
                metadata: [
                    "sampleRate": String(frame.sampleRate),
                    "channels": String(frame.channels),
                    "bitsPerSample": String(frame.bitsPerSample)
                ]
            )
            self.statusMessage = statusMessage
        }
    }

    private func handlePlaybackFailure(_ failure: SessionFailure) {
        log(
            .error,
            "AppViewModel",
            "Playback failed",
            metadata: [
                "failureCode": failure.code.rawValue,
                "reason": failure.message
            ]
        )
        streamState = .failed
        statusMessage = failure.message
    }

    private func updateAudioStreamDiagnostics(_ diagnostics: AudioStreamDiagnostics) {
        Task { @MainActor [weak self] in
            self?.audioStreamDiagnostics = diagnostics
        }
    }

    private func prepareListenerInputState(input: String) {
        shutdownActiveRuntime(clearBroadcastFlag: true)
        streamState = .idle
        setupStep = .listenerInput
        initPayloadRaw = ""
        confirmPayloadRaw = ""
        verificationCode = ""
        activeSessionId = ""
        payloadExpiresAtUnixMs = 0
        pendingAnswerSdp = ""
        localSenderFingerprint = ""
        listenerInputRaw = input
        audioStreamDiagnostics = AudioStreamDiagnostics()
        connectionDiagnostics = ConnectionDiagnostics(pathType: NetworkPathClassifier.classifyFromLocalInterfaces())
        statusMessage = transportMode == .webRtc
            ? L10n.tr("status.listener_ready_to_scan_or_paste")
            : L10n.tr("status.udp_listener_ready_to_scan_or_paste")
        needsBroadcastStartHint = false
    }

    private func resetToEntry(status: String, asFailure: Bool) {
        shutdownActiveRuntime(clearBroadcastFlag: true)

        streamState = asFailure ? .failed : .idle
        statusMessage = status
        setupStep = .entry
        initPayloadRaw = ""
        confirmPayloadRaw = ""
        verificationCode = ""
        activeSessionId = ""
        payloadExpiresAtUnixMs = 0
        listenerInputRaw = ""
        pendingAnswerSdp = ""
        localSenderFingerprint = ""
        audioStreamDiagnostics = AudioStreamDiagnostics()
        connectionDiagnostics = ConnectionDiagnostics(pathType: NetworkPathClassifier.classifyFromLocalInterfaces())
        needsBroadcastStartHint = false
    }

    private func shutdownActiveRuntime(clearBroadcastFlag: Bool) {
        invalidatePendingSenderStart()
        sessionController.close()
        udpListenerTransport.close(emitEnded: false)
        webRtcPlayer.stop()
        udpPlayer.stop()

        if clearBroadcastFlag {
            replayKitController.stopBroadcastFlag()
        } else if isConsumingReplayKitFrames {
            replayKitController.stopConsumingFrames()
        }

        isConsumingReplayKitFrames = false
        didShowReceivingMessage = false
    }

    private func invalidatePendingSenderStart() {
        senderStartRequestToken &+= 1
    }

    private func makeWebRtcPlayer(_ preset: PlaybackLatencyPreset) -> PcmPlayer {
        let config = preset.webRtcConfig
        return PcmPlayer(
            source: .webRtcReceive,
            startupPrebufferFrames: config.startupPrebufferFrames,
            steadyPrebufferFrames: config.steadyPrebufferFrames,
            maxQueueFrames: config.maxQueueFrames,
            diagnosticsHandler: { [weak self] diagnostics in
                Task { @MainActor [weak self] in
                    self?.updateAudioStreamDiagnostics(diagnostics)
                }
            },
            errorHandler: { [weak self] failure in
                Task { @MainActor [weak self] in
                    self?.handlePlaybackFailure(failure)
                }
            }
        )
    }

    private func makeUdpPlayer(_ preset: PlaybackLatencyPreset) -> PcmPlayer {
        let config = preset.udpOpusConfig
        return PcmPlayer(
            source: .udpOpusReceive,
            startupPrebufferFrames: config.startupPrebufferFrames,
            steadyPrebufferFrames: config.steadyPrebufferFrames,
            maxQueueFrames: config.maxQueueFrames,
            diagnosticsHandler: { [weak self] diagnostics in
                Task { @MainActor [weak self] in
                    self?.updateAudioStreamDiagnostics(diagnostics)
                }
            },
            errorHandler: { [weak self] failure in
                Task { @MainActor [weak self] in
                    self?.handlePlaybackFailure(failure)
                }
            }
        )
    }

    private func createOfferAsync() async throws -> (sdp: String, fingerprint: String) {
        try await withCheckedThrowingContinuation { continuation in
            sessionController.createOffer { result in
                continuation.resume(with: result)
            }
        }
    }

    private func createAnswerAsync(for offerSdp: String) async throws -> (sdp: String, fingerprint: String) {
        try await withCheckedThrowingContinuation { continuation in
            sessionController.createAnswer(for: offerSdp) { result in
                continuation.resume(with: result)
            }
        }
    }

    private func applyAnswerAsync(_ answerSdp: String) async throws {
        try await withCheckedThrowingContinuation { continuation in
            sessionController.applyAnswer(answerSdp) { result in
                continuation.resume(with: result)
            }
        }
    }

    private func stateLabel(_ state: AudioStreamState) -> String {
        switch state {
        case .idle:
            return L10n.tr("stream_state.idle")
        case .capturing:
            return L10n.tr("stream_state.capturing")
        case .connecting:
            return L10n.tr("stream_state.connecting")
        case .streaming:
            return L10n.tr("stream_state.streaming")
        case .interrupted:
            return L10n.tr("stream_state.interrupted")
        case .failed:
            return L10n.tr("stream_state.failed")
        case .ended:
            return L10n.tr("stream_state.ended")
        }
    }

    private func message(from error: Error, fallbackKey: String) -> String {
        if let failure = error as? SessionFailure {
            return failure.message
        }
        return L10n.tr(fallbackKey)
    }

    private func negotiationFailure(error: Error, fallbackKey: String) -> SessionFailure {
        if let failure = error as? SessionFailure, failure.code != .webrtcNegotiationFailed {
            return failure
        }

        if connectionDiagnostics.pathType == .usbTether && connectionDiagnostics.localCandidatesCount == 0 {
            return SessionFailure(code: .usbTetherUnavailable, message: L10n.tr("error.usb_tether_unavailable"))
        }
        if connectionDiagnostics.pathType == .usbTether {
            return SessionFailure(code: .usbTetherDetectedButNotReachable, message: L10n.tr("error.usb_tether_not_reachable"))
        }
        if connectionDiagnostics.localCandidatesCount == 0 {
            return SessionFailure(code: .networkInterfaceNotUsable, message: L10n.tr("error.network_interface_not_usable"))
        }
        return SessionFailure(code: .webrtcNegotiationFailed, message: message(from: error, fallbackKey: fallbackKey))
    }

    private func log(
        _ level: AppLogLevel,
        _ category: String,
        _ message: String,
        metadata: [String: String] = [:]
    ) {
        Task { @MainActor [weak self] in
            self?.logStore.append(level: level, category: category, message: message, metadata: metadata)
        }
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static func realtimeNowMs() -> UInt64 {
        DispatchTime.now().uptimeNanoseconds / 1_000_000
    }

    private static let payloadTTLms: Int64 = 600_000
    private static let senderStartRetryAttempts = 10
    private static let senderStartRetryIntervalMs = 200
    private static let receiverLatencyStorageKey = "receiver_latency_preset"
}
