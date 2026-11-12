package com.glaude.iaxphone

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Настройки работы в фоне и интеграции с системой («Настройки → Общие»). */
object BackgroundPrefs {
    private const val PREFS = "background"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Регистрироваться на сервере при запуске приложения. */
    fun connectOnStart(ctx: Context) = p(ctx).getBoolean("connectOnStart", true)
    fun setConnectOnStart(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("connectOnStart", on).apply()

    /** Запускаться и регистрироваться после включения телефона (и после обновления приложения). */
    fun startOnBoot(ctx: Context) = p(ctx).getBoolean("startOnBoot", false)
    fun setStartOnBoot(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("startOnBoot", on).apply()

    /** Не давать процессору засыпать, пока есть подключение (надёжнее приём, больше расход батареи). */
    fun keepAwake(ctx: Context) = p(ctx).getBoolean("keepAwake", false)
    fun setKeepAwake(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("keepAwake", on).apply()

    /** Встраивание в систему вызовов Android (Telecom, собственный режим). */
    fun telecom(ctx: Context) = p(ctx).getBoolean("telecom", false)
    fun setTelecom(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("telecom", on).apply()

    /** Приложение исключено из оптимизации батареи. */
    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Системный запрос «Разрешить работу в фоне без ограничений». */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))

    /** Можно ли показывать входящий вызов поверх экрана блокировки (Android 14+ спрашивает отдельно). */
    fun canUseFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun fullScreenSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))
}

/** Автозапуск после включения телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!BackgroundPrefs.startOnBoot(context) || !Account.load(context).isComplete) return
        try {
            SoftphoneService.start(context)
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "start", e)
        }
    }
}
