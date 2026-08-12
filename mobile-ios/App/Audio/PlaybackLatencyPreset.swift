import Foundation

struct PlaybackBufferConfig {
    let startupPrebufferFrames: Int
    let steadyPrebufferFrames: Int
    let maxQueueFrames: Int
    let minTrackBufferFrames: Int
}

enum PlaybackLatencyPreset: String, CaseIterable, Identifiable {
    case ms20 = "MS_20"
    case ms50 = "MS_50"
    case ms100 = "MS_100"
    case ms300 = "MS_300"

    var id: String { rawValue }

    var labelKey: String {
        switch self {
        case .ms20:
            return "receiver_latency_20"
        case .ms50:
            return "receiver_latency_50"
        case .ms100:
            return "receiver_latency_100"
        case .ms300:
            return "receiver_latency_300"
        }
    }

    var descriptionKey: String {
        switch self {
        case .ms20:
            return "receiver_latency_20_description"
        case .ms50:
            return "receiver_latency_50_description"
        case .ms100:
            return "receiver_latency_100_description"
        case .ms300:
            return "receiver_latency_300_description"
        }
    }

    var localizedLabel: String { L10n.tr(labelKey) }

    var localizedDescription: String { L10n.tr(descriptionKey) }

    var webRtcConfig: PlaybackBufferConfig {
        switch self {
        case .ms20:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 2,
                steadyPrebufferFrames: 2,
                maxQueueFrames: 12,
                minTrackBufferFrames: 6
            )
        case .ms50:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 3,
                steadyPrebufferFrames: 3,
                maxQueueFrames: 20,
                minTrackBufferFrames: 8
            )
        case .ms100:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 5,
                steadyPrebufferFrames: 5,
                maxQueueFrames: 28,
                minTrackBufferFrames: 12
            )
        case .ms300:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 15,
                steadyPrebufferFrames: 15,
                maxQueueFrames: 48,
                minTrackBufferFrames: 24
            )
        }
    }

    var udpOpusConfig: PlaybackBufferConfig {
        switch self {
        case .ms20:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 3,
                steadyPrebufferFrames: 3,
                maxQueueFrames: 18,
                minTrackBufferFrames: 8
            )
        case .ms50:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 4,
                steadyPrebufferFrames: 4,
                maxQueueFrames: 24,
                minTrackBufferFrames: 12
            )
        case .ms100:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 6,
                steadyPrebufferFrames: 6,
                maxQueueFrames: 32,
                minTrackBufferFrames: 16
            )
        case .ms300:
            return PlaybackBufferConfig(
                startupPrebufferFrames: 15,
                steadyPrebufferFrames: 15,
                maxQueueFrames: 56,
                minTrackBufferFrames: 28
            )
        }
    }

    static let defaultPreset: PlaybackLatencyPreset = .ms50

    static func fromStorageValue(_ rawValue: String?) -> PlaybackLatencyPreset {
        switch rawValue {
        case "LOW":
            return .ms20
        case "BALANCED":
            return .ms50
        case "STABLE":
            return .ms100
        default:
            return Self.allCases.first(where: { $0.rawValue == rawValue }) ?? defaultPreset
        }
    }
}
