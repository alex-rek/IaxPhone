package com.glaude.iaxphone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.glaude.iaxphone.databinding.ActivityMainBinding
import com.glaude.iaxphone.databinding.DialogContactBinding
import com.glaude.iaxphone.databinding.ItemRowBinding
import com.glaude.iaxphone.databinding.KeypadBinding
import com.glaude.iaxphone.iax.CallState
import com.glaude.iaxphone.iax.Codec
import com.glaude.iaxphone.iax.IaxCall
import com.glaude.iaxphone.iax.RegState
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), Phone.Observer {

    private lateinit var b: ActivityMainBinding
    private var page = PAGE_DIALER
    private var pendingAnswer = false
    private var pendingDial: String? = null

    /** Имя собеседника для экрана разговора, вычисляется один раз на вызов. */
    private var shownCall: IaxCall? = null
    private var shownCallName = ""

    private lateinit var updateUi: UpdateUi
    private val journalAdapter = JournalAdapter()
    private val contactsAdapter = ContactsAdapter()

    private val ticker = object : Runnable {
        override fun run() { renderCall(); b.root.postDelayed(this, 1000) }
    }

    private val backToDialer = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selectPage(PAGE_DIALER)
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == false) {
            snack(getString(R.string.need_mic))
            pendingAnswer = false; pendingDial = null
            return@registerForActivityResult
        }
        if (pendingAnswer) { pendingAnswer = false; Phone.answer() }
        pendingDial?.let { pendingDial = null; dial(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Phone.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.tabs.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.pages.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            b.callPanel.root.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        onBackPressedDispatcher.addCallback(this, backToDialer)

        setupTabs(savedInstanceState?.getInt(KEY_PAGE) ?: PAGE_DIALER)
        setupDialer()
        setupJournal()
        setupContacts()
        setupSettings()
        setupCallPanel()

        requestPermissionsIfNeeded()
        updateUi = UpdateUi(this)
        if (savedInstanceState == null && Updater.autoCheckDue(this)) {
            // Тихая проверка: сообщаем только о найденной версии, ошибки сети не показываем.
            Updater.check(this) { res ->
                val info = res.getOrNull() ?: return@check
                if (!isFinishing && !isDestroyed && Phone.call == null) updateUi.showAvailable(info)
            }
        }
        if (savedInstanceState == null && !Phone.connected && Account.autoConnect(this) && Account.load(this).isComplete) {
            SoftphoneService.start(this)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Phone.addObserver(this)
        render()
        refreshLists()
        b.root.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        updateUi.onResume()
    }

    override fun onStop() {
        Phone.removeObserver(this)
        b.root.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, page)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == SoftphoneService.ACTION_ANSWER) answerWithPermission()
    }

    // ------------------------------------------------------------------ вкладки

    private fun setupTabs(initial: Int) {
        listOf(
            R.drawable.ic_dialpad to R.string.tab_dialer,
            R.drawable.ic_call to R.string.tab_journal,
            R.drawable.ic_person to R.string.tab_contacts,
            R.drawable.ic_settings to R.string.tab_settings,
        ).forEach { (icon, title) ->
            b.tabs.addTab(b.tabs.newTab().setIcon(icon).setContentDescription(title))
        }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        selectPage(initial)
        showPage(initial)
    }

    private fun selectPage(p: Int) { b.tabs.getTabAt(p)?.select() }

    private fun showPage(p: Int) {
        page = p
        b.pageDialer.root.visibility = if (p == PAGE_DIALER) View.VISIBLE else View.GONE
        b.pageJournal.root.visibility = if (p == PAGE_JOURNAL) View.VISIBLE else View.GONE
        b.pageContacts.root.visibility = if (p == PAGE_CONTACTS) View.VISIBLE else View.GONE
        b.pageSettings.root.visibility = if (p == PAGE_SETTINGS) View.VISIBLE else View.GONE
        backToDialer.isEnabled = p != PAGE_DIALER
        refreshLists()
    }

    // ------------------------------------------------------------------ звонилка

    private fun setupDialer() {
        val d = b.pageDialer
        d.number.showSoftInputOnFocus = false
        d.keypad.k0.text = keyLabel("0", "+")
        forEachKey(d.keypad) { key, c -> key.setOnClickListener { insertDigit(c) } }
        d.keypad.k0.setOnLongClickListener { insertDigit('+'); true }
        // Долгое нажатие «1» — голосовая почта, как в обычной звонилке.
        d.keypad.k1.setOnLongClickListener { dial(Account.load(this).voicemail); true }
        d.voicemail.setOnClickListener { dial(Account.load(this).voicemail) }
        d.backspace.setOnClickListener {
            val e = d.number
            val start = e.selectionStart.coerceAtLeast(0)
            val end = e.selectionEnd.coerceAtLeast(0)
            when {
                start != end -> e.text.delete(minOf(start, end), maxOf(start, end))
                start > 0 -> e.text.delete(start - 1, start)
            }
        }
        d.backspace.setOnLongClickListener { d.number.text.clear(); true }
        d.number.doAfterTextChanged { s ->
            d.numberName.text = s?.toString()?.let { Contacts.find(this, it)?.name }.orEmpty()
        }
        d.call.setOnClickListener {
            val n = d.number.text.toString().trim()
            if (n.isEmpty()) {
                // Пустой номер: подставляем последний набранный, как в обычной звонилке.
                d.number.setText(Account.load(this).lastNumber)
                d.number.setSelection(d.number.length())
            } else {
                dial(n)
            }
        }
        d.statusRow.setOnClickListener { startActivity(Intent(this, ConnectionActivity::class.java)) }
    }

    private fun insertDigit(c: Char) {
        val e = b.pageDialer.number
        val start = e.selectionStart.coerceAtLeast(0)
        val end = e.selectionEnd.coerceAtLeast(0)
        e.text.replace(minOf(start, end), maxOf(start, end), c.toString())
    }

    /** Звонок на номер с любой вкладки. */
    private fun dial(number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        if (!hasMic()) { pendingDial = n; requestPermissionsIfNeeded(); return }
        if (!Phone.dial(n)) {
            if (!Phone.connected) snackNotConnected()
            return
        }
        Account.save(this, Account.load(this).copy(lastNumber = n))
        b.pageDialer.number.text.clear()
    }

    // ------------------------------------------------------------------ журнал

    private fun setupJournal() {
        val j = b.pageJournal
        j.list.adapter = journalAdapter
        j.list.emptyView = j.empty
        j.list.setOnItemClickListener { _, view, pos, _ -> journalMenu(view, journalAdapter.getItem(pos)) }
        j.clearJournal.setOnClickListener {
            if (journalAdapter.count == 0) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.clear_journal_q)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ -> CallHistory.clear(this); refreshLists() }
                .show()
        }
    }

    private fun journalMenu(anchor: View, r: CallRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.call)
        menu.menu.add(0, 2, 1, R.string.to_dialer)
        if (Contacts.find(this, r.number) == null) menu.menu.add(0, 3, 2, R.string.add_to_contacts)
        menu.menu.add(0, 4, 3, R.string.delete)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> dial(r.number)
                2 -> {
                    b.pageDialer.number.setText(r.number)
                    b.pageDialer.number.setSelection(r.number.length)
                    selectPage(PAGE_DIALER)
                }
                3 -> editContact(Contact(0, r.name.orEmpty(), r.number))
                4 -> { CallHistory.remove(this, r); refreshLists() }
            }
            true
        }
        menu.show()
    }

    private inner class JournalAdapter : BaseAdapter() {
        var items: List<CallRecord> = emptyList()
        var names: Map<String, String> = emptyMap()
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].startMs

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rowBinding(convertView, parent)
            val r = items[position]
            val name = names[r.number] ?: r.name?.takeIf { it.isNotBlank() }
            row.avatar.visibility = View.INVISIBLE
            row.kind.visibility = View.VISIBLE
            val (icon, color) = when {
                r.missed -> R.drawable.ic_call_missed to R.color.red
                r.outgoing -> R.drawable.ic_call_made to R.color.top_bar_primary
                else -> R.drawable.ic_call_received to R.color.green
            }
            row.kind.setImageResource(icon)
            row.kind.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, color))
            row.title.text = name ?: r.number
            row.title.setTextColor(ContextCompat.getColor(this@MainActivity, if (r.missed) R.color.red else R.color.key_text))
            val status = when {
                r.missed -> getString(R.string.journal_missed)
                !r.answered -> getString(R.string.journal_not_answered)
                else -> DateUtils.formatElapsedTime(r.durationSec)
            }
            row.subtitle.text = if (name != null) "${r.number} · $status" else status
            row.meta.text = if (DateUtils.isToday(r.startMs)) timeFmt.format(Date(r.startMs)) else dateFmt.format(Date(r.startMs))
            row.action.setOnClickListener { dial(r.number) }
            return row.root
        }
    }

    // ------------------------------------------------------------------ контакты

    private fun setupContacts() {
        val c = b.pageContacts
        c.list.adapter = contactsAdapter
        c.list.emptyView = c.empty
        c.addContact.setOnClickListener { editContact(Contact(0, "", "")) }
        c.list.setOnItemClickListener { _, view, pos, _ -> contactMenu(view, contactsAdapter.getItem(pos)) }
    }

    private fun contactMenu(anchor: View, contact: Contact) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.call)
        menu.menu.add(0, 2, 1, R.string.edit)
        menu.menu.add(0, 3, 2, R.string.delete)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> dial(contact.number)
                2 -> editContact(contact)
                3 -> MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.delete_contact_q, contact.name))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ -> Contacts.remove(this, contact); refreshLists() }
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun editContact(contact: Contact) {
        val v = DialogContactBinding.inflate(layoutInflater)
        v.name.setText(contact.name)
        v.number.setText(contact.number)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (contact.id == 0L) R.string.contact_add else R.string.contact_edit)
            .setView(v.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .show()
        // Свой обработчик, чтобы диалог не закрывался при пустом номере.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val number = v.number.text.toString().trim()
            if (number.isEmpty()) { v.number.error = getString(R.string.required); return@setOnClickListener }
            val name = v.name.text.toString().trim().ifEmpty { number }
            Contacts.put(this, contact.copy(name = name, number = number))
            dialog.dismiss()
            refreshLists()
            if (page != PAGE_CONTACTS) snack(name)
        }
    }

    private inner class ContactsAdapter : BaseAdapter() {
        var items: List<Contact> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rowBinding(convertView, parent)
            val c = items[position]
            row.avatar.visibility = View.VISIBLE
            row.kind.visibility = View.GONE
            row.avatar.text = c.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
            row.title.text = c.name
            row.title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.key_text))
            row.subtitle.text = c.number
            row.meta.text = ""
            row.action.setOnClickListener { dial(c.number) }
            return row.root
        }
    }

    private fun rowBinding(convertView: View?, parent: ViewGroup): ItemRowBinding =
        (convertView?.tag as? ItemRowBinding)
            ?: ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false).also { it.root.tag = it }

    private fun refreshLists() {
        if (page == PAGE_JOURNAL) {
            journalAdapter.items = CallHistory.all(this)
            journalAdapter.names = Contacts.all(this).associate { it.number to it.name }
            journalAdapter.notifyDataSetChanged()
        }
        if (page == PAGE_CONTACTS) {
            contactsAdapter.items = Contacts.all(this)
            contactsAdapter.notifyDataSetChanged()
        }
        b.pageDialer.numberName.text = Contacts.find(this, b.pageDialer.number.text.toString())?.name.orEmpty()
    }

    // ------------------------------------------------------------------ настройки

    private fun setupSettings() {
        val s = b.pageSettings
        s.rowGeneral.icon.setImageResource(R.drawable.ic_palette)
        s.rowGeneral.title.setText(R.string.general)
        s.rowGeneral.root.setOnClickListener { startActivity(Intent(this, GeneralActivity::class.java)) }
        s.rowConnection.icon.setImageResource(R.drawable.ic_cloud)
        s.rowConnection.title.setText(R.string.connection)
        s.rowConnection.root.setOnClickListener { startActivity(Intent(this, ConnectionActivity::class.java)) }
        s.rowLogs.icon.setImageResource(R.drawable.ic_list)
        s.rowLogs.title.setText(R.string.logs)
        s.rowLogs.summary.setText(R.string.logs_summary)
        s.rowLogs.root.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
    }

    // ------------------------------------------------------------------ экран разговора

    private fun setupCallPanel() {
        val c = b.callPanel
        c.hangup.setOnClickListener { Phone.hangup() }
        c.answer.setOnClickListener { answerWithPermission() }
        c.mute.setOnClickListener { Phone.toggleMute() }
        c.speaker.setOnClickListener { Phone.toggleSpeaker() }
        c.keypadToggle.setOnClickListener {
            c.callKeypad.root.visibility = if (c.keypadToggle.isChecked) View.VISIBLE else View.GONE
        }
        val keyBg = ColorStateList.valueOf(0x26FFFFFF)
        forEachKey(c.callKeypad) { key, ch ->
            key.backgroundTintList = keyBg
            key.setTextColor(Color.WHITE)
            key.setOnClickListener {
                if (Phone.call?.state != CallState.ACTIVE) return@setOnClickListener
                Phone.dtmf(ch)
                c.dtmfSent.text = (c.dtmfSent.text.toString() + ch).takeLast(16)
            }
        }
    }

    private fun answerWithPermission() {
        if (hasMic()) Phone.answer() else { pendingAnswer = true; requestPermissionsIfNeeded() }
    }

    // ------------------------------------------------------------------ отрисовка

    override fun onPhoneChanged() {
        render()
        refreshLists()
    }

    private fun render() {
        val d = b.pageDialer
        d.regStatus.text = statusLine()
        d.regDot.background.setTint(ContextCompat.getColor(this, regColor()))
        d.voicemail.visibility = if (Phone.connected && Phone.vmNew > 0) View.VISIBLE else View.GONE
        if (Phone.vmNew > 0) d.voicemail.text = getString(R.string.voicemail_new, Phone.vmNew)
        b.pageSettings.rowConnection.summary.text = statusLine()
        b.pageSettings.rowGeneral.summary.text =
            getString(R.string.theme) + ": " + getString(ThemePref.get(this).title).lowercase()
        renderCall()
    }

    private fun statusLine(): String {
        val acc = Account.load(this)
        if (!Phone.connected) {
            return if (acc.isComplete) "${acc.username}@${acc.host} · ${Phone.regDetail}" else getString(R.string.not_configured)
        }
        return "${acc.username}@${acc.host} · ${Phone.regDetail}"
    }

    private fun regColor() = when (Phone.regState) {
        RegState.REGISTERED -> R.color.status_ok
        RegState.REGISTERING -> R.color.status_wait
        RegState.FAILED -> R.color.status_error
        RegState.OFF -> R.color.status_off
    }

    private fun renderCall() {
        val c = b.callPanel
        val call = Phone.call
        if (call == null) {
            c.root.visibility = View.GONE
            shownCall = null
            return
        }
        if (call !== shownCall) {
            shownCall = call
            shownCallName = Contacts.find(this, call.number)?.name
                ?: call.remoteName?.takeIf { it.isNotBlank() } ?: call.number
            c.dtmfSent.text = ""
            c.keypadToggle.isChecked = false
            c.callKeypad.root.visibility = View.GONE
        }
        c.root.visibility = View.VISIBLE
        val state = call.state
        c.callName.text = shownCallName
        c.callNumber.text = if (shownCallName != call.number) call.number else ""
        c.callStatus.text = when (state) {
            CallState.DIALING -> getString(R.string.state_dialing)
            CallState.RINGING -> getString(R.string.state_ringing)
            CallState.INCOMING -> getString(R.string.state_incoming)
            CallState.ACTIVE -> {
                val sec = (System.currentTimeMillis() - call.answeredAtMs) / 1000
                getString(R.string.state_active, sec / 60, sec % 60, Codec.name(call.format))
            }
            CallState.ENDED -> getString(R.string.state_ended, call.endReason ?: "")
        }
        c.answer.visibility = if (state == CallState.INCOMING) View.VISIBLE else View.GONE
        c.hangup.visibility = if (state != CallState.ENDED) View.VISIBLE else View.INVISIBLE
        c.inCallControls.visibility = if (state == CallState.ACTIVE) View.VISIBLE else View.INVISIBLE
        if (state != CallState.ACTIVE && c.callKeypad.root.visibility == View.VISIBLE) {
            c.keypadToggle.isChecked = false
            c.callKeypad.root.visibility = View.GONE
        }
        c.mute.isChecked = Phone.muted
        c.speaker.isChecked = Phone.speaker
    }

    // ------------------------------------------------------------------ вспомогательное

    private fun forEachKey(k: KeypadBinding, action: (MaterialButton, Char) -> Unit) {
        listOf(
            k.k1 to '1', k.k2 to '2', k.k3 to '3', k.k4 to '4', k.k5 to '5', k.k6 to '6',
            k.k7 to '7', k.k8 to '8', k.k9 to '9', k.kStar to '*', k.k0 to '0', k.kHash to '#',
        ).forEach { (key, c) -> action(key, c) }
    }

    /** Цифра с маленькой подписью снизу (например, «0» и «+»). */
    private fun keyLabel(main: String, sub: String): CharSequence =
        SpannableStringBuilder(main).append("\n").also { sb ->
            val start = sb.length
            sb.append(sub)
            sb.setSpan(RelativeSizeSpan(0.5f), start - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun snack(text: String) = Snackbar.make(b.pages, text, Snackbar.LENGTH_SHORT).show()

    private fun snackNotConnected() =
        Snackbar.make(b.pages, R.string.not_connected, Snackbar.LENGTH_LONG)
            .setAction(R.string.setup) { startActivity(Intent(this, ConnectionActivity::class.java)) }
            .show()

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    companion object {
        private const val KEY_PAGE = "page"
        const val PAGE_DIALER = 0
        const val PAGE_JOURNAL = 1
        const val PAGE_CONTACTS = 2
        const val PAGE_SETTINGS = 3
    }
}
