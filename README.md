# Audio Share

English | [简体中文](README.zh-CN.md)

Audio Share streams audio from a Windows or Linux computer to Android devices over a network.

This repository is a maintained fork of [mkckr0/audio-share](https://github.com/mkckr0/audio-share). It retains the original attribution and Apache-2.0 license.

[Download the latest release](https://github.com/lkamhk/audio-share/releases/latest) · [Report an issue](https://github.com/lkamhk/audio-share/issues)

## Changes in this fork

- **Multiple servers and devices:** An Android client can connect to multiple Audio Share servers simultaneously, with independent names, addresses, ports, enable states, and volume controls.
- **Automatic server discovery:** Discover available Audio Share servers on the local network. The app no longer creates an invalid default server on first launch.
- **More reliable audio transport:** Protocol v2 adds packet sequencing, frame indexes, reordering, deduplication, bounded jitter buffering, one-shot retransmission, and deadline-based concealment.
- **Stable Android playback:** Ordered audio queues, startup prebuffering, fixed render intervals, and complete blocking writes prevent PCM loss caused by partial writes.
- **Safer multi-source mixing:** Float accumulation, automatic headroom, and corrected PCM8, PCM16, PCM24, PCM32, and float conversion avoid hard clipping.
- **Improved Windows capture:** WASAPI capture drains all available packets and correctly handles silent packets, frame alignment, and data discontinuities.
- **Signed automatic updates:** Windows and Android updates are verified with ECDSA P-256/SHA-256 detached signatures before installation.
- **Live diagnostics:** Android displays queue latency, loss, late and reordered packets, retries, concealment, underflows, rebuffering, and AudioTrack underruns.

## License

This project is distributed under the [Apache License 2.0](LICENSE) and retains the original copyright notice:

```text
Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
