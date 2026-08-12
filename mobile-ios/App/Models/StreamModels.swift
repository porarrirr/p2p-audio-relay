import Foundation

enum AudioStreamState: String {
    case idle
    case capturing
    case connecting
    case streaming
    case interrupted
    case failed
    case ended
}

enum FailureCode: String {
    case permissionDenied = "permission_denied"
    case audioCaptureNotSupported = "audio_capture_not_supported"
    case webrtcNegotiationFailed = "webrtc_negotiation_failed"
    case peerUnreachable = "peer_unreachable"
    case networkChanged = "network_changed"
    case usbTetherUnavailable = "usb_tether_unavailable"
    case usbTetherDetectedButNotReachable = "usb_tether_detected_but_not_reachable"
    case networkInterfaceNotUsable = "network_interface_not_usable"
    case sessionExpired = "session_expired"
    case invalidPayload = "invalid_payload"
}

struct SessionFailure: Error {
    let code: FailureCode
    let message: String
}

enum TransportMode: String, CaseIterable, Identifiable {
    case webRtc = "webrtc"
    case udpOpus = "udp_opus"

    var id: String { rawValue }
}

enum NetworkPathType: String {
    case wifiLan = "wifi_lan"
    case usbTether = "usb_tether"
    case unknown = "unknown"
}

struct ConnectionDiagnostics {
    let pathType: NetworkPathType
    let localCandidatesCount: Int
    let selectedCandidatePairType: String
    let failureHint: String

    init(
        pathType: NetworkPathType = .unknown,
        localCandidatesCount: Int = 0,
        selectedCandidatePairType: String = "",
        failureHint: String = ""
    ) {
        self.pathType = pathType
        self.localCandidatesCount = localCandidatesCount
        self.selectedCandidatePairType = selectedCandidatePairType
        self.failureHint = failureHint
    }
}

enum AudioStreamSource: String {
    case none
    case webRtcReceive
    case udpOpusReceive
}

struct AudioStreamDiagnostics {
    let source: AudioStreamSource
    let sampleRate: Int
    let channels: Int
    let bitsPerSample: Int
    let frameSamplesPerChannel: Int
    let frameDurationMs: Int
    let startupTargetFrames: Int
    let targetPrebufferFrames: Int
    let maxQueueFrames: Int
    let queueDepthFrames: Int
    let playedFrames: Int64
    let decodedPackets: Int64
    let staleFrameDrops: Int64
    let queueOverflowDrops: Int64

    init(
        source: AudioStreamSource = .none,
        sampleRate: Int = 0,
        channels: Int = 0,
        bitsPerSample: Int = 0,
        frameSamplesPerChannel: Int = 0,
        frameDurationMs: Int = 0,
        startupTargetFrames: Int = 0,
        targetPrebufferFrames: Int = 0,
        maxQueueFrames: Int = 0,
        queueDepthFrames: Int = 0,
        playedFrames: Int64 = 0,
        decodedPackets: Int64 = 0,
        staleFrameDrops: Int64 = 0,
        queueOverflowDrops: Int64 = 0
    ) {
        self.source = source
        self.sampleRate = sampleRate
        self.channels = channels
        self.bitsPerSample = bitsPerSample
        self.frameSamplesPerChannel = frameSamplesPerChannel
        self.frameDurationMs = frameDurationMs
        self.startupTargetFrames = startupTargetFrames
        self.targetPrebufferFrames = targetPrebufferFrames
        self.maxQueueFrames = maxQueueFrames
        self.queueDepthFrames = queueDepthFrames
        self.playedFrames = playedFrames
        self.decodedPackets = decodedPackets
        self.staleFrameDrops = staleFrameDrops
        self.queueOverflowDrops = queueOverflowDrops
    }

    func hasContent() -> Bool {
        source != .none ||
            sampleRate > 0 ||
            queueDepthFrames > 0 ||
            playedFrames > 0 ||
            decodedPackets > 0 ||
            staleFrameDrops > 0 ||
            queueOverflowDrops > 0
    }
}
