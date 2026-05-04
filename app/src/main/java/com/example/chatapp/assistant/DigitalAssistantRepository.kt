package com.example.chatapp.assistant

import android.app.Application
import com.example.chatapp.LocaleHelper
import com.example.chatapp.viewmodel.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DigitalAssistantRepository(
    private val application: Application,
    private val chatViewModel: ChatViewModel = ChatViewModel(application)
) {
    data class SendResult(
        val chatId: String?,
        val fullAnswer: String
    )

    suspend fun send(
        text: String,
        attachment: AssistantAttachment?,
        onChatReady: (String?) -> Unit,
        onChunk: (String) -> Unit
    ): SendResult = withContext(Dispatchers.Main) {
        val title = when {
            text.isNotBlank() -> text.take(60)
            attachment != null -> LocaleHelper.getString(application, "digital_assistant_screen_analysis")
            else -> LocaleHelper.getString(application, "label_new_chat")
        }
        val chatId = ensureChat(title)
        onChatReady(chatId)
        if (!chatViewModel.consumeLimit()) {
            error(LocaleHelper.getString(application, "toast_limits_exhausted"))
        }

        val result = CompletableDeferred<SendResult>()
        var accumulated = ""
        val payload = attachment?.toAttachmentPayload()
        chatViewModel.addToChatHistoryAndSend(
            content = text,
            base64Data = payload?.base64Data,
            fileUri = payload?.fileUri?.takeIf { it.isNotBlank() },
            mimeType = payload?.mimeType,
            fileName = payload?.fileName,
            fileContext = payload?.attachmentContext,
            onError = { error ->
                if (!result.isCompleted) {
                    result.completeExceptionally(IllegalStateException(error))
                }
            },
            onChunk = { chunk ->
                accumulated = chunk
                onChunk(chunk)
            },
            onStreamComplete = {
                if (!result.isCompleted) {
                    result.complete(
                        SendResult(
                            chatId = chatViewModel.currentChatId,
                            fullAnswer = accumulated
                        )
                    )
                }
            }
        )
        result.await()
    }

    fun cancelActiveResponse() {
        chatViewModel.cancelActiveResponse()
    }

    private suspend fun ensureChat(title: String): String? {
        chatViewModel.currentChatId?.let { return it }
        val deferred = CompletableDeferred<String>()
        chatViewModel.currentChatTitle = title
        chatViewModel.isFirstMessage = false
        chatViewModel.createNewChat(title) { chatId ->
            chatViewModel.currentChatId = chatId
            deferred.complete(chatId)
        }
        return deferred.await()
    }
}
