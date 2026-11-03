package app.retro.slingeur

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Which Slingbox model family is configured, and whether that family speaks
 * the Solo/ProHD packetized-stream protocol (per-fragment TEA carry-over
 * decrypt, see SlingboxClient.processSoloMsg) or a standard, unencrypted ASF
 * Data Packet stream (see SlingboxClient.parseStandardAsfPacket).
 *
 * These three groupings are taken directly from the reference server's own
 * config.ini, which documents them as the three substitutable values for its
 * `sbtype` setting -- not a guess, the author's own deliberate categorization:
 *   sbtype="Solo/Pro/ProHD"    (active default there)
 *   sbtype="350/500/M1/M2"
 *   sbtype="240"
 * Solo-ness matches the reference server's own string-match logic
 * (`Solo = 'Solo' in sbtype or 'Pro' in sbtype`): true only for the first
 * grouping, since neither "350/500/M1/M2" nor "240" contain "Solo" or "Pro".
 *
 * Only the Solo-protocol path has been tested against a real box (yours, a
 * ProHD) -- the other two are implemented from the published ASF spec,
 * cross-checked against ffmpeg's own demuxer, but not yet verified against
 * a real 350/500/M1/M2 or Classic/240 capture.
 */
object SlingboxType {
    data class Option(val key: String, val label: String, val isSolo: Boolean)

    // Not a real Slingbox at all: a slingbox_server.py instance, reached over
    // plain HTTP instead of the box's own login/crypto protocol. It does its
    // own login/decrypt against the real box and re-emits a standard,
    // unencrypted ASF stream -- see SlingboxClient.openHttpVideoStream/
    // sendHttpIrCommand -- so it always behaves like a non-Solo box
    // (isSolo=false below) for demuxing purposes, and ignores the
    // resolution/frame-rate/bitrate/etc. settings entirely, since the server
    // decides those itself.
    const val KEY_SLINGER_SERVER = "slinger_server"
    fun isSlingerServer(key: String): Boolean = key == KEY_SLINGER_SERVER

    val OPTIONS = listOf(
        Option("solo_pro_prohd", "Solo/Pro/ProHD", true),
        Option("350_500_m1_m2", "350/500/M1/M2 (untested)", false),
        Option("240", "240 (untested)", false),
        Option(KEY_SLINGER_SERVER, "Slinger server", false)
    )

    fun isSolo(key: String): Boolean = OPTIONS.firstOrNull { it.key == key }?.isSolo ?: true
    fun indexOf(key: String): Int = OPTIONS.indexOfFirst { it.key == key }.coerceAtLeast(0)
    fun keyAt(index: Int): String = OPTIONS.getOrNull(index)?.key ?: "solo_pro_prohd"
}

/** Resolution-code to pixel-dimensions mapping (res.txt, the reference server's own table). */
object SlingboxResolution {
    data class Option(val value: Int, val width: Int, val height: Int) {
        val label: String get() = when {
            value == 0 -> "$value (audio only)"
            width > 0 -> "$value: ${width}x$height"
            else -> "$value (unspecified)"
        }
    }

    // 0 isn't listed in res.txt, but it's a legitimate selection: config.ini
    // documents the valid range as 0..16, and SlingboxClient.openStream treats
    // it as a distinct, smaller fixed packet size (1636 vs 3000 bytes) from
    // every other value -- kept as an explicit entry rather than omitted.
    val OPTIONS = listOf(
        Option(0, 0, 0),
        Option(1, 320, 240),
        Option(2, 160, 120),
        Option(3, 352, 240),
        Option(4, 176, 120),
        Option(5, 640, 480),
        Option(6, 640, 240),
        Option(7, 320, 480),
        Option(8, 128, 96),
        Option(9, 224, 176),
        Option(10, 448, 240),
        Option(11, 256, 192),
        Option(12, 1280, 720),
        Option(13, 1440, 544),
        Option(14, 1680, 544),
        Option(15, 1920, 544),
        Option(16, 1920, 1080)
    )

