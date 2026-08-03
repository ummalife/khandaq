# Vendored WebRTC audio DSP — provenance & CVE status (audit A34)

`native-audio-jni/cpp/webrtc6/` is a **partial, vendored** copy of WebRTC's audio-processing DSP,
used only for the mono voice-call filters. It is NOT a full WebRTC/PeerConnection stack.

## Snapshot
- Layout: the pre-M60 `webrtc/base/…` era (~2015–2016 upstream).
- Only these modules are compiled + linked (see the CMake/`Android.mk`):
  - `modules/audio_processing/aecm` (echo control, mobile)
  - `modules/audio_processing/ns` (noise suppression)
  - `modules/audio_processing/agc` (gain control)
  - `common_audio/signal_processing` (fixed-point DSP primitives)

## CVE exposure
The high-severity WebRTC CVEs are in code paths **not built here**:
- Video (VP8/VP9/H264 depacketizers), RTP/RTCP parsing, SRTP/DTLS, SDP, ICE, PeerConnection —
  **none of these files are compiled**, so their CVEs do not apply to this vendored subset.
- The audio DSP modules above have no known remotely-triggerable memory-safety CVEs of record;
  they operate on already-decoded PCM produced by our own capture path, not on peer-controlled bytes.

## Policy
- Do not add new WebRTC modules without re-reviewing CVE exposure for that module family.
- If the audio DSP is ever refreshed, pin the exact upstream commit here and re-run the CVE cross-check
  against the WebRTC/Chromium security advisories for the compiled modules only.
