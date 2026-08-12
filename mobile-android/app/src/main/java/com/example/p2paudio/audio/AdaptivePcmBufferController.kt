package com.example.p2paudio.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class AdaptivePcmBufferSnapshot(
    val startupTargetFrames: Int,
    val targetPrebufferFrames: Int,
    val basePrebufferFrames: Int,
    val estimatedJitterMs: Int
)

internal class AdaptivePcmBufferController(
    private val startupPrebufferFrames: Int,
    private val steadyPrebufferFrames: Int,
    maxQueueFrames: Int
) {
    private val maxTargetFrames = (maxQueueFrames - 1).coerceAtLeast(steadyPrebufferFrames)
    private val maxStartupFrames = (startupPrebufferFrames + STARTUP_RECOVERY_EXTRA_FRAMES)
        .coerceAtMost(maxTargetFrames)
    private var frameDurationMs: Long = 20L
    private var currentTargetFrames: Int = steadyPrebufferFrames
    private var estimatedJitterMs: Double = 0.0
    private var lastArrivalRealtimeMs: Long? = null
    private var lastSenderTimestampMs: Long? = null
    private var stablePlaybackFrames: Int = 0
    private var pressureBoostFrames: Int = 0
    private var consecutivePlaybackWaits: Int = 0

    fun reset(frameDurationMs: Long) {
        this.frameDurationMs = frameDurationMs.coerceAtLeast(1L)
        currentTargetFrames = steadyPrebufferFrames
        estimatedJitterMs = 0.0
        lastArrivalRealtimeMs = null
        lastSenderTimestampMs = null
        stablePlaybackFrames = 0
        pressureBoostFrames = 0
        consecutivePlaybackWaits = 0
    }

    fun onFrameArrived(frame: PcmFrame, arrivalRealtimeMs: Long) {
        val previousArrival = lastArrivalRealtimeMs
        val previousTimestamp = lastSenderTimestampMs
        if (previousArrival != null && previousTimestamp != null && frame.timestampMs > previousTimestamp) {
            val arrivalDeltaMs = arrivalRealtimeMs - previousArrival
            val senderDeltaMs = (frame.timestampMs - previousTimestamp).coerceAtLeast(1L)
            val variationMs = abs(arrivalDeltaMs - senderDeltaMs).toDouble()
            val smoothingDivisor = if (variationMs >= estimatedJitterMs) 4.0 else 12.0
            estimatedJitterMs += (variationMs - estimatedJitterMs) / smoothingDivisor
            currentTargetFrames = max(currentTargetFrames, recommendedTargetFrames())
        }

        lastArrivalRealtimeMs = arrivalRealtimeMs
        lastSenderTimestampMs = frame.timestampMs
    }

    fun onGapConcealed() {
        registerPressure(extraFrames = 4)
    }

    fun onLateFrameDropped() {
        registerPressure(extraFrames = 2)
    }

    fun onQueueOverflow() {
        registerPressure(extraFrames = 2)
    }

    fun onAudioTrackUnderrun(underrunDelta: Int) {
        if (underrunDelta > 0) {
            registerPressure(extraFrames = (underrunDelta * 3).coerceAtMost(8))
        }
    }

    fun onPlaybackWait() {
        consecutivePlaybackWaits++
        if (consecutivePlaybackWaits < PLAYBACK_WAIT_PRESSURE_THRESHOLD) {
            return
        }
        consecutivePlaybackWaits = 0
        registerPressure(extraFrames = 1)
    }

    fun onFramePlayed(queueDepthFrames: Int) {
        consecutivePlaybackWaits = 0
        val stableEnough = queueDepthFrames >= (currentTargetFrames - 1).coerceAtLeast(0)
        stablePlaybackFrames = if (stableEnough) stablePlaybackFrames + 1 else 0
        if (stablePlaybackFrames < STABLE_PLAYBACK_THRESHOLD_FRAMES) {
            return
        }

        stablePlaybackFrames = 0
        if (pressureBoostFrames > 0) {
            pressureBoostFrames--
        }

        val recommended = recommendedTargetFrames()
        if (currentTargetFrames > recommended) {
            currentTargetFrames--
        }
    }

    fun snapshot(): AdaptivePcmBufferSnapshot {
        return AdaptivePcmBufferSnapshot(
            startupTargetFrames = currentTargetFrames
                .coerceAtMost(maxStartupFrames)
                .coerceAtLeast(startupPrebufferFrames),
            targetPrebufferFrames = currentTargetFrames,
            basePrebufferFrames = steadyPrebufferFrames,
            estimatedJitterMs = estimatedJitterMs.roundToInt()
        )
    }

    private fun registerPressure(extraFrames: Int) {
        consecutivePlaybackWaits = 0
        pressureBoostFrames = (pressureBoostFrames + extraFrames)
            .coerceAtMost(maxTargetFrames - steadyPrebufferFrames)
        stablePlaybackFrames = 0
        currentTargetFrames = (recommendedTargetFrames() + pressureBoostFrames)
            .coerceAtMost(maxTargetFrames)
    }

    private fun recommendedTargetFrames(): Int {
        val jitterFrames = ceil(estimatedJitterMs / frameDurationMs.toDouble()).toInt()
        val safetyFrames = if (estimatedJitterMs >= frameDurationMs / 2.0) 1 else 0
        val burstFrames = when {
            estimatedJitterMs >= frameDurationMs * 2.5 -> 2
            estimatedJitterMs >= frameDurationMs * 1.25 -> 1
            else -> 0
        }
        return (steadyPrebufferFrames + jitterFrames + safetyFrames + burstFrames)
            .coerceIn(steadyPrebufferFrames, maxTargetFrames)
    }

    private companion object {
        private const val STABLE_PLAYBACK_THRESHOLD_FRAMES = 32
        private const val PLAYBACK_WAIT_PRESSURE_THRESHOLD = 3
        private const val STARTUP_RECOVERY_EXTRA_FRAMES = 4
    }
}
