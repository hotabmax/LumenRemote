package com.lumen.remote.ui.devices

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lumen.remote.R
import com.lumen.remote.core.Ids
import com.lumen.remote.core.Prefs
import com.lumen.remote.databinding.FragmentDevicesBinding
import com.lumen.remote.net.Discovery
import com.lumen.remote.ui.common.DeviceAdapter
import com.lumen.remote.ui.common.DeviceUi
import com.lumen.remote.ui.common.PinPrompt
import com.lumen.remote.ui.viewer.ViewerActivity
import com.lumen.remote.util.modeLabel
import com.lumen.remote.util.relativeTime
import com.lumen.remote.util.toast

class DevicesFragment : Fragment() {

    private var binding: FragmentDevicesBinding? = null
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val nearbyPeers = LinkedHashMap<String, Discovery.Peer>()

    private val nearbyAdapter = DeviceAdapter(onClick = { device ->
        promptPin(device.mode, device.address, device.name)
    })

    private val savedAdapter = DeviceAdapter(
        onClick = { device -> promptPin(device.mode, device.address, device.name) },
        onLongClick = { device -> confirmRemove(device) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentDevicesBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs.get(requireContext())
        val views = binding ?: return
        views.rvNearby.layoutManager = LinearLayoutManager(requireContext())
        views.rvNearby.adapter = nearbyAdapter
        views.rvSaved.layoutManager = LinearLayoutManager(requireContext())
        views.rvSaved.adapter = savedAdapter
    }

    override fun onResume() {
        super.onResume()
        refreshSaved()
        startDiscovery()
    }

    override fun onPause() {
        Discovery.stopListening()
        super.onPause()
    }

    private fun startDiscovery() {
        Discovery.stopListening()
        nearbyPeers.clear()
        updateNearbyUi()
        Discovery.startListening(requireContext()) { peer ->
            handler.post {
                if (binding == null) return@post
                nearbyPeers[peer.id] = peer
                updateNearbyUi()
            }
        }
    }

    private fun updateNearbyUi() {
        val views = binding ?: return
        val items = nearbyPeers.values.map { DeviceUi.fromNearby(it) }
        nearbyAdapter.submitList(items)
        views.tvNearbyStatus.text = if (items.isEmpty()) {
            getString(R.string.devices_nearby_searching)
        } else {
            getString(R.string.devices_nearby_found, items.size)
        }
        views.tvNearbyEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        views.pbNearby.visibility = View.VISIBLE
    }

    private fun refreshSaved() {
        val views = binding ?: return
        val items = prefs.savedDevices().map { DeviceUi.fromRecord(it) }
        savedAdapter.submitList(items)
        val isEmpty = items.isEmpty()
        views.cardSavedEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        views.rvSaved.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun promptPin(mode: String, address: String, name: String) {
        PinPrompt.show(requireContext(), name) { pin ->
            if (!Ids.isPin(pin)) {
                requireContext().toast(getString(R.string.home_pin_error))
                return@show
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
    }

    private fun confirmRemove(device: DeviceUi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(device.name)
            .setMessage(R.string.devices_remove)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.devices_remove) { _, _ ->
                prefs.removeDevice(device.key)
                refreshSaved()
                requireContext().toast(getString(R.string.devices_remove_done))
            }
            .show()
    }

    override fun onDestroyView() {
        binding?.rvNearby?.adapter = null
        binding?.rvSaved?.adapter = null
        binding = null
        super.onDestroyView()
    }
}
