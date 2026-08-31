# Audio Share network protocol

Audio Share keeps TCP for negotiation and liveness, and sends uncompressed little-endian PCM over UDP. Protocol v2 adds packet identity, ordering, retransmission, and an explicit PCM timeline while preserving the v1 command values and behavior.

## TCP negotiation

All TCP command values are little-endian signed 32-bit integers.

| Command | Value | Direction | Response |
| --- | ---: | --- | --- |
| `CMD_GET_FORMAT` | 1 | Client to server | Length-prefixed protobuf `AudioFormat` |
| `CMD_START_PLAY` | 2 | Client to server | v1: `uint32 client_id`; v2: `uint32 client_id`, then `uint64 session_id` |
| `CMD_HEARTBEAT` | 3 | Both | The peer echoes the same command |
| `CMD_HELLO_V2` | 4 | Client to server | Length-prefixed protobuf `Capabilities` |

A v2 client sends `CMD_HELLO_V2` first. If the connection closes, the command times out, or the response is invalid, it reconnects once and uses the v1 flow. A v2 server continues to accept clients that start with `CMD_GET_FORMAT`.

The current capability values are protocol version `2`, maximum UDP datagram size `1200`, and retransmission window `250 ms`.

## Protocol v1 UDP

After `CMD_START_PLAY`, the client sends the four-byte `client_id` to the server UDP port. Every following server datagram contains only raw PCM. This mode has no sequence number or loss recovery and exists for backward compatibility.

## Protocol v2 UDP header

Every v2 datagram starts with this fixed 32-byte packed header. Integer fields are little-endian.

| Offset | Size | Field | Description |
| ---: | ---: | --- | --- |
| 0 | 4 | `magic` | `0x32534141` (`AAS2` in little-endian bytes) |
| 4 | 1 | `version` | `2` |
| 5 | 1 | `flags` | Packet type and retransmission flag |
| 6 | 2 | `header_size` | `32` |
| 8 | 8 | `session_id` | Random identifier returned by `CMD_START_PLAY` |
| 16 | 4 | `sequence` | Wrapping packet sequence number |
| 20 | 8 | `frame_index` | First interleaved PCM frame on the stream timeline |
| 28 | 2 | `frame_count` | Number of complete PCM frames in the payload |
| 30 | 2 | `payload_size` | Payload bytes following the header |

Flags are `AUDIO=0x01`, `REGISTRATION=0x02`, `NACK=0x04`, and `RETRANSMITTED=0x08`. Exactly one of the first three type flags is used. A retransmitted audio packet also sets `RETRANSMITTED`.

The total datagram size never exceeds 1200 bytes. Audio payloads always contain complete interleaved PCM frames, so `payload_size == frame_count * channels * bytes_per_sample`.

### Registration

After TCP start, the client sends a header-only `REGISTRATION` packet with its negotiated `session_id`. The server associates that UDP endpoint with the TCP session.

### Audio ordering and recovery

The server serializes UDP sends and retains sent packets for 250 ms. The client orders packets by wrapping `sequence`, removes duplicates, and uses `frame_index` to preserve the exact playback timeline.

When a future packet reveals a gap, the client sends a header-only `NACK`. Its `sequence` field identifies one missing packet. A request is sent at most once per missing sequence. If the packet remains unavailable at its playback deadline, the client inserts the exact missing frame count, fades the previous sample toward silence, and fades the next packet in. Other sources continue on the common output clock.

## Playback buffering

The Android client renders fixed 10 ms quanta. Before starting the hardware playback clock it primes `AudioTrack` with 20 ms, 30 ms, or 40 ms for the Low, Balanced, and Stable presets. After startup, blocking writes to a 30–60 ms `AudioTrack` buffer provide the output clock; a second software timer is deliberately avoided because it can drift relative to the audio device. The network queue targets are 25 ms, 50 ms, and 100 ms respectively; Balanced is bounded to 120 ms and Stable to 150 ms so the latter has enough burst headroom. One-frame resampling corrections keep independent source clocks near the selected target without moving the shared output clock. After a real underflow, that source outputs smooth silence without consuming newly arrived packets until its contiguous queue reaches the selected target again; it then resumes with a short fade-in. This prevents a depleted queue from remaining permanently trapped near zero.

For predictable bandwidth and Android device compatibility, use 48 kHz, stereo, PCM16 when available. Native formats remain supported, but rates above 48 kHz and more than two channels increase bandwidth and packet rate.
