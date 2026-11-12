package com.glaude.iaxphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Запись журнала звонков. */
data class CallRecord(
    val number: String,
    val name: String?,
    val outgoing: Boolean,
    val answered: Boolean,
    val startMs: Long,
    val durationSec: Long,
    /** Uri записи разговора, если разговор записывался. */
    val recording: String? = null,
) {
    val missed get() = !outgoing && !answered
}

data class Contact(val id: Long, val name: String, val number: String)

/** Журнал звонков (последние [MAX] записей, новые сверху). */
object CallHistory {
    private const val PREFS = "history"
    private const val MAX = 200

    fun all(ctx: Context): List<CallRecord> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CallRecord(
                number = o.getString("number"),
                name = o.optString("name").ifEmpty { null },
                outgoing = o.getBoolean("out"),
                answered = o.getBoolean("answered"),
                startMs = o.getLong("start"),
                durationSec = o.getLong("dur"),
                recording = o.optString("rec").ifEmpty { null },
            )
        }
    }

    fun add(ctx: Context, r: CallRecord) = save(ctx, (listOf(r) + all(ctx)).take(MAX))

    fun remove(ctx: Context, r: CallRecord) = save(ctx, all(ctx).filter { it != r })

    /** Привязывает файл записи к звонку, начавшемуся в startMs. */
    fun setRecording(ctx: Context, startMs: Long, uri: String) =
        save(ctx, all(ctx).map { if (it.startMs == startMs) it.copy(recording = uri) else it })

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, items: List<CallRecord>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("number", it.number).put("name", it.name ?: "").put("out", it.outgoing)
                .put("answered", it.answered).put("start", it.startMs).put("dur", it.durationSec)
                .put("rec", it.recording ?: ""))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }
}

/** Контакты приложения (отдельно от системной адресной книги). */
object Contacts {
    private const val PREFS = "contacts"

    fun all(ctx: Context): List<Contact> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Contact(o.getLong("id"), o.getString("name"), o.getString("number"))
        }.sortedBy { it.name.lowercase() }
    }

    /** Добавляет новый контакт (id = 0) или заменяет существующий. */
    fun put(ctx: Context, c: Contact) {
        val list = all(ctx).toMutableList()
        if (c.id == 0L) {
            list += c.copy(id = System.currentTimeMillis())
        } else {
            val i = list.indexOfFirst { it.id == c.id }
            if (i >= 0) list[i] = c else list += c
        }
        save(ctx, list)
    }

    fun remove(ctx: Context, c: Contact) = save(ctx, all(ctx).filter { it.id != c.id })

    fun find(ctx: Context, number: String): Contact? {
        val n = normalize(number)
        if (n.isEmpty()) return null
        return all(ctx).firstOrNull { normalize(it.number) == n }
    }

    private fun normalize(s: String) = s.filter { it.isDigit() || it == '*' || it == '#' }

    private fun save(ctx: Context, items: List<Contact>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("number", it.number)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }
}
