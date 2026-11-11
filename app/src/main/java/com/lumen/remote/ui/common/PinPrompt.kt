package com.lumen.remote.ui.common

import android.content.Context
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lumen.remote.R

/**
 * Диалог ввода PIN-кода: используется при подключении к устройству из списка.
 */
object PinPrompt {

    private const val PIN_LENGTH = 4

    fun show(
        context: Context,
        title: String,
        onConfirm: (String) -> Unit
    ): AlertDialog {
        val field = InputViews.field(
            context = context,
            hint = context.getString(R.string.home_pin_hint),
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            maxLength = PIN_LENGTH,
            textSizeSp = 18f
        )
        return MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(field.container)
            .setPositiveButton(R.string.viewer_connect) { _, _ ->
                onConfirm(field.input.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
