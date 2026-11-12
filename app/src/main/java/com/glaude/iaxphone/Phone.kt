package com.glaude.iaxphone

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glaude.iaxphone.audio.AudioEngine
import com.glaude.iaxphone.audio.CallRecorder
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
    private var callStartedMs = 0L

    var regState = RegState.OFF; private set
    var regDetail = "Не подключено"; private set
    /** Голосовая почта: -1 — сервер не сообщал. */
    var vmNew = -1; private set
    var vmOld = -1; private set
    var call: IaxCall? = null; private set
    var callState: CallState? = null; private set
    var muted = false; private set
    var speaker = false; private set
    val connected get() = client != null

    /** Запись разговора: время начала или 0, если не пишем. */
    var recordingSinceMs = 0L; private set
    val recording get() = recordingSinceMs > 0
    private var recorder: CallRecorder? = null

    /** Хук сервиса: true — нужен микрофон в foreground-сервисе, false — больше не нужен. */
    var microphoneHook: ((Boolean) -> Unit)? = null

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        AudioPrefs.load(app)
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
        NetworkWatch.start()
        notifyChanged()
    }

    fun disconnect(onDone: (() -> Unit)? = null) {
        val c = client ?: run { onDone?.invoke(); return }
        NetworkWatch.stop()
        client = null
        stopRinging()
        stopRecording()
        audio?.stop()
        c.stop { main.post { onDone?.invoke() } }
        regState = RegState.OFF
        regDetail = "Не подключено"
        vmNew = -1; vmOld = -1
        setCall(null)
    }

    fun dial(number: String): Boolean {
        val c = client ?: return false
        if ((call != null && call?.state != CallState.ENDED) || number.isBlank()) return false
        microphoneHook?.invoke(true)
        val newCall = c.call(number.trim())
        setCall(newCall)
        TelecomBridge.onOutgoing(app, newCall)
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
    }

    fun toggleMute() { muted = !muted; audio?.muted = muted; notifyChanged() }

    fun toggleSpeaker() {
        speaker = !speaker
        if (!TelecomBridge.setSpeaker(speaker)) audio?.setSpeaker(speaker)
        notifyChanged()
    }

    fun toggleHold() { call?.let { setHold(!it.onHold) } }

    fun setHold(hold: Boolean) {
        val cl = call ?: return
        if (cl.state != CallState.ACTIVE) return
        audio?.onHold = hold
        client?.setHold(cl, hold)
    }

    fun silenceRinger() = stopRinging()

    /** Включает или выключает запись текущего разговора. */
    fun toggleRecording() { if (recording) stopRecording() else startRecording() }

    private fun startRecording() {
        val cl = call ?: return
        val a = audio ?: return
        if (cl.state != CallState.ACTIVE || recording) return
        val r = try { CallRecorder(Recordings.newTempFile(app)) } catch (e: Exception) { Log.w(TAG, "rec", e); return }
        recorder = r
        a.recorder = r
        recordingSinceMs = System.currentTimeMillis()
        notifyChanged()
    }

    /** Останавливает запись и переносит файл в общую папку (в фоне). */
    private fun stopRecording() {
        val r = recorder ?: return
        val cl = call
        recorder = null
        audio?.recorder = null
        recordingSinceMs = 0
        val startMs = callStartedMs
        val name = Recordings.fileName(cl?.number ?: "call", cl?.outgoing ?: true, startMs)
        Thread {
            val sec = try { r.close() } catch (e: Exception) { Log.w(TAG, "rec close", e); -1L }
            val uri = if (sec >= 0) Recordings.publish(app, r.file, name) else { r.file.delete(); null }
            main.post {
                if (uri != null) {
                    // Запись журнала появляется при завершении вызова — привяжем, когда она есть.
                    pendingRecording = startMs to uri.toString()
                    attachPendingRecording()
                }
                notifyChanged()
            }
        }.start()
        notifyChanged()
    }

    private var pendingRecording: Pair<Long, String>? = null

    private fun attachPendingRecording() {
        val (start, uri) = pendingRecording ?: return
        if (CallHistory.all(app).any { it.startMs == start }) {
            CallHistory.setRecording(app, start, uri)
            pendingRecording = null
        }
    }

    // ------------------------------------------------------------------ система вызовов (Telecom)

    internal fun onTelecomAttached(cl: IaxCall) {
        if (cl !== call) return
        audio?.routedBySystem = true
    }

    internal fun onTelecomRejected(cl: IaxCall) {
        if (cl !== call) return
        // Система не дала занять линию (например, идёт экстренный вызов).
        if (!cl.outgoing) client?.hangup(cl, busy = true)
        else onLog("Система вызовов отказала, продолжаем без неё")
    }

    internal fun onSystemAudioRoute(speakerOn: Boolean) {
        if (speaker == speakerOn) return
        speaker = speakerOn
        notifyChanged()
    }

    // ------------------------------------------------------------------ сеть

    /** Следит за сменой сети и сразу перерегистрируется. */
    private object NetworkWatch {
        private var callback: ConnectivityManager.NetworkCallback? = null
        private var network: Network? = null
        private var addresses: List<String>? = null
        private var initialized = false

        fun start() {
            if (callback != null) return
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            initialized = false; network = null; addresses = null
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { main.post { changed(n, null) } }
                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                    val list = lp.linkAddresses.mapNotNull { it.address?.hostAddress }.sorted()
                    main.post { changed(n, list) }
                }
                override fun onLost(n: Network) {
                    main.post {
                        if (network == n) { network = null; addresses = null; onLog("Сеть пропала") }
                    }
                }
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
                callback = cb
            } catch (e: Exception) {
                Log.w(TAG, "network callback", e)
            }
        }

        fun stop() {
            main.removeCallbacks(reregister)
            pendingReason = null
            val cb = callback ?: return
            callback = null
            try { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
        }

        private fun changed(n: Network, list: List<String>?) {
            if (callback == null) return
            val first = !initialized
            initialized = true
            val newNetwork = n != network
            val newAddresses = list != null && addresses != null && list != addresses
            network = n
            if (list != null) addresses = list
            if (first || !(newNetwork || newAddresses)) return
            // Смена сети приходит пачкой событий (сеть, адреса IPv4/IPv6) — регистрируемся один раз.
            pendingReason = if (newNetwork || pendingReason == null) (if (newNetwork) "сменилась сеть" else "сменился IP-адрес") else pendingReason
            main.removeCallbacks(reregister)
            main.postDelayed(reregister, 1500)
        }

        private var pendingReason: String? = null
        private val reregister = Runnable {
            val reason = pendingReason ?: return@Runnable
            pendingReason = null
            client?.reregister(reason)
        }
    }

    // ------------------------------------------------------------------ IaxListener (поток iax-loop)

    override fun onRegistration(state: RegState, detail: String) {
        main.post {
            if (client == null && state != RegState.OFF) return@post
            regState = state; regDetail = detail; notifyChanged()
        }
    }

    override fun onVoicemail(newCount: Int, oldCount: Int) {
        main.post {
            if (client == null) return@post
            vmNew = newCount; vmOld = oldCount; notifyChanged()
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
            TelecomBridge.onIncoming(app, call)
        }
    }

    override fun onCallState(call: IaxCall) {
        main.post {
            if (call !== this.call) return@post
            callState = call.state
            audio?.onHold = call.onHold
            if (call.state != CallState.INCOMING) stopRinging()
            TelecomBridge.update(call)
            if (call.state == CallState.ENDED) {
                stopRecording()
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
        a.onHold = false
        a.routedBySystem = TelecomBridge.active
        a.start(call.format) { frame -> c.sendVoice(call, frame) }
        main.post {
            if (!TelecomBridge.active) a.setSpeaker(speaker)
            if (AudioPrefs.autoRecord && call.state == CallState.ACTIVE) startRecording()
        }
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
            attachPendingRecording()
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
}
