package app.retro.slingeur

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque

/**
 * Real-time low-latency player: logs into the Slingbox (same session setup as
 * [MainActivity]'s capture mode), then demuxes the decrypted ASF data packets by
 * Stream Number and feeds H.264 Annex-B frames / raw AAC frames straight into
 * MediaCodec -- no MediaExtractor, no container remux, no ExoPlayer. Video
 * normally paces to a steady per-frame clock (see [VideoPacer]); audio is
 * written to an AudioTrack as soon as each frame decodes, unpaced (its own
 * blocking writes provide natural pacing).
 *
 * The AAC config is synthesized from the header's real sample-rate/channel
 * fields rather than the box's own WAVEFORMATEX extra-data bytes, which were
 * confirmed (via ffprobe/ffmpeg forensics on a real capture) to not be a usable
 * AudioSpecificConfig. See AsfHeader.kt.
 *
 * Tapping the video: shows a debug overlay (resolution, buffer backlog, an
 * aspect-ratio toggle, a settings button) and forces an immediate resync --
 * discards whatever's buffered and waits for the next video key frame, same
 * idea as the initial cold start. There's only one tap gesture right now; a
 * future remote-control input could trigger resync without also toggling the
 * overlay if that split turns out to matter in practice.
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var videoContainer: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var noVideoImage: ImageView
    @Volatile private var firstVideoFrameRendered = false
    // Raw wire bytes read so far this session; debugTick samples the delta
    // between ticks to compute an instantaneous rate for the debug line.
    @Volatile private var bytesReceivedTotal = 0L
    private var lastRateSampleAt = 0L
    private var lastRateSampleBytes = 0L
    private var lastDisplayedRateKbps = 0.0
    // The latest message status() was given -- statusView itself now also
    // carries the live resolution/backlog/rate stats appended on each
    // debugTick, so this is kept separately rather than reading it back out
    // of statusView.text (which would include that suffix too).
    private var lastStatusMessage = ""
    private lateinit var statusView: TextView
    private lateinit var debugOverlay: android.view.View
    private lateinit var debugInfoView: TextView
    private lateinit var settingsButton: Button
    private lateinit var aspectButton: Button
    private lateinit var quitButton: Button
    private lateinit var irFlashView: TextView
    private lateinit var clockView: TextView
    private var irFlashHideRunnable: Runnable? = null
    private lateinit var irGrid: android.view.View
    private lateinit var rootFrame: android.view.View
    private lateinit var irOkButton: Button

    @Volatile private var running = false
    private var client: SlingboxClient? = null
    private var playThread: Thread? = null
    private var keepAliveThread: Thread? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var videoPump: CodecInputPump? = null
    private var audioPump: CodecInputPump? = null
    private var videoPacer: VideoPacer? = null
    // Separate HandlerThreads for video vs audio MediaCodec callbacks -- AudioTrack.write()
    // in the audio output callback blocks when the hardware buffer is full, and sharing one
    // thread meant that could stall video's onInputBufferAvailable/onOutputBufferAvailable
    // callbacks too. That's a plausible cause of decode corruption that scales with frame
    // size/resolution (bigger video frames = more callback work = more chances to collide
    // with an audio stall) and is completely unaffected by VideoPacer's on/off state, which
    // matches it being ruled out as a factor.
    private var videoCodecThread: HandlerThread? = null
    private var videoCodecHandler: Handler? = null
    private var audioCodecThread: HandlerThread? = null
    private var audioCodecHandler: Handler? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var hideControlsRunnable: Runnable? = null
    private var debugMessagesEnabled = true
    // Last physical key event seen by onKeyDown, shown on the debug row
    // when debug is on -- handy for checking what keyCode a given remote
    // button actually sends, without needing logcat.
    private var lastKeyDebugText = ""
    private val clockFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val debugTick = object : Runnable {
        override fun run() {
            val avOffsetSec = (videoPacer?.avOffsetUs ?: 0L) / 1_000_000.0
            val backlogKB = (videoPacer?.queueBytes ?: 0L) / 1024.0
            val now = System.currentTimeMillis()
            // Only re-sampled once a full second has actually elapsed (ticks
            // in between just keep showing the last computed value), so this
            // reads as a stable per-second rate rather than jittering with
            // whatever happened to arrive in the last 500ms.
            if (lastRateSampleAt == 0L) {
                lastRateSampleAt = now
                lastRateSampleBytes = bytesReceivedTotal
            } else if (now - lastRateSampleAt >= 1000) {
                val elapsedSec = (now - lastRateSampleAt) / 1000.0
                lastDisplayedRateKbps = (bytesReceivedTotal - lastRateSampleBytes) * 8.0 / 1024.0 / elapsedSec
                lastRateSampleAt = now
                lastRateSampleBytes = bytesReceivedTotal
            }
            debugInfoView.text = "%s  A/V offset %+.2fs".format(lastVideoDecoderName, avOffsetSec)
            val keySuffix = if (debugMessagesEnabled && lastKeyDebugText.isNotEmpty()) "  $lastKeyDebugText" else ""
            statusView.text = "%s   %dx%d  backlog %.0fKB  %.0f kb/s%s".format(
                lastStatusMessage, lastVideoWidth, lastVideoHeight, backlogKB, lastDisplayedRateKbps, keySuffix
            )
            if (clockView.visibility == android.view.View.VISIBLE) {
                clockView.text = clockFormat.format(java.util.Date())
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    @Volatile private var resyncRequested = false
    @Volatile private var lastVideoWidth = 0
    @Volatile private var lastVideoHeight = 0
    @Volatile private var lastVideoDecoderName = ""

    // Edge drags: right edge changes the device's own media volume (not an IR
    // command -- the box's own volume, if it even has one, is a separate
    // concern entirely), left edge changes this screen's own brightness.
    // Fields are only ever touched from the main thread (all touch events
    // arrive there), so no synchronization needed.
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var volumeDragActive = false
    private var volumeDragStartY = 0f
    private var volumeDragStartVolume = 0
    private var brightnessDragActive = false
    private var brightnessDragStartY = 0f
    private var brightnessDragStartValue = 0.5f
    private var touchDownX = 0f
    private var touchDownY = 0f

    // Pinch zoom (centered on the SurfaceView's own pivot) and, once zoomed,
    // single/multi-finger pan instead of the usual edge-drag/tap/swipe
    // gestures -- see onRootTouch. applyZoomPan() re-clamps and reapplies
    // scaleX/scaleY/translationX/Y every time either changes.
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var panActive = false
    private var panLastX = 0f
    private var panLastY = 0f
    private lateinit var scaleGestureDetector: android.view.ScaleGestureDetector
    // Set on every pointer-count change (pinch start/end). The final finger
    // lifting off a pinch that landed back at 1x has nothing else marking it
    // as "not a plain single-finger gesture" -- touchDownX/Y are still from
    // whichever finger went down first, often a good distance from the last
    // one's lift point purely from the pinch motion itself, which read as a
    // large, fast swipe. This cooldown (checked on ACTION_UP) suppresses
    // tap/swipe for a brief window after any pinch activity instead.
    private var lastMultiTouchAt = 0L

    private val SETTINGS_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        // Keeps the screen from dimming/locking while watching -- a window
        // flag rather than a WakeLock, so it needs no extra permission and
        // is automatically released the moment this Activity isn't the one
        // in front (no explicit cleanup needed in onPause/onDestroy either).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreen()
        videoContainer = findViewById(R.id.videoContainer)
        surfaceView = findViewById(R.id.surfaceView)
        noVideoImage = findViewById(R.id.noVideoImage)
        statusView = findViewById(R.id.statusView)
        debugOverlay = findViewById(R.id.debugOverlay)
        debugInfoView = findViewById(R.id.debugInfoView)
        settingsButton = findViewById(R.id.settingsButton)
        aspectButton = findViewById(R.id.aspectButton)
        quitButton = findViewById(R.id.quitButton)
        irFlashView = findViewById(R.id.irFlashView)
        clockView = findViewById(R.id.clockView)
        irGrid = findViewById(R.id.irGrid)
        // Default: the remote's buttons are NOT reachable by D-pad focus-
        // search at all, even while visible -- this is what keeps a stock
        // Android device's physical/HW keyboard working exactly as before
        // (arrow keys sending IR nav codes, letters sending their own IR
        // codes, etc.) while the remote happens to be on-screen, since
        // without this, simply having the remote visible would let Android's
        // own focus-search jump an arrow key into a button instead of
        // reaching onKeyDown at all. Only long-pressing OK (see
        // onKeyLongPress) switches this to FOCUS_BEFORE_DESCENDANTS for a
        // genuine D-pad-only remote (Android TV) that has no other way to
        // reach most buttons.
        (irGrid as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // irGrid is now a HorizontalScrollView (the remote is wider than any
        // screen), so it must NOT get a blanket "always consume" touch
        // listener like before -- that would swallow the drag gesture the
        // ScrollView itself needs to see in order to pan. Instead,
        // irGridContent (the scrolled child) is just marked clickable in the
        // layout: that absorbs a tap that lands in a gap between buttons
        // (so it doesn't fall through to rootFrame's "tap outside controls"
        // handling) while leaving drag-to-scroll and individual button
        // clicks working exactly as Android's normal touch dispatch expects.
        // It also only ever returns false (never claims the event), so this
        // is purely an observer: it resets the auto-hide countdown on any
        // touch activity (panning included) without affecting scrolling or
        // button clicks, which are decided further down the dispatch chain.
        irGrid.setOnTouchListener { _, _ ->
            if (hideControlsRunnable != null) scheduleHideControls()
            false
        }
        rootFrame = findViewById(R.id.rootFrame)
        // A stable, always-present focus target to hand D-pad focus back to
        // whenever the remote hides -- see showControls/hideControls.
        rootFrame.isFocusable = true
        irOkButton = findViewById(R.id.irOkButton)
        applyAspectRatio()
        applyDebugVisibility()
        applyClockVisibility()
        applyInsetsPadding()
        surfaceView.holder.addCallback(this)

        scaleGestureDetector = android.view.ScaleGestureDetector(
            this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                    applyZoomPan()
                    return true
                }
            }
        )
        rootFrame.setOnTouchListener { view, event -> onRootTouch(view, event) }
        settingsButton.setOnClickListener {
            startActivityForResult(Intent(this, SettingsActivity::class.java), SETTINGS_REQUEST_CODE)
        }
        aspectButton.setOnClickListener { cycleAspectRatio() }
        quitButton.setOnClickListener {
            // stopPlayback() -> releaseSessionResources() already does a
            // proper client.logout() before closing the socket, same as any
            // other teardown -- just also finish the Activity afterward
            // instead of letting onResume() reconnect.
            stopPlayback()
            finish()
        }

        // Every Button under irGrid carries its IR code(s) as android:tag
        // (a plain Int, or a comma-separated list for macro buttons like
        // 555/666/999) -- exactly mirroring ABCryptobox_Remote.html's own
        // value="..." attribute. Wiring it generically this way means a new
        // remote button only needs a layout change, no matching Kotlin.
        wireIrButtons(irGrid)
    }

    private fun wireIrButtons(view: android.view.View) {
        if (view is Button) {
            val tag = view.tag as? String
            if (tag != null) view.setOnClickListener { sendIr(tag) }
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) wireIrButtons(view.getChildAt(i))
        }
    }

    // Lets a physical/Bluetooth keyboard drive the remote, mirroring
    // ABCryptobox_Remote.html's own keyMap (keycode -> IR value) rather than
    // inventing a separate mapping. Keys not in the map (volume, back, etc.)
    // fall through to the normal Activity handling untouched.
    private fun irCodeForKeyEvent(event: KeyEvent): Int? {
        // Some remotes that present themselves as a USB/BT keyboard have no
        // physical Shift key at all, using Caps Lock to send the uppercase
        // R/G/B/Y (etc.) letters instead -- treat that the same as Shift for
        // the letters below, since the intent (the "shifted" button) is the
        // same either way.
        val shift = event.isShiftPressed || (event.metaState and KeyEvent.META_CAPS_LOCK_ON) != 0
        return when (event.keyCode) {
            KeyEvent.KEYCODE_0 -> 18
            KeyEvent.KEYCODE_1 -> 9
            KeyEvent.KEYCODE_2 -> 10
            KeyEvent.KEYCODE_3 -> 11
            KeyEvent.KEYCODE_4 -> 12
            KeyEvent.KEYCODE_5 -> 13
            KeyEvent.KEYCODE_6 -> 14
            KeyEvent.KEYCODE_7 -> 15
            KeyEvent.KEYCODE_8 -> 16
            KeyEvent.KEYCODE_9 -> 17
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER -> 42
            KeyEvent.KEYCODE_DPAD_RIGHT -> 41
            KeyEvent.KEYCODE_DPAD_LEFT -> 40
            KeyEvent.KEYCODE_DPAD_UP -> 38
            KeyEvent.KEYCODE_DPAD_DOWN -> 39
            KeyEvent.KEYCODE_PAGE_UP -> 44
            KeyEvent.KEYCODE_PAGE_DOWN -> 43
            // M/m and G/g are each two different buttons depending on shift,
            // exactly like the reference keyMap's 'M'/'m' and 'G'/'g' entries.
            KeyEvent.KEYCODE_M -> if (shift) 8 else 33 // Mute / Menu
            KeyEvent.KEYCODE_G -> if (shift) 75 else 35 // Vmod (green) / Guide
            KeyEvent.KEYCODE_S -> if (!shift) 64 else null // Sat
            KeyEvent.KEYCODE_F -> if (!shift) 63 else null // Fav
            KeyEvent.KEYCODE_I -> if (!shift) 46 else null // Info
            KeyEvent.KEYCODE_X -> if (!shift) 36 else null // Exit
            KeyEvent.KEYCODE_P -> if (!shift) 1 else null // Power
            KeyEvent.KEYCODE_R -> if (shift) 74 else null // Find (red)
            KeyEvent.KEYCODE_Y -> if (shift) 76 else null // Txt (yellow)
            KeyEvent.KEYCODE_B -> if (shift) 77 else null // Audio (blue)
            // Dedicated colour keys some remotes have alongside (or instead
            // of) the shifted letters above -- same codes either way.
            KeyEvent.KEYCODE_PROG_RED -> 74
            KeyEvent.KEYCODE_PROG_GREEN -> 75
            KeyEvent.KEYCODE_PROG_YELLOW -> 76
            KeyEvent.KEYCODE_PROG_BLUE -> 77
            // Standard Android/Bluetooth media keys -> the same transport-
            // controls codes the on-screen remote block uses.
            KeyEvent.KEYCODE_MEDIA_PLAY -> 24
            KeyEvent.KEYCODE_MEDIA_PAUSE -> 26
            // One physical toggle key, two separate IR codes on the box --
            // picked Pause, since most remotes land on this key to pause a
            // live/recorded stream more often than to resume one.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 26
            KeyEvent.KEYCODE_MEDIA_STOP -> 25
            KeyEvent.KEYCODE_MEDIA_REWIND -> 27
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 28
            KeyEvent.KEYCODE_MEDIA_RECORD -> 29
            else -> null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        lastKeyDebugText = "key ${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")} ($keyCode)"
        // Checked before the repeatCount==0 gate below (unlike everything
        // else in this method) so holding an arrow key pans smoothly via the
        // system's own key auto-repeat, instead of requiring a fresh press
        // per step -- auto-repeat is only suppressed elsewhere to avoid
        // flooding the box with repeated IR sends/zoom cycles/etc, which
        // doesn't apply here since this never calls sendIr() at all.
        if (zoomScale > 1f) {
            val stepPx = ARROW_PAN_STEP_DP * resources.displayMetrics.density
            when (keyCode) {
                // Inverted from how touch panning moves (there, content
                // follows the finger directly) -- for arrow keys, the key
                // direction is the viewpoint moving that way, e.g. Right
                // reveals more of what's to the right, so the content itself
                // shifts left.
                KeyEvent.KEYCODE_DPAD_UP -> { panY += stepPx; applyZoomPan(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { panY -= stepPx; applyZoomPan(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { panX += stepPx; applyZoomPan(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { panX -= stepPx; applyZoomPan(); return true }
            }
        }
        if (event.repeatCount == 0) {
            // Tab/Menu open this app's own Settings rather than being an IR
            // code -- Menu in particular is the physical/remote "options"
            // key on many Android TV-ish keyboards and remotes, a natural
            // fit for it here.
            if (keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_MENU) {
                settingsButton.performClick()
                return true
            }
            // A single physical/remote "Zoom" key cycles through the same
            // pinch-zoom levels a touch gesture would reach (see
            // onRootTouch/applyZoomPan) -- covers both the dedicated camera-
            // style zoom key and Android TV remotes' picture-zoom-mode key,
            // since there's no one standard "zoom button" keycode.
            if (keyCode == KeyEvent.KEYCODE_ZOOM_IN || keyCode == KeyEvent.KEYCODE_TV_ZOOM_MODE ||
                keyCode == KeyEvent.KEYCODE_Z) {
                cycleZoom()
                return true
            }
            // Same as tapping the video: toggles the remote/top bar and
            // requests a resync.
            if (keyCode == KeyEvent.KEYCODE_C) {
                onTap()
                return true
            }
            // TV_TELETEXT also brings the remote up, but in D-pad-navigable
            // mode (same as long-pressing OK -- see onLongPressShowRemote)
            // rather than plain onTap()'s mode: on hardware where long-press
            // detection doesn't fire reliably, this is the only other
            // reachable way to get a navigable remote.
            if (keyCode == KeyEvent.KEYCODE_TV_TELETEXT) {
                onLongPressShowRemote()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_A || keyCode == KeyEvent.KEYCODE_CAPTIONS) {
                cycleAspectRatio()
                return true
            }
            // Dedicated channel keys some remotes have, same as the on-screen
            // remote's own quick-channel buttons (see nextChannel/previousChannel).
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                nextChannel()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                previousChannel()
                return true
            }
            // While the on-screen remote is up in D-pad-navigable mode (see
            // showControls/onLongPressShowRemote), D-pad/OK presses are
            // meant to move focus between its buttons / click the focused
            // one -- not also fire the same codes as IR commands behind the
            // scenes. Leaving this unconsumed here lets super.onKeyDown()
            // (and whichever Button currently has focus) handle it instead.
            val isDpadNavKey = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            if (!(isDpadNavKey && remoteIsDpadNavigable())) {
                val code = irCodeForKeyEvent(event)
                if (code != null) {
                    // DPAD_CENTER/ENTER send their short-press OK code
                    // immediately here either way, but also need to arm the
                    // framework's own long-press tracking (see
                    // onKeyLongPress) -- returning true without calling
                    // startTracking() on the initial down event would
                    // otherwise leave the long-press timeout permanently
                    // unarmed, since KeyEvent.dispatch() only starts
                    // tracking a key when the handler that consumed its
                    // first down event explicitly asked it to.
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        event.startTracking()
                    }
                    sendIr(code.toString())
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun remoteIsDpadNavigable(): Boolean =
        irGrid.visibility == android.view.View.VISIBLE &&
            (irGrid as ViewGroup).descendantFocusability == ViewGroup.FOCUS_BEFORE_DESCENDANTS

    // Long-pressing OK brings the remote forward -- an easy, always-present
    // way to do that on an Android TV remote, which typically has only a
    // D-pad, OK, Back, Home and a numeric keypad, none of the other keys
    // this app maps (letters, Tab, c, etc.) on a full keyboard. A short
    // press still sends the box's own OK code immediately either way (see
    // onKeyDown above); this just additionally triggers once the system's
    // own long-press timer fires if the key is held, same as a long-press
    // on Home/Back elsewhere in Android.
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            onLongPressShowRemote()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // Cycles 2x -> 3x -> 4x -> back to unzoomed, picking the next step up
    // from wherever zoomScale actually is (not just these four exact values,
    // since a pinch gesture could have left it anywhere in between) so the
    // zoom key and pinch-zoom stay consistent with each other.
    private fun cycleZoom() {
        zoomScale = when {
            zoomScale < 2f -> 2f
            zoomScale < 3f -> 3f
            zoomScale < 4f -> 4f
            else -> 1f
        }
        applyZoomPan()
    }

    // Sends one or more (comma-separated in `value`) IR keycodes in the
    // background (sendKeycode does blocking socket I/O), using the rccode
    // the reference config recommends defaulting to -- the same value as
    // the configured VideoSource. A short gap between codes mirrors the
    // reference server's own multi-code macro handling (e.g. its "555"
    // quick-channel button), which sends each code as a separate keypress
    // rather than one combined command.
    private fun sendIr(value: String) {
        val c = client ?: return
        flashIrCommand(value)
        if (SlingboxType.isSlingerServer(AppSettings.load(this).slingboxType)) {
            // One POST with the whole comma-separated value -- the server
            // splits it itself and paces multi-code macros server-side, so
            // there's no need for the per-code delay loop the direct-to-box
            // path below uses.
            Thread { c.sendHttpIrCommand(value) }.start()
            return
        }
        val codes = value.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (codes.isEmpty()) return
        val rccode = AppSettings.load(this).videoSource ?: 1
        Thread {
            try {
                for ((i, code) in codes.withIndex()) {
                    c.sendKeycode(code, rccode)
                    if (i < codes.size - 1) Thread.sleep(150)
                }
            } catch (e: Exception) {
                // sendCmd() is exception-safe now (this crashed the whole app
                // via an uncaught SocketTimeoutException before that fix), but
                // this stays as a backstop for a bare background thread rather
                // than relying solely on the callee never throwing.
                android.util.Log.e("SlingboxPlayer", "sendIr failed", e)
            }
        }.start()
    }

    // Brief red flash of the keycode(s) just sent, top-left, independent of
    // the tap-revealed controls -- rapid presses just restart the timer
    // (same pattern as the toast fix for rapid swipes) rather than queuing
    // up and lagging behind what was actually pressed.
    private fun flashIrCommand(value: String) {
        runOnUiThread {
            irFlashHideRunnable?.let { uiHandler.removeCallbacks(it) }
            irFlashView.text = value
            irFlashView.visibility = android.view.View.VISIBLE
            val r = Runnable { irFlashView.visibility = android.view.View.GONE }
            irFlashHideRunnable = r
            uiHandler.postDelayed(r, 600)
        }
    }

    // Shown together on tap: the remote grid, and the top bar (resolution/
    // decoder/A-V-offset text plus Settings/Aspect). statusView (bottom line)
    // joins them here unconditionally -- while the remote is up, the debug
    // line is always visible regardless of the setting -- but on hide, it
    // follows the "Show debug messages" setting instead of disappearing
    // outright: on means it stays up permanently, off means it only shows
    // with the rest of the controls. See applyDebugVisibility/hideControls.
    // dpadFocus=true (only from long-pressing OK -- see onKeyLongPress)
    // switches the remote into a genuinely D-pad-navigable mode: the buttons
    // become reachable by focus-search and OK starts focused. Everywhere
    // else that shows the remote (tap, 'c', zoom key, etc.) leaves it false,
    // which keeps a stock device's physical/HW keyboard working exactly as
    // before while the remote happens to be visible, since
    // FOCUS_BLOCK_DESCENDANTS (set in onCreate, restored on hide below)
    // means the remote's buttons simply aren't focus-search candidates at
    // all in that case -- arrow keys/letters still reach onKeyDown.
    private fun showControls(dpadFocus: Boolean = false) {
        irGrid.visibility = android.view.View.VISIBLE
        debugOverlay.visibility = android.view.View.VISIBLE
        syncStatusVisibility()
        if (dpadFocus) {
            (irGrid as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            irOkButton.requestFocus()
        }
        scheduleHideControls()
    }

    // Called by the framework for every key/touch event dispatched to this
    // Activity, consumed or not -- including D-pad focus navigation between
    // remote buttons and OK-clicking a focused one, both of which are
    // consumed entirely by the focused Button itself (via its own built-in
    // key handling) and so never reach onKeyDown/onTap at all. Without this,
    // navigating the D-pad-mode remote (see showControls/onKeyLongPress)
    // would let it auto-hide out from under an otherwise-active user.
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (irGrid.visibility == android.view.View.VISIBLE) scheduleHideControls()
    }

    // Restarts the 5-second auto-hide countdown without touching visibility,
    // so panning the remote (a drag the HorizontalScrollView consumes for
    // itself, never reaching onTap/showControls) still counts as activity
    // instead of letting the remote vanish mid-drag.
    private fun scheduleHideControls() {
        hideControlsRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable {
            // Move focus off whatever remote button has it first (only ever
            // applicable if this was shown in D-pad mode) -- a focused view
            // becoming GONE gets reassigned somewhere by Android regardless,
            // but to somewhere unpredictable; rootFrame is a stable,
            // always-present fallback. Also restores the safe default of
            // blocking the remote's buttons from focus-search entirely,
            // whether or not this particular show was in D-pad mode.
            rootFrame.requestFocus()
            (irGrid as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            irGrid.visibility = android.view.View.GONE
            debugOverlay.visibility = android.view.View.GONE
            syncStatusVisibility()
        }
        hideControlsRunnable = r
        uiHandler.postDelayed(r, 5000)
    }

    private fun hideControls() {
        hideControlsRunnable?.let { uiHandler.removeCallbacks(it) }
        rootFrame.requestFocus()
        (irGrid as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        irGrid.visibility = android.view.View.GONE
        debugOverlay.visibility = android.view.View.GONE
        syncStatusVisibility()
    }

    // statusView combines four independent reasons to be up: the "Show debug
    // messages" setting (permanent while on), the rest of the controls being
    // shown (tap-revealed, auto-hides with them), the no-video placeholder
    // being shown, and video simply not playing yet at all (connecting,
    // reconnecting -- before the test card would even show, see
    // releaseSessionResources). The last two override the setting, since
    // that's exactly when the status text (what's actually going on) is most
    // needed, setting or not.
    private fun syncStatusVisibility() {
        val show = debugMessagesEnabled ||
            irGrid.visibility == android.view.View.VISIBLE ||
            noVideoImage.visibility == android.view.View.VISIBLE ||
            !firstVideoFrameRendered
        statusView.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    // setDecorFitsSystemWindows(false) (set below whenever fullscreen is on)
    // makes content draw edge-to-edge, under where the status bar/camera
    // cutout/navigation bar are -- without this, debugOverlay (pinned to the
    // very top) and statusView (pinned to the very bottom) end up physically
    // obscured by those areas instead of just "fullscreen," which looked like
    // the Settings/Aspect buttons had vanished. Pads them by the live inset
    // instead of a fixed guess, so it stays correct whether fullscreen is on
    // or off and across different cutout shapes.
    private fun applyInsetsPadding() {
        val basePad = (8 * resources.displayMetrics.density).toInt() // matches debugOverlay's 8dp
        val baseStatusPad = (6 * resources.displayMetrics.density).toInt() // matches statusView's 6dp
        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val topInset = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom
            debugOverlay.setPadding(basePad, basePad + topInset, basePad, basePad)
            statusView.setPadding(baseStatusPad, baseStatusPad, baseStatusPad, baseStatusPad + bottomInset)
            insets
        }
    }

    // Hides (or shows) the status/navigation bars per the Settings toggle, so
    // the video fills the whole screen when enabled. The system clears these
    // flags whenever the window loses focus (e.g. the Settings activity
    // opening, or a system dialog), so onWindowFocusChanged re-applies it
    // every time focus comes back -- the standard pattern for sticky
    // immersive mode, since there's no one-shot "stay fullscreen forever"
    // flag.
    private fun applyFullscreen() {
        val fullscreen = AppSettings.load(this).fullscreenEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!fullscreen)
            if (fullscreen) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen) {
                (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE
                    )
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == SettingsActivity.RESULT_DISCONNECT) {
            stopPlayback()
            finish()
        }
        // Otherwise (saved, or just backed out): onResume() will reconnect with
        // whatever AppSettings now holds, since navigating to Settings already
        // paused (and logged out of) the current session via onPause().
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startPlayback()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopPlayback()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(debugTick)
        stopPlayback()
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen()
        applyAspectRatio()
        applyDebugVisibility()
        applyClockVisibility()
        uiHandler.removeCallbacks(debugTick)
        uiHandler.post(debugTick)
        if (surfaceView.holder.surface.isValid) {
            startPlayback()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    private fun status(s: String) {
        android.util.Log.d("SlingboxPlayer", "[status] $s")
        lastStatusMessage = s
        // debugTick (500ms tick) appends the live resolution/backlog/rate
        // suffix on top of whatever's set here, so it immediately
        // overwrites a plain status()-only update anyway when controls are
        // visible -- setting the plain message first avoids a blank/stale
        // flash between this call and the next tick.
        runOnUiThread { statusView.text = s }
    }

    // statusView keeps receiving every status() update either way (so it's
    // never stale once re-enabled). Visibility is "setting on OR remote
    // showing": with the setting on, the bottom line stays up permanently;
    // with it off, it only shows while the rest of the controls (remote, top
    // bar) are up, same as them.
    private fun applyDebugVisibility() {
        debugMessagesEnabled = AppSettings.load(this).debugMessagesEnabled
        syncStatusVisibility()
    }

    // Independent of the debug-messages/controls machinery above -- the
    // clock is its own persistent corner overlay, not tied to a tap to
    // reveal it. debugTick (already ticking every 500ms regardless) updates
    // clockView.text whenever it's visible; this just decides visibility.
    private fun applyClockVisibility() {
        clockView.visibility = if (AppSettings.load(this).displayTimeEnabled)
            android.view.View.VISIBLE else android.view.View.GONE
    }

    // Cancels whatever toast is still showing before posting a new one --
    // without this, rapid swipes (each posting its own LENGTH_SHORT toast)
    // queue up behind each other instead of replacing, so what's on screen
    // lags further and further behind the actual swipe you just made.
    private var currentToast: android.widget.Toast? = null

    private fun toast(s: String) {
        runOnUiThread {
            currentToast?.cancel()
            val t = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT)
            currentToast = t
            t.show()
        }
    }

    // ---- edge drags: right = device volume, left = screen brightness ----
    // everything else: tap-to-resync/show-controls ----

    companion object {
        private const val EDGE_WIDTH_DP = 48f
        private const val SWIPE_THRESHOLD_DP = 60f
        private const val MAX_ZOOM = 4f
        private const val PINCH_COOLDOWN_MS = 400L
        private const val ARROW_PAN_STEP_DP = 40f
    }

    // Re-clamps pan to whatever the current zoom level actually allows (the
    // scaled view only overflows its own laid-out bounds by (zoomScale-1)/2
    // on each side, pivoting at its center by default) and reapplies both to
    // the SurfaceView. Called from both the pinch callback and pan dragging.
    private fun applyZoomPan() {
        val w = surfaceView.width.toFloat()
        val h = surfaceView.height.toFloat()
        val maxPanX = (zoomScale - 1f) * w / 2f
        val maxPanY = (zoomScale - 1f) * h / 2f
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        surfaceView.scaleX = zoomScale
        surfaceView.scaleY = zoomScale
        surfaceView.translationX = panX
        surfaceView.translationY = panY
    }

    private fun nextChannel() {
        toast("Channel +")
        sendIr("4")
    }

    private fun previousChannel() {
        toast("Channel -")
        sendIr("5")
    }

    // Replaces a plain click listener on rootFrame, since detecting "this drag
    // started in an edge strip" needs raw touch events, not just a click
    // callback. A drag that starts in either edge strip is consumed entirely
    // as that gesture (never reaches onTap()); everything else behaves
    // exactly like the click listener did -- down-then-up without much
    // movement calls onTap(), same as before. Button children still get
    // their own touches first regardless (normal Android touch dispatch), so
    // Settings/Aspect/IR buttons are unaffected.
    //
    // Pinch-zoom and, once zoomed, pan take over from the edge-drag/swipe
    // gestures entirely -- every event is fed to scaleGestureDetector first
    // (it tracks pinch state internally regardless of what else happens
    // below), and while zoomed (zoomScale > 1f) a finger down starts a pan
    // instead of the usual volume/brightness-edge or channel-swipe handling,
    // since those wouldn't make sense while looking at a cropped, zoomed view.
    private fun onRootTouch(view: android.view.View, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        val edgePx = EDGE_WIDTH_DP * resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                if (zoomScale > 1f) {
                    panActive = true
                    panLastX = event.x
                    panLastY = event.y
                    volumeDragActive = false
                    brightnessDragActive = false
                } else {
                    panActive = false
                    volumeDragActive = event.x >= view.width - edgePx
                    brightnessDragActive = !volumeDragActive && event.x <= edgePx
                    if (volumeDragActive) {
                        volumeDragStartY = event.y
                        volumeDragStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    } else if (brightnessDragActive) {
                        brightnessDragStartY = event.y
                        brightnessDragStartValue = currentWindowBrightness()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger just touched down -- this is becoming a
                // pinch (or just an extra stray touch), not a single-finger
                // drag; let the scale detector own it instead.
                volumeDragActive = false
                brightnessDragActive = false
                panActive = false
                lastMultiTouchAt = System.currentTimeMillis()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // One finger lifted but at least one remains (pinch ending,
                // most likely) -- if still zoomed, keep panning with
                // whatever's left rather than losing track mid-gesture. If
                // the pinch instead landed back at 1x (not zoomed), this
                // falls through to the plain swipe/tap path below with the
                // *other* finger's long-stale touchDownX/Y from when the
                // pinch started -- lastMultiTouchAt (checked below on the
                // final ACTION_UP) is what actually prevents that from being
                // misread as a channel swipe.
                if (zoomScale > 1f) {
                    panActive = true
                    panLastX = event.x
                    panLastY = event.y
                }
                lastMultiTouchAt = System.currentTimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleGestureDetector.isInProgress) {
                    // Handled by the onScale callback already.
                } else if (panActive) {
                    panX += event.x - panLastX
                    panY += event.y - panLastY
                    panLastX = event.x
                    panLastY = event.y
                    applyZoomPan()
                } else if (volumeDragActive) {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    // Dragging up increases volume: a full screen-height drag
                    // sweeps the whole volume range.
                    val deltaY = volumeDragStartY - event.y
                    val deltaSteps = (deltaY / view.height * maxVolume).toInt()
                    val newVolume = (volumeDragStartVolume + deltaSteps).coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, AudioManager.FLAG_SHOW_UI)
                } else if (brightnessDragActive) {
                    // Same "full screen height = full range" sweep as volume,
                    // but adjusts this window's own brightness override
                    // (WindowManager.LayoutParams.screenBrightness) rather
                    // than the system-wide setting -- no extra permission
                    // needed, and it only affects this screen.
                    val deltaY = brightnessDragStartY - event.y
                    val deltaFraction = deltaY / view.height
                    val newBrightness = (brightnessDragStartValue + deltaFraction).coerceIn(0.01f, 1f)
                    val attrs = window.attributes
                    attrs.screenBrightness = newBrightness
                    window.attributes = attrs
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (panActive) {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (Math.abs(event.x - touchDownX) < slop && Math.abs(event.y - touchDownY) < slop) {
                        onTap()
                    }
                } else if (!volumeDragActive && !brightnessDragActive &&
                    System.currentTimeMillis() - lastMultiTouchAt >= PINCH_COOLDOWN_MS) {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val swipeThresholdPx = SWIPE_THRESHOLD_DP * resources.displayMetrics.density
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                        onTap()
                    } else if (Math.max(Math.abs(dx), Math.abs(dy)) >= swipeThresholdPx) {
                        // Any of the four directions works, grouped into two
                        // actions: up or right = next channel, down or left =
                        // previous -- whichever axis moved further decides
                        // which pair is meant. IR 4/5 are the box's own
                        // dedicated channel-up/down keys (ABCryptobox_Remote.
                        // html's hidden duplicate block labels them "chup"/
                        // "chdown"), not the Pg+/Pg- (44/43) keypad buttons,
                        // which page an on-screen EPG grid instead.
                        if (Math.abs(dx) > Math.abs(dy)) {
                            if (dx > 0) nextChannel() else previousChannel()
                        } else {
                            if (dy < 0) nextChannel() else previousChannel()
                        }
                    }
                }
                volumeDragActive = false
                brightnessDragActive = false
                panActive = false
            }
        }
        return true
    }

    // The window's brightness override starts unset (-1, "use system
    // default"), which isn't usable as a numeric drag baseline -- fall back
    // to the actual system setting so the first drag doesn't jump.
    private fun currentWindowBrightness(): Float {
        val current = window.attributes.screenBrightness
        if (current in 0f..1f) return current
        return try {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    // ---- tap handling: resync + show/hide controls ----
    // The video plays distraction-free by default (only the bottom status
    // line stays visible); a tap brings the remote grid and the top bar
    // (resolution/decoder/A-V-offset text, Settings, Aspect) forward together.

    private fun onTap() {
        resyncRequested = true
        // Tapping outside the controls while they're showing dismisses them
        // instead of just re-extending the auto-hide timer -- a tap directly
        // on one of the grid's own buttons never reaches here at all
        // (consumed by the button's own click handler), so this only ever
        // fires for taps elsewhere.
        if (debugOverlay.visibility == android.view.View.VISIBLE) {
            hideControls()
        } else {
            showControls()
        }
    }

    // Same show/hide toggle as onTap(), but for the long-press-OK trigger
    // (see onKeyLongPress): shows the remote in D-pad-navigable mode, since
    // that's the one case (TV remote with no other way to reach most
    // buttons) where grabbing focus is actually wanted.
    private fun onLongPressShowRemote() {
        resyncRequested = true
        if (debugOverlay.visibility == android.view.View.VISIBLE) {
            hideControls()
        } else {
            showControls(dpadFocus = true)
        }
    }

    // ---- aspect ratio: letterbox/pillarbox the SurfaceView within its container ----

    // Deliberately synchronous, using display metrics instead of videoContainer's
    // own measured size (which would require a layout pass to have already
    // happened, i.e. videoContainer.post {}). Resizing a live SurfaceView can make
    // Android tear down and recreate its underlying native surface, which fires
    // surfaceDestroyed() -> stopPlayback() mid-decode -- applying the size once,
    // synchronously, before the surface is ever first created, avoids any later
    // resize racing against playback.
    private fun applyAspectRatio() {
        val s = AppSettings.load(this)
        val dm = resources.displayMetrics
        val cw = dm.widthPixels
        val ch = dm.heightPixels
        aspectButton.text = when (s.aspectRatio) {
            "16:9" -> "16:9"
            "4:3" -> "4:3"
            else -> "[]"
        }
        if (cw <= 0 || ch <= 0) return
        val lp = when (s.aspectRatio) {
            "16:9" -> fittedBox(cw, ch, 16, 9)
            "4:3" -> fittedBox(cw, ch, 4, 3)
            else -> FrameLayout.LayoutParams(cw, ch) // "fit": fill the available area, no letterboxing
        }
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    private fun fittedBox(cw: Int, ch: Int, ratioW: Int, ratioH: Int): FrameLayout.LayoutParams {
        var w = cw
        var h = w * ratioH / ratioW
        if (h > ch) {
            h = ch
            w = h * ratioW / ratioH
        }
        return FrameLayout.LayoutParams(w, h)
    }

    private fun cycleAspectRatio() {
        val s = AppSettings.load(this)
        val next = when (s.aspectRatio) {
            "4:3" -> "16:9"
            "16:9" -> "fit"
            else -> "4:3"
        }
        AppSettings.setAspectRatio(this, next)
        applyAspectRatio()
        toast("Aspect: $next")
    }

    // ---- session ----

    private fun startPlayback() {
        if (running) return
        running = true

        val s = AppSettings.load(this)

        playThread = Thread {
            try {
                // Gives the box a moment to actually release the previous
                // control session (acking LOGOUT doesn't necessarily mean
                // it's instantly ready for a new login -- see the "kicking
                // off other user" self-heal in SlingboxClient.startSession)
                // before the very first connect attempt too, not just
                // between automatic retries further down. Applies even to a
                // fresh app launch with no prior session, which is harmless
                // -- just an extra second before the first "Connecting...".
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                }
                while (running) {
                    val shouldRetry = runSession(s)
                    if (!running || !shouldRetry) break
                    releaseSessionResources()
                    status("Reconnecting...")
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                status("ERROR: $e")
            } finally {
                // If running is still true here, nobody outside this thread
                // (stopPlayback()) asked it to stop -- it gave up on its own
                // (runSession returned false after its own retry attempts
                // were exhausted, e.g. a reconnect's startSession failing
                // after the "kicking off other user" self-heal gave up too,
                // or an uncaught exception from a reconnect attempt). That
                // used to fall straight through to here with no cleanup and
                // no sign to the user that anything happened -- client's
                // control/stream sockets were left dangling, unclosed, and
                // the screen just silently stopped updating, which looked
                // exactly like getting kicked out with no explanation.
                // stopPlayback() already does its own teardown + toast after
                // joining this thread, so only do it here when THIS loop is
                // the one deciding to quit.
                val gaveUp = running
                running = false
                if (gaveUp) {
                    releaseSessionResources()
                    toast("Disconnected: could not reconnect to Slingbox")
                }
            }
        }
        playThread!!.start()
    }

    /**
     * Releases the current session's codec/audio/client resources without
     * touching [running] or [playThread] -- used both between automatic
     * reconnect attempts (runSession returning true, called from the play
     * thread) and as part of stopPlayback()'s own final teardown (called
     * from the main thread, after playThread has already stopped).
     */
    private fun releaseSessionResources() {
        // Back to plain black (not the test card) -- that's reserved for an
        // actual audio-only signal, not "no session active right now".
        // firstVideoFrameRendered resets here too (not just at the next
        // runSession's start), so the status line stays forced-visible
        // through the "Reconnecting..." gap between sessions as well.
        firstVideoFrameRendered = false
        bytesReceivedTotal = 0L
        lastRateSampleAt = 0L
        lastRateSampleBytes = 0L
        lastDisplayedRateKbps = 0.0
        runOnUiThread { noVideoImage.visibility = android.view.View.GONE; syncStatusVisibility() }
        keepAliveThread?.interrupt()
        videoPacer?.shutdown()

        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}

        // logout() does blocking socket I/O; run it on its own thread with a
        // short synchronous wait rather than risk NetworkOnMainThreadException
        // when this is called from stopPlayback() on the main thread.
        val c = client
        val teardown = Thread {
            if (c != null) {
                c.logout()
                c.closeControl()
            }
        }
        teardown.start()
        try {
            teardown.join(3000)
        } catch (_: InterruptedException) {
        }

        videoCodecThread?.quitSafely()
        audioCodecThread?.quitSafely()

        videoCodec = null
        audioCodec = null
        audioTrack = null
        videoPump = null
        audioPump = null
        videoPacer = null
        videoCodecThread = null
        videoCodecHandler = null
        audioCodecThread = null
        audioCodecHandler = null
        client = null
    }

    /**
     * Runs one full connect-and-stream session until it ends, one way or
     * another. Returns true if the caller should immediately reconnect (the
     * stream ended on its own while still meant to be running -- e.g. the box
     * renegotiating from audio-only to full video on power-on sends a
     * format-changed/stream-restart signal that ends the current
     * connection, which otherwise looked like a dead end rather than
     * something to recover from), false if it should stop entirely.
     */
    private fun runSession(s: AppSettings.Snapshot): Boolean {
        videoCodecThread = HandlerThread("video-codec-callbacks").also { it.start() }
        videoCodecHandler = Handler(videoCodecThread!!.looper)
        audioCodecThread = HandlerThread("audio-codec-callbacks").also { it.start() }
        audioCodecHandler = Handler(audioCodecThread!!.looper)

        val c = SlingboxClient(s.host, s.port, s.password, log = { status(it) })
        client = c

        val isSlingerServer = SlingboxType.isSlingerServer(s.slingboxType)
        val isSolo: Boolean
        val start: SlingboxClient.StreamStart
        if (isSlingerServer) {
            // No login, no control channel, no resolution/decoder/bitrate/etc
            // -- those are the real box's own concern, decided by whatever
            // the server was configured with, not something this client
            // sends. isSolo is always false here: the server re-emits a
            // standard, unencrypted ASF stream regardless of the real box's
            // own native protocol (see openHttpVideoStream's doc).
            isSolo = false
            start = c.openHttpVideoStream()
        } else {
            status("Connecting...")
            val ok = c.startSession(s.videoSource, s.resolution, s.frameRate, s.videoBandwidth, s.videoSmoothness, s.iframeRate, s.audioBitRate)
            if (!ok) {
                status("Session setup failed, stat=0x${c.stat.toString(16)}")
                return false
            }
            isSolo = SlingboxType.isSolo(s.slingboxType)
            start = c.openStream(s.resolution, isSolo)
        }
        val info = AsfHeader.parse(start.streamHeader)
        val vWidth = if (info.videoWidth > 0) info.videoWidth else 640
        val vHeight = if (info.videoHeight > 0) info.videoHeight else 480
        val aRate = if (info.audioSampleRate > 0) info.audioSampleRate else 32000
        val aChannels = if (info.audioChannels > 0) info.audioChannels else 2
        lastVideoWidth = vWidth
        lastVideoHeight = vHeight

        val videoDesc = if (info.videoStreamNumber in 1..127) "video#${info.videoStreamNumber} ${vWidth}x${vHeight}" else "no video"
        status("$videoDesc  audio#${info.audioStreamNumber} ${aRate}Hz x${aChannels}ch")

        // Video codec setup is deferred until the first key frame arrives -- see
        // initVideoCodecFromFirstFrame below for why (SPS/PPS need to come from real
        // stream data, rather than being left inline).
        setupAudioCodec(aRate, aChannels)
        audioCodec!!.start()

        val frameIntervalUs = 1_000_000L / s.frameRate.coerceAtLeast(1)
        // Sync video to the audio hardware's actual playback position instead of a
        // free-running wall clock: getPlaybackHeadPosition() counts frames actually
        // rendered, so it already reflects decode/buffering latency and drifts with
        // the real output device rather than this thread's own clock. Video then
        // simply targets "whatever audio is presenting right now" every frame -- if
        // it's behind, the wait comes out <= 0 and the frame goes out immediately,
        // which corrects ordinary drift with no extra bookkeeping. A real network
        // stall is different (neither stream gets new data, so there's nothing to
        // "catch up" to yet) -- onBacklogExceeded fires once the queued backlog
        // passes the pacer's threshold, triggering the same resync a tap would, to
        // recover that latency instead of just playing through it.
        val audioClockUs: () -> Long = { (audioTrack?.playbackHeadPosition ?: 0).toLong() * 1_000_000L / aRate }
        val pacer = VideoPacer(frameIntervalUs, audioClockUs, onBacklogExceeded = { resyncRequested = true }) {
            bytes -> videoPump?.submit(PendingFrame(bytes))
        }
        videoPacer = pacer

        keepAliveThread = Thread {
            try {
                while (running) {
                    Thread.sleep(10_000)
                    if (running) c.keepAlive()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                // Any uncaught exception on a background thread kills the whole
                // process, not just this thread -- confirmed the hard way via a
                // SocketTimeoutException escaping a similar bare thread elsewhere.
                // keepAlive() itself is exception-safe now, but this stays broad
                // as a backstop rather than narrowly matching just what's known
                // to throw today.
                android.util.Log.e("SlingboxPlayer", "keepAlive thread error", e)
            }
        }.also { it.isDaemon = true; it.start() }

        val videoAccum = ByteArrayOutputStream()
        val audioAccum = ByteArrayOutputStream()
        var videoStarted = false
        var requireKeyFrameToStart = true
        var videoCodecReady = false
        // codec.start() succeeding (see initVideoCodecFromFirstFrame) doesn't
        // guarantee it ever actually decodes anything -- e.g. if SPS/PPS
        // extraction from the first key frame failed below, some decoders
        // never produce a single output frame from the in-band parameter
        // sets alone. The test card has to stay up until a frame is really
        // rendered (setupVideoCodec's onOutputBufferAvailable), not just
        // until the codec is technically running -- reset per session since
        // it's a class field, not a local.
        firstVideoFrameRendered = false
        bytesReceivedTotal = 0L
        lastRateSampleAt = 0L
        lastRateSampleBytes = 0L
        lastDisplayedRateKbps = 0.0
        var audioOnlyNoticeShown = false

        // Diagnostic escape hatch (Settings): with pacing off, frames go straight to
        // the codec as soon as they're reassembled, for isolating decode issues from
        // VideoPacer's real-time scheduling.
        fun submitVideoFrame(bytes: ByteArray, isKeyFrame: Boolean) {
            if (s.pacingEnabled) {
                pacer.submit(bytes, isKeyFrame)
            } else {
                videoPump?.submit(PendingFrame(bytes))
            }
        }

        // The box repeats SPS/PPS inline before every key frame. Extracting them once
        // and stripping the repeated copies out of every later frame avoids the
        // decoder ever seeing a parameter set appear mid-stream. SPS/PPS are
        // submitted as an explicit input buffer flagged BUFFER_FLAG_CODEC_CONFIG
        // through the same FIFO queue the first real frame goes through right
        // after, rather than via MediaFormat's csd-0/csd-1 (which didn't reliably
        // win the ordering race against the first real frame in async callback
        // mode on at least one real device).
        //
        // Returns whether the codec actually got started. A key frame without
        // usable SPS/PPS (seen in practice: the box's own placeholder frame
        // before a source is powered on, under some decoders -- confirmed on
        // Waydroid, not reproduced on real hardware) used to still start the
        // codec and feed it that frame raw as a fallback; some decoders then
        // never produce output again even once a later, well-formed key frame
        // with real SPS/PPS arrives, permanently wedging the session (visible
        // as an endless reconnect loop, since every retry hits the exact same
        // first-frame-has-no-SPS/PPS situation and wedges the exact same way).
        // Not committing to starting the codec at all until a key frame with
        // genuine SPS/PPS shows up avoids that -- see handleCompleteVideoFrame.
        fun initVideoCodecFromFirstFrame(frameBytes: ByteArray): Boolean {
            val nals = splitAnnexBNals(frameBytes)
            val sps = nals.firstOrNull { it.first == 7 }?.second
            val pps = nals.firstOrNull { it.first == 8 }?.second
            if (sps == null || pps == null) {
                status("Key frame has no SPS/PPS yet; waiting for a usable one")
                return false
            }
            setupVideoCodec(vWidth, vHeight, s.videoDecoder, s.lowLatencyHint)
            videoCodec!!.start()
            videoCodecReady = true
            // Not "noVideoImage.visibility = GONE" here -- codec.start()
            // succeeding doesn't mean it will actually decode anything (see
            // setupVideoCodec's own onOutputBufferAvailable, which is where
            // the test card really gets hidden, on the first frame actually
            // rendered).
            if (audioOnlyNoticeShown) status("Video signal resumed.")
            val csd = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
            videoPump?.submit(PendingFrame(csd, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
            return true
        }

        fun handleCompleteVideoFrame(frameBytes: ByteArray) {
            val splitNals = splitAnnexBNals(frameBytes)
            val isKeyFrame = splitNals.any { it.first == 5 }
            if (!videoCodecReady) {
                // Only an actual key frame can carry SPS/PPS at all (that's
                // an H.264 structural fact, not a quirk of this box) -- a
                // delta frame arriving before the codec is up can't help,
                // so just drop it and wait for the next key frame instead
                // of attempting (and failing) extraction on it.
                if (!isKeyFrame || !initVideoCodecFromFirstFrame(frameBytes)) return
            }
            // Always stripped now: the codec is never started without
            // confirmed SPS/PPS (see above), so every frame reaching here
            // has already had its own inline copies extracted and sent once
            // as BUFFER_FLAG_CODEC_CONFIG -- feeding them again inline would
            // be the exact "parameter set appears mid-stream" case the
            // stripping exists to avoid.
            val toSend = buildAnnexB(splitNals, excludeTypes = setOf(7, 8))
            if (toSend.isNotEmpty()) submitVideoFrame(toSend, isKeyFrame)
        }

        // Tap-to-resync: jump to the latest key frame already sitting in the
        // pacer's backlog (common when pacing has let the network run ahead of
        // real-time playback), if there is one. If there isn't, do nothing at
        // all -- previously this always fell back to dropping everything and
        // waiting for a fresh key frame from the network, which could take up
        // to a full key-frame interval and was needless disruption for a tap
        // that has nothing to actually jump to.
        fun doResync() {
            if (!pacer.dropToLatestKeyFrameIfBuffered()) return
            videoAccum.reset()
            audioAccum.reset()
            videoPump?.clear()
            audioPump?.clear()
            try {
                audioTrack?.flush()
            } catch (_: Exception) {
            }
            videoStarted = false
            // The jumped-to key frame is already queued and will display on its
            // own; the *live* network reception is wherever it happens to be, with
            // no guarantee that's a frame boundary. So even after a jump, still
            // wait for the next clean mediaObjectOffset==0 boundary before
            // resuming accumulation -- just without requiring it to be a key
            // frame specifically, since a good one is already in hand.
            requireKeyFrameToStart = false
            status("Resynced: jumped to a buffered key frame.")
        }

        // Tracks video going quiet *after* it already started, not just
        // "never started" (audioOnlyNoticeShown's job, above) -- e.g. the
        // source device gets switched off mid-watch. Audio keeps flowing
        // either way, so its arrival doubles as a clock to notice video has
        // gone stale against. A short grace period (rather than reacting the
        // instant a video packet is late) avoids flapping the test card on
        // ordinary jitter between frames.
        var lastVideoPacketAt = System.currentTimeMillis()
        var videoStalled = false
        val videoStallTimeoutMs = 2500L

        var shouldRetry = false
        try {
            while (running) {
                if (resyncRequested) {
                    resyncRequested = false
                    doResync()
                }
                val more = c.readAndDemuxPacket(start.packetSize, isSolo) { e ->
                    when (e.streamNumber) {
                        info.videoStreamNumber -> {
                            lastVideoPacketAt = System.currentTimeMillis()
                            if (videoStalled) {
                                videoStalled = false
                                status("Video signal resumed.")
                                runOnUiThread { noVideoImage.visibility = android.view.View.GONE; syncStatusVisibility() }
                            }
                            // Sling's own fragmentation boundaries (mediaObjectOffset) are
                            // arbitrary ~3000-byte packet chops with no relationship to
                            // H.264 NAL unit boundaries -- a single NAL can span multiple
                            // fragments, confirmed against a real capture. Reassemble full
                            // access units before handing them to the pacer/codec.
                            if (e.mediaObjectOffset == 0) {
                                if (videoAccum.size() > 0) {
                                    handleCompleteVideoFrame(videoAccum.toByteArray())
                                    videoAccum.reset()
                                }
                                if (!videoStarted) {
                                    if (!requireKeyFrameToStart || e.keyFrame) {
                                        videoStarted = true
                                    } else {
                                        return@readAndDemuxPacket
                                    }
                                }
                            }
                            if (videoStarted) videoAccum.write(e.payload)
                        }
                        info.audioStreamNumber -> {
                            // The box sends no video frames at all (not even a blank one)
                            // when it detects no video signal on the selected input -- e.g.
                            // the downstream source is powered off -- audio keeps flowing
                            // normally the whole time. Flag that clearly once, rather than
                            // leaving the screen looking frozen with no explanation.
                            if (!videoCodecReady && !audioOnlyNoticeShown) {
                                audioOnlyNoticeShown = true
                                status("No video")
                                runOnUiThread { noVideoImage.visibility = android.view.View.VISIBLE; syncStatusVisibility() }
                            } else if (videoCodecReady && !videoStalled &&
                                System.currentTimeMillis() - lastVideoPacketAt > videoStallTimeoutMs) {
                                videoStalled = true
                                status("No video")
                                runOnUiThread { noVideoImage.visibility = android.view.View.VISIBLE; syncStatusVisibility() }
                            }
                            if (e.mediaObjectOffset == 0) {
                                if (audioAccum.size() > 0) {
                                    audioPump?.submit(PendingFrame(audioAccum.toByteArray()))
                                    audioAccum.reset()
                                }
                            }
                            audioAccum.write(e.payload)
                        }
                    }
                }
                // Once per packet read, not per demuxed entry (the onEntry
                // lambda above can fire more than once per packet) -- raw
                // wire bytes, not just payload, so this reflects actual
                // incoming throughput including ASF/encryption overhead.
                bytesReceivedTotal += start.packetSize
                if (!more) {
                    status("Stream ended")
                    // Only worth retrying if we weren't already asked to
                    // stop -- e.g. the box just renegotiating the stream
                    // format, not the activity being backgrounded.
                    shouldRetry = running
                    break
                }
            }
        } finally {
            keepAliveThread?.interrupt()
            pacer.shutdown()
        }
        return shouldRetry
    }

    private fun setupVideoCodec(
        width: Int, height: Int, decoderMode: String, lowLatencyHint: Boolean
    ) {
        val codec = createVideoDecoder(decoderMode)
        lastVideoDecoderName = codec.name
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        if (lowLatencyHint) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // The real stream is confirmed (full bit-level SPS parse) to be High Profile,
        // Level 4.0. Declaring that upfront, before the decoder has seen any SPS/PPS at
        // all, is different from the last two attempts (which only changed *when*
        // SPS/PPS bytes were delivered): this avoids the decoder ever assuming some
        // default/baseline configuration and then needing to reconfigure once it learns
        // the real profile/level -- logcat showed reconfiguration events
        // (processCSDInfo/updateC2Config_StreamSize) right around every failure, so
        // skipping that reconfiguration entirely is worth ruling out.
        format.setInteger(MediaFormat.KEY_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        format.setInteger(MediaFormat.KEY_LEVEL, android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        // SPS/PPS are NOT set here via csd-0/csd-1 (that was tried and didn't fix the
        // observed corruption -- see initVideoCodecFromFirstFrame for the explicit
        // BUFFER_FLAG_CODEC_CONFIG approach used instead, and why).
        // A theoretical mitigation tried while chasing an "I-frame has garbled
        // horizontal bands, P-frames gradually paper over it" corruption bug,
        // on the reasoning that a decoder's default input buffer size isn't
        // guaranteed to hold a full I-frame -- CodecInputPump would then be
        // forced to split one access unit across multiple queueInputBuffer
        // calls, which (at least on some decoders) could get treated as
        // multiple independent, each-incomplete access units. The actual,
        // confirmed (via logcat) root cause of that corruption turned out to
        // be unrelated: a decrypt carry-over bug in SlingboxClient's Solo
        // packet parsing, feeding a few bytes of raw TEA ciphertext into some
        // fragments instead of the decrypted payload (see emitGatedEntry's
        // own comment there). Left in regardless -- undersizing a decoder's
        // input buffer is a real, independently-worth-avoiding risk even
        // though it wasn't what was actually happening here -- so the pump's
        // splitting path (kept as a last-resort safety net) should never
        // actually need to trigger in practice.
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (width * height).coerceAtLeast(256 * 1024))
        val pump = CodecInputPump(codec)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                pump.onInputBufferAvailable(index)
            }
            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    mc.releaseOutputBuffer(index, true)
                    if (!firstVideoFrameRendered) {
                        firstVideoFrameRendered = true
                        runOnUiThread { noVideoImage.visibility = android.view.View.GONE; syncStatusVisibility() }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SlingboxPlayer", "[video] releaseOutputBuffer failed", e)
                }
            }
            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                android.util.Log.e("SlingboxPlayer", "[video] codec error: errorCode=${e.errorCode} diagnosticInfo=${e.diagnosticInfo}", e)
                status("Video codec error: $e")
            }
            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {}
        }, videoCodecHandler)
        try {
            codec.configure(format, surfaceView.holder.surface, null, 0)
        } catch (e: Exception) {
            try { codec.release() } catch (_: Exception) {}
            if (lowLatencyHint) {
                // KEY_LOW_LATENCY is just a hint, but support for it is
                // decoder-dependent -- confirmed on Waydroid's software
                // OMX.google.h264.decoder, which rejects it outright with
                // ERROR_UNSUPPORTED during configure() rather than quietly
                // ignoring it, failing the whole session. Retry once without
                // it instead of requiring the user to find and disable the
                // setting manually; a real hardware decoder that does
                // support it is unaffected, since this path never runs then.
                status("Decoder rejected low-latency mode ($e); retrying without it")
                setupVideoCodec(width, height, decoderMode, false)
                return
            }
            throw e
        }
        videoCodec = codec
        videoPump = pump
    }

    /**
     * "auto" leaves it to the platform's own default (createDecoderByType, which
     * on most devices prefers a hardware decoder when one exists). "hardware" and
     * "software" search MediaCodecList for a matching AVC decoder by name/type,
     * falling back to the platform default if none is found. "legacy" forces the
     * pre-Codec2 OMX HAL path directly by name, bypassing the Codec2 component --
     * useful on devices whose Codec2 AVC decoder has its own quirks.
     */
    private fun createVideoDecoder(mode: String): MediaCodec {
        if (mode == "legacy") {
            try {
                return MediaCodec.createByCodecName("OMX.Exynos.avc.dec")
            } catch (e: Exception) {
                status("Legacy OMX decoder unavailable ($e), falling back to platform default")
            }
        }
        if (mode != "hardware" && mode != "software") {
            return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        val wantHardware = mode == "hardware"
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
            if (isHardwareDecoder(info) != wantHardware) continue
            try {
                return MediaCodec.createByCodecName(info.name)
            } catch (_: Exception) {
                // this one didn't work out; keep looking
            }
        }
        status("No ${if (wantHardware) "hardware" else "software"} AVC decoder found, using platform default")
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    private fun isHardwareDecoder(info: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        // Pre-API29 heuristic: Google's own reference software decoders consistently
        // use these name prefixes; anything else is a vendor (hardware) implementation.
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google."))
    }

    private fun setupAudioCodec(sampleRate: Int, channels: Int) {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        val asc = AacConfig.buildAacLcAsc(sampleRate, channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))

        val channelMask = if (channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSizeInBytes = maxOf(minBuf * 2, 4096)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        // AudioTrack.Builder() itself needs API 23 -- below that (down to the
        // API 21 floor), the equivalent direct constructor taking the same
        // AudioAttributes/AudioFormat objects is used instead. The one thing
        // it can't do is PERFORMANCE_MODE_LOW_LATENCY (Builder-only, API 26+
        // regardless), so that's skipped entirely below 23 rather than only
        // below 26.
        val track = if (Build.VERSION.SDK_INT >= 23) {
            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            trackBuilder.build()
        } else {
            AudioTrack(
                attributes, audioFormat, bufferSizeInBytes, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }
        track.play()
        audioTrack = track

        val pump = CodecInputPump(codec)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                pump.onInputBufferAvailable(index)
            }
            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = mc.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(pcm, 0, info.size)
                        track.write(pcm, 0, pcm.size)
                    }
                    mc.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                }
            }
            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                status("Audio codec error: $e")
            }
            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {}
        }, audioCodecHandler)
        codec.configure(format, null, null, 0)
        audioCodec = codec
        audioPump = pump
    }

    private fun stopPlayback() {
        if (!running && client == null && videoCodec == null) return
        running = false
        client?.stopRequested = true
        client?.closeStream()
        // Interrupt in case the thread is between reconnect attempts (in its
        // 1s retry sleep) rather than blocked on the stream socket read that
        // closeStream() just unblocked -- either way, join should now return
        // promptly instead of waiting out the full retry delay.
        playThread?.interrupt()
        try {
            playThread?.join(2000)
        } catch (_: InterruptedException) {
        }
        // logout() does blocking socket I/O (write + read). Calling it directly
        // here (the main thread, since every caller of stopPlayback() -- onPause,
        // surfaceDestroyed, onDestroy -- runs there) throws
        // NetworkOnMainThreadException, which used to get silently swallowed by
        // logout()'s own try/catch: the logout command never actually reached the
        // box, leaving a stale session server-side. releaseSessionResources()
        // already runs it on a background thread with a bounded join for exactly
        // this reason.
        releaseSessionResources()
        toast("Logged out of Slingbox")
        playThread = null
    }
}

