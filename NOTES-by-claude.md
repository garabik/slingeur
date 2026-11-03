# Slingeur (Android Slingbox client)

Native Android/Kotlin Slingbox client — no `MediaExtractor`, no container
remux, no ExoPlayer, direct `MediaCodec`. Package `app.retro.slingeur`
(formerly `sk.savba.juls.slingbox`), minSdk 21. Two independent ways to get
video, selectable per saved Slingbox:

- **Direct protocol** (default): an Android/Kotlin port of
  `slingbox_server.py`'s own control protocol (login, dynamic session-key
  exchange, stream setup, keepalive, IR keycode send) plus, for Solo/Pro/
  ProHD boxes, the per-packet TEA decryption needed to make the A/V stream
  readable at all. Talks to the Slingbox directly; no Python server involved.
  Also supports the 350/500/M1/M2 and 240 box families via a standard
  (unencrypted) ASF Data Packet path — implemented from the published ASF
  spec and cross-checked against ffmpeg's own demuxer, but only the Solo/
  ProHD path has been verified against real hardware.
- **"Slinger server" mode**: connects to a running `slingbox_server.py`
  instance over plain HTTP instead — `GET /slingbox` for the raw stream (the
  server does its own login/decrypt against the real box and re-emits a
  standard, unencrypted ASF stream, so this always demuxes via the same
  standard-ASF path above) and `POST /Remote` (form field `key=<value>`,
  comma-separated for multi-code macros) for IR. No control-channel login at
  all in this mode — see `SlingboxClient.openHttpVideoStream`/
  `sendHttpIrCommand`.

## Using it