    fun indexOf(value: Int): Int = OPTIONS.indexOfFirst { it.value == value }.coerceAtLeast(0)
    fun valueAt(index: Int): Int = OPTIONS.getOrNull(index)?.value ?: 5

    /** Just the dimensions (e.g. "640x480"), for display contexts that don't want the code number. */
    fun dimensionsLabel(value: Int): String {
        if (value == 0) return "audio only"
        val o = OPTIONS.firstOrNull { it.value == value } ?: return "res $value"
        return if (o.width > 0) "${o.width}x${o.height}" else "unspecified resolution"
    }
}

/** One saved Slingbox's connection details -- everything that's specific to
 *  which box you're talking to, as opposed to the player/stream preferences
 *  in [AppSettings.Snapshot], which stay shared across all of them. */
data class SlingboxProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val password: String,
    val videoSource: Int?,
    val slingboxType: String,
    // These five, plus videoSource above, all go into the same
    // SET_VIDEO_PARAMETERS request as part of session setup -- genuinely
    // box-specific (e.g. a box on a slower link wanting a lower bitrate),
    // not a player/display preference like the fields that stayed in
    // Snapshot (aspect ratio, decoder choice, pacing, etc).
    val resolution: Int,
    val frameRate: Int,
    val videoBandwidth: Int,
    val videoSmoothness: Int,
    val iframeRate: Int,
    val audioBitRate: Int
)

/**
 * Single persisted source of truth for connection/stream parameters and player
 * preferences, backed by SharedPreferences. Defaults match config.ini.
 *
 * MainActivity's capture form writes here when "Play live" is tapped (so the
 * values it was using become what PlayerActivity/SettingsActivity see), and
 * SettingsActivity (reachable from the live player's overlay) reads/writes here
 * directly. PlayerActivity always reads from here rather than from Intent
 * extras, so changing settings and returning to the player naturally picks up
 * the new values on the next connect.
 */
object AppSettings {
    private const val PREFS_NAME = "slingbox_settings"

