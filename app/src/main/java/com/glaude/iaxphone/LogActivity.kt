package com.glaude.iaxphone

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glaude.iaxphone.databinding.ActivityLogBinding

/** Настройки → Логи: журнал протокола IAX2 в реальном времени. */
class LogActivity : AppCompatActivity(), Phone.Observer {

    private lateinit var b: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Phone.init(this)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.scroll.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.log)
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.copy -> copyLog()
                R.id.clear -> { Phone.clearLog(); showAll() }
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        Phone.addObserver(this)
        showAll()
    }

    override fun onStop() {
        Phone.removeObserver(this)
        super.onStop()
    }

    override fun onPhoneChanged() {}

    override fun onLogLine(line: String) {
        val atBottom = !b.scroll.canScrollVertically(1)
        if (b.log.tag == EMPTY) { b.log.text = line; b.log.tag = null } else b.log.append("\n$line")
        // Держим в окне столько же строк, сколько хранит Phone.
        if (b.log.lineCount > 400) b.log.text = Phone.log().joinToString("\n")
        if (atBottom) scrollToEnd()
    }

    private fun showAll() {
        val lines = Phone.log()
        if (lines.isEmpty()) {
            b.log.text = getString(R.string.log_empty)
            b.log.tag = EMPTY
        } else {
            b.log.text = lines.joinToString("\n")
            b.log.tag = null
        }
        scrollToEnd()
    }

    private fun scrollToEnd() = b.scroll.post { b.scroll.fullScroll(View.FOCUS_DOWN) }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.logs), Phone.log().joinToString("\n")))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val EMPTY = "empty"
    }
}
