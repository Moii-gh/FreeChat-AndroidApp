package com.example.chatapp.assistant

import com.example.chatapp.AttachmentPayload

data class AssistantAttachment(
    val mimeType: String,
    val fileName: String,
    val base64Data: String,
    val cacheFilePath: String?
) {
    fun toAttachmentPayload(): AttachmentPayload =
        AttachmentPayload(
            fileUri = cacheFilePath.orEmpty(),
            mimeType = mimeType,
            fileName = fileName,
            base64Data = base64Data,
            attachmentContext = null
        )
}

enum class AssistantResponseStatus {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}

data class DigitalAssistantState(
    val attachment: AssistantAttachment? = null,
    val responseStatus: AssistantResponseStatus = AssistantResponseStatus.IDLE,
    val userQuestion: String = "",
    val answerText: String = "",
    val errorText: String? = null,
    val isScreenAnalysis: Boolean = false,
    val activeChatId: String? = null
) {
    val isGenerating: Boolean
        get() = responseStatus == AssistantResponseStatus.LOADING
}

