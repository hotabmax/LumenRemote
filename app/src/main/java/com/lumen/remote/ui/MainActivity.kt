package com.lumen.remote.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.lumen.remote.R
import com.lumen.remote.databinding.ActivityMainBinding
import com.lumen.remote.ui.devices.DevicesFragment
import com.lumen.remote.ui.home.HomeFragment
import com.lumen.remote.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String = TAG_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(0, bars.top, 0, 0)
            binding.bottomBar.updatePadding(
                bottom = bars.bottom + resources.getDimensionPixelSize(R.dimen.space_s)
            )
            insets
        }

        binding.tabHome.setOnClickListener { openTab(TAG_HOME) }
        binding.tabDevices.setOnClickListener { openTab(TAG_DEVICES) }
        binding.tabSettings.setOnClickListener { openTab(TAG_SETTINGS) }

        val restored = savedInstanceState?.getString(KEY_TAG) ?: TAG_HOME
        if (supportFragmentManager.findFragmentByTag(restored) == null) {
            show(HomeFragment(), TAG_HOME)
        } else {
            currentTag = restored
            updateTabs()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAG, currentTag)
    }

    /** Переключение вкладки по id контейнера (используется фрагментами). */
    fun openTab(tabId: Int) {
        when (tabId) {
            R.id.tabDevices -> openTab(TAG_DEVICES)
            R.id.tabSettings -> openTab(TAG_SETTINGS)
            else -> openTab(TAG_HOME)
        }
    }

    private fun openTab(tag: String) {
        val fragment = when (tag) {
            TAG_DEVICES -> DevicesFragment()
            TAG_SETTINGS -> SettingsFragment()
            else -> HomeFragment()
        }
        show(fragment, tag)
    }

    private fun show(fragment: Fragment, tag: String) {
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        manager.fragments.forEach { transaction.hide(it) }
        val existing = manager.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.add(R.id.fragment_container, fragment, tag)
        }
        transaction.commitAllowingStateLoss()
        currentTag = tag
        updateTabs()
    }

    private fun updateTabs() {
        val active = ContextCompat.getColor(this, R.color.cyan)
        val inactive = ContextCompat.getColor(this, R.color.text_tertiary)
        val tabs = listOf(
            Triple(binding.tabHome, binding.iconTabHome, binding.labelTabHome) to TAG_HOME,
            Triple(binding.tabDevices, binding.iconTabDevices, binding.labelTabDevices) to TAG_DEVICES,
            Triple(binding.tabSettings, binding.iconTabSettings, binding.labelTabSettings) to TAG_SETTINGS
        )
        tabs.forEach { (views, tag) ->
            val isActive = tag == currentTag
            val container = views.first
            val icon = views.second
            val label = views.third
            container.setBackgroundResource(
                if (isActive) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            )
            icon.setColorFilter(if (isActive) active else inactive)
            label.setTextColor(if (isActive) active else inactive)
            container.animate()
                .scaleX(if (isActive) 1f else 0.97f)
                .scaleY(if (isActive) 1f else 0.97f)
                .setDuration(160)
                .start()
        }
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_DEVICES = "devices"
        private const val TAG_SETTINGS = "settings"
        private const val KEY_TAG = "tab"
    }
}
