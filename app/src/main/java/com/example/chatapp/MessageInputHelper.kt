package com.example.chatapp

import org.json.JSONObject

/**
 * Небольшие операции подготовки текста и вложений из истории чата.
 * Здесь нет UI-состояния Activity, поэтому код безопасно переиспользовать при редактировании.
 */
object MessageInputHelper {
    fun editableTextFromHistory(
        message: JSONObject,
        fallback: String,
        attachmentPlaceholder: String
    ): String {
        val rawContent = message.optNonBlankString("content") ?: fallback
        val baseText = rawContent.substringBefore("\n\n[")
        return if (baseText == attachmentPlaceholder) "" else baseText
    }

    fun attachmentPayloadFromHistory(message: JSONObject): AttachmentPayload? {
        val base64Data = message.optNonBlankString("base64")
        val fileUri = message.optNonBlankString("imageUri")
        val mimeType = message.optNonBlankString("mimeType")
        val fileName = message.optNonBlankString("fileName")
        val fileContext = message.optNonBlankString("fileContext")

        if (base64Data == null && fileUri == null && mimeType == null && fileName == null && fileContext == null) {
            return null
        }

        return AttachmentPayload(
            fileUri = fileUri.orEmpty(),
            mimeType = mimeType ?: ChatAttachmentHelper.resolveMimeTypeFromName(fileName),
            fileName = fileName,
            base64Data = base64Data,
            attachmentContext = fileContext
        )
    }

    private fun JSONObject.optNonBlankString(key: String): String? {
        return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }
}
