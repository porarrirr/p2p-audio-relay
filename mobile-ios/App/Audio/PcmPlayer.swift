import AVFoundation
import Foundation

private struct QueuedPcmFrame {
    let frame: PcmFrame
    let arrivalRealtimeMs: UInt64
}

final class PcmPlayer {
    typealias DiagnosticsHandler = (AudioStreamDiagnostics) -> Void
    typealias ErrorHandler = (SessionFailure) -> Void

    private let source: AudioStreamSource
    private let startupPrebufferFrames: Int
    private let steadyPrebufferFrames: Int
    private let maxQueueFrames: Int
    private let diagnosticsHandler: DiagnosticsHandler
    private let errorHandler: ErrorHandler?
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let queue = DispatchQueue(label: "com.example.p2paudio.pcm-player")

    private var currentFormatKey: String?
    private var currentFormat: AVAudioFormat?
    private var pendingFrames: [QueuedPcmFrame] = []
    private var scheduledFrames = 0
    private var playbackStarted = false
    private var expectedSequence: Int?
    private var generation = 0
    private var playedFrames: Int64 = 0
    private var decodedPackets: Int64 = 0
    private var staleFrameDrops: Int64 = 0
    private var queueOverflowDrops: Int64 = 0
    private var currentSampleRate = 0
    private var currentChannels = 0
    private var currentBitsPerSample = 0
    private var currentFrameSamplesPerChannel = 0

    init(
        source: AudioStreamSource,
        startupPrebufferFrames: Int = 3,
        steadyPrebufferFrames: Int = 3,
        maxQueueFrames: Int = 20,
        diagnosticsHandler: @escaping DiagnosticsHandler = { _ in },
        errorHandler: ErrorHandler? = nil
    ) {
        self.source = source
        self.startupPrebufferFrames = max(startupPrebufferFrames, 1)
        self.steadyPrebufferFrames = max(steadyPrebufferFrames, 1)
        self.maxQueueFrames = max(maxQueueFrames, self.startupPrebufferFrames)
        self.diagnosticsHandler = diagnosticsHandler
        self.errorHandler = errorHandler
        engine.attach(playerNode)
    }

    func enqueue(_ frame: PcmFrame, arrivalRealtimeMs: UInt64 = realtimeNowMs()) {
        queue.async { [weak self] in
            self?.enqueueOnQueue(frame, arrivalRealtimeMs: arrivalRealtimeMs)
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.resetUnsafe(notifyDiagnostics: true)
        }
    }

    private func enqueueOnQueue(_ frame: PcmFrame, arrivalRealtimeMs: UInt64) {
        guard let format = prepareFormatIfNeeded(for: frame) else {
            return
        }
        decodedPackets += 1
        currentSampleRate = frame.sampleRate
        currentChannels = frame.channels
        currentBitsPerSample = frame.bitsPerSample
        currentFrameSamplesPerChannel = frame.frameSamplesPerChannel

        if let expectedSequence, frame.sequence < expectedSequence {
            staleFrameDrops += 1
            publishDiagnosticsUnsafe()
            return
        }
        if pendingFrames.contains(where: { $0.frame.sequence == frame.sequence }) {
            return
        }

        pendingFrames.append(
            QueuedPcmFrame(
                frame: frame,
                arrivalRealtimeMs: arrivalRealtimeMs
            )
        )
        pendingFrames.sort {
            if $0.frame.sequence == $1.frame.sequence {
                return $0.arrivalRealtimeMs < $1.arrivalRealtimeMs
            }
            return $0.frame.sequence < $1.frame.sequence
        }

        while pendingFrames.count + scheduledFrames > maxQueueFrames {
            let dropped = pendingFrames.removeFirst()
            queueOverflowDrops += 1
            if let expectedSequence, dropped.frame.sequence >= expectedSequence {
                self.expectedSequence = dropped.frame.sequence + 1
            }
        }

        drainQueueUnsafe(format: format)
        publishDiagnosticsUnsafe()
    }

