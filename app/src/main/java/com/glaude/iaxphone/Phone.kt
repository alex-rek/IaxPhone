package com.glaude.iaxphone

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glaude.iaxphone.audio.AudioEngine
import com.glaude.iaxphone.iax.CallState
import com.glaude.iaxphone.iax.IaxCall
import com.glaude.iaxphone.iax.IaxClient
import com.glaude.iaxphone.iax.IaxConfig
import com.glaude.iaxphone.iax.IaxListener
import com.glaude.iaxphone.iax.RegState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Единая точка управления софтфоном: протокол + звук + состояние для UI и сервиса.
 * Все колбэки наблюдателей вызываются в главном потоке.
 */
object Phone : IaxListener {
    interface Observer {
        fun onPhoneChanged()
        fun onLogLine(line: String) {}
    }

    private const val TAG = "Phone"
    private val main = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<Observer>()
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var app: Context
    private var audio: AudioEngine? = null
    private var client: IaxClient? = null
    private var ringtone: Ringtone? = null
    private var dtmfTone: ToneGenerator? = null
    private var callStartedMs = 0L

    var regState = RegState.OFF; private set
    var regDetail = "Не подключено"; private set
    var call: IaxCall? = null; private set
    var callState: CallState? = null; private set
    var muted = false; private set
    var speaker = false; private set
    val connected get() = client != null

    /** Хук сервиса: true — нужен микрофон в foreground-сервисе, false — больше не нужен. */
    var microphoneHook: ((Boolean) -> Unit)? = null

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun addObserver(o: Observer) { observers += o }
    fun removeObserver(o: Observer) { observers -= o }
    fun log(): List<String> = logLines.toList()
    fun clearLog() { logLines.clear() }

    // ------------------------------------------------------------------ команды UI

    fun connect(config: IaxConfig) {
        client?.stop()
        audio = AudioEngine(app)
        regState = RegState.REGISTERING
        regDetail = "Подключение…"
        client = IaxClient(config, this).also { it.start() }
        notifyChanged()
    }

    fun disconnect(onDone: (() -> Unit)? = null) {
        val c = client ?: run { onDone?.invoke(); return }
        client = null
        stopRinging()
        audio?.stop()
        c.stop { main.post { onDone?.invoke() } }
        regState = RegState.OFF
        regDetail = "Не подключено"
        setCall(null)
    }

    fun dial(number: String): Boolean {
        val c = client ?: return false
        if ((call != null && call?.state != CallState.ENDED) || number.isBlank()) return false
        microphoneHook?.invoke(true)
        setCall(c.call(number.trim()))
        return true
    }

    fun answer() {
        val c = client ?: return
        val cl = call ?: return
        if (cl.state != CallState.INCOMING) return
        stopRinging()
        microphoneHook?.invoke(true)
        c.answer(cl)
    }

    fun hangup() {
        val cl = call ?: return
        stopRinging()
        client?.hangup(cl)
    }

    fun dtmf(digit: Char) {
        val cl = call ?: return
        if (cl.state != CallState.ACTIVE) return
        client?.sendDtmf(cl, digit)
        playDtmfTone(digit)
    }

    fun toggleMute() { muted = !muted; audio?.muted = muted; notifyChanged() }

    fun toggleSpeaker() { speaker = !speaker; audio?.setSpeaker(speaker); notifyChanged() }

    // ------------------------------------------------------------------ IaxListener (поток iax-loop)

    override fun onRegistration(state: RegState, detail: String) {
        main.post {
            if (client == null && state != RegState.OFF) return@post
            regState = state; regDetail = detail; notifyChanged()
        }
    }

    override fun onIncomingCall(call: IaxCall) {
        main.post {
            if (this.call != null && this.call?.state != CallState.ENDED) {
                client?.hangup(call, busy = true) // одна линия: второй вызов — «занято»
                return@post
            }
            setCall(call)
            startRinging()
        }
    }

    override fun onCallState(call: IaxCall) {
        main.post {
            if (call !== this.call) return@post
            callState = call.state
            if (call.state != CallState.INCOMING) stopRinging()
            if (call.state == CallState.ENDED) {
                recordHistory(call)
                audio?.stop()
                muted = false; speaker = false
                microphoneHook?.invoke(false)
                notifyChanged()
                // Показываем причину завершения пару секунд, затем освобождаем линию.
                main.postDelayed({ if (this.call === call) setCall(null) }, 2500)
            } else {
                notifyChanged()
            }
        }
    }

    override fun onMediaStart(call: IaxCall) {
        val c = client ?: return
        val a = audio ?: return
        a.muted = muted
        a.start(call.format) { frame -> c.sendVoice(call, frame) }
        main.post { a.setSpeaker(speaker) }
    }

    override fun onVoice(call: IaxCall, timestamp: Long, format: Long, payload: ByteArray) {
        if (call === this.call) audio?.onIncoming(timestamp, format, payload)
    }

    override fun onLog(line: String) {
        Log.d(TAG, line)
        val stamped = timeFmt.format(Date()) + " " + line
        main.post {
            logLines.addLast(stamped)
            while (logLines.size > 300) logLines.removeFirst()
            observers.toList().forEach { it.onLogLine(stamped) }
        }
    }

    // ------------------------------------------------------------------ внутреннее

    private fun recordHistory(c: IaxCall) {
        val answered = c.answeredAtMs > 0
        val dur = if (answered) (System.currentTimeMillis() - c.answeredAtMs) / 1000 else 0
        try {
            CallHistory.add(app, CallRecord(c.number, c.remoteName, c.outgoing, answered, callStartedMs, dur))
        } catch (e: Exception) {
            Log.w(TAG, "history", e)
        }
    }

    private fun setCall(c: IaxCall?) {
        if (c != null && c !== call) callStartedMs = System.currentTimeMillis()
        call = c
        callState = c?.state
        notifyChanged()
    }

    private fun notifyChanged() = observers.toList().forEach { it.onPhoneChanged() }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(app, uri)?.apply { isLooping = true; play() }
        } catch (e: Exception) {
            Log.w(TAG, "ringtone", e)
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
    }

    private fun playDtmfTone(digit: Char) {
        val tone = when (digit) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (digit - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        try {
            if (dtmfTone == null) dtmfTone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
            dtmfTone?.startTone(tone, 120)
        } catch (_: Exception) { }
    }
}
