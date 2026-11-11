package com.glaude.iaxphone

import android.content.Context
import com.glaude.iaxphone.iax.IAX_DEFAULT_PORT
import com.glaude.iaxphone.iax.IaxConfig

/** Настройки учётной записи (SharedPreferences). */
data class Account(
    val host: String = "",
    val port: Int = IAX_DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
    val callerId: String = "",
    val lastNumber: String = "",
    val voicemail: String = DEFAULT_VOICEMAIL,
) {
    val isComplete get() = host.isNotBlank() && username.isNotBlank()

    fun toConfig() = IaxConfig(
        host = host.trim(), port = port, username = username.trim(), password = password,
        callerId = callerId.trim(), callerName = callerId.trim(),
    )

    companion object {
        private const val PREFS = "account"
        const val DEFAULT_VOICEMAIL = "*97"

        fun load(ctx: Context): Account {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Account(
                host = p.getString("host", "") ?: "",
                port = p.getInt("port", IAX_DEFAULT_PORT),
                username = p.getString("username", "") ?: "",
                password = p.getString("password", "") ?: "",
                callerId = p.getString("callerId", "") ?: "",
                lastNumber = p.getString("lastNumber", "") ?: "",
                voicemail = p.getString("voicemail", DEFAULT_VOICEMAIL) ?: DEFAULT_VOICEMAIL,
            )
        }

        /** Подключаться автоматически при запуске приложения (пользователь не отключался сам). */
        fun autoConnect(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoConnect", false)

        fun setAutoConnect(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoConnect", on).apply()

        fun save(ctx: Context, a: Account) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("host", a.host).putInt("port", a.port)
                .putString("username", a.username).putString("password", a.password)
                .putString("callerId", a.callerId).putString("lastNumber", a.lastNumber)
                .putString("voicemail", a.voicemail)
                .apply()
        }
    }
}
