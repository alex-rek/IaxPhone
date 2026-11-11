package com.glaude.iaxphone.iax

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class IaxConfig(
    val host: String,
    val port: Int = IAX_DEFAULT_PORT,
    val username: String,
    val password: String,
    val callerId: String = "",
    val callerName: String = "",
    /** Желаемый период перерегистрации, секунд. */
    val refresh: Int = 60,
    /** Кодеки в порядке предпочтения. */
    val codecs: List<Long> = Codecs.SUPPORTED,
    val localPort: Int = 0,
    /** Регистрироваться на сервере (для приёма входящих). */
    val register: Boolean = true,
)

enum class RegState { OFF, REGISTERING, REGISTERED, FAILED }

enum class CallState { DIALING, RINGING, INCOMING, ACTIVE, ENDED }

class IaxCall internal constructor(
    internal val callNo: Int,
    val outgoing: Boolean,
    /** Для исходящего: набранный номер; для входящего: номер звонящего. */
    val number: String,
) {
    @Volatile var state: CallState = if (outgoing) CallState.DIALING else CallState.INCOMING
        internal set
    @Volatile var remoteName: String? = null
        internal set
    @Volatile var format: Long = 0
        internal set
    @Volatile var endReason: String? = null
        internal set
    @Volatile var answeredAtMs: Long = 0
        internal set

    override fun toString() = "Call#$callNo($number, $state)"
}

interface IaxListener {
    fun onRegistration(state: RegState, detail: String) {}
    /** Голосовая почта из REGACK: новые и прослушанные сообщения. */
    fun onVoicemail(newCount: Int, oldCount: Int) {}
    fun onIncomingCall(call: IaxCall) {}
    fun onCallState(call: IaxCall) {}
    /** Пора запускать звук: формат согласован (ACCEPT для исходящего, ответ для входящего). */
    fun onMediaStart(call: IaxCall) {}
    fun onVoice(call: IaxCall, timestamp: Long, format: Long, payload: ByteArray) {}
    fun onDtmf(call: IaxCall, digit: Char) {}
    fun onLog(line: String) {}
}

/**
 * Клиент IAX2 поверх UDP. Вся логика протокола выполняется в одном потоке ("iax-loop"),
 * поэтому внутреннее состояние не требует синхронизации. Публичные методы потокобезопасны.
 */
