package com.example.p2paudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLatencyPresetTest {

    @Test
    fun defaultPresetKeepsExistingBalancedDefaults() {
        assertSame(PlaybackLatencyPreset.MS_50, PlaybackLatencyPreset.default)

        assertEquals(3, PlaybackLatencyPreset.MS_50.webrtcConfig.startupPrebufferFrames)
        assertEquals(3, PlaybackLatencyPreset.MS_50.webrtcConfig.steadyPrebufferFrames)
        assertEquals(20, PlaybackLatencyPreset.MS_50.webrtcConfig.maxQueueFrames)
        assertEquals(8, PlaybackLatencyPreset.MS_50.webrtcConfig.minTrackBufferFrames)

        assertEquals(4, PlaybackLatencyPreset.MS_50.udpOpusConfig.startupPrebufferFrames)
        assertEquals(4, PlaybackLatencyPreset.MS_50.udpOpusConfig.steadyPrebufferFrames)
        assertEquals(24, PlaybackLatencyPreset.MS_50.udpOpusConfig.maxQueueFrames)
        assertEquals(12, PlaybackLatencyPreset.MS_50.udpOpusConfig.minTrackBufferFrames)
    }

    @Test
    fun higherLatencyPresetsUseMoreBufferThanLowerLatencyPresets() {
        assertTrue(
            PlaybackLatencyPreset.MS_300.webrtcConfig.steadyPrebufferFrames >
                PlaybackLatencyPreset.MS_20.webrtcConfig.steadyPrebufferFrames
        )
        assertTrue(
            PlaybackLatencyPreset.MS_300.udpOpusConfig.maxQueueFrames >
                PlaybackLatencyPreset.MS_20.udpOpusConfig.maxQueueFrames
        )
    }

    @Test
    fun fromStorageValueSupportsLegacyNamesAndFallsBackToDefaultForUnknownValues() {
        assertSame(
            PlaybackLatencyPreset.default,
            PlaybackLatencyPreset.fromStorageValue("unknown")
        )
        assertSame(
            PlaybackLatencyPreset.MS_100,
            PlaybackLatencyPreset.fromStorageValue(PlaybackLatencyPreset.MS_100.name)
        )
        assertSame(
            PlaybackLatencyPreset.MS_20,
            PlaybackLatencyPreset.fromStorageValue("LOW")
        )
        assertSame(
            PlaybackLatencyPreset.MS_50,
            PlaybackLatencyPreset.fromStorageValue("BALANCED")
        )
        assertSame(
            PlaybackLatencyPreset.MS_100,
            PlaybackLatencyPreset.fromStorageValue("STABLE")
        )
    }
}
