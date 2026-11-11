package com.lumen.remote.ui.common

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lumen.remote.R
import com.lumen.remote.util.dp

/**
 * Готовые поля ввода для диалогов: одинаковое оформление в разных экранах.
 */
object InputViews {

    private const val CORNER_RADIUS_DP = 18f
    private const val FIELD_PADDING_HORIZONTAL_DP = 16
    private const val FIELD_PADDING_VERTICAL_DP = 22
    private const val CONTAINER_PADDING_HORIZONTAL_DP = 24
    private const val CONTAINER_PADDING_TOP_DP = 8

    class Field(val container: FrameLayout, val input: TextInputEditText)

    fun field(
        context: Context,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        maxLength: Int? = null,
        textSizeSp: Float = 16f
    ): Field {
        val radius = context.dp(CORNER_RADIUS_DP).toFloat()
        val layout = TextInputLayout(context).apply {
            this.hint = hint
            boxBackgroundColor = context.getColor(R.color.surface_2)
            setBoxCornerRadii(radius, radius, radius, radius)
        }
        val input = TextInputEditText(context).apply {
            this.inputType = inputType
            maxLength?.let { filters = arrayOf(InputFilter.LengthFilter(it)) }
            textSize = textSizeSp
            setPadding(
                context.dp(FIELD_PADDING_HORIZONTAL_DP),
                context.dp(FIELD_PADDING_VERTICAL_DP),
                context.dp(FIELD_PADDING_HORIZONTAL_DP),
                context.dp(FIELD_PADDING_VERTICAL_DP)
            )
        }
        layout.addView(
            input,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val container = FrameLayout(context).apply {
            val horizontal = context.dp(CONTAINER_PADDING_HORIZONTAL_DP)
            setPadding(horizontal, context.dp(CONTAINER_PADDING_TOP_DP), horizontal, 0)
            addView(layout)
        }
        return Field(container, input)
    }
}
