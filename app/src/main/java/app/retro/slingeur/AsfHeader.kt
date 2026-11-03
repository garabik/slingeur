package app.retro.slingeur

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Minimal parser for the ASF Header Object captured by [SlingboxClient.openStream].
 * Only extracts what the live player needs: which ASF Stream Number is video vs
 * audio, and the audio's real sample rate / channel count from its WAVEFORMATEX.
 *
 * Ground-truthed against a real capture from a Slingbox ProHD (see conversation
 * notes): all the GUIDs here are the standard, un-tampered ASF ones -- nothing
 * about this header is Slingbox-proprietary. The one genuine gotcha: the audio
 * WAVEFORMATEX's trailing "extra data" (cbSize bytes) is NOT a usable
 * AudioSpecificConfig -- feeding it to a decoder produces "audio object type 0"
 * (invalid). The real sample rate/channel count fields ARE correct, though, so
 * [AacConfig.buildAacLcAsc] synthesizes a standard ASC from those instead of
 * trusting the box's own extra-data bytes.
 */
object AsfHeader {

    private val HEADER_OBJECT_GUID = guidBytes("75B22630-668E-11CF-A6D9-00AA0062CE6C")
    private val STREAM_PROPERTIES_GUID = guidBytes("B7DC0791-A9B7-11CF-8EE6-00C00C205365")
    private val FILE_PROPERTIES_GUID = guidBytes("8CABDCA1-A947-11CF-8EE4-00C00C205365")
    private val AUDIO_MEDIA_GUID = guidBytes("F8699E40-5B4D-11CF-A8FD-00805F5C442B")
    private val VIDEO_MEDIA_GUID = guidBytes("BC19EFC0-5B4D-11CF-A8FD-00805F5C442B")

    data class Info(
        // -1 when the header has no video Stream Properties Object at all --
        // a legitimate case, not a parse failure: resolution 0 (audio only)
        // streams a header with just an audio stream in it. No real stream
        // number is ever -1, so this plus a plain `when` branch in the demux
        // loop naturally never matches it, same as any other audio-only
        // detection.
        val videoStreamNumber: Int,
        val audioStreamNumber: Int,
        val audioChannels: Int,
        val audioSampleRate: Int,
        val videoWidth: Int,
        val videoHeight: Int
    )

    private fun guidBytes(uuidString: String): ByteArray {
        val u = UUID.fromString(uuidString)
        val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(u.mostSignificantBits)
        bb.putLong(u.leastSignificantBits)
        val be = bb.array()
        // Microsoft GUIDs are serialized with the first three fields little-endian
        // and the last two big-endian (standard "bytes_le" layout).
        return byteArrayOf(
            be[3], be[2], be[1], be[0],
            be[5], be[4],
            be[7], be[6],
            be[8], be[9], be[10], be[11], be[12], be[13], be[14], be[15]
        )
    }

    private fun eq16(data: ByteArray, off: Int, guid: ByteArray): Boolean {
        if (off + 16 > data.size) return false
        for (i in 0 until 16) if (data[off + i] != guid[i]) return false
        return true
    }

    fun parse(header: ByteArray): Info {
        require(eq16(header, 0, HEADER_OBJECT_GUID)) { "Not an ASF Header Object" }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val numHeaderObjects = bb.getInt(24)

        var videoStreamNumber = -1
        var audioStreamNumber = -1
        var audioChannels = 0
        var audioSampleRate = 0
        var videoWidth = 0
        var videoHeight = 0

        var off = 30
        for (i in 0 until numHeaderObjects) {
            if (off + 24 > header.size) break
            val size = bb.getLong(off + 16)
            if (size < 24 || off + size > header.size) break
            if (eq16(header, off, STREAM_PROPERTIES_GUID)) {
                val bodyOff = off + 24
                val streamType = header.copyOfRange(bodyOff, bodyOff + 16)
                val tsDataLen = bb.getInt(bodyOff + 40)
                val flags = bb.getShort(bodyOff + 48).toInt() and 0xFFFF
                val streamNumber = flags and 0x7F
                val tsDataOff = bodyOff + 54
                when {
                    eq16(streamType, 0, AUDIO_MEDIA_GUID) -> {
                        audioStreamNumber = streamNumber
                        if (tsDataLen >= 8) {
                            audioChannels = bb.getShort(tsDataOff + 2).toInt() and 0xFFFF
                            audioSampleRate = bb.getInt(tsDataOff + 4)
                        }
                    }
                    eq16(streamType, 0, VIDEO_MEDIA_GUID) -> {
                        videoStreamNumber = streamNumber
                        if (tsDataLen >= 8) {
                            videoWidth = bb.getInt(tsDataOff)
                            videoHeight = bb.getInt(tsDataOff + 4)
                        }
                    }
                }
            }
            off += size.toInt()
        }

        // No require() on videoStreamNumber -- see the Info docstring, a
        // missing one is a legitimate audio-only stream, not a parse error.
        require(audioStreamNumber in 1..127) { "Could not find an audio Stream Properties Object in header" }

        return Info(videoStreamNumber, audioStreamNumber, audioChannels, audioSampleRate, videoWidth, videoHeight)
    }

    /**
     * The File Properties Object's "Minimum Data Packet Size" field -- for a
     * CBR stream (true of every Slingbox-derived one seen here) this equals
     * the Maximum Data Packet Size too, i.e. it's simply *the* fixed packet
     * size subsequent reads should use. Needed for [SlingboxClient.
     * openHttpVideoStream]'s "Slinger server" path, which (unlike
     * [SlingboxClient.openStream]'s direct-to-box login) has no protocol
     * handshake to learn this from elsewhere -- it has to come from the
     * header itself. Standard ASF layout (not Slingbox-specific): within the
     * object body, Minimum Data Packet Size is a 4-byte little-endian field
     * at offset 68 (after File ID[16] + File Size[8] + Creation Date[8] +
     * Data Packets Count[8] + Play Duration[8] + Send Duration[8] +
     * Preroll[8] + Flags[4]).
     */
    fun minDataPacketSize(header: ByteArray): Int {
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val numHeaderObjects = bb.getInt(24)
        var off = 30
        for (i in 0 until numHeaderObjects) {
            if (off + 24 > header.size) break
            val size = bb.getLong(off + 16)
            if (size < 24 || off + size > header.size) break
            if (eq16(header, off, FILE_PROPERTIES_GUID)) {
                val bodyOff = off + 24
                if (bodyOff + 72 <= header.size) {
                    return bb.getInt(bodyOff + 68)
                }
            }
            off += size.toInt()
        }
        return 0
    }
}

/**
 * Builds a standard MPEG-4 AudioSpecificConfig for plain AAC-LC, since the box's
 * own WAVEFORMATEX extra-data bytes are not a usable one (see [AsfHeader] doc).
 */
object AacConfig {
    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    fun buildAacLcAsc(sampleRate: Int, channels: Int): ByteArray {
        val freqIdx = SAMPLE_RATES.indexOf(sampleRate).let { if (it < 0) 15 else it }
        val objectType = 2 // AAC LC
        val chanConfig = if (channels in 1..7) channels else 2
        val bits = (objectType shl 11) or (freqIdx shl 7) or (chanConfig shl 3)
        return byteArrayOf(((bits shr 8) and 0xFF).toByte(), (bits and 0xFF).toByte())
    }
}
