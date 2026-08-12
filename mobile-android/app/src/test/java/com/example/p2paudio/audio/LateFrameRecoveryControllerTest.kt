package com.example.p2paudio.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LateFrameRecoveryControllerTest {

    @Test
    fun waitsForLateFrameWhileTrackHasSafeHeadroom() {
        val controller = LateFrameRecoveryController()

        assertTrue(
            controller.shouldKeepWaiting(
                expectedSequence = 42,
                frameDurationMs = 20L,
                bufferedPacketCount = 16,
                nowRealtimeMs = 1_000L
            )
        )
        assertTrue(
            controller.shouldKeepWaiting(
                expectedSequence = 42,
                frameDurationMs = 20L,
                bufferedPacketCount = 12,
                nowRealtimeMs = 1_120L
            )
        )
    }

    @Test
    fun stopsWaitingAfterGraceBudgetExpires() {
        val controller = LateFrameRecoveryController()

        assertTrue(
            controller.shouldKeepWaiting(
                expectedSequence = 7,
                frameDurationMs = 20L,
                bufferedPacketCount = 16,
                nowRealtimeMs = 2_000L
            )
        )
        assertFalse(
            controller.shouldKeepWaiting(
                expectedSequence = 7,
                frameDurationMs = 20L,
                bufferedPacketCount = 16,
                nowRealtimeMs = 2_210L
            )
        )
    }

    @Test
    fun stopsWaitingWhenTrackHeadroomFallsToSafetyMargin() {
        val controller = LateFrameRecoveryController()

        assertTrue(
            controller.shouldKeepWaiting(
                expectedSequence = 99,
                frameDurationMs = 60L,
                bufferedPacketCount = 4,
                nowRealtimeMs = 3_000L
            )
        )
        assertFalse(
            controller.shouldKeepWaiting(
                expectedSequence = 99,
                frameDurationMs = 60L,
                bufferedPacketCount = 2,
                nowRealtimeMs = 3_060L
            )
        )
    }

    @Test
    fun newSequenceStartsFreshWaitBudget() {
        val controller = LateFrameRecoveryController()

        assertTrue(
            controller.shouldKeepWaiting(
                expectedSequence = 10,
                frameDurationMs = 20L,
                bufferedPacketCount = 16,
                nowRealtimeMs = 4_000L
            )
        )
        assertFalse(
            controller.shouldKeepWaiting(
                expectedSequence = 10,
                frameDurationMs = 20L,
                bufferedPacketCount = 16,
                nowRealtimeMs = 4_210L
            )
        )

        controller.reset()

        assertTrue(
            controller.shouldKeepWaiting(
                expectedSequence = 11,
                frameDurationMs = 20L,
                bufferedPacketCount = 16,
                nowRealtimeMs = 4_240L
            )
        )
    }
}
