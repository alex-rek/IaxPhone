package com.glaude.iaxphone

import android.graphics.Color
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glaude.iaxphone.databinding.ActivityAudioBinding
import com.google.android.material.materialswitch.MaterialSwitch

/** Настройки → Аудио: отклик клавиатуры, обработка звука, датчик приближения, громкость. */
class AudioActivity : AppCompatActivity() {

    private lateinit var b: ActivityAudioBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        AudioPrefs.load(this)
        b = ActivityAudioBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.content.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        b.toolbar.setNavigationOnClickListener { finish() }

        bind(b.keyVibrate, AudioPrefs.keyVibrate) { AudioPrefs.update(this, keyVibrate = it) }
        bind(b.keySound, AudioPrefs.keySound) { AudioPrefs.update(this, keySound = it) }
        bind(b.echoCancel, AudioPrefs.echoCancel, AcousticEchoCanceler.isAvailable()) { AudioPrefs.update(this, echoCancel = it) }
        bind(b.noiseSuppress, AudioPrefs.noiseSuppress, NoiseSuppressor.isAvailable()) { AudioPrefs.update(this, noiseSuppress = it) }
        bind(b.autoGain, AudioPrefs.autoGain, AutomaticGainControl.isAvailable()) { AudioPrefs.update(this, autoGain = it) }
        bind(b.proximity, AudioPrefs.proximity) { AudioPrefs.update(this, proximity = it) }
        bind(b.autoRecord, AudioPrefs.autoRecord) { AudioPrefs.update(this, autoRecord = it) }
        b.recordingHint.text = getString(R.string.audio_recording_hint, Recordings.folderLabel())

        b.speakerGain.value = AudioPrefs.speakerDb.toFloat()
        b.micGain.value = AudioPrefs.micDb.toFloat()
        renderGains()
        b.speakerGain.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { AudioPrefs.update(this, speakerDb = v.toInt()); renderGains() }
        }
        b.micGain.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { AudioPrefs.update(this, micDb = v.toInt()); renderGains() }
        }
    }

    /** Переключатель; если эффект не поддерживается устройством, он выключен и недоступен. */
    private fun bind(sw: MaterialSwitch, value: Boolean, available: Boolean = true, onChange: (Boolean) -> Unit) {
        sw.isChecked = value && available
        sw.isEnabled = available
        if (!available) sw.text = getString(R.string.audio_unavailable, sw.text)
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun renderGains() {
        b.speakerLabel.text = getString(R.string.audio_speaker_gain_value, db(AudioPrefs.speakerDb))
        b.micLabel.text = getString(R.string.audio_mic_gain_value, db(AudioPrefs.micDb))
    }

    private fun db(v: Int) = (if (v > 0) "+" else "") + v + " дБ"
}
