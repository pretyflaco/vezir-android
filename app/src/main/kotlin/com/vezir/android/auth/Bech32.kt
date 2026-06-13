package com.vezir.android.auth

/**
 * Minimal bech32 decoder, just enough to convert a NIP-19 `npub1…` to the
 * 64-char hex public key the vezir server expects.  Amber usually returns
 * hex already, but some signers return the npub form, so we handle both.
 *
 * Implements BIP-173 bech32 (not bech32m) decode + the 5-bit→8-bit
 * regrouping NIP-19 uses for the `npub` TLV-less simple case (32-byte key).
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** Convert an `npub1…` to 64-char lowercase hex, or null on failure. */
    fun npubToHex(npub: String): String? {
        val decoded = decode(npub) ?: return null
        val (hrp, data) = decoded
        if (hrp != "npub") return null
        val bytes = convertBits(data, 5, 8, false) ?: return null
        if (bytes.size != 32) return null
        val sb = StringBuilder(64)
        val digits = "0123456789abcdef"
        for (b in bytes) {
            val v = b and 0xFF
            sb.append(digits[v ushr 4])
            sb.append(digits[v and 0x0F])
        }
        return sb.toString()
    }

    private fun decode(bech: String): Pair<String, IntArray>? {
        if (bech.any { it.code < 33 || it.code > 126 }) return null
        val lower = bech.lowercase()
        val upper = bech.uppercase()
        if (bech != lower && bech != upper) return null
        val s = lower
        val pos = s.lastIndexOf('1')
        if (pos < 1 || pos + 7 > s.length) return null
        val hrp = s.substring(0, pos)
        val dataPart = s.substring(pos + 1)
        val data = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val idx = CHARSET.indexOf(dataPart[i])
            if (idx == -1) return null
            data[i] = idx
        }
        if (!verifyChecksum(hrp, data)) return null
        // strip the 6-char checksum
        return hrp to data.copyOfRange(0, data.size - 6)
    }

    private fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (value in data) {
            if (value < 0 || (value ushr from) != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        val values = hrpExpand(hrp) + data
        return polymod(values) == 1
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if (((b ushr i) and 1) != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }
}