/**
 * Minimal Annex-B H.264 NAL unit splitter. Scans for 3-byte start codes (`00 00 01`);
 * a preceding 4-byte start code's extra leading zero is trimmed off the end of the
 * previous NAL. Returns (nalType, nalBytes) pairs with the start code stripped off,
 * in stream order.
 */
private fun splitAnnexBNals(data: ByteArray): List<Pair<Int, ByteArray>> {
    val starts = ArrayList<Int>()
    var i = 0
    while (i + 2 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            starts.add(i + 3)
            i += 3
        } else {
            i++
        }
    }
    val result = ArrayList<Pair<Int, ByteArray>>()
    for (idx in starts.indices) {
        val nalStart = starts[idx]
        if (nalStart >= data.size) continue
        val nalType = data[nalStart].toInt() and 0x1F
        var nalEnd = if (idx + 1 < starts.size) starts[idx + 1] - 3 else data.size
        while (nalEnd > nalStart && data[nalEnd - 1] == 0.toByte()) nalEnd--
        if (nalEnd > nalStart) {
            result.add(nalType to data.copyOfRange(nalStart, nalEnd))
        }
    }
    return result
}

/** Rebuilds Annex-B bytes (4-byte start codes) from (type, bytes) pairs, dropping [excludeTypes]. */
private fun buildAnnexB(nals: List<Pair<Int, ByteArray>>, excludeTypes: Set<Int>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for ((type, bytes) in nals) {
        if (type in excludeTypes) continue
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(bytes)
    }
    return out.toByteArray()
}

