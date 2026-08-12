import XCTest
@testable import P2PAudio

final class PlaybackLatencyPresetTests: XCTestCase {
    func testDefaultPresetIs50Ms() {
        XCTAssertEqual(PlaybackLatencyPreset.defaultPreset, .ms50)
    }

    func testLegacyStorageValuesMapToCurrentPresets() {
        XCTAssertEqual(PlaybackLatencyPreset.fromStorageValue("LOW"), .ms20)
        XCTAssertEqual(PlaybackLatencyPreset.fromStorageValue("BALANCED"), .ms50)
        XCTAssertEqual(PlaybackLatencyPreset.fromStorageValue("STABLE"), .ms100)
    }
}
