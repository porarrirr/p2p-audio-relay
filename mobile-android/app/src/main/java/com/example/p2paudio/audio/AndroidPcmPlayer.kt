package com.example.p2paudio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.example.p2paudio.logging.AppLogger
import com.example.p2paudio.model.AudioStreamDiagnostics
import com.example.p2paudio.model.AudioStreamSource
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal data class QueueOverflowTrimResult(
    val droppedFrameCount: Int,
    val firstDroppedSequence: Int?,
    val lastDroppedSequence: Int?,
    val nextExpectedSequence: Int?
)

internal fun shouldWaitForMissingFrameQueueRecovery(
    bufferedPacketCount: Int,
    startupTargetFrames: Int
): Boolean {
    return bufferedPacketCount < startupTargetFrames.coerceAtLeast(1)
}

internal fun trimOverflowFramesForRealtimePlayback(
    pendingFrames: PriorityQueue<PcmFrame>,
    maxQueueFrames: Int,
    expectedSequence: Int?
): QueueOverflowTrimResult {
    var nextExpectedSequence = expectedSequence
    var droppedFrameCount = 0
    var firstDroppedSequence: Int? = null
    var lastDroppedSequence: Int? = null
    while (pendingFrames.size > maxQueueFrames) {
        val droppedFrame = pendingFrames.poll() ?: break
        if (firstDroppedSequence == null) {
            firstDroppedSequence = droppedFrame.sequence
        }
        lastDroppedSequence = droppedFrame.sequence
        droppedFrameCount++
        if (nextExpectedSequence != null && droppedFrame.sequence >= nextExpectedSequence) {
            nextExpectedSequence = droppedFrame.sequence + 1
        }
    }
    return QueueOverflowTrimResult(
        droppedFrameCount = droppedFrameCount,
        firstDroppedSequence = firstDroppedSequence,
        lastDroppedSequence = lastDroppedSequence,
        nextExpectedSequence = nextExpectedSequence
    )
}

