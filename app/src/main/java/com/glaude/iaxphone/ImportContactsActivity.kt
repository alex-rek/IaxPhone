package com.glaude.iaxphone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone as CPhone
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.glaude.iaxphone.databinding.ActivityImportBinding
import com.glaude.iaxphone.databinding.ItemRowBinding

/**
 * Выбор номеров из телефонной книги Android: копируются только отмеченные контакты.
 */
class ImportContactsActivity : AppCompatActivity() {

    private data class Entry(val name: String, val number: String, val type: String, val exists: Boolean)

    private lateinit var b: ActivityImportBinding
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private val selected = LinkedHashSet<Entry>()
    private val adapter = Adapter()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) load() else showNoPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivityImportBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            b.toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.content.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.adapter = adapter
        b.list.emptyView = b.empty
        b.list.setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos]) }
        b.search.doAfterTextChanged { filter() }
        b.add.setOnClickListener { importSelected() }
        b.grant.setOnClickListener { permission.launch(Manifest.permission.READ_CONTACTS) }
        updateButton()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) load()
        else permission.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun showNoPermission() {
        b.emptyText.setText(R.string.import_no_permission)
        b.grant.visibility = View.VISIBLE
    }

    private fun load() {
        b.grant.visibility = View.GONE
        val existing = Contacts.all(this).map { normalize(it.number) }.toSet()
        Thread {
            val list = ArrayList<Entry>()
            val seen = HashSet<String>()
            try {
                contentResolver.query(
                    CPhone.CONTENT_URI,
                    arrayOf(CPhone.DISPLAY_NAME_PRIMARY, CPhone.NUMBER, CPhone.TYPE, CPhone.LABEL),
                    null, null, CPhone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val number = c.getString(1)?.trim().orEmpty()
                        if (number.isEmpty()) continue
                        val name = c.getString(0)?.trim().orEmpty().ifEmpty { number }
                        if (!seen.add(name + "|" + normalize(number))) continue // один номер в нескольких аккаунтах
                        val type = CPhone.getTypeLabel(resources, c.getInt(2), c.getString(3)).toString()
                        list += Entry(name, number, type, normalize(number) in existing)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ImportContacts", "query", e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all = list
                b.emptyText.setText(R.string.import_empty)
                filter()
            }
        }.start()
    }

    private fun filter() {
        val q = b.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val digits = normalize(q)
        shown = if (q.isEmpty()) all else all.filter {
            it.name.lowercase().contains(q) || (digits.isNotEmpty() && normalize(it.number).contains(digits))
        }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(e: Entry) {
        if (e.exists) return
        if (!selected.remove(e)) selected += e
        adapter.notifyDataSetChanged()
        updateButton()
    }

    private fun updateButton() {
        b.add.text = getString(R.string.import_add, selected.size)
        b.add.isEnabled = selected.isNotEmpty()
    }

    private fun importSelected() {
        selected.forEach { Contacts.put(this, Contact(0, it.name, it.number)) }
        Toast.makeText(this, getString(R.string.import_done, selected.size), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun normalize(s: String) = s.filter { it.isDigit() || it == '*' || it == '#' }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView?.tag as? ItemRowBinding)
                ?: ItemRowBinding.inflate(layoutInflater, parent, false).also { r ->
                    r.root.tag = r
                    // Вместо кнопки звонка — флажок выбора.
                    val box = CheckBox(this@ImportContactsActivity).apply {
                        isClickable = false
                        isFocusable = false
                        buttonTintList = ContextCompat.getColorStateList(this@ImportContactsActivity, R.color.top_bar_primary)
                    }
                    val lp = r.action.layoutParams
                    val parentLayout = r.action.parent as ViewGroup
                    val idx = parentLayout.indexOfChild(r.action)
                    parentLayout.removeView(r.action)
                    parentLayout.addView(box, idx, lp)
                    box.tag = "check"
                }
            val e = shown[position]
            row.avatar.visibility = View.VISIBLE
            row.kind.visibility = View.GONE
            row.avatar.text = e.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
            row.title.text = e.name
            row.subtitle.text = if (e.exists) "${e.number} · ${getString(R.string.import_exists)}" else "${e.number} · ${e.type}"
            row.meta.text = ""
            val box = row.root.findViewWithTag<CheckBox>("check")
            box.isChecked = e.exists || e in selected
            box.isEnabled = !e.exists
            row.root.alpha = if (e.exists) 0.5f else 1f
            return row.root
        }
    }
}
