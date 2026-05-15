package com.example.chatapp.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatapp.ChatEntity
import com.example.chatapp.ChatRepository
import com.example.chatapp.LocaleHelper
import com.example.chatapp.ai.AiActivityState
import com.example.chatapp.ai.AiActivityStateManager
import com.example.chatapp.ai.AiActivityToolMapper
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.AuthRepository
import com.example.chatapp.data.NetworkResult
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.AiApiService
import com.example.chatapp.network.AiModelCatalog
import com.example.chatapp.network.AiProvider
import com.example.chatapp.network.AiProviderSettings
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.dto.CreateChatShareResponse
import com.example.chatapp.network.dto.RevokeChatShareResponse
import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * ViewModel для главного экрана чата.
 *
 * Хранит состояние чата (переживает повороты экрана), управляет:
 * - историей сообщений (chatHistory)
 * - текущим чатом (ID, заголовок, контекст)
 * - лимитами запросов
 * - CRUD-операциями через Repository
 * - отправкой и получением AI-ответов
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    data class DailyQuotaSnapshot(
        val isUnlimited: Boolean,
        val baseRemaining: Int?,
        val bonusRequests: Int,
        val totalRemaining: Int?,
        val dailyRequestLimit: Int?,
        val resetsAt: String?
    )

    private data class ImageAttachment(
        val imageUrl: String?,
        val attachmentData: String?,
        val mimeType: String?,
        val fileName: String?
    )

    private data class MessageMetadata(
        val syncId: String,
        val timestamp: Long,
        val updatedAt: Long,
        val editRevision: Int
    )

    private val repository = ChatRepository(application)
    private val syncRepository = com.example.chatapp.SyncRepository(application)
    private val accountSettings = AccountScopedSettings(application)
    private val sessionStore = SharedPrefsAccountSessionStore(application)
    private val summaryCacheStore = SummaryCacheStore(application)
    private val authRepository = AuthRepository(
        service = NetworkModule.createAuthApiService(com.example.chatapp.BuildConfig.APP_API_BASE_URL),
        localize = LocaleHelper.localizer(application)
    )
    private val aiProviderSettings = AiProviderSettings(accountSettings)
    private val aiActivityManager = AiActivityStateManager(viewModelScope)
    val aiActivityState: StateFlow<com.example.chatapp.ai.AiActivitySnapshot?> = aiActivityManager.state

    // ──────── Состояние текущего чата ────────
    val chatHistory = mutableListOf<JSONObject>()
    var currentChatId: String? = null
    var currentChatTitle: String = ""
    var chatContextSummary: String = ""
    var isFirstMessage = true
    var isAnonymousChat = false
    var currentMode: String? = null
    var popularNewsQueries: List<String> = emptyList()
        private set
    var selectedFileUri: Uri? = null
    private var activeResponseJob: Job? = null
    var onChatListUpdated: (() -> Unit)? = null

    /** Накопленная информация о всех файлах, прикреплённых за сессию чата */
    private val attachedFilesRegistry = mutableListOf<String>()
    private val titleGenerationInFlight = mutableSetOf<String>()
    private val titleGenerationCompletedChats = mutableSetOf<String>()

    // Кэш чатов для бокового меню
    var cachedChats: List<ChatEntity> = emptyList()
        private set

    /** Количество сообщений, храним в скользящем окне для контекста */
    private val CONTEXT_WINDOW_SIZE = 20

    private fun notifyChatListUpdated() {
        SafeLog.d("ChatViewModel", "Chat history cache updated count=${cachedChats.size}")
        onChatListUpdated?.invoke()
    }

    // ──────── CRUD операции с чатами ────────

    fun performSync(onRefreshed: (() -> Unit)? = null) {
        viewModelScope.launch {
            syncRepository.trySync()
            cachedChats = repository.getAllChats()
            notifyChatListUpdated()
            onRefreshed?.invoke()
        }
    }

    fun loadChats(onLoaded: () -> Unit) {
        viewModelScope.launch {
            cachedChats = repository.getAllChats()
            onLoaded()
            performSync()
        }
    }

    fun refreshChats(onRefreshed: () -> Unit) {
        viewModelScope.launch {
            cachedChats = repository.getAllChats()
            onRefreshed()
        }
    }

    fun createNewChat(temporaryTitle: String, callback: (String) -> Unit) {
        viewModelScope.launch {
            val chatId = repository.createChat(temporaryTitle)
            cachedChats = repository.getAllChats()
            notifyChatListUpdated()
            callback(chatId)
        }
    }

    fun deleteChat(chatId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
            cachedChats = repository.getAllChats()
            onDeleted()
            performSync()
        }
    }

    fun renameChat(chatId: String, newTitle: String, onRenamed: () -> Unit) {
        viewModelScope.launch {
            repository.updateChatTitle(chatId, newTitle)
            if (currentChatId == chatId) currentChatTitle = newTitle
            cachedChats = repository.getAllChats()
            onRenamed()
            performSync()
        }
    }

    fun togglePinChat(chatId: String, isPinned: Boolean, onToggled: () -> Unit) {
        viewModelScope.launch {
            repository.togglePinChat(chatId, !isPinned)
            cachedChats = repository.getAllChats()
            onToggled()
            performSync()
        }
    }

    suspend fun getChatById(chatId: String) = repository.getChatById(chatId)

    suspend fun getMessages(chatId: String) = repository.getMessages(chatId)

    fun createShareLink(
        chatId: String,
        onResult: (Result<CreateChatShareResponse>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(repository.createShareLink(chatId))
        }
    }

    fun revokeShareLinks(
        chatId: String,
        onResult: (Result<RevokeChatShareResponse>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(repository.revokeShareLinks(chatId))
        }
    }

    fun importSharedChat(
        token: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.importSharedChat(token)
            if (result.isSuccess) {
                cachedChats = repository.getAllChats()
            }
            onResult(result)
        }
    }

    /**
     * Удаляет все сообщения из chatHistory начиная с позиции [fromIndex],
     * а также из БД (если чат уже сохранён).
     */
    fun truncateHistoryFrom(fromIndex: Int, onTruncated: (() -> Unit)? = null) {
        // Удаляем из in-memory списка
        while (chatHistory.size > fromIndex) {
            chatHistory.removeAt(chatHistory.lastIndex)
        }
        // Удаляем из БД
        val chatId = currentChatId
        if (chatId != null) {
            viewModelScope.launch {
                repository.deleteMessagesFromIndex(chatId, fromIndex)
                chatContextSummary = ""
                clearSummaryCache(chatId)
                syncRepository.trySync()
                cachedChats = repository.getAllChats()
                onTruncated?.invoke()
            }
        } else {
            onTruncated?.invoke()
        }
    }

    fun editUserMessageAndPrepareResponse(
        historyIndex: Int,
        content: String,
        base64Data: String?,
        fileUri: String?,
        mimeType: String?,
        fileName: String?,
        fileContext: String?,
        onPrepared: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (historyIndex !in 0 until chatHistory.size) {
            onError("Message not found")
            return
        }

        val existingMessage = chatHistory[historyIndex]
        if (existingMessage.optString("role") != "user") {
            onError("Only user messages can be edited")
            return
        }

        val chatId = currentChatId
        val syncId = existingMessage.optString("syncId").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        existingMessage.put("syncId", syncId)
        val storedContent = contentForUserMessage(content)

        viewModelScope.launch {
            try {
                val result = if (chatId != null) {
                    repository.updateUserMessageAndTombstoneTail(
                        chatId = chatId,
                        syncId = syncId,
                        historyIndex = historyIndex,
                        content = storedContent,
                        imageUrl = fileUri,
                        attachmentData = base64Data,
                        attachmentMimeType = mimeType,
                        attachmentFileName = fileName,
                        attachmentContext = fileContext
                    )
                } else {
                    val previous = ensureMessageMetadata(existingMessage)
                    ChatRepository.MessageEditResult(
                        syncId = syncId,
                        timestamp = previous.timestamp,
                        updatedAt = System.currentTimeMillis(),
                        editRevision = previous.editRevision + 1
                    )
                }

                applyEditedUserMessageToHistory(
                    historyIndex = historyIndex,
                    content = storedContent,
                    base64Data = base64Data,
                    fileUri = fileUri,
                    mimeType = mimeType,
                    fileName = fileName,
                    fileContext = fileContext,
                    metadata = MessageMetadata(
                        syncId = result.syncId,
                        timestamp = result.timestamp,
                        updatedAt = result.updatedAt,
                        editRevision = result.editRevision
                    )
                )

                chatContextSummary = ""
                clearSummaryCache(chatId)
                if (chatId != null) {
                    syncRepository.trySync()
                    cachedChats = repository.getAllChats()
                }
                onPrepared()
            } catch (e: Exception) {
                onError(e.message ?: "Unable to edit message")
            }
        }
    }

    fun generateAndSetChatTitle(
        chatId: String,
        firstMessage: String,
        firstAssistantMessage: String? = null,
        onDone: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!titleGenerationInFlight.add(chatId)) {
                SafeLog.d("ChatViewModel", "Chat title generation skipped because request is already in flight")
                onDone(false)
                return@launch
            }

            try {
                val aiTitle = repository.generateChatTitle(firstMessage, firstAssistantMessage)
                if (aiTitle != null) {
                    if (currentChatId == chatId) {
                        currentChatTitle = aiTitle
                    }
                    repository.updateChatTitle(chatId, aiTitle)
                    titleGenerationCompletedChats.add(chatId)
                    cachedChats = repository.getAllChats()
                    SafeLog.d("ChatViewModel", "Chat title saved and history cache refreshed hasTitle=true")
                    notifyChatListUpdated()
                    syncRepository.trySync()
                    cachedChats = repository.getAllChats()
                    notifyChatListUpdated()
                    onDone(true)
                } else {
                    SafeLog.d("ChatViewModel", "Chat title generation returned empty title; keeping temporary title")
                    onDone(false)
                }
            } finally {
                titleGenerationInFlight.remove(chatId)
            }
        }
    }

    private fun maybeGenerateTitleAfterSuccessfulResponse(chatId: String) {
        val firstUserMessage = firstMessageContent("user") ?: return
        if (!shouldGenerateTitle(chatId, firstUserMessage)) {
            return
        }

        val firstAssistantMessage = firstMessageContent("assistant")
        SafeLog.d(
            "ChatViewModel",
            "Chat title generation scheduled chatId=${chatId.take(8)} hasAssistantMessage=${!firstAssistantMessage.isNullOrBlank()}"
        )
        generateAndSetChatTitle(chatId, firstUserMessage, firstAssistantMessage)
    }

    private fun shouldGenerateTitle(chatId: String, firstUserMessage: String): Boolean {
        if (isAnonymousChat || titleGenerationCompletedChats.contains(chatId)) {
            return false
        }

        val title = currentChatTitle.trim()
        val firstMessageDraft = firstUserMessage.take(60).trim()
        val temporaryTitles = setOf(
            "",
            LocaleHelper.getString(getApplication(), "label_new_chat"),
            LocaleHelper.getString(getApplication(), "label_file_analysis"),
            LocaleHelper.getString(getApplication(), "untitled_chat"),
            "New chat",
            "Новый чат",
            "Untitled",
            "Без названия"
        )

        return title in temporaryTitles || (firstMessageDraft.isNotBlank() && title == firstMessageDraft)
    }

    private fun firstMessageContent(role: String): String? =
        chatHistory.firstOrNull { it.optString("role") == role }
            ?.optString("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    // ──────── Добавление в историю и отправка ────────

    fun addToChatHistoryAndSend(
        content: String,
        base64Data: String?,
        fileUri: String?,
        mimeType: String?,
        fileName: String?,
        fileContext: String?,
        activityGenerationId: Long = System.currentTimeMillis(),
        onError: (String) -> Unit,
        onChunk: (String) -> Unit,
        onStreamComplete: () -> Unit
    ) {
        val now = System.currentTimeMillis()
        val userSyncId = UUID.randomUUID().toString()
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", contentForUserMessage(content))
            if (base64Data != null) put("base64", base64Data)
            if (fileUri != null) put("imageUri", fileUri)
            if (mimeType != null) put("mimeType", mimeType)
            if (fileName != null) put("fileName", fileName)
            if (fileContext != null) put("fileContext", fileContext)
            put("syncId", userSyncId)
            put("timestamp", now)
            put("updatedAt", now)
            put("editRevision", 0)
            put("isDeleted", false)
        }
        chatHistory.add(userMessage)

        // Регистрируем файл, чтобы модель всегда помнила его содержимое
        if (fileName != null || fileContext != null) {
            registerAttachedFile(fileName, mimeType, fileContext)
        }

        // Сохраняем сразу, если чат уже существует
        currentChatId?.let {
            viewModelScope.launch {
                repository.addUserMessage(
                    chatId = it,
                    content = userMessage.getString("content"),
                    imageUrl = fileUri,
                    attachmentData = base64Data,
                    attachmentMimeType = mimeType,
                    attachmentFileName = fileName,
                    attachmentContext = fileContext,
                    syncId = userSyncId,
                    timestamp = now,
                    updatedAt = now
                )
            }
        }

        fetchAiResponse(
            onChunk = onChunk,
            onComplete = onStreamComplete,
            onError = onError,
            activityGenerationId = activityGenerationId
        )
    }

    /**
     * Основной метод отправки запроса к AI.
     * Управляет контекстным окном и суммаризацией.
     */
    fun fetchAiResponse(
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        modeOverride: String? = null,
        useModeOverride: Boolean = false,
        activityGenerationId: Long = System.currentTimeMillis()
    ) {
        val effectiveMode = if (useModeOverride) modeOverride else currentMode
        val lastN = CONTEXT_WINDOW_SIZE
        val messagesToKeep = if (chatHistory.size > lastN + 1) {
            chatHistory.takeLast(lastN + 1)
        } else {
            chatHistory
        }

        val messagesToSummarize = if (chatHistory.size > lastN + 1) {
            chatHistory.dropLast(lastN + 1)
        } else emptyList()

        // Фоновая суммаризация при необходимости
        if (messagesToSummarize.isNotEmpty() && currentChatId != null) {
            val summAuthToken = sessionStore.getAuthToken()?.trim().orEmpty()
            if (summAuthToken.isNotEmpty()) {
                launchSummarization(summAuthToken, messagesToSummarize)
            }
        }

        val customInstructions = accountSettings.getUserInstructions()
        val filesContext = buildFilesContext()
        val authToken = sessionStore.getAuthToken()?.trim().orEmpty()
        val adultMode = aiProviderSettings.isAdultModeEnabled()
        val effectiveProvider = if (adultMode) AiProvider.VSEGPT else aiProviderSettings.getProvider()
        val effectiveModelKey = if (effectiveProvider == aiProviderSettings.getProvider()) {
            aiProviderSettings.getModelKey()
        } else {
            AiModelCatalog.defaultModelKey(effectiveProvider)
        }
        if (authToken.isBlank()) {
            onError(LocaleHelper.getString(getApplication(), "session_expired_sign_in"))
            return
        }

        val initialActivityState = AiActivityToolMapper.initialStateForRequest(effectiveMode, messagesToKeep)
        activeResponseJob?.cancel()
        aiActivityManager.begin(activityGenerationId, initialActivityState)
        val responseJob = viewModelScope.launch {
            try {
                val streamCallback = object : AiApiService.StreamCallback {
                    override fun onActivity(activityState: AiActivityState) {
                        aiActivityManager.update(activityGenerationId, activityState)
                    }

                    override fun onStreamStarted() {
                        aiActivityManager.markStreamStarted(activityGenerationId)
                    }

                    override fun onChunk(accumulatedText: String) {
                        onChunk(accumulatedText)
                    }

                    override fun onComplete(fullText: String) {
                        val generatedImage = extractImageAttachment(fullText)
                        val now = System.currentTimeMillis()
                        val assistantMessage = JSONObject().apply {
                            put("role", "assistant")
                            put("content", fullText)
                            generatedImage.imageUrl?.let { put("imageUri", it) }
                            generatedImage.attachmentData?.let { put("base64", it) }
                            generatedImage.mimeType?.let { put("mimeType", it) }
                            generatedImage.fileName?.let { put("fileName", it) }
                            put("syncId", UUID.randomUUID().toString())
                            put("timestamp", now)
                            put("updatedAt", now)
                            put("editRevision", 0)
                            put("isDeleted", false)
                        }
                        chatHistory.add(assistantMessage)
                        saveCompletedResponse(fullText, assistantMessage)
                        aiActivityManager.complete(activityGenerationId)
                        onComplete()
                    }

                    override fun onError(errorMessage: String) {
                        aiActivityManager.fail(activityGenerationId)
                        onError(errorMessage)
                    }
                }

                AiApiService.fetchStreamingResponse(
                    authToken = authToken,
                    provider = effectiveProvider,
                    modelKey = effectiveModelKey,
                    messagesToKeep = messagesToKeep,
                    currentMode = effectiveMode,
                    customInstructions = customInstructions,
                    chatContextSummary = chatContextSummary,
                    filesContext = filesContext,
                    adultMode = adultMode,
                    callback = streamCallback
                )
            } finally {
                if (activeResponseJob == coroutineContext[Job]) {
                    activeResponseJob = null
                }
            }
        }
        activeResponseJob = responseJob
    }

    fun cancelActiveResponse() {
        activeResponseJob?.cancel()
        activeResponseJob = null
        aiActivityManager.cancelActive()
    }

    /**
     * Сохраняет завершённый ответ AI в БД.
     * Если чат ещё не создан — создаёт его со всей историей.
     * После сохранения всех сообщений запускает синхронизацию с сервером.
     */
    private fun saveCompletedResponse(fullText: String, assistantHistoryMessage: JSONObject? = null) {
        val generatedImage = extractImageAttachment(fullText)
        val hasContent = fullText.isNotBlank() || !generatedImage.imageUrl.isNullOrBlank()
        if (!hasContent) {
            return
        }

        if (currentChatId == null && !isAnonymousChat) {
            createNewChat(currentChatTitle) { chatId ->
                currentChatId = chatId
                viewModelScope.launch {
                    for (msg in chatHistory) {
                        val role = msg.getString("role")
                        val content = msg.getString("content")
                        val metadata = ensureMessageMetadata(msg)
                        val imageUrl = msg.optString("imageUri").ifBlank {
                            msg.optString("imageUrl")
                        }.takeIf { it.isNotEmpty() }
                        val attachmentData = msg.optString("base64").takeIf { it.isNotEmpty() }
                        val attachmentMimeType = msg.optString("mimeType").takeIf { it.isNotEmpty() }
                        val attachmentFileName = msg.optString("fileName").takeIf { it.isNotEmpty() }
                        if (role == "user") {
                            repository.addUserMessage(
                                chatId = chatId,
                                content = content,
                                imageUrl = imageUrl,
                                attachmentData = attachmentData,
                                attachmentMimeType = attachmentMimeType,
                                attachmentFileName = attachmentFileName,
                                attachmentContext = msg.optString("fileContext").takeIf { it.isNotEmpty() },
                                syncId = metadata.syncId,
                                timestamp = metadata.timestamp,
                                updatedAt = metadata.updatedAt,
                                editRevision = metadata.editRevision
                            )
                        } else if (role == "assistant") {
                            val storedImage = extractImageAttachment(content)
                            val storedImageUrl = imageUrl ?: storedImage.imageUrl
                            repository.addAssistantMessage(
                                chatId = chatId,
                                content = contentForStorage(content, storedImageUrl),
                                imageUrl = storedImageUrl,
                                attachmentData = attachmentData ?: storedImage.attachmentData,
                                attachmentMimeType = attachmentMimeType ?: storedImage.mimeType,
                                attachmentFileName = attachmentFileName ?: storedImage.fileName,
                                syncId = metadata.syncId,
                                timestamp = metadata.timestamp,
                                updatedAt = metadata.updatedAt,
                                editRevision = metadata.editRevision,
                                reaction = normalizeReaction(msg.optString("reaction").takeIf { it.isNotBlank() })
                            )
                        }
                    }
                    cachedChats = repository.getAllChats()
                    notifyChatListUpdated()
                    maybeGenerateTitleAfterSuccessfulResponse(chatId)
                    performSync()
                }
            }
        } else {
            currentChatId?.let { chatId ->
                viewModelScope.launch {
                    val metadata = ensureMessageMetadata(
                        assistantHistoryMessage ?: chatHistory.lastOrNull() ?: JSONObject()
                    )
                    repository.addAssistantMessage(
                        chatId = chatId,
                        content = contentForStorage(fullText, generatedImage.imageUrl),
                        imageUrl = generatedImage.imageUrl,
                        attachmentData = generatedImage.attachmentData,
                        attachmentMimeType = generatedImage.mimeType,
                        attachmentFileName = generatedImage.fileName,
                        syncId = metadata.syncId,
                        timestamp = metadata.timestamp,
                        updatedAt = metadata.updatedAt,
                        editRevision = metadata.editRevision,
                        reaction = normalizeReaction(
                            (assistantHistoryMessage ?: chatHistory.lastOrNull())
                                ?.optString("reaction")
                                ?.takeIf { it.isNotBlank() }
                        )
                    )
                    cachedChats = repository.getAllChats()
                    notifyChatListUpdated()
                    maybeGenerateTitleAfterSuccessfulResponse(chatId)
                    performSync()
                }
            }
        }
    }

    /** Фоновая суммаризация контекста через внешний AI-сервис */
    private fun launchSummarization(authToken: String, messagesToSummarize: List<JSONObject>) {
        val hash = messagesToSummarize.hashCode().toString()
        val accountKey = accountSettings.currentAccountKey()
        val cachedHash = summaryCacheStore.cachedHash(accountKey, currentChatId)

        if (hash != cachedHash) {
            viewModelScope.launch(Dispatchers.IO) {
                val provider = aiProviderSettings.getProvider()
                val summaryReply = AiApiService.summarizeMessages(
                    authToken = authToken,
                    provider = provider,
                    modelKey = aiProviderSettings.getModelKey(),
                    messagesToSummarize = messagesToSummarize
                )
                if (summaryReply != null) {
                    val newSummary = if (chatContextSummary.isNotEmpty()) {
                        "$chatContextSummary\n$summaryReply"
                    } else summaryReply
                    chatContextSummary = newSummary

                    currentChatId?.let { repository.updateChatSummary(it, newSummary) }
                    summaryCacheStore.saveHash(accountKey, currentChatId, hash)
                }
            }
        }
    }


    // ──────── Лимиты запросов ────────

    fun refreshDailyQuota(onUpdated: (DailyQuotaSnapshot) -> Unit = {}) {
        val token = sessionStore.getAuthToken()?.trim().orEmpty()
        if (token.isBlank()) {
            onUpdated(readDailyQuotaSnapshot())
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val service = NetworkModule.createAiLimitsApiService(
                    com.example.chatapp.BuildConfig.APP_API_BASE_URL,
                    token
                )
                val response = service.getLimits()
                if (response.isSuccessful) {
                    response.body()?.let { limits ->
                        sessionStore.saveDailyQuota(
                            dailyLimit = limits.dailyLimit,
                            baseRemaining = limits.baseRemaining,
                            bonusRequests = limits.bonusRequests,
                            resetAt = limits.resetAt
                        )
                    }
                }
            }
            onUpdated(readDailyQuotaSnapshot())
        }
    }

    /** Расходует 1 запрос из лимита. Возвращает false если лимит исчерпан. */
    fun consumeLimit(): Boolean {
        val snapshot = readDailyQuotaSnapshot()
        if (snapshot.isUnlimited) {
            return true
        }

        val current = snapshot.totalRemaining ?: return true
        if (current <= 0) return false
        return true
    }

    /** Текущее количество оставшихся запросов */
    fun getRemainingLimitsLabel(): String {
        val snapshot = readDailyQuotaSnapshot()
        return when {
            snapshot.isUnlimited -> "∞"
            snapshot.totalRemaining != null -> snapshot.totalRemaining.toString()
            else -> "?"
        }
    }

    /** Добавляет запросы (награда за просмотр рекламы) */
    fun addLimits(amount: Int, onDone: () -> Unit) {
        val token = sessionStore.getAuthToken()?.trim().orEmpty()
        if (token.isBlank()) {
            sessionStore.addDailyRequests(amount)
            onDone()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val service = NetworkModule.createAiLimitsApiService(
                    com.example.chatapp.BuildConfig.APP_API_BASE_URL,
                    token
                )
                val response = service.rewardAd()
                if (response.isSuccessful) {
                    response.body()?.let { limits ->
                        sessionStore.saveDailyQuota(
                            dailyLimit = limits.dailyLimit,
                            baseRemaining = limits.baseRemaining,
                            bonusRequests = limits.bonusRequests,
                            resetAt = limits.resetAt
                        )
                    }
                }
            }
            refreshDailyQuota {
                onDone()
            }
        }
    }

    fun loadPopularNewsQueries(onUpdated: (List<String>) -> Unit) {
        val token = sessionStore.getAuthToken()?.trim().orEmpty()
        if (token.isBlank() || aiProviderSettings.getProvider() != AiProvider.VSEGPT) {
            popularNewsQueries = emptyList()
            onUpdated(emptyList())
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val queries = runCatching {
                val service = NetworkModule.createAiLimitsApiService(
                    com.example.chatapp.BuildConfig.APP_API_BASE_URL,
                    token
                )
                val response = service.getTrendingQueries("ru")
                if (response.isSuccessful) {
                    response.body()?.queries.orEmpty()
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
            popularNewsQueries = queries
            onUpdated(queries)
        }
    }

    private fun readDailyQuotaSnapshot(): DailyQuotaSnapshot {
        val baseRemaining = sessionStore.getBaseRemainingDailyRequests()
        val bonusRequests = sessionStore.getBonusRequests()
        val totalRemaining = baseRemaining?.plus(bonusRequests)
        return DailyQuotaSnapshot(
            isUnlimited = false,
            baseRemaining = baseRemaining,
            bonusRequests = bonusRequests,
            totalRemaining = totalRemaining,
            dailyRequestLimit = sessionStore.getDailyRequestLimit(),
            resetsAt = sessionStore.getDailyQuotaResetsAt()
        )
    }

    // ──────── Сброс чата ────────

    fun resetChatState() {
        chatHistory.clear()
        currentChatId = null
        currentChatTitle = ""
        chatContextSummary = ""
        isFirstMessage = true
        isAnonymousChat = false
        currentMode = null
        selectedFileUri = null
        cancelActiveResponse()
        attachedFilesRegistry.clear()
    }

    // ──────── Реестр прикреплённых файлов ────────

    /**
     * Регистрирует файл в реестре, чтобы модель сохраняла знание
     * о его содержимом даже после выхода сообщения из контекстного окна.
     */
    private fun registerAttachedFile(fileName: String?, mimeType: String?, fileContext: String?) {
        val entry = buildString {
            append("--- ").append(LocaleHelper.getString(getApplication(), "attachment_context_file_summary"))
            if (!fileName.isNullOrBlank()) append(": $fileName")
            if (!mimeType.isNullOrBlank()) append(" ($mimeType)")
            append(" ---")
            if (!fileContext.isNullOrBlank()) {
                append("\n")
                // Сохраняем полное содержимое файла
                append(fileContext)
            }
        }
        attachedFilesRegistry.add(entry)
    }

    /**
     * Строит блок контекста с содержимым всех прикреплённых файлов
     * для включения в системный промпт.
     */
    private fun buildFilesContext(): String {
        val restoredFileContexts = chatHistory.mapNotNull { message ->
            message.optString("fileContext").takeIf { it.isNotBlank() }
        }
        val allContexts = (attachedFilesRegistry + restoredFileContexts).distinct()
        if (allContexts.isEmpty()) return ""
        return allContexts.joinToString("\n\n")
    }

    private fun contentForUserMessage(content: String): String {
        return if (content.isEmpty()) {
            LocaleHelper.getString(getApplication(), "attachment_empty_text")
        } else {
            content
        }
    }

    fun setAssistantReaction(syncId: String, reaction: String?) {
        val normalizedReaction = normalizeReaction(reaction)
        chatHistory.firstOrNull {
            it.optString("syncId") == syncId && it.optString("role") == "assistant"
        }?.let { message ->
            if (normalizedReaction == null) {
                message.remove("reaction")
            } else {
                message.put("reaction", normalizedReaction)
            }
        }

        if (currentChatId != null) {
            viewModelScope.launch {
                repository.updateMessageReaction(syncId, normalizedReaction)
            }
        }
    }

    private fun normalizeReaction(reaction: String?): String? =
        when (reaction) {
            "like", "dislike" -> reaction
            else -> null
        }

    private fun ensureMessageMetadata(message: JSONObject): MessageMetadata {
        val syncId = message.optString("syncId").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { message.put("syncId", it) }
        val timestamp = message.optLong("timestamp", 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis().also { message.put("timestamp", it) }
        val updatedAt = message.optLong("updatedAt", 0L).takeIf { it > 0L }
            ?: timestamp.also { message.put("updatedAt", it) }
        val editRevision = message.optInt("editRevision", 0).coerceAtLeast(0)
        message.put("editRevision", editRevision)
        message.put("isDeleted", false)
        return MessageMetadata(syncId, timestamp, updatedAt, editRevision)
    }

    private fun applyEditedUserMessageToHistory(
        historyIndex: Int,
        content: String,
        base64Data: String?,
        fileUri: String?,
        mimeType: String?,
        fileName: String?,
        fileContext: String?,
        metadata: MessageMetadata
    ) {
        while (chatHistory.size > historyIndex + 1) {
            chatHistory.removeAt(chatHistory.lastIndex)
        }

        val message = chatHistory[historyIndex]
        listOf("base64", "imageUri", "imageUrl", "mimeType", "fileName", "fileContext").forEach {
            message.remove(it)
        }

        message.put("role", "user")
        message.put("content", content)
        base64Data?.let { message.put("base64", it) }
        fileUri?.let { message.put("imageUri", it) }
        mimeType?.let { message.put("mimeType", it) }
        fileName?.let { message.put("fileName", it) }
        fileContext?.let { message.put("fileContext", it) }
        message.put("syncId", metadata.syncId)
        message.put("timestamp", metadata.timestamp)
        message.put("updatedAt", metadata.updatedAt)
        message.put("editRevision", metadata.editRevision)
        message.put("isDeleted", false)
    }

    private fun clearSummaryCache(chatId: String?) {
        summaryCacheStore.clear(accountSettings.currentAccountKey(), chatId)
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
        val start = content.indexOf("](")
        if (start == -1 || !content.contains("![")) {
            return null
        }

        val end = content.indexOf(')', start + 2)
        if (end == -1) {
            return null
        }

        return content.substring(start + 2, end).takeIf { imageUrl ->
            imageUrl.startsWith("http", ignoreCase = true) ||
                imageUrl.startsWith("data:image", ignoreCase = true)
        }
    }

    private fun contentForStorage(content: String, imageUrl: String?): String {
        if (imageUrl.isNullOrBlank()) {
            return content
        }
        val isBase64Image = imageUrl.startsWith("data:image", ignoreCase = true)
        return if (isBase64Image && extractMarkdownImageUrl(content) != null) {
            ""
        } else {
            content
        }
    }
}
