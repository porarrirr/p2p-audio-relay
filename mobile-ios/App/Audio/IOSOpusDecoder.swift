import AudioToolbox
import Foundation

private struct ConverterInputContext {
    var packetPointer: UnsafeMutableRawPointer?
    var packetSize: UInt32
    var channels: UInt32
    var packetDescription: AudioStreamPacketDescription
    var consumed: Bool
}

final class IOSOpusDecoder {
    private let frameListener: (PcmFrame, UInt64) -> Void
    private var converter: AudioConverterRef?
    private var formatKey: String?

    init(frameListener: @escaping (PcmFrame, UInt64) -> Void) {
        self.frameListener = frameListener
    }

    deinit {
        close()
    }

    func decode(_ packet: UdpOpusPacket, arrivalRealtimeMs: UInt64) throws {
        try ensureConverter(for: packet)
        guard let converter else {
            throw SessionFailure(
                code: .webrtcNegotiationFailed,
                message: L10n.tr("error.opus_decoder_unavailable")
            )
        }

        let expectedPcmBytes = max(packet.frameSamplesPerChannel * packet.channels * 2, 1)
        var opusBytes = [UInt8](packet.opusPayload)
        var pcmData = Data(count: max(expectedPcmBytes * 4, 4_096))
        var producedByteCount = 0

        let status: OSStatus = opusBytes.withUnsafeMutableBytes { opusBuffer in
            pcmData.withUnsafeMutableBytes { pcmBuffer in
                var inputContext = ConverterInputContext(
                    packetPointer: opusBuffer.baseAddress,
                    packetSize: UInt32(opusBuffer.count),
                    channels: UInt32(packet.channels),
                    packetDescription: AudioStreamPacketDescription(
                        mStartOffset: 0,
                        mVariableFramesInPacket: 0,
                        mDataByteSize: UInt32(opusBuffer.count)
                    ),
                    consumed: false
                )

                var outputPacketCount = UInt32(max(packet.frameSamplesPerChannel, 1))
                var audioBufferList = AudioBufferList(
                    mNumberBuffers: 1,
                    mBuffers: AudioBuffer(
                        mNumberChannels: UInt32(packet.channels),
                        mDataByteSize: UInt32(pcmBuffer.count),
                        mData: pcmBuffer.baseAddress
                    )
                )

                let status = AudioConverterFillComplexBuffer(
                    converter,
                    Self.inputDataProc,
                    &inputContext,
                    &outputPacketCount,
                    &audioBufferList,
                    nil
                )
                producedByteCount = Int(audioBufferList.mBuffers.mDataByteSize)
                return status
            }
        }

        guard status == noErr else {
            throw SessionFailure(
                code: .webrtcNegotiationFailed,
                message: L10n.tr("error.opus_decoder_unavailable")
            )
        }

        if producedByteCount <= 0 {
            return
        }

        pcmData.count = producedByteCount
        let bytesPerSampleFrame = max(packet.channels * 2, 1)
        let frameSamplesPerChannel = producedByteCount / bytesPerSampleFrame
        frameListener(
            PcmFrame(
                sequence: packet.sequence,
                timestampMs: packet.timestampMs,
                sampleRate: packet.sampleRate,
                channels: packet.channels,
                bitsPerSample: 16,
                frameSamplesPerChannel: frameSamplesPerChannel,
                pcmData: pcmData
            ),
            arrivalRealtimeMs
        )
    }

    func close() {
        if let converter {
            AudioConverterDispose(converter)
        }
        converter = nil
        formatKey = nil
    }

    private func ensureConverter(for packet: UdpOpusPacket) throws {
        let nextKey = "\(packet.sampleRate)-\(packet.channels)-\(packet.frameSamplesPerChannel)"
        if converter != nil, formatKey == nextKey {
            return
        }

        close()

        var inputDescription = AudioStreamBasicDescription(
            mSampleRate: Double(packet.sampleRate),
            mFormatID: kAudioFormatOpus,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: UInt32(packet.frameSamplesPerChannel),
            mBytesPerFrame: 0,
            mChannelsPerFrame: UInt32(packet.channels),
            mBitsPerChannel: 0,
            mReserved: 0
        )
        var outputDescription = AudioStreamBasicDescription(
            mSampleRate: Double(packet.sampleRate),
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
            mBytesPerPacket: UInt32(packet.channels * 2),
            mFramesPerPacket: 1,
            mBytesPerFrame: UInt32(packet.channels * 2),
            mChannelsPerFrame: UInt32(packet.channels),
            mBitsPerChannel: 16,
            mReserved: 0
        )

        var newConverter: AudioConverterRef?
        let status = AudioConverterNew(&inputDescription, &outputDescription, &newConverter)
        guard status == noErr, let newConverter else {
            throw SessionFailure(
                code: .webrtcNegotiationFailed,
                message: L10n.tr("error.opus_decoder_unavailable")
            )
        }

        let magicCookie = makeOpusMagicCookie(sampleRate: packet.sampleRate, channels: packet.channels)
        let cookieStatus = magicCookie.withUnsafeBytes { rawBuffer -> OSStatus in
            guard let baseAddress = rawBuffer.baseAddress else {
                return kAudio_ParamError
            }
            return AudioConverterSetProperty(
                newConverter,
                kAudioConverterDecompressionMagicCookie,
                UInt32(magicCookie.count),
                baseAddress
            )
        }
        guard cookieStatus == noErr else {
            AudioConverterDispose(newConverter)
            throw SessionFailure(
                code: .webrtcNegotiationFailed,
                message: L10n.tr("error.opus_decoder_unavailable")
            )
        }

        converter = newConverter
        formatKey = nextKey
    }

    private func makeOpusMagicCookie(sampleRate: Int, channels: Int) -> Data {
        var data = Data()
        data.append(Data("OpusHead".utf8))
        data.append(1)
        data.append(UInt8(channels & 0xFF))
        data.appendUInt16LE(312)
        data.appendUInt32LE(UInt32(sampleRate))
        data.appendUInt16LE(0)
        data.append(0)
        return data
    }

    private static let inputDataProc: AudioConverterComplexInputDataProc = {
        _,
        ioNumberDataPackets,
        ioData,
        outDataPacketDescription,
        inUserData in
        guard let inUserData else {
            ioNumberDataPackets.pointee = 0
            return noErr
        }

        let context = inUserData.assumingMemoryBound(to: ConverterInputContext.self)
        if context.pointee.consumed {
            ioNumberDataPackets.pointee = 0
            ioData.pointee.mNumberBuffers = 1
            ioData.pointee.mBuffers.mDataByteSize = 0
            ioData.pointee.mBuffers.mData = nil
            return noErr
        }

        context.pointee.consumed = true
        ioNumberDataPackets.pointee = 1
        ioData.pointee.mNumberBuffers = 1
        ioData.pointee.mBuffers.mNumberChannels = context.pointee.channels
        ioData.pointee.mBuffers.mDataByteSize = context.pointee.packetSize
        ioData.pointee.mBuffers.mData = context.pointee.packetPointer

        if let outDataPacketDescription {
            withUnsafeMutablePointer(to: &context.pointee.packetDescription) { packetPointer in
                outDataPacketDescription.pointee = packetPointer
            }
        }
        return noErr
    }
}

private extension Data {
    mutating func appendUInt16LE(_ value: UInt16) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { bytes in
            append(contentsOf: bytes)
        }
    }

    mutating func appendUInt32LE(_ value: UInt32) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { bytes in
            append(contentsOf: bytes)
        }
    }
}
