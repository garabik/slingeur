package app.retro.slingeur

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port of the TEA-based cipher and dynamic-key derivation from slingbox_server.py
 * (encipher/decipher/Crypt/Decrypt/new_key). Every operation here must match the
 * Python reference bit-for-bit -- this is session-critical, not cosmetic.
 */
object SlingboxCrypto {

    // Python: delta = 0x61C88647 ; sum.value -= delta each round (== adding standard TEA delta)
    private const val ENC_DELTA = 0x61C88647

    // Python: sum = 0xc6ef3720 ; delta = 0x9e3779b9
    // NOTE: these bare values exceed 0x7FFFFFFF, so we go through a UInt literal
    // (unambiguously valid for the full 32-bit range) and reinterpret the bit
    // pattern as Int, rather than relying on bare hex-Int-literal range rules.
    private val DEC_SUM0 = 0xC6EF3720u.toInt()
    private val DEC_DELTA = 0x9E3779B9u.toInt()

    private fun encipher(v0in: Int, v1in: Int, k: IntArray): Pair<Int, Int> {
        var y = v0in
        var z = v1in
        var sum = 0
        repeat(32) {
            sum -= ENC_DELTA
            y += (((z shl 4) + k[0]) xor (z + sum)) xor ((z ushr 5) + k[1])
            z += (((y shl 4) + k[2]) xor (y + sum)) xor ((y ushr 5) + k[3])
        }
        return Pair(y, z)
    }

    private fun decipher(v0in: Int, v1in: Int, k: IntArray): Pair<Int, Int> {
        var y = v0in
        var z = v1in
        var sum = DEC_SUM0
        repeat(32) {
            z -= (((y shl 4) + k[2]) xor (y + sum)) xor ((y ushr 5) + k[3])
            y -= (((z shl 4) + k[0]) xor (z + sum)) xor ((z ushr 5) + k[1])
            sum -= DEC_DELTA
        }
        return Pair(y, z)
    }

    /**
     * Reads a little-endian 32-bit int starting at [offset], zero-extending if fewer
     * than 4 bytes remain. Mirrors Python's `int.from_bytes(data[i:i+4], 'little')`
     * which happily accepts a short trailing slice.
     */
    private fun readIntLEPartial(data: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0 until 4) {
            val pos = offset + i
            val b = if (pos < data.size) (data[pos].toInt() and 0xFF) else 0
            v = v or (b shl (8 * i))
        }
        return v
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /**
     * Port of Python Crypt(). NOTE: deliberately replicates the Python quirk where
     * a data length that isn't a multiple of 4 bytes still produces output -- the
     * last partial 4-byte chunk is zero-extended, and the whole buffer is always
     * emitted in full 8-byte (two 4-byte int) blocks. The number of 4-byte chunks
     * (ceil(len/4)) must be even, or this throws, exactly like the Python version
     * would raise IndexError.
     */
    fun crypt(data: ByteArray, key: IntArray): ByteArray {
        val n = (data.size + 3) / 4
        require(n % 2 == 0) { "Crypt: odd number of 4-byte chunks for len=${data.size}" }
        val out = ByteArray(n / 2 * 8)
        var oi = 0
        var i = 0
        while (i < n) {
            val v0 = readIntLEPartial(data, i * 4)
            val v1 = readIntLEPartial(data, (i + 1) * 4)
            val (c0, c1) = encipher(v0, v1, key)
            writeIntLE(out, oi, c0)
            writeIntLE(out, oi + 4, c1)
            oi += 8
            i += 2
        }
        return out
    }

    /** Port of Python Decrypt(). Same chunking rules as [crypt]. */
    fun decrypt(data: ByteArray, key: IntArray): ByteArray {
        val n = (data.size + 3) / 4
        require(n % 2 == 0) { "Decrypt: odd number of 4-byte chunks for len=${data.size}" }
        val out = ByteArray(n / 2 * 8)
        var oi = 0
        var i = 0
        while (i < n) {
            val v0 = readIntLEPartial(data, i * 4)
            val v1 = readIntLEPartial(data, (i + 1) * 4)
            val (c0, c1) = decipher(v0, v1, key)
            writeIntLE(out, oi, c0)
            writeIntLE(out, oi + 4, c1)
            oi += 8
            i += 2
        }
        return out
    }

