import Foundation

enum UdpOpusPacketCodec {
    private static let magic = Data("P2AU".utf8)
    private static let version: UInt8 = 1
    static let headerBytes = 26

    static func encode(_ packet: UdpOpusPacket) -> Data {
        precondition(packet.sampleRate > 0)
        precondition((1...2).contains(packet.channels))
        precondition(packet.frameSamplesPerChannel > 0)
        precondition(!packet.opusPayload.isEmpty)
        precondition(packet.opusPayload.count <= 0xFFFF)

        var data = Data()
        data.reserveCapacity(headerBytes + packet.opusPayload.count)
        data.append(magic)
        data.append(version)
        data.append(UInt8(packet.channels & 0xFF))
        data.appendUInt16BE(UInt16(packet.frameSamplesPerChannel & 0xFFFF))
        data.appendUInt32BE(UInt32(bitPattern: Int32(packet.sampleRate)))
        data.appendUInt32BE(UInt32(bitPattern: Int32(packet.sequence)))
        data.appendUInt64BE(UInt64(bitPattern: packet.timestampMs))
        data.appendUInt16BE(UInt16(packet.opusPayload.count & 0xFFFF))
        data.append(packet.opusPayload)
        return data
    }

    static func decode(_ raw: Data) -> UdpOpusPacket? {
        guard raw.count >= headerBytes else {
            return nil
        }
        guard raw.starts(with: magic) else {
            return nil
        }
        guard raw[4] == version else {
            return nil
        }

        let channels = Int(raw[5])
        let frameSamplesPerChannel = Int(raw.readUInt16BE(at: 6))
        let sampleRate = Int(Int32(bitPattern: raw.readUInt32BE(at: 8)))
        let sequence = Int(Int32(bitPattern: raw.readUInt32BE(at: 12)))
        let timestampMs = Int64(bitPattern: raw.readUInt64BE(at: 16))
        let payloadSize = Int(raw.readUInt16BE(at: 24))

        guard (1...2).contains(channels),
              frameSamplesPerChannel > 0,
              sampleRate > 0,
              payloadSize > 0,
              raw.count == headerBytes + payloadSize else {
            return nil
        }

        return UdpOpusPacket(
            sequence: sequence,
            timestampMs: timestampMs,
            sampleRate: sampleRate,
            channels: channels,
            frameSamplesPerChannel: frameSamplesPerChannel,
            opusPayload: raw.subdata(in: headerBytes..<raw.count)
        )
    }
}

private extension Data {
    mutating func appendUInt16BE(_ value: UInt16) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { bytes in
            append(contentsOf: bytes)
        }
    }

    mutating func appendUInt32BE(_ value: UInt32) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { bytes in
            append(contentsOf: bytes)
        }
    }

    mutating func appendUInt64BE(_ value: UInt64) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { bytes in
            append(contentsOf: bytes)
        }
    }

    func readUInt16BE(at offset: Int) -> UInt16 {
        guard offset + 1 < count else {
            return 0
        }
        return (UInt16(self[offset]) << 8) | UInt16(self[offset + 1])
    }

    func readUInt32BE(at offset: Int) -> UInt32 {
        guard offset + 3 < count else {
            return 0
        }
        return (UInt32(self[offset]) << 24)
            | (UInt32(self[offset + 1]) << 16)
            | (UInt32(self[offset + 2]) << 8)
            | UInt32(self[offset + 3])
    }

    func readUInt64BE(at offset: Int) -> UInt64 {
        guard offset + 7 < count else {
            return 0
        }
        var value = UInt64(self[offset]) << 56
        value |= UInt64(self[offset + 1]) << 48
        value |= UInt64(self[offset + 2]) << 40
        value |= UInt64(self[offset + 3]) << 32
        value |= UInt64(self[offset + 4]) << 24
        value |= UInt64(self[offset + 5]) << 16
        value |= UInt64(self[offset + 6]) << 8
        value |= UInt64(self[offset + 7])
        return value
    }
}
