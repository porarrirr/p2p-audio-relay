package com.example.p2paudio.audio

import androidx.annotation.StringRes
import com.example.p2paudio.R

data class PlaybackBufferConfig(
    val startupPrebufferFrames: Int,
    val steadyPrebufferFrames: Int,
    val maxQueueFrames: Int,
    val minTrackBufferFrames: Int
)

enum class PlaybackLatencyPreset(
    @StringRes val labelResId: Int,
    @StringRes val descriptionResId: Int,
    val webrtcConfig: PlaybackBufferConfig,
    val udpOpusConfig: PlaybackBufferConfig
) {
    MS_20(
        labelResId = R.string.receiver_latency_20,
        descriptionResId = R.string.receiver_latency_20_description,
        webrtcConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 2,
            steadyPrebufferFrames = 2,
            maxQueueFrames = 12,
            minTrackBufferFrames = 6
        ),
        udpOpusConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 3,
            steadyPrebufferFrames = 3,
            maxQueueFrames = 18,
            minTrackBufferFrames = 8
        )
    ),
    MS_50(
        labelResId = R.string.receiver_latency_50,
        descriptionResId = R.string.receiver_latency_50_description,
        webrtcConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 3,
            steadyPrebufferFrames = 3,
            maxQueueFrames = 20,
            minTrackBufferFrames = 8
        ),
        udpOpusConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 4,
            steadyPrebufferFrames = 4,
            maxQueueFrames = 24,
            minTrackBufferFrames = 12
        )
    ),
    MS_100(
        labelResId = R.string.receiver_latency_100,
        descriptionResId = R.string.receiver_latency_100_description,
        webrtcConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 5,
            steadyPrebufferFrames = 5,
            maxQueueFrames = 28,
            minTrackBufferFrames = 12
        ),
        udpOpusConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 6,
            steadyPrebufferFrames = 6,
            maxQueueFrames = 32,
            minTrackBufferFrames = 16
        )
    ),
    MS_300(
        labelResId = R.string.receiver_latency_300,
        descriptionResId = R.string.receiver_latency_300_description,
        webrtcConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 15,
            steadyPrebufferFrames = 15,
            maxQueueFrames = 48,
            minTrackBufferFrames = 24
        ),
        udpOpusConfig = PlaybackBufferConfig(
            startupPrebufferFrames = 15,
            steadyPrebufferFrames = 15,
            maxQueueFrames = 56,
            minTrackBufferFrames = 28
        )
    );

    companion object {
        val default: PlaybackLatencyPreset = MS_50

        fun fromStorageValue(rawValue: String?): PlaybackLatencyPreset {
            return when (rawValue) {
                LEGACY_LOW_NAME -> MS_20
                LEGACY_BALANCED_NAME -> MS_50
                LEGACY_STABLE_NAME -> MS_100
                else -> entries.firstOrNull { it.name == rawValue } ?: default
            }
        }

        private const val LEGACY_LOW_NAME = "LOW"
        private const val LEGACY_BALANCED_NAME = "BALANCED"
        private const val LEGACY_STABLE_NAME = "STABLE"
    }
}
