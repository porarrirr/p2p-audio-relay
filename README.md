# P2P Device Audio Relay

Native Android, iOS, and Windows implementations for relaying device audio directly between two devices without media-relay or signaling servers. All platforms target the same v2 pairing and transport protocol.

## Structure

- `docs/`: protocol, state machine, constraints, and test matrix.
- `mobile-android/`: Android sender/receiver implementation (Kotlin).
- `mobile-ios/`: iOS sender/receiver implementation (Swift + ReplayKit extension).
- `desktop-windows/`: Windows implementation (WinUI + core protocol/audio logic + native WebRTC bridge).

## Constraints

- No media relay server and no signaling server.
- LAN-only connection using host ICE candidates.
- USB tethering is supported as a LAN-equivalent IP path (Android USB tethering / iPhone Personal Hotspot over USB).
- 1:1 session via out-of-band payload exchange.
- Payload transport supports compressed mode (`p2paudio-z1:` zlib + Base64URL).
- iOS sender can capture ReplayKit app audio only, not system-wide audio.

See [known limitations](docs/KNOWN_LIMITATIONS.md) and the [test matrix](docs/TEST_MATRIX.md) before treating a platform pair as supported.

## Windows audio dependency

The Windows application consumes the official [`NAudio` 2.2.1 NuGet package](https://www.nuget.org/packages/NAudio/2.2.1) through `PackageReference`. No copied or decompiled NAudio source is included in this repository. Restore dependencies with the standard .NET/NuGet workflow before building the Windows solution.

Third-party dependency information is recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

No license is currently granted for this repository's original code. Copyright remains with its respective owner. Third-party packages remain subject to their own licenses; the absence of a project license does not replace or modify those terms.
