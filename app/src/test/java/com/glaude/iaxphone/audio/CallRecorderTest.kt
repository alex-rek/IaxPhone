package com.glaude.iaxphone.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CallRecorderTest {

    @Test
    fun writesValidWavWithMixedVoices() {
        val f = File.createTempFile("rec", ".wav")
        try {
            val r = CallRecorder(f)
            r.onRx(ShortArray(160) { 1000 })
            r.onTx(ShortArray(160) { 200 })        // смешивается с собеседником
            r.onTx(ShortArray(160) { 30000 })      // собеседника нет — только микрофон
            r.onRx(ShortArray(160) { 30000 })
            r.onTx(ShortArray(160) { 30000 })      // переполнение — насыщение
            r.close()

            val bytes = f.readBytes()
            assertEquals(44 + 3 * 320, bytes.size)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(36 + 960, bb.getInt(4))
            assertEquals(8000, bb.getInt(24))
            assertEquals(16, bb.getShort(34).toInt())
            assertEquals(960, bb.getInt(40))
            assertEquals(1200, bb.getShort(44).toInt())
            assertEquals(30000, bb.getShort(44 + 320).toInt())
            assertEquals(Short.MAX_VALUE.toInt(), bb.getShort(44 + 640).toInt())
        } finally {
            f.delete()
        }
    }
}
