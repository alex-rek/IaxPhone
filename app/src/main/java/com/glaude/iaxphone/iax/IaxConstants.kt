package com.glaude.iaxphone.iax

/** Константы протокола IAX2 (RFC 5456). */
object FrameType {
    const val DTMF_END = 0x01
    const val VOICE = 0x02
    const val VIDEO = 0x03
    const val CONTROL = 0x04
    const val NULL = 0x05
    const val IAX = 0x06
    const val TEXT = 0x07
    const val IMAGE = 0x08
    const val HTML = 0x09
    const val CNG = 0x0a
    const val DTMF_BEGIN = 0x0c
}

object IaxCmd {
    const val NEW = 1
    const val PING = 2
    const val PONG = 3
    const val ACK = 4
    const val HANGUP = 5
    const val REJECT = 6
    const val ACCEPT = 7
    const val AUTHREQ = 8
    const val AUTHREP = 9
    const val INVAL = 10
    const val LAGRQ = 11
    const val LAGRP = 12
    const val REGREQ = 13
    const val REGAUTH = 14
    const val REGACK = 15
    const val REGREJ = 16
    const val REGREL = 17
    const val VNAK = 18
    const val TXREQ = 22
    const val TXCNT = 23
    const val TXACC = 24
    const val TXREADY = 25
    const val TXREL = 26
    const val TXREJ = 27
    const val QUELCH = 28
    const val UNQUELCH = 29
    const val POKE = 30
    const val MWI = 32
    const val UNSUPPORT = 33
    const val CALLTOKEN = 40
}

object ControlCmd {
    const val HANGUP = 1
    const val RING = 2
    const val RINGING = 3
    const val ANSWER = 4
    const val BUSY = 5
    const val CONGESTION = 8
    const val PROGRESS = 14
    const val PROCEEDING = 15
    const val HOLD = 16
    const val UNHOLD = 17
}

/** Типы информационных элементов (IE). */
object Ie {
    const val CALLED_NUMBER = 0x01
    const val CALLING_NUMBER = 0x02
    const val CALLING_ANI = 0x03
    const val CALLING_NAME = 0x04
    const val CALLED_CONTEXT = 0x05
    const val USERNAME = 0x06
    const val PASSWORD = 0x07
    const val CAPABILITY = 0x08
    const val FORMAT = 0x09
    const val LANGUAGE = 0x0a
    const val VERSION = 0x0b
    const val DNID = 0x0d
    const val AUTHMETHODS = 0x0e
    const val CHALLENGE = 0x0f
    const val MD5_RESULT = 0x10
    const val APPARENT_ADDR = 0x12
    const val REFRESH = 0x13
    const val CALLNO = 0x15
    const val CAUSE = 0x16
    const val IAX_UNKNOWN = 0x17
    const val MSGCOUNT = 0x18
    const val DATETIME = 0x1f
    const val CAUSECODE = 0x2a
    const val CALLTOKEN = 0x36
    const val CAPABILITY2 = 0x37
    const val FORMAT2 = 0x38
}

object AuthMethod {
    const val PLAINTEXT = 1
    const val MD5 = 2
    const val RSA = 4
}

/** Битовые маски кодеков (поле FORMAT/CAPABILITY). */
object Codec {
    const val GSM = 0x2L
    const val ULAW = 0x4L
    const val ALAW = 0x8L
    const val SLINEAR = 0x40L

    fun name(format: Long): String = when (format) {
        ULAW -> "G.711 µ-law"
        ALAW -> "G.711 A-law"
        GSM -> "GSM"
        SLINEAR -> "SLIN"
        else -> "0x" + format.toString(16)
    }
}

/** Коды причин отбоя (Q.931). */
object CauseCode {
    const val NORMAL_CLEARING = 16
    const val USER_BUSY = 17
    const val CALL_REJECTED = 21
}

const val IAX_DEFAULT_PORT = 4569