    private const val KEY_BOXES_JSON = "boxesJson"
    private const val KEY_ACTIVE_BOX_ID = "activeBoxId"
    // Legacy single-box keys -- no longer written, but still read once by
    // loadBoxes() to migrate an existing install's saved connection into a
    // single box the first time it runs after this feature shipped, rather
    // than silently losing it.
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_PASSWORD = "password"
    private const val KEY_VIDEO_SOURCE = "videoSource"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FRAME_RATE = "frameRate"
    private const val KEY_VIDEO_BANDWIDTH = "videoBandwidth"
    private const val KEY_VIDEO_SMOOTHNESS = "videoSmoothness"
    private const val KEY_IFRAME_RATE = "iframeRate"
    private const val KEY_AUDIO_BIT_RATE = "audioBitRate"
    private const val KEY_SLINGBOX_TYPE = "slingboxType"
    private const val KEY_ASPECT_RATIO = "aspectRatio" // "16:9" or "4:3"
    private const val KEY_VIDEO_DECODER = "videoDecoder" // "auto", "hardware", or "software"
    private const val KEY_PACING_ENABLED = "pacingEnabled"
    private const val KEY_LOW_LATENCY_HINT = "lowLatencyHint"
    private const val KEY_FULLSCREEN_ENABLED = "fullscreenEnabled"
    private const val KEY_DEBUG_MESSAGES_ENABLED = "debugMessagesEnabled"
    private const val KEY_DISPLAY_TIME_ENABLED = "displayTimeEnabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val host: String,
        val port: Int,
        val password: String,
        val videoSource: Int?,
        val resolution: Int,
        val frameRate: Int,
        val videoBandwidth: Int,
        val videoSmoothness: Int,
        val iframeRate: Int,
        val audioBitRate: Int,
        val slingboxType: String,
        val aspectRatio: String,
        val videoDecoder: String,
        val pacingEnabled: Boolean,
        val lowLatencyHint: Boolean,
        val fullscreenEnabled: Boolean,
        val debugMessagesEnabled: Boolean,
        val displayTimeEnabled: Boolean
    )

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        val box = activeBox(context)
        return Snapshot(
            host = box?.host ?: "192.0.2.1",
            port = box?.port ?: 5001,
            password = box?.password ?: "",
            videoSource = box?.videoSource,
            resolution = box?.resolution ?: 5,
            frameRate = box?.frameRate ?: 30,
            videoBandwidth = box?.videoBandwidth ?: 500,
            videoSmoothness = box?.videoSmoothness ?: 1,
            iframeRate = box?.iframeRate ?: 15,
            audioBitRate = box?.audioBitRate ?: 64,
            slingboxType = box?.slingboxType ?: "solo_pro_prohd",
            aspectRatio = p.getString(KEY_ASPECT_RATIO, "16:9") ?: "16:9",
            videoDecoder = p.getString(KEY_VIDEO_DECODER, "auto") ?: "auto",
            pacingEnabled = p.getBoolean(KEY_PACING_ENABLED, true),
            lowLatencyHint = p.getBoolean(KEY_LOW_LATENCY_HINT, true),
            fullscreenEnabled = p.getBoolean(KEY_FULLSCREEN_ENABLED, true),
            debugMessagesEnabled = p.getBoolean(KEY_DEBUG_MESSAGES_ENABLED, true),
            displayTimeEnabled = p.getBoolean(KEY_DISPLAY_TIME_ENABLED, true)
        )
    }

    // host/port/password/videoSource/resolution/frameRate/videoBandwidth/
    // videoSmoothness/iframeRate/audioBitRate/slingboxType are deliberately
    // not written here -- those all live in the box list (saveBoxes/
    // setActiveBoxId) now, edited via "Manage Slingboxes" rather than this
    // screen.
    fun save(context: Context, s: Snapshot) {
        prefs(context).edit()
            .putString(KEY_ASPECT_RATIO, s.aspectRatio)
            .putString(KEY_VIDEO_DECODER, s.videoDecoder)
            .putBoolean(KEY_PACING_ENABLED, s.pacingEnabled)
            .putBoolean(KEY_LOW_LATENCY_HINT, s.lowLatencyHint)
            .putBoolean(KEY_FULLSCREEN_ENABLED, s.fullscreenEnabled)
            .putBoolean(KEY_DEBUG_MESSAGES_ENABLED, s.debugMessagesEnabled)
            .putBoolean(KEY_DISPLAY_TIME_ENABLED, s.displayTimeEnabled)
            .apply()
    }

    fun setAspectRatio(context: Context, aspectRatio: String) {
        prefs(context).edit().putString(KEY_ASPECT_RATIO, aspectRatio).apply()
    }

    // ---- multiple saved Slingboxes ----

    /** Reads the saved box list, migrating an existing single-box install's
     *  legacy host/port/password/videoSource keys into one box the first
     *  time this runs (so upgrading doesn't silently lose a saved connection),
     *  or synthesizing one default box if there's truly nothing saved yet. */
    fun loadBoxes(context: Context): List<SlingboxProfile> {
        val p = prefs(context)
        val json = p.getString(KEY_BOXES_JSON, null)
        if (json != null) {
            val arr = JSONArray(json)
            val boxes = ArrayList<SlingboxProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val vs = o.optInt("videoSource", -1)
                boxes.add(
                    SlingboxProfile(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        host = o.getString("host"),
                        port = o.getInt("port"),
                        password = o.getString("password"),
                        videoSource = if (vs < 0) null else vs,
                        slingboxType = o.optString("slingboxType", "solo_pro_prohd"),
                        resolution = o.optInt("resolution", 5),
                        frameRate = o.optInt("frameRate", 30),
                        videoBandwidth = o.optInt("videoBandwidth", 500),
                        videoSmoothness = o.optInt("videoSmoothness", 1),
                        iframeRate = o.optInt("iframeRate", 15),
                        audioBitRate = o.optInt("audioBitRate", 64)
                    )
                )
            }
            return boxes
        }

        // Nothing saved under the new key yet -- migrate from the legacy
        // single-box fields (present on any pre-existing install) or fall
        // back to the same hardcoded defaults load() always used.
        val legacyVideoSourceRaw = p.getInt(KEY_VIDEO_SOURCE, 1)
        val migrated = SlingboxProfile(
            id = UUID.randomUUID().toString(),
            name = "Slingbox",
            host = p.getString(KEY_HOST, "192.0.2.1") ?: "192.0.2.1",
            port = p.getInt(KEY_PORT, 5001),
            password = p.getString(KEY_PASSWORD, "") ?: "",
            videoSource = if (legacyVideoSourceRaw < 0) null else legacyVideoSourceRaw,
            slingboxType = p.getString(KEY_SLINGBOX_TYPE, "solo_pro_prohd") ?: "solo_pro_prohd",
            resolution = p.getInt(KEY_RESOLUTION, 5),
            frameRate = p.getInt(KEY_FRAME_RATE, 30),
            videoBandwidth = p.getInt(KEY_VIDEO_BANDWIDTH, 500),
            videoSmoothness = p.getInt(KEY_VIDEO_SMOOTHNESS, 1),
            iframeRate = p.getInt(KEY_IFRAME_RATE, 15),
            audioBitRate = p.getInt(KEY_AUDIO_BIT_RATE, 64)
        )
        val boxes = listOf(migrated)
        saveBoxes(context, boxes)
        setActiveBoxId(context, migrated.id)
        return boxes
    }

    fun saveBoxes(context: Context, boxes: List<SlingboxProfile>) {
        val arr = JSONArray()
        for (b in boxes) {
            val o = JSONObject()
            o.put("id", b.id)
            o.put("name", b.name)
            o.put("host", b.host)
            o.put("port", b.port)
            o.put("password", b.password)
            o.put("videoSource", b.videoSource ?: -1)
            o.put("slingboxType", b.slingboxType)
            o.put("resolution", b.resolution)
            o.put("frameRate", b.frameRate)
            o.put("videoBandwidth", b.videoBandwidth)
            o.put("videoSmoothness", b.videoSmoothness)
            o.put("iframeRate", b.iframeRate)
            o.put("audioBitRate", b.audioBitRate)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_BOXES_JSON, arr.toString()).apply()
    }

    fun activeBoxId(context: Context): String? = prefs(context).getString(KEY_ACTIVE_BOX_ID, null)

    fun setActiveBoxId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE_BOX_ID, id).apply()
    }

    /** The currently-selected box, falling back to the first saved one if
     *  the active id is unset or no longer exists (e.g. it was removed),
     *  or null if there are no boxes saved at all. */
    fun activeBox(context: Context): SlingboxProfile? {
        val boxes = loadBoxes(context)
        val id = activeBoxId(context)
        return boxes.firstOrNull { it.id == id } ?: boxes.firstOrNull()
    }

    fun newBoxId(): String = UUID.randomUUID().toString()

    /** Applies [update] to just the currently-active box and writes the
     *  whole list back -- lets SettingsActivity offer a quick way to tweak
     *  the box you're actually watching (resolution, bitrate, etc.) without
     *  a trip through "Manage Slingboxes" and hunting for the right row.
     *  No-ops if there's no active box. */
    fun updateActiveBox(context: Context, update: (SlingboxProfile) -> SlingboxProfile) {
        val id = activeBoxId(context) ?: return
        val boxes = loadBoxes(context).map { if (it.id == id) update(it) else it }
        saveBoxes(context, boxes)
    }
}
