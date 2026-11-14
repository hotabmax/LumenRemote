package com.lumen.remote.ui.host

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lumen.remote.R
import com.lumen.remote.control.RemoteControlService
import com.lumen.remote.core.BuildFlags
import com.lumen.remote.core.Ids
import com.lumen.remote.core.Prefs
import com.lumen.remote.databinding.ActivityHostBinding
import com.lumen.remote.host.HostSession
import com.lumen.remote.host.ScreenShareService
import com.lumen.remote.net.NetUtils
import com.lumen.remote.net.RelayChannel
import com.lumen.remote.util.copyToClipboard
import com.lumen.remote.util.toast
import kotlinx.coroutines.launch

class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private lateinit var prefs: Prefs
    private var mode = HostSession.Mode.LAN
    private var started = false
    private var pendingStartAfterNotifications = false
    private var lastControlRunning = RemoteControlService.isRunning

    private val diagnosticsHandler = Handler(Looper.getMainLooper())
    private val diagnosticsTicker = object : Runnable {
        override fun run() {
            updateDiagnostics()
            diagnosticsHandler.postDelayed(this, 1000)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            beginSession(result.resultCode, data)
        } else {
            toast(getString(R.string.error_cancelled))
            started = false
            render(HostSession.state.value)
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        render(HostSession.state.value)
        if (pendingStartAfterNotifications) {
            pendingStartAfterNotifications = false
            if (granted) startProjection() else showNotificationWarning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.get(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        started = HostSession.state.value.isActive
        mode = HostSession.state.value.mode

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopy.setOnClickListener {
            val code = HostSession.state.value.code.ifBlank { prefs.myId }
            copyToClipboard("Lumen ID", code)
            toast(getString(R.string.copied))
        }
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val requested = if (checkedId == R.id.btnModeRelay) HostSession.Mode.RELAY else HostSession.Mode.LAN
            if (started) {
                toast(getString(R.string.host_stop_first))
                binding.toggleMode.check(if (mode == HostSession.Mode.RELAY) R.id.btnModeRelay else R.id.btnModeLan)
                return@addOnButtonCheckedListener
            }
            mode = requested
            renderMode()
        }
        binding.btnStart.setOnClickListener {
            if (started) stopSession() else requestStart()
        }
        binding.rowControl.setOnClickListener {
            RemoteControlService.openSettings(this)
        }
        binding.rowNotifications.setOnClickListener { requestNotifications() }
        binding.rowCapture.setOnClickListener { if (!started) requestStart() }

        if (mode == HostSession.Mode.RELAY) binding.toggleMode.check(R.id.btnModeRelay)
        renderMode()
        render(HostSession.state.value)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HostSession.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (HostSession.state.value.isActive) {
            HostSession.refreshControlAvailability()
        }
        render(HostSession.state.value)
    }

    override fun onStart() {
        super.onStart()
        diagnosticsHandler.post(diagnosticsTicker)
    }

    override fun onStop() {
        diagnosticsHandler.removeCallbacks(diagnosticsTicker)
        keepScreenOn(false)
        super.onStop()
    }

    private fun requestStart() {
        requestOverlayPermissionIfNeeded()
        if (mode == HostSession.Mode.RELAY) {
            val relayUrl = prefs.relayUrl
            if (relayUrl.isBlank()) {
                toast(getString(R.string.settings_relay_required))
                return
            }
            if (!BuildFlags.isRelayUrlAllowed(relayUrl)) {
                toast(getString(R.string.relay_wss_required))
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted()) {
            pendingStartAfterNotifications = true
            runCatching {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure { startProjection() }
            return
        }
        startProjection()
    }

    private fun startProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { projectionLauncher.launch(manager.createScreenCaptureIntent()) }
            .onFailure { toast(getString(R.string.error_unknown)) }
    }

    /** Без уведомления система может остановить трансляцию (частая проблема на Huawei). */
    private fun showNotificationWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.host_notifications_title)
            .setMessage(R.string.host_notifications_text)
            .setPositiveButton(R.string.host_open_settings) { _, _ -> openNotificationSettings() }
            .setNeutralButton(R.string.host_start_anyway) { _, _ -> startProjection() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openNotificationSettings() {
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
            }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun beginSession(resultCode: Int, data: Intent) {
        HostSession.start(this, mode)
        runCatching {
            ContextCompat.startForegroundService(
                this,
                ScreenShareService.startIntent(this, resultCode, data)
            )
        }.onFailure {
            HostSession.stop()
            toast(getString(R.string.error_unknown))
            return
        }
        started = true
        render(HostSession.state.value)
    }

    private fun stopSession() {
        started = false
        ScreenShareService.stop(this)
        HostSession.stop()
        render(HostSession.state.value)
    }

    /** Диалог подтверждения подключения, пока приложение на экране. */
    private var approvalDialog: androidx.appcompat.app.AlertDialog? = null

    /** Диалог подтверждения: параллельно с уведомлением, если приложение открыто. */
    private fun showApprovalDialog(name: String?, platform: String?) {
        if (approvalDialog != null) return
        val device = name ?: getString(R.string.approval_unknown_device)
        approvalDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.approval_title)
            .setMessage(getString(R.string.approval_dialog_text, device, platform ?: "Android"))
            .setPositiveButton(R.string.approval_allow) { _, _ -> HostSession.approvePending() }
            .setNegativeButton(R.string.approval_deny) { _, _ ->
                HostSession.denyPending(getString(R.string.approval_denied))
            }
            .setCancelable(false)
            .show()
    }

    private fun hideApprovalDialog() {
        approvalDialog?.dismiss()
        approvalDialog = null
    }

    private fun renderMode() {
        val views = binding
        val relay = mode == HostSession.Mode.RELAY
        views.btnModeLan.isEnabled = !started
        views.btnModeRelay.isEnabled = !started
        views.tvModeHint.text = getString(if (relay) R.string.host_relay_hint else R.string.host_lan_hint)
        render(HostSession.state.value)
    }

    private fun render(state: HostSession.State) {
        val views = binding
        val active = state.isActive
        started = active || started
        if (state.phase == HostSession.Phase.PENDING) {
            showApprovalDialog(state.viewerName, state.viewerPlatform)
        } else {
            hideApprovalDialog()
        }

        views.tvCode.text = Ids.formatCode(state.code.ifBlank { prefs.myId })
        views.tvPin.text = getString(R.string.home_my_pin_value, state.pin.ifBlank { prefs.myPin })
        views.tvIp.text = when (state.mode) {
            HostSession.Mode.LAN -> {
                val ip = state.ip ?: NetUtils.localIp()
                ip?.let { getString(R.string.host_ip, it) } ?: getString(R.string.home_status_offline)
            }
            HostSession.Mode.RELAY -> state.relayUrl?.let { getString(R.string.host_relay_address, it) } ?: ""
        }

        views.radar.running = active
        views.btnStart.text = getString(if (active) R.string.host_stop else R.string.host_start)

        when (state.phase) {
            HostSession.Phase.CONNECTED -> {
                views.tvStatus.text = getString(R.string.host_connected)
                views.tvStatusHint.text = getString(R.string.host_streaming)
                views.cardConnected.visibility = android.view.View.VISIBLE
                views.tvViewer.text = getString(
                    R.string.host_viewer,
                    state.viewerName ?: "—",
                    state.viewerPlatform ?: "Android"
                )
                views.tvStats.text = getString(R.string.host_stats, state.framesSent)
            }
            HostSession.Phase.ERROR -> {
                views.tvStatus.text = getString(R.string.host_error)
                views.tvStatusHint.text = state.lastError ?: getString(R.string.error_unknown)
                views.cardConnected.visibility = android.view.View.GONE
            }
            HostSession.Phase.WAITING -> {
                views.tvStatus.text = getString(R.string.host_waiting)
                views.tvStatusHint.text = getString(R.string.host_waiting_hint)
                views.cardConnected.visibility = android.view.View.GONE
            }
            HostSession.Phase.PENDING -> {
                views.tvStatus.text = getString(R.string.approval_title)
                views.tvStatusHint.text = state.viewerName ?: getString(R.string.host_waiting_hint)
                views.cardConnected.visibility = android.view.View.GONE
            }
            HostSession.Phase.IDLE -> {
                views.tvStatus.text = getString(R.string.host_ready)
                views.tvStatusHint.text = getString(R.string.host_ready_hint)
                views.cardConnected.visibility = android.view.View.GONE
            }
        }

        views.tvPermCapture.text = getString(
            if (active) R.string.host_perm_granted else R.string.host_perm_needed
        )
        views.tvPermCapture.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.success else R.color.warning)
        )

        val controlEnabled = RemoteControlService.isEnabled(this)
        views.tvPermControl.text = getString(
            if (controlEnabled) R.string.host_perm_granted else R.string.host_perm_needed
        )
        views.tvPermControl.setTextColor(
            ContextCompat.getColor(this, if (controlEnabled) R.color.success else R.color.warning)
        )

        val notifications = notificationsGranted()
        views.tvPermNotifications.text = getString(
            if (notifications) R.string.host_perm_granted else R.string.host_perm_needed
        )
        views.tvPermNotifications.setTextColor(
            ContextCompat.getColor(this, if (notifications) R.color.success else R.color.warning)
        )

        updateDiagnostics()
    }

    /** Живая диагностика: кадры, fps, размер, состояние службы управления. */
    private fun updateDiagnostics() {
        val views = binding
        val state = HostSession.state.value

        views.tvDiagnostics.text = when {
            !state.isActive -> getString(R.string.host_diag_waiting)
            !state.contentVisible -> getString(R.string.host_content_hidden)
            state.phase == HostSession.Phase.CONNECTED ->
                if (state.captureActive && state.framesEncoded == 0L) {
                    getString(R.string.host_no_frames_secure)
                } else {
                    getString(
                        R.string.host_diag_format,
                        state.framesSent,
                        state.fps,
                        state.screenWidth,
                        state.screenHeight
                    )
                }
            else -> getString(R.string.host_waiting_hint)
        }
        views.tvDiagnostics.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.isActive && !state.contentVisible) R.color.warning else R.color.text_tertiary
            )
        )

        val serviceEnabled = RemoteControlService.isEnabled(this)
        val serviceRunning = RemoteControlService.isRunning
        views.tvControlWarning.isVisible = serviceEnabled && !serviceRunning

        // Huawei/HarmonyOS могут выгрузить службу доступности: сообщаем об этом партнёру.
        if (serviceRunning != lastControlRunning) {
            lastControlRunning = serviceRunning
            if (state.isActive) HostSession.refreshControlAvailability()
        }

        keepScreenOn(state.isActive)
    }

    /** На Huawei/HarmonyOS экран без этого гаснет и трансляция становится чёрной. */
    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Просит разрешение «поверх других приложений»: без него на экране не появится
     * кнопка остановки трансляции. Спрашиваем один раз за запуск приложения.
     */
    private fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(this) || overlayPermissionAsked) return
        overlayPermissionAsked = true
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }
        toast(getString(R.string.overlay_permission_hint))
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Разрешение «поверх других приложений» уже спрашивали в этом запуске. */
        private var overlayPermissionAsked = false

        fun intent(context: Context): Intent = Intent(context, HostActivity::class.java)
    }
}
