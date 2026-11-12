package com.glaude.iaxphone.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Запись разговора в WAV (8 кГц, 16 бит, моно): голос собеседника смешивается с микрофоном.
 * Темп задаёт поток микрофона (кадр каждые 20 мс), входящие кадры копятся в очереди.
 */
class CallRecorder(val file: File) {
    private val out = BufferedOutputStream(FileOutputStream(file), 32 * 1024)
    private val rx = ArrayDeque<ShortArray>()
    private var dataBytes = 0L
    @Volatile var closed = false; private set

    init { out.write(ByteArray(44)) } // заголовок допишем при закрытии

    /** Звук собеседника (то, что воспроизводится). */
    fun onRx(pcm: ShortArray) {
        synchronized(rx) {
            rx.addLast(pcm)
            while (rx.size > 25) rx.removeFirst() // не копим отставание больше 0,5 с
        }
    }

    /** Кадр микрофона (после mute/усиления); смешивается с очередным кадром собеседника. */
    @Synchronized
    fun onTx(pcm: ShortArray) {
        if (closed) return
        val remote = synchronized(rx) { rx.removeFirstOrNull() }
        val buf = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val r = if (remote != null && i < remote.size) remote[i].toInt() else 0
            val v = (pcm[i] + r).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf[2 * i] = v.toByte()
            buf[2 * i + 1] = (v shr 8).toByte()
        }
        try { out.write(buf); dataBytes += buf.size } catch (_: Exception) { }
    }

    /** Закрывает файл и записывает заголовок WAV. Возвращает длительность, с. */
    @Synchronized
    fun close(): Long {
        if (closed) return dataBytes / (RATE * 2)
        closed = true
        try { out.close() } catch (_: Exception) { }
        RandomAccessFile(file, "rw").use { f ->
            f.seek(0)
            f.write(wavHeader(dataBytes))
        }
        return dataBytes / (RATE * 2)
    }

    companion object {
        private const val RATE = 8000

        fun wavHeader(dataLen: Long): ByteArray {
            val h = ByteArray(44)
            fun str(off: Int, s: String) = s.forEachIndexed { i, c -> h[off + i] = c.code.toByte() }
            fun u32(off: Int, v: Long) { for (i in 0..3) h[off + i] = (v shr (8 * i)).toByte() }
            fun u16(off: Int, v: Int) { h[off] = v.toByte(); h[off + 1] = (v shr 8).toByte() }
            str(0, "RIFF"); u32(4, 36 + dataLen); str(8, "WAVE")
            str(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, 1)
            u32(24, RATE.toLong()); u32(28, RATE * 2L); u16(32, 2); u16(34, 16)
            str(36, "data"); u32(40, dataLen)
            return h
        }
    }
}