class IaxClient(
    private val config: IaxConfig,
    private val listener: IaxListener,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private enum class Kind { REG, CALL }

    private class Pending(val frame: FullFrame, var sentAt: Long, var rto: Long, var retries: Int = 0)

    private inner class Session(val callNo: Int, val kind: Kind) {
        var remoteCallNo = 0
        var oseq = 0
        var iseq = 0
        val start = clock()
        val createdAt = start
        var lastTs = 0L
        val pending = ArrayList<Pending>()
        var call: IaxCall? = null

        /** Первый запрос (NEW/REGREQ/REGREL) — нужен для повтора с CALLTOKEN. */
        var initialCmd = 0
        var initialIes: (() -> IeBuilder)? = null
        var tokenUsed = false
        var authSent = false
        var closing = false
        var closingSince = 0L

        var quelched = false
        var txVoiceStarted = false
        var txFormat = 0L
        var lastVoiceTs = -1L
        var rxVoiceTs = 0L
        var rxFormat = 0L
    }

    private val loop: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "iax-loop").apply { isDaemon = true }
    }
    private val sessions = ConcurrentHashMap<Int, Session>()
    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    @Volatile private var server: InetSocketAddress? = null
    @Volatile private var running = false

    private var regState = RegState.OFF
    private var regSession: Session? = null
    private var regTimer: ScheduledFuture<*>? = null
    private var tickTimer: ScheduledFuture<*>? = null

    val localPort: Int get() = socket?.localPort ?: 0

    // ------------------------------------------------------------------ public API

    fun start() {
        val sock = DatagramSocket(config.localPort)
        socket = sock
        running = true
        receiver = Thread({ receiveLoop(sock) }, "iax-rx").apply { isDaemon = true; start() }
        tickTimer = loop.scheduleWithFixedDelay({ safe { tick() } }, 50, 50, TimeUnit.MILLISECONDS)
        post {
            if (!resolveServer()) return@post
            log("Локальный UDP-порт ${sock.localPort}, сервер $server")
            if (config.register) register(IaxCmd.REGREQ)
        }
    }

    /** Завершает вызовы, снимает регистрацию и закрывает сокет. */
    fun stop(onStopped: (() -> Unit)? = null) {
        post {
            sessions.values.filter { it.kind == Kind.CALL }.forEach { s -> s.call?.let { hangupInternal(s, it) } }
            regTimer?.cancel(false)
            val wasRegistered = regState == RegState.REGISTERED
            if (wasRegistered) register(IaxCmd.REGREL) else regSession?.let { destroy(it) }
            setReg(RegState.OFF, "Отключено")
            loop.schedule({ shutdown(); onStopped?.invoke() }, if (wasRegistered) 1200 else 300, TimeUnit.MILLISECONDS)
        }
    }

    fun call(number: String): IaxCall {
        val s = newSession(Kind.CALL)
        val call = IaxCall(s.callNo, outgoing = true, number = number)
        s.call = call
        post {
            if (server == null && !resolveServer()) { endCall(s, "Сервер недоступен"); destroy(s); return@post }
            val caps = config.codecs.fold(0L) { acc, c -> acc or c }
            s.initialCmd = IaxCmd.NEW
            s.initialIes = {
                IeBuilder()
                    .u16(Ie.VERSION, 2)
                    .str(Ie.CALLED_NUMBER, number)
                    .str(Ie.CALLING_NUMBER, config.callerId.ifEmpty { config.username })
                    .str(Ie.CALLING_NAME, config.callerName.ifEmpty { config.username })
                    .str(Ie.USERNAME, config.username)
                    .u32(Ie.CAPABILITY, caps)
                    .u32(Ie.FORMAT, config.codecs.first())
            }
            sendInitial(s)
            listener.onCallState(call)
        }
        return call
    }

    fun answer(call: IaxCall) = post {
        val s = sessions[call.callNo] ?: return@post
        if (call.state != CallState.INCOMING) return@post
        sendFull(s, FrameType.CONTROL, ControlCmd.ANSWER.toLong())
        call.state = CallState.ACTIVE
        call.answeredAtMs = System.currentTimeMillis()
        listener.onMediaStart(call)
        listener.onCallState(call)
    }

    fun hangup(call: IaxCall, busy: Boolean = false) = post {
        val s = sessions[call.callNo] ?: return@post
        hangupInternal(s, call, busy)
    }

    fun sendDtmf(call: IaxCall, digit: Char) = post {
        val s = sessions[call.callNo] ?: return@post
        if (call.state != CallState.ACTIVE) return@post
        sendFull(s, FrameType.DTMF_END, digit.code.toLong())
    }

    /** Отправляет 20 мс закодированного голоса в формате call.format. */
    fun sendVoice(call: IaxCall, payload: ByteArray) = post {
        val s = sessions[call.callNo] ?: return@post
        if (call.state != CallState.ACTIVE || s.quelched || call.format == 0L) return@post
        var ts = ((clock() - s.start) / 20) * 20
        if (ts <= s.lastVoiceTs) ts = s.lastVoiceTs + 20
        val needFull = !s.txVoiceStarted || s.txFormat != call.format ||
            (ts and 0xffff0000L) != (s.lastVoiceTs and 0xffff0000L)
        s.lastVoiceTs = ts
        if (needFull) {
            s.txVoiceStarted = true
            s.txFormat = call.format
            sendFull(s, FrameType.VOICE, call.format, payload, ts)
        } else {
            send(MiniFrame(s.callNo, (ts and 0xffff).toInt(), payload).encode())
        }
    }

    // ------------------------------------------------------------------ registration

    private fun register(cmd: Int) {
        regSession?.let { destroy(it) }
        regTimer?.cancel(false)
        val s = newSession(Kind.REG)
        regSession = s
        s.initialCmd = cmd
        s.initialIes = { IeBuilder().str(Ie.USERNAME, config.username).u16(Ie.REFRESH, config.refresh) }
        if (cmd == IaxCmd.REGREQ) setReg(RegState.REGISTERING, "Регистрация…")
        sendInitial(s)
    }

    private fun scheduleRegister(delaySec: Long) {
        regTimer?.cancel(false)
        if (!running) return
        regTimer = loop.schedule({
            safe { if (running && resolveServer()) register(IaxCmd.REGREQ) }
        }, delaySec, TimeUnit.SECONDS)
    }

    private fun onRegFrame(s: Session, f: FullFrame) {
        if (f.type != FrameType.IAX) return
        when (f.subclass.toInt()) {
            IaxCmd.REGAUTH -> {
                if (s.authSent) { regFailed(s, "Повторный запрос авторизации"); return }
                val auth = buildAuth(f.ies) ?: run { regFailed(s, "Метод авторизации не поддерживается"); return }
                s.authSent = true
                val ies = IeBuilder().str(Ie.USERNAME, config.username).raw(auth.first, auth.second)
                    .u16(Ie.REFRESH, config.refresh)
                sendFull(s, FrameType.IAX, s.initialCmd.toLong(), ies.build())
            }
            IaxCmd.REGACK -> {
                destroy(s)
                if (regSession === s) regSession = null
                if (s.initialCmd == IaxCmd.REGREL) { log("Регистрация снята"); return }
                val refresh = (f.ies.u16(Ie.REFRESH) ?: config.refresh).coerceAtLeast(10)
                setReg(RegState.REGISTERED, "Зарегистрирован (обновление через $refresh с)")
                f.ies.u16(Ie.MSGCOUNT)?.let { m ->
                    // Asterisk: старший байт — прослушанные, младший — новые (каждое до 255).
                    val (new, old) = parseMsgCount(m)
                    log("Голосовая почта: новых $new, прослушанных $old")
                    listener.onVoicemail(new, old)
                }
                scheduleRegister((refresh * 3L / 4).coerceAtLeast(5))
            }
            IaxCmd.REGREJ -> regFailed(s, "Отказ: " + (f.ies.str(Ie.CAUSE) ?: "без причины"))
        }
    }

    private fun regFailed(s: Session, reason: String) {
        destroy(s)
        if (regSession === s) regSession = null
        if (s.initialCmd == IaxCmd.REGREL) return
        setReg(RegState.FAILED, reason)
        scheduleRegister(30)
    }

    private fun setReg(state: RegState, detail: String) {
        regState = state
        log("Регистрация: $state — $detail")
        listener.onRegistration(state, detail)
    }

    // ------------------------------------------------------------------ calls

    private fun onIncomingNew(f: FullFrame) {
        // Повтор NEW, на который мы уже ответили.
        if (sessions.values.any { it.kind == Kind.CALL && it.remoteCallNo == f.srcCall && it.call?.outgoing == false }) return
        val s = newSession(Kind.CALL)
        s.remoteCallNo = f.srcCall
        s.iseq = (f.oseq + 1) and 0xff
        log("<< " + f.describe())
        sendAck(s, f)

        val ies = f.ies
        val number = ies.str(Ie.CALLING_NUMBER) ?: ies.str(Ie.CALLING_ANI) ?: "неизвестный"
        val call = IaxCall(s.callNo, outgoing = false, number = number)
        call.remoteName = ies.str(Ie.CALLING_NAME)
        s.call = call

        val fmt = chooseFormat(ies.format(), ies.capability())
        if (fmt == null) {
            sendFull(s, FrameType.IAX, IaxCmd.REJECT.toLong(),
                IeBuilder().str(Ie.CAUSE, "No compatible codec").u8(Ie.CAUSECODE, 58).build())
            s.closing = true; s.closingSince = clock()
            return
        }
        call.format = fmt
        sendFull(s, FrameType.IAX, IaxCmd.ACCEPT.toLong(),
            IeBuilder().u32(Ie.FORMAT, fmt).u64v(Ie.FORMAT2, fmt).build())
        sendFull(s, FrameType.CONTROL, ControlCmd.RINGING.toLong())
        listener.onIncomingCall(call)
        listener.onCallState(call)
    }

    private fun chooseFormat(preferred: Long?, capability: Long?): Long? {
        if (preferred != null && preferred in config.codecs) return preferred
        val caps = capability ?: preferred ?: return null
        return config.codecs.firstOrNull { caps and it != 0L }
    }

    private fun onCallFrame(s: Session, f: FullFrame) {
        val call = s.call ?: return
        when (f.type) {
            FrameType.IAX -> when (f.subclass.toInt()) {
                IaxCmd.AUTHREQ -> {
                    if (s.authSent) { failCall(s, "Повторный запрос авторизации"); return }
                    val auth = buildAuth(f.ies) ?: run { failCall(s, "Метод авторизации не поддерживается"); return }
                    s.authSent = true
                    sendFull(s, FrameType.IAX, IaxCmd.AUTHREP.toLong(), IeBuilder().raw(auth.first, auth.second).build())
                }
                IaxCmd.ACCEPT -> {
                    val fmt = f.ies.format()?.takeIf { it in config.codecs }
                        ?: chooseFormat(null, f.ies.capability()) ?: config.codecs.first()
                    call.format = fmt
                    log("Кодек: ${Codec.name(fmt)}")
                    listener.onMediaStart(call)
                }
                IaxCmd.HANGUP -> { endCall(s, f.ies.str(Ie.CAUSE) ?: "Собеседник положил трубку"); destroy(s) }
                IaxCmd.REJECT -> { endCall(s, "Отклонён: " + (f.ies.str(Ie.CAUSE) ?: "без причины")); destroy(s) }
                IaxCmd.PING -> sendFull(s, FrameType.IAX, IaxCmd.PONG.toLong(), ts = f.timestamp)
                IaxCmd.LAGRQ -> sendFull(s, FrameType.IAX, IaxCmd.LAGRP.toLong(), ts = f.timestamp)
                IaxCmd.QUELCH -> s.quelched = true
                IaxCmd.UNQUELCH -> s.quelched = false
                IaxCmd.TXREQ -> sendFull(s, FrameType.IAX, IaxCmd.TXREJ.toLong())
                IaxCmd.PONG, IaxCmd.LAGRP, IaxCmd.MWI, IaxCmd.UNSUPPORT -> Unit
                else -> sendFull(s, FrameType.IAX, IaxCmd.UNSUPPORT.toLong(),
                    IeBuilder().u8(Ie.IAX_UNKNOWN, f.subclass.toInt()).build())
            }
            FrameType.CONTROL -> when (f.subclass.toInt()) {
                ControlCmd.RINGING -> if (call.state == CallState.DIALING) {
                    call.state = CallState.RINGING; listener.onCallState(call)
                }
                ControlCmd.ANSWER -> if (call.outgoing && call.state != CallState.ACTIVE && call.state != CallState.ENDED) {
                    call.state = CallState.ACTIVE
                    call.answeredAtMs = System.currentTimeMillis()
                    listener.onCallState(call)
                }
                ControlCmd.BUSY -> failCall(s, "Занято", CauseCode.USER_BUSY)
                ControlCmd.CONGESTION -> failCall(s, "Перегрузка сети")
                ControlCmd.HANGUP -> { endCall(s, "Собеседник положил трубку"); destroy(s) }
                else -> Unit
            }
            FrameType.VOICE -> {
                s.rxFormat = f.subclass
                s.rxVoiceTs = f.timestamp
                listener.onVoice(call, f.timestamp, f.subclass, f.payload)
            }
            FrameType.DTMF_END -> listener.onDtmf(call, f.subclass.toInt().toChar())
            FrameType.TEXT -> log("Текст: " + String(f.payload))
            else -> Unit
        }
    }

    private fun onMiniFrame(m: MiniFrame) {
        val s = sessions.values.firstOrNull { it.kind == Kind.CALL && it.remoteCallNo == m.srcCall } ?: return
        val call = s.call ?: return
        if (s.rxFormat == 0L) return // формат неизвестен, пока не пришёл полный голосовой кадр
        var ts = (s.rxVoiceTs and 0xffff0000L) or m.timestamp.toLong()
        if (ts < s.rxVoiceTs - 0x8000) ts += 0x10000
        if (ts > s.rxVoiceTs) s.rxVoiceTs = ts
        listener.onVoice(call, ts, s.rxFormat, m.payload)
    }

    private fun hangupInternal(s: Session, call: IaxCall, busy: Boolean = false) {
        if (call.state == CallState.ENDED) return
        if (s.remoteCallNo == 0) {
            // Сервер ещё не ответил на NEW — посылать HANGUP некуда.
            endCall(s, "Вызов отменён"); destroy(s); return
        }
        val code = if (busy) CauseCode.USER_BUSY else if (call.state == CallState.INCOMING) CauseCode.CALL_REJECTED else CauseCode.NORMAL_CLEARING
        val cause = if (busy) "User busy" else if (code == CauseCode.CALL_REJECTED) "Call rejected" else "Normal Clearing"
        sendFull(s, FrameType.IAX, IaxCmd.HANGUP.toLong(), IeBuilder().str(Ie.CAUSE, cause).u8(Ie.CAUSECODE, code).build())
        endCall(s, if (busy) "Отклонён (занято)" else if (call.state == CallState.INCOMING) "Отклонён" else "Завершён")
        s.closing = true
        s.closingSince = clock()
    }

    private fun failCall(s: Session, reason: String, code: Int = CauseCode.NORMAL_CLEARING) {
        val call = s.call ?: return
        if (s.remoteCallNo != 0) {
            sendFull(s, FrameType.IAX, IaxCmd.HANGUP.toLong(), IeBuilder().str(Ie.CAUSE, reason).u8(Ie.CAUSECODE, code).build())
        }
        endCall(s, reason)
        s.closing = true
        s.closingSince = clock()
        if (call.state == CallState.ENDED && s.remoteCallNo == 0) destroy(s)
    }

    private fun endCall(s: Session, reason: String) {
        val call = s.call ?: return
        if (call.state == CallState.ENDED) return
        call.state = CallState.ENDED
        call.endReason = reason
        log("Вызов ${call.number}: $reason")
        listener.onCallState(call)
    }

    // ------------------------------------------------------------------ auth

    /** Возвращает пару (тип IE, значение) для AUTHREP/REGREQ. */
    private fun buildAuth(ies: InfoElements): Pair<Int, ByteArray>? {
        val methods = ies.u16(Ie.AUTHMETHODS) ?: AuthMethod.MD5
        val challenge = ies.str(Ie.CHALLENGE) ?: ""
        return when {
            methods and AuthMethod.MD5 != 0 -> Ie.MD5_RESULT to md5Hex(challenge + config.password).toByteArray()
            methods and AuthMethod.PLAINTEXT != 0 -> Ie.PASSWORD to config.password.toByteArray()
            else -> null
        }
    }

    // ------------------------------------------------------------------ transport

    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (e: Exception) {
                if (running) log("Ошибка приёма: ${e.message}")
                break
            }
            val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            val from = pkt.address
            post { onDatagram(data, from) }
        }
    }

    private fun onDatagram(data: ByteArray, from: InetAddress) {
        val srv = server ?: return
        if (from != srv.address) return // принимаем пакеты только от своего сервера
        when (val p = IaxParser.parse(data)) {
            is MiniFrame -> onMiniFrame(p)
            is FullFrame -> onFullFrame(p)
            null -> Unit
        }
    }

    private fun onFullFrame(f: FullFrame) {
        if (f.dstCall == 0) {
            when {
                f.isIax(IaxCmd.NEW) -> onIncomingNew(f)
                f.isIax(IaxCmd.POKE) || f.isIax(IaxCmd.PING) -> {
                    // Проверка доступности (qualify): отвечаем PONG вне сессии.
                    val pong = FullFrame(allocCallNo(), f.srcCall, false, f.timestamp, 0, 1, FrameType.IAX, IaxCmd.PONG.toLong())
                    send(pong.encode())
                }
            }
            return
        }
        val s = sessions[f.dstCall] ?: return
        if (f.isIax(IaxCmd.CALLTOKEN)) { onCallToken(s, f); return }
        if (s.remoteCallNo != 0 && f.srcCall != s.remoteCallNo) {
            // Сервер принял повтор нашего первого запроса за новый вызов и завёл лишнюю сессию:
            // сбрасываем её, иначе она висит на сервере до тайм-аута.
            if (!f.isIax(IaxCmd.ACK) && !f.isIax(IaxCmd.INVAL)) {
                log("<< лишняя сессия сервера #${f.srcCall} (${f.describe()}) — сбрасываем")
                send(FullFrame(s.callNo, f.srcCall, false, f.timestamp, 0, (f.oseq + 1) and 0xff,
                    FrameType.IAX, IaxCmd.INVAL.toLong()).encode())
            }
            return
        }
        if (f.type != FrameType.VOICE) log("<< " + f.describe())
        if (s.remoteCallNo == 0) s.remoteCallNo = f.srcCall
        ackUpTo(s, f.iseq)

        if (f.type == FrameType.IAX) when (f.subclass.toInt()) {
            IaxCmd.ACK, IaxCmd.TXCNT, IaxCmd.TXACC -> return
            IaxCmd.VNAK -> { s.pending.forEach { resend(it) }; return }
            IaxCmd.INVAL -> { endCall(s, "Сервер сбросил вызов"); if (s.kind == Kind.REG) regFailed(s, "INVAL") else destroy(s); return }
        }

        if (f.oseq != s.iseq) {
            // Дубликат (наш ACK потерялся) — подтверждаем ещё раз; кадр из будущего — ждём повтора.
            if (seqBefore(f.oseq, s.iseq)) sendAck(s, f)
            return
        }
        s.iseq = (s.iseq + 1) and 0xff
        sendAck(s, f)

        when (s.kind) {
            Kind.REG -> onRegFrame(s, f)
            Kind.CALL -> if (!s.closing) onCallFrame(s, f)
        }
    }

    private fun onCallToken(s: Session, f: FullFrame) {
        val token = f.ies.bytes(Ie.CALLTOKEN) ?: return
        if (s.tokenUsed || s.initialIes == null) return
        log("<< IAX/CALLTOKEN, повторяем запрос с токеном")
        s.tokenUsed = true
        s.pending.clear()
        s.oseq = 0; s.iseq = 0; s.remoteCallNo = 0
        sendInitial(s, token)
    }

    private fun sendInitial(s: Session, token: ByteArray? = null) {
        val ies = s.initialIes!!.invoke()
        if (token != null) ies.raw(Ie.CALLTOKEN, token) else ies.empty(Ie.CALLTOKEN)
        sendFull(s, FrameType.IAX, s.initialCmd.toLong(), ies.build())
    }

    private fun sendFull(s: Session, type: Int, subclass: Long, payload: ByteArray = ByteArray(0), ts: Long? = null) {
        var t = ts ?: (clock() - s.start)
        if (ts == null && t <= s.lastTs) t = s.lastTs + 1
        if (t > s.lastTs) s.lastTs = t
        val f = FullFrame(s.callNo, s.remoteCallNo, false, t, s.oseq, s.iseq, type, subclass, payload)
        s.oseq = (s.oseq + 1) and 0xff
        s.pending += Pending(f, clock(), INITIAL_RTO)
        if (type != FrameType.VOICE) log(">> " + f.describe())
        send(f.encode())
    }

    private fun sendAck(s: Session, f: FullFrame) {
        val ack = FullFrame(s.callNo, s.remoteCallNo, false, f.timestamp, s.oseq, s.iseq, FrameType.IAX, IaxCmd.ACK.toLong())
        send(ack.encode())
    }

    /** Неявное подтверждение: собеседник ждёт кадр iseq, значит всё до него получено. */
    private fun ackUpTo(s: Session, iseq: Int) {
        s.pending.removeAll { val d = (iseq - it.frame.oseq) and 0xff; d in 1..127 }
        if (s.closing && s.pending.isEmpty()) destroy(s)
    }

    private fun resend(p: Pending) {
        send(p.frame.encode(retransmit = true))
    }

    private fun tick() {
        val now = clock()
        for (s in sessions.values.toList()) {
            if (s.closing && (s.pending.isEmpty() || now - s.closingSince > 6000)) { destroy(s); continue }
            val p = s.pending.firstOrNull() ?: continue
            if (now - p.sentAt < p.rto) continue
            if (p.retries >= MAX_RETRIES) { onTimeout(s); continue }
            for (q in s.pending) {
                if (now - q.sentAt >= q.rto) {
                    q.retries++; q.sentAt = now; q.rto = (q.rto * 2).coerceAtMost(MAX_RTO)
                    resend(q)
                }
            }
        }
    }

    private fun onTimeout(s: Session) {
        when (s.kind) {
            Kind.REG -> regFailed(s, "Сервер не отвечает")
            Kind.CALL -> { endCall(s, "Нет ответа от сервера"); destroy(s) }
        }
    }

    private fun newSession(kind: Kind): Session {
        val s = Session(allocCallNo(), kind)
        sessions[s.callNo] = s
        return s
    }

    @Synchronized
    private fun allocCallNo(): Int {
        while (true) {
            val n = Random.nextInt(1, 0x7fff)
            if (!sessions.containsKey(n)) return n
        }
    }

    private fun destroy(s: Session) {
        sessions.remove(s.callNo)
        s.pending.clear()
    }

    private fun resolveServer(): Boolean = try {
        server = InetSocketAddress(InetAddress.getByName(config.host), config.port)
        true
    } catch (e: Exception) {
        setReg(RegState.FAILED, "Не удалось найти сервер ${config.host}")
        scheduleRegister(30)
        false
    }

    private fun send(bytes: ByteArray) {
        val srv = server ?: return
        try {
            socket?.send(DatagramPacket(bytes, bytes.size, srv))
        } catch (e: Exception) {
            log("Ошибка отправки: ${e.message}")
        }
    }

    private fun shutdown() {
        running = false
        tickTimer?.cancel(false)
        regTimer?.cancel(false)
        sessions.clear()
        socket?.close()
        loop.shutdown()
    }

    private fun post(block: () -> Unit) {
        if (loop.isShutdown) return
        try { loop.execute { safe(block) } } catch (_: Exception) { }
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { log("Внутренняя ошибка: $e") }
    }

    private fun log(line: String) = listener.onLog(line)

    companion object {
        private const val INITIAL_RTO = 500L
        private const val MAX_RTO = 2000L
        private const val MAX_RETRIES = 6

        /** Поле MSGCOUNT: (прослушанные shl 8) or новые. Возвращает (новые, прослушанные). */
        fun parseMsgCount(v: Int): Pair<Int, Int> = (v and 0xff) to ((v shr 8) and 0xff)

        fun md5Hex(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).toHex()

        /** true, если порядковый номер a предшествует b (по модулю 256). */
        fun seqBefore(a: Int, b: Int): Boolean { val d = (b - a) and 0xff; return d in 1..127 }
    }
}