/**
 * One frame of elementary-stream data waiting to be fed to a [MediaCodec]. Tracks
 * how much of itself has already been handed to the codec, since a single frame
 * (e.g. an H.264 I-frame with inline SPS/PPS) can exceed one input buffer's
 * capacity and need to be split across several `queueInputBuffer` calls.
 */
private class PendingFrame(val data: ByteArray, val flags: Int = 0) {
    var offset = 0
}

/**
 * Pairs a MediaCodec's asynchronously-offered free input buffers with frame data
 * that arrives from the network on a different thread -- the standard pattern for
 * driving MediaCodec in async/callback mode when input doesn't arrive in lockstep
 * with the codec's buffer offers.
 *
 * Never truncates a frame that doesn't fit in one input buffer -- it splits it
 * across as many consecutive buffer offers as needed.
 */
private class CodecInputPump(private val codec: MediaCodec) {
    private val freeInputBuffers = LinkedBlockingDeque<Int>()
    private val pendingFrames = LinkedBlockingDeque<PendingFrame>()

    fun onInputBufferAvailable(index: Int) {
        freeInputBuffers.addLast(index)
        pump()
    }

    fun submit(frame: PendingFrame) {
        pendingFrames.addLast(frame)
        pump()
    }

    /** Drops whatever's queued but not yet fed to the codec (used on resync). */
    @Synchronized
    fun clear() {
        pendingFrames.clear()
    }

