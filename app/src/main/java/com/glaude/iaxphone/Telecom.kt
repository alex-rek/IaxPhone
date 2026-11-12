package com.glaude.iaxphone

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.glaude.iaxphone.iax.CallState
import com.glaude.iaxphone.iax.IaxCall

/**
 * Встраивание в систему вызовов Android в собственном режиме (self-managed ConnectionService).
 * Интерфейс звонка остаётся нашим, но система знает о разговоре: показывает его на часах и в машине,
 * отдаёт кнопки Bluetooth-гарнитуры, сама выбирает звуковой выход и ставит наш вызов
 * на удержание при входящем GSM-звонке.
 *
 * Источник правды — [Phone]; здесь только зеркало его состояния в [Connection].
 */
object TelecomBridge {
    private const val TAG = "TelecomBridge"
    private const val ACCOUNT_ID = "iax"

    /** Connection текущего вызова (создаётся системой асинхронно). */
    private var connection: IaxConnection? = null
    /** Вызов, для которого ждём создания Connection. */
    private var pending: IaxCall? = null

    /** Система управляет звуком (громкая связь, Bluetooth) для текущего вызова. */
    val active get() = connection != null

    /** Android 13+ объявляет «android.software.telecom», более ранние — «connectionservice». */
    @Suppress("DEPRECATION")
    fun isSupported(ctx: Context) =
        ctx.packageManager.hasSystemFeature("android.software.telecom") ||
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)

    private fun handle(ctx: Context) =
        PhoneAccountHandle(ComponentName(ctx, IaxConnectionService::class.java), ACCOUNT_ID)

    private fun tm(ctx: Context) = ctx.getSystemService(TelecomManager::class.java)

    /** Регистрирует или снимает учётную запись в системе по настройке. */
    fun sync(ctx: Context) {
        if (!isSupported(ctx)) return
        try {
            if (BackgroundPrefs.telecom(ctx)) {
                val account = PhoneAccount.builder(handle(ctx), ctx.getString(R.string.app_name))
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP, PhoneAccount.SCHEME_TEL))
                    .build()
                tm(ctx).registerPhoneAccount(account)
            } else {
                tm(ctx).unregisterPhoneAccount(handle(ctx))
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync", e)
        }
    }

    private fun enabled(ctx: Context) = BackgroundPrefs.telecom(ctx) && isSupported(ctx)

    /** Новый исходящий вызов: просим систему создать для него Connection. */
    fun onOutgoing(ctx: Context, call: IaxCall) {
        if (!enabled(ctx)) return
        pending = call
        try {
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(ctx)) }
            tm(ctx).placeCall(Uri.fromParts(PhoneAccount.SCHEME_SIP, call.number, null), extras)
        } catch (e: Exception) {
            Log.w(TAG, "placeCall", e); pending = null
        }
    }

    /** Новый входящий вызов: система покажет его на часах, гарнитуре и т. п. */
    fun onIncoming(ctx: Context, call: IaxCall) {
        if (!enabled(ctx)) return
        pending = call
        try {
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_SIP, call.number, null))
            }
            tm(ctx).addNewIncomingCall(handle(ctx), extras)
        } catch (e: Exception) {
            Log.w(TAG, "addNewIncomingCall", e); pending = null
        }
    }

    /** Состояние вызова изменилось в [Phone]. */
    fun update(call: IaxCall) {
        val c = connection?.takeIf { it.call === call } ?: return
        c.render()
        if (call.state == CallState.ENDED) connection = null
    }

    /** Громкая связь через систему: true, если система управляет маршрутом. */
    fun setSpeaker(on: Boolean): Boolean {
        val c = connection ?: return false
        val supported = c.callAudioState?.supportedRouteMask ?: 0
        @Suppress("DEPRECATION")
        c.setAudioRoute(when {
            on -> CallAudioState.ROUTE_SPEAKER
            supported and CallAudioState.ROUTE_BLUETOOTH != 0 -> CallAudioState.ROUTE_BLUETOOTH
            else -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
        })
        return true
    }

    internal fun create(request: ConnectionRequest?, incoming: Boolean): Connection {
        val call = pending?.takeIf { it.outgoing != incoming && it.state != CallState.ENDED }
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "no call"))
        pending = null
        val c = IaxConnection(call)
        c.setAddress(request?.address ?: Uri.fromParts(PhoneAccount.SCHEME_SIP, call.number, null), TelecomManager.PRESENTATION_ALLOWED)
        call.remoteName?.let { c.setCallerDisplayName(it, TelecomManager.PRESENTATION_ALLOWED) }
        connection?.let { if (it.call !== call) it.destroyQuietly() }
        connection = c
        c.render()
        Phone.onTelecomAttached(call)
        return c
    }

    internal fun failed(incoming: Boolean) {
        val call = pending ?: return
        pending = null
        Log.w(TAG, "система отказала в вызове (incoming=$incoming)")
        // Например, идёт экстренный вызов — занимать линию нельзя.
        Phone.onTelecomRejected(call)
    }

    internal fun onAudioState(c: IaxConnection, state: CallAudioState) {
        if (connection !== c) return
        Phone.onSystemAudioRoute(state.route == CallAudioState.ROUTE_SPEAKER)
    }
}

