import Foundation
import Darwin

private struct QueuedRealtimeDecodePacket {
    let packet: UdpOpusPacket
    let arrivalRealtimeMs: UInt64
}

final class UdpOpusListenerTransport {
    typealias StateHandler = (AudioStreamState, String?) -> Void
    typealias DiagnosticsHandler = (ConnectionDiagnostics) -> Void

    static let udpPort = 49_152
    static let maxOpusPayloadBytes = 1_500
    static let maxPacketBytes = UdpOpusPacketCodec.headerBytes + maxOpusPayloadBytes
    private static let maxDecodeQueuePackets = 32

    private let stateHandler: StateHandler
    private let pcmFrameHandler: (PcmFrame, UInt64) -> Void
    private let diagnosticsHandler: DiagnosticsHandler
    private let receiveQueue = DispatchQueue(label: "com.example.p2paudio.udp-receive", qos: .userInitiated)
    private let decodeQueue = DispatchQueue(label: "com.example.p2paudio.udp-decode", qos: .userInitiated)
    private let decodeCondition = NSCondition()
    private let lifecycleLock = NSLock()
    private let decoder: IOSOpusDecoder

    private var pendingDecodePackets: [QueuedRealtimeDecodePacket] = []
    private var socketFileDescriptor: Int32 = -1
    private var isRunning = false
    private var streamingStarted = false

    init(
        stateHandler: @escaping StateHandler,
        pcmFrameHandler: @escaping (PcmFrame, UInt64) -> Void,
        diagnosticsHandler: @escaping DiagnosticsHandler = { _ in }
    ) {
        self.stateHandler = stateHandler
        self.pcmFrameHandler = pcmFrameHandler
        self.diagnosticsHandler = diagnosticsHandler
        self.decoder = IOSOpusDecoder(frameListener: pcmFrameHandler)
    }

