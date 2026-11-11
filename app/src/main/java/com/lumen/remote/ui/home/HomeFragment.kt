package com.lumen.remote.ui.home

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumen.remote.R
import com.lumen.remote.core.BuildFlags
import com.lumen.remote.core.Ids
import com.lumen.remote.core.Prefs
import com.lumen.remote.databinding.FragmentHomeBinding
import com.lumen.remote.net.NetUtils
import com.lumen.remote.ui.MainActivity
import com.lumen.remote.ui.common.DeviceAdapter
import com.lumen.remote.ui.common.DeviceUi
import com.lumen.remote.ui.host.HostActivity
import com.lumen.remote.ui.viewer.ViewerActivity
import com.lumen.remote.util.copyToClipboard
import com.lumen.remote.util.modeLabel
import com.lumen.remote.util.relativeTime
import com.lumen.remote.util.toast

class HomeFragment : Fragment() {

    private var binding: FragmentHomeBinding? = null
    private lateinit var prefs: Prefs

    private val recentAdapter = DeviceAdapter(
        onClick = { device -> prefill(device.address) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentHomeBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs.get(requireContext())
        val views = binding ?: return

        views.rvRecent.layoutManager = LinearLayoutManager(requireContext())
        views.rvRecent.adapter = recentAdapter

        views.btnCopyId.setOnClickListener {
            requireContext().copyToClipboard("Lumen ID", prefs.myId)
            requireContext().toast(getString(R.string.copied))
        }

        views.btnHost.setOnClickListener {
            startActivity(HostActivity.intent(requireContext()))
        }

        views.btnSettings.setOnClickListener {
            (activity as? MainActivity)?.openTab(R.id.tabSettings)
        }

        views.btnAllDevices.setOnClickListener {
            (activity as? MainActivity)?.openTab(R.id.tabDevices)
        }

        views.btnHeroCta.setOnClickListener { revealConnectForm(views) }

        views.btnConnect.setOnClickListener { submitConnection() }

        views.etPin.setOnEditorActionListener { _, _, _ ->
            submitConnection()
            true
        }

        views.etCode.doAfterTextChanged {
            views.codeLayout.error = null
            views.tvConnectError.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val views = binding ?: return
        views.tvMyCode.text = Ids.formatCode(prefs.myId)
        views.tvMyPin.text = getString(R.string.home_my_pin_value, prefs.myPin)

        val ip = NetUtils.localIp()
        views.tvLanStatus.text = if (ip != null) {
            getString(R.string.home_status_lan, ip)
        } else {
            getString(R.string.home_status_offline)
        }

        val devices = prefs.savedDevices().take(RECENT_LIMIT).map { DeviceUi.fromRecord(it) }
        recentAdapter.submitList(devices)
        views.tvRecentEmpty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        views.rvRecent.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Прокручивает экран к форме «Подключение по коду», ставит фокус в поле кода
     * и показывает клавиатуру.
     *
     * `requestRectangleOnScreen` надёжнее `smoothScrollTo`: `codeLayout.top` задан
     * относительно своего родителя, а не всего содержимого прокрутки, поэтому
     * смещение получалось неверным и кнопка «ничего не делала».
     */
    private fun revealConnectForm(views: FragmentHomeBinding) {
        views.etCode.requestFocus()
        views.codeLayout.post {
            val margin = (resources.displayMetrics.density * 160).toInt()
            views.codeLayout.requestRectangleOnScreen(
                Rect(0, 0, views.codeLayout.width, views.codeLayout.height + margin),
                true
            )
            showKeyboard(views.etCode)
        }
    }

    private fun showKeyboard(view: View) {
        val manager = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun prefill(address: String) {
        val views = binding ?: return
        views.etCode.setText(address)
        views.etCode.setSelection(views.etCode.text?.length ?: 0)
        views.etPin.requestFocus()
        requireContext().toast(getString(R.string.home_enter_pin))
    }

    private fun submitConnection() {
        val views = binding ?: return
        val rawCode = views.etCode.text?.toString()?.trim().orEmpty()
        val pin = views.etPin.text?.toString()?.trim().orEmpty()

        views.codeLayout.error = null
        views.pinLayout.error = null
        views.tvConnectError.visibility = View.GONE
        views.etCode.clearFocus()
        views.etPin.clearFocus()

        val digits = rawCode.filter { it.isDigit() }
        val mode: String
        val address: String
        when {
            rawCode.isBlank() -> {
                showError(getString(R.string.home_code_error))
                return
            }
            Ids.isLanAddress(rawCode) -> {
                mode = Prefs.MODE_LAN
                address = rawCode
            }
            digits.length == Ids.CODE_LENGTH -> {
                mode = Prefs.MODE_RELAY
                address = digits
            }
            else -> {
                showError(getString(R.string.home_code_error))
                return
            }
        }
        if (!Ids.isPin(pin)) {
            views.pinLayout.error = getString(R.string.home_pin_error)
            return
        }

        if (mode == Prefs.MODE_RELAY && !BuildFlags.isRelayUrlAllowed(prefs.relayUrl)) {
            showError(
                getString(
                    if (prefs.relayUrl.isBlank()) R.string.settings_relay_required
                    else R.string.relay_wss_required
                )
            )
            return
        }

        startActivity(
            ViewerActivity.intent(
                context = requireContext(),
                mode = mode,
                address = address,
                pin = pin,
                viewerName = prefs.deviceName
            )
        )
    }

    private fun showError(message: String) {
        val views = binding ?: return
        views.tvConnectError.text = message
        views.tvConnectError.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        binding?.rvRecent?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val RECENT_LIMIT = 3
    }
}
