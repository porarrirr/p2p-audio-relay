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

The platform apps restore WebRTC, AndroidX, Kotlin, NAudio, libdatachannel, Opus, OpenSSL, PortAudio, libsrtp, usrsctp, zlib, and related dependencies from their official package sources. Required attribution, complete license texts, and the MPL-2.0 source-availability requirement are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The build configurations copy this notice into Android, iOS, and Windows release outputs.

## License

The original code is proprietary and all rights are reserved. See [LICENSE](LICENSE). Third-party components remain subject to their own licenses and source-availability obligations.
