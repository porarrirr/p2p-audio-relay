# P2P Device Audio Relay

English | [日本語](README.ja.md)

A cross-platform app for sending device audio directly between two devices on the same local network. It includes native Android, iOS, and Windows apps and does not require a media-relay or signaling server.

## Highlights

- Direct one-to-one audio sessions over a LAN
- Android, iOS, and Windows implementations
- Pairing through an out-of-band payload
- USB tethering supported as a local IP connection
- No project-operated relay, signaling, analytics, or account service

## Important limitations

- Connections use local-network host candidates and are intended for LAN use.
- iOS can capture audio exposed by ReplayKit; it cannot capture all system audio.
- Platform combinations should be checked against the [test matrix](docs/TEST_MATRIX.md).
- Review the [known limitations](docs/KNOWN_LIMITATIONS.md) before relying on a specific sender and receiver pair.

Setup and platform-specific instructions are maintained in the relevant app directories and under [`docs/`](docs/).

## Dependencies

The Windows app uses the official [`NAudio` 2.2.1 NuGet package](https://www.nuget.org/packages/NAudio/2.2.1). Third-party license information is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

No license is currently granted for this repository's original code. Third-party components remain subject to their own licenses.
