package com.example.chatapp

import android.animation.AnimatorSet
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.chatapp.ai.AiActivitySnapshot
import com.example.chatapp.assistant.DigitalAssistantHandoffStore
import com.example.chatapp.browser.InAppBrowserManager
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.speech.SpeechRecognizerManager
import com.example.chatapp.ui.AssistantMessageWrapper
import com.example.chatapp.ui.ChatMessageRenderer
import com.example.chatapp.ui.ChatScrollToBottomController
import com.example.chatapp.ui.ChatUiAnimationHelper
import com.example.chatapp.ui.DrawerManager
import com.example.chatapp.ui.FreeChatAttentionDrawable
import com.example.chatapp.ui.LaunchLogoAnimator
import com.example.chatapp.ui.PopupMenuHelper
import com.example.chatapp.ui.SelectableTextSupport
import com.example.chatapp.ui.chat.BiometricGateController
import com.example.chatapp.ui.chat.ChatAttachmentPreviewController
import com.example.chatapp.ui.chat.ChatModePresentation
import com.example.chatapp.ui.chat.StreamingUiController
import com.example.chatapp.ui.chat.WelcomePromptController
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.SafeLog
import com.example.chatapp.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.example.chatapp.util.setHapticClickListener
import com.example.chatapp.util.OnSwipeTouchListener
import com.example.chatapp.ads.RewardedAdManager
import android.text.Selection
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.activity.OnBackPressedCallback

import com.example.chatapp.ui.TypingDotsView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

class FreeChatActivity : AppCompatActivity(), ChatInputHost {

    private enum class QuickSuggestionCategory {
        IMAGE,
        IDEA
    }

    companion object {
        const val EXTRA_PREFILL_INPUT = "com.example.chatapp.EXTRA_PREFILL_INPUT"
        const val EXTRA_SKIP_BIOMETRIC_ONCE = "com.example.chatapp.EXTRA_SKIP_BIOMETRIC_ONCE"
        const val EXTRA_OPEN_CHAT_ID = "com.example.chatapp.EXTRA_OPEN_CHAT_ID"

        const val SUGGESTIONS_SHOW_DURATION_MS = 180L
        const val SUGGESTIONS_HIDE_DURATION_MS = 120L
        const val TOP_ACTIONS_SPLIT_DURATION_MS = 260L
        const val FREE_CHAT_ATTENTION_IDLE_DELAY_MS = 30_000L
    }

    private lateinit var binding: ActivityMainBinding
    private val chatViewModel: ChatViewModel by viewModels()

    private lateinit var drawerManager: DrawerManager
    private lateinit var popupMenuHelper: PopupMenuHelper
    private lateinit var messageRenderer: ChatMessageRenderer
    private lateinit var inAppBrowserManager: InAppBrowserManager
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    private lateinit var welcomePromptController: WelcomePromptController
    private lateinit var streamingUiController: StreamingUiController
    private lateinit var biometricGateController: BiometricGateController
    private lateinit var attachmentPreviewController: ChatAttachmentPreviewController
    private val attachmentHelper by lazy { ChatAttachmentHelper(this) }

