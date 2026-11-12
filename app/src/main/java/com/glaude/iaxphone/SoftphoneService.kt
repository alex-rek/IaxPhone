package com.glaude.iaxphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glaude.iaxphone.iax.CallState
import com.glaude.iaxphone.iax.RegState

/**
 * Foreground-сервис: держит сокет и регистрацию живыми в фоне, показывает статус
 * и уведомление о входящем вызове.
 */
class SoftphoneService : Service(), Phone.Observer {

    private var micActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var shownIncoming: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Phone.init(this)
        createChannels()
        Phone.addObserver(this)
        Phone.microphoneHook = { need -> setMicrophone(need) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                promote(false)
                val acc = Account.load(this)
                if (acc.isComplete) Phone.connect(acc.toConfig()) else stopSelf()
            }
            ACTION_STOP -> {
                Phone.disconnect { stopSelf() }
            }
            ACTION_DECLINE, ACTION_HANGUP -> Phone.hangup()
            ACTION_REFRESH -> updateIdleLock()
            else -> {
                // Перезапуск системой без интента: восстанавливаем подключение.
                promote(false)
                val acc = Account.load(this)
                if (!Phone.connected && acc.isComplete) Phone.connect(acc.toConfig())
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        idleLock?.let { if (it.isHeld) it.release() }
        idleLock = null
        Phone.removeObserver(this)
        Phone.microphoneHook = null
        releaseWakeLock()
        nm().cancel(NOTIF_INCOMING)
        super.onDestroy()
    }

    override fun onPhoneChanged() {
        updateIdleLock()
        if (!Phone.connected) return
        nm().notify(NOTIF_STATUS, statusNotification())
        val call = Phone.call
        if (call != null && call.state == CallState.INCOMING) {
            if (shownIncoming !== call) {
                shownIncoming = call
                nm().notify(NOTIF_INCOMING, incomingNotification(call.number, call.remoteName))
            }
        } else if (shownIncoming != null) {
            shownIncoming = null
            nm().cancel(NOTIF_INCOMING)
        }
    }

    /** Повышает/понижает тип foreground-сервиса: микрофон нужен только во время разговора. */
    private fun setMicrophone(need: Boolean) {
        if (need == micActive) return
        micActive = need
        promote(need)
        if (need) acquireWakeLock() else releaseWakeLock()
    }

    private fun promote(microphone: Boolean) {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        // С системой вызовов разговор считается звонком: Android разрешает микрофон и из фона.
        if (microphone && BackgroundPrefs.telecom(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        try {
            ServiceCompat.startForeground(this, NOTIF_STATUS, statusNotification(), type)
        } catch (e: Exception) {
            // Например, микрофон запрошен, когда приложение в фоне.
            android.util.Log.w("SoftphoneService", "startForeground", e)
        }
    }

    /** «Не давать процессору засыпать»: держим процессор, пока есть подключение. */
    private var idleLock: PowerManager.WakeLock? = null

    private fun updateIdleLock() {
        val want = Phone.connected && BackgroundPrefs.keepAwake(this)
        val held = idleLock?.isHeld == true
        if (want && !held) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            idleLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IaxPhone:idle").apply {
                setReferenceCounted(false); acquire()
            }
        } else if (!want && held) {
            idleLock?.release(); idleLock = null
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IaxPhone:call").apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ------------------------------------------------------------------ уведомления

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        nm().createNotificationChannel(NotificationChannel(CH_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW))
        nm().createNotificationChannel(NotificationChannel(CH_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // мелодию играет само приложение
            enableVibration(true)
        })
    }

    private fun openAppIntent(action: String? = null): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            this.action = action ?: Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, (action ?: "main").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, SoftphoneService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun statusNotification(): Notification {
        val call = Phone.call
        val text = when {
            call != null && call.state != CallState.ENDED -> when (call.state) {
                CallState.ACTIVE -> getString(R.string.notif_in_call, call.number)
                CallState.INCOMING -> getString(R.string.notif_incoming, call.number)
                else -> getString(R.string.notif_dialing, call.number)
            }
            Phone.regState == RegState.REGISTERED -> getString(R.string.notif_registered)
            else -> Phone.regDetail
        }
        val b = NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (call != null && (call.state == CallState.ACTIVE || call.state == CallState.DIALING || call.state == CallState.RINGING)) {
            b.addAction(0, getString(R.string.hangup), serviceIntent(ACTION_HANGUP))
        } else {
            b.addAction(0, getString(R.string.disconnect), serviceIntent(ACTION_STOP))
        }
        return b.build()
    }

    private fun incomingNotification(number: String, name: String?): Notification =
        NotificationCompat.Builder(this, CH_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.incoming_call))
            .setContentText(if (name.isNullOrBlank()) number else "$name ($number)")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setFullScreenIntent(openAppIntent(), true)
            .addAction(0, getString(R.string.decline), serviceIntent(ACTION_DECLINE))
            .addAction(0, getString(R.string.answer), openAppIntent(ACTION_ANSWER))
            .build()

    companion object {
        const val ACTION_START = "com.glaude.iaxphone.START"
        const val ACTION_STOP = "com.glaude.iaxphone.STOP"
        const val ACTION_DECLINE = "com.glaude.iaxphone.DECLINE"
        const val ACTION_HANGUP = "com.glaude.iaxphone.HANGUP"
        const val ACTION_ANSWER = "com.glaude.iaxphone.ANSWER"
        const val ACTION_REFRESH = "com.glaude.iaxphone.REFRESH"
        private const val CH_STATUS = "status"
        private const val CH_CALLS = "calls"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_INCOMING = 2

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, SoftphoneService::class.java).setAction(ACTION_START))

        /** Перечитать настройки работы в фоне (только если сервис уже работает). */
        fun refresh(ctx: Context) {
            if (Phone.connected) ctx.startService(Intent(ctx, SoftphoneService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, SoftphoneService::class.java).setAction(ACTION_STOP))
    }
}
