package com.example.p2paudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePcmBufferControllerTest {

    @Test
    fun audioTrackUnderrunRaisesTargetImmediately() {
        val controller = AdaptivePcmBufferController(
            startupPrebufferFrames = 2,
            steadyPrebufferFrames = 2,
            maxQueueFrames = 12
        )
        controller.reset(frameDurationMs = 20)

        controller.onAudioTrackUnderrun(underrunDelta = 1)

        assertTrue(controller.snapshot().targetPrebufferFrames > 2)
    }

    @Test
    fun repeatedJitterIncreasesTargetPrebuffer() {
        val controller = AdaptivePcmBufferController(
            startupPrebufferFrames = 2,
            steadyPrebufferFrames = 2,
            maxQueueFrames = 12
        )
        controller.reset(frameDurationMs = 20)

        controller.onFrameArrived(frame(sequence = 0, timestampMs = 0), arrivalRealtimeMs = 0)
        controller.onFrameArrived(frame(sequence = 1, timestampMs = 20), arrivalRealtimeMs = 70)
        controller.onFrameArrived(frame(sequence = 2, timestampMs = 40), arrivalRealtimeMs = 110)
        controller.onFrameArrived(frame(sequence = 3, timestampMs = 60), arrivalRealtimeMs = 180)

        assertTrue(controller.snapshot().targetPrebufferFrames > 2)
        assertTrue(controller.snapshot().estimatedJitterMs > 0)
    }

    @Test
    fun stablePlaybackGraduallyReturnsToBaseTarget() {
        val controller = AdaptivePcmBufferController(
            startupPrebufferFrames = 2,
            steadyPrebufferFrames = 2,
            maxQueueFrames = 12
        )
        controller.reset(frameDurationMs = 20)
        controller.onAudioTrackUnderrun(underrunDelta = 1)

        repeat(160) {
            controller.onFramePlayed(queueDepthFrames = 4)
        }

        assertEquals(2, controller.snapshot().targetPrebufferFrames)
    }

    @Test
    fun queueOverflowRaisesTargetPrebuffer() {
        val controller = AdaptivePcmBufferController(
            startupPrebufferFrames = 2,
            steadyPrebufferFrames = 2,
            maxQueueFrames = 12
        )
        controller.reset(frameDurationMs = 20)

        controller.onQueueOverflow()

        assertTrue(controller.snapshot().targetPrebufferFrames > 2)
    }

    @Test
    fun transientPlaybackWaitDoesNotImmediatelyRaiseTarget() {
        val controller = AdaptivePcmBufferController(
            startupPrebufferFrames = 2,
            steadyPrebufferFrames = 2,
            maxQueueFrames = 12
        )
        controller.reset(frameDurationMs = 20)

        repeat(2) {
            controller.onPlaybackWait()
        }

        assertEquals(2, controller.snapshot().targetPrebufferFrames)
    }

    @Test
    fun sustainedPlaybackWaitRaisesTarget() {
        val controller = AdaptivePcmBufferController(
            startupPrebufferFrames = 2,
            steadyPrebufferFrames = 2,
            maxQueueFrames = 12
        )
        controller.reset(frameDurationMs = 20)

        repeat(3) {
            controller.onPlaybackWait()
        }

        assertTrue(controller.snapshot().targetPrebufferFrames > 2)
    }

    @Test
    fun startupTargetRemainsCappedAfterHeavyPressure() {
        val controller = AdaptivePcmBufferController(
            startupPrebufferFrames = 6,
            steadyPrebufferFrames = 6,
            maxQueueFrames = 32
        )
        controller.reset(frameDurationMs = 20)

        repeat(10) {
            controller.onAudioTrackUnderrun(underrunDelta = 3)
        }

        val snapshot = controller.snapshot()
        assertEquals(31, snapshot.targetPrebufferFrames)
        assertEquals(10, snapshot.startupTargetFrames)
    }

    private fun frame(sequence: Int, timestampMs: Long): PcmFrame {
        return PcmFrame(
            sequence = sequence,
            timestampMs = timestampMs,
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
            frameSamplesPerChannel = 960,
            pcmBytes = ByteArray(3_840)
        )
    }
}