    private var currentAssistantMessage: AssistantMessageWrapper? = null
    private var isSending = false
    private var activeSuggestionCategory: QuickSuggestionCategory? = null
    private var isIdeaSuggestionsPinned = false
    private var suppressSuggestionUpdates = false
    private var handledShareToken: String? = null
    private var adManager: RewardedAdManager? = null
    private var topActionsAnimator: AnimatorSet? = null
    private var editingMessageHistoryIndex: Int? = null
    private val freeChatAttentionHandler = Handler(Looper.getMainLooper())
    private var freeChatAttentionDrawable: FreeChatAttentionDrawable? = null
    private var isFreeChatButtonInteracting = false
    private var isChatUiInitialized = false
    private var navigationBarInsetBottom = 0
    private var systemWindowInsetTop = 0
    private var messagesBottomAnchorId = View.NO_ID
    private var isUserAtBottom = true
    private var isUserTouchingMessages = false
    private var isGeneratingIndicatorVisible = false
    private var appliedLanguageCode: String? = null
    private var pendingAutoScrollRunnable: Runnable? = null
    private var scrollToBottomController: ChatScrollToBottomController? = null
    private var pendingSendJob: Job? = null
    private var generatingChatIds: Set<String> = emptySet()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    private val freeChatAttentionRunnable = object : Runnable {
        override fun run() {
            if (!isFreeChatButtonInteracting && binding.btnAddLimits.isShown) {
                freeChatAttentionDrawable?.play(binding.btnAddLimits)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SharedPrefsAccountSessionStore(applicationContext).isSignedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NotificationPermissionHelper.requestOnFirstOpen(this)
        setupLifecycleControllers()
        welcomePromptController.restoreState(savedInstanceState)
        setupBackNavigation()
        val shouldSkipBiometricOnce = intent?.getBooleanExtra(EXTRA_SKIP_BIOMETRIC_ONCE, false) == true
        val shouldGateWithBiometrics = biometricGateController.shouldGate(shouldSkipBiometricOnce)
        if (shouldGateWithBiometrics) {
            biometricGateController.prepareGate()
        }
        val shouldPlayLaunchAnimation = LaunchLogoAnimator.shouldPlayOnActivityCreate(savedInstanceState)
        if (shouldPlayLaunchAnimation) {
            LaunchLogoAnimator.show(this) {
                if (shouldGateWithBiometrics) {
                    biometricGateController.start()
                }
            }
        } else if (shouldGateWithBiometrics) {
            biometricGateController.start()
        }
        if (!shouldGateWithBiometrics) {
            initializeChatUi(intent)
        }
    }

    private fun setupLifecycleControllers() {
        welcomePromptController = WelcomePromptController(
            context = this,
            lifecycleOwner = this,
            titleContainer = binding.welcomeTitleRotator,
            titleView = binding.tvWelcomeTitle
        )
        streamingUiController = StreamingUiController(
            isCurrentAssistantMessage = { wrapper -> currentAssistantMessage === wrapper },
            onContentApplied = {
                scheduleScrollToBottomIfPinned()
                updateGeneratingIndicatorVisibility()
            },
            onCancelAutoScroll = ::cancelPendingAutoScroll
        )
        observeAiActivityState()
        observeChatGenerationState()
        biometricGateController = BiometricGateController(
            activity = this,
            rootView = binding.root,
            onUnlocked = { initializeChatUi(intent) },
            onMessage = ::toast
        )
        attachmentPreviewController = ChatAttachmentPreviewController(
            context = this,
            binding = binding,
            chatViewModel = chatViewModel,
            onPreviewShown = {
                hideQuickSuggestions()
                updateSendState()
            },
            onPreviewCleared = {
                updateSendState()
                syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
            },
            onPreviewChanged = ::updateSendState
        )
    }

    private fun observeAiActivityState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.aiActivityState.collect { snapshot ->
                    applyAiActivityState(snapshot)
                }
            }
        }
    }

    private fun observeChatGenerationState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.generationSnapshots.collect { snapshots ->
                    updateDrawerGenerationIndicators(
                        snapshots.values
                            .filter { it.isRunning && !it.chatId.isNullOrBlank() }
                            .mapNotNull { it.chatId }
                            .toSet()
                    )
                    val currentChatId = chatViewModel.currentChatId ?: return@collect
                    val snapshot = snapshots.values
                        .filter { it.chatId == currentChatId }
                        .maxByOrNull { it.generationId }
                        ?: return@collect
                    applyObservedGenerationSnapshot(snapshot)
                }
            }
        }
    }

    private fun updateDrawerGenerationIndicators(chatIds: Set<String>) {
        if (generatingChatIds == chatIds) return
        generatingChatIds = chatIds
        if (!::drawerManager.isInitialized) return
        drawerManager.setGeneratingChatIds(chatIds)
        refreshDrawerSelection()
    }

    private fun applyObservedGenerationSnapshot(snapshot: ChatGenerationSnapshot) {
        val wrapper = currentAssistantMessage
            ?.takeIf { it.messageSyncId == snapshot.assistantSyncId }
            ?: return

        if (streamingUiController.activeGenerationId != snapshot.generationId) {
            streamingUiController.attachGeneration(snapshot.generationId)
        }

        when (snapshot.status) {
            ChatGenerationStatus.RUNNING -> {
                isSending = true
                updateSendState()
                enqueueStreamingUpdate(snapshot.generationId, wrapper, snapshot.accumulatedText)
            }
            ChatGenerationStatus.COMPLETED -> {
                flushStreamingUpdate(snapshot.generationId, wrapper, snapshot.accumulatedText, isFinal = true)
                finishGeneration(snapshot.generationId)
                isSending = false
                updateSendState()
                hideGeneratingIndicator()
                refreshDailyQuotaUi()
                refreshChats()
            }
            ChatGenerationStatus.FAILED -> {
                val text = snapshot.errorMessage ?: snapshot.accumulatedText
                flushStreamingUpdate(snapshot.generationId, wrapper, text, isFinal = true)
                finishGeneration(snapshot.generationId)
                isSending = false
                updateSendState()
                hideGeneratingIndicator()
                refreshDailyQuotaUi()
            }
            ChatGenerationStatus.CANCELLED -> {
                finishGeneration(snapshot.generationId)
                isSending = false
                updateSendState()
                hideGeneratingIndicator()
            }
        }
    }

    private fun applyAiActivityState(snapshot: AiActivitySnapshot?) {
        val wrapper = currentAssistantMessage
        if (snapshot != null && wrapper != null && isActiveGeneration(snapshot.generationId, wrapper)) {
            wrapper.showActivity(snapshot)
            updateFloatingActivityIndicator(snapshot)
            scheduleScrollToBottomIfPinned()
        } else {
            wrapper?.hideActivity()
            updateFloatingActivityIndicator(null)
        }
        updateGeneratingIndicatorVisibility()
    }

    private fun updateFloatingActivityIndicator(snapshot: AiActivitySnapshot?) {
        if (snapshot != null && isGeneratingIndicatorVisible) {
            binding.typingDotsView.startAnimation()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isChatUiInitialized) {
            return
        }
        handleSharedChatIntent(intent)
        handleOpenChatIntent(intent)
        handleAssistantHandoffIntent(intent)
        handlePrefillInputIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::welcomePromptController.isInitialized) {
            welcomePromptController.saveState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!isChatUiInitialized) return
        if (::inAppBrowserManager.isInitialized) {
            inAppBrowserManager.onResume()
        }
        val currentLanguageCode = LocaleHelper.getSelectedLanguage(this)
        if (appliedLanguageCode != null && appliedLanguageCode != currentLanguageCode) {
            recreate()
            return
        }
        appliedLanguageCode = currentLanguageCode
        drawerManager.updateUserProfile()
        ChatGenerationManager.setVisibleChat(chatViewModel.currentChatId, true)
        refreshDailyQuotaUi()
        chatViewModel.performSync()
        applyTranslations()
        scheduleFreeChatAttentionAfterIdle()
    }

    override fun onPause() {
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
        freeChatAttentionDrawable?.cancelAttention()
        if (::inAppBrowserManager.isInitialized) {
            inAppBrowserManager.onPause()
        }
        ChatGenerationManager.setVisibleChat(chatViewModel.currentChatId, false)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!isChatUiInitialized) return
        freeChatAttentionDrawable?.cancelAttention()
        scheduleFreeChatAttentionAfterIdle()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::inAppBrowserManager.isInitialized && inAppBrowserManager.onBackPressed()) {
                    return
                }

                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun initializeChatUi(startIntent: Intent?) {
        if (isChatUiInitialized) return
        isChatUiInitialized = true
        appliedLanguageCode = LocaleHelper.getSelectedLanguage(this)

        setupHelpers()
        setupTopBar()
        setupDrawer()
        drawerManager.setGeneratingChatIds(generatingChatIds)
        chatViewModel.onChatListUpdated = {
            runOnUiThread {
                refreshDrawerSelection()
                ChatGenerationManager.setVisibleChat(
                    chatViewModel.currentChatId,
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                )
            }
        }
        setupInputArea()
        setupWelcomeActions()
        setupPreview()
        setupAds()
        refreshDailyQuotaUi()

        val swipeListener = object : OnSwipeTouchListener(this) {
            override fun onSwipeRight() {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        binding.messagesContainer.setOnTouchListener(swipeListener)
        binding.welcomeScreen.setOnTouchListener(swipeListener)

        // Перехватываем касания на ScrollView, чтобы блокировать автоскролл при касании пользователя
        binding.messagesScrollView.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    // Пользователь касается/тянет — блокируем автоскролл
                    isUserTouchingMessages = true
                    cancelPendingAutoScroll()
                    isUserAtBottom = false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Пользователь отпустил — проверяем позицию через короткую задержку
                    isUserTouchingMessages = false
                    binding.messagesScrollView.postDelayed({
                        recheckScrollPosition()
                    }, 100)
                }
            }
            // Передаём событие дальше для свайпа
            swipeListener.onTouch(v, event)
        }

        setupGeneratingIndicator()

        showWelcomeState()
        loadChats()
        handleSharedChatIntent(startIntent)
        handleOpenChatIntent(startIntent)
        handleAssistantHandoffIntent(startIntent)
        handlePrefillInputIntent(startIntent)
        applyTranslations()
        scheduleFreeChatAttentionAfterIdle()
    }

    private fun applyTranslations() {
        welcomePromptController.refreshPrompts()
        if (binding.welcomeScreen.isVisible) {
            welcomePromptController.start(resetIndex = false)
        }
        binding.tvBtnCreateImage.text = LocaleHelper.getString(this, "button_create_image")
        binding.tvBtnIdea.text = LocaleHelper.getString(this, "button_create_idea")
        binding.tvBtnMore.text = LocaleHelper.getString(this, "button_more")
        binding.tvAnonymousDesc.text = LocaleHelper.getString(this, "anonymous_mode_desc")
        binding.btnMenu.contentDescription = LocaleHelper.getString(this, "content_desc_menu")
        binding.btnChat.contentDescription = LocaleHelper.getString(this, "content_desc_anonymous_chat")
        binding.btnNewChat.contentDescription = LocaleHelper.getString(this, "content_desc_new_chat")
        binding.btnMore.contentDescription = LocaleHelper.getString(this, "content_desc_more_options")
        binding.btnPlus.contentDescription = LocaleHelper.getString(this, "content_desc_add_attachment")
        binding.btnMic.contentDescription = LocaleHelper.getString(this, "content_desc_microphone")
        binding.btnSend.contentDescription = LocaleHelper.getString(this, "content_desc_send")
        binding.btnRemovePreview.contentDescription = LocaleHelper.getString(this, "content_desc_remove_attachment")
        binding.btnCloseChip.contentDescription = LocaleHelper.getString(this, "content_desc_clear_mode")
        binding.tvEditMessageTitle.text = LocaleHelper.getString(this, "menu_edit_message")

        // Тексты бокового меню.
        findViewById<android.widget.TextView>(R.id.tvDrawerTitle)?.text = LocaleHelper.getString(this, "app_brand")
        findViewById<android.widget.TextView>(R.id.tvDrawerNewChat)?.text = LocaleHelper.getString(this, "button_new_chat")
        findViewById<android.widget.EditText>(R.id.etDrawerSearch)?.hint = LocaleHelper.getString(this, "panel_search")
        drawerManager.populateChats(chatViewModel.cachedChats) // Названия чатов перерисуются с нужной локалью.

        // Восстанавливаем подсказку поля ввода с учетом текущего режима.
        binding.etInput.hint = ChatModePresentation.inputHint(this, chatViewModel.currentMode)
    }

    override fun onDestroy() {
        if (::streamingUiController.isInitialized) {
            invalidateActiveGeneration(cancelScroll = true)
        }
        pendingSendJob?.cancel()
        pendingSendJob = null
        ChatGenerationManager.setVisibleChat(chatViewModel.currentChatId, false)
        if (::welcomePromptController.isInitialized) {
            welcomePromptController.stop()
        }
        if (::biometricGateController.isInitialized) {
            biometricGateController.dismiss()
        }
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
        freeChatAttentionDrawable?.cancelAttention()
        scrollToBottomController?.detach()
        scrollToBottomController = null
        if (::inAppBrowserManager.isInitialized) {
            inAppBrowserManager.onDestroy()
        }
        if (::speechRecognizerManager.isInitialized) {
            speechRecognizerManager.destroy()
        }
        chatViewModel.onChatListUpdated = null
        adManager?.destroy()
        super.onDestroy()
    }

    private fun Int.dpToPx(): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            toFloat(),
            resources.displayMetrics
        )
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
        setWelcomeActionButtonsVisible(mode == null)

        if (mode == ChatMode.SEARCH) {
            loadPopularNewsQueries()
        } else {
            syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
        }
    }

    override fun showFilePreview(fileUri: Uri) {
        attachmentPreviewController.showFilePreview(fileUri)
    }

    private fun imagePromptPrefix(): String = LocaleHelper.getString(this, "action_create_image")

    private fun ideaPromptPrefix(): String = LocaleHelper.getString(this, "prompt_idea_prefix")

    private fun quickSuggestionsFor(category: QuickSuggestionCategory): List<String> = when (category) {
        QuickSuggestionCategory.IMAGE -> listOf(
            LocaleHelper.getString(this, "quick_suggestion_image_1"),
            LocaleHelper.getString(this, "quick_suggestion_image_2"),
            LocaleHelper.getString(this, "quick_suggestion_image_3"),
            LocaleHelper.getString(this, "quick_suggestion_image_4")
        )

        QuickSuggestionCategory.IDEA -> listOf(
            LocaleHelper.getString(this, "quick_suggestion_idea_1"),
            LocaleHelper.getString(this, "quick_suggestion_idea_2"),
            LocaleHelper.getString(this, "quick_suggestion_idea_3"),
            LocaleHelper.getString(this, "quick_suggestion_idea_4")
        )
    }

    private fun activateImageSuggestions() {
        val imageMode = ChatModePresentation.imageCreationSpec(this)
        setInputContext(
            title = imageMode.title,
            iconRes = imageMode.iconRes,
            hint = imageMode.hint,
            mode = imageMode.mode
        )
    }

    private fun activateIdeaSuggestions() {
        clearInputContext(showWelcomeActions = false, syncSuggestions = false)
        showIdeaInputContext()
        isIdeaSuggestionsPinned = true
        updateInputText("", keepSuggestions = true)
    }

    private fun showIdeaInputContext() {
        val tint = Color.parseColor("#FFD60A")
        binding.contextChipContainer.isVisible = true
        binding.chipTitle.text = LocaleHelper.getString(this, "button_create_idea")
        binding.chipTitle.setTextColor(tint)
        binding.chipIcon.setImageResource(R.drawable.ic_bulb_yellow)
        binding.chipIcon.setColorFilter(tint)
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        setWelcomeActionButtonsVisible(false)
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

    private fun handlePrefillInputIntent(intent: Intent?) {
        val prefill = intent?.getStringExtra(EXTRA_PREFILL_INPUT)
            ?.takeIf { it.isNotBlank() }
            ?: return
        intent.removeExtra(EXTRA_PREFILL_INPUT)
        clearInputContext()
        updateInputText(prefill, keepSuggestions = false)
        binding.etInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun handleAssistantHandoffIntent(intent: Intent?) {
        val token = intent?.getStringExtra(DigitalAssistantHandoffStore.EXTRA_HANDOFF_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        intent.removeExtra(DigitalAssistantHandoffStore.EXTRA_HANDOFF_ID)
        val handoff = DigitalAssistantHandoffStore(this).consume(token) ?: return
        val chatId = handoff.chatId
        if (!chatId.isNullOrBlank()) {
            openChat(chatId) {
                restoreAssistantHandoffDraft(handoff)
            }
            return
        }
        clearInputContext()
        restoreAssistantHandoffDraft(handoff)
        binding.etInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun restoreAssistantHandoffDraft(handoff: com.example.chatapp.assistant.DigitalAssistantHandoff) {
        if (handoff.draftText.isNotBlank()) {
            updateInputText(handoff.draftText, keepSuggestions = false)
        }
        handoff.attachmentUri(this)?.let { uri ->
            attachmentPreviewController.assistantHandoffAttachmentPath = handoff.attachmentPath
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            showFilePreview(uri)
        }
    }

    private fun syncQuickSuggestions(query: String) {
        if (suppressSuggestionUpdates || attachmentPreviewController.currentPreviewUri != null || isSending || hasCompletedChatExchange()) {
            hideQuickSuggestions()
            return
        }

        if (chatViewModel.currentMode == ChatMode.SEARCH) {
            if (query.isBlank()) {
                showPopularNewsQueries(chatViewModel.popularNewsQueries)
            } else {
                hideQuickSuggestions()
            }
            return
        }

        if (chatViewModel.currentMode == ChatMode.CREATE_IMAGE) {
            if (query.isBlank()) {
                showQuickSuggestions(QuickSuggestionCategory.IMAGE)
            } else {
                hideQuickSuggestions()
            }
            return
        }

        if (isIdeaSuggestionsPinned) {
            if (query.isBlank()) {
                showQuickSuggestions(QuickSuggestionCategory.IDEA)
            } else {
                isIdeaSuggestionsPinned = false
                hideQuickSuggestions()
            }
            return
        }

        val normalized = query.trim().lowercase()
        val imagePrefix = imagePromptPrefix().trim().lowercase()
        val ideaPrefix = ideaPromptPrefix().trim().lowercase()
        val category = when {
            normalized.isBlank() -> null
            imagePrefix.isNotBlank() && normalized.startsWith(imagePrefix) ->
                QuickSuggestionCategory.IMAGE

            ideaPrefix.isNotBlank() && normalized.startsWith(ideaPrefix) ->
                QuickSuggestionCategory.IDEA

            else -> null
        }

        if (category == null) {
            hideQuickSuggestions()
        } else {
            showQuickSuggestions(category)
        }
    }

    private fun hasCompletedChatExchange(): Boolean {
        var hasUserMessage = false
        var hasAssistantMessage = false
        chatViewModel.chatHistory.forEach { message ->
            when (message.optString("role")) {
                "user" -> hasUserMessage = true
                "assistant" -> hasAssistantMessage = true
            }
            if (hasUserMessage && hasAssistantMessage) {
                return true
            }
        }
        return false
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
            QuickSuggestionCategory.IMAGE -> Color.parseColor("#34C759")
            QuickSuggestionCategory.IDEA -> Color.parseColor("#FFD60A")
        }
        val density = resources.displayMetrics.density

        activeSuggestionCategory = category
        binding.suggestionsTitle.isGone = true
        binding.suggestionsContainer.background = null
        binding.suggestionsContainer.setPadding(0, 0, 0, 0)
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

        showSuggestionsContainerAnimated()
    }

    private fun loadPopularNewsQueries() {
        hideQuickSuggestions()
        chatViewModel.loadPopularNewsQueries { queries ->
            runOnUiThread {
                if (chatViewModel.currentMode == ChatMode.SEARCH && binding.etInput.text.isNullOrBlank()) {
                    showPopularNewsQueries(queries)
                }
            }
        }
    }

    private fun showPopularNewsQueries(queries: List<String>) {
        val visibleQueries = queries.filter { it.isNotBlank() }.take(4)
        if (visibleQueries.isEmpty() || chatViewModel.currentMode != ChatMode.SEARCH || attachmentPreviewController.currentPreviewUri != null || isSending) {
            hideQuickSuggestions()
            return
        }

        val density = resources.displayMetrics.density
        activeSuggestionCategory = null
        binding.suggestionsTitle.isVisible = true
        binding.suggestionsTitle.text = LocaleHelper.getString(this, "popular_news_title")
        binding.suggestionsContainer.setBackgroundResource(R.drawable.bg_popular_queries)
        binding.suggestionsContainer.setPadding(
            (12 * density).toInt(),
            (12 * density).toInt(),
            (12 * density).toInt(),
            (12 * density).toInt()
        )
        binding.suggestionsList.removeAllViews()

        visibleQueries.forEachIndexed { index, query ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index < visibleQueries.lastIndex) {
                        bottomMargin = (6 * density).toInt()
                    }
                }
                setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
                isClickable = true
                isFocusable = true
                val selectable = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                setBackgroundResource(selectable.resourceId)
                setOnClickListener {
                    updateInputText(query, keepSuggestions = false)
                    binding.etInput.requestFocus()
                }
            }

            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
                    marginEnd = (12 * density).toInt()
                }
                setImageResource(R.drawable.ic_trending_up)
                setColorFilter(Color.parseColor("#D1D1D6"))
            }

            val textView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = query
                setTextColor(Color.parseColor("#D1D1D6"))
                textSize = 17f
                maxLines = 2
            }

            row.addView(icon)
            row.addView(textView)
            binding.suggestionsList.addView(row)
        }

        showSuggestionsContainerAnimated()
    }

    private fun applyQuickSuggestion(category: QuickSuggestionCategory, suggestion: String) {
        when (category) {
            QuickSuggestionCategory.IMAGE -> {
                if (chatViewModel.currentMode != ChatMode.CREATE_IMAGE) {
                    val imageMode = ChatModePresentation.imageCreationSpec(this)
                    setInputContext(
                        title = imageMode.title,
                        iconRes = imageMode.iconRes,
                        hint = imageMode.hint,
                        mode = imageMode.mode
                    )
                }
            }

            QuickSuggestionCategory.IDEA -> {
                isIdeaSuggestionsPinned = false
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
        val container = binding.suggestionsContainer
        container.animate().cancel()

        if (!container.isVisible) {
            binding.suggestionsList.removeAllViews()
            updateFloatingInputPadding()
            return
        }

        val slideDistance = 8f * resources.displayMetrics.density
        container.animate()
            .translationY(slideDistance)
            .alpha(0f)
            .scaleY(0.98f)
            .setDuration(SUGGESTIONS_HIDE_DURATION_MS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                container.isGone = true
                container.translationY = 0f
                container.scaleY = 1f
                container.alpha = 1f
                binding.suggestionsList.removeAllViews()
                resetSuggestionsContainerHeight()
                updateFloatingInputPadding()
            }
            .start()
    }

    private fun showSuggestionsContainerAnimated() {
        val container = binding.suggestionsContainer
        container.animate().cancel()
        constrainSuggestionsContainerHeight()

        if (!container.isVisible) {
            val slideDistance = 12f * resources.displayMetrics.density
            container.translationY = slideDistance
            container.scaleY = 0.96f
            container.alpha = 0f
            container.isVisible = true
        }

        container.pivotY = container.height.toFloat()
        container.animate()
            .translationY(0f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(SUGGESTIONS_SHOW_DURATION_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .withStartAction {
                updateFloatingInputPadding()
                keepLatestMessageReadable()
            }
            .withEndAction {
                updateFloatingInputPadding()
                keepLatestMessageReadable()
            }
            .start()
    }

    private fun constrainSuggestionsContainerHeight() {
        val container = binding.suggestionsContainer
        val rootHeight = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val topLimit = binding.topBar.bottom + dp(12f).toInt()
        val bottomLimit = binding.bottomInputArea.top - dp(8f).toInt()
        val availableHeight = (bottomLimit - topLimit).coerceAtLeast(dp(96f).toInt())
        val maxHeight = minOf(availableHeight, (rootHeight * 0.38f).toInt())
        val width = binding.root.width - binding.suggestionsContainer.paddingLeft -
            binding.suggestionsContainer.paddingRight - dp(32f).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        container.measure(widthSpec, heightSpec)
        val targetHeight = container.measuredHeight.coerceAtMost(maxHeight)

        container.layoutParams = container.layoutParams.apply {
            height = if (targetHeight > 0) targetHeight else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        container.isVerticalScrollBarEnabled = container.measuredHeight >= maxHeight
    }

    private fun resetSuggestionsContainerHeight() {
        binding.suggestionsContainer.layoutParams = binding.suggestionsContainer.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.suggestionsContainer.isVerticalScrollBarEnabled = false
    }

    private fun keepLatestMessageReadable() {
        if (!binding.messagesScrollView.isVisible) return
        if (isSending) {
            scheduleScrollToBottomIfPinned()
            return
        }
        binding.messagesScrollView.post {
            binding.messagesScrollView.smoothScrollTo(0, binding.messagesContainer.bottom)
        }
    }

    private fun nextGenerationId(): Long {
        return streamingUiController.nextGenerationId()
    }

    private fun finishGeneration(requestId: Long) {
        streamingUiController.finishGeneration(requestId)
    }

    private fun invalidateActiveGeneration(cancelScroll: Boolean = true) {
        streamingUiController.invalidate(cancelScroll = cancelScroll)
    }

    private fun isActiveGeneration(requestId: Long, wrapper: AssistantMessageWrapper): Boolean {
        return streamingUiController.isActive(requestId, wrapper)
    }

    private fun enqueueStreamingUpdate(
        requestId: Long,
        wrapper: AssistantMessageWrapper,
        text: String
    ) {
        streamingUiController.enqueue(requestId, wrapper, text)
    }

    private fun flushStreamingUpdate(
        requestId: Long,
        wrapper: AssistantMessageWrapper,
        text: String,
        isFinal: Boolean
    ) {
        streamingUiController.flush(requestId, wrapper, text, isFinal)
    }

    private fun attachCompletedAssistantMessage(wrapper: AssistantMessageWrapper) {
        val message = chatViewModel.chatHistory.lastOrNull()
            ?.takeIf { it.optString("role") == "assistant" }
            ?: return
        val syncId = message.optString("syncId").takeIf { it.isNotBlank() } ?: return
        wrapper.messageSyncId = syncId
        wrapper.reaction?.let { reaction ->
            chatViewModel.setAssistantReaction(syncId, reaction)
        }
    }

    private fun handleAssistantReactionChanged(syncId: String, reaction: String?) {
        chatViewModel.setAssistantReaction(syncId, reaction)
    }

    private fun cancelScheduledStreamFlush() {
        streamingUiController.cancelScheduledFlush()
    }

    private fun scheduleScrollToBottomIfPinned() {
        if (!binding.messagesScrollView.isVisible || !isUserAtBottom) return
        if (pendingAutoScrollRunnable != null) return

        val runnable = Runnable {
            pendingAutoScrollRunnable = null
            scrollToBottomIfPinned()
        }
        pendingAutoScrollRunnable = runnable
        binding.messagesScrollView.postOnAnimation(runnable)
    }

    private fun cancelPendingAutoScroll() {
        pendingAutoScrollRunnable?.let { binding.messagesScrollView.removeCallbacks(it) }
        pendingAutoScrollRunnable = null
    }

    private fun scrollToBottomIfPinned() {
        if (!binding.messagesScrollView.isVisible || !isUserAtBottom) return
        val targetScrollY = bottomScrollY()
        binding.messagesScrollView.scrollTo(0, targetScrollY)
        scrollToBottomController?.refresh()
        if (isMessagesAtBottom()) {
            hideGeneratingIndicator()
        }
    }

    private fun isMessagesAtBottom(): Boolean {
        if (!binding.messagesScrollView.isVisible) return true
        val threshold = dp(150f).toInt()
        return bottomScrollY() - binding.messagesScrollView.scrollY <= threshold
    }

    private fun bottomScrollY(): Int {
        val scrollView = binding.messagesScrollView
        val child = scrollView.getChildAt(0) ?: return 0
        return (child.bottom + scrollView.paddingBottom - scrollView.height).coerceAtLeast(0)
    }

    private fun setupHelpers() {
        inAppBrowserManager = InAppBrowserManager(
            activity = this,
            host = findViewById(android.R.id.content),
            onBeforeOpen = ::clearTextSelectionBeforeBrowserOpen
        )

        popupMenuHelper = PopupMenuHelper(
            activity = this,
            onRename = { chat, newTitle, complete ->
                chatViewModel.renameChat(
                    chatId = chat.id,
                    newTitle = newTitle,
                    isTitleManuallyEdited = true
                ) { result ->
                    runOnUiThread {
                        if (result.saved) {
                            drawerManager.queueTitleRenameAnimation(
                                chatId = chat.id,
                                oldTitle = chat.title,
                                newTitle = result.title
                            )
                        }
                        complete()
                        refreshChats()
                        if (!result.saved) {
                            toast(LocaleHelper.getString(this, "toast_name_empty"))
                        }
                    }
                }
            },
            onTogglePin = { chat ->
                chatViewModel.togglePinChat(chat.id, chat.isPinned) {
                    refreshChats()
                }
            },
            onShare = { chat ->
                shareChat(chat)
            },
            onRevokeShares = { chat ->
                revokeChatShareLinks(chat)
            },
            onDelete = { chat ->
                chatViewModel.deleteChat(chat.id) {
                    if (chatViewModel.currentChatId == chat.id) {
                        startFreshChat()
                    }
                    refreshChats()
                }
            },
            onRegenerate = ::regenerateAssistantResponse,
            onEditUserMessage = { historyIndex, message ->
                beginEditingUserMessage(historyIndex, message)
            }
        )

        messageRenderer = ChatMessageRenderer(
            context = this,
            messagesContainer = binding.messagesContainer,
            popupMenuHelper = popupMenuHelper,
            onRegenerate = ::regenerateAssistantResponse,
            onUserMessageLongClick = { anchor, message, historyIndex ->
                popupMenuHelper.showUserMessageOptionsMenu(anchor, message, historyIndex)
            },
            onAssistantContentChanged = ::scheduleScrollToBottomIfPinned,
            onAssistantReactionChanged = ::handleAssistantReactionChanged,
            onOpenLink = { url -> inAppBrowserManager.open(url) }
        )

        speechRecognizerManager = SpeechRecognizerManager(
            context = this,
            etInput = binding.etInput,
            waveformContainer = binding.waveformContainer
        )
        speechRecognizerManager.setup()
        speechRecognizerManager.setupMicButton(binding.btnMic, this)
    }

    private fun clearTextSelectionBeforeBrowserOpen() {
        SelectableTextSupport.clearAllSelections()
        binding.etInput.text?.let { editable ->
            Selection.setSelection(editable, editable.length)
        }
        binding.etInput.clearFocus()
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
            toggleAnonymousChat()
        }
        binding.btnAddLimits.setHapticClickListener {
            adManager?.show()
        }
        freeChatAttentionDrawable = FreeChatAttentionDrawable(resources.displayMetrics.density)
        scheduleFreeChatAttentionAfterIdle()
        setupMainButtonPressAnimations()
    }

    private fun scheduleFreeChatAttentionAfterIdle() {
        if (!::binding.isInitialized || isFreeChatButtonInteracting) return
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
        freeChatAttentionHandler.postDelayed(freeChatAttentionRunnable, FREE_CHAT_ATTENTION_IDLE_DELAY_MS)
    }

    private fun pauseFreeChatAttention() {
        isFreeChatButtonInteracting = true
        freeChatAttentionDrawable?.isInteracting = true
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
    }

    private fun resumeFreeChatAttentionAfterInteraction() {
        isFreeChatButtonInteracting = false
        freeChatAttentionDrawable?.isInteracting = false
        scheduleFreeChatAttentionAfterIdle()
    }

    private fun setupMainButtonPressAnimations() {
        setupPressAnimation(binding.btnAddLimits, pressedScale = 0.96f, pressedOffset = dp(2f), pressedTranslationZ = -dp(2f))
        setupPressAnimation(binding.btnMenu)
        setupPressAnimation(binding.btnChat, target = binding.topRightMain)
        setupPressAnimation(binding.btnNewChat)
        setupPressAnimation(binding.btnMore)
        setupPressAnimation(binding.btnCreateImage, pressedScale = 0.97f)
        setupPressAnimation(binding.btnIdea, pressedScale = 0.97f)
        setupPressAnimation(binding.btnCenterMore, pressedScale = 0.97f)
        setupPressAnimation(binding.btnPlus)
        setupPressAnimation(binding.btnSend)
        setupPressAnimation(binding.btnCloseChip, pressedScale = 0.88f)
        setupPressAnimation(binding.btnCloseEditMessage, pressedScale = 0.88f)
        setupPressAnimation(binding.btnRemovePreview, pressedScale = 0.88f)
    }

    private fun setupPressAnimation(
        touchSource: View,
        target: View = touchSource,
        pressedScale: Float = 0.92f,
        pressedOffset: Float = 0f,
        pressedTranslationZ: Float = 0f
    ) {
        ChatUiAnimationHelper.installPressAnimation(
            touchSource = touchSource,
            target = target,
            pressedScale = pressedScale,
            pressedOffset = pressedOffset,
            pressedTranslationZ = pressedTranslationZ,
            onPressStart = {
                if (touchSource === binding.btnAddLimits) {
                    pauseFreeChatAttention()
                }
            },
            onPressEnd = {
                if (touchSource === binding.btnAddLimits) {
                    resumeFreeChatAttentionAfterInteraction()
                }
            }
        )
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
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = Unit
            override fun onDrawerOpened(drawerView: View) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
            }
            override fun onDrawerClosed(drawerView: View) {
                // Сбрасываем поиск при закрытии бокового меню.
                val sc = findViewById<LinearLayout>(R.id.drawerSearchContainer) ?: return
                if (sc.visibility == View.VISIBLE) {
                    val sf = findViewById<EditText>(R.id.etDrawerSearch)
                    val dhc = findViewById<LinearLayout>(R.id.defaultHeaderContent)
                    val csb = findViewById<ImageView>(R.id.btnCloseDrawerSearch)
                    sf?.setText("")
                    sf?.alpha = 0f
                    csb?.alpha = 0f
                    sc.gravity = android.view.Gravity.CENTER
                    sc.setPadding(0, 0, 0, 0)
                    val lp = sc.layoutParams
                    lp.width = sc.height
                    sc.layoutParams = lp
                    sc.visibility = View.INVISIBLE
                    dhc?.alpha = 1f
                    dhc?.visibility = View.VISIBLE
                    drawerManager.populateChats(chatViewModel.cachedChats)
                }
            }
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
            openTelegramBot()
        }

        val defaultHeaderContent = findViewById<LinearLayout>(R.id.defaultHeaderContent)
        val searchContainer = findViewById<LinearLayout>(R.id.drawerSearchContainer)
        val searchField = findViewById<EditText>(R.id.etDrawerSearch)
        val closeSearchBtn = findViewById<ImageView>(R.id.btnCloseDrawerSearch)

        fun expandSearchBar() {
            searchContainer.visibility = View.VISIBLE
            searchContainer.measure(
                View.MeasureSpec.makeMeasureSpec(defaultHeaderContent.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(searchContainer.height, View.MeasureSpec.EXACTLY)
            )
            val targetWidth = defaultHeaderContent.width
            val startWidth = searchContainer.height // Круг равен высоте.
            val interpolator = AccelerateDecelerateInterpolator()

            // Плавно скрываем заголовок.
            defaultHeaderContent.animate()
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(interpolator)
                .withEndAction { defaultHeaderContent.isGone = true }
                .start()

            // Расширяем поле поиска из круглой кнопки до полной ширины.
            val widthAnimator = ValueAnimator.ofInt(startWidth, targetWidth).apply {
                duration = 320
                this.interpolator = interpolator
                addUpdateListener { anim ->
                    val lp = searchContainer.layoutParams
                    lp.width = anim.animatedValue as Int
                    searchContainer.layoutParams = lp
                    // Центрируем поле после середины анимации.
                    val fraction = anim.animatedFraction
                    if (fraction > 0.5f) {
                        searchContainer.gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        // Показываем содержимое по мере расширения поля.
                        searchField.animate()
                            .alpha(1f)
                            .setStartDelay(160)
                            .setDuration(180)
                            .start()
                        closeSearchBtn.animate()
                            .alpha(1f)
                            .setStartDelay(200)
                            .setDuration(160)
                            .start()
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        val lp = searchContainer.layoutParams as? android.widget.FrameLayout.LayoutParams
                        if (lp != null) {
                            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            searchContainer.layoutParams = lp
                        }
                        searchContainer.setPadding(
                            (14 * resources.displayMetrics.density).toInt(), 0,
                            (14 * resources.displayMetrics.density).toInt(), 0
                        )
                        searchField.requestFocus()
                        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                })
            }
            widthAnimator.start()
        }

        fun collapseSearchBar() {
            val targetWidth = searchContainer.height // Сжимаем обратно до круга.
            val startWidth = searchContainer.width
            val interpolator = AccelerateDecelerateInterpolator()

            // Сначала скрываем содержимое.
            searchField.animate().alpha(0f).setDuration(120).setStartDelay(0).start()
            closeSearchBtn.animate().alpha(0f).setDuration(120).setStartDelay(0).start()

            // Затем сжимаем поле обратно в кнопку.
            val widthAnimator = ValueAnimator.ofInt(startWidth, targetWidth).apply {
                duration = 300
                this.interpolator = interpolator
                startDelay = 80
                addUpdateListener { anim ->
                    val lp = searchContainer.layoutParams
                    lp.width = anim.animatedValue as Int
                    searchContainer.layoutParams = lp
                    if (anim.animatedFraction > 0.5f) {
                        searchContainer.gravity = android.view.Gravity.CENTER
                        searchContainer.setPadding(0, 0, 0, 0)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        searchContainer.visibility = View.INVISIBLE
                        searchContainer.gravity = android.view.Gravity.CENTER
                        // Возвращаем заголовок.
                        defaultHeaderContent.alpha = 0f
                        defaultHeaderContent.isVisible = true
                        defaultHeaderContent.animate()
                            .alpha(1f)
                            .setDuration(200)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                })
            }
            widthAnimator.start()
        }

        findViewById<ImageView>(R.id.btnDrawerSearch).setOnClickListener {
            expandSearchBar()
        }
        closeSearchBtn.setOnClickListener {
            searchField.setText("")
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchField.windowToken, 0)
            collapseSearchBar()
            drawerManager.populateChats(chatViewModel.cachedChats)
        }
        searchField.doAfterTextChanged { query ->
            filterChats(query?.toString().orEmpty())
        }
    }

    private fun openTelegramBot() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/FreeChatAI_Robot"))
        runCatching { startActivity(intent) }
            .onFailure { toast(LocaleHelper.getString(this, "toast_open_link_error")) }
    }

    private fun setupInputArea() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            navigationBarInsetBottom = systemBars.bottom
            systemWindowInsetTop = systemBars.top
            updateBottomInputSystemInset()
            updateTopInputSystemInset()
            updateFloatingInputPadding()
            insets
        }
        binding.topBar.viewTreeObserver.addOnGlobalLayoutListener {
            updateFloatingInputPadding()
        }
        binding.bottomInputArea.viewTreeObserver.addOnGlobalLayoutListener {
            updateFloatingInputPadding()
        }
        binding.bottomInputArea.alpha = 1f
        binding.bottomInputArea.translationY = 0f
        binding.topInputScrim.animate().cancel()
        binding.topInputScrim.alpha = 0f
        binding.topInputScrim.isGone = true
        binding.bottomInputScrim.animate().cancel()
        binding.bottomInputScrim.alpha = 0f
        binding.bottomInputScrim.isGone = true

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
            if (isSending) {
                stopGeneration()
            } else {
                sendMessage()
            }
        }
        binding.btnPlus.setHapticClickListener {
            BottomSheetMenuFragment().show(supportFragmentManager, "bottom_sheet_menu")
        }
        binding.btnCloseChip.setHapticClickListener {
            clearInputContext()
        }
        binding.btnCloseEditMessage.setHapticClickListener {
            cancelEditingUserMessage()
        }
    }

    private fun updateFloatingInputPadding() {
        updateMessagesViewportAnchor()
        
        val topBarHeight = binding.topBar.height
        val dynamicTopPadding = if (topBarHeight > 0) {
            topBarHeight + dp(8f).toInt()
        } else {
            dp(64f).toInt()
        }
        val finalTopPadding = dynamicTopPadding + systemWindowInsetTop
        
        val inputHeight = binding.bottomInputArea.height
        val dynamicBottomPadding = if (inputHeight > 0) {
            inputHeight + dp(24f).toInt()
        } else {
            dp(120f).toInt()
        }
        val finalBottomPadding = dynamicBottomPadding + navigationBarInsetBottom

        if (
            binding.messagesScrollView.paddingTop == finalTopPadding &&
            binding.messagesScrollView.paddingBottom == finalBottomPadding
        ) {
            return
        }
        binding.messagesScrollView.setPadding(
            binding.messagesScrollView.paddingLeft,
            finalTopPadding,
            binding.messagesScrollView.paddingRight,
            finalBottomPadding
        )
    }

    private fun updateBottomInputSystemInset() {
        val targetBottomMargin = navigationBarInsetBottom + dp(10f).toInt()
        val inputParams = binding.bottomInputArea.layoutParams as ViewGroup.MarginLayoutParams
        if (inputParams.bottomMargin != targetBottomMargin) {
            inputParams.bottomMargin = targetBottomMargin
            binding.bottomInputArea.layoutParams = inputParams
        }

        binding.bottomInputScrim.animate().cancel()
        binding.bottomInputScrim.alpha = 0f
        binding.bottomInputScrim.isGone = true
    }

    private fun updateTopInputSystemInset() {
        val targetTopMargin = systemWindowInsetTop
        val topBarParams = binding.topBar.layoutParams as ViewGroup.MarginLayoutParams
        if (topBarParams.topMargin != targetTopMargin) {
            topBarParams.topMargin = targetTopMargin
            binding.topBar.layoutParams = topBarParams
        }

        binding.topInputScrim.animate().cancel()
        binding.topInputScrim.alpha = 0f
        binding.topInputScrim.isGone = true
    }

    private fun updateMessagesViewportAnchor() {
        val params = binding.messagesScrollView.layoutParams as ConstraintLayout.LayoutParams
        if (params.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID) {
            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomMargin = 0
            binding.messagesScrollView.layoutParams = params
        }
        
        val welcomeParams = binding.welcomeScreen.layoutParams as ConstraintLayout.LayoutParams
        if (welcomeParams.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID) {
            welcomeParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            welcomeParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.welcomeScreen.layoutParams = welcomeParams
        }
        
        val anonWelcomeParams = binding.anonymousWelcomeScreen.layoutParams as ConstraintLayout.LayoutParams
        if (anonWelcomeParams.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID) {
            anonWelcomeParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            anonWelcomeParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.anonymousWelcomeScreen.layoutParams = anonWelcomeParams
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
        binding.btnAddLimits.isVisible = true
        adManager = RewardedAdManager(this) {
            chatViewModel.addLimits(5) {
                runOnUiThread {
                    refreshDailyQuotaUi()
                    toast(LocaleHelper.getString(this, "toast_limits_added"))
                }
            }
        }
        adManager?.initialize()
    }


    private fun showWelcomeState() {
        resetTopActionsAnimation()
        binding.welcomeScreen.isVisible = true
        welcomePromptController.start(resetIndex = true)
        setWelcomeActionButtonsVisible(chatViewModel.currentMode == null)
        binding.anonymousWelcomeScreen.isGone = true
        binding.messagesScrollView.isGone = true
        scrollToBottomController?.refresh()
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true

        updateTopBarIcons()

        binding.tvBtnCreateImage.text = LocaleHelper.getString(this, "button_create_image")
        binding.tvBtnIdea.text = LocaleHelper.getString(this, "button_create_idea")
        binding.tvBtnMore.text = LocaleHelper.getString(this, "button_more")
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
    }

    private fun showAnonymousWelcomeState() {
        welcomePromptController.stop()
        resetTopActionsAnimation()
        binding.welcomeScreen.isGone = true
        binding.anonymousWelcomeScreen.isVisible = true
        binding.messagesScrollView.isGone = true
        scrollToBottomController?.refresh()
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true

        updateTopBarIcons()

        binding.tvAnonymousDesc.text = LocaleHelper.getString(this, "anonymous_mode_desc")
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
    }

    private fun showMessagesState(animateTopActions: Boolean = false) {
        welcomePromptController.stop()
        binding.welcomeScreen.isGone = true
        binding.anonymousWelcomeScreen.isGone = true
        binding.messagesScrollView.isVisible = true
        scrollToBottomController?.refresh()
        if (animateTopActions && binding.topRightMain.isVisible) {
            animateTopActionsSplit()
        } else {
            resetTopActionsAnimation()
            binding.topRightMain.isGone = true
            binding.topRightChat.isVisible = true
        }
    }

    private fun resetTopActionsAnimation() {
        topActionsAnimator?.cancel()
        topActionsAnimator = null
        resetTopActionsTransforms()
    }

    private fun resetTopActionsTransforms() {
        ChatUiAnimationHelper.resetTopActionTransforms(
            binding.topRightMain,
            binding.topRightChat,
            binding.btnNewChat,
            binding.btnMore
        )
    }

    private fun animateTopActionsSplit() {
        topActionsAnimator?.cancel()
        ChatUiAnimationHelper.animateTopActionsSplit(
            topRightMain = binding.topRightMain,
            topRightChat = binding.topRightChat,
            btnNewChat = binding.btnNewChat,
            btnMore = binding.btnMore,
            durationMs = TOP_ACTIONS_SPLIT_DURATION_MS,
            density = resources.displayMetrics.density,
            onAnimatorReady = { animator -> topActionsAnimator = animator },
            onFinished = { topActionsAnimator = null }
        )
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }

    private fun startFreshChat() {
        invalidateActiveGeneration(cancelScroll = true)
        chatViewModel.resetChatState()
        currentAssistantMessage = null
        isSending = false
        clearEditingMessageState()
        binding.etInput.text?.clear()
        binding.messagesContainer.removeAllViews()
        clearInputContext()
        clearPreview()
        hideQuickSuggestions()
        showWelcomeState()
        updateSendState()
        refreshDrawerSelection()
        ChatGenerationManager.setVisibleChat(null, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    private fun toggleAnonymousChat() {
        if (chatViewModel.isAnonymousChat) {
            chatViewModel.isAnonymousChat = false
            startFreshChat()
        } else {
            startFreshChat()
            chatViewModel.isAnonymousChat = true
            showAnonymousWelcomeState()
        }
    }

    private fun updateTopBarIcons() {
        val isAnon = chatViewModel.isAnonymousChat
        binding.btnChat.setImageResource(
            if (isAnon) R.drawable.ic_private_chat_checked else R.drawable.ic_anonymous_chat
        )
        binding.btnAddLimits.isVisible = true
    }

    private fun startAnonymousChat() {
        startFreshChat()
        chatViewModel.isAnonymousChat = true
        showAnonymousWelcomeState()
    }

    private fun loadChats() {
        chatViewModel.loadChats {
            runOnUiThread {
                drawerManager.setSelectedChatId(chatViewModel.currentChatId)
                drawerManager.populateChats(chatViewModel.cachedChats)
                drawerManager.updateUserProfile()
                val activeChatId = chatViewModel.generationSnapshots.value.values
                    .firstOrNull { it.isRunning && !it.chatId.isNullOrBlank() }
                    ?.chatId
                if (chatViewModel.currentChatId == null && activeChatId != null) {
                    openChat(activeChatId)
                }
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
        drawerManager.setGeneratingChatIds(generatingChatIds)
        drawerManager.populateChats(chats, trimmed)
    }

    private fun openChat(chatId: String, onOpened: (() -> Unit)? = null) {
        invalidateActiveGeneration(cancelScroll = true)
        lifecycleScope.launch {
            val chat = chatViewModel.getChatById(chatId) ?: return@launch
            val messages = try {
                chatViewModel.getMessages(chatId)
            } catch (e: android.database.sqlite.SQLiteBlobTooBigException) {
                SafeLog.w("FreeChatActivity", "Chat database row is too large", e)
                // Удаляем поврежденный чат, чтобы пользователь не попадал в бесконечный цикл падений.
                chatViewModel.deleteChat(chatId) {
                    runOnUiThread {
                        toast(LocaleHelper.getString(this@FreeChatActivity, "toast_error") + ": Chat corrupted and was deleted")
                        startFreshChat()
                    }
                }
                return@launch
            } catch (e: Exception) {
                SafeLog.w("FreeChatActivity", "Could not open chat", e)
                return@launch
            }

            chatViewModel.resetChatState()
            chatViewModel.currentChatId = chat.id
            chatViewModel.currentChatTitle = chat.title
            chatViewModel.chatContextSummary = chat.summary
            chatViewModel.isFirstMessage = messages.none { it.role == "user" }
            clearEditingMessageState()
            binding.etInput.text?.clear()
            clearPreview()

            binding.messagesContainer.removeAllViews()
            currentAssistantMessage = null
            isSending = false
            val activeGeneration = chatViewModel.activeGenerationForChat(chat.id)
            messages.forEach { message ->
                val historyIndex = chatViewModel.chatHistory.size
                chatViewModel.chatHistory.add(
                    JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                        message.imageUrl?.let { put("imageUri", it) }
                        message.attachmentData?.let { put("base64", it) }
                        message.attachmentMimeType?.let { put("mimeType", it) }
                        message.attachmentFileName?.let { put("fileName", it) }
                        message.attachmentContext?.let { put("fileContext", it) }
                        put("syncId", message.syncId)
                        put("timestamp", message.timestamp)
                        put("updatedAt", message.updatedAt)
                        put("editRevision", message.editRevision)
                        put("isDeleted", message.isDeleted)
                        message.reaction?.let { put("reaction", it) }
                    }
                )

                if (message.role == "user") {
                    messageRenderer.renderRestoredUserMessage(
                        content = message.content,
                        imageUrl = message.imageUrl,
                        attachmentData = message.attachmentData,
                        attachmentMimeType = message.attachmentMimeType,
                        attachmentFileName = message.attachmentFileName,
                        historyIndex = historyIndex
                    )
                } else {
                    val assistantContent = if (
                        !AssistantMessageWrapper.containsImageReply(message.content) &&
                        !message.imageUrl.isNullOrBlank()
                    ) {
                        "![image](${message.imageUrl})"
                    } else {
                        message.content
                    }
                    val wrapper = messageRenderer.addAssistantMessage(
                        text = assistantContent,
                        animate = false,
                        isImageMode = AssistantMessageWrapper.containsImageReply(assistantContent),
                        messageSyncId = message.syncId,
                        reaction = message.reaction
                    )
                    if (activeGeneration?.assistantSyncId == message.syncId) {
                        currentAssistantMessage = wrapper
                        isSending = true
                        streamingUiController.attachGeneration(activeGeneration.generationId)
                        if (activeGeneration.accumulatedText.isNotBlank()) {
                            flushStreamingUpdate(
                                activeGeneration.generationId,
                                wrapper,
                                activeGeneration.accumulatedText,
                                isFinal = false
                            )
                        }
                    }
                }
            }

            showMessagesState()
            hideQuickSuggestions()
            isUserAtBottom = true
            scheduleScrollToBottomIfPinned()
            refreshDrawerSelection()
            updateSendState()
            ChatGenerationManager.setVisibleChat(chatViewModel.currentChatId, true)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            onOpened?.invoke()
        }
    }

    private fun refreshDrawerSelection() {
        if (!::drawerManager.isInitialized) return
        val query = findViewById<EditText>(R.id.etDrawerSearch)?.text?.toString().orEmpty()
        filterChats(query)
    }

    private fun shareChat(chat: ChatEntity) {
        chatViewModel.createShareLink(chat.id) { result ->
            runOnUiThread {
                result.onSuccess { share ->
                    toast(LocaleHelper.getString(this, "toast_share_link_created"))
                    FileUtils.shareText(this, share.shareUrl)
                }.onFailure { error ->
                    toast(userFacingError(error, "toast_share_error"))
                }
            }
        }
    }

    private fun revokeChatShareLinks(chat: ChatEntity) {
        chatViewModel.revokeShareLinks(chat.id) { result ->
            runOnUiThread {
                result.onSuccess { revoke ->
                    val key = if (revoke.revoked) {
                        "toast_share_links_revoked"
                    } else {
                        "toast_share_links_not_found"
                    }
                    toast(LocaleHelper.getString(this, key))
                }.onFailure { error ->
                    toast(userFacingError(error, "toast_share_revoke_error"))
                }
            }
        }
    }

    private fun handleSharedChatIntent(intent: Intent?) {
        val token = ChatShareDeepLink.extractToken(intent?.data) ?: return
        if (handledShareToken == token) return
        handledShareToken = token
        intent?.data = null

        toast(LocaleHelper.getString(this, "toast_share_importing"))
        chatViewModel.importSharedChat(token) { result ->
            runOnUiThread {
                result.onSuccess { chatId ->
                    openChat(chatId)
                    refreshChats()
                    toast(LocaleHelper.getString(this, "toast_share_imported"))
                }.onFailure { error ->
                    toast(userFacingError(error, "toast_share_import_error"))
                }
            }
        }
    }

    private fun handleOpenChatIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra(EXTRA_OPEN_CHAT_ID)?.takeIf { it.isNotBlank() }
            ?: return
        intent.removeExtra(EXTRA_OPEN_CHAT_ID)
        openChat(chatId)
    }

    private fun userFacingError(error: Throwable, fallbackKey: String): String {
        val raw = error.message.orEmpty()
        val parsedMessage = runCatching {
            JSONObject(raw).optString("message")
        }.getOrNull()

        return parsedMessage?.takeIf { it.isNotBlank() }
            ?: raw.takeIf { it.isNotBlank() && it.length <= 160 }
            ?: LocaleHelper.getString(this, fallbackKey)
    }

    // Отправка и streaming остаются здесь: метод одновременно управляет UI, лимитами, ViewModel и текущим состоянием экрана.
    private fun sendMessage() {
        if (isSending) return

        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        val editingHistoryIndex = editingMessageHistoryIndex
        if (editingHistoryIndex != null) {
            editUserMessage(editingHistoryIndex, text)
            return
        }

        val previewUri = attachmentPreviewController.currentPreviewUri
        if (text.isBlank() && previewUri == null) return

        if (!chatViewModel.consumeLimit()) {
            refreshDailyQuotaUi()
            toast(LocaleHelper.getString(this, "toast_limits_exhausted"))
            return
        }

        isSending = true
        updateSendState()
        pendingSendJob = lifecycleScope.launch {
            val attachmentPayload = try {
                attachmentHelper.buildAttachmentPayload(previewUri)
            } catch (e: IllegalArgumentException) {
                if (isActive) {
                    isSending = false
                    pendingSendJob = null
                    updateSendState()
                    toast(e.message ?: LocaleHelper.getString(this@FreeChatActivity, "attachment_read_error"))
                }
                return@launch
            }
            pendingSendJob = null
            if (!isActive || !isSending) return@launch
            startPreparedSend(text, previewUri, attachmentPayload)
        }
    }

    private fun startPreparedSend(text: String, previewUri: Uri?, attachmentPayload: AttachmentPayload?) {
        val mimeType = attachmentPayload?.mimeType
        val userHistoryIndex = chatViewModel.chatHistory.size

        val shouldAnimateTopActions = chatViewModel.isFirstMessage && binding.topRightMain.isVisible
        val requestId = nextGenerationId()
        isUserAtBottom = true
        hideQuickSuggestions()
        showMessagesState(animateTopActions = shouldAnimateTopActions)
        refreshDailyQuotaUi()

        when {
            previewUri == null -> messageRenderer.addUserMessage(text, userHistoryIndex)
            mimeType?.startsWith("image/") == true -> messageRenderer.addUserMessageWithImage(text, previewUri, userHistoryIndex)
            else -> messageRenderer.addUserMessageWithFile(text, previewUri, userHistoryIndex)
        }

        if (chatViewModel.isFirstMessage) {
            chatViewModel.currentChatTitle = when {
                text.isNotBlank() -> text.take(60)
                previewUri != null -> LocaleHelper.getString(this, "label_file_analysis")
                else -> LocaleHelper.getString(this, "label_new_chat")
            }
            chatViewModel.isFirstMessage = false
        }

        val isImageRequest = chatViewModel.currentMode == ChatMode.CREATE_IMAGE
        val wrapper = messageRenderer.addAssistantMessage(
            text = "",
            animate = false,
            isImageMode = isImageRequest
        )
        currentAssistantMessage = wrapper
        scheduleScrollToBottomIfPinned()
        binding.etInput.text?.clear()
        clearPreview()
        updateSendState()

        var lastAccumulated = ""
        chatViewModel.addToChatHistoryAndSend(
            content = text,
            base64Data = attachmentPayload?.base64Data,
            fileUri = attachmentPayload?.fileUri,
            mimeType = mimeType,
            fileName = attachmentPayload?.fileName,
            fileContext = attachmentPayload?.attachmentContext,
            activityGenerationId = requestId,
            onError = { error ->
                runOnUiThread {
                    if (!isActiveGeneration(requestId, wrapper)) return@runOnUiThread
                    flushStreamingUpdate(requestId, wrapper, error, isFinal = true)
                    finishGeneration(requestId)
                    isSending = false
                    updateSendState()
                    hideGeneratingIndicator()
                    refreshDailyQuotaUi()
                    toast(error)
                }
            },
            onChunk = { chunk ->
                lastAccumulated = chunk
                runOnUiThread {
                    enqueueStreamingUpdate(requestId, wrapper, chunk)
                }
            },
            onStreamComplete = {
                runOnUiThread {
                    if (!isActiveGeneration(requestId, wrapper)) return@runOnUiThread
                    flushStreamingUpdate(requestId, wrapper, lastAccumulated, isFinal = true)
                    attachCompletedAssistantMessage(wrapper)
                    finishGeneration(requestId)
                    isSending = false

                    updateSendState()
                    hideGeneratingIndicator()
                    refreshDailyQuotaUi()
                    refreshChats()
                }
            }
        )
    }

    private fun stopGeneration() {
        if (!isSending) return

        pendingSendJob?.cancel()
        pendingSendJob = null

        val requestId = streamingUiController.activeGenerationId
        val wrapper = currentAssistantMessage
        chatViewModel.cancelActiveResponse()
        cancelScheduledStreamFlush()
        if (wrapper != null && isActiveGeneration(requestId, wrapper)) {
            val latestText = streamingUiController.pendingText ?: wrapper.rawText
            if (latestText.isNotBlank()) {
                flushStreamingUpdate(requestId, wrapper, latestText, isFinal = true)
            }
        }
        finishGeneration(requestId)
        cancelPendingAutoScroll()
        isSending = false
        updateSendState()
        hideGeneratingIndicator()

        val activeWrapper = currentAssistantMessage ?: return
        val thinkingText = LocaleHelper.getString(this, "ai_thinking")
        if (activeWrapper.rawText.isBlank() || activeWrapper.rawText == thinkingText) {
            binding.messagesContainer.removeView(activeWrapper.container)
            currentAssistantMessage = null
        }
    }

    private fun beginEditingUserMessage(historyIndex: Int, message: String) {
        if (isSending || historyIndex !in 0 until chatViewModel.chatHistory.size) return

        invalidateActiveGeneration(cancelScroll = true)
        val historyMessage = chatViewModel.chatHistory[historyIndex]
        editingMessageHistoryIndex = historyIndex
        binding.tvEditMessageTitle.text = LocaleHelper.getString(this, "menu_edit_message")
        setInputCapsuleTopOffset(dp(38f).toInt())
        showEditMessagePanel()
        clearPreview()
        attachmentPreviewController.retainedEditingAttachment = MessageInputHelper.attachmentPayloadFromHistory(historyMessage)
        showRetainedAttachmentPreview(attachmentPreviewController.retainedEditingAttachment)
        hideQuickSuggestions()
        updateInputText(
            MessageInputHelper.editableTextFromHistory(
                message = historyMessage,
                fallback = message,
                attachmentPlaceholder = LocaleHelper.getString(this, "attachment_empty_text")
            ),
            keepSuggestions = false
        )
        binding.etInput.requestFocus()

        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        updateFloatingInputPadding()
    }

    private fun showEditMessagePanel() {
        binding.editMessageBanner.apply {
            animate().cancel()
            isVisible = true
            alpha = 0f
            translationY = dp(8f)
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .start()
        }
    }

    private fun clearEditingMessageState() {
        editingMessageHistoryIndex = null
        binding.editMessageBanner.animate().cancel()
        binding.editMessageBanner.alpha = 1f
        binding.editMessageBanner.translationY = 0f
        binding.editMessageBanner.isGone = true
        setInputCapsuleTopOffset(0)
    }

    private fun cancelEditingUserMessage() {
        clearEditingMessageState()
        attachmentPreviewController.retainedEditingAttachment = null
        binding.etInput.text?.clear()
        clearPreview()
        updateSendState()
        syncQuickSuggestions("")
    }

    private fun setInputCapsuleTopOffset(offsetPx: Int) {
        val params = binding.inputCapsule.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == offsetPx) return
        params.topMargin = offsetPx
        binding.inputCapsule.layoutParams = params
    }

    private fun showRetainedAttachmentPreview(payload: AttachmentPayload?) {
        attachmentPreviewController.showRetainedAttachmentPreview(payload)
    }

    private fun editUserMessage(historyIndex: Int, newText: String) {
        if (isSending) return
        if (historyIndex !in 0 until chatViewModel.chatHistory.size) return

        if (!chatViewModel.consumeLimit()) {
            refreshDailyQuotaUi()
            toast(LocaleHelper.getString(this, "toast_limits_exhausted"))
            return
        }

        isSending = true
        updateSendState()
        pendingSendJob = lifecycleScope.launch {
            val attachmentPayload = try {
                attachmentPreviewController.currentPreviewUri?.let { attachmentHelper.buildAttachmentPayload(it) }
                    ?: attachmentPreviewController.retainedEditingAttachment
            } catch (e: IllegalArgumentException) {
                if (isActive) {
                    isSending = false
                    pendingSendJob = null
                    updateSendState()
                    toast(e.message ?: LocaleHelper.getString(this@FreeChatActivity, "attachment_read_error"))
                }
                return@launch
            }
            pendingSendJob = null
            if (!isActive || !isSending) return@launch
            prepareEditedUserMessage(historyIndex, newText, attachmentPayload)
        }
    }

    private fun prepareEditedUserMessage(
        historyIndex: Int,
        newText: String,
        attachmentPayload: AttachmentPayload?
    ) {
        if (newText.isBlank() && attachmentPayload == null) {
            isSending = false
            updateSendState()
            return
        }

        chatViewModel.editUserMessageAndPrepareResponse(
            historyIndex = historyIndex,
            content = newText,
            base64Data = attachmentPayload?.base64Data,
            fileUri = attachmentPayload?.fileUri?.takeIf { it.isNotBlank() },
            mimeType = attachmentPayload?.mimeType,
            fileName = attachmentPayload?.fileName,
            fileContext = attachmentPayload?.attachmentContext,
            onError = { error ->
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    toast(error)
                }
            },
            onPrepared = {
                runOnUiThread {
                    if (!isSending) return@runOnUiThread
                    removeViewsFromUserMessage(historyIndex)
                    clearEditingMessageState()
                    binding.etInput.text?.clear()
                    attachmentPreviewController.retainedEditingAttachment = null
                    clearPreview()
                    submitEditedUserMessage(historyIndex, newText, attachmentPayload)
                }
            }
        )
    }

    private fun removeViewsFromUserMessage(historyIndex: Int) {
        var firstViewIndex = -1
        for (i in 0 until binding.messagesContainer.childCount) {
            val child = binding.messagesContainer.getChildAt(i)
            if (child.getTag(R.id.user_message_history_index) == historyIndex) {
                firstViewIndex = i
                break
            }
        }
        if (firstViewIndex < 0) return

        for (i in binding.messagesContainer.childCount - 1 downTo firstViewIndex) {
            binding.messagesContainer.removeViewAt(i)
        }
    }

    private fun renderUserDraftMessage(text: String, attachmentPayload: AttachmentPayload?, historyIndex: Int) {
        if (attachmentPayload == null) {
            messageRenderer.addUserMessage(text, historyIndex)
            return
        }

        val mimeType = attachmentPayload.mimeType
        val base64Data = attachmentPayload.base64Data
        when {
            mimeType.startsWith("image/", ignoreCase = true) && !base64Data.isNullOrBlank() -> {
                messageRenderer.addUserMessageWithImageData(text, base64Data, mimeType, attachmentPayload.fileName, historyIndex)
            }
            mimeType.startsWith("image/", ignoreCase = true) && attachmentPayload.fileUri.isNotBlank() -> {
                messageRenderer.addUserMessageWithImage(text, Uri.parse(attachmentPayload.fileUri), historyIndex)
            }
            !base64Data.isNullOrBlank() -> {
                messageRenderer.addUserMessageWithFileData(text, base64Data, mimeType, attachmentPayload.fileName, historyIndex)
            }
            attachmentPayload.fileUri.isNotBlank() -> {
                messageRenderer.addUserMessageWithFile(text, Uri.parse(attachmentPayload.fileUri), historyIndex)
            }
            else -> {
                messageRenderer.addUserMessage(text, historyIndex)
            }
        }
    }

    private fun submitEditedUserMessage(historyIndex: Int, text: String, attachmentPayload: AttachmentPayload?) {
        isSending = true
        val requestId = nextGenerationId()
        isUserAtBottom = true
        hideQuickSuggestions()
        showMessagesState()
        refreshDailyQuotaUi()

        renderUserDraftMessage(text, attachmentPayload, historyIndex)

        if (historyIndex == 0) {
            val derivedTitle = when {
                text.isNotBlank() -> text.take(60)
                !attachmentPayload?.fileName.isNullOrBlank() -> attachmentPayload?.fileName?.take(60).orEmpty()
                attachmentPayload != null -> LocaleHelper.getString(this, "label_file_analysis")
                else -> LocaleHelper.getString(this, "label_new_chat")
            }
            chatViewModel.isFirstMessage = false
            chatViewModel.currentChatId?.let { chatId ->
                if (chatViewModel.cachedChats.firstOrNull { it.id == chatId }?.isTitleManuallyEdited != true) {
                    chatViewModel.currentChatTitle = derivedTitle
                    chatViewModel.renameChat(
                        chatId = chatId,
                        newTitle = derivedTitle,
                        isTitleManuallyEdited = false
                    ) {
                        refreshChats()
                    }
                }
            }
        }

        val isImageRequest = chatViewModel.currentMode == ChatMode.CREATE_IMAGE
        val wrapper = messageRenderer.addAssistantMessage(
            text = "",
            animate = false,
            isImageMode = isImageRequest
        )
        currentAssistantMessage = wrapper
        scheduleScrollToBottomIfPinned()
        updateSendState()

        retryWithCurrentProvider(
            wrapper = wrapper,
            requestId = requestId,
            modeOverride = if (isImageRequest) ChatMode.CREATE_IMAGE else null,
            useModeOverride = isImageRequest
        )
    }

    private fun retryWithCurrentProvider(
        wrapper: AssistantMessageWrapper,
        requestId: Long,
        modeOverride: String? = null,
        useModeOverride: Boolean = false
    ) {
        var lastAccumulated = ""
        chatViewModel.fetchAiResponse(
            onChunk = { chunk ->
                lastAccumulated = chunk
                runOnUiThread {
                    enqueueStreamingUpdate(requestId, wrapper, chunk)
                }
            },
            onComplete = {
                runOnUiThread {
                    if (!isActiveGeneration(requestId, wrapper)) return@runOnUiThread
                    flushStreamingUpdate(requestId, wrapper, lastAccumulated, isFinal = true)
                    attachCompletedAssistantMessage(wrapper)
                    finishGeneration(requestId)
                    isSending = false
                    updateSendState()
                    hideGeneratingIndicator()
                    refreshDailyQuotaUi()
                    refreshChats()
                }
            },
            onError = { error ->
                runOnUiThread {
                    if (!isActiveGeneration(requestId, wrapper)) return@runOnUiThread
                    flushStreamingUpdate(requestId, wrapper, error, isFinal = true)
                    finishGeneration(requestId)
                    isSending = false
                    updateSendState()
                    hideGeneratingIndicator()
                    refreshDailyQuotaUi()
                    toast(error)
                }
            },
            modeOverride = modeOverride,
            useModeOverride = useModeOverride,
            activityGenerationId = requestId
        )
    }

    private fun regenerateAssistantResponse(wrapper: AssistantMessageWrapper) {
        if (isSending) return

        val wrapperIndex = binding.messagesContainer.indexOfChild(wrapper.container)
        if (wrapperIndex < 0) return

        // Считаем порядковый номер блока ассистента, начиная с нуля.
        // Контейнер ассистента отличается от пользовательских сообщений шириной MATCH_PARENT.
        var assistantOrdinal = 0
        for (i in 0 until binding.messagesContainer.childCount) {
            val child = binding.messagesContainer.getChildAt(i)
            if (child === wrapper.container) break
            val lp = child.layoutParams as? LinearLayout.LayoutParams
            if (lp?.width == LinearLayout.LayoutParams.MATCH_PARENT) {
                assistantOrdinal++
            }
        }

        // В истории сообщения идут парами: пользователь, ассистент, пользователь, ассистент.
        // Блок ассистента с номером K находится по индексу (2 * K + 1).
        // Обрезаем начиная с этой позиции, оставляя пользовательское сообщение перед ним.
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
        val requestId = nextGenerationId()
        isUserAtBottom = isMessagesAtBottom()
        updateSendState()
        val isImageRequest = wrapper.isImageMode || AssistantMessageWrapper.containsImageReply(wrapper.rawText)
        val freshWrapper = messageRenderer.addAssistantMessage(
            text = "",
            animate = false,
            isImageMode = isImageRequest
        )
        currentAssistantMessage = freshWrapper
        scheduleScrollToBottomIfPinned()
        retryWithCurrentProvider(
            wrapper = freshWrapper,
            requestId = requestId,
            modeOverride = if (isImageRequest) ChatMode.CREATE_IMAGE else null,
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
        attachmentPreviewController.clearPreview()
    }

    private fun clearInputContext(
        showWelcomeActions: Boolean = true,
        syncSuggestions: Boolean = true
    ) {
        binding.contextChipContainer.isGone = true
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        chatViewModel.currentMode = null
        isIdeaSuggestionsPinned = false
        setWelcomeActionButtonsVisible(showWelcomeActions)
        if (syncSuggestions) {
            syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
        }
    }

    // ──────── Плавающий индикатор генерации ────────

    /**
     * Настраивает слушатель скролла и обработчик нажатия на индикатор.
     * Когда пользователь прокрутил чат вверх и AI генерирует ответ,
     * по центру экрана появляется пилюля с анимированными точками.
     * Нажатие на неё прокручивает чат вниз к генерируемому тексту.
     */
    private fun setupGeneratingIndicator() {
        binding.generatingIndicator.setOnClickListener {
            isUserAtBottom = true
            cancelPendingAutoScroll()
            scrollToBottom()
            hideGeneratingIndicator()
        }

        scrollToBottomController = ChatScrollToBottomController(
            scrollView = binding.messagesScrollView,
            button = binding.btnScrollToBottom,
            bottomScrollY = ::bottomScrollY,
            isPinnedToBottom = { isUserAtBottom },
            isSuppressed = { isSending },
            onScrollStateChanged = ::handleMessagesScrollChanged,
            onScrollToBottom = {
                isUserAtBottom = true
                cancelPendingAutoScroll()
                scrollToBottom()
                hideGeneratingIndicator()
            }
        ).also { it.attach() }
    }

    private fun handleMessagesScrollChanged() {
        if (isMessagesAtBottom()) {
            isUserAtBottom = true
        } else if (isUserTouchingMessages) {
            isUserAtBottom = false
        }

        updateGeneratingIndicatorVisibility()
    }

    private fun updateGeneratingIndicatorVisibility() {
        if (isSending && !isUserAtBottom) {
            showGeneratingIndicator()
        } else {
            hideGeneratingIndicator()
        }
    }

    private fun showGeneratingIndicator() {
        if (isGeneratingIndicatorVisible) return
        isGeneratingIndicatorVisible = true

        val indicator = binding.generatingIndicator
        indicator.animate().cancel()
        indicator.alpha = 0f
        indicator.scaleX = 0.8f
        indicator.scaleY = 0.8f
        indicator.translationY = dp(12f)
        indicator.isVisible = true

        indicator.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()

        binding.typingDotsView.startAnimation()
    }

    private fun hideGeneratingIndicator() {
        if (!isGeneratingIndicatorVisible) return
        isGeneratingIndicatorVisible = false

        val indicator = binding.generatingIndicator
        indicator.animate().cancel()

        indicator.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .translationY(dp(8f))
            .setDuration(160L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                indicator.isGone = true
                indicator.scaleX = 1f
                indicator.scaleY = 1f
                indicator.translationY = 0f
                indicator.alpha = 1f
                binding.typingDotsView.stopAnimation()
            }
            .start()
    }

    private fun scrollToBottom() {
        binding.messagesScrollView.post {
            binding.messagesScrollView.smoothScrollTo(0, bottomScrollY())
        }
    }

    /** Пересчитывает позицию скролла после того как пользователь убрал палец */
    private fun recheckScrollPosition() {
        isUserAtBottom = isMessagesAtBottom()
        updateGeneratingIndicatorVisibility()
        scrollToBottomController?.refresh()
    }

    private fun setWelcomeActionButtonsVisible(isVisible: Boolean) {
        binding.btnCreateImage.isVisible = isVisible
        binding.btnIdea.isVisible = isVisible
        binding.btnCenterMore.isVisible = isVisible
    }

    private fun updateSendState() {
        val hasInput = binding.etInput.text?.isNotBlank() == true ||
            attachmentPreviewController.hasAttachment
        binding.btnSend.isEnabled = isSending || hasInput
        scrollToBottomController?.refreshVisibility()

        if (isSending) {
            binding.btnSend.setBackgroundResource(R.drawable.circle_solid_white_bg)
            binding.btnSend.setImageResource(R.drawable.ic_stop_square)
            binding.btnSend.setColorFilter(android.graphics.Color.BLACK)
        } else if (binding.btnSend.isEnabled) {
            binding.btnSend.setBackgroundResource(R.drawable.circle_solid_white_bg)
            binding.btnSend.setImageResource(R.drawable.ic_send_arrow_up)
            binding.btnSend.setColorFilter(android.graphics.Color.BLACK)
        } else {
            binding.btnSend.setBackgroundResource(R.drawable.circle_solid_grey_bg)
            binding.btnSend.setImageResource(R.drawable.ic_send_arrow_up)
            binding.btnSend.setColorFilter(android.graphics.Color.parseColor("#8E8E93"))
        }
    }


    private fun refreshDailyQuotaUi() {
        chatViewModel.refreshDailyQuota { snapshot ->
            runOnUiThread {
                freeChatAttentionDrawable?.useLowQuotaPalette =
                    !snapshot.isUnlimited && (snapshot.totalRemaining ?: Int.MAX_VALUE) < 5
                val label = if (snapshot.isUnlimited) {
                    SpannableString("∞")
                } else {
                    val base = snapshot.baseRemaining ?: 0
                    val bonus = snapshot.bonusRequests
                    val textPrefix = LocaleHelper.getString(this, "label_limits_requests")
                    val fullText = "$textPrefix: $base + $bonus"
                    SpannableString(fullText).apply {
                        val plusIndex = fullText.indexOf("+")
                        if (plusIndex != -1) {
                            setSpan(
                                ForegroundColorSpan(Color.parseColor("#d1a3ff")),
                                plusIndex,
                                fullText.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                binding.tvLimitsCount.text = label
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