/** Системное представление одного IAX-вызова. */
class IaxConnection(val call: IaxCall) : Connection() {
    private var lastState: Int = -1

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    fun render() {
        when (call.state) {
            CallState.DIALING, CallState.RINGING -> once(STATE_DIALING) { setDialing() }
            CallState.INCOMING -> once(STATE_RINGING) { setRinging() }
            CallState.ACTIVE -> if (call.onHold) once(STATE_HOLDING) { setOnHold() } else once(STATE_ACTIVE) { setActive() }
            CallState.ENDED -> once(STATE_DISCONNECTED) {
                val reason = call.endReason.orEmpty()
                val code = when {
                    reason.contains("Занято") || reason.contains("занято") -> DisconnectCause.BUSY
                    reason.startsWith("Отклонён") -> DisconnectCause.REJECTED
                    reason.startsWith("Завершён") || reason.startsWith("Вызов отменён") -> DisconnectCause.LOCAL
                    reason.contains("Нет ответа") || reason.contains("Сервер") -> DisconnectCause.ERROR
                    else -> DisconnectCause.REMOTE
                }
                setDisconnected(DisconnectCause(code, reason))
                destroy()
            }
        }
    }

    private inline fun once(state: Int, block: () -> Unit) {
        if (lastState == state) return
        lastState = state
        block()
    }

    fun destroyQuietly() {
        if (lastState == STATE_DISCONNECTED) return
        lastState = STATE_DISCONNECTED
        setDisconnected(DisconnectCause(DisconnectCause.OTHER))
        destroy()
    }

    override fun onAnswer() = Phone.answer()
    override fun onAnswer(videoState: Int) = Phone.answer()
    override fun onReject() = Phone.hangup()
    override fun onDisconnect() = Phone.hangup()
    override fun onAbort() = Phone.hangup()
    override fun onHold() = Phone.setHold(true)
    override fun onUnhold() = Phone.setHold(false)
    override fun onPlayDtmfTone(c: Char) = Phone.dtmf(c)
    override fun onSilence() = Phone.silenceRinger()
    override fun onShowIncomingCallUi() = Unit // уведомление показывает SoftphoneService

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        TelecomBridge.onAudioState(this, state)
    }
}

/** Служба, через которую система создаёт [IaxConnection]. */
class IaxConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        TelecomBridge.create(request, incoming = false)

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        TelecomBridge.create(request, incoming = true)

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        TelecomBridge.failed(incoming = false)

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        TelecomBridge.failed(incoming = true)
}
