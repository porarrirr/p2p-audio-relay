import Foundation

struct PairingInitPayload: Codable {
    let version: String
    let phase: String
    let sessionId: String
    let senderDeviceName: String
    let senderPubKeyFingerprint: String
    let offerSdp: String
    let expiresAtUnixMs: Int64

    init(
        sessionId: String,
        senderDeviceName: String,
        senderPubKeyFingerprint: String,
        offerSdp: String,
        expiresAtUnixMs: Int64
    ) {
        self.version = "2"
        self.phase = "init"
        self.sessionId = sessionId
        self.senderDeviceName = senderDeviceName
        self.senderPubKeyFingerprint = senderPubKeyFingerprint
        self.offerSdp = offerSdp
        self.expiresAtUnixMs = expiresAtUnixMs
    }
}

struct PairingConfirmPayload: Codable {
    let version: String
    let phase: String
    let sessionId: String
    let receiverDeviceName: String
    let receiverPubKeyFingerprint: String
    let answerSdp: String
    let expiresAtUnixMs: Int64

    init(
        sessionId: String,
        receiverDeviceName: String,
        receiverPubKeyFingerprint: String,
        answerSdp: String,
        expiresAtUnixMs: Int64
    ) {
        self.version = "2"
        self.phase = "confirm"
        self.sessionId = sessionId
        self.receiverDeviceName = receiverDeviceName
        self.receiverPubKeyFingerprint = receiverPubKeyFingerprint
        self.answerSdp = answerSdp
        self.expiresAtUnixMs = expiresAtUnixMs
    }
}

struct UdpInitPayload: Codable {
    let version: String
    let phase: String
    let transport: String
    let sessionId: String
    let senderDeviceName: String
    let expiresAtUnixMs: Int64

    init(
        sessionId: String,
        senderDeviceName: String,
        expiresAtUnixMs: Int64
    ) {
        self.version = "2"
        self.phase = "udp_init"
        self.transport = "udp_opus"
        self.sessionId = sessionId
        self.senderDeviceName = senderDeviceName
        self.expiresAtUnixMs = expiresAtUnixMs
    }
}

struct UdpConfirmPayload: Codable {
    let version: String
    let phase: String
    let transport: String
    let sessionId: String
    let receiverDeviceName: String
    let receiverPort: Int
    let expiresAtUnixMs: Int64

    init(
        sessionId: String,
        receiverDeviceName: String,
        receiverPort: Int,
        expiresAtUnixMs: Int64
    ) {
        self.version = "2"
        self.phase = "udp_confirm"
        self.transport = "udp_opus"
        self.sessionId = sessionId
        self.receiverDeviceName = receiverDeviceName
        self.receiverPort = receiverPort
        self.expiresAtUnixMs = expiresAtUnixMs
    }
}

struct ConnectionCodePayload: Equatable {
    let host: String
    let port: Int
    let token: String
    let expiresAtUnixMs: Int64
}
