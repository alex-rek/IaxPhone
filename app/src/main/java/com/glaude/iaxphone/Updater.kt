package com.glaude.iaxphone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Описание доступного релиза на GitHub. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

/** Проверка обновлений через GitHub Releases и загрузка APK. */
object Updater {
    private const val REPO = "alex-rek/IaxPhone"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "updates"
    private const val AUTO_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    fun autoCheckEnabled(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    /** Пора ли автоматически проверить обновления (включено и прошло достаточно времени). */
    fun autoCheckDue(ctx: Context): Boolean =
        autoCheckEnabled(ctx) && System.currentTimeMillis() - prefs(ctx).getLong("last", 0) > AUTO_INTERVAL_MS

    /**
     * Запрашивает последний релиз. Результат в главном потоке:
     * UpdateInfo — есть более новая версия, null — установлена последняя.
     */
    fun check(ctx: Context, callback: (Result<UpdateInfo?>) -> Unit) {
        val app = ctx.applicationContext
        Thread({
            val result = runCatching {
                val json = JSONObject(httpGet(API))
                prefs(app).edit().putLong("last", System.currentTimeMillis()).apply()
                val version = json.getString("tag_name").removePrefix("v")
                if (!isNewer(version, currentVersion(app))) return@runCatching null
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                    ?: throw IOException("В релизе $version нет APK-файла")
                UpdateInfo(
                    version = version,
                    notes = json.optString("body"),
                    apkUrl = apk.getString("browser_download_url"),
                    apkSize = apk.optLong("size"),
                    pageUrl = json.optString("html_url"),
                )
            }
            main.post { callback(result) }
        }, "update-check").start()
    }

    /** Сравнение версий вида 1.2.3 (суффиксы после «-» игнорируются). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Скачивает APK в кэш; прогресс 0..100 (или -1, если размер неизвестен). */
    fun download(
        ctx: Context,
        info: UpdateInfo,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        Thread({
            val result = runCatching {
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "IaxPhone-${info.version}.apk")
                val conn = open(info.apkUrl)
                try {
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -2
                            while (true) {
                                if (isCancelled()) throw IOException("Загрузка отменена")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct) } }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                file
            }
            main.post { onDone(result) }
        }, "update-download").start()
    }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "IaxPhone-Android")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException(if (code == 404) "Релизы не найдены" else "Ошибка сервера GitHub: HTTP $code")
        }
        return conn
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Диалоги обновления: предложение, загрузка с прогрессом, запуск установки. */
class UpdateUi(private val activity: AppCompatActivity) {
    private var pendingInstall: File? = null

    fun showAvailable(info: UpdateInfo) {
        val notes = info.notes.lineSequence()
            .map { it.replace("**", "").replace("`", "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SHA-256") }
            .take(12).joinToString("\n")
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available, info.version))
            .setMessage(activity.getString(R.string.update_current, Updater.currentVersion(activity)) +
                if (notes.isNotEmpty()) "\n\n$notes" else "")
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(R.string.update) { _, _ -> download(info) }
            .show()
    }

    private fun download(info: UpdateInfo) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply { text = activity.getString(R.string.update_downloading) }
        val bar = LinearProgressIndicator(activity).apply { isIndeterminate = true }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(text)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad / 2 })
        }
        var cancelled = false
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, info.version))
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()
        Updater.download(activity, info, { cancelled },
            onProgress = { pct ->
                if (pct >= 0) {
                    if (bar.isIndeterminate) { bar.isIndeterminate = false; bar.max = 100 }
                    bar.setProgressCompat(pct, true)
                    text.text = activity.getString(R.string.update_progress, pct)
                }
            },
            onDone = { res ->
                if (dialog.isShowing) dialog.dismiss()
                res.onSuccess { install(it) }
                    .onFailure { if (!cancelled) error(it.message ?: it.toString()) }
            })
    }

    /** Запускает системный установщик; при необходимости просит разрешить установку из этого приложения. */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.update_allow_install)
                .setNegativeButton(R.string.cancel) { _, _ -> pendingInstall = null }
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)))
                }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            error(e.message ?: "Установщик не найден")
        }
    }

    /** Вызывать из onResume: продолжает установку после выдачи разрешения. */
    fun onResume() {
        val file = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            install(file)
        }
    }

    private fun error(message: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_error)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
