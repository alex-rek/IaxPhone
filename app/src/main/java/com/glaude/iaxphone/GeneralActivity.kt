package com.glaude.iaxphone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glaude.iaxphone.databinding.ActivityGeneralBinding

/** Настройки → Общие: выбор темы оформления. */
class GeneralActivity : AppCompatActivity() {

    private lateinit var b: ActivityGeneralBinding
    private lateinit var updateUi: UpdateUi

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivityGeneralBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.content.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        b.toolbar.setNavigationOnClickListener { finish() }

        val buttons = mapOf(
            ThemeMode.SYSTEM to b.themeSystem,
            ThemeMode.LIGHT to b.themeLight,
            ThemeMode.DARK to b.themeDark,
        )
        buttons[ThemePref.get(this)]?.isChecked = true
        b.themeGroup.setOnCheckedChangeListener { _, id ->
            val mode = buttons.entries.firstOrNull { it.value.id == id }?.key ?: return@setOnCheckedChangeListener
            if (mode != ThemePref.get(this)) ThemePref.set(this, mode)
        }

        // Обновления
        updateUi = UpdateUi(this)
        b.version.text = getString(R.string.app_version, Updater.currentVersion(this))
        b.autoUpdate.isChecked = Updater.autoCheckEnabled(this)
        b.autoUpdate.setOnCheckedChangeListener { _, on -> Updater.setAutoCheck(this, on) }
        b.checkUpdate.setOnClickListener { checkNow() }
    }

    override fun onResume() {
        super.onResume()
        updateUi.onResume()
    }

    private fun checkNow() {
        b.checkUpdate.isEnabled = false
        b.updateStatus.setText(R.string.update_checking)
        Updater.check(this) { res ->
            if (isDestroyed) return@check
            b.checkUpdate.isEnabled = true
            res.onSuccess { info ->
                if (info == null) {
                    b.updateStatus.setText(R.string.update_latest)
                } else {
                    b.updateStatus.text = getString(R.string.update_available, info.version)
                    updateUi.showAvailable(info)
                }
            }.onFailure { b.updateStatus.text = getString(R.string.update_error) + ": " + it.message }
        }
    }
}
