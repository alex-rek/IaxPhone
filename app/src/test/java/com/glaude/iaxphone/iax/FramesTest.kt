package com.glaude.iaxphone.iax

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class FramesTest {

    @Test
    fun fullFrameRoundTrip() {
        val payload = IeBuilder().str(Ie.USERNAME, "100").u16(Ie.REFRESH, 60).u32(Ie.FORMAT, Codec.ULAW).empty(Ie.CALLTOKEN).build()
        val f = FullFrame(1234, 4321, false, 123456, 7, 9, FrameType.IAX, IaxCmd.REGREQ.toLong(), payload)
        val bytes = f.encode()
        assertEquals(0x80 or (1234 shr 8), bytes[0].toInt() and 0xff)
        val p = IaxParser.parse(bytes) as FullFrame
        assertEquals(1234, p.srcCall); assertEquals(4321, p.dstCall); assertEquals(123456L, p.timestamp)
        assertEquals(7, p.oseq); assertEquals(9, p.iseq); assertEquals(IaxCmd.REGREQ.toLong(), p.subclass)
        assertEquals("100", p.ies.str(Ie.USERNAME)); assertEquals(60, p.ies.u16(Ie.REFRESH))
        assertEquals(Codec.ULAW, p.ies.format()); assertTrue(p.ies.has(Ie.CALLTOKEN))

        val r = IaxParser.parse(f.encode(retransmit = true)) as FullFrame
        assertTrue(r.retransmitted); assertEquals(4321, r.dstCall)
    }

    @Test
    fun compressedSubclass() {
        assertEquals(0x04, FullFrame.encodeSubclass(Codec.ULAW))
        assertEquals(0x80 or 12, FullFrame.encodeSubclass(1L shl 12))
        assertEquals(1L shl 12, FullFrame.decodeSubclass(0x80 or 12))
    }

    @Test
    fun format2TakesPrecedence() {
        val ies = InfoElements.parse(IeBuilder().u32(Ie.FORMAT, Codec.ALAW).u64v(Ie.FORMAT2, Codec.ULAW).build())
        assertEquals(Codec.ULAW, ies.format())
    }

    @Test
    fun miniFrameRoundTrip() {
        val m = MiniFrame(555, 0xfffe, byteArrayOf(1, 2, 3))
        val p = IaxParser.parse(m.encode()) as MiniFrame
        assertEquals(555, p.srcCall); assertEquals(0xfffe, p.timestamp); assertArrayEquals(byteArrayOf(1, 2, 3), p.payload)
    }

    @Test
    fun g711RoundTrip() {
        val pcm = ShortArray(160) { (12000 * sin(it * 2 * Math.PI * 440 / 8000)).toInt().toShort() }
        for (codec in listOf(Codec.ULAW, Codec.ALAW)) {
            val back = G711.decode(codec, G711.encode(codec, pcm))
            for (i in pcm.indices) {
                val err = abs(back[i] - pcm[i])
                assertTrue("${Codec.name(codec)} err=$err at $i", err <= abs(pcm[i].toInt()) / 16 + 16)
            }
        }
        // Известные значения: тишина
        assertEquals(0xff, G711.linearToUlaw(0).toInt() and 0xff)
        assertEquals(0xd5, G711.linearToAlaw(0).toInt() and 0xff)
    }

    @Test
    fun md5AndSeq() {
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", IaxClient.md5Hex("secret"))
        assertTrue(IaxClient.seqBefore(255, 0))
        assertTrue(IaxClient.seqBefore(3, 5))
        assertTrue(!IaxClient.seqBefore(5, 5))
    }

    @Test
    fun msgCountSplitsNewAndOld() {
        // Пример с реального сервера: 0x1111 — 17 новых и 17 прослушанных, 0x1100 — 0 новых.
        assertEquals(17 to 17, IaxClient.parseMsgCount(0x1111))
        assertEquals(0 to 17, IaxClient.parseMsgCount(0x1100))
        assertEquals(3 to 0, IaxClient.parseMsgCount(0x0003))
    }
}
