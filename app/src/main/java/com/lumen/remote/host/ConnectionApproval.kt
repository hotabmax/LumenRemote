package com.lumen.remote.host

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lumen.remote.LumenApp
import com.lumen.remote.R
import com.lumen.remote.ui.host.HostActivity

/**
 * Уведомление «подключиться хочет устройство X» с кнопками разрешения и отказа.
 *
 * Нужно, чтобы подтверждение работало и когда приложение хоста не на экране.
 */
object ConnectionApproval {

    private const val TAG = "LumenApproval"
    private const val NOTIFICATION_ID = 77

    fun show(
        context: Context?,
        token: String,
        name: String,
        platform: String,
        address: String?
    ) {
        val ctx = context ?: return
        if (!canNotify(ctx)) {
            Log.w(TAG, "Нет разрешения на уведомления — подтверждение только в приложении")
            return
        }
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val openIntent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, HostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val allowIntent = PendingIntent.getBroadcast(
            ctx,
            1,
            ApprovalReceiver.intent(ctx, token, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyIntent = PendingIntent.getBroadcast(
            ctx,
            2,
            ApprovalReceiver.intent(ctx, token, false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val details = buildString {
            append(name.ifBlank { ctx.getString(R.string.approval_unknown_device) })
            if (platform.isNotBlank()) append(" · ").append(platform)
            if (!address.isNullOrBlank()) append(" · ").append(address)
        }

        val notification = NotificationCompat.Builder(ctx, LumenApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(ctx.getString(R.string.approval_title))
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, ctx.getString(R.string.approval_allow), allowIntent)
            .addAction(0, ctx.getString(R.string.approval_deny), denyIntent)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun hide(context: Context?) {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
