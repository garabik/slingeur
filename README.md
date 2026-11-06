# Slingeur

This is an alternate Android client for Slinger/slingbox. It can connect directly 
to a Slingbox, if reachable by an IP number (or DNS record); or to a
running Gerry Dazoo's [Slinger server](https://github.com/GerryDazoo/Slinger)
as an alternative to an existing Slinger player or `ffplay`.

Current package is here: https://github.com/garabik/slingeur/releases/download/v0.99/slingeur-0.99-debug.apk


*Warning*: this is vibe-coded, with the help of Claude Sonnet 5. Claude wrote
all the code, based on Python implementation of Slinger server, video stream
capture analysis and a lot of debugging and error-hunting, without access to an
original client. It took about one week of intense discussion and iterative
extending and testing, on and off.

This has been tested only with Slingbox ProHD, because this is all I have
(technically, I own also a Slingbox Solo, but without a wall adapter).

Worth repeating for new users: if installing a Slinbox, make sure outgoing TCP
port 5678 is blocked (or the Slingbox cannot reach wide internet), otherwise it
will be intentionally bricked by Sling Media.


The primary motivation for the development was to have a reliable client for an
Android settop box (a cheap, unreliable and low powerered one), that is,
working in landscape, controlled by a keyboard (or Android remote control) and
preferably with low latency playback. Something that my 
[web player](https://github.com/garabik/Slinger/tree/remux-video) definitely isn't.

But it has also some nice features for handheld devices with a touchscreen, such as remote-on-tap, pinch-zoom&pan, volume control by sliding a finger on the right border, brightness control by sliding a finger on the left border.

Native Android Slingbox client (Kotlin, direct `MediaCodec`, no ExoPlayer) —
talks to a Slingbox (Solo/Pro/ProHD, or 350/500/M1/M2/240) directly over its
own protocol, or to a `slingbox_server.py` instance over plain HTTP ("Slinger
server" mode).

The minimun required Android version is 5.0 (Lollipop, untested).

## License

To an extent anything produced by a LLM can be licensed, consider this 
software to be under the [WTFPL](https://www.wtfpl.net/) version 2 license.

## Building

```
./gradlew assembleDebug
```

or open this folder in Android Studio and let it sync. Needs the Android
SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`/`ANDROID_SDK_ROOT`
set) with `platform-tools`, `platforms;android-34`, and `build-tools;34.0.0`
installed (`compileSdk`/`targetSdk` 34; `minSdk` 21). No other dependencies
beyond the standard Android/Kotlin Gradle plugins — `org.json` (used for the
saved-boxes list) and `android.widget`/`android.media` are all part of the
platform SDK, nothing extra to fetch. Built APK ends up at
`app/build/outputs/apk/debug/slingeur-$VERSION-debug.apk`.

## Usage

1. **Add a Slingbox**: on the main screen, tap *Manage Slingboxes...* and add
   one (name, host, port, password, video source, type). Multiple boxes can
   be saved; pick the active one with the radio list on the main screen.
   If connecting to a Slinger server, password and all other settings will
   be ignored, those in server's `config.ini` will be used.
2. **Play**: select a box and tap *Play*.
3. **While watching**:
   - Tap the video to show/hide the remote and top bar.
   - Drag the right edge of the screen for volume, the left edge for
     brightness.
   - Swipe (up/down or right/left) to change channel up/down.
   - Pinch to zoom (up to 4×); drag to pan while zoomed.
4. **Settings** (gear icon on the remote, or `Tab`/`Menu` on a keyboard):
   resolution/bitrate/etc. for the current box, plus shared player
   preferences (aspect ratio, decoder, pacing, fullscreen, clock, debug
   line). Back cancels without saving.
5. **Manage Slingboxes**: add/remove boxes, edit connection details and
   per-box stream parameters.

## Keyboard bindings

A physical/Bluetooth keyboard can drive the whole remote. This is modelled
after AB Cryptobox, but should be applicable to other remotes, with more or
less usability. Slinger custom remotes are not supported.

* 0–9:    Number keys
* Enter / Space / D-pad center:   OK
* Arrow keys: Left/Right/Up/Down
* Page Up / Down: Page Up/Down
* m / M (shift):  Menu / Mute (remote)
* g / G (shift):  Guide / Green button (vmod)
* s:    Sat 
* f:    Favourite group
* i;    Info
* x:    Exit
* p:    Power
* R / Y / B / G (uppercase, with a shift): coloured buttons (find/txt/audio/vmod)
* Media keys (Play / Pause / Play-Pause / Stop / Rewind / FF / Record: Media control
* Tab / Menu:   Open Settings
* Zoom key / z: Cycle zoom: 2× → 3× → 4× → off
* c:    Toggle remote/top bar (same as tapping the video)
* a:    Cycle aspect ratio (16:9, 4:3, fit screen)
 
Unmapped keys (volume, back, etc.) pass through to normal Android handling.
Held keys don't flood the box with repeated IR sends, except panning, which
repeats smoothly.

Keyboard mapping is hardwired in `app/src/main/java/app/retro/slingeur/PlayerActivity.kt`

