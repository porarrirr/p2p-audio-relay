package com.example.p2paudio.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPcmPlayerGapRecoveryPolicyTest {

    @Test
    fun missingFrameQueueRecoveryWaitsWhileBufferedHeadroomIsBelowStartupTarget() {
        assertTrue(
            shouldWaitForMissingFrameQueueRecovery(
                bufferedPacketCount = 5,
                startupTargetFrames = 10
            )
        )
    }

    @Test
    fun missingFrameQueueRecoveryDoesNotWaitJustBecauseSteadyTargetHasGrown() {
        assertFalse(
            shouldWaitForMissingFrameQueueRecovery(
                bufferedPacketCount = 21,
                startupTargetFrames = 10
            )
        )
    }
}
