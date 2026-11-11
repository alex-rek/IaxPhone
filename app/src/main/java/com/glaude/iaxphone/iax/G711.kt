package com.glaude.iaxphone.iax

/** Кодек G.711 (µ-law и A-law), 8 кГц, 16-битный PCM. */
object G711 {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    private val ULAW_DECODE = ShortArray(256) { decodeUlawSample(it) }
    private val ALAW_DECODE = ShortArray(256) { decodeAlawSample(it) }

    fun encode(format: Long, pcm: ShortArray, count: Int = pcm.size): ByteArray = when (format) {
        Codec.ULAW -> ByteArray(count) { linearToUlaw(pcm[it].toInt()) }
        Codec.ALAW -> ByteArray(count) { linearToAlaw(pcm[it].toInt()) }
        else -> throw IllegalArgumentException("Unsupported codec ${Codec.name(format)}")
    }

    fun decode(format: Long, data: ByteArray): ShortArray = when (format) {
        Codec.ULAW -> ShortArray(data.size) { ULAW_DECODE[data[it].toInt() and 0xff] }
        Codec.ALAW -> ShortArray(data.size) { ALAW_DECODE[data[it].toInt() and 0xff] }
        else -> throw IllegalArgumentException("Unsupported codec ${Codec.name(format)}")
    }

    fun isSupported(format: Long) = format == Codec.ULAW || format == Codec.ALAW

    fun linearToUlaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0f
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun decodeUlawSample(b: Int): Short {
        val u = b.inv() and 0xff
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0f
        val s = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (sign != 0) -s else s).toShort()
    }

    fun linearToAlaw(sample: Int): Byte {
        var s = sample
        val sign = if (s >= 0) 0x80 else { s = -s - 1; 0x00 }
        if (s > 32767) s = 32767
        val out = if (s < 256) {
            s shr 4
        } else {
            var exponent = 7
            var mask = 0x4000
            while (exponent > 1 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
            (exponent shl 4) or ((s shr (exponent + 3)) and 0x0f)
        }
        return ((sign or out) xor 0x55).toByte()
    }

    private fun decodeAlawSample(b: Int): Short {
        val a = b xor 0x55
        val sign = a and 0x80
        val exponent = (a shr 4) and 0x07
        var s = (a and 0x0f) shl 4
        s = if (exponent == 0) s + 8 else (s + 0x108) shl (exponent - 1)
        return (if (sign != 0) s else -s).toShort()
    }
}