    // ---- Dynamic session key derivation (new_key / dynk in the Python source) ----

    /** bytes2bits: per byte, bit k (0=LSB..7=MSB) -> output[byteIdx*8+k]. */
    private fun bytesToBits(buf: ByteArray): IntArray {
        val bits = IntArray(buf.size * 8)
        for (bi in buf.indices) {
            val v = buf[bi].toInt() and 0xFF
            for (k in 0 until 8) {
                bits[bi * 8 + k] = (v ushr k) and 1
            }
        }
        return bits
    }

    /** bits2bytes: byte = sum(bit[k] << k for k in 0..7). Algebraically equivalent
     *  to the Python double-reversal trick, derived and verified by hand. */
    private fun bitsToBytes(bits: IntArray): ByteArray {
        val out = ByteArray(bits.size / 8)
        for (i in out.indices) {
            var n = 0
            for (k in 0 until 8) {
                n = n or (bits[i * 8 + k] shl k)
            }
            out[i] = n.toByte()
        }
        return out
    }

    /** xor(b1, b2) from Python: elementwise xor over b2's length, b1's tail unchanged. */
    private fun xorBits(b1: IntArray, b2: IntArray): IntArray {
        val out = IntArray(b1.size)
        for (i in b1.indices) {
            out[i] = if (i < b2.size) (b1[i] xor b2[i]) else b1[i]
        }
        return out
    }

    /**
     * rotateLeft replicates Python's `t[r:] + t[0:r]` slice semantics for any r with
     * |r| < t.size, including negative r (Python's negative-index slicing), which is
     * algebraically a left-rotate by (r mod n).
     */
    private fun rotateLeft(bits: IntArray, r: Int): IntArray {
        val n = bits.size
        val idx = ((r % n) + n) % n
        val out = IntArray(n)
        System.arraycopy(bits, idx, out, 0, n - idx)
        System.arraycopy(bits, 0, out, n - idx, idx)
        return out
    }

    private fun dynk(seed: ByteArray, sid: Int, a: Int, b: Int): IntArray {
        var t = bytesToBits(seed)
        val s = IntArray(16) { (sid ushr it) and 1 }
        val td = intArrayOf(a, b)
        var v = IntArray(0)
        for (i in 1..16) {
            val bitSel = (sid ushr (i - 1)) and 1
            val r = i * td[bitSel]
            val z = rotateLeft(t, r)
            t = xorBits(z, s)
            v = xorBits(t, v)
        }
        return v
    }

    /**
     * Port of Python new_key(sid, rand, challenge). [rand] is the 16-byte fixed
     * "random" challenge we send in the 0xc6 command; [challenge] is dbuf[0:16]
     * from the box's response. Returns the 4-word (128-bit) session key.
     */
    fun newKey(sid: Int, rand: ByteArray, challenge: ByteArray): IntArray {
        val k1 = dynk(rand, sid, 2, 3)
        val k2 = dynk(challenge, sid, -1, -4)
        val myKeyBits = xorBits(k1, k2)
        val keyBytes = bitsToBytes(myKeyBits)
        val bb = ByteBuffer.wrap(keyBytes).order(ByteOrder.LITTLE_ENDIAN)
        return intArrayOf(bb.getInt(0), bb.getInt(4), bb.getInt(8), bb.getInt(12))
    }

    /** The fixed, well-known default admin key used before the dynamic key is set up. */
    val DEFAULT_KEY = intArrayOf(
        0xBCDEAAAAu.toInt(),
        0x87FBBBBAu.toInt(),
        0x7CCCCFFAu.toInt(),
        0xDDDDAABCu.toInt()
    )
}
