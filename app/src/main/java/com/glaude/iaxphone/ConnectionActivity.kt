package com.glaude.iaxphone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glaude.iaxphone.databinding.ActivityConnectionBinding
import com.glaude.iaxphone.iax.IAX_DEFAULT_PORT
import com.glaude.iaxphone.iax.RegState

/** Настройки → Подключение: учётная запись IAX2 и кнопка подключения. */
class ConnectionActivity : AppCompatActivity(), Phone.Observer {

    private lateinit var b: ActivityConnectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Phone.init(this)
        b = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            b.toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.scroll.updatePadding(left = bars.left, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        b.toolbar.setNavigationOnClickListener { finish() }

        val acc = Account.load(this)
        b.host.setText(acc.host)
        b.port.setText(acc.port.toString())
        b.username.setText(acc.username)
        b.password.setText(acc.password)
        b.callerId.setText(acc.callerId)
        b.connect.setOnClickListener { onConnectClicked() }
    }

    override fun onStart() {
        super.onStart()
        Phone.addObserver(this)
        render()
    }

    override fun onStop() {
        Phone.removeObserver(this)
        super.onStop()
    }

    override fun onPhoneChanged() = render()

    private fun onConnectClicked() {
        if (Phone.connected) {
            Account.setAutoConnect(this, false)
            SoftphoneService.stop(this)
            return
        }
        val host = b.host.text.toString().trim()
        val user = b.username.text.toString().trim()
        if (host.isEmpty()) { b.host.error = getString(R.string.required); return }
        if (user.isEmpty()) { b.username.error = getString(R.string.required); return }
        val acc = Account.load(this).copy(
            host = host,
            port = b.port.text.toString().toIntOrNull() ?: IAX_DEFAULT_PORT,
            username = user,
            password = b.password.text.toString(),
            callerId = b.callerId.text.toString().trim(),
        )
        Account.save(this, acc)
        Account.setAutoConnect(this, true)
        SoftphoneService.start(this)
    }

    private fun render() {
        val connected = Phone.connected
        b.connect.setText(if (connected) R.string.disconnect else R.string.connect)
        listOf(b.host, b.port, b.username, b.password, b.callerId).forEach { it.isEnabled = !connected }
        b.regStatus.text = Phone.regDetail
        val color = when (Phone.regState) {
            RegState.REGISTERED -> R.color.status_ok
            RegState.REGISTERING -> R.color.status_wait
            RegState.FAILED -> R.color.status_error
            RegState.OFF -> R.color.status_off
        }
        b.regDot.background.setTint(ContextCompat.getColor(this, color))
    }
}
