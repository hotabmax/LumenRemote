package com.lumen.remote.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.lumen.remote.R
import com.lumen.remote.control.RemoteControlService
import com.lumen.remote.core.BuildFlags
import com.lumen.remote.core.Prefs
import com.lumen.remote.databinding.FragmentSettingsBinding
import com.lumen.remote.net.Discovery
import com.lumen.remote.net.RelayChannel
import com.lumen.remote.util.toast

class SettingsFragment : Fragment() {

    private var binding: FragmentSettingsBinding? = null
    private lateinit var prefs: Prefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentSettingsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs.get(requireContext())
        val views = binding ?: return

        views.etName.setText(prefs.deviceName)
        views.etName.doAfterTextChanged { text ->
            val value = text?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) prefs.deviceName = value
        }

        val quality = snap(prefs.quality, 30, 90, 5)
        views.sliderQuality.value = quality.toFloat()
        views.tvQuality.text = getString(R.string.settings_quality_value, quality)
        views.sliderQuality.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val rounded = value.toInt()
            prefs.quality = rounded
            views.tvQuality.text = getString(R.string.settings_quality_value, rounded)
        }

        views.sliderFps.value = prefs.frameRate.toFloat()
        views.tvFps.text = getString(R.string.settings_fps_value, prefs.frameRate)
        views.sliderFps.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val rounded = value.toInt()
            prefs.frameRate = rounded
            views.tvFps.text = getString(R.string.settings_fps_value, rounded)
        }

        views.rowAccessibility.setOnClickListener {
            RemoteControlService.openSettings(requireContext())
        }

        views.swConfirmTouch.isChecked = prefs.confirmTouch
        views.swConfirmTouch.setOnCheckedChangeListener { _, checked -> prefs.confirmTouch = checked }

        views.swRequirePin.isChecked = prefs.requirePin
        views.swRequirePin.setOnCheckedChangeListener { _, checked -> prefs.requirePin = checked }

        views.swClipboard.isChecked = prefs.allowClipboard
        views.swClipboard.setOnCheckedChangeListener { _, checked -> prefs.allowClipboard = checked }

        views.swConfirmConnect.isChecked = prefs.confirmConnections
        views.swConfirmConnect.setOnCheckedChangeListener { _, checked ->
            prefs.confirmConnections = checked
        }

        views.swDiscoverable.isChecked = prefs.discoverable
        views.swDiscoverable.setOnCheckedChangeListener { _, checked ->
            prefs.discoverable = checked
            if (!checked) Discovery.stopBroadcast()
        }

        views.etRelay.setText(prefs.relayUrl)
        views.btnSaveRelay.setOnClickListener {
            val url = views.etRelay.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                views.tilRelay.error = getString(R.string.settings_relay_required)
                return@setOnClickListener
            }
            if (!BuildFlags.isRelayUrlAllowed(url)) {
                views.tilRelay.error = getString(
                    if (RelayChannel.isSupportedUrl(url)) {
                        R.string.relay_wss_required
                    } else {
                        R.string.settings_relay_invalid
                    }
                )
                return@setOnClickListener
            }
            views.tilRelay.error = null
            prefs.relayUrl = url
            requireContext().toast(getString(R.string.settings_relay_saved))
        }

        views.tvVersion.text = getString(R.string.settings_version, versionName())
    }

    override fun onResume() {
        super.onResume()
        val views = binding ?: return
        val enabled = RemoteControlService.isEnabled(requireContext())
        views.tvAccessibilityStatus.text = getString(
            if (enabled) R.string.settings_accessibility_on else R.string.settings_accessibility_off
        )
        views.tvAccessibilityStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.success else R.color.warning
            )
        )
    }

    private fun snap(value: Int, from: Int, to: Int, step: Int): Int {
        val clamped = value.coerceIn(from, to)
        val steps = Math.round((clamped - from).toFloat() / step)
        return (from + steps * step).coerceIn(from, to)
    }

    private fun versionName(): String = runCatching {
        val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        info.versionName ?: "1.0"
    }.getOrDefault("1.0")

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
