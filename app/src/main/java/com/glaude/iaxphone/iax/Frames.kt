package com.glaude.iaxphone.iax

/** Кадр IAX2: полный (full) или мини (mini, только голос). */
sealed class IaxPacket

class FullFrame(
    val srcCall: Int,
    val dstCall: Int,
    val retransmitted: Boolean,
    val timestamp: Long,
    val oseq: Int,
    val iseq: Int,
    val type: Int,
    val subclass: Long,
    val payload: ByteArray = ByteArray(0),
) : IaxPacket() {

    val ies: InfoElements by lazy { InfoElements.parse(payload) }

    fun isIax(cmd: Int) = type == FrameType.IAX && subclass == cmd.toLong()

    fun encode(retransmit: Boolean = retransmitted): ByteArray {
        val b = ByteArray(12 + payload.size)
        val src = 0x8000 or (srcCall and 0x7fff)
        val dst = (if (retransmit) 0x8000 else 0) or (dstCall and 0x7fff)
        b[0] = (src shr 8).toByte(); b[1] = src.toByte()
        b[2] = (dst shr 8).toByte(); b[3] = dst.toByte()
        writeU32(b, 4, timestamp)
        b[8] = oseq.toByte(); b[9] = iseq.toByte()
        b[10] = type.toByte()
        b[11] = encodeSubclass(subclass).toByte()
        System.arraycopy(payload, 0, b, 12, payload.size)
        return b
    }

    fun describe(): String {
        val name = when (type) {
            FrameType.IAX -> "IAX/" + iaxName(subclass.toInt())
            FrameType.CONTROL -> "CONTROL/" + controlName(subclass.toInt())
            FrameType.VOICE -> "VOICE/" + Codec.name(subclass)
            FrameType.DTMF_END -> "DTMF/" + subclass.toInt().toChar()
            else -> "TYPE$type/$subclass"
        }
        val ie = if (type == FrameType.IAX && payload.isNotEmpty()) " " + ies else ""
        return "$name src=$srcCall dst=$dstCall ts=$timestamp o=$oseq i=$iseq$ie"
    }

    companion object {
        fun encodeSubclass(v: Long): Int {
            if (v < 0x80) return v.toInt()
            require(v and (v - 1) == 0L) { "Subclass $v is not power of two" }
            return 0x80 or java.lang.Long.numberOfTrailingZeros(v)
        }

        fun decodeSubclass(b: Int): Long =
            if (b and 0x80 != 0) (if (b == 0xff) -1L else 1L shl (b and 0x7f)) else b.toLong()

        fun iaxName(c: Int) = when (c) {
            IaxCmd.NEW -> "NEW"; IaxCmd.PING -> "PING"; IaxCmd.PONG -> "PONG"; IaxCmd.ACK -> "ACK"
            IaxCmd.HANGUP -> "HANGUP"; IaxCmd.REJECT -> "REJECT"; IaxCmd.ACCEPT -> "ACCEPT"
            IaxCmd.AUTHREQ -> "AUTHREQ"; IaxCmd.AUTHREP -> "AUTHREP"; IaxCmd.INVAL -> "INVAL"
            IaxCmd.LAGRQ -> "LAGRQ"; IaxCmd.LAGRP -> "LAGRP"; IaxCmd.REGREQ -> "REGREQ"
            IaxCmd.REGAUTH -> "REGAUTH"; IaxCmd.REGACK -> "REGACK"; IaxCmd.REGREJ -> "REGREJ"
            IaxCmd.REGREL -> "REGREL"; IaxCmd.VNAK -> "VNAK"; IaxCmd.POKE -> "POKE"
            IaxCmd.CALLTOKEN -> "CALLTOKEN"; IaxCmd.TXREQ -> "TXREQ"; IaxCmd.TXREJ -> "TXREJ"
            IaxCmd.QUELCH -> "QUELCH"; IaxCmd.UNQUELCH -> "UNQUELCH"; IaxCmd.MWI -> "MWI"
            IaxCmd.UNSUPPORT -> "UNSUPPORT"
            else -> c.toString()
        }

        fun controlName(c: Int) = when (c) {
            ControlCmd.HANGUP -> "HANGUP"; ControlCmd.RING -> "RING"; ControlCmd.RINGING -> "RINGING"
            ControlCmd.ANSWER -> "ANSWER"; ControlCmd.BUSY -> "BUSY"; ControlCmd.CONGESTION -> "CONGESTION"
            ControlCmd.PROGRESS -> "PROGRESS"; ControlCmd.PROCEEDING -> "PROCEEDING"
            ControlCmd.HOLD -> "HOLD"; ControlCmd.UNHOLD -> "UNHOLD"
            else -> c.toString()
        }
    }
}

/** Мини-кадр: 4 байта заголовка (номер вызова + младшие 16 бит метки времени) и голос. */
class MiniFrame(val srcCall: Int, val timestamp: Int, val payload: ByteArray) : IaxPacket() {
    fun encode(): ByteArray {
        val b = ByteArray(4 + payload.size)
        b[0] = ((srcCall shr 8) and 0x7f).toByte(); b[1] = srcCall.toByte()
        b[2] = (timestamp shr 8).toByte(); b[3] = timestamp.toByte()
        System.arraycopy(payload, 0, b, 4, payload.size)
        return b
    }
}

object IaxParser {
    /** Возвращает null для мусора и мета-кадров (видео/транк), которые клиент не поддерживает. */
    fun parse(data: ByteArray, len: Int = data.size): IaxPacket? {
        if (len < 4) return null
        val w0 = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        if (w0 and 0x8000 != 0) {
            if (len < 12) return null
            val w1 = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
            return FullFrame(
                srcCall = w0 and 0x7fff,
                dstCall = w1 and 0x7fff,
                retransmitted = w1 and 0x8000 != 0,
                timestamp = readU32(data, 4),
                oseq = data[8].toInt() and 0xff,
                iseq = data[9].toInt() and 0xff,
                type = data[10].toInt() and 0xff,
                subclass = FullFrame.decodeSubclass(data[11].toInt() and 0xff),
                payload = data.copyOfRange(12, len),
            )
        }
        if (w0 == 0) return null // мета-кадр
        val ts = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        return MiniFrame(w0, ts, data.copyOfRange(4, len))
    }
}
