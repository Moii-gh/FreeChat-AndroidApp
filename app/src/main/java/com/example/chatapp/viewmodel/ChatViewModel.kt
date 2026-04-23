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
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.AiApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

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

    private val repository = ChatRepository(application)
    private val syncRepository = com.example.chatapp.SyncRepository(application)
    private val accountSettings = AccountScopedSettings(application)
    private val sessionStore = SharedPrefsAccountSessionStore(application)

    // ──────── Состояние текущего чата ────────
    val chatHistory = mutableListOf<JSONObject>()
    var currentChatId: String? = null
    var currentChatTitle: String = ""
    var chatContextSummary: String = ""
    var isFirstMessage = true
    var isAnonymousChat = false
    var currentMode: String? = null
    var selectedFileUri: Uri? = null

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
            performSync()
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

    fun deleteAllChats(onCleared: () -> Unit) {
        viewModelScope.launch {
            repository.deleteAllChats()
            cachedChats = emptyList()
            onCleared()
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

    // ──────── Сохранение сообщений в БД ────────

    fun saveUserMessage(chatId: String, content: String, fileUri: String? = null) {
        viewModelScope.launch {
            repository.addUserMessage(chatId, content, fileUri)
            performSync()
        }
    }

    fun saveAssistantMessage(chatId: String, content: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            repository.addAssistantMessage(chatId, content)
            cachedChats = repository.getAllChats()
            onSaved()
            performSync()
        }
    }

    fun deleteLastAssistantMessage(chatId: String) {
        viewModelScope.launch {
            repository.deleteLastAssistantMessage(chatId)
            performSync()
        }
    }

    /**
     * Удаляет все сообщения из chatHistory начиная с позиции [fromIndex],
     * а также из БД (если чат уже сохранён).
     */
    fun truncateHistoryFrom(fromIndex: Int) {
        // Удаляем из in-memory списка
        while (chatHistory.size > fromIndex) {
            chatHistory.removeAt(chatHistory.lastIndex)
        }
        // Удаляем из БД
        currentChatId?.let { chatId ->
            viewModelScope.launch {
                repository.deleteMessagesFromIndex(chatId, fromIndex)
                performSync()
            }
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
        fileText: String?,
        onError: (String) -> Unit,
        onChunk: (String) -> Unit,
        onStreamComplete: () -> Unit
    ) {
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", if (content.isEmpty()) "[Файл/Изображение без текста]" else content)
            if (base64Data != null) put("base64", base64Data)
            if (fileUri != null) put("imageUri", fileUri)
            if (mimeType != null) put("mimeType", mimeType)
            if (fileName != null) put("fileName", fileName)
            if (fileText != null) put("fileText", fileText)
        }
        chatHistory.add(userMessage)

        // Регистрируем файл, чтобы модель всегда помнила его содержимое
        if (fileName != null || fileText != null) {
            registerAttachedFile(fileName, mimeType, fileText)
        }

        // Сохраняем сразу, если чат уже существует
        currentChatId?.let {
            viewModelScope.launch {
                repository.addUserMessage(it, userMessage.getString("content"), fileUri)
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
            val authToken = sessionStore.getAuthToken()?.trim().orEmpty()
            if (authToken.isNotEmpty()) {
                launchSummarization(authToken, messagesToSummarize)
            }
        }

        val customInstructions = accountSettings.getUserInstructions()
        val filesContext = buildFilesContext()
        val authToken = sessionStore.getAuthToken()?.trim().orEmpty()
        if (authToken.isBlank()) {
            onError("Session expired. Sign in again.")
            return
        }

        viewModelScope.launch {
            AiApiService.fetchStreamingResponse(
                authToken = authToken,
                messagesToKeep = messagesToKeep,
                currentMode = effectiveMode,
                customInstructions = customInstructions,
                chatContextSummary = chatContextSummary,
                filesContext = filesContext,
                callback = object : AiApiService.StreamCallback {
                    override fun onChunk(accumulatedText: String) {
                        onChunk(accumulatedText)
                    }

                    override fun onComplete(fullText: String) {
                        val assistantMessage = JSONObject().apply {
                            put("role", "assistant")
                            put("content", fullText)
                        }
                        chatHistory.add(assistantMessage)
                        saveCompletedResponse(fullText)
                        onComplete()
                    }

                    override fun onError(errorMessage: String) {
                        onError(errorMessage)
                    }
                }
            )
        }
    }

    /**
     * Сохраняет завершённый ответ AI в БД.
     * Если чат ещё не создан — создаёт его со всей историей.
     */
    private fun saveCompletedResponse(fullText: String) {
        if (currentChatId == null && !isAnonymousChat) {
            createNewChat(currentChatTitle) { chatId ->
                currentChatId = chatId
                viewModelScope.launch {
                    for (msg in chatHistory) {
                        val role = msg.getString("role")
                        val content = msg.getString("content")
                        val fileUriParams = msg.optString("imageUri", null)
                            .takeIf { it?.isNotEmpty() == true }
                        if (role == "user") {
                            repository.addUserMessage(chatId, content, fileUriParams)
                        } else if (role == "assistant") {
                            repository.addAssistantMessage(chatId, content)
                        }
                    }
                    val titleSource = chatHistory.firstOrNull {
                        it.getString("role") == "user"
                    }?.getString("content")
                        ?: LocaleHelper.getString(getApplication(), "label_file_analysis")
                    generateAndSetChatTitle(chatId, titleSource) {}
                }
            }
        } else {
            currentChatId?.let { chatId ->
                viewModelScope.launch {
                    repository.addAssistantMessage(chatId, fullText)
                    cachedChats = repository.getAllChats()
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
                val summaryReply = AiApiService.summarizeMessages(authToken, messagesToSummarize)
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

    fun checkAndResetDailyLimits(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = accountSettings.getLastResetDate()

        var currentLeft = accountSettings.getRequestsLeft()

        if (lastDate != today) {
            currentLeft = nextDayRequestsLeft(currentLeft)
            accountSettings.saveLastResetDate(today)
            accountSettings.saveRequestsLeft(currentLeft)
        }
        return currentLeft
    }

    /** Расходует 1 запрос из лимита. Возвращает false если лимит исчерпан. */
    fun consumeLimit(): Boolean {
        var current = accountSettings.getRequestsLeft()
        if (current <= 0) return false
        current -= 1
        accountSettings.saveRequestsLeft(current)
        return true
    }

    /** Текущее количество оставшихся запросов */
    fun getRemainingLimits(): Int {
        return accountSettings.getRequestsLeft()
    }

    /** Добавляет запросы (награда за просмотр рекламы) */
    fun addLimits(amount: Int): Int {
        var current = accountSettings.getRequestsLeft()
        current += amount
        accountSettings.saveRequestsLeft(current)
        return current
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
        attachedFilesRegistry.clear()
    }

    // ──────── Реестр прикреплённых файлов ────────

    /**
     * Регистрирует файл в реестре, чтобы модель сохраняла знание
     * о его содержимом даже после выхода сообщения из контекстного окна.
     */
    private fun registerAttachedFile(fileName: String?, mimeType: String?, fileText: String?) {
        val entry = buildString {
            append("--- Файл")
            if (!fileName.isNullOrBlank()) append(": $fileName")
            if (!mimeType.isNullOrBlank()) append(" ($mimeType)")
            append(" ---")
            if (!fileText.isNullOrBlank()) {
                append("\n")
                // Сохраняем полное содержимое файла
                append(fileText)
            }
        }
        attachedFilesRegistry.add(entry)
    }

    /**
     * Строит блок контекста с содержимым всех прикреплённых файлов
     * для включения в системный промпт.
     */
    private fun buildFilesContext(): String {
        if (attachedFilesRegistry.isEmpty()) return ""
        return attachedFilesRegistry.joinToString("\n\n")
    }
}
