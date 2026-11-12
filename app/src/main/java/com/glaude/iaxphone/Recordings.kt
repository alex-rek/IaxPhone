package com.glaude.iaxphone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файлы записей разговоров. Запись идёт во временный файл, после звонка он переносится
 * в общую папку: «Recordings/IaxPhone» (Android 12+) или «Music/IaxPhone».
 */
object Recordings {
    private const val FOLDER = "IaxPhone"

    fun newTempFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "rec").apply { mkdirs() }
        return File(dir, "rec_${System.currentTimeMillis()}.wav")
    }

    /** Папка, которую видит пользователь (для подсказки в настройках). */
    fun folderLabel(): String =
        (if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC) + "/" + FOLDER

    fun fileName(number: String, outgoing: Boolean, startMs: Long): String {
        val time = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startMs))
        val safe = number.map { if (it.isLetterOrDigit() || it == '+') it else '_' }.joinToString("").take(32)
        return "${time}_${if (outgoing) "out" else "in"}_$safe.wav"
    }

    /** Переносит временный файл в общую папку; возвращает Uri для воспроизведения или null. */
    fun publish(ctx: Context, tmp: File, name: String): Uri? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, folderLabel())
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            if (uri != null) {
                cr.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } else {
            // До Android 10 общая папка требует разрешения на запись — храним в папке приложения.
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
            val dst = File(dir, name)
            tmp.copyTo(dst, overwrite = true)
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dst)
        }
    } catch (e: Exception) {
        android.util.Log.w("Recordings", "publish", e)
        null
    } finally {
        tmp.delete()
    }

    fun playIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/x-wav").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun delete(ctx: Context, uri: Uri) {
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
    }
}
