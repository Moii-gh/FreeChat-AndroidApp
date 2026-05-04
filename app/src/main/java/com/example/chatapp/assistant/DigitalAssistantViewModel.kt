package com.example.chatapp.assistant

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.chatapp.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

class DigitalAssistantViewModel(context: Context) {
    private val application = context.applicationContext as Application
    private val repository = DigitalAssistantRepository(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = linkedSetOf<(DigitalAssistantState) -> Unit>()
    private var sendJob: Job? = null
    private var lastSubmittedText: String = ""
    private var lastSubmittedAttachment: AssistantAttachment? = null
    private var nextMessageId = 1L
    private var assistScreenshot: Bitmap? = null
    private var pendingAssistScreenshotRequest: PendingAssistScreenshotRequest? = null

    var state: DigitalAssistantState = DigitalAssistantState()
        private set

    fun addListener(listener: (DigitalAssistantState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (DigitalAssistantState) -> Unit) {
        listeners.remove(listener)
    }

    fun beginAssistSession() {
        cleanupAssistScreenshot()
    }

    fun setAssistScreenshot(screenshot: Bitmap?) {
        cleanupAssistScreenshot()
        if (screenshot == null || screenshot.isRecycled) {
            failPendingAssistScreenshotRequest()
            return
        }
        assistScreenshot = runCatching {
            screenshot.copy(screenshot.config ?: Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        if (assistScreenshot == null) {
            failPendingAssistScreenshotRequest()
            return
        }
        pendingAssistScreenshotRequest?.let { request ->
            pendingAssistScreenshotRequest = null
            request.callback(attachLatestAssistScreenshot())
        }
    }

    fun requestAssistScreenshotAttachment(onResult: (Result<AssistantAttachment>) -> Unit) {
        if (assistScreenshot != null) {
            onResult(attachLatestAssistScreenshot())
            return
        }

        val request = PendingAssistScreenshotRequest(onResult)
        pendingAssistScreenshotRequest = request
        scope.launch {
            delay(ASSIST_SCREENSHOT_WAIT_MS)
            if (pendingAssistScreenshotRequest === request) {
                pendingAssistScreenshotRequest = null
                onResult(assistScreenshotUnavailableResult())
            }
        }
    }

    fun attachLatestAssistScreenshot(): Result<AssistantAttachment> {
        val screenshot = assistScreenshot
            ?: return assistScreenshotUnavailableResult()
        assistScreenshot = null
        return runCatching { buildAssistScreenshotAttachment(screenshot) }
            .onFailure {
                if (!screenshot.isRecycled) {
                    screenshot.recycle()
                }
            }
    }

    fun setScreenAttachment(attachment: AssistantAttachment) {
        setScreenAttachments(listOf(attachment))
    }

    fun setScreenAttachments(attachments: List<AssistantAttachment>) {
        val cleanAttachments = attachments.filter { it.base64Data.isNotBlank() }
        if (cleanAttachments.isEmpty()) return
        state.effectiveAttachments.forEach(::cleanupAttachment)
        updateState {
            copy(
                attachment = cleanAttachments.firstOrNull(),
                attachments = cleanAttachments,
                responseStatus = if (responseStatus == AssistantResponseStatus.ERROR) {
                    AssistantResponseStatus.IDLE
                } else {
                    responseStatus
                },
                errorText = null
            )
        }
    }

    fun clearAttachment() {
        state.effectiveAttachments.forEach(::cleanupAttachment)
        updateState { copy(attachment = null, attachments = emptyList()) }
    }

    fun submit(rawText: String): Boolean {
        if (state.isGenerating) return false
        val currentAttachments = state.effectiveAttachments
        val currentAttachment = currentAttachments.firstOrNull()
        val text = rawText.trim().ifBlank {
            if (currentAttachments.isNotEmpty()) {
                LocaleHelper.getString(application, "digital_assistant_default_screen_question")
            } else {
                ""
            }
        }
        if (text.isBlank() && currentAttachments.isEmpty()) return false

        lastSubmittedText = text
        lastSubmittedAttachment = currentAttachment
        val userMessage = AssistantMessage(
            id = nextMessageId++,
            role = AssistantMessageRole.USER,
            text = text,
            isScreenAnalysis = currentAttachments.isNotEmpty(),
            attachments = currentAttachments
        )
        val assistantMessageId = nextMessageId++
        val assistantMessage = AssistantMessage(
            id = assistantMessageId,
            role = AssistantMessageRole.ASSISTANT,
            text = "",
            status = AssistantResponseStatus.LOADING,
            isScreenAnalysis = currentAttachment != null
        )
        updateState {
            copy(
                attachment = null,
                attachments = emptyList(),
                responseStatus = AssistantResponseStatus.LOADING,
                messages = messages + userMessage + assistantMessage,
                userQuestion = text,
                answerText = "",
                errorText = null,
                isScreenAnalysis = currentAttachments.isNotEmpty()
            )
        }

        sendJob = scope.launch {
            runCatching {
                repository.send(
                    text = text,
                    attachment = currentAttachment,
                    onChatReady = { chatId ->
                        updateState { copy(activeChatId = chatId) }
                    },
                    onChunk = { chunk ->
                        updateState {
                            copy(
                                answerText = chunk,
                                messages = messages.replaceMessage(assistantMessageId) {
                                    it.copy(text = chunk, status = AssistantResponseStatus.LOADING)
                                }
                            )
                        }
                    }
                )
            }.onSuccess { result ->
                currentAttachments.forEach(::cleanupAttachment)
                lastSubmittedAttachment = null
                updateState {
                    val finalAnswer = result.fullAnswer.ifBlank { answerText }
                    copy(
                        responseStatus = AssistantResponseStatus.SUCCESS,
                        answerText = finalAnswer,
                        messages = messages.replaceMessage(assistantMessageId) {
                            it.copy(text = finalAnswer, status = AssistantResponseStatus.SUCCESS)
                        },
                        activeChatId = result.chatId
                    )
                }
            }.onFailure { error ->
                currentAttachments.forEach(::cleanupAttachment)
                val message = error.message
                    ?: LocaleHelper.getString(application, "digital_assistant_response_error")
                updateState {
                    copy(
                        responseStatus = AssistantResponseStatus.ERROR,
                        errorText = message,
                        messages = messages.replaceMessage(assistantMessageId) {
                            it.copy(text = message, status = AssistantResponseStatus.ERROR)
                        }
                    )
                }
            }
        }
        return true
    }

    fun retry() {
        val text = lastSubmittedText
        val attachment = lastSubmittedAttachment
        if (text.isBlank() && attachment == null) return
        updateState { copy(attachment = attachment) }
        submit(text)
    }

    fun createHandoff(context: Context, draftText: String): String {
        val chatId = state.activeChatId
        val store = DigitalAssistantHandoffStore(context)
        return if (!chatId.isNullOrBlank()) {
            store.saveChat(chatId, draftText, state.attachment)
        } else {
            store.saveDraft(draftText, state.attachment)
        }
    }

    fun cancelAndReset() {
        repository.cancelActiveResponse()
        sendJob?.cancel()
        sendJob = null
        state.effectiveAttachments.forEach(::cleanupAttachment)
        cleanupAttachment(lastSubmittedAttachment)
        cleanupAssistScreenshot()
        pendingAssistScreenshotRequest = null
        lastSubmittedAttachment = null
        updateState { DigitalAssistantState() }
    }

    fun resetIdleOnly() {
        if (state.isGenerating) return
        state.effectiveAttachments.forEach(::cleanupAttachment)
        cleanupAssistScreenshot()
        pendingAssistScreenshotRequest = null
        updateState { DigitalAssistantState() }
    }

    private fun updateState(reducer: DigitalAssistantState.() -> DigitalAssistantState) {
        state = state.reducer()
        listeners.forEach { it(state) }
    }

    private inline fun List<AssistantMessage>.replaceMessage(
        id: Long,
        transform: (AssistantMessage) -> AssistantMessage
    ): List<AssistantMessage> =
        map { message ->
            if (message.id == id) transform(message) else message
        }

    private fun buildAssistScreenshotAttachment(bitmap: Bitmap): AssistantAttachment {
        val output = ByteArrayOutputStream()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                error(LocaleHelper.getString(application, "digital_assistant_capture_failed"))
            }
            val bytes = output.toByteArray()
            val fileName = "assist_screen_${System.currentTimeMillis()}.jpg"
            val tempDir = File(application.cacheDir, ScreenCaptureManager.TEMP_DIR_NAME).apply { mkdirs() }
            val file = File(tempDir, fileName)
            file.writeBytes(bytes)
            return AssistantAttachment(
                mimeType = "image/jpeg",
                fileName = fileName,
                base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                cacheFilePath = file.absolutePath
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun cleanupAttachment(attachment: AssistantAttachment?) {
        attachment?.cacheFilePath?.let { path ->
            runCatching { java.io.File(path).delete() }
        }
    }

    private fun failPendingAssistScreenshotRequest() {
        pendingAssistScreenshotRequest?.let { request ->
            pendingAssistScreenshotRequest = null
            request.callback(assistScreenshotUnavailableResult())
        }
    }

    private fun assistScreenshotUnavailableResult(): Result<AssistantAttachment> =
        Result.failure(
            IllegalStateException(
                LocaleHelper.getString(application, "digital_assistant_assist_screenshot_unavailable")
            )
        )

    private fun cleanupAssistScreenshot() {
        assistScreenshot?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        assistScreenshot = null
    }

    private class PendingAssistScreenshotRequest(
        val callback: (Result<AssistantAttachment>) -> Unit
    )

    companion object {
        private const val ASSIST_SCREENSHOT_WAIT_MS = 650L
    }
}