- **Main screen**: pick a saved Slingbox (radio list) and tap *Play* — or
  just press OK/Enter, since Play is the default-focused button. *Manage
  Slingboxes...* adds/removes/edits boxes (name, host, port, password, video
  source, box type, and that box's own resolution/bitrate/frame-rate/etc.).
- **Player**: fullscreen, distraction-free by default. Tap the video (or `c`
  on a keyboard) to show the remote + top debug bar for 5s. Touch gestures:
  drag the right/left screen edge for volume/brightness, swipe any direction
  to change channel, pinch to zoom (up to 4×) and drag to pan while zoomed.
  A PM5544-style test card placeholder shows whenever there's genuinely no
  video (connecting, reconnecting, or a confirmed audio-only signal from the
  box) instead of a plain black screen.
- **Settings** (gear button on the remote, or `Tab`/`Menu`): resolution/
  bitrate/frame-rate/etc. for the *currently active* box (a quick-access
  editor, not a separate global setting — writes straight back into that
  box's own saved fields), plus genuinely shared player preferences (aspect
  ratio, decoder choice, pacing, low-latency hint, fullscreen, clock,
  show-debug-messages). Back cancels without saving.
- **Keyboard**: see `README.md` for the full binding table. Physical/
  Bluetooth keyboards (including ones with no Shift key that use Caps Lock
  instead — handled via `META_CAPS_LOCK_ON`) can drive the whole remote,
  cycle zoom, pan while zoomed (with proper key auto-repeat), toggle the
  remote, and cycle aspect ratio.

## Backlog / pacing

Applies only with the "Pace video to real-time" setting on (the default).
In that mode, reassembled video frames go through `VideoPacer`
(`PlayerActivity.kt`) instead of straight to the codec: each frame paces
itself against the *live audio clock* (not a free-running wall clock)
rather than a fixed schedule, so decode/output latency and device drift are
naturally absorbed. If a backlog builds up (e.g. after a network stall), it
temporarily speeds up until drained, then returns to normal.

With that setting off, `submitVideoFrame` bypasses `VideoPacer` entirely and
feeds `videoPump` directly as soon as a frame is reassembled — a diagnostic
escape hatch for isolating decode issues from the pacer's own scheduling.
None of the backlog/catch-up logic above applies in that mode, and the
bottom status line's backlog figure reads ~0 always, since nothing ever
queues in `VideoPacer`.

The bottom status line shows the real backlog size in KB (summed actual
bytes of queued frames, not just a frame count) and the live incoming data
rate in kb/s, resampled once per second for stability.

Tapping the video forces an immediate resync (jumps to the latest buffered
key frame if one's available, otherwise a no-op) in addition to toggling the
remote/overlay.

## What a real capture told us (ground truth, not guesses)

A capture taken against a real ProHD box and inspected with `ffprobe`/
`ffmpeg` settled several things that were previously assumptions:

- The ASF header and all its GUIDs are **standard, untampered** ASF —
  nothing Slingbox-proprietary in the container structure itself.
- **ASF Stream Number 1 = audio, Stream Number 2 = video** (this resolved an
  ambiguity in how `process_solo_msg`'s `sn==0x82` / `sn&63==1` conditions
  treat the two streams differently).
- Video is H.264 (High profile confirmed), and the per-packet payloads are
  genuine Annex-B (`00 00 00 01` start codes already between NAL units,
  SPS/PPS included) — confirmed by extracting the raw elementary stream and
  checking byte-for-byte, not inferred.
- **The audio WAVEFORMATEX's own "extra data" bytes are not a usable
  `AudioSpecificConfig`.** Feeding them to a standard AAC decoder produces
  "audio object type 0" (invalid). The WAVEFORMATEX's plain `nChannels`/
  `nSamplesPerSec` fields *are* correct, though, so a standard AAC-LC
  `AudioSpecificConfig` is synthesized from those instead
  (`AacConfig.buildAacLcAsc` in `AsfHeader.kt`).
- **A genuine decrypt carry-over bug was the confirmed root cause of an
  early "I-frame has garbled horizontal bands, P-frames gradually paper over
  it" corruption bug** (confirmed via logcat, `SlingboxClient.kt`'s
  `emitGatedEntry`/`emitResolvedTail`): the TEA carry-over math itself was
  correct, but a gated entry's last few bytes were being handed to the
  demuxer *before* actually being decrypted, scattering a few bytes of raw
  ciphertext through multi-fragment frames. `KEY_MAX_INPUT_SIZE` in
  `PlayerActivity.setupVideoCodec` was a separate, independently-reasonable
  mitigation tried while chasing the same symptom (undersized codec input
  buffers forcing `CodecInputPump` to split one access unit across multiple
  `queueInputBuffer` calls) — real risk, just not what was actually causing
  that particular bug.
- **The video codec must not be started on a key frame that lacks SPS/PPS**
  (`PlayerActivity.initVideoCodecFromFirstFrame`): some decoders (confirmed:
  Waydroid's software `OMX.google.h264.decoder`) never recover once started
  without real parameter sets, even once a later well-formed key frame
  arrives — visible as an endless reconnect loop. Fixed by waiting for a key
  frame that actually has SPS/PPS before committing to starting the codec at
  all, retrying on each subsequent key frame otherwise.
- **`KEY_LOW_LATENCY` support is decoder-dependent, not universal** —
  confirmed on Waydroid's software decoder, which rejects it outright with
  `ERROR_UNSUPPORTED` during `configure()` rather than ignoring it. Handled
  by catching that failure and retrying once without the hint.

## Architecture

- `SlingboxCrypto.kt` — TEA cipher, dynamic key derivation. Self-contained,
  no Android dependencies. Cross-checked against the reference Python
  functions via generated test vectors, expression-for-expression.
- `SlingboxClient.kt` — control protocol (login/key-exchange/IR/keepalive),
  the Solo/ProHD decrypting stream reader (`readAndDemuxPacket`, per-entry
  callback with Stream Number / key-frame flag / Media-Object-offset /
  payload), the standard (unencrypted) ASF path for other box families, and
  the "Slinger server" HTTP path (`openHttpVideoStream`/`sendHttpIrCommand`).
- `AsfHeader.kt` — parses the ASF header to find video/audio Stream Numbers,
  the audio's real channel/sample-rate fields, builds the synthetic AAC-LC
  `AudioSpecificConfig`, and extracts the File Properties Object's packet
  size (needed by the Slinger-server path, which has no protocol handshake
  to learn it from otherwise).
- `PlayerActivity.kt` — the live player: reassembles per-stream fragments
  into complete frames and feeds them to `MediaCodec` via `CodecInputPump`;
  `VideoPacer` for real-time-clock-based pacing with backlog catch-up;
  pinch-zoom/pan (touch and keyboard); the full touch-gesture and
  physical-keyboard remote-control input handling; the test-card no-video
  placeholder; the reconnect-with-backoff loop (handles the box's own
  "stream restarted" signal on audio→video transitions, and cleans up +
  notifies the user if a reconnect attempt ultimately gives up rather than
  silently dying).
- `AppSettings.kt` — SharedPreferences-backed settings. Multiple
  `SlingboxProfile`s (host/port/password/video source/box type/resolution/
  bitrate/frame-rate/smoothness/keyframe-interval/audio-bitrate each) stored
  as a JSON array, one marked active; everything else (aspect ratio, decoder
  choice, pacing, fullscreen, clock, debug messages) is a single shared
  `Snapshot`. An existing single-box install's legacy flat keys get migrated
  into one box automatically the first time this runs.
- `MainActivity.kt` — box picker + Play.
- `SettingsActivity.kt` — active box's stream parameters (quick-access edit,
  writes back via `AppSettings.updateActiveBox`) + shared player
  preferences.
- `SlingboxListActivity.kt` — add/remove/edit the full box list. Rows built
  programmatically (no fixed cap, no RecyclerView needed for this scale).

## Building

```
./gradlew assembleDebug
```

or open this folder in Android Studio. Needs a device/emulator that can
reach the Slingbox's (or `slingbox_server.py`'s) host:port. Verified against
real Solo/ProHD hardware; also tested under Waydroid (see the Waydroid-
specific fixes above).
