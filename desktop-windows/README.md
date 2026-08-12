# Windows App

This module contains the Windows implementation for the shared v2 pairing/audio protocol.

## Scope in this repository

- `src/P2PAudio.Windows.Core/`
  - v2 payload models (`init` / `confirm`)
  - payload validation + failure-code mapping
  - payload transport codec (`p2paudio-z1:` zlib + Base64URL)
  - verification code generation
  - PCM packet codec (`audio-pcm`)
  - USB tethering interface classifier
- `src/P2PAudio.Windows.App/`
  - WinUI shell with sender/listener payload text flow (copy, paste, manual entry)
  - native bridge loading (`p2paudio_core_webrtc.dll`, `p2paudio_core_udp_opus.dll`) with native-required default
  - WASAPI loopback sender to `audio-pcm` packet pipeline
  - DataChannel receive polling + PCM playback (NAudio)
- `src/core-webrtc/`
  - C++ bridge libraries + C ABI exports for WebRTC control and UDP + Opus media-audio sending
- `tests/P2PAudio.Windows.Core.Tests/`
  - codec, validator, and failure-mapper tests

## Build prerequisites (local)

- Visual Studio 2022 (17.10+) with:
  - Desktop development with C++
  - .NET desktop development
  - Windows App SDK / WinUI tooling
- .NET 8 SDK
- Windows 10 2004+ (19041)
- `vcpkg` (manifest mode)

## Build (x64 release path)

1. Bootstrap `vcpkg`.
   - `vcpkg install --x-manifest-root=. --triplet x64-windows`
2. Build native bridge:
    - `cmake -S src/core-webrtc -B out/core-webrtc -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake -DVCPKG_MANIFEST_MODE=OFF -DVCPKG_INSTALLED_DIR=<repo>\desktop-windows\vcpkg_installed -DVCPKG_TARGET_TRIPLET=x64-windows -DP2PAUDIO_USE_LIBDATACHANNEL=ON`
    - `cmake --build out/core-webrtc --config Release`
3. Build managed app:
    - `dotnet build src/P2PAudio.Windows.App/P2PAudio.Windows.App.csproj -c Release -r win-x64 -p:Platform=x64`
    - The app project includes `out/core-webrtc/p2paudio_core_udp_opus.dll`, `vcpkg_installed/x64-windows/bin/opus.dll`, and `vcpkg_installed/x64-windows/bin/portaudio.dll` in the app output when those files exist.
    - The repository root `run-app.ps1` launcher searches `src/P2PAudio.Windows.App/bin` and starts the newest `P2PAudio.Windows.App.exe`, so Start Menu shortcuts do not need to be repointed to every new build path.

## Runtime behavior

- Native backend is required by default.
- If native bridge load fails, app enters failed state and blocks connection flow.
- Development-only stub can be enabled with `ALLOW_STUB_FOR_DEV=1`.
- UDP + Opus mode requires the native UDP sender DLL and Opus runtime from `vcpkg_installed/x64-windows/bin/` to be copied beside the app.
- The Windows resolver loads native dependencies from `runtimes\win-x64\native` before loading the primary WebRTC / UDP bridge DLLs.
- The Start Menu shortcut targets `run-app.ps1`, which resolves the newest available Windows app build under `src/P2PAudio.Windows.App/bin`.
- On startup, the app also repairs any existing `P2PAudio.lnk` shortcuts in the user or common Start Menu folders so older shortcuts are repointed to the launcher.

## Notes

- USB support is IP over USB tethering, not direct accessory transport.
- `P2PAUDIO_USE_LIBDATACHANNEL=ON` enables linking with libdatachannel when available in toolchain.
