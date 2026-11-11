package com.glaude.iaxphone.iax

import com.glaude.iaxphone.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class GsmTest {

    /** Смесь тонов с медленно меняющейся амплитудой — грубое подобие голоса. */
    private fun signal(n: Int) = ShortArray(n) {
        val t = it / 8000.0
        val env = 0.6 + 0.4 * sin(2 * PI * 3 * t)
        (env * (5000 * sin(2 * PI * 220 * t) + 2500 * sin(2 * PI * 660 * t) + 1200 * sin(2 * PI * 1450 * t))).toInt().toShort()
    }

    @Test
    fun frameFormat() {
        val enc = GsmEncoder()
        val frame = enc.encode(signal(160))
        assertEquals(33, frame.size)
        assertEquals(0xD, (frame[0].toInt() shr 4) and 0xF)
    }

    @Test
    fun roundTripKeepsSignal() {
        val frames = 50
        val input = signal(frames * 160)
        val enc = GsmEncoder()
        val dec = GsmDecoder()
        val out = ShortArray(input.size)
        for (i in 0 until frames) dec.decode(enc.encode(input, i * 160)).copyInto(out, i * 160)

        // Сравниваем вторую половину (после установления фильтров) с подбором небольшой задержки.
        var best = 0.0
        var bestEnergy = 0.0
        for (lag in 0..20) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (k in input.size / 2 until input.size - lag) {
                val x = input[k].toDouble(); val y = out[k + lag].toDouble()
                xy += x * y; xx += x * x; yy += y * y
            }
            val c = xy / sqrt(xx * yy)
            if (c > best) { best = c; bestEnergy = yy / xx }
        }
        println("GSM: корреляция $best, отношение энергий $bestEnergy")
        assertTrue("корреляция $best", best > 0.8)
        assertTrue("энергия $bestEnergy", bestEnergy in 0.3..3.0)
    }

    @Test
    fun silenceStaysQuiet() {
        val enc = GsmEncoder()
        val dec = GsmDecoder()
        var peak = 0
        repeat(10) {
            val pcm = dec.decode(enc.encode(ShortArray(160)))
            peak = maxOf(peak, pcm.maxOf { kotlin.math.abs(it.toInt()) })
        }
        assertTrue("пик тишины $peak", peak < 64)
    }

    @Test
    fun decoderSurvivesGarbage() {
        val dec = GsmDecoder()
        val rnd = java.util.Random(1)
        repeat(200) {
            val data = ByteArray(33).also { rnd.nextBytes(it) }
            assertEquals(160, dec.decode(data).size)
        }
    }

    @Test
    fun versionCompare() {
        assertTrue(Updater.isNewer("0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("1.0", "0.9.9"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.0"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.9", "0.2.0"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0-test"))
    }

    @Test
    fun gainSaturatesAndKeepsUnity() {
        val pcm = shortArrayOf(1000, -1000, 30000, -30000, 0)
        assertTrue(com.glaude.iaxphone.AudioPrefs.applyGain(pcm, 1f) === pcm)
        val louder = com.glaude.iaxphone.AudioPrefs.applyGain(pcm, com.glaude.iaxphone.AudioPrefs.dbToFactor(6))
        assertEquals(1995, louder[0].toInt())
        assertEquals(Short.MAX_VALUE, louder[2])
        assertEquals(Short.MIN_VALUE, louder[3])
    }
}
