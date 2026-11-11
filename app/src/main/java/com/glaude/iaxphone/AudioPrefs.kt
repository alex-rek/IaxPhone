package com.glaude.iaxphone

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Настройки звука («Настройки → Аудио»). Значения кэшируются в памяти, чтобы звуковые потоки
 * читали их без обращения к SharedPreferences; громкость меняется прямо во время разговора.
 */
object AudioPrefs {
    private const val PREFS = "audio"
    const val GAIN_MIN_DB = -6
    const val GAIN_MAX_DB = 12

    @Volatile var keyVibrate = true; private set
    @Volatile var keySound = true; private set
    @Volatile var echoCancel = true; private set
    @Volatile var noiseSuppress = true; private set
    @Volatile var autoGain = true; private set
    @Volatile var proximity = true; private set
    @Volatile var speakerDb = 0; private set
    @Volatile var micDb = 0; private set

    /** Множители громкости (1.0 — без изменений). */
    @Volatile var speakerGain = 1f; private set
    @Volatile var micGain = 1f; private set

    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        keyVibrate = p.getBoolean("keyVibrate", true)
        keySound = p.getBoolean("keySound", true)
        echoCancel = p.getBoolean("echoCancel", true)
        noiseSuppress = p.getBoolean("noiseSuppress", true)
        autoGain = p.getBoolean("autoGain", true)
        proximity = p.getBoolean("proximity", true)
        setGains(p.getInt("speakerDb", 0), p.getInt("micDb", 0))
        loaded = true
    }

    fun update(
        ctx: Context,
        keyVibrate: Boolean = this.keyVibrate,
        keySound: Boolean = this.keySound,
        echoCancel: Boolean = this.echoCancel,
        noiseSuppress: Boolean = this.noiseSuppress,
        autoGain: Boolean = this.autoGain,
        proximity: Boolean = this.proximity,
        speakerDb: Int = this.speakerDb,
        micDb: Int = this.micDb,
    ) {
        this.keyVibrate = keyVibrate
        this.keySound = keySound
        this.echoCancel = echoCancel
        this.noiseSuppress = noiseSuppress
        this.autoGain = autoGain
        this.proximity = proximity
        setGains(speakerDb, micDb)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("keyVibrate", keyVibrate).putBoolean("keySound", keySound)
            .putBoolean("echoCancel", echoCancel).putBoolean("noiseSuppress", noiseSuppress)
            .putBoolean("autoGain", autoGain).putBoolean("proximity", proximity)
            .putInt("speakerDb", this.speakerDb).putInt("micDb", this.micDb)
            .apply()
    }

    private fun setGains(spk: Int, mic: Int) {
        speakerDb = spk.coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)
        micDb = mic.coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)
        speakerGain = dbToFactor(speakerDb)
        micGain = dbToFactor(micDb)
    }

    fun dbToFactor(db: Int): Float = 10f.pow(db / 20f)

    /** Усиливает PCM с насыщением; при множителе 1.0 возвращает исходный массив. */
    fun applyGain(pcm: ShortArray, factor: Float): ShortArray {
        if (factor == 1f) return pcm
        return ShortArray(pcm.size) {
            (pcm[it] * factor).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
