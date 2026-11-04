package app.retro.slingeur

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Port of slingbox_server.py's control protocol (login, dynamic key exchange,
 * stream setup, keepalive, IR send, logout) and the Solo/ProHD per-packet stream
 * decryption (process_solo_msg). This talks directly to the Slingbox; there is no
 * Python server involved.
 *
 * Deliberately faithful to the reference, including its quirks (e.g. SendKeycode's
 * oddly-sized, non-8-byte-aligned payload -- see SlingboxCrypto.crypt), because the
 * real device expects exactly these bytes.
 */
class SlingboxClient(
    val host: String,
    val port: Int,
    val password: String,
    val log: (String) -> Unit = {}
) {
    // ---- Session state (mirrors the Python streamer()'s nonlocal/closure vars) ----
    private var sid = 0
    private var seq = 0
    private var smode = 0x2000
    private var skey = SlingboxCrypto.DEFAULT_KEY
    var stat = 0
        private set
    private var dbuf: ByteArray = ByteArray(0)

    // process_solo_msg carry-over state, persists across stream packets.
    private var tbd = 0
    private var dco: ByteArray = ByteArray(0)
    private var bco = 0
    private var bts = 0
    // Masked stream number (sn and 0x3F) that the current bco/dco carry actually
    // belongs to -- needed so a resolved carry can be emitted as a continuation of
    // the correct stream's frame, not whichever entry happens to resolve it next.
    private var pendingTailStreamNumber = -1

    private var controlSocket: Socket? = null
    private var controlOut: OutputStream? = null
    private var controlIn: InputStream? = null

    private var streamSocket: Socket? = null
    private var streamIn: InputStream? = null

    @Volatile var stopRequested = false

    companion object {
        // Fixed "random" challenge used by the reference server. The box doesn't
        // care that it's not actually random -- the dynamic key derivation mixes
        // in the box's own challenge too.
        val RAND16: ByteArray = hexToBytes("feedfacedeadbeef1111222233334444")

        // ASF Data Object GUID (75B22636-668E-11CF-A6D9-00AA0062CE6C in little-endian
        // byte layout). Marks the end of the ASF header and the start of the first
        // data packet; its fixed fields (GUID+Size+FileID+TotalPackets+Reserved) are
        // 50 bytes, hence +50 below.
        val ASF_DATA_OBJECT_GUID: ByteArray = byteArrayOf(
            0x36, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
            0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C
        )
        const val ASF_DATA_OBJECT_FIXED_SIZE = 50

        val SLINGBOX_MAGIC_UTF16LE: ByteArray = "Slingbox".toByteArray(Charset.forName("UTF-16LE"))

        fun hexToBytes(s: String): ByteArray {
            val n = s.length / 2
            val out = ByteArray(n)
            for (i in 0 until n) {
                out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
            }
            return out
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
            if (needle.isEmpty()) return -1
            outer@ for (i in from..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        private fun opcodeName(opcode: Int): String = when (opcode) {
            0x67 -> "LOGIN"
            0xc6 -> "SETUP_DYNAMIC_KEY"
            0x7e -> "STREAM_CONTROL"
            0x93 -> "KICK_OFF_USER"
            0x6a -> "UNK_0x6a"
            0x85 -> "SELECT_VIDEO_SOURCE"
            0x86 -> "GET_KEY_CODES"
            0xa6 -> "MISC_PARAMS"
            0xb5 -> "SET_VIDEO_PARAMETERS"
            0x66 -> "KEEPALIVE"
            0x87 -> "SEND_KEYCODE"
            0x89 -> "SEND_DOTTED_KEYCODE"
            0x68 -> "LOGOUT"
            else -> "OPCODE"
        }
    }

    // ---- small binary-packing helpers (all little-endian, matching struct '<' format) ----

    private fun leInt(v: Int): ByteArray {
        val b = ByteArray(4)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(0, v)
        return b
    }

    private fun leShort(v: Int): ByteArray {
        val b = ByteArray(2)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(0, v.toShort())
        return b
    }

    private fun zeros(n: Int): ByteArray = ByteArray(n)

    /** UTF-16LE-ish encoding used by the Python futf16(): one ASCII char -> (char, 0x00). */
    private fun futf16(s: String): ByteArray {
        val out = ByteArray(s.length * 2)
        for (i in s.indices) {
            out[i * 2] = s[i].code.toByte()
            out[i * 2 + 1] = 0
        }
        return out
    }

    /** struct '<Ns' semantics: zero-padded to exactly [size] bytes; errors if longer. */
    private fun fixedField(data: ByteArray, size: Int): ByteArray {
        require(data.size <= size) { "field of ${data.size} bytes overflows fixed size $size" }
        val out = ByteArray(size)
        System.arraycopy(data, 0, out, 0, data.size)
        return out
    }

    // ---- low level socket IO ----

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return buf
    }

    /** Mirrors a single Python `.recv(maxLen)` call: one read, whatever is available. */
    private fun recvUpTo(input: InputStream, maxLen: Int): ByteArray {
        val buf = ByteArray(maxLen)
        val r = input.read(buf, 0, maxLen)
        if (r <= 0) return ByteArray(0)
        return buf.copyOf(r)
    }

    private fun slingOpen(connectionType: String, sessionId: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 15_000)
        s.soTimeout = 20_000
        val request = "GET /stream.asf HTTP/1.1\r\nAccept: */*\r\n" +
            "Pragma: Sling-Connection-Type=$connectionType, Session-Id=$sessionId\r\n\r\n"
        s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        s.getOutputStream().flush()
        return s
    }

    // ---- control command plumbing (sling_cmd) ----

    @Synchronized
    private fun sendCmd(opcode: Int, data: ByteArray, msgType: Int = 0x0101): Boolean {
        val cOut = controlOut ?: return false
        val cIn = controlIn ?: return false

        log("→ ${opcodeName(opcode)} (0x${opcode.toString(16)}) seq=${seq + 1}")

        var parity = 0
        if (smode == 0x8000) {
            for (b in data) parity = parity xor (b.toInt() and 0xFF)
        }
        seq++

        val header = ByteArray(32)
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        hb.putShort(0, msgType.toShort())
        hb.putShort(2, sid.toShort())
        hb.putShort(4, opcode.toShort())
        hb.putShort(6, 0)
        hb.putShort(8, seq.toShort())
        hb.putShort(16, data.size.toShort())
        hb.putShort(18, smode.toShort())
        hb.putShort(24, parity.toShort())

        val payload = try {
            SlingboxCrypto.crypt(data, skey)
        } catch (e: Exception) {
            log("Error encrypting command 0x${opcode.toString(16)}: $e")
            return false
        }

        try {
            cOut.write(header)
            cOut.write(payload)
            cOut.flush()
        } catch (e: Exception) {
            log("Error sending command 0x${opcode.toString(16)}: $e")
            return false
        }

        if (opcode == 0x66) return true // keepalive: fire and forget, no response read here

        // Unlike the write above, this read was never guarded -- a stale/dead
        // control connection (e.g. after a long idle period) throws
        // SocketTimeoutException here, which went uncaught all the way up
        // through an IR button's bare background Thread and crashed the
        // whole process. Every other failure mode in this function already
        // returns false + logs instead of throwing; this one should too.
        return try {
            val response = readFully(cIn, 32) ?: return false
            val rb = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN)
            sid = rb.getShort(2).toInt() and 0xFFFF
            stat = rb.getShort(12).toInt() and 0xFFFF
            val dlen = rb.getShort(16).toInt() and 0xFFFF

            if (opcode == 0x68) return true // logout: ignore errors / don't consume a body

            if (stat != 0 && stat != 0x0d && stat != 0x13) {
                log("cmd: 0x${opcode.toString(16)} err: 0x${stat.toString(16)} dlen=$dlen")
            }
            if (dlen > 0) {
                val raw = recvUpTo(cIn, 512)
                dbuf = try {
                    SlingboxCrypto.decrypt(raw, skey)
                } catch (e: Exception) {
                    log("Error decrypting response to 0x${opcode.toString(16)}: $e")
                    ByteArray(0)
                }
            }
            true
        } catch (e: Exception) {
            log("Error reading response to 0x${opcode.toString(16)}: $e")
            false
        }
    }

    // ---- session setup, port of start_slingbox_session ----

    /**
     * Logs in, negotiates the dynamic session key, kicks off any other connected
     * client, selects the configured video source and sets stream parameters.
     * Returns true on success. On failure, check [stat] / the log for why.
     */
    fun startSession(
        videoSource: Int?,
        resolution: Int,
        frameRate: Int,
        videoBandwidth: Int,
        videoSmoothness: Int,
        iframeRate: Int,
        audioBitRate: Int
    ): Boolean {
        skey = SlingboxCrypto.DEFAULT_KEY
        smode = 0x2000
        sid = 0
        seq = 0

        log("Connecting control channel to $host:$port ...")
        val s = slingOpen("Control", sid)
        controlSocket = s
        controlOut = s.getOutputStream()
        controlIn = s.getInputStream()

        log("Logging in ...")
        val loginData = leInt(0) + fixedField(futf16("admin"), 32) + fixedField(futf16(password), 32) + zeros(132)
        if (!sendCmd(0x67, loginData)) {
            log("Slingbox did not respond to login request.")
            return false
        }
        if (stat != 0) {
            when (stat) {
                0x2 -> log("Login error 0x2: check the admin password.")
                0x2b -> log("Login error 0x2b: Slingbox might be bricked.")
                else -> log("Unknown login error, stat=0x${stat.toString(16)}")
            }
            return false
        }
        log("Logged in, session id=$sid")

        log("Setting up dynamic key ...")
        val dynKeyData = leInt(1) + fixedField(RAND16, 16) + zeros(36)
        if (!sendCmd(0xc6, dynKeyData)) return false
        if (stat != 0) {
            log("Error setting up dynamic key, stat=0x${stat.toString(16)}")
            return false
        }
        if (dbuf.size < 16) {
            log("Dynamic key response too short (${dbuf.size} bytes)")
            return false
        }
        val challenge = dbuf.copyOfRange(0, 16)
        skey = SlingboxCrypto.newKey(sid, RAND16, challenge)
        smode = 0x8000
        log("Dynamic key established.")

        if (!sendCmd(0x7e, leInt(1) + leInt(0))) return false
        var retries = 0
        while (stat != 0 && retries < 3) {
            retries++
            log("Box in use, kicking off other user (attempt $retries) ...")
            val kickData = fixedField(futf16("admin"), 32) + fixedField(futf16(password), 32) + zeros(8)
            sendCmd(0x93, kickData)
            Thread.sleep(1000)
            sendCmd(0x6a, leInt(1) + zeros(172))
            sendCmd(0x7e, leInt(1) + leInt(0))
        }
        if (stat != 0) {
            log("Cannot kick off other user; giving up.")
            return false
        }

        if (videoSource != null) {
            log("Selecting video source $videoSource")
            if (!sendCmd(0x85, leShort(videoSource) + leShort(0) + leShort(0) + leShort(0))) return false
            if (stat != 0) {
                log("Error selecting video source, stat=0x${stat.toString(16)}")
                return false
            }
            sendCmd(0x86, leShort(0x0400 + videoSource) + zeros(254))
        }

        sendCmd(
            0xa6,
            leShort(0x1cf) + leShort(0) + leShort(0x40) + leShort(0x10) + leShort(0x5000) +
                leShort(0x180) + leShort(1) + leShort(0x1e) + leShort(0) + leShort(0x3d) + zeros(76)
        )

        log("Setting video parameters (${SlingboxResolution.dimensionsLabel(resolution)} framerate=$frameRate bw=$videoBandwidth smoothness=$videoSmoothness iframe=$iframeRate audio=$audioBitRate)")
        val vp = leInt(0xff) + leInt(0xff) + leInt(resolution) + leInt(1) +
            leInt((iframeRate shl 24) + (frameRate shl 16) + videoBandwidth) +
            leInt(0x10001 + (videoSmoothness shl 8)) +
            leInt(3) + leInt(1) + leInt(audioBitRate) + leInt(0x4f) + leInt(1) +
            fixedField(RAND16, 16) +
            leInt(0x1012020) + leInt(1) +
            zeros(92)
        if (!sendCmd(0xb5, vp)) return false
        if (stat != 0) {
            log("Error setting video parameters, stat=0x${stat.toString(16)}")
            return false
        }

        log("Session established. sid=$sid")
        return true
    }

    // ---- IR / keycodes ----

    /**
     * Single (non-ProHD-dotted) keycode, e.g. digits/buttons.
     *
     * The reference server builds this payload with Python's
     * `pack('467x 4h', ...)` -- no explicit byte-order prefix, which makes
     * struct use *native* size/alignment rather than flat little-endian. That
     * silently inserts one extra padding byte here: offset 467 is odd, and
     * native 'h' (short) fields need 2-byte alignment, so struct pads to 468
     * before the shorts begin. Confirmed by actually running that exact
     * pack() call (476 bytes, not the naively-expected 475 from 467+8) rather
     * than assuming it -- this is exactly the kind of one-byte protocol
     * mismatch that's easy to get wrong and silent until the box rejects it.
     */
    fun sendKeycode(key: Int, rccode: Int) {
        val cmd = byteArrayOf(key.toByte(), 0, 0, (rccode and 0xFF).toByte())
        val payload = cmd + zeros(468) + leShort(rccode and 0xFF) + leShort(0) + leShort(0) + leShort(0)
        log("Sending keycode $key (rccode=$rccode)")
        sendCmd(0x87, payload)
    }

    /** ProHD-style "code.chan.subchan" keycode. */
    fun sendDottedKeycode(code: Int, chan: Int, subchan: Int) {
        val cmd = byteArrayOf(
            code.toByte(), 0, 0, 0,
            (chan and 0xFF).toByte(), ((chan shr 8) and 0xFF).toByte(),
            subchan.toByte(), 0
        )
        val payload = cmd + zeros(8)
        log("Sending dotted keycode $code.$chan.$subchan")
        sendCmd(0x89, payload)
    }

    fun keepAlive() {
        sendCmd(0x66, ByteArray(0))
        // Drain any unsolicited bytes the box may have sent, same as the reference's
        // non-blocking select+recv after a keepalive.
        try {
            val cIn = controlIn
            if (cIn != null && cIn.available() > 0) {
                cIn.read(ByteArray(8192))
            }
        } catch (_: Exception) {
        }
    }

    fun logout() {
        try {
            sendCmd(0x68, ByteArray(0))
        } catch (_: Exception) {
        }
    }

    fun closeControl() {
        try {
            controlSocket?.close()
        } catch (_: Exception) {
        }
        controlSocket = null
        controlOut = null
        controlIn = null
    }

    fun closeStream() {
        try {
            streamSocket?.close()
        } catch (_: Exception) {
        }
        streamSocket = null
        streamIn = null
    }

    // ---- A/V stream: open, read header, decrypt data packets (Solo/ProHD path) ----

    data class StreamStart(val streamHeader: ByteArray, val packetSize: Int)

    /**
     * Opens the stream connection and returns the raw ASF header bytes (everything
     * up to and including the ASF Data Object's fixed fields) plus the packet size
     * to use for subsequent reads. Does NOT apply the reference server's cosmetic
     * header patches (source-name text, Solo/SB240 audio format-tag rewrite) --
     * this intentionally preserves the box's true, original header so the real
     * codec IDs can be inspected (e.g. with ffprobe) instead of being overwritten.
     */
    fun openStream(resolution: Int, solo: Boolean): StreamStart {
        log("Opening stream channel ...")
        val s = slingOpen("Stream", sid)
        streamSocket = s
        streamIn = s.getInputStream()

        val pksize = if (solo) {
            if (resolution != 0) 3000 else 1636
        } else 3072

        val peekBuf = peek(streamIn!!, pksize)
        val dataObjIdx = indexOf(peekBuf, ASF_DATA_OBJECT_GUID)
        if (dataObjIdx < 0) {
            throw IllegalStateException("ASF Data Object GUID not found in first $pksize bytes; unexpected stream format")
        }
        val headerLen = dataObjIdx + ASF_DATA_OBJECT_FIXED_SIZE
        val streamHeader = peekBuf.copyOfRange(0, headerLen)

        // Consume exactly headerLen bytes so the stream position is at the start of
        // the first data packet (matches stream.recv(h264_header_pos) in Python).
        // Must go through readFullyStream (not readFully) so it drains the bytes we
        // already buffered in peek() first, rather than blocking on fresh socket data.
        val consumed = readFullyStream(streamIn!!, headerLen)
            ?: throw IllegalStateException("Stream closed while consuming header")
        check(consumed.size == headerLen)

        log("Stream header is $headerLen bytes, packet size $pksize bytes.")
        return StreamStart(streamHeader, pksize)
    }

    /**
     * "Slinger server" mode: instead of the box's own login/dynamic-key
     * protocol, connects to a slingbox_server.py instance's plain HTTP
     * endpoint ([host]:[port]"/slingbox" by default -- that path matches
     * its own default URLbase config value). It has already logged into
     * the real box and decrypted its stream itself, so what comes back here
     * -- after a minimal HTTP response -- is a standard, unencrypted ASF
     * header and Data Packets: the same format [parseStandardAsfPacket]
     * already handles for the untested 350/500/M1/M2 and 240 box types.
     * No control channel is opened at all in this mode (no login, no
     * keepalive, no [sendKeycode]) -- see [sendHttpIrCommand] for IR
     * instead, and [password] is unused here.
     */
    fun openHttpVideoStream(path: String = "/slingbox"): StreamStart {
        log("Connecting to Slinger server stream ...")
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 15_000)
        s.soTimeout = 20_000
        streamSocket = s
        val input = s.getInputStream()
        val request = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: keep-alive\r\n\r\n"
        s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        s.getOutputStream().flush()
        skipHttpResponseHeaders(input)
        streamIn = input

        // The real ASF Header Object is typically a few hundred bytes (582 in
        // one real capture); 8KB comfortably covers any realistic one.
        val peekSize = 8192
        val peekBuf = peek(input, peekSize)
        val dataObjIdx = indexOf(peekBuf, ASF_DATA_OBJECT_GUID)
        if (dataObjIdx < 0) {
            throw IllegalStateException("ASF Data Object GUID not found in first $peekSize bytes; unexpected stream format")
        }
        val headerLen = dataObjIdx + ASF_DATA_OBJECT_FIXED_SIZE
        val streamHeader = peekBuf.copyOfRange(0, headerLen)

        val consumed = readFullyStream(input, headerLen)
            ?: throw IllegalStateException("Stream closed while consuming header")
        check(consumed.size == headerLen)

        val packetSize = AsfHeader.minDataPacketSize(streamHeader)
        log("Stream header is $headerLen bytes, packet size $packetSize bytes.")
        return StreamStart(streamHeader, packetSize)
    }

    /** Reads and discards an HTTP response's status line + headers, up to and
     *  including the blank line that ends them, leaving the stream positioned
     *  at the first byte of the body (here, the raw ASF header). */
    private fun skipHttpResponseHeaders(input: InputStream) {
        val target = byteArrayOf(13, 10, 13, 10) // \r\n\r\n
        var matched = 0
        while (matched < target.size) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("Connection closed while reading HTTP response headers")
            matched = if (b.toByte() == target[matched]) matched + 1 else if (b.toByte() == target[0]) 1 else 0
        }
    }

    /**
     * "Slinger server" mode's IR path: a plain HTTP POST to the same server's
     * Flask-backed remote endpoint, which splits a comma-separated value
     * exactly like [value] already is (see sendIr's own doc) and sends each
     * as a real IR keypress server-side -- no client-side per-code delay
     * loop needed here unlike the direct-to-box macro path, the server
     * already paces that itself. The field name just needs to avoid a
     * handful of reserved ones the server treats specially (Digits, RCtest,
     * Restart, Channel); "key" isn't one of them.
     */
    fun sendHttpIrCommand(value: String): Boolean {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 10_000)
                s.soTimeout = 10_000
                val body = "key=" + java.net.URLEncoder.encode(value, "UTF-8")
                val bodyBytes = body.toByteArray(Charsets.US_ASCII)
                val request = "POST /Remote HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(request.toByteArray(Charsets.US_ASCII))
                out.write(bodyBytes)
                out.flush()
                // Drain the response so the request completes cleanly server-side;
                // the page body itself isn't needed.
                val input = s.getInputStream()
                val buf = ByteArray(4096)
                while (input.read(buf) >= 0) { /* discard */ }
            }
            true
        } catch (e: Exception) {
            log("Slinger server IR POST failed: $e")
            false
        }
    }

    /** Peek [n] bytes without consuming them (Socket has no MSG_PEEK in java.net,
     *  so we read-and-buffer ourselves; the caller then re-consumes via readFully). */
    private fun peek(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r <= 0) break
            off += r
        }
        // Stash it so the subsequent "consume" read sees the same bytes again.
        pendingPeek = buf.copyOf(off)
        pendingPeekOffset = 0
        return pendingPeek!!
    }

    private var pendingPeek: ByteArray? = null
    private var pendingPeekOffset = 0

    /** readnbytes() port: blocking read of exactly n bytes, serving [pendingPeek] first. */
    private fun readFullyStream(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        val pp = pendingPeek
        if (pp != null) {
            val avail = pp.size - pendingPeekOffset
            val take = minOf(avail, n)
            System.arraycopy(pp, pendingPeekOffset, out, 0, take)
            pendingPeekOffset += take
            off += take
            if (pendingPeekOffset >= pp.size) pendingPeek = null
        }
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r <= 0) return if (off == 0) null else out.copyOf(off)
            off += r
        }
        return out
    }

    /**
     * Reads and decrypts one Solo/ProHD stream packet. Returns the decrypted bytes
     * ready to append to the output, or null if the stream ended / the box signaled
     * a format restart (caller should stop and, if desired, reconnect).
     */
    fun readAndDecryptPacket(pksize: Int): ByteArray? {
        return try {
            val sIn = streamIn ?: return null
            val raw = readFullyStream(sIn, pksize) ?: return null
            if (raw.size < 16) return null // EOF mid-packet; not enough bytes for even the fixed packet header
            when (val r = processSoloMsg(raw, null)) {
                is SoloResult.Restart -> null
                is SoloResult.Data -> r.bytes
            }
        } catch (e: Exception) {
            if (!stopRequested) log("Error reading/processing stream packet: $e")
            null
        }
    }

    /**
     * One demuxed payload entry from a data packet's payload-parsing-info: which
     * ASF Stream Number it belongs to, whether it's flagged as a key frame, its
     * offset within the current Media Object (0 == start of a new frame), and the
     * (already-decrypted, where applicable) payload bytes.
     */
    data class StreamEntry(
        val streamNumber: Int,
        val keyFrame: Boolean,
        val mediaObjectOffset: Int,
        val payload: ByteArray
    )

    /**
     * Like [readAndDecryptPacket], but additionally invokes [onEntry] for every
     * payload-parsing-info entry found in the packet (not just the ones that
     * needed decryption), so a caller can demux by ASF Stream Number instead of
     * just getting back one flat blob. Returns false at end of stream / on a
     * format-restart signal, true otherwise.
     *
     * [solo] selects which wire protocol to parse: Solo/ProHD boxes packetize
     * and TEA-encrypt the stream (processSoloMsg); other models (Classic, 350,
     * 500, ...) send a standard, unencrypted ASF Data Packet per the published
     * ASF spec -- confirmed from the reference server itself, whose non-Solo
     * branch does nothing to the raw bytes at all before forwarding them. See
     * [parseStandardAsfPacket].
     */
    fun readAndDemuxPacket(pksize: Int, solo: Boolean, onEntry: (StreamEntry) -> Unit): Boolean {
        return try {
            val sIn = streamIn ?: return false
            val raw = readFullyStream(sIn, pksize) ?: return false
            if (raw.size < 16) return false
            if (solo) {
                when (processSoloMsg(raw, onEntry)) {
                    is SoloResult.Restart -> false
                    is SoloResult.Data -> true
                }
            } else {
                parseStandardAsfPacket(raw, onEntry)
                true
            }
        } catch (e: Exception) {
            if (!stopRequested) log("Error reading/processing stream packet: $e")
            false
        }
    }

    /**
     * Parses one raw ASF Data Packet -- already plaintext for non-Solo boxes,
     * per the reference server's own handling -- into its payload entries, per
     * the published ASF specification's Data Packet layout. Verified bit-for-
     * bit against ffmpeg's own asfdec_f.c (asf_get_packet()/
     * asf_read_frame_header()) rather than from memory, since getting this
     * wrong silently would be exactly the class of bug the Solo/ProHD path
     * took this whole project to track down. Still genuinely best-effort: no
     * real non-Solo capture exists to test against, so this logs the parsed
     * header fields for every packet (tag "SlingboxAsfStd") to make any future
     * mismatch quick to diagnose against a real capture.
     *
     * Handles the common case (no declared Payload Extension System fields,
     * i.e. replicated-data size exactly 8: 4-byte Media Object Size + 4-byte
     * Presentation Time) and the "compact, no timing" case (replicated-data
     * size 0 or 1). Anything else for a given payload is skipped with a
     * warning rather than guessed at.
     */
    private fun parseStandardAsfPacket(raw: ByteArray, onEntry: (StreamEntry) -> Unit) {
        var pos = 0
        fun u8(): Int {
            val v = raw[pos].toInt() and 0xFF
            pos++
            return v
        }
        fun u32(): Long {
            val v = (raw[pos].toLong() and 0xFF) or
                ((raw[pos + 1].toLong() and 0xFF) shl 8) or
                ((raw[pos + 2].toLong() and 0xFF) shl 16) or
                ((raw[pos + 3].toLong() and 0xFF) shl 24)
            pos += 4
            return v
        }
        // ffmpeg's DO_2BITS: a 2-bit length-type code selects how many bytes
        // the field occupies (0=absent/default, 1=byte, 2=word, 3=dword).
        fun readByLengthType(code: Int, default: Long): Long {
            return when (code and 3) {
                1 -> u8().toLong()
                2 -> {
                    val v = (raw[pos].toLong() and 0xFF) or ((raw[pos + 1].toLong() and 0xFF) shl 8)
                    pos += 2
                    v
                }
                3 -> u32()
                else -> default
            }
        }

        val ecFlags = u8()
        val packetFlags: Int
        if (ecFlags and 0x80 != 0) {
            // Standard error-correction data is exactly 2 bytes (normally both
            // zero); (ecFlags and 0x0F) is its declared total length, which can
            // in principle exceed 2 -- skip whatever's left after those 2.
            if (ecFlags and 0x60 == 0) {
                pos += 2
                val ecdLen = ecFlags and 0x0F
                if (ecdLen > 2) pos += (ecdLen - 2)
            }
            packetFlags = u8()
        } else {
            packetFlags = ecFlags
        }
        val packetProperty = u8()

        val packetLength = readByLengthType(packetFlags ushr 5, raw.size.toLong())
        readByLengthType(packetFlags ushr 1, 0) // sequence -- present in the stream, but unused
        val padSize = readByLengthType(packetFlags ushr 3, 0)

        if (packetLength != raw.size.toLong()) {
            log("ASF packet length field ($packetLength) doesn't match actual packet size (${raw.size}) -- parse may be misaligned")
        }

        pos += 4 // send time
        pos += 2 // duration

        val multiplePayloads = (packetFlags and 0x01) != 0
        var segSizeType = 0
        val numSegments = if (multiplePayloads) {
            segSizeType = u8()
            segSizeType and 0x3F
        } else {
            1
        }

        android.util.Log.d(
            "SlingboxAsfStd",
            "packet: ecFlags=0x%02x packetFlags=0x%02x packetProperty=0x%02x packetLength=%d padSize=%d multi=%b segments=%d"
                .format(ecFlags, packetFlags, packetProperty, packetLength, padSize, multiplePayloads, numSegments)
        )

        for (seg in 0 until numSegments) {
            if (pos >= raw.size) break
            val streamByte = u8()
            val keyFrame = (streamByte and 0x80) != 0
            val streamNumber = streamByte and 0x7F

            readByLengthType(packetProperty ushr 4, 0) // media object number -- unused
            val fragOffset = readByLengthType(packetProperty ushr 2, 0)
            val replicSize = readByLengthType(packetProperty, 0)

            when {
                replicSize >= 8 -> {
                    pos += 4 // media object size (total frame size)
                    pos += 4 // presentation time
                    val extra = (replicSize - 8).toInt()
                    if (extra > 0) pos += extra // payload extension system fields -- not needed, skipped opaque
                }
                replicSize.toInt() == 1 -> pos += 1 // packet_time_delta (compact multi-packet timing)
                replicSize.toInt() != 0 -> {
                    android.util.Log.w("SlingboxAsfStd", "segment $seg: unexpected replicated-data size $replicSize, skipping rest of packet")
                    return
                }
            }

            val fragSize = if (multiplePayloads) {
                readByLengthType(segSizeType ushr 6, 0).toInt()
            } else {
                (raw.size - padSize.toInt()) - pos
            }
            if (fragSize <= 0 || pos + fragSize > raw.size) {
                android.util.Log.w("SlingboxAsfStd", "segment $seg: invalid fragSize=$fragSize at pos=$pos (packet size ${raw.size}), stopping")
                return
            }
            android.util.Log.d(
                "SlingboxAsfStd",
                "  segment $seg: stream=$streamNumber keyFrame=$keyFrame fragOffset=$fragOffset replicSize=$replicSize fragSize=$fragSize"
            )
            onEntry(StreamEntry(streamNumber, keyFrame, fragOffset.toInt(), raw.copyOfRange(pos, pos + fragSize)))
            pos += fragSize
        }
    }

    sealed class SoloResult {
        object Restart : SoloResult()
        data class Data(val bytes: ByteArray) : SoloResult()
    }

    /**
     * Port of process_solo_msg from slingbox_server.py. Parses the ASF data packet's
     * payload-parsing-info entries to find the (sn==0x82 or sn&63==1) video/audio
     * segments, TEA-decrypts them in 16-byte-aligned chunks, and carries any
     * sub-16-byte remainder (dco/bco/bts/tbd) across calls -- exactly like the
     * Python version's nonlocal state.
     */
    private fun processSoloMsg(msgIn: ByteArray, onEntry: ((StreamEntry) -> Unit)?): SoloResult {
        val msg = msgIn.copyOf()
        val bb = ByteBuffer.wrap(msg)

        bb.order(ByteOrder.BIG_ENDIAN)
        val pmode = bb.getInt(0)
        bb.order(ByteOrder.LITTLE_ENDIAN)

        val pad = bb.getInt(5)
        var pcnt = msg[15].toInt() and 0xFF

        if ((pmode and -2) != RESTART_PMODE) {
            if (indexOf(msg, SLINGBOX_MAGIC_UTF16LE) >= 0) {
                log("Input video format changed, stream restarted.")
                return SoloResult.Restart
            }
        }

        var off = 16
        if (pmode == RESTART_PMODE) {
            off = 15
            pcnt = 1
        }

        var p = 0
        while (p < (pcnt and 63)) {
            if (off > 2983) break
            if (off + 17 > msg.size) break
            val sn = msg[off].toInt() and 0xFF
            val objoff = bb.getInt(off + 2)
            val objsiz = bb.getInt(off + 7)
            var length = bb.getShort(off + 15).toInt() and 0xFFFF
            off += 17
            if ((pmode and 1) == 0) {
                off -= 2
                length = 2970 - pad
            }
            fun emitEntry() {
                if (onEntry != null && off + length <= msg.size) {
                    // Stream Number is the LOW 6 BITS of sn here (matching the exact
                    // `sn & 63` mask the decrypt-gate condition below uses), not the
                    // low 7 bits a generic ASF payload-header flags byte would use --
                    // confirmed necessary: video continuation fragments apparently
                    // carry a 2-bit prefix (not 1-bit), so a 7-bit mask misrouted them
                    // and dropped them from the video stream, corrupting frames after
                    // the first fragment. keyFrame mirrors the exact sn==0x82 case the
                    // decrypt logic treats as "start of a video key frame" below,
                    // rather than guessing at a generic single top bit.
                    onEntry(StreamEntry(sn and 0x3F, sn == 0x82, objoff, msg.copyOfRange(off, off + length)))
                }
            }

            // Like emitEntry(), but for a gated (decrypt-worthy) entry whose last
            // `tailLen` bytes haven't actually been decrypted yet at this point --
            // they're carried forward (dco/bco) and only resolved once the *next*
            // gated entry's decrypt runs. Emitting the full `length` here (as
            // emitEntry() does) was handing the live demuxer up to 15 bytes of raw
            // TEA ciphertext per fragment, scattered through every multi-fragment
            // frame -- confirmed via logcat (demux_log.txt) to be the actual source
            // of the keyframe corruption: carry-over math itself was correct, but it
            // only ever fed the *flat* decrypted blob (dco+msg), never back into the
            // per-entry payload already handed to onEntry for that fragment.
            fun emitGatedEntry(tailLen: Int) {
                if (onEntry == null || off + length > msg.size) return
                onEntry(StreamEntry(sn and 0x3F, sn == 0x82, objoff, msg.copyOfRange(off, off + length - tailLen)))
            }

            // Emits a previously-held-back trailing remainder, now resolved by this
            // entry's own decrypt call, as a continuation (non-zero mediaObjectOffset)
            // of whichever stream it actually belongs to -- which may differ from the
            // current entry's own stream if packets interleave video and audio.
            fun emitResolvedTail(resolved: ByteArray) {
                if (onEntry == null || resolved.isEmpty() || pendingTailStreamNumber < 0) return
                onEntry(StreamEntry(pendingTailStreamNumber, false, 1, resolved))
            }

            if (sn == 0x82 || (sn and 63) == 1) {
                if (objoff == 0) tbd = objsiz and -16
                bts = (bco + length) and 15
                val cbd = (bco + length) and -16
                var resolvedTail: ByteArray? = null
                if (cbd != 0) {
                    if (bco != 0) {
                        val avail = off + (cbd - bco)
                        if (avail > msg.size) {
                            emitEntry()
                            break
                        }
                        val merged = dco + msg.copyOfRange(off, avail)
                        val buf = SlingboxCrypto.decrypt(merged, skey)
                        dco = buf.copyOfRange(0, bco)
                        resolvedTail = dco
                        System.arraycopy(buf, bco, msg, off, cbd - bco)
                    } else {
                        if (off + cbd > msg.size) {
                            emitEntry()
                            break
                        }
                        val slice = msg.copyOfRange(off, off + cbd)
                        val buf = SlingboxCrypto.decrypt(slice, skey)
                        System.arraycopy(buf, 0, msg, off, cbd)
                    }
                    bco = 0
                }
                tbd -= cbd
                if (tbd == 0) bts = 0
                // IMPORTANT: this entry must be handed to onEntry before the
                // sn==0x82/objoff==0 break below -- that break fires exactly on the
                // entry marking the start of a video key frame, and a caller doing
                // live demuxing (PlayerActivity) needs to see that entry to ever
                // start feeding video at all. Emitting it only outside this if-block
                // (after a possible break) meant it never arrived.
                resolvedTail?.let { emitResolvedTail(it) }
                emitGatedEntry(bts)
                pendingTailStreamNumber = sn and 0x3F
                if (sn == 0x82 && objoff == 0) break
            } else {
                emitEntry()
            }
            off += length
            p++
        }

        var result = dco + msg
        bco = bts
        dco = if (bco > 0) {
            val tail = result.copyOfRange(result.size - bts, result.size)
            result = result.copyOfRange(0, result.size - bts)
            tail
        } else {
            ByteArray(0)
        }
        return SoloResult.Data(result)
    }
}

// 0x82000018 exceeds Int range as a bare positive literal; go through UInt to get
// the exact bit pattern unambiguously.
private val RESTART_PMODE = 0x82000018u.toInt()
