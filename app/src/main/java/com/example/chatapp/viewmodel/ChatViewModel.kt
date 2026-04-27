package com.example.chatapp.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatapp.ChatEntity
import com.example.chatapp.ChatRepository
import com.example.chatapp.LocaleHelper
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.AuthRepository
import com.example.chatapp.data.NetworkResult
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.AiApiService
import com.example.chatapp.network.AiProvider
import com.example.chatapp.network.AiProviderSettings
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.OpenAiDirectService
import com.example.chatapp.network.dto.CreateChatShareResponse
import com.example.chatapp.network.dto.RevokeChatShareResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        val remainingRequests: Int?,
        val dailyRequestLimit: Int?,
        val resetsAt: String?
    )

    private data class ImageAttachment(
        val imageUrl: String?,
        val attachmentData: String?,
        val mimeType: String?,
        val fileName: String?
    )

    private val repository = ChatRepository(application)
    private val syncRepository = com.example.chatapp.SyncRepository(application)
    private val accountSettings = AccountScopedSettings(application)
    private val sessionStore = SharedPrefsAccountSessionStore(application)
    private val authRepository = AuthRepository(
        service = NetworkModule.createAuthApiService(com.example.chatapp.BuildConfig.APP_API_BASE_URL),
        localize = LocaleHelper.localizer(application)
    )
    private val aiProviderSettings = AiProviderSettings(accountSettings)

    // ──────── Состояние текущего чата ────────
    val chatHistory = mutableListOf<JSONObject>()
    var currentChatId: String? = null
    var currentChatTitle: String = ""
    var chatContextSummary: String = ""
    var isFirstMessage = true
    var isAnonymousChat = false
    var currentMode: String? = null
    var selectedFileUri: Uri? = null
    private var activeResponseJob: Job? = null

    /** Накопленная информация о всех файлах, прикреплённых за сессию чата */
    private val attachedFilesRegistry = mutableListOf<String>()

    // Кэш чатов для бокового меню
    var cachedChats: List<ChatEntity> = emptyList()
        private set

    /** Количество сообщений, храним в скользящем окне для контекста */
    private val CONTEXT_WINDOW_SIZE = 20

    // ──────── CRUD операции с чатами ────────

    fun performSync(onRefreshed: (() -> Unit)? = null) {
        viewModelScope.launch {
            syncRepository.trySync()
            cachedChats = repository.getAllChats()
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
                performSync()
                onTruncated?.invoke()
            }
        } else {
            onTruncated?.invoke()
        }
    }

    fun generateAndSetChatTitle(chatId: String, firstMessage: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val aiTitle = repository.generateChatTitle(firstMessage)
            if (aiTitle != null) {
                currentChatTitle = aiTitle
                repository.updateChatTitle(chatId, aiTitle)
                cachedChats = repository.getAllChats()
                onDone()
            }
        }
    }

    // ──────── Добавление в историю и отправка ────────

    fun addToChatHistoryAndSend(
        content: String,
        base64Data: String?,
        fileUri: String?,
        mimeType: String?,
        fileName: String?,
        fileContext: String?,
        onError: (String) -> Unit,
        onChunk: (String) -> Unit,
        onStreamComplete: () -> Unit
    ) {
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", if (content.isEmpty()) LocaleHelper.getString(getApplication(), "attachment_empty_text") else content)
            if (base64Data != null) put("base64", base64Data)
            if (fileUri != null) put("imageUri", fileUri)
            if (mimeType != null) put("mimeType", mimeType)
            if (fileName != null) put("fileName", fileName)
            if (fileContext != null) put("fileContext", fileContext)
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
                    attachmentContext = fileContext
                )
            }
        }

        fetchAiResponse(onChunk, onStreamComplete, onError)
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
        useModeOverride: Boolean = false
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
            val canSummarize = summAuthToken.isNotEmpty() ||
                (aiProviderSettings.getProvider() == AiProvider.OPENAI && aiProviderSettings.getOpenAiApiKey().isNotBlank())
            if (canSummarize) {
                launchSummarization(summAuthToken, messagesToSummarize)
            }
        }

        val customInstructions = accountSettings.getUserInstructions()
        val filesContext = buildFilesContext()
        val authToken = sessionStore.getAuthToken()?.trim().orEmpty()
        if (authToken.isBlank() && aiProviderSettings.getProvider() == AiProvider.VSEGPT) {
            onError(LocaleHelper.getString(getApplication(), "session_expired_sign_in"))
            return
        }

        activeResponseJob?.cancel()
        val responseJob = viewModelScope.launch {
            try {
                val streamCallback = object : AiApiService.StreamCallback {
                    override fun onChunk(accumulatedText: String) {
                        onChunk(accumulatedText)
                    }

                    override fun onComplete(fullText: String) {
                        val generatedImage = extractImageAttachment(fullText)
                        val assistantMessage = JSONObject().apply {
                            put("role", "assistant")
                            put("content", fullText)
                            generatedImage.imageUrl?.let { put("imageUri", it) }
                            generatedImage.attachmentData?.let { put("base64", it) }
                            generatedImage.mimeType?.let { put("mimeType", it) }
                            generatedImage.fileName?.let { put("fileName", it) }
                        }
                        chatHistory.add(assistantMessage)
                        saveCompletedResponse(fullText)
                        onComplete()
                    }

                    override fun onError(errorMessage: String) {
                        onError(errorMessage)
                    }
                }

                when (aiProviderSettings.getProvider()) {
                    AiProvider.OPENAI -> {
                        val apiKey = aiProviderSettings.getOpenAiApiKey()
                        OpenAiDirectService.fetchStreamingResponse(
                            apiKey = apiKey,
                            messagesToKeep = messagesToKeep,
                            currentMode = effectiveMode,
                            customInstructions = customInstructions,
                            chatContextSummary = chatContextSummary,
                            filesContext = filesContext,
                            callback = streamCallback
                        )
                    }
                    AiProvider.VSEGPT -> {
                        AiApiService.fetchStreamingResponse(
                            authToken = authToken,
                            messagesToKeep = messagesToKeep,
                            currentMode = effectiveMode,
                            customInstructions = customInstructions,
                            chatContextSummary = chatContextSummary,
                            filesContext = filesContext,
                            callback = streamCallback
                        )
                    }
                }
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
    }

    /**
     * Сохраняет завершённый ответ AI в БД.
     * Если чат ещё не создан — создаёт его со всей историей.
     * После сохранения всех сообщений запускает синхронизацию с сервером.
     */
    private fun saveCompletedResponse(fullText: String) {
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
                                attachmentContext = msg.optString("fileContext").takeIf { it.isNotEmpty() }
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
                                attachmentFileName = attachmentFileName ?: storedImage.fileName
                            )
                        }
                    }
                    val titleSource = chatHistory.firstOrNull {
                        it.getString("role") == "user"
                    }?.getString("content")
                        ?: LocaleHelper.getString(getApplication(), "label_file_analysis")
                    generateAndSetChatTitle(chatId, titleSource) {}
                    performSync()
                }
            }
        } else {
            currentChatId?.let { chatId ->
                viewModelScope.launch {
                    repository.addAssistantMessage(
                        chatId = chatId,
                        content = contentForStorage(fullText, generatedImage.imageUrl),
                        imageUrl = generatedImage.imageUrl,
                        attachmentData = generatedImage.attachmentData,
                        attachmentMimeType = generatedImage.mimeType,
                        attachmentFileName = generatedImage.fileName
                    )
                    cachedChats = repository.getAllChats()
                    performSync()
                }
            }
        }
    }

    /** Фоновая суммаризация контекста через внешний AI-сервис */
    private fun launchSummarization(authToken: String, messagesToSummarize: List<JSONObject>) {
        val hash = messagesToSummarize.hashCode().toString()
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("summary_cache", Context.MODE_PRIVATE)
        val cacheKey = "chat_${accountSettings.currentAccountKey()}_${currentChatId}_hash"
        val cachedHash = prefs.getString(cacheKey, "")

        if (hash != cachedHash) {
            viewModelScope.launch(Dispatchers.IO) {
                val summaryReply = when (aiProviderSettings.getProvider()) {
                    AiProvider.OPENAI -> OpenAiDirectService.summarizeMessages(
                        apiKey = aiProviderSettings.getOpenAiApiKey(),
                        messagesToSummarize = messagesToSummarize
                    )
                    AiProvider.VSEGPT -> AiApiService.summarizeMessages(
                        authToken = authToken,
                        messagesToSummarize = messagesToSummarize
                    )
                }
                if (summaryReply != null) {
                    val newSummary = if (chatContextSummary.isNotEmpty()) {
                        "$chatContextSummary\n$summaryReply"
                    } else summaryReply
                    chatContextSummary = newSummary

                    currentChatId?.let { repository.updateChatSummary(it, newSummary) }
                    prefs.edit().putString(cacheKey, hash).apply()
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

        onUpdated(readDailyQuotaSnapshot())
    }

    /** Расходует 1 запрос из лимита. Возвращает false если лимит исчерпан. */
    fun consumeLimit(): Boolean {
        val snapshot = readDailyQuotaSnapshot()
        if (snapshot.isUnlimited) {
            return true
        }

        val current = snapshot.remainingRequests ?: return true
        if (current <= 0) return false
        sessionStore.consumeDailyRequest()
        return true
    }

    /** Текущее количество оставшихся запросов */
    fun getRemainingLimitsLabel(): String {
        val snapshot = readDailyQuotaSnapshot()
        return when {
            snapshot.isUnlimited -> "∞"
            snapshot.remainingRequests != null -> snapshot.remainingRequests.toString()
            else -> "?"
        }
    }

    /** Добавляет запросы (награда за просмотр рекламы) */
    fun addLimits(amount: Int, onDone: () -> Unit) {
        sessionStore.addDailyRequests(amount)
        refreshDailyQuota {
            onDone()
        }
    }

    private fun readDailyQuotaSnapshot(): DailyQuotaSnapshot {
        return DailyQuotaSnapshot(
            isUnlimited = false,
            remainingRequests = sessionStore.getRemainingDailyRequests(),
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
