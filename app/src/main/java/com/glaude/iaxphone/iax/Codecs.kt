package com.glaude.iaxphone.iax

/** Кодек голоса одного направления/вызова (у GSM есть состояние между кадрами). */
interface VoiceCodec {
    /** 160 отсчётов (20 мс) → полезная нагрузка кадра. */
    fun encode(pcm: ShortArray): ByteArray
    /** Полезная нагрузка (один или несколько кадров) → PCM. */
    fun decode(data: ByteArray): ShortArray
}

object Codecs {
    /** Поддерживаемые кодеки в порядке предпочтения. */
    val SUPPORTED: List<Long> = listOf(Codec.ULAW, Codec.ALAW, Codec.GSM)

    fun isSupported(format: Long) = format in SUPPORTED

    fun create(format: Long): VoiceCodec = when (format) {
        Codec.ULAW, Codec.ALAW -> G711Codec(format)
        Codec.GSM -> GsmCodec()
        else -> throw IllegalArgumentException("Unsupported codec ${Codec.name(format)}")
    }
}

private class G711Codec(private val format: Long) : VoiceCodec {
    override fun encode(pcm: ShortArray) = G711.encode(format, pcm)
    override fun decode(data: ByteArray) = G711.decode(format, data)
}

private class GsmCodec : VoiceCodec {
    private val encoder = GsmEncoder()
    private val decoder = GsmDecoder()

    override fun encode(pcm: ShortArray) = encoder.encode(pcm)

    override fun decode(data: ByteArray): ShortArray {
        val frames = data.size / Gsm.FRAME_BYTES
        val out = ShortArray(frames * Gsm.FRAME_SAMPLES)
        for (i in 0 until frames) {
            decoder.decode(data, i * Gsm.FRAME_BYTES).copyInto(out, i * Gsm.FRAME_SAMPLES)
        }
        return out
    }
}
