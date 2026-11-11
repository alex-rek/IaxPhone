package com.glaude.iaxphone.iax

import java.io.ByteArrayOutputStream

/** Разобранный список информационных элементов из полезной нагрузки IAX-кадра. */
class InfoElements private constructor(private val items: List<Pair<Int, ByteArray>>) {

    fun has(type: Int) = items.any { it.first == type }
    fun bytes(type: Int): ByteArray? = items.firstOrNull { it.first == type }?.second
    fun str(type: Int): String? = bytes(type)?.toString(Charsets.UTF_8)

    fun u8(type: Int): Int? = bytes(type)?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xff }

    fun u16(type: Int): Int? = bytes(type)?.takeIf { it.size >= 2 }?.let {
        ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff)
    }

    fun u32(type: Int): Long? = bytes(type)?.takeIf { it.size >= 4 }?.let { readU32(it, 0) }

    /** FORMAT2/CAPABILITY2: 1 байт версии + 8 байт маски. */
    fun u64v(type: Int): Long? = bytes(type)?.takeIf { it.size >= 9 }?.let {
        var v = 0L
        for (i in 1..8) v = (v shl 8) or (it[i].toLong() and 0xff)
        v
    }

    fun format(): Long? = u64v(Ie.FORMAT2) ?: u32(Ie.FORMAT)
    fun capability(): Long? = u64v(Ie.CAPABILITY2) ?: u32(Ie.CAPABILITY)

    override fun toString(): String = items.joinToString(", ", "{", "}") { (t, v) ->
        val shown = if (v.all { it in 0x20..0x7e }) "\"" + String(v) + "\"" else v.toHex()
        "0x%02x=%s".format(t, shown)
    }

    companion object {
        fun parse(data: ByteArray): InfoElements {
            val list = ArrayList<Pair<Int, ByteArray>>()
            var p = 0
            while (p + 2 <= data.size) {
                val type = data[p].toInt() and 0xff
                val len = data[p + 1].toInt() and 0xff
                if (p + 2 + len > data.size) break
                list += type to data.copyOfRange(p + 2, p + 2 + len)
                p += 2 + len
            }
            return InfoElements(list)
        }
    }
}

/** Построитель полезной нагрузки из IE. */
class IeBuilder {
    private val out = ByteArrayOutputStream()

    fun raw(type: Int, value: ByteArray) = apply {
        require(value.size <= 255) { "IE too long" }
        out.write(type); out.write(value.size); out.write(value)
    }

    fun str(type: Int, value: String?) = apply { if (value != null) raw(type, value.toByteArray(Charsets.UTF_8)) }
    fun empty(type: Int) = raw(type, ByteArray(0))
    fun u8(type: Int, v: Int) = raw(type, byteArrayOf(v.toByte()))
    fun u16(type: Int, v: Int) = raw(type, byteArrayOf((v shr 8).toByte(), v.toByte()))
    fun u32(type: Int, v: Long) = raw(type, ByteArray(4).also { writeU32(it, 0, v) })
    fun u64v(type: Int, v: Long) = raw(type, ByteArray(9).also { b ->
        for (i in 0 until 8) b[8 - i] = (v shr (8 * i)).toByte()
    })

    fun build(): ByteArray = out.toByteArray()
}

internal fun readU32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
        ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

internal fun writeU32(b: ByteArray, off: Int, v: Long) {
    b[off] = (v shr 24).toByte(); b[off + 1] = (v shr 16).toByte()
    b[off + 2] = (v shr 8).toByte(); b[off + 3] = v.toByte()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