    @Synchronized
    private fun pump() {
        while (true) {
            val index = freeInputBuffers.pollFirst() ?: return
            val frame = pendingFrames.peekFirst()
            if (frame == null) {
                freeInputBuffers.addFirst(index)
                return
            }
            try {
                val buf = codec.getInputBuffer(index) ?: continue
                buf.clear()
                val remaining = frame.data.size - frame.offset
                val n = minOf(remaining, buf.remaining())
                if (n < remaining) {
                    // This condition (splitting one access unit across multiple
                    // queueInputBuffer calls, which the decoder might not understand
                    // as continued) could in theory silently corrupt I-frames, though
                    // it wasn't actually confirmed to be happening here -- see
                    // KEY_MAX_INPUT_SIZE's own comment, which should make this not
                    // happen in practice regardless. Logging it so it's visible in
                    // logcat if it ever does.
                    android.util.Log.w(
                        "SlingboxPlayer",
                        "Video frame (${frame.data.size} bytes) exceeds input buffer " +
                            "capacity (${buf.remaining()} bytes at offset ${frame.offset}); " +
                            "splitting across multiple queueInputBuffer calls."
                    )
                }
                buf.put(frame.data, frame.offset, n)
                frame.offset += n
                val pts = System.nanoTime() / 1000
                codec.queueInputBuffer(index, 0, n, pts, frame.flags)
                if (frame.offset >= frame.data.size) {
                    pendingFrames.pollFirst()
                }
            } catch (_: Exception) {
                pendingFrames.pollFirst()
            }
        }
    }
}

