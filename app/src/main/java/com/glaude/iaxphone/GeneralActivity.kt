package com.glaude.iaxphone

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glaude.iaxphone.databinding.ActivityGeneralBinding

/** Настройки → Общие: тема, звонки, работа в фоне, обновления. */
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

        // Звонки: система вызовов Android
        if (TelecomBridge.isSupported(this)) {
            b.telecom.isChecked = BackgroundPrefs.telecom(this)
            b.telecom.setOnCheckedChangeListener { _, on ->
                BackgroundPrefs.setTelecom(this, on)
                TelecomBridge.sync(this)
            }
        } else {
            b.telecom.isEnabled = false
            b.telecomHint.setText(R.string.telecom_unsupported)
        }
        b.fullscreenAllow.setOnClickListener { open(BackgroundPrefs.fullScreenSettingsIntent(this)) }

        // Работа в фоне
        b.connectOnStart.isChecked = BackgroundPrefs.connectOnStart(this)
        b.connectOnStart.setOnCheckedChangeListener { _, on -> BackgroundPrefs.setConnectOnStart(this, on) }
        b.startOnBoot.isChecked = BackgroundPrefs.startOnBoot(this)
        b.startOnBoot.setOnCheckedChangeListener { _, on -> BackgroundPrefs.setStartOnBoot(this, on) }
        b.keepAwake.isChecked = BackgroundPrefs.keepAwake(this)
        b.keepAwake.setOnCheckedChangeListener { _, on ->
            BackgroundPrefs.setKeepAwake(this, on)
            SoftphoneService.refresh(this)
        }
        b.batteryAllow.setOnClickListener { open(BackgroundPrefs.batteryOptimizationIntent(this)) }

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
        renderPermissions()
    }

    /** Состояние системных разрешений (могли измениться в настройках Android). */
    private fun renderPermissions() {
        val battery = BackgroundPrefs.ignoresBatteryOptimizations(this)
        b.batteryStatus.setText(if (battery) R.string.bg_battery_on else R.string.bg_battery_off)
        b.batteryAllow.visibility = if (battery) View.GONE else View.VISIBLE
        val full = BackgroundPrefs.canUseFullScreen(this)
        b.fullscreenStatus.setText(if (full) R.string.fullscreen_on else R.string.fullscreen_off)
        b.fullscreenAllow.visibility = if (full) View.GONE else View.VISIBLE
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
        }
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
