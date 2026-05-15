package com.example.chatapp

import android.content.Context
import com.example.chatapp.ai.AiActivityState
import com.example.chatapp.network.AiApiService
import com.example.chatapp.network.AiProvider
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ChatGenerationStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ChatGenerationSnapshot(
    val generationId: Long,
    val chatId: String?,
    val assistantSyncId: String,
    val accumulatedText: String,
    val status: ChatGenerationStatus,
    val errorMessage: String? = null,
    val activityState: AiActivityState? = null,
    val streamStarted: Boolean = false
) {
    val isRunning: Boolean
        get() = status == ChatGenerationStatus.RUNNING
}

data class ChatGenerationRequest(
    val generationId: Long,
    val chatId: String?,
    val assistantSyncId: String,
    val authToken: String,
    val provider: AiProvider,
    val modelKey: String,
    val messagesToKeep: List<JSONObject>,
    val currentMode: String?,
    val customInstructions: String,
    val chatContextSummary: String,
    val filesContext: String,
    val adultMode: Boolean
)

object ChatGenerationManager {
    private const val PREFS_NAME = "freechat_active_generations"
    private const val KEY_ACTIVE_GENERATIONS = "active_generations"
    private const val ERROR_TEXT = "Не удалось получить ответ"
    private const val SNAPSHOT_RETENTION_MS = 30_000L
    private const val PARTIAL_SAVE_INTERVAL_MS = 450L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _snapshots = MutableStateFlow<Map<String, ChatGenerationSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ChatGenerationSnapshot>> = _snapshots.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val notifiedResponses = mutableSetOf<String>()
    private var visibleChatId: String? = null
    private var isChatUiVisible: Boolean = false

    fun setVisibleChat(chatId: String?, visible: Boolean) {
        visibleChatId = chatId
        isChatUiVisible = visible
    }

    fun activeForChat(chatId: String?): ChatGenerationSnapshot? {
        if (chatId == null) return null
        return _snapshots.value.values.firstOrNull {
            it.chatId == chatId && it.status == ChatGenerationStatus.RUNNING
        }
    }

