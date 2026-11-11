package com.lumen.remote.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Context.dp(value: Number): Int =
    (value.toFloat() * resources.displayMetrics.density).toInt()

fun Context.copyToClipboard(label: String, text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 60 * 24 -> "${minutes / 60} ч назад"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} дн назад"
        else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

fun platformIconTint(platform: String): String = platform

fun modeLabel(mode: String): String = when (mode) {
    "relay" -> "Интернет"
    else -> "Локальная сеть"
}

fun formatElapsed(startedAt: Long): String {
    if (startedAt <= 0L) return "00:00"
    val total = (System.currentTimeMillis() - startedAt) / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
}
