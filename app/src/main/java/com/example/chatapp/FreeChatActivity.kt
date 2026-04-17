package com.example.chatapp

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.speech.SpeechRecognizerManager
import com.example.chatapp.ui.AssistantMessageWrapper
import com.example.chatapp.ui.ChatMessageRenderer
import com.example.chatapp.ui.DrawerManager
import com.example.chatapp.ui.PopupMenuHelper
import com.example.chatapp.viewmodel.ChatViewModel
import com.example.chatapp.ads.RewardedAdManager
import kotlinx.coroutines.launch
import org.json.JSONObject

class FreeChatActivity : AppCompatActivity(), ChatInputHost {

    private enum class QuickSuggestionCategory {
        IMAGE,
        IDEA
    }

    private lateinit var binding: ActivityMainBinding
    private val chatViewModel: ChatViewModel by viewModels()

    private lateinit var drawerManager: DrawerManager
    private lateinit var popupMenuHelper: PopupMenuHelper
    private lateinit var messageRenderer: ChatMessageRenderer
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    private lateinit var rewardedAdManager: RewardedAdManager

    private var currentPreviewUri: Uri? = null
    private var currentAssistantMessage: AssistantMessageWrapper? = null
    private var isSending = false
    private var activeSuggestionCategory: QuickSuggestionCategory? = null
    private var suppressSuggestionUpdates = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SharedPrefsAccountSessionStore(applicationContext).isSignedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHelpers()
        setupTopBar()
        setupDrawer()
        setupInputArea()
        setupWelcomeActions()
        setupPreview()
        setupAds()
        updateLimitsCount(chatViewModel.checkAndResetDailyLimits())
        showWelcomeState()
        loadChats()
    }

    override fun onResume() {
        super.onResume()
        drawerManager.updateUserProfile()
        updateLimitsCount(chatViewModel.checkAndResetDailyLimits())
    }

    override fun onDestroy() {
        speechRecognizerManager.destroy()
        super.onDestroy()
    }

    override fun setInputContext(
        title: String,
        iconRes: Int,
        hint: String,
        iconTint: String,
        mode: String?
    ) {
        binding.contextChipContainer.isVisible = true
        binding.chipTitle.text = title
        binding.chipTitle.setTextColor(android.graphics.Color.parseColor(iconTint))
        binding.chipIcon.setImageResource(iconRes)
        binding.chipIcon.setColorFilter(android.graphics.Color.parseColor(iconTint))
        binding.etInput.hint = hint
        chatViewModel.currentMode = mode

        if (mode == "create_image" && binding.etInput.text.isNullOrBlank()) {
            updateInputText(imagePromptPrefix(), keepSuggestions = true)
        } else {
            syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
        }
    }

    override fun showFilePreview(fileUri: Uri) {
        currentPreviewUri = fileUri
        chatViewModel.selectedFileUri = fileUri
        val mimeType = contentResolver.getType(fileUri).orEmpty()

        binding.previewContainer.isVisible = true
        if (mimeType.startsWith("image/")) {
            binding.previewImage.isVisible = true
            binding.previewFileContainer.isGone = true
            runCatching { binding.previewImage.setImageURI(fileUri) }
        } else {
            binding.previewImage.isGone = true
            binding.previewFileContainer.isVisible = true
            binding.previewFileName.text = com.example.chatapp.util.FileUtils.getFileName(this, fileUri)
        }
        hideQuickSuggestions()
        updateSendState()
    }

    private fun imagePromptPrefix(): String = "Создай изображение"

    private fun ideaPromptPrefix(): String = "Придумай несколько идей"

    private fun quickSuggestionsFor(category: QuickSuggestionCategory): List<String> = when (category) {
        QuickSuggestionCategory.IMAGE -> listOf(
            "Создай изображение для моей презентации",
            "Создай изображение моего питомца",
            "Создай изображение для моего сайта",
            "Создай изображения из фетра"
        )

        QuickSuggestionCategory.IDEA -> listOf(
            "Придумай несколько идей для моего следующего отпуска",
            "Придумай несколько идей для рекламной кампании бренда",
            "Придумай несколько идей для новой программы тренировок",
            "Придумай несколько идей для меню званого ужина"
        )
    }

    private fun activateImageSuggestions() {
        setInputContext(
            title = "Создать изображение",
            iconRes = R.drawable.ic_palette,
            hint = "Опишите изображение, которое хотите создать",
            mode = "create_image"
        )
        updateInputText(imagePromptPrefix(), keepSuggestions = true)
    }

    private fun activateIdeaSuggestions() {
        clearInputContext()
        updateInputText(ideaPromptPrefix(), keepSuggestions = true)
    }

    private fun updateInputText(text: String, keepSuggestions: Boolean) {
        suppressSuggestionUpdates = !keepSuggestions
        binding.etInput.setText(text)
        binding.etInput.setSelection(binding.etInput.text?.length ?: 0)
        suppressSuggestionUpdates = false

        if (keepSuggestions) {
            syncQuickSuggestions(text)
        } else {
            hideQuickSuggestions()
        }
    }

    private fun syncQuickSuggestions(query: String) {
        if (suppressSuggestionUpdates || currentPreviewUri != null || isSending) {
            hideQuickSuggestions()
            return
        }

        val normalized = query.trim().lowercase()
        val category = when {
            normalized.isBlank() -> null
            normalized.startsWith("создай изображ") || normalized.startsWith("создать изображ") ->
                QuickSuggestionCategory.IMAGE

            normalized.startsWith("придумай") || normalized.startsWith("помоги придумать") ->
                QuickSuggestionCategory.IDEA

            else -> null
        }

        if (category == null) {
            hideQuickSuggestions()
        } else {
            showQuickSuggestions(category)
        }
    }

    private fun showQuickSuggestions(category: QuickSuggestionCategory) {
        if (activeSuggestionCategory == category && binding.suggestionsContainer.isVisible) {
            return
        }

        val suggestions = quickSuggestionsFor(category)
        val iconRes = when (category) {
            QuickSuggestionCategory.IMAGE -> R.drawable.ic_image_green
            QuickSuggestionCategory.IDEA -> R.drawable.ic_bulb_yellow
        }
        val tintColor = when (category) {
            QuickSuggestionCategory.IMAGE -> Color.parseColor("#00C853")
            QuickSuggestionCategory.IDEA -> Color.parseColor("#FFD60A")
        }
        val density = resources.displayMetrics.density

        activeSuggestionCategory = category
        binding.suggestionsList.removeAllViews()

        suggestions.forEachIndexed { index, suggestion ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index < suggestions.lastIndex) {
                        bottomMargin = (4 * density).toInt()
                    }
                }
                setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
                isClickable = true
                isFocusable = true
                val selectable = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                setBackgroundResource(selectable.resourceId)
                setOnClickListener {
                    applyQuickSuggestion(category, suggestion)
                }
            }

            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                    topMargin = (2 * density).toInt()
                    marginEnd = (10 * density).toInt()
                }
                setImageResource(iconRes)
                setColorFilter(tintColor)
            }

            val textView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = suggestion
                setTextColor(Color.parseColor("#D1D1D6"))
                textSize = 15f
                setLineSpacing(0f, 1.05f)
            }

            row.addView(icon)
            row.addView(textView)
            binding.suggestionsList.addView(row)
        }

        binding.suggestionsContainer.isVisible = true
    }

    private fun applyQuickSuggestion(category: QuickSuggestionCategory, suggestion: String) {
        when (category) {
            QuickSuggestionCategory.IMAGE -> {
                if (chatViewModel.currentMode != "create_image") {
                    setInputContext(
                        title = "Создать изображение",
                        iconRes = R.drawable.ic_palette,
                        hint = "Опишите изображение, которое хотите создать",
                        mode = "create_image"
                    )
                }
            }

            QuickSuggestionCategory.IDEA -> {
                if (chatViewModel.currentMode != null) {
                    clearInputContext()
                }
            }
        }

        updateInputText(suggestion, keepSuggestions = false)
        binding.etInput.requestFocus()
    }

    private fun hideQuickSuggestions() {
        activeSuggestionCategory = null
        binding.suggestionsContainer.isGone = true
        binding.suggestionsList.removeAllViews()
    }

    private fun setupHelpers() {
        popupMenuHelper = PopupMenuHelper(
            activity = this,
            onRename = { chat, newTitle ->
                chatViewModel.renameChat(chat.id, newTitle) {
                    refreshChats()
                }
            },
            onTogglePin = { chat ->
                chatViewModel.togglePinChat(chat.id, chat.isPinned) {
                    refreshChats()
                }
            },
            onDelete = { chat ->
                chatViewModel.deleteChat(chat.id) {
                    if (chatViewModel.currentChatId == chat.id) {
                        startFreshChat()
                    }
                    refreshChats()
                }
            },
            onRegenerate = ::regenerateAssistantResponse
        )

        messageRenderer = ChatMessageRenderer(
            context = this,
            messagesContainer = binding.messagesContainer,
            messagesScrollView = binding.messagesScrollView,
            popupMenuHelper = popupMenuHelper,
            onRegenerate = ::regenerateAssistantResponse
        )

        speechRecognizerManager = SpeechRecognizerManager(
            context = this,
            etInput = binding.etInput,
            waveformContainer = binding.waveformContainer
        )
        speechRecognizerManager.setup()
        speechRecognizerManager.setupMicButton(binding.btnMic, this)
    }

    private fun setupTopBar() {
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.btnNewChat.setOnClickListener {
            startFreshChat()
        }
        binding.btnMore.setOnClickListener {
            showCurrentChatMenu()
        }
        binding.btnChat.setOnClickListener {
            startAnonymousChat()
        }
        binding.btnAddLimits.setOnClickListener {
            rewardedAdManager.show()
        }
    }

    private fun setupDrawer() {
        val chatsContainer = findViewById<LinearLayout>(R.id.chatsContainer)
        drawerManager = DrawerManager(
            context = this,
            chatsContainer = chatsContainer,
            onChatClick = ::openChat,
            onChatLongClick = { anchor, chat ->
                popupMenuHelper.showChatPopupMenu(anchor, chat)
            }
        )

        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
            }
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })

        findViewById<LinearLayout>(R.id.btnNewChatDrawer).setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startFreshChat()
        }
        findViewById<FrameLayout>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageView>(R.id.currentChatItem).setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        val defaultHeaderContent = findViewById<LinearLayout>(R.id.defaultHeaderContent)
        val searchContainer = findViewById<LinearLayout>(R.id.drawerSearchContainer)
        val searchField = findViewById<EditText>(R.id.etDrawerSearch)

        findViewById<ImageView>(R.id.btnDrawerSearch).setOnClickListener {
            defaultHeaderContent.isGone = true
            searchContainer.isVisible = true
            searchField.requestFocus()
        }
        findViewById<ImageView>(R.id.btnCloseDrawerSearch).setOnClickListener {
            searchField.setText("")
            searchContainer.isGone = true
            defaultHeaderContent.isVisible = true
            drawerManager.populateChats(chatViewModel.cachedChats)
        }
        searchField.doAfterTextChanged { query ->
            filterChats(query?.toString().orEmpty())
        }
    }

    private fun setupInputArea() {
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        binding.etInput.doAfterTextChanged { editable ->
            updateSendState()
            syncQuickSuggestions(editable?.toString().orEmpty())
        }
        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            val shouldSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (shouldSend) {
                sendMessage()
                true
            } else {
                false
            }
        }
        binding.btnSend.setOnClickListener {
            sendMessage()
        }
        binding.btnPlus.setOnClickListener {
            BottomSheetMenuFragment().show(supportFragmentManager, "bottom_sheet_menu")
        }
        binding.btnCloseChip.setOnClickListener {
            clearInputContext()
        }
    }

    private fun setupWelcomeActions() {
        binding.btnCreateImage.setOnClickListener {
            activateImageSuggestions()
            binding.etInput.requestFocus()
        }
        binding.btnIdea.setOnClickListener {
            activateIdeaSuggestions()
            binding.etInput.requestFocus()
        }
        binding.btnCenterMore.setOnClickListener {
            BottomSheetMenuFragment().show(supportFragmentManager, "bottom_sheet_menu")
        }
    }

    private fun setupPreview() {
        binding.btnRemovePreview.setOnClickListener {
            clearPreview()
        }
    }

    private fun setupAds() {
        rewardedAdManager = RewardedAdManager(this) {
            // Callback вызывается после успешного просмотра рекламы
            val newValue = chatViewModel.addLimits(5)
            updateLimitsCount(newValue)
            toast("Добавлено 5 запросов")
        }
        rewardedAdManager.initialize()
    }

    private fun showWelcomeState() {
        binding.welcomeScreen.isVisible = true
        binding.anonymousWelcomeScreen.isGone = true
        binding.messagesScrollView.isGone = true
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true
    }

    private fun showAnonymousWelcomeState() {
        binding.welcomeScreen.isGone = true
        binding.anonymousWelcomeScreen.isVisible = true
        binding.messagesScrollView.isGone = true
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true
    }

    private fun showMessagesState() {
        binding.welcomeScreen.isGone = true
        binding.anonymousWelcomeScreen.isGone = true
        binding.messagesScrollView.isVisible = true
        binding.topRightMain.isGone = true
        binding.topRightChat.isVisible = true
    }

    private fun startFreshChat() {
        chatViewModel.resetChatState()
        currentAssistantMessage = null
        isSending = false
        binding.etInput.text?.clear()
        binding.messagesContainer.removeAllViews()
        clearInputContext()
        clearPreview()
        hideQuickSuggestions()
        showWelcomeState()
        updateSendState()
        refreshDrawerSelection()
    }

    private fun startAnonymousChat() {
        startFreshChat()
        chatViewModel.isAnonymousChat = true
        showAnonymousWelcomeState()
        toast("Инкогнито-чат не сохраняется в истории")
    }

    private fun loadChats() {
        chatViewModel.loadChats {
            runOnUiThread {
                drawerManager.setSelectedChatId(chatViewModel.currentChatId)
                drawerManager.populateChats(chatViewModel.cachedChats)
                drawerManager.updateUserProfile()
            }
        }
    }

    private fun refreshChats() {
        chatViewModel.refreshChats {
            runOnUiThread {
                val query = findViewById<EditText>(R.id.etDrawerSearch).text?.toString().orEmpty()
                filterChats(query)
            }
        }
    }

    private fun filterChats(query: String) {
        val trimmed = query.trim()
        val chats = if (trimmed.isBlank()) {
            chatViewModel.cachedChats
        } else {
            chatViewModel.cachedChats.filter {
                it.title.contains(trimmed, ignoreCase = true)
            }
        }
        drawerManager.setSelectedChatId(chatViewModel.currentChatId)
        drawerManager.populateChats(chats, trimmed)
    }

    private fun openChat(chatId: String) {
        lifecycleScope.launch {
            val chat = chatViewModel.getChatById(chatId) ?: return@launch
            val messages = chatViewModel.getMessages(chatId)

            chatViewModel.resetChatState()
            chatViewModel.currentChatId = chat.id
            chatViewModel.currentChatTitle = chat.title
            chatViewModel.chatContextSummary = chat.summary
            chatViewModel.isFirstMessage = messages.none { it.role == "user" }

            binding.messagesContainer.removeAllViews()
            messages.forEach { message ->
                chatViewModel.chatHistory.add(
                    JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                        message.imageUrl?.let { put("imageUri", it) }
                    }
                )

                if (message.role == "user") {
                    messageRenderer.renderRestoredUserMessage(message.content, message.imageUrl)
                } else {
                    messageRenderer.addAssistantMessage(
                        text = message.content,
                        animate = false,
                        isImageMode = AssistantMessageWrapper.containsImageReply(message.content)
                    )
                }
            }

            showMessagesState()
            hideQuickSuggestions()
            refreshDrawerSelection()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun refreshDrawerSelection() {
        if (!::drawerManager.isInitialized) return
        val query = findViewById<EditText>(R.id.etDrawerSearch)?.text?.toString().orEmpty()
        filterChats(query)
    }

    private fun sendMessage() {
        if (isSending) return

        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        val previewUri = currentPreviewUri
        if (text.isBlank() && previewUri == null) return

        if (!chatViewModel.consumeLimit()) {
            toast("Лимит запросов исчерпан")
            return
        }

        val mimeType = previewUri?.let { contentResolver.getType(it) }
        val base64Data = if (mimeType?.startsWith("image/") == true) {
            encodeUriToBase64(previewUri)
        } else {
            null
        }

        isSending = true
        hideQuickSuggestions()
        showMessagesState()
        updateLimitsCount(chatViewModel.getRemainingLimits())

        when {
            previewUri == null -> messageRenderer.addUserMessage(text)
            mimeType?.startsWith("image/") == true -> messageRenderer.addUserMessageWithImage(text, previewUri)
            else -> messageRenderer.addUserMessageWithFile(text, previewUri)
        }

        if (chatViewModel.isFirstMessage) {
            chatViewModel.currentChatTitle = when {
                text.isNotBlank() -> text.take(60)
                previewUri != null -> LocaleHelper.getString(this, "label_file_analysis")
                else -> "Новый чат"
            }
            chatViewModel.isFirstMessage = false
        }

        val isImageRequest = chatViewModel.currentMode == "create_image"
        val wrapper = messageRenderer.addAssistantMessage(
            text = if (isImageRequest) "" else LocaleHelper.getString(this, "ai_thinking"),
            animate = false,
            isImageMode = isImageRequest
        )
        currentAssistantMessage = wrapper

        binding.etInput.text?.clear()
        clearPreview()
        updateSendState()

        chatViewModel.addToChatHistoryAndSend(
            content = text,
            base64Data = base64Data,
            fileUri = previewUri?.toString(),
            mimeType = mimeType,
            onAssistantResponse = {},
            onFallbackRequired = {
                runOnUiThread {
                    wrapper.updateContent("Переключаемся на резервную модель…", animate = false)
                }
                retryWithFallback(wrapper)
            },
            onError = { error ->
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    wrapper.updateContent(error, animate = false)
                    toast(error)
                }
            },
            onChunk = { chunk ->
                runOnUiThread {
                    wrapper.updateContent(chunk, animate = false)
                }
            },
            onStreamComplete = {
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    refreshChats()
                }
            }
        )
    }

    private fun retryWithFallback(
        wrapper: AssistantMessageWrapper,
        modeOverride: String? = null,
        useModeOverride: Boolean = false
    ) {
        chatViewModel.fetchAiResponse(
            onChunk = { chunk ->
                runOnUiThread {
                    wrapper.updateContent(chunk, animate = false)
                }
            },
            onComplete = {
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    refreshChats()
                }
            },
            onFallbackRequired = {
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    wrapper.updateContent("Не удалось получить ответ. Попробуйте ещё раз.", animate = false)
                }
            },
            onError = { error ->
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    wrapper.updateContent(error, animate = false)
                    toast(error)
                }
            },
            forceFallbackRoute = true,
            modeOverride = modeOverride,
            useModeOverride = useModeOverride
        )
    }

    private fun regenerateAssistantResponse(wrapper: AssistantMessageWrapper) {
        if (isSending) return

        val wrapperIndex = binding.messagesContainer.indexOfChild(wrapper.container)
        if (wrapperIndex < 0) return

        // Считаем, какой по счёту это assistant-блок (0-indexed).
        // Assistant rootContainer отличается от user views шириной MATCH_PARENT.
        var assistantOrdinal = 0
        for (i in 0 until binding.messagesContainer.childCount) {
            val child = binding.messagesContainer.getChildAt(i)
            if (child === wrapper.container) break
            val lp = child.layoutParams as? LinearLayout.LayoutParams
            if (lp?.width == LinearLayout.LayoutParams.MATCH_PARENT) {
                assistantOrdinal++
            }
        }

        // В chatHistory сообщения идут парами: [user, assistant, user, assistant, ...].
        // Assistant #K находится в chatHistory по индексу (2*K + 1).
        // Обрезаем начиная с этой позиции — т.е. сохраняем user-сообщение перед ним.
        val historyTruncateIndex = assistantOrdinal * 2 + 1
        // Защита: если индекс выходит за границы, обрезаем просто последнее сообщение
        val safeTruncateIndex = historyTruncateIndex.coerceAtMost(chatViewModel.chatHistory.size)

        // Удаляем все view начиная с wrapperIndex (включая все последующие сообщения)
        val totalViews = binding.messagesContainer.childCount
        for (i in totalViews - 1 downTo wrapperIndex) {
            binding.messagesContainer.removeViewAt(i)
        }

        // Обрезаем chatHistory и БД
        chatViewModel.truncateHistoryFrom(safeTruncateIndex)

        // Генерируем новый ответ
        isSending = true
        updateSendState()
        val isImageRequest = wrapper.isImageMode || AssistantMessageWrapper.containsImageReply(wrapper.rawText)
        val freshWrapper = messageRenderer.addAssistantMessage(
            text = if (isImageRequest) "" else LocaleHelper.getString(this, "ai_thinking"),
            animate = false,
            isImageMode = isImageRequest
        )
        currentAssistantMessage = freshWrapper
        retryWithFallback(
            wrapper = freshWrapper,
            modeOverride = if (isImageRequest) "create_image" else null,
            useModeOverride = true
        )
    }

    private fun showCurrentChatMenu() {
        val currentChatId = chatViewModel.currentChatId ?: return
        lifecycleScope.launch {
            val chat = chatViewModel.getChatById(currentChatId) ?: return@launch
            popupMenuHelper.showCurrentChatOptionsMenu(binding.btnMore, chat)
        }
    }

    private fun clearPreview() {
        currentPreviewUri = null
        chatViewModel.selectedFileUri = null
        binding.previewContainer.isGone = true
        binding.previewImage.setImageDrawable(null)
        binding.previewImage.isGone = true
        binding.previewFileContainer.isGone = true
        updateSendState()
        syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
    }

    private fun clearInputContext() {
        binding.contextChipContainer.isGone = true
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        chatViewModel.currentMode = null
        syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
    }

    private fun updateSendState() {
        val hasInput = binding.etInput.text?.isNotBlank() == true || currentPreviewUri != null
        binding.btnSend.isEnabled = hasInput && !isSending
        
        if (binding.btnSend.isEnabled) {
            binding.btnSend.setBackgroundResource(R.drawable.circle_solid_white_bg)
            binding.btnSend.setColorFilter(android.graphics.Color.BLACK)
        } else {
            binding.btnSend.setBackgroundResource(R.drawable.circle_solid_grey_bg)
            binding.btnSend.setColorFilter(android.graphics.Color.parseColor("#8E8E93"))
        }
    }

    private fun updateLimitsCount(value: Int) {
        binding.tvLimitsCount.text = value.toString()
    }

    private fun encodeUriToBase64(uri: Uri?): String? {
        if (uri == null) return null
        return runCatching {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                Base64.encodeToString(inputStream.readBytes(), Base64.NO_WRAP)
            }
        }.getOrNull()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
