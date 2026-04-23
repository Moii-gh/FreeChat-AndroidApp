package com.example.chatapp

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.example.chatapp.util.setHapticClickListener
import com.example.chatapp.util.OnSwipeTouchListener

class FreeChatActivity : AppCompatActivity(), ChatInputHost {

    private enum class QuickSuggestionCategory {
        IMAGE,
        IDEA
    }

    private data class AttachmentPayload(
        val fileUri: String,
        val mimeType: String,
        val fileName: String?,
        val base64Data: String?,
        val extractedText: String?
    )

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
        const val MAX_EXTRACTED_TEXT_CHARS = 120_000
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

        val swipeListener = object : OnSwipeTouchListener(this) {
            override fun onSwipeRight() {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        binding.messagesScrollView.setOnTouchListener(swipeListener)
        binding.messagesContainer.setOnTouchListener(swipeListener)
        binding.welcomeScreen.setOnTouchListener(swipeListener)

        showWelcomeState()
        loadChats()
    }

    override fun onResume() {
        super.onResume()
        drawerManager.updateUserProfile()
        updateLimitsCount(chatViewModel.checkAndResetDailyLimits())
        applyTranslations()
    }

    private fun applyTranslations() {
        binding.tvWelcomeTitle.text = LocaleHelper.getString(this, "welcome_question")
        binding.tvBtnCreateImage.text = LocaleHelper.getString(this, "button_create_image")
        binding.tvBtnIdea.text = LocaleHelper.getString(this, "button_create_idea")
        binding.tvBtnMore.text = LocaleHelper.getString(this, "button_more")
        binding.tvAnonymousTitle.text = LocaleHelper.getString(this, "anonymous_mode_title")
        binding.tvAnonymousDesc.text = LocaleHelper.getString(this, "anonymous_mode_desc")

        // Drawer texts
        findViewById<android.widget.TextView>(R.id.tvDrawerNewChat)?.text = LocaleHelper.getString(this, "button_new_chat")
        findViewById<android.widget.EditText>(R.id.etDrawerSearch)?.hint = LocaleHelper.getString(this, "panel_search")
        drawerManager.populateChats(chatViewModel.cachedChats) // Automatically re-renders chat titles correctly

        // Also restore the correct hint based on the current mode
        when (chatViewModel.currentMode) {
            "create_image" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_create_image")
            "search" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_panel_search")
            "shopping" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_purchase_research")
            "study" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_stud_ and_training")
            else -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        }
    }

    override fun onDestroy() {
        if (::rewardedAdManager.isInitialized) {
            rewardedAdManager.destroy()
        }
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

    private fun imagePromptPrefix(): String = LocaleHelper.getString(this, "action_create_image")

    private fun ideaPromptPrefix(): String = LocaleHelper.getString(this, "prompt_idea_prefix")

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
            title = LocaleHelper.getString(this, "action_create_image"),
            iconRes = R.drawable.ic_palette,
            hint = LocaleHelper.getString(this, "hint_create_image"),
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
                        title = LocaleHelper.getString(this, "action_create_image"),
                        iconRes = R.drawable.ic_palette,
                        hint = LocaleHelper.getString(this, "hint_create_image"),
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
        binding.btnMenu.setHapticClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.btnNewChat.setHapticClickListener {
            startFreshChat()
        }
        binding.btnMore.setHapticClickListener {
            showCurrentChatMenu()
        }
        binding.btnChat.setHapticClickListener {
            startAnonymousChat()
        }
        binding.btnAddLimits.setHapticClickListener {
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
        binding.btnSend.setHapticClickListener {
            sendMessage()
        }
        binding.btnPlus.setHapticClickListener {
            BottomSheetMenuFragment().show(supportFragmentManager, "bottom_sheet_menu")
        }
        binding.btnCloseChip.setHapticClickListener {
            clearInputContext()
        }
    }

    private fun setupWelcomeActions() {
        binding.btnCreateImage.setHapticClickListener {
            activateImageSuggestions()
            binding.etInput.requestFocus()
        }
        binding.btnIdea.setHapticClickListener {
            activateIdeaSuggestions()
            binding.etInput.requestFocus()
        }
        binding.btnCenterMore.setHapticClickListener {
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
            val newValue = chatViewModel.addLimits(5)
            updateLimitsCount(newValue)
            toast(LocaleHelper.getString(this, "toast_reward_updated"))
        }
        rewardedAdManager.initialize()
    }

    private fun showWelcomeState() {
        binding.welcomeScreen.isVisible = true
        binding.anonymousWelcomeScreen.isGone = true
        binding.messagesScrollView.isGone = true
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true

        binding.tvWelcomeTitle.text = LocaleHelper.getString(this, "welcome_question")
        binding.tvBtnCreateImage.text = LocaleHelper.getString(this, "button_create_image")
        binding.tvBtnIdea.text = LocaleHelper.getString(this, "button_create_idea")
        binding.tvBtnMore.text = LocaleHelper.getString(this, "button_more")
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
    }

    private fun showAnonymousWelcomeState() {
        binding.welcomeScreen.isGone = true
        binding.anonymousWelcomeScreen.isVisible = true
        binding.messagesScrollView.isGone = true
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true

        binding.tvAnonymousTitle.text = LocaleHelper.getString(this, "anonymous_mode_title")
        binding.tvAnonymousDesc.text = LocaleHelper.getString(this, "anonymous_mode_desc")
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
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
        toast(LocaleHelper.getString(this, "toast_incognito_info"))
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

        val attachmentPayload = try {
            buildAttachmentPayload(previewUri)
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "Could not read attachment")
            return
        }

        if (!chatViewModel.consumeLimit()) {
            toast(LocaleHelper.getString(this, "toast_limits_exhausted"))
            return
        }

        val mimeType = attachmentPayload?.mimeType

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
            base64Data = attachmentPayload?.base64Data,
            fileUri = attachmentPayload?.fileUri,
            mimeType = mimeType,
            fileName = attachmentPayload?.fileName,
            fileText = attachmentPayload?.extractedText,
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

    private fun retryWithCurrentProvider(
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
            onError = { error ->
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    wrapper.updateContent(error, animate = false)
                    toast(error)
                }
            },
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
        retryWithCurrentProvider(
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

    private fun buildAttachmentPayload(uri: Uri?): AttachmentPayload? {
        if (uri == null) return null

        val fileName = com.example.chatapp.util.FileUtils.getFileName(this, uri)
            .takeIf { it.isNotBlank() }
        val mimeType = resolveMimeType(uri, fileName)
        val bytes = readAttachmentBytes(uri)
        val isImage = mimeType.startsWith("image/", ignoreCase = true)

        val extractedText = if (!isImage) {
            extractTextFromFile(bytes, mimeType, fileName)
        } else {
            null
        }

        val base64Data = if (isImage || extractedText == null) {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } else {
            null
        }

        return AttachmentPayload(
            fileUri = uri.toString(),
            mimeType = mimeType,
            fileName = fileName,
            base64Data = base64Data,
            extractedText = extractedText
        )
    }

    /**
     * Извлекает текстовое содержимое файла.
     * Поддерживает: текстовые файлы, DOCX, а также эвристику для неизвестных форматов.
     */
    private fun extractTextFromFile(bytes: ByteArray, mimeType: String, fileName: String?): String? {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

        // 1. DOCX — ZIP-архив с XML внутри
        if (isDocxFile(mimeType, extension)) {
            val docxText = extractDocxText(bytes)
            if (!docxText.isNullOrBlank()) return truncateText(docxText)
        }

        // 2. Явно текстовые файлы по MIME/расширению
        if (isTextLikeAttachment(mimeType, fileName)) {
            return decodeAttachmentText(bytes)
        }

        // 3. Эвристика: пробуем декодировать как UTF-8 и проверяем читаемость
        val heuristicText = tryDecodeAsText(bytes)
        if (heuristicText != null) return truncateText(heuristicText)

        return null
    }

    private fun isDocxFile(mimeType: String, extension: String): Boolean {
        return extension == "docx" ||
            mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ignoreCase = true)
    }

    /**
     * Извлекает текст из DOCX (ZIP → word/document.xml → strip XML tags).
     */
    private fun extractDocxText(bytes: ByteArray): String? {
        return runCatching {
            val zipInput = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
            var entry = zipInput.nextEntry
            var documentXml: String? = null
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXml = zipInput.bufferedReader(Charsets.UTF_8).readText()
                    break
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
            zipInput.close()

            if (documentXml == null) return@runCatching null

            // Заменяем теги абзацев/переносов на переводы строк
            val withBreaks = documentXml
                .replace(Regex("<w:p[\\s>]"), "\n")
                .replace(Regex("<w:br[^>]*>"), "\n")
                .replace(Regex("<w:tab[^>]*>"), "\t")

            // Убираем все XML-теги
            val text = withBreaks.replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")

            text.ifBlank { null }
        }.getOrNull()
    }

    /**
     * Эвристика: пробуем прочитать байты как UTF-8.
     * Если большинство символов печатаемые — считаем файл текстовым.
     */
    private fun tryDecodeAsText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        // Проверяем первые 8KB для скорости
        val sampleSize = minOf(bytes.size, 8192)
        val sample = bytes.copyOf(sampleSize)
        val text = sample.toString(Charsets.UTF_8)

        var printable = 0
        var nonPrintable = 0
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch.isWhitespace() || ch in "!@#\$%^&*()_+-=[]{}|;':\",./<>?`~\\") {
                printable++
            } else if (ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t') {
                nonPrintable++
            }
        }
        // Если меньше 5% непечатных — считаем текстом
        val total = printable + nonPrintable
        if (total == 0) return null
        val ratio = nonPrintable.toFloat() / total
        if (ratio > 0.05f) return null

