import AVFoundation
import SwiftUI

@main
struct P2PAudioApp: App {
    init() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.allowAirPlay])
            try session.setActive(true)
        } catch {
            FileHandle.standardError.write(Data("P2PAudio audio session setup failed: \(error)\n".utf8))
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
