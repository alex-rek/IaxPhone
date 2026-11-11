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
    }
}
