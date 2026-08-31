# Audio Share v0.4.8

Audio Share v0.4.8 is the first release maintained at
[lkamhk/audio-share](https://github.com/lkamhk/audio-share). It is based on
[mkckr0/audio-share](https://github.com/mkckr0/audio-share) and retains the
original Apache-2.0 license and attribution.

## Highlights

- Add protocol v2 sequence and frame metadata, packet reordering, bounded jitter buffering, one-shot retransmission, and deadline-based concealment.
- Fix Android partial audio writes and move rendering to an ordered blocking writer.
- Add server discovery, multi-server playback, float mixing, and live transport statistics.
- Improve Windows WASAPI packet draining, silent-packet handling, and discontinuity reporting.
- Add signed Android and Windows automatic updates using Supabase manifests and Dropbox artifacts.
- Move project, release, and issue links to `lkamhk/audio-share`.

## Recommended audio format

Use 48 kHz, stereo, PCM16 for predictable bandwidth and broad Android compatibility. Higher sample rates, float PCM, and additional channels increase both bandwidth and packet rate and do not fix an unstable Wi-Fi connection.

## Installation

- Android: install `AudioShare-Android-v0.4.8.apk`.
- Windows GUI: extract `AudioShare-Server-GUI-Windows-x64-v0.4.8.zip`, then run `AudioShareServer.exe`.
- Windows CLI: extract `AudioShare-Server-CLI-Windows-x64-v0.4.8.zip`, then run `as-cmd.exe`.

The Android app no longer creates a default server on first launch. Use server discovery or add the server address manually.

## Windows signing notice

The Windows executables are protected inside the automatic-update path by a detached ECDSA P-256 signature, but the executables do not currently carry an Authenticode publisher signature. Windows may therefore display `Unknown publisher`, Microsoft Defender SmartScreen, or an antivirus reputation warning. Verify downloads against `SHA256SUMS.txt`; do not disable antivirus protection globally.

## Compatibility

- Existing installations retain the same Android application ID and signing identity, allowing an in-place update from v0.4.7.
- Protocol v2 clients retain fallback compatibility with protocol v1 servers.
- GitHub downloads are independent from the Supabase and Dropbox automatic-update channel.