    func startListening() throws {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        if isRunning {
            return
        }

        let fileDescriptor = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fileDescriptor >= 0 else {
            throw SessionFailure(
                code: .peerUnreachable,
                message: L10n.tr("error.udp_listener_start_failed")
            )
        }

        var reuseAddress: Int32 = 1
        _ = setsockopt(
            fileDescriptor,
            SOL_SOCKET,
            SO_REUSEADDR,
            &reuseAddress,
            socklen_t(MemoryLayout<Int32>.size)
        )

        var receiveBufferSize: Int32 = Int32(Self.maxPacketBytes * 128)
        _ = setsockopt(
            fileDescriptor,
            SOL_SOCKET,
            SO_RCVBUF,
            &receiveBufferSize,
            socklen_t(MemoryLayout<Int32>.size)
        )

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(Self.udpPort).bigEndian
        address.sin_addr = in_addr(s_addr: in_addr_t(0))

        let bindResult = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                Darwin.bind(
                    fileDescriptor,
                    sockaddrPointer,
                    socklen_t(MemoryLayout<sockaddr_in>.size)
                )
            }
        }
        guard bindResult == 0 else {
            Darwin.close(fileDescriptor)
            throw SessionFailure(
                code: .peerUnreachable,
                message: L10n.tr("error.udp_listener_start_failed")
            )
        }

        socketFileDescriptor = fileDescriptor
        isRunning = true
        streamingStarted = false
        diagnosticsHandler(
            ConnectionDiagnostics(
                pathType: NetworkPathClassifier.classifyFromLocalInterfaces(),
                selectedCandidatePairType: "udp_opus"
            )
        )
        stateHandler(.connecting, L10n.tr("status.udp_waiting_for_connection"))

        receiveQueue.async { [weak self] in
            self?.runReceiveLoop()
        }
        decodeQueue.async { [weak self] in
            self?.runDecodeLoop()
        }
    }

    func close(emitEnded: Bool = true) {
        lifecycleLock.lock()
        let wasRunning = isRunning
        isRunning = false
        let fileDescriptor = socketFileDescriptor
        socketFileDescriptor = -1
        lifecycleLock.unlock()

        if fileDescriptor >= 0 {
            Darwin.shutdown(fileDescriptor, SHUT_RDWR)
            Darwin.close(fileDescriptor)
        }

        decodeCondition.lock()
        pendingDecodePackets.removeAll()
        decodeCondition.broadcast()
        decodeCondition.unlock()

        decoder.close()
        diagnosticsHandler(ConnectionDiagnostics())
        streamingStarted = false

        if wasRunning && emitEnded {
            stateHandler(.ended, nil)
        }
    }

    private func runReceiveLoop() {
        var buffer = [UInt8](repeating: 0, count: Self.maxPacketBytes)

        while true {
            let fileDescriptor = currentSocketFileDescriptor()
            if fileDescriptor < 0 {
                return
            }
            var remoteAddress = sockaddr_storage()
            var remoteLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let bytesRead = withUnsafeMutablePointer(to: &remoteAddress) { addressPointer in
                addressPointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                    recvfrom(
                        fileDescriptor,
                        &buffer,
                        buffer.count,
                        0,
                        sockaddrPointer,
                        &remoteLength
                    )
                }
            }

            if bytesRead <= 0 {
                if isCurrentlyRunning() {
                    fail(SessionFailure(code: .peerUnreachable, message: L10n.tr("error.udp_receive_failed")))
                }
                return
            }

            let packetData = Data(buffer.prefix(Int(bytesRead)))
            guard let packet = UdpOpusPacketCodec.decode(packetData) else {
                continue
            }

            if !streamingStarted {
                streamingStarted = true
                stateHandler(.streaming, L10n.tr("status.udp_receiving_audio"))
            }

            enqueueDecodePacket(
                packet,
                arrivalRealtimeMs: DispatchTime.now().uptimeNanoseconds / 1_000_000
            )
        }
    }

    private func runDecodeLoop() {
        while isCurrentlyRunning() {
            guard let nextPacket = waitForDecodePacket() else {
                continue
            }

            do {
                try decoder.decode(nextPacket.packet, arrivalRealtimeMs: nextPacket.arrivalRealtimeMs)
            } catch let failure as SessionFailure {
                fail(failure)
                return
            } catch {
                fail(
                    SessionFailure(
                        code: .peerUnreachable,
                        message: L10n.tr("error.udp_decode_failed")
                    )
                )
                return
            }
        }
    }

    private func enqueueDecodePacket(_ packet: UdpOpusPacket, arrivalRealtimeMs: UInt64) {
        decodeCondition.lock()
        defer {
            decodeCondition.signal()
            decodeCondition.unlock()
        }

        if pendingDecodePackets.contains(where: { $0.packet.sequence == packet.sequence }) {
            return
        }

        while pendingDecodePackets.count >= Self.maxDecodeQueuePackets {
            pendingDecodePackets.removeFirst()
        }

        pendingDecodePackets.append(
            QueuedRealtimeDecodePacket(
                packet: packet,
                arrivalRealtimeMs: arrivalRealtimeMs
            )
        )
        pendingDecodePackets.sort {
            if $0.packet.sequence == $1.packet.sequence {
                return $0.arrivalRealtimeMs < $1.arrivalRealtimeMs
            }
            return $0.packet.sequence < $1.packet.sequence
        }
    }

    private func waitForDecodePacket() -> QueuedRealtimeDecodePacket? {
        decodeCondition.lock()
        defer { decodeCondition.unlock() }

        while isCurrentlyRunning(), pendingDecodePackets.isEmpty {
            decodeCondition.wait()
        }

        if pendingDecodePackets.isEmpty {
            return nil
        }

        return pendingDecodePackets.removeFirst()
    }

    private func fail(_ failure: SessionFailure) {
        diagnosticsHandler(
            ConnectionDiagnostics(
                pathType: NetworkPathClassifier.classifyFromLocalInterfaces(),
                selectedCandidatePairType: "udp_opus",
                failureHint: "peer_unreachable"
            )
        )
        stateHandler(.failed, failure.message)
        close(emitEnded: false)
    }

    private func currentSocketFileDescriptor() -> Int32 {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return socketFileDescriptor
    }

    private func isCurrentlyRunning() -> Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return isRunning
    }
}