        // Всё ОК — декодируем полный файл
        return decodeAttachmentText(bytes)
    }

    private fun resolveMimeType(uri: Uri, fileName: String?): String {
        return contentResolver.getType(uri)
            ?.takeIf { it.isNotBlank() }
            ?: fileName?.let { java.net.URLConnection.guessContentTypeFromName(it) }
            ?: "application/octet-stream"
    }

    private fun readAttachmentBytes(uri: Uri): ByteArray {
        val declaredSize = queryAttachmentSize(uri)
        if (declaredSize != null && declaredSize > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException("Attachment is too large to analyze")
        }

        val output = java.io.ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException("Attachment is too large to analyze")
                }
                output.write(buffer, 0, read)
            }
        } ?: throw IllegalArgumentException("Could not read attachment")

        return output.toByteArray()
    }

    private fun queryAttachmentSize(uri: Uri): Long? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index == -1 || cursor.isNull(index)) null else cursor.getLong(index)
            }
        }.getOrNull()
    }

    private fun isTextLikeAttachment(mimeType: String, fileName: String?): Boolean {
        if (mimeType.startsWith("text/", ignoreCase = true)) return true
        val normalizedMime = mimeType.lowercase()
        if (normalizedMime in setOf(
                "application/json",
                "application/xml",
                "application/javascript",
                "application/x-javascript",
                "application/typescript",
                "application/csv",
                "application/sql",
                "application/rtf",
                "application/yaml",
                "application/x-yaml",
                "application/x-sh",
                "application/x-httpd-php",
                "application/graphql",
                "application/ld+json",
                "application/x-latex",
                "application/x-tex",
                "application/toml",
                "application/x-toml",
                "application/x-properties"
            )
        ) {
            return true
        }

        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in setOf(
            // Текстовые
            "txt", "md", "markdown", "csv", "tsv", "log", "rtf",
            // Web
            "json", "xml", "html", "htm", "css", "js", "jsx", "ts", "tsx",
            "svg", "graphql", "gql",
            // JVM
            "kt", "kts", "java", "gradle", "groovy", "scala",
            // Скриптовые
            "py", "rb", "php", "pl", "pm", "lua", "r",
            // Системные
            "c", "cpp", "h", "hpp", "cs", "swift", "go", "rs", "dart",
            // Shell / config
            "sh", "bash", "zsh", "bat", "cmd", "ps1", "psm1",
            "env", "ini", "cfg", "conf", "properties", "toml",
            "yaml", "yml", "dockerfile",
            // SQL / Data
            "sql", "proto", "graphql",
            // Разметка / документация
            "tex", "latex", "rst", "adoc", "org",
            // Прочие
            "diff", "patch", "gitignore", "editorconfig",
            "makefile", "cmake", "tf", "tfvars", "hcl"
        )
    }

    private fun decodeAttachmentText(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
            .replace("\u0000", "")
            .trim()
        return truncateText(text)
    }

    private fun truncateText(text: String): String {
        return if (text.length > MAX_EXTRACTED_TEXT_CHARS) {
            text.take(MAX_EXTRACTED_TEXT_CHARS) + "\n\n[Содержимое файла обрезано]"
        } else {
            text
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
