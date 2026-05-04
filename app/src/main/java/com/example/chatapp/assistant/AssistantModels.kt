package com.example.chatapp.assistant

import com.example.chatapp.AttachmentPayload

data class AssistantAttachment(
    val mimeType: String,
    val fileName: String,
    val base64Data: String,
    val cacheFilePath: String?,
    val attachmentContext: String? = null
) {
    fun toAttachmentPayload(): AttachmentPayload =
        AttachmentPayload(
            fileUri = cacheFilePath.orEmpty(),
            mimeType = mimeType,
            fileName = fileName,
            base64Data = base64Data,
            attachmentContext = attachmentContext
        )
}

enum class AssistantResponseStatus {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}

enum class AssistantMessageRole {
    USER,
    ASSISTANT
}

data class AssistantMessage(
    val id: Long,
    val role: AssistantMessageRole,
    val text: String,
    val status: AssistantResponseStatus = AssistantResponseStatus.SUCCESS,
    val isScreenAnalysis: Boolean = false,
    val attachments: List<AssistantAttachment> = emptyList()
)

data class DigitalAssistantState(
    val attachment: AssistantAttachment? = null,
    val attachments: List<AssistantAttachment> = emptyList(),
    val responseStatus: AssistantResponseStatus = AssistantResponseStatus.IDLE,
    val messages: List<AssistantMessage> = emptyList(),
    val userQuestion: String = "",
    val answerText: String = "",
    val errorText: String? = null,
    val isScreenAnalysis: Boolean = false,
    val activeChatId: String? = null
) {
    val isGenerating: Boolean
        get() = responseStatus == AssistantResponseStatus.LOADING

    val effectiveAttachments: List<AssistantAttachment>
        get() = attachments.ifEmpty { listOfNotNull(attachment) }
}