/**
 * Paces video frames to the real audio clock instead of submitting them to
 * the decoder as fast as they're demuxed. Each frame targets "whatever audio
 * is presenting right now" rather than a free-running wall clock --
 * [audioClockUs] (getPlaybackHeadPosition() converted to microseconds) counts
 * frames actually rendered by the audio HAL, so it already reflects
 * decode/buffer latency and tracks the real output device's clock. A genuine
 * master/slave A/V sync, not two independently-paced streams that happen to
 * start together. If video falls behind, the computed wait is <= 0 and the
 * frame goes out immediately: that single check both detects and corrects
 * drift, every frame, with no separate smoothing needed.
 *
 * That self-correction handles *drift* (small, continuous clock-rate
 * mismatches) but not an actual network stall: during a stall neither stream
 * gets new data, so there's no "video behind audio" to correct -- both are
 * equally starved together. Once the stall clears and a burst of backlog
 * arrives, pacing it out at the normal rate would just make the resulting
 * extra latency permanent. [onBacklogExceeded] is invoked (once per episode,
 * on the submitting thread) when the queued backlog exceeds
 * [AUTO_CATCHUP_THRESHOLD_US] -- the caller is expected to trigger the same
 * resync a tap would (see [dropToLatestKeyFrameIfBuffered]), which both
 * drops the stale video backlog and flushes the correspondingly-backlogged
 * audio, recovering the latency instead of just playing through it.
 *
 * [onSubmit] is called on the pacer's own thread, so it must be safe to call
 * from a thread other than the one that calls [submit].
 */
