package com.example.chatapp.ui.chat

import android.content.Context
import com.example.chatapp.ChatMode
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R

internal data class ChatInputModeSpec(
    val title: String,
    val iconRes: Int,
    val hint: String,
    val mode: String
)

internal object ChatModePresentation {

    // Презентационные строки режимов держим рядом, чтобы Activity не знала о внутренних кодах режимов.
    fun inputHint(context: Context, mode: String?): String =
        LocaleHelper.getString(context, inputHintKey(mode))

    fun imageCreationSpec(context: Context): ChatInputModeSpec =
        ChatInputModeSpec(
            title = LocaleHelper.getString(context, "action_create_image"),
            iconRes = R.drawable.ic_palette,
            hint = LocaleHelper.getString(context, "hint_create_image"),
            mode = ChatMode.CREATE_IMAGE
        )

    private fun inputHintKey(mode: String?): String = when (mode) {
        ChatMode.CREATE_IMAGE -> "main_panel_input_create_image"
        ChatMode.SEARCH -> "main_panel_input_panel_search"
        ChatMode.SHOPPING -> "main_panel_input_purchase_research"
        ChatMode.STUDY -> "main_panel_study_training"
        else -> "main_panel_input"
    }
}