    fun start(context: Context, request: ChatGenerationRequest) {
        val appContext = context.applicationContext
        if (jobs[request.assistantSyncId]?.isActive == true) return

        updateSnapshot(
            ChatGenerationSnapshot(
                generationId = request.generationId,
                chatId = request.chatId,
                assistantSyncId = request.assistantSyncId,
                accumulatedText = "",
                status = ChatGenerationStatus.RUNNING
            )
        )
        persistActiveGeneration(appContext, request.chatId, request.assistantSyncId)

        val repository = ChatRepository(appContext)
        val job = scope.launch {
            var currentText = ""
            var terminalHandled = false
            var lastPartialSavedAt = 0L
            var delayedSaveJob: Job? = null

            suspend fun savePartial(text: String, force: Boolean) {
                if (request.chatId == null) return
                val now = System.currentTimeMillis()
                if (!force && now - lastPartialSavedAt < PARTIAL_SAVE_INTERVAL_MS) {
                    delayedSaveJob?.cancel()
                    delayedSaveJob = scope.launch(Dispatchers.IO) {
                        delay(PARTIAL_SAVE_INTERVAL_MS)
                        repository.updateAssistantMessage(
                            syncId = request.assistantSyncId,
                            content = text,
                            imageUrl = null,
                            attachmentData = null,
                            attachmentMimeType = null,
                            attachmentFileName = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    return
                }
                delayedSaveJob?.cancel()
                delayedSaveJob = null
                lastPartialSavedAt = now
                withContext(Dispatchers.IO) {
                    repository.updateAssistantMessage(
                        syncId = request.assistantSyncId,
                        content = text,
                        imageUrl = null,
                        attachmentData = null,
                        attachmentMimeType = null,
                        attachmentFileName = null,
                        updatedAt = now
                    )
                }
            }

            suspend fun fail(errorMessage: String) {
                if (terminalHandled) return
                terminalHandled = true
                delayedSaveJob?.cancel()
                val chatId = request.chatId
                if (chatId != null) {
                    withContext(Dispatchers.IO) {
                        repository.updateAssistantMessage(
                            syncId = request.assistantSyncId,
                            content = ERROR_TEXT,
                            imageUrl = null,
                            attachmentData = null,
                            attachmentMimeType = null,
                            attachmentFileName = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                }
                removeActiveGeneration(appContext, request.assistantSyncId)
                updateSnapshot(
                    snapshotFor(request).copy(
                        accumulatedText = errorMessage.ifBlank { ERROR_TEXT },
                        status = ChatGenerationStatus.FAILED,
                        errorMessage = errorMessage.ifBlank { ERROR_TEXT },
                        streamStarted = true
                    )
                )
                maybeNotify(appContext, chatId, request.assistantSyncId, isError = true)
                scheduleSnapshotRemoval(request.assistantSyncId)
            }

            try {
                AiApiService.fetchStreamingResponse(
                    authToken = request.authToken,
                    provider = request.provider,
                    modelKey = request.modelKey,
                    messagesToKeep = request.messagesToKeep,
                    currentMode = request.currentMode,
                    customInstructions = request.customInstructions,
                    chatContextSummary = request.chatContextSummary,
                    filesContext = request.filesContext,
                    adultMode = request.adultMode,
                    callback = object : AiApiService.StreamCallback {
                        override suspend fun onActivity(activityState: AiActivityState) {
                            updateSnapshot(
                                snapshotFor(request).copy(
                                    accumulatedText = currentText,
                                    activityState = activityState
                                )
                            )
                        }

                        override suspend fun onStreamStarted() {
                            updateSnapshot(
                                snapshotFor(request).copy(
                                    accumulatedText = currentText,
                                    activityState = null,
                                    streamStarted = true
                                )
                            )
                        }

                        override suspend fun onChunk(accumulatedText: String) {
                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("Generation was cancelled")
                            }
                            currentText = accumulatedText
                            updateSnapshot(
                                snapshotFor(request).copy(
                                    accumulatedText = accumulatedText,
                                    activityState = null,
                                    streamStarted = true
                                )
                            )
                            savePartial(accumulatedText, force = false)
                        }

                        override suspend fun onComplete(fullText: String) {
                            if (terminalHandled) return
                            terminalHandled = true
                            delayedSaveJob?.cancel()
                            val prepared = prepareAssistantResponse(appContext, fullText)
                            currentText = prepared.content
                            val chatId = request.chatId
                            if (chatId != null) {
                                withContext(Dispatchers.IO) {
                                    repository.updateAssistantMessage(
                                        syncId = request.assistantSyncId,
                                        content = contentForStorage(
                                            prepared.content,
                                            prepared.imageAttachment.imageUrl
                                        ),
                                        imageUrl = prepared.imageAttachment.imageUrl,
                                        attachmentData = prepared.imageAttachment.attachmentData,
                                        attachmentMimeType = prepared.imageAttachment.mimeType,
                                        attachmentFileName = prepared.imageAttachment.fileName,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                }
                            }
                            removeActiveGeneration(appContext, request.assistantSyncId)
                            updateSnapshot(
                                snapshotFor(request).copy(
                                    accumulatedText = prepared.content,
                                    status = ChatGenerationStatus.COMPLETED,
                                    activityState = null,
                                    streamStarted = true
                                )
                            )
                            maybeNotify(appContext, chatId, request.assistantSyncId, isError = false)
                            scheduleSnapshotRemoval(request.assistantSyncId)
                        }

                        override suspend fun onError(errorMessage: String) {
                            fail(errorMessage)
                        }
                    }
                )
            } catch (error: CancellationException) {
                removeActiveGeneration(appContext, request.assistantSyncId)
                if (request.chatId != null && currentText.isBlank()) {
                    withContext(Dispatchers.IO) {
                        repository.tombstoneMessage(request.assistantSyncId)
                    }
                } else if (request.chatId != null) {
                    savePartial(currentText, force = true)
                }
                updateSnapshot(
                    snapshotFor(request).copy(
                        accumulatedText = currentText,
                        status = ChatGenerationStatus.CANCELLED
                    )
                )
                scheduleSnapshotRemoval(request.assistantSyncId)
                throw error
            } catch (error: Exception) {
                SafeLog.w("ChatGenerationManager", "Generation failed", error)
                fail(error.message ?: ERROR_TEXT)
            } finally {
                delayedSaveJob?.cancel()
                jobs.remove(request.assistantSyncId)
            }
        }
        jobs[request.assistantSyncId] = job
    }

    fun cancel(assistantSyncId: String?) {
        if (assistantSyncId.isNullOrBlank()) return
        jobs[assistantSyncId]?.cancel()
    }

    fun recoverInterruptedGenerations(context: Context) {
        val appContext = context.applicationContext
        val active = readActiveGenerations(appContext)
        if (active.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val repository = ChatRepository(appContext)
            active.keys.forEach { assistantSyncId ->
                repository.updateAssistantMessage(
                    syncId = assistantSyncId,
                    content = ERROR_TEXT,
                    imageUrl = null,
                    attachmentData = null,
                    attachmentMimeType = null,
                    attachmentFileName = null,
                    updatedAt = System.currentTimeMillis()
                )
            }
            clearActiveGenerations(appContext)
        }
    }

    private fun updateSnapshot(snapshot: ChatGenerationSnapshot) {
        _snapshots.value = _snapshots.value + (snapshot.assistantSyncId to snapshot)
    }

    private fun snapshotFor(request: ChatGenerationRequest): ChatGenerationSnapshot =
        _snapshots.value[request.assistantSyncId] ?: ChatGenerationSnapshot(
            generationId = request.generationId,
            chatId = request.chatId,
            assistantSyncId = request.assistantSyncId,
            accumulatedText = "",
            status = ChatGenerationStatus.RUNNING
        )

    private fun scheduleSnapshotRemoval(assistantSyncId: String) {
        scope.launch {
            delay(SNAPSHOT_RETENTION_MS)
            _snapshots.value = _snapshots.value - assistantSyncId
        }
    }

    private fun maybeNotify(
        context: Context,
        chatId: String?,
        assistantSyncId: String,
        isError: Boolean
    ) {
        if (chatId.isNullOrBlank()) return
        if (!notifiedResponses.add(assistantSyncId)) return
        if (isChatUiVisible && visibleChatId == chatId) return
        ChatResponseNotifications.showAnswerReady(
            context = context,
            chatId = chatId,
            assistantSyncId = assistantSyncId,
            isError = isError
        )
    }

    private fun persistActiveGeneration(context: Context, chatId: String?, assistantSyncId: String) {
        if (chatId.isNullOrBlank()) return
        val active = readActiveGenerations(context).toMutableMap()
        active[assistantSyncId] = chatId
        writeActiveGenerations(context, active)
    }

    private fun removeActiveGeneration(context: Context, assistantSyncId: String) {
        val active = readActiveGenerations(context).toMutableMap()
        if (active.remove(assistantSyncId) != null) {
            writeActiveGenerations(context, active)
        }
    }

    private fun clearActiveGenerations(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_GENERATIONS)
            .apply()
    }

    private fun readActiveGenerations(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_GENERATIONS, null)
            ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.optString(key) }
                .filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    private fun writeActiveGenerations(context: Context, active: Map<String, String>) {
        val json = JSONObject()
        active.forEach { (assistantSyncId, chatId) ->
            json.put(assistantSyncId, chatId)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_GENERATIONS, json.toString())
            .apply()
    }

    private data class ImageAttachment(
        val imageUrl: String?,
        val attachmentData: String?,
        val mimeType: String?,
        val fileName: String?
    )

    private data class PreparedAssistantResponse(
        val content: String,
        val imageAttachment: ImageAttachment
    )

    private suspend fun prepareAssistantResponse(
        context: Context,
        content: String
    ): PreparedAssistantResponse = withContext(Dispatchers.IO) {
        val imageAttachment = extractImageAttachment(content)
        val inlineData = imageAttachment.attachmentData
        val imageUrl = imageAttachment.imageUrl
        if (
            inlineData.isNullOrBlank() ||
            imageUrl.isNullOrBlank() ||
            !imageUrl.startsWith("data:image", ignoreCase = true)
        ) {
            return@withContext PreparedAssistantResponse(content, imageAttachment)
        }

        val persisted = FileUtils.saveBase64FileToPersistentImage(
            context = context,
            base64Str = inlineData,
            fileName = imageAttachment.fileName,
            mimeType = imageAttachment.mimeType
        )
        if (persisted == null) {
            return@withContext PreparedAssistantResponse(content, imageAttachment)
        }

        val persistedUri = persisted.uri.toString()
        PreparedAssistantResponse(
            content = replaceMarkdownImageUrl(content, persistedUri),
            imageAttachment = ImageAttachment(
                imageUrl = persistedUri,
                attachmentData = null,
                mimeType = persisted.mimeType,
                fileName = persisted.fileName
            )
        )
    }

    private fun extractImageAttachment(content: String): ImageAttachment {
        val imageUrl = extractMarkdownImageUrl(content)
        if (imageUrl.isNullOrBlank()) {
            return ImageAttachment(null, null, null, null)
        }

        if (!imageUrl.startsWith("data:image", ignoreCase = true)) {
            return ImageAttachment(imageUrl, null, null, null)
        }

        val mimeType = imageUrl.substringAfter("data:", "")
            .substringBefore(";", "")
            .takeIf { it.isNotBlank() }
            ?: "image/png"
        val base64Data = imageUrl.substringAfter(",", "")
            .takeIf { it.isNotBlank() }

        return ImageAttachment(
            imageUrl = imageUrl,
            attachmentData = base64Data,
            mimeType = mimeType,
            fileName = "generated_image_${System.currentTimeMillis()}.png"
        )
    }

    private fun extractMarkdownImageUrl(content: String): String? {
        val imageStart = content.indexOf("![")
        if (imageStart == -1) return null
        val start = content.indexOf("](", imageStart)
        if (start == -1) return null
        val end = content.indexOf(')', start + 2)
        if (end == -1) return null
        return content.substring(start + 2, end).takeIf { imageUrl ->
            imageUrl.startsWith("http", ignoreCase = true) ||
                imageUrl.startsWith("data:image", ignoreCase = true) ||
                imageUrl.startsWith("content://", ignoreCase = true) ||
                imageUrl.startsWith("file://", ignoreCase = true)
        }
    }

    private fun replaceMarkdownImageUrl(content: String, replacementUrl: String): String {
        val imageStart = content.indexOf("![")
        if (imageStart == -1) return content
        val start = content.indexOf("](", imageStart)
        if (start == -1) return content
        val end = content.indexOf(')', start + 2)
        if (end == -1) return content
        return content.replaceRange(start + 2, end, replacementUrl)
    }

    private fun contentForStorage(content: String, imageUrl: String?): String {
        if (imageUrl.isNullOrBlank()) return content
        val isBase64Image = imageUrl.startsWith("data:image", ignoreCase = true)
        return if (isBase64Image && extractMarkdownImageUrl(content) != null) "" else content
    }
}