private class VideoPacer(
    private val frameIntervalUs: Long,
    private val audioClockUs: () -> Long,
    private val onBacklogExceeded: () -> Unit,
    private val onSubmit: (ByteArray) -> Unit
) {
    companion object {
        private const val AUTO_CATCHUP_THRESHOLD_US = 2_000_000L
    }

    private class PacedFrame(val ptsUs: Long, val isKeyFrame: Boolean, val data: ByteArray)

    private val queue = LinkedBlockingDeque<PacedFrame>()
    // The real A/V offset: this frame's target time minus where audio actually
    // is right now. Should stay small; a persistent backlog shows up as queue
    // depth, not as a large value here, since each frame paces itself against
    // the live clock regardless of how many others are queued behind it.
    @Volatile var avOffsetUs: Long = 0
        private set
    // Frames waiting to be paced out and sent to the decoder -- the actual
    // backlog depth; avOffsetUs stays small regardless of how deep this is,
    // since each frame paces itself against the live clock independently.
    val queueSize: Int get() = queue.size
    // Real queued size in bytes (sum of each buffered frame's actual size),
    // rather than just a frame count, for a backlog figure that means
    // something in KB regardless of resolution/bitrate.
    val queueBytes: Long get() = queue.sumOf { it.data.size.toLong() }
    @Volatile private var running = true
    @Volatile private var backlogSignaled = false

    // Only ever touched from the submitting (network/demux) thread. Re-anchored
    // to the live audio clock whenever the queue is empty (see submit()), which
    // naturally covers both "first frame ever" and "ongoing drift correction."
    private var nextPtsUs = 0L

    private val thread: Thread = Thread {
        try {
            while (running) {
                val frame = queue.take()
                if (!running) break
                val waitUs = frame.ptsUs - audioClockUs()
                if (waitUs > 0) {
                    try {
                        Thread.sleep(waitUs / 1000, ((waitUs % 1000) * 1000).toInt())
                    } catch (_: InterruptedException) {
                        if (!running) break
                    }
                }
                onSubmit(frame.data)
                avOffsetUs = frame.ptsUs - audioClockUs()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            // audioClockUs()/onSubmit() touch the AudioTrack/codec, which
            // stopPlayback() can release concurrently from the main thread --
            // an uncaught exception here would kill the whole process, not
            // just this thread (confirmed the hard way elsewhere already).
            android.util.Log.e("SlingboxPlayer", "VideoPacer thread error", e)
        }
    }.also { it.isDaemon = true; it.start() }

    /**
     * Assigns the next frame a steady PTS and enqueues it for pacing.
     *
     * Re-anchors nextPtsUs to the live audio clock whenever there's no backlog
     * to preserve (queue empty at submit time) -- without this, the synthetic
     * per-frame increment (based on the configured frame rate) drifts linearly
     * away from real elapsed audio time whenever the stream's actual delivered
     * frame rate doesn't exactly match that configured value, which it
     * essentially never does exactly. Only re-anchoring when the queue is
     * empty means an already-queued, deliberately-paced backlog never gets
     * disturbed -- this only corrects drift once caught up.
     */
    fun submit(data: ByteArray, isKeyFrame: Boolean) {
        if (queue.isEmpty()) {
            nextPtsUs = audioClockUs()
        }
        val pts = nextPtsUs
        nextPtsUs += frameIntervalUs
        queue.addLast(PacedFrame(pts, isKeyFrame, data))
        if (!backlogSignaled && queue.size.toLong() * frameIntervalUs > AUTO_CATCHUP_THRESHOLD_US) {
            backlogSignaled = true
            onBacklogExceeded()
        }
    }

    /** Drops whatever's queued and resets pacing state (used on resync). */
    fun reset() {
        queue.clear()
        avOffsetUs = 0
        backlogSignaled = false
    }

    /**
     * On resync: if a key frame is already sitting in the backlog (common when
     * pacing has let the network run ahead of real-time playback), jump
     * straight to the most recent one and keep playing from there. Returns
     * true if it found and kept one -- the caller should treat that as an
     * already-complete resync.
     *
     * If there's no key frame to jump to, this is a true no-op: the queue is
     * left exactly as it was (drained-then-restored, not discarded), nothing
     * else changes, and the caller should not fall back to dropping anything
     * or waiting for a fresh key frame from the network -- a tap with nothing
     * to jump to should not disrupt ongoing playback at all. Always false
     * (and a no-op) when pacing is off, since frames go straight to the
     * decoder in that mode and never accumulate here to begin with.
     */
    fun dropToLatestKeyFrameIfBuffered(): Boolean {
        val drained = ArrayList<PacedFrame>(queue.size)
        while (true) {
            val f = queue.pollFirst() ?: break
            drained.add(f)
        }
        val keyIdx = drained.indexOfLast { it.isKeyFrame }
        backlogSignaled = false
        if (keyIdx < 0) {
            // Nothing to jump to -- put everything back exactly as it was.
            // This must be a true no-op on the queue, not a silent discard.
            for (f in drained) queue.addLast(f)
            return false
        }
        for (i in keyIdx until drained.size) queue.addLast(drained[i])
        return true
    }

    fun shutdown() {
        running = false
        queue.clear()
        thread.interrupt()
    }
}
