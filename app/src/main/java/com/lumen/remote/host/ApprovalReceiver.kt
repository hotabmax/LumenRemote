package com.lumen.remote.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumen.remote.R

/**
 * Обрабатывает кнопки «Разрешить» и «Отклонить» в уведомлении о подключении.
 */
class ApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        if (allow) {
            HostSession.approvePending(token)
        } else {
            HostSession.denyPending(context.getString(R.string.approval_denied))
        }
        ConnectionApproval.hide(context)
    }

    companion object {
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_ALLOW = "allow"

        fun intent(context: Context, token: String, allow: Boolean): Intent =
            Intent(context, ApprovalReceiver::class.java).apply {
                action = "com.lumen.remote.action.APPROVAL"
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_ALLOW, allow)
            }
    }
}
