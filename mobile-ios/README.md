# iOS App

This folder contains the Swift iOS app and ReplayKit broadcast extension for the shared v2 pairing/audio protocol.

## Supported flows

- `WebRTC` sender and receiver flow using:
  - init / confirm payload exchange,
  - Windows connection codes (`p2paudio-c1:`),
  - compressed payload transport (`p2paudio-z1:`).
- `UDP + Opus` listener flow for **Windows -> iPhone** playback using the Android-aligned connection-code exchange.
- Receiver latency presets aligned with Android:
  - `20 ms`
  - `50 ms` (default)
  - `100 ms`
  - `300 ms`

## Current platform behavior

- iOS sender uses ReplayKit app-audio capture.
- iOS UDP + Opus sender mode is intentionally unavailable, matching Android.
- Windows -> iPhone is supported over:
  - WebRTC DataChannel PCM
  - UDP + Opus connection-code receive mode

## Required Xcode setup

1. Create an iOS App target named `P2PAudio`.
2. Add a Broadcast Upload Extension target named `AudioBroadcastExtension`.
3. Add the Swift Package dependency declared in `project.yml`.
4. Enable:
   - App Groups
   - Background Modes -> Audio, AirPlay, and Picture in Picture
5. Optional: generate the project from `mobile-ios/project.yml` using `xcodegen`.

## GitHub Actions

Workflow: `.github/workflows/ios-ipa.yml`

The workflow now:

1. resolves or generates the Xcode project,
2. runs iOS unit tests on a simulator,
3. archives an unsigned `.ipa`,
4. uploads the IPA artifact,
5. optionally publishes it to GitHub Releases.

## Notes

- iOS sender can capture ReplayKit app audio only.
- System-wide sounds (for example ringtones/notifications) cannot be captured by third-party apps.
- There is no relay or signaling server; all flows are LAN / USB-IP only.
- USB support with Windows uses Personal Hotspot over USB (IP path), not direct accessory transport.
- Unsigned IPA output from CI still needs re-signing before installation on physical devices.
