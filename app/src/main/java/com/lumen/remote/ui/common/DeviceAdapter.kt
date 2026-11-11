package com.lumen.remote.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumen.remote.R
import com.lumen.remote.databinding.ItemDeviceBinding
import com.lumen.remote.core.DeviceRecord
import com.lumen.remote.core.Prefs
import com.lumen.remote.net.Discovery
import com.lumen.remote.util.modeLabel
import com.lumen.remote.util.relativeTime

data class DeviceUi(
    val key: String,
    val name: String,
    val platform: String,
    val subtitle: String,
    val online: Boolean,
    val mode: String,
    val address: String
) {
    companion object {
        /** Устройство из истории подключений. */
        fun fromRecord(record: DeviceRecord, online: Boolean = false): DeviceUi = DeviceUi(
            key = record.id,
            name = record.name,
            platform = record.platform,
            subtitle = subtitleOf(record.platform, record.mode, relativeTime(record.lastConnectedAt)),
            online = online,
            mode = record.mode,
            address = record.address
        )

        /** Устройство, найденное в локальной сети. */
        fun fromNearby(peer: Discovery.Peer): DeviceUi = DeviceUi(
            key = peer.id,
            name = peer.name,
            platform = "Android",
            subtitle = "Рядом · ${peer.ip}:${peer.port}",
            online = true,
            mode = Prefs.MODE_LAN,
            address = peer.ip
        )

        private fun subtitleOf(platform: String, mode: String, time: String): String =
            listOf(platform, modeLabel(mode), time).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

class DeviceAdapter(
    private val onClick: (DeviceUi) -> Unit,
    private val onLongClick: ((DeviceUi) -> Unit)? = null
) : ListAdapter<DeviceUi, DeviceAdapter.DeviceHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                val handler = onLongClick
                if (position != RecyclerView.NO_POSITION && handler != null) {
                    handler(getItem(position))
                    true
                } else {
                    false
                }
            }
        }

        fun bind(item: DeviceUi) {
            val context = binding.root.context
            binding.tvName.text = item.name
            binding.tvSubtitle.text = item.subtitle
            binding.dot.setBackgroundResource(
                if (item.online) R.drawable.bg_dot_online else R.drawable.bg_dot_offline
            )
            val tint = if (item.platform.contains("iOS", ignoreCase = true)) {
                R.color.magenta
            } else {
                R.color.cyan
            }
            binding.ivPlatform.setColorFilter(ContextCompat.getColor(context, tint))
            binding.tile.setBackgroundResource(
                if (item.online) R.drawable.bg_tile_cyan else R.drawable.bg_tile_muted
            )
            if (item.online) {
                binding.ivPlatform.setColorFilter(ContextCompat.getColor(context, R.color.bg_deep))
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DeviceUi>() {
            override fun areItemsTheSame(oldItem: DeviceUi, newItem: DeviceUi) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: DeviceUi, newItem: DeviceUi) = oldItem == newItem
        }
    }
}