class AndroidPcmPlayer(
    private val source: AudioStreamSource,
    private val startupPrebufferFrames: Int = 3,
    private val steadyPrebufferFrames: Int = 3,
    private val maxQueueFrames: Int = 20,
    private val minTrackBufferFrames: Int = 8,
    private val diagnosticsListener: (AudioStreamDiagnostics) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val lock = Object()
    private val pendingFrames = PriorityQueue<PcmFrame>(compareBy { it.sequence })
    private val adaptiveBufferController = AdaptivePcmBufferController(
        startupPrebufferFrames = startupPrebufferFrames,
        steadyPrebufferFrames = steadyPrebufferFrames,
        maxQueueFrames = maxQueueFrames
    )
    private val lateFrameRecoveryController = LateFrameRecoveryController()

    private var playbackThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var expectedSequence: Int? = null
    private var lastFormatKey: String? = null
    private var frameBytes: Int = 0
    private var frameDurationMs: Long = 20
    private var playedFrames: Long = 0
    private var insertedSilenceFrames: Long = 0
    private var queueOverflowDrops: Long = 0
    private var staleFrameDrops: Long = 0
    private var audioTrackUnderruns: Int = 0
    private var lastObservedTrackUnderrunCount: Int = 0
    private var currentSampleRate: Int = 0
    private var currentChannels: Int = 0
    private var currentBitsPerSample: Int = 0
    private var currentFrameSamplesPerChannel: Int = 0
    private var audioTrackBufferFrames: Int = 0
    private var bytesPerAudioFrame: Int = 0
    private var totalTrackFramesWritten: Long = 0L
    private var lastStatsLogAtMs: Long = System.currentTimeMillis()
    private var lastWarningLogAtMs: Long = 0L
    private var lastDiagnosticsDispatchAtMs: Long = 0L
    private var rebuffering: Boolean = false

    fun enqueue(frame: PcmFrame, arrivalRealtimeMs: Long = SystemClock.elapsedRealtime()) {
        if (frame.pcmBytes.isEmpty()) {
            return
        }

        synchronized(lock) {
            val formatKey = "${frame.sampleRate}-${frame.channels}-${frame.bitsPerSample}"
            if (lastFormatKey != null && lastFormatKey != formatKey) {
                AppLogger.i(
                    "PcmPlayer",
                    "player_format_change",
                    "PCM player format changed; resetting playback state",
                    context = mapOf("from" to lastFormatKey, "to" to formatKey)
                )
                resetUnsafe()
            }
            if (audioTrack == null) {
                audioTrack = createTrack(frame)
                frameBytes = frame.pcmBytes.size
                frameDurationMs = ((frame.frameSamplesPerChannel * 1000L) / frame.sampleRate).coerceAtLeast(1L)
                lastFormatKey = formatKey
                currentSampleRate = frame.sampleRate
                currentChannels = frame.channels
                currentBitsPerSample = frame.bitsPerSample
                currentFrameSamplesPerChannel = frame.frameSamplesPerChannel
                bytesPerAudioFrame = (frame.channels * (frame.bitsPerSample / 8)).coerceAtLeast(1)
                totalTrackFramesWritten = 0L
                adaptiveBufferController.reset(frameDurationMs)
                lateFrameRecoveryController.reset()
                lastObservedTrackUnderrunCount = audioTrack?.underrunCount ?: 0
                audioTrackBufferFrames = audioTrack?.bufferSizeInFrames ?: 0
            }

            adaptiveBufferController.onFrameArrived(frame, arrivalRealtimeMs)
            pendingFrames.add(frame)
            val overflowTrimResult = trimOverflowFramesForRealtimePlayback(
                pendingFrames = pendingFrames,
                maxQueueFrames = maxQueueFrames,
                expectedSequence = expectedSequence
            )
            if (overflowTrimResult.droppedFrameCount > 0) {
                expectedSequence = overflowTrimResult.nextExpectedSequence
                queueOverflowDrops += overflowTrimResult.droppedFrameCount
                adaptiveBufferController.onQueueOverflow()
                logPlaybackWarning(
                    event = "player_queue_overflow",
                    message = "Dropped queued PCM frames and resynchronized playback to cap latency",
                    context = mapOf(
                        "queueSize" to pendingFrames.size,
                        "droppedFrames" to overflowTrimResult.droppedFrameCount,
                        "firstDroppedSequence" to overflowTrimResult.firstDroppedSequence,
                        "lastDroppedSequence" to overflowTrimResult.lastDroppedSequence,
                        "expectedSequence" to expectedSequence,
                        "queueOverflowDrops" to queueOverflowDrops
                    )
                )
            }
            lock.notifyAll()
        }

        publishDiagnosticsIfNeeded()
        if (running.compareAndSet(false, true)) {
            startPlaybackLoop()
        }
    }

    fun stop() {
        var stopPlayedFrames: Long
        var stopInsertedSilenceFrames: Long
        var stopQueueOverflowDrops: Long
        var stopStaleFrameDrops: Long
        var stopAudioTrackUnderruns: Int
        running.set(false)
        synchronized(lock) {
            lock.notifyAll()
        }
        val thread = playbackThread
        thread?.interrupt()
        thread?.join(PLAYBACK_STOP_JOIN_TIMEOUT_MS)
        if (thread?.isAlive == true) {
            logPlaybackWarning(
                event = "player_stop_timeout",
                message = "PCM playback thread did not stop before reset",
                context = emptyMap()
            )
        }
        synchronized(lock) {
            stopPlayedFrames = playedFrames
            stopInsertedSilenceFrames = insertedSilenceFrames
            stopQueueOverflowDrops = queueOverflowDrops
            stopStaleFrameDrops = staleFrameDrops
            stopAudioTrackUnderruns = audioTrackUnderruns
            resetUnsafe()
            playedFrames = 0
            insertedSilenceFrames = 0
            queueOverflowDrops = 0
            staleFrameDrops = 0
            audioTrackUnderruns = 0
            lastStatsLogAtMs = System.currentTimeMillis()
            lastWarningLogAtMs = 0L
            lastDiagnosticsDispatchAtMs = 0L
        }
        playbackThread = null
        diagnosticsListener(AudioStreamDiagnostics())
        AppLogger.i(
            "PcmPlayer",
            "player_stop",
            "PCM player stopped",
            context = mapOf(
                "playedFrames" to stopPlayedFrames,
                "insertedSilenceFrames" to stopInsertedSilenceFrames,
                "queueOverflowDrops" to stopQueueOverflowDrops,
                "staleFrameDrops" to stopStaleFrameDrops,
                "audioTrackUnderruns" to stopAudioTrackUnderruns
            )
        )
    }

    private fun startPlaybackLoop() {
        playbackThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                while (running.get()) {
                    var trackToWrite: AudioTrack? = null
                    var bytesToWrite: ByteArray? = null
                    var shouldContinue = false

                    synchronized(lock) {
                        val track = audioTrack
                        if (track == null) {
                            waitForFramesUnsafe()
                            shouldContinue = true
                        } else {
                            val nextBytes = dequeueFrameUnsafe()
                            if (nextBytes == null) {
                                waitForFramesUnsafe()
                                shouldContinue = true
                            } else {
                                trackToWrite = track
                                bytesToWrite = nextBytes
                            }
                        }
                    }

                    if (shouldContinue) {
                        continue
                    }
                    if (trackToWrite == null || bytesToWrite == null) {
                        continue
                    }

                    val track = trackToWrite ?: continue
                    val bytes = bytesToWrite ?: continue
                    val writeResult = try {
                        track.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
                    } catch (_: IllegalStateException) {
                        if (!running.get()) {
                            return@Thread
                        }
                        logPlaybackWarning(
                            event = "player_write_interrupted",
                            message = "AudioTrack write failed during shutdown",
                            context = emptyMap()
                        )
                        continue
                    }
                    if (writeResult < 0) {
                        logPlaybackWarning(
                            event = "player_write_failed",
                            message = "AudioTrack write failed",
                            context = mapOf("writeResult" to writeResult)
                        )
                        continue
                    }

                    synchronized(lock) {
                        if (bytesPerAudioFrame > 0) {
                            totalTrackFramesWritten += writeResult.toLong() / bytesPerAudioFrame
                        }
                        playedFrames++
                        adaptiveBufferController.onFramePlayed(
                            bufferedPacketCountForControllerUnsafe(track)
                        )
                    }
                    updateTrackUnderruns(track)
                    publishDiagnosticsIfNeeded()
                    logStatsIfNeeded()
                }
            } catch (_: InterruptedException) {
            } catch (error: Exception) {
                logPlaybackWarning(
                    event = "player_thread_failed",
                    message = "PCM playback thread stopped after an unexpected error",
                    context = mapOf("reason" to (error.message ?: "unknown"))
                )
            }
        }.apply {
            name = "android-pcm-player"
            isDaemon = true
            start()
        }
    }

    private fun dequeueFrameUnsafe(): ByteArray? {
        if (pendingFrames.isEmpty()) {
            if (expectedSequence != null) {
                adaptiveBufferController.onPlaybackWait()
            }
            return null
        }

        val adaptiveSnapshot = adaptiveBufferController.snapshot()
        if (rebuffering) {
            if (pendingFrames.size < adaptiveSnapshot.startupTargetFrames) {
                return null
            }
            rebuffering = false
        }
        val expected = expectedSequence
        if (expected == null) {
            if (pendingFrames.size < adaptiveSnapshot.startupTargetFrames) {
                return null
            }
            val first = pendingFrames.poll() ?: return null
            lateFrameRecoveryController.reset()
            expectedSequence = first.sequence + 1
            return first.pcmBytes
        }

        while (pendingFrames.isNotEmpty()) {
            val head = pendingFrames.peek() ?: break
            if (head.sequence >= expected) {
                break
            }
            pendingFrames.poll()
            staleFrameDrops++
            adaptiveBufferController.onLateFrameDropped()
        }

        val head = pendingFrames.peek()
        if (head != null && head.sequence == expected) {
            lateFrameRecoveryController.reset()
            expectedSequence = expected + 1
            val frame = pendingFrames.poll() ?: return null
            return frame.pcmBytes
        }

        val track = audioTrack
        val bufferedPacketCount = track?.let { bufferedPacketCountForControllerUnsafe(it) } ?: pendingFrames.size
        val shouldWaitForQueueRecovery = shouldWaitForMissingFrameQueueRecovery(
            bufferedPacketCount = bufferedPacketCount,
            startupTargetFrames = adaptiveSnapshot.startupTargetFrames
        )
        val shouldWaitForLateFrame = shouldKeepWaitingForMissingFrameUnsafe(expected)
        if (shouldWaitForQueueRecovery || shouldWaitForLateFrame) {
            adaptiveBufferController.onPlaybackWait()
            return null
        }

        lateFrameRecoveryController.reset()
        expectedSequence = expected + 1
        insertedSilenceFrames++
        adaptiveBufferController.onGapConcealed()
        logPlaybackWarning(
            event = "player_gap_concealed",
            message = "Inserted silence for a missing PCM frame",
            context = mapOf(
                "expectedSequence" to expected,
                "pendingFrames" to pendingFrames.size,
                "insertedSilenceFrames" to insertedSilenceFrames,
                "staleFrameDrops" to staleFrameDrops
            )
        )
        return ByteArray(frameBytes)
    }

    private fun createTrack(frame: PcmFrame): AudioTrack {
        val channelConfig = if (frame.channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            frame.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufferSize, frame.pcmBytes.size * minTrackBufferFrames)
        AppLogger.i(
            "PcmPlayer",
            "player_track_create",
            "Creating AudioTrack for remote PCM playback",
            context = mapOf(
                "sampleRate" to frame.sampleRate,
                "channels" to frame.channels,
                "frameBytes" to frame.pcmBytes.size,
                "bufferSize" to bufferSize
            )
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(frame.sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
            .apply { play() }
    }

    private fun updateTrackUnderruns(track: AudioTrack) {
        val currentUnderrunCount = track.underrunCount
        val underrunDelta = (currentUnderrunCount - lastObservedTrackUnderrunCount).coerceAtLeast(0)
        if (underrunDelta <= 0) {
            return
        }

        synchronized(lock) {
            audioTrackUnderruns += underrunDelta
            lastObservedTrackUnderrunCount = currentUnderrunCount
            adaptiveBufferController.onAudioTrackUnderrun(underrunDelta)
            enterRebufferingUnsafe()
        }
    }

    private fun publishDiagnosticsIfNeeded(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val diagnostics = synchronized(lock) {
            if (!force && now - lastDiagnosticsDispatchAtMs < DIAGNOSTICS_DISPATCH_INTERVAL_MS) {
                null
            } else {
                lastDiagnosticsDispatchAtMs = now
                val adaptiveSnapshot = adaptiveBufferController.snapshot()
                AudioStreamDiagnostics(
                source = if (currentSampleRate > 0) source else AudioStreamSource.NONE,
                sampleRate = currentSampleRate,
                channels = currentChannels,
                bitsPerSample = currentBitsPerSample,
                frameSamplesPerChannel = currentFrameSamplesPerChannel,
                frameDurationMs = frameDurationMs.toInt(),
                startupTargetFrames = adaptiveSnapshot.startupTargetFrames,
                targetPrebufferFrames = adaptiveSnapshot.targetPrebufferFrames,
                basePrebufferFrames = adaptiveSnapshot.basePrebufferFrames,
                maxQueueFrames = maxQueueFrames,
                queueDepthFrames = pendingFrames.size,
                audioTrackBufferFrames = audioTrackBufferFrames,
                estimatedJitterMs = adaptiveSnapshot.estimatedJitterMs,
                playedFrames = playedFrames,
                insertedSilenceFrames = insertedSilenceFrames,
                staleFrameDrops = staleFrameDrops,
                queueOverflowDrops = queueOverflowDrops,
                audioTrackUnderruns = audioTrackUnderruns
            )
            }
        }
        diagnostics?.let(diagnosticsListener)
    }

    private fun waitForFramesUnsafe() {
        lock.wait(frameDurationMs)
    }

    private fun resetUnsafe() {
        pendingFrames.clear()
        expectedSequence = null
        lastFormatKey = null
        frameBytes = 0
        frameDurationMs = 20
        currentSampleRate = 0
        currentChannels = 0
        currentBitsPerSample = 0
        currentFrameSamplesPerChannel = 0
        audioTrackBufferFrames = 0
        bytesPerAudioFrame = 0
        totalTrackFramesWritten = 0L
        lastObservedTrackUnderrunCount = 0
        rebuffering = false
        lateFrameRecoveryController.reset()
        audioTrack?.runCatching {
            pause()
            flush()
            stop()
            release()
        }
        audioTrack = null
        adaptiveBufferController.reset(frameDurationMs)
    }

    private fun logPlaybackWarning(event: String, message: String, context: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        if (now - lastWarningLogAtMs < WARNING_LOG_INTERVAL_MS) {
            return
        }
        lastWarningLogAtMs = now
        AppLogger.w("PcmPlayer", event, message, context)
    }

    private fun logStatsIfNeeded() {
        val diagnostics = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastStatsLogAtMs < STATS_LOG_INTERVAL_MS) {
                null
            } else {
                lastStatsLogAtMs = now
                val adaptiveSnapshot = adaptiveBufferController.snapshot()
                mapOf(
                    "playedFrames" to playedFrames,
                    "insertedSilenceFrames" to insertedSilenceFrames,
                    "queueOverflowDrops" to queueOverflowDrops,
                    "staleFrameDrops" to staleFrameDrops,
                    "queueDepth" to pendingFrames.size,
                    "targetPrebufferFrames" to adaptiveSnapshot.targetPrebufferFrames,
                    "estimatedJitterMs" to adaptiveSnapshot.estimatedJitterMs,
                    "audioTrackUnderruns" to audioTrackUnderruns
                )
            }
        }

        diagnostics?.let {
            AppLogger.d(
                "PcmPlayer",
                "player_stats",
                "PCM player stats",
                context = it
            )
        }
    }

    private fun enterRebufferingUnsafe() {
        if (rebuffering) {
            return
        }

        rebuffering = true
        expectedSequence = null
        lateFrameRecoveryController.reset()
        logPlaybackWarning(
            event = "player_rebuffer_start",
            message = "Entering adaptive rebuffering after playback pressure",
            context = mapOf(
                "pendingFrames" to pendingFrames.size,
                "audioTrackUnderruns" to audioTrackUnderruns
            )
        )
    }

    private fun shouldKeepWaitingForMissingFrameUnsafe(expectedSequence: Int): Boolean {
        val track = audioTrack ?: return false
        return lateFrameRecoveryController.shouldKeepWaiting(
            expectedSequence = expectedSequence,
            frameDurationMs = frameDurationMs,
            bufferedPacketCount = bufferedPacketCountForControllerUnsafe(track),
            nowRealtimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun bufferedPacketCountForControllerUnsafe(track: AudioTrack): Int {
        val trackPackets = if (currentFrameSamplesPerChannel <= 0) {
            0
        } else {
            val queuedTrackFrames = estimateQueuedTrackFramesUnsafe(track)
            (queuedTrackFrames + currentFrameSamplesPerChannel - 1) / currentFrameSamplesPerChannel
        }
        return pendingFrames.size + trackPackets
    }

    private fun estimateQueuedTrackFramesUnsafe(track: AudioTrack): Int {
        if (bytesPerAudioFrame <= 0 || totalTrackFramesWritten <= 0L || audioTrackBufferFrames <= 0) {
            return 0
        }
        val playbackHeadFrames = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val queuedTrackFrames = (totalTrackFramesWritten - playbackHeadFrames).coerceAtLeast(0L)
        return queuedTrackFrames
            .coerceAtMost(audioTrackBufferFrames.toLong())
            .toInt()
    }

    companion object {
        private const val PLAYBACK_STOP_JOIN_TIMEOUT_MS = 500L
        private const val STATS_LOG_INTERVAL_MS = 5_000L
        private const val WARNING_LOG_INTERVAL_MS = 1_000L
        private const val DIAGNOSTICS_DISPATCH_INTERVAL_MS = 250L
    }
}
