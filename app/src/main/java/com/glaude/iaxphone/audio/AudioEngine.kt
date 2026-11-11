package com.glaude.iaxphone.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import com.glaude.iaxphone.iax.Codecs
import com.glaude.iaxphone.iax.VoiceCodec
import java.util.TreeMap

/**
 * Захват микрофона и воспроизведение для одного вызова: 8 кГц, моно, кадры по 20 мс (160 отсчётов).
 */
class AudioEngine(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var running = false
    @Volatile var muted = false
    private var txCodec: VoiceCodec? = null
    /** Декодеры по форматам входящего голоса (используются только из потока iax-loop). */
    private val rxCodecs = HashMap<Long, VoiceCodec>()
    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    /** Джиттер-буфер: метка времени → PCM. */
    private val jitter = TreeMap<Long, ShortArray>()
    private var lastPlayedTs = -1L

    @SuppressLint("MissingPermission")
    fun start(format: Long, onFrame: (ByteArray) -> Unit) {
        if (running) return
        if (!Codecs.isSupported(format)) { Log.e(TAG, "Кодек не поддерживается: $format"); return }
        txCodec = Codecs.create(format)
        synchronized(rxCodecs) { rxCodecs.clear() }
        running = true
        synchronized(jitter) { jitter.clear(); lastPlayedTs = -1 }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        recordThread = Thread({ recordLoop(onFrame) }, "iax-mic").apply { start() }
        playThread = Thread({ playLoop() }, "iax-spk").apply { start() }
    }

    fun stop() {
        if (!running) return
        running = false
        recordThread?.join(500)
        playThread?.join(500)
        recordThread = null
        playThread = null
        setSpeaker(false)
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun onIncoming(timestamp: Long, format: Long, payload: ByteArray) {
        if (!running || !Codecs.isSupported(format)) return
        val codec = synchronized(rxCodecs) { rxCodecs.getOrPut(format) { Codecs.create(format) } }
        val pcm = try { codec.decode(payload) } catch (e: Exception) { Log.w(TAG, "decode", e); return }
        if (pcm.isEmpty()) return
        synchronized(jitter) {
            if (timestamp <= lastPlayedTs) return // опоздавший кадр
            jitter[timestamp] = pcm
            while (jitter.size > MAX_JITTER_FRAMES) jitter.pollFirstEntry()
        }
    }

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop(onFrame: (ByteArray) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val codec = txCodec ?: return
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 10))
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord", e); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord не инициализирован"); rec.release(); return }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
        val buf = ShortArray(FRAME)
        val silence = ShortArray(FRAME)
        try {
            rec.startRecording()
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = rec.read(buf, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) continue
                onFrame(codec.encode(if (muted) silence else buf))
            }
        } catch (e: Exception) {
            Log.e(TAG, "record", e)
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            aec?.release(); ns?.release()
            rec.release()
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, FRAME * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val silence = ShortArray(FRAME)
        var buffering = true
        var underruns = 0
        try {
            track.play()
            while (running) {
                val pcm: ShortArray? = synchronized(jitter) {
                    if (buffering && jitter.size >= PREFILL_FRAMES) buffering = false
                    if (buffering) null else jitter.pollFirstEntry()?.also { lastPlayedTs = it.key }?.value
                }
                if (pcm != null) {
                    underruns = 0
                    track.write(pcm, 0, pcm.size)
                } else {
                    if (!buffering && ++underruns > 5) buffering = true
                    track.write(silence, 0, silence.size) // write блокируется и задаёт темп 20 мс
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "play", e)
        } finally {
            try { track.stop() } catch (_: Exception) { }
            track.release()
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val RATE = 8000
        const val FRAME = 160
        private const val PREFILL_FRAMES = 3
        private const val MAX_JITTER_FRAMES = 15
    }
}
