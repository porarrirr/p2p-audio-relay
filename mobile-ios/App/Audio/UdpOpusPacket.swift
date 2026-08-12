import Foundation

struct UdpOpusPacket {
    let sequence: Int
    let timestampMs: Int64
    let sampleRate: Int
    let channels: Int
    let frameSamplesPerChannel: Int
    let opusPayload: Data
}