    private func prepareFormatIfNeeded(for frame: PcmFrame) -> AVAudioFormat? {
        let key = "\(frame.sampleRate)-\(frame.channels)-\(frame.bitsPerSample)"
        if currentFormatKey != key {
            resetUnsafe(notifyDiagnostics: false)

            guard let newFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Double(frame.sampleRate),
                channels: AVAudioChannelCount(frame.channels),
                interleaved: true
            ) else {
                handleError(SessionFailure(code: .webrtcNegotiationFailed, message: L10n.tr("error.audio_playback_unavailable")))
                return nil
            }

            engine.disconnectNodeOutput(playerNode)
            engine.connect(playerNode, to: engine.mainMixerNode, format: newFormat)

            currentFormat = newFormat
            currentFormatKey = key
            return newFormat
        }
        return currentFormat
    }

    private func makeBuffer(frame: PcmFrame, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let frameCount = AVAudioFrameCount(frame.frameSamplesPerChannel)
        guard let pcmBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
            return nil
        }
        pcmBuffer.frameLength = frameCount

        let bufferList = UnsafeMutableAudioBufferListPointer(pcmBuffer.mutableAudioBufferList)
        guard let audioBuffer = bufferList.first, let dst = audioBuffer.mData else {
            return nil
        }

        let copySize = min(Int(audioBuffer.mDataByteSize), frame.pcmData.count)
        frame.pcmData.withUnsafeBytes { src in
            guard let srcBase = src.baseAddress else { return }
            memcpy(dst, srcBase, copySize)
            if copySize < Int(audioBuffer.mDataByteSize) {
                memset(dst.advanced(by: copySize), 0, Int(audioBuffer.mDataByteSize) - copySize)
            }
        }

        return pcmBuffer
    }

    private func drainQueueUnsafe(format: AVAudioFormat) {
        while let next = nextFrameToScheduleUnsafe(), scheduledFrames < maxQueueFrames {
            guard let buffer = makeBuffer(frame: next.frame, format: format) else {
                continue
            }

            let scheduledGeneration = generation
            scheduledFrames += 1
            playerNode.scheduleBuffer(buffer, completionHandler: { [weak self] in
                self?.queue.async { [weak self] in
                    guard let self, self.generation == scheduledGeneration else {
                        return
                    }
                    self.scheduledFrames = max(self.scheduledFrames - 1, 0)
                    self.playedFrames += 1
                    self.publishDiagnosticsUnsafe()
                    if let format = self.currentFormat {
                        self.drainQueueUnsafe(format: format)
                    }
                }
            })
        }

        if !playbackStarted && scheduledFrames >= startupPrebufferFrames {
            do {
                try ensureEngineRunningUnsafe()
                playerNode.play()
                playbackStarted = true
            } catch {
                handleError(
                    SessionFailure(
                        code: .webrtcNegotiationFailed,
                        message: L10n.tr("error.audio_playback_unavailable")
                    )
                )
            }
        } else if playbackStarted && !playerNode.isPlaying && scheduledFrames >= steadyPrebufferFrames {
            do {
                try ensureEngineRunningUnsafe()
                playerNode.play()
            } catch {
                handleError(
                    SessionFailure(
                        code: .webrtcNegotiationFailed,
                        message: L10n.tr("error.audio_playback_unavailable")
                    )
                )
            }
        }
    }

    private func nextFrameToScheduleUnsafe() -> QueuedPcmFrame? {
        while let head = pendingFrames.first, let expectedSequence, head.frame.sequence < expectedSequence {
            pendingFrames.removeFirst()
            staleFrameDrops += 1
        }

        guard !pendingFrames.isEmpty else {
            return nil
        }

        let next = pendingFrames.removeFirst()
        if let expectedSequence {
            self.expectedSequence = max(expectedSequence, next.frame.sequence) + 1
        } else {
            self.expectedSequence = next.frame.sequence + 1
        }
        return next
    }

    private func ensureEngineRunningUnsafe() throws {
        if !engine.isRunning {
            try engine.start()
        }
    }

    private func resetUnsafe(notifyDiagnostics: Bool) {
        generation &+= 1
        playerNode.stop()
        engine.stop()
        engine.reset()
        currentFormat = nil
        currentFormatKey = nil
        pendingFrames.removeAll()
        scheduledFrames = 0
        playbackStarted = false
        expectedSequence = nil
        playedFrames = 0
        decodedPackets = 0
        staleFrameDrops = 0
        queueOverflowDrops = 0
        currentSampleRate = 0
        currentChannels = 0
        currentBitsPerSample = 0
        currentFrameSamplesPerChannel = 0
        if notifyDiagnostics {
            diagnosticsHandler(AudioStreamDiagnostics())
        }
    }

    private func publishDiagnosticsUnsafe() {
        diagnosticsHandler(
            AudioStreamDiagnostics(
                source: source,
                sampleRate: currentSampleRate,
                channels: currentChannels,
                bitsPerSample: currentBitsPerSample,
                frameSamplesPerChannel: currentFrameSamplesPerChannel,
                frameDurationMs: currentSampleRate > 0
                    ? Int((Double(currentFrameSamplesPerChannel) / Double(currentSampleRate)) * 1000.0)
                    : 0,
                startupTargetFrames: startupPrebufferFrames,
                targetPrebufferFrames: playbackStarted ? steadyPrebufferFrames : startupPrebufferFrames,
                maxQueueFrames: maxQueueFrames,
                queueDepthFrames: pendingFrames.count + scheduledFrames,
                playedFrames: playedFrames,
                decodedPackets: decodedPackets,
                staleFrameDrops: staleFrameDrops,
                queueOverflowDrops: queueOverflowDrops
            )
        )
    }

    private func handleError(_ failure: SessionFailure) {
        errorHandler?(failure)
    }

    private static func realtimeNowMs() -> UInt64 {
        DispatchTime.now().uptimeNanoseconds / 1_000_000
    }
}
