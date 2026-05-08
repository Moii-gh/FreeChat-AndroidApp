package com.example.chatapp

import android.animation.AnimatorSet
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.assistant.DigitalAssistantHandoffStore
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.databinding.ActivityMainBinding
import com.example.chatapp.speech.SpeechRecognizerManager
import com.example.chatapp.ui.AssistantMessageWrapper
import com.example.chatapp.ui.ChatMessageRenderer
import com.example.chatapp.ui.ChatUiAnimationHelper
import com.example.chatapp.ui.DrawerManager
import com.example.chatapp.ui.FreeChatAttentionDrawable
import com.example.chatapp.ui.LaunchLogoAnimator
import com.example.chatapp.ui.PopupMenuHelper
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.SafeLog
import com.example.chatapp.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.example.chatapp.util.setHapticClickListener
import com.example.chatapp.util.OnSwipeTouchListener
import com.example.chatapp.ads.RewardedAdManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

import com.example.chatapp.viewmodel.AccountSecuritySettingsStore
import com.example.chatapp.ui.TypingDotsView

class FreeChatActivity : AppCompatActivity(), ChatInputHost {

    private enum class QuickSuggestionCategory {
        IMAGE,
        IDEA
    }

    companion object {
        const val EXTRA_PREFILL_INPUT = "com.example.chatapp.EXTRA_PREFILL_INPUT"
        const val EXTRA_SKIP_BIOMETRIC_ONCE = "com.example.chatapp.EXTRA_SKIP_BIOMETRIC_ONCE"

        const val SUGGESTIONS_SHOW_DURATION_MS = 180L
        const val SUGGESTIONS_HIDE_DURATION_MS = 120L
        const val TOP_ACTIONS_SPLIT_DURATION_MS = 260L
        const val WELCOME_PROMPT_ROTATION_MS = 5_000L
        const val WELCOME_PROMPT_ANIMATION_MS = 260L
        const val WELCOME_PROMPT_TYPE_STEP_MS = 26L
        const val WELCOME_PROMPT_CURSOR_BLINK_MS = 460L
        const val WELCOME_PROMPT_CURSOR = "|"
        const val FREE_CHAT_ATTENTION_IDLE_DELAY_MS = 30_000L
        const val STREAM_UI_FLUSH_INTERVAL_MS = 50L
    }

    private lateinit var binding: ActivityMainBinding
    private val chatViewModel: ChatViewModel by viewModels()

    private lateinit var drawerManager: DrawerManager
    private lateinit var popupMenuHelper: PopupMenuHelper
    private lateinit var messageRenderer: ChatMessageRenderer
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    private val attachmentHelper by lazy { ChatAttachmentHelper(this) }

    private var currentPreviewUri: Uri? = null
    private var retainedEditingAttachment: AttachmentPayload? = null
    private var assistantHandoffAttachmentPath: String? = null
    private var currentAssistantMessage: AssistantMessageWrapper? = null
    private var isSending = false
    private var activeSuggestionCategory: QuickSuggestionCategory? = null
    private var suppressSuggestionUpdates = false
    private var handledShareToken: String? = null
    private var adManager: RewardedAdManager? = null
    private var topActionsAnimator: AnimatorSet? = null
    private val welcomePromptHandler = Handler(Looper.getMainLooper())
    private var welcomePromptAnimator: AnimatorSet? = null
    private var welcomePrompts: List<String> = emptyList()
    private var welcomePromptIndex = 0
    private var welcomePromptText = ""
    private var welcomePromptVisibleChars = 0
    private var welcomePromptCursorVisible = true
    private var isWelcomePromptCycleRunning = false
    private var editingMessageHistoryIndex: Int? = null
    private val freeChatAttentionHandler = Handler(Looper.getMainLooper())
    private var freeChatAttentionDrawable: FreeChatAttentionDrawable? = null
    private var isFreeChatButtonInteracting = false
    private lateinit var securitySettingsStore: AccountSecuritySettingsStore
    private var biometricGateDialog: AlertDialog? = null
    private var isBiometricGateActive = false
    private var isChatUiInitialized = false
    private var navigationBarInsetBottom = 0
    private var systemWindowInsetTop = 0
    private var messagesBottomAnchorId = View.NO_ID
    private var isUserAtBottom = true
    private var isUserTouchingMessages = false
    private var isGeneratingIndicatorVisible = false
    private val streamUiHandler = Handler(Looper.getMainLooper())
    private var activeGenerationId = 0L
    private var streamPendingRequestId = 0L
    private var streamPendingWrapper: AssistantMessageWrapper? = null
    private var streamPendingText: String? = null
    private var streamLastRenderedText: String? = null
    private var streamFlushRunnable: Runnable? = null
    private var pendingAutoScrollRunnable: Runnable? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    private val welcomePromptRotationRunnable = object : Runnable {
        override fun run() {
            if (!isWelcomePromptCycleRunning || welcomePrompts.isEmpty()) return
            showNextWelcomePrompt()
            welcomePromptHandler.postDelayed(this, WELCOME_PROMPT_ROTATION_MS)
        }
    }

    private val welcomePromptTypingRunnable = object : Runnable {
        override fun run() {
            if (!isWelcomePromptCycleRunning) return
            if (welcomePromptVisibleChars < welcomePromptText.length) {
                welcomePromptVisibleChars += 1
                renderWelcomePrompt()
                welcomePromptHandler.postDelayed(this, WELCOME_PROMPT_TYPE_STEP_MS)
            } else {
                renderWelcomePrompt(showCursor = false)
            }
        }
    }

    private val freeChatAttentionRunnable = object : Runnable {
        override fun run() {
            if (!isFreeChatButtonInteracting && binding.btnAddLimits.isShown) {
                freeChatAttentionDrawable?.play(binding.btnAddLimits)
            }
        }
    }

    private val welcomePromptCursorRunnable = object : Runnable {
        override fun run() {
            if (!isWelcomePromptCycleRunning) return
            welcomePromptCursorVisible = !welcomePromptCursorVisible
            if (welcomePromptVisibleChars < welcomePromptText.length) {
                renderWelcomePrompt()
            }
            welcomePromptHandler.postDelayed(this, WELCOME_PROMPT_CURSOR_BLINK_MS)
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
        securitySettingsStore = AccountSecuritySettingsStore(applicationContext)
        val shouldSkipBiometricOnce = intent?.getBooleanExtra(EXTRA_SKIP_BIOMETRIC_ONCE, false) == true
        val shouldGateWithBiometrics = securitySettingsStore.isBiometricEnabled() && !shouldSkipBiometricOnce
        if (shouldGateWithBiometrics) {
            isBiometricGateActive = true
            binding.root.alpha = 0f
        }
        val shouldPlayLaunchAnimation = LaunchLogoAnimator.shouldPlayOnActivityCreate(savedInstanceState)
        if (shouldPlayLaunchAnimation) {
            LaunchLogoAnimator.show(this) {
                if (shouldGateWithBiometrics) {
                    startBiometricUnlockGate()
                }
            }
        } else if (shouldGateWithBiometrics) {
            startBiometricUnlockGate()
        }
        if (!shouldGateWithBiometrics) {
            initializeChatUi(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isChatUiInitialized) {
            return
        }
        handleSharedChatIntent(intent)
        handleAssistantHandoffIntent(intent)
        handlePrefillInputIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!isChatUiInitialized) return
        drawerManager.updateUserProfile()
        refreshDailyQuotaUi()
        applyTranslations()
        scheduleFreeChatAttentionAfterIdle()
    }

    override fun onPause() {
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
        freeChatAttentionDrawable?.cancelAttention()
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!isChatUiInitialized) return
        freeChatAttentionDrawable?.cancelAttention()
        scheduleFreeChatAttentionAfterIdle()
    }

    private fun initializeChatUi(startIntent: Intent?) {
        if (isChatUiInitialized) return
        isChatUiInitialized = true

        setupHelpers()
        setupTopBar()
        setupDrawer()
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
        handleAssistantHandoffIntent(startIntent)
        handlePrefillInputIntent(startIntent)
        applyTranslations()
        scheduleFreeChatAttentionAfterIdle()
    }

    private fun applyTranslations() {
        refreshWelcomePrompts()
        if (binding.welcomeScreen.isVisible) {
            startWelcomePromptCycle(resetIndex = false)
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
        when (chatViewModel.currentMode) {
            "create_image" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_create_image")
            "search" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_panel_search")
            "shopping" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_purchase_research")
            "study" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_study_training")
            else -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        }
    }

    override fun onDestroy() {
        invalidateActiveGeneration(cancelScroll = true)
        stopWelcomePromptCycle()
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
        freeChatAttentionDrawable?.cancelAttention()
        speechRecognizerManager.destroy()
        adManager?.destroy()
        super.onDestroy()
    }

    private fun refreshWelcomePrompts() {
        val prompts = LocaleHelper.getStringList(this, "welcome_prompt")
            .ifEmpty { listOf(LocaleHelper.getString(this, "welcome_question")) }
        welcomePrompts = prompts
        if (welcomePromptIndex >= welcomePrompts.size) {
            welcomePromptIndex = 0
        }
    }

    private fun startWelcomePromptCycle(resetIndex: Boolean) {
        refreshWelcomePrompts()
        if (welcomePrompts.isEmpty()) return

        if (resetIndex) {
            welcomePromptIndex = 0
        }

        isWelcomePromptCycleRunning = true
        welcomePromptHandler.removeCallbacks(welcomePromptRotationRunnable)
        welcomePromptHandler.removeCallbacks(welcomePromptTypingRunnable)
        welcomePromptHandler.removeCallbacks(welcomePromptCursorRunnable)
        welcomePromptAnimator?.cancel()

        binding.tvWelcomeTitle.alpha = 1f
        binding.tvWelcomeTitle.translationY = 0f
        typeWelcomePrompt(welcomePrompts[welcomePromptIndex])
        welcomePromptHandler.postDelayed(welcomePromptRotationRunnable, WELCOME_PROMPT_ROTATION_MS)
        welcomePromptHandler.postDelayed(welcomePromptCursorRunnable, WELCOME_PROMPT_CURSOR_BLINK_MS)
    }

    private fun stopWelcomePromptCycle() {
        isWelcomePromptCycleRunning = false
        welcomePromptAnimator?.cancel()
        welcomePromptAnimator = null
        welcomePromptHandler.removeCallbacks(welcomePromptRotationRunnable)
        welcomePromptHandler.removeCallbacks(welcomePromptTypingRunnable)
        welcomePromptHandler.removeCallbacks(welcomePromptCursorRunnable)
    }

    private fun showNextWelcomePrompt() {
        if (welcomePrompts.isEmpty()) return
        welcomePromptIndex = (welcomePromptIndex + 1) % welcomePrompts.size
        val nextPrompt = welcomePrompts[welcomePromptIndex]

        welcomePromptAnimator?.cancel()
        welcomePromptHandler.removeCallbacks(welcomePromptTypingRunnable)

        val fadeOut = ObjectAnimator.ofFloat(binding.tvWelcomeTitle, View.ALPHA, 1f, 0f)
        val slideOut = ObjectAnimator.ofFloat(binding.tvWelcomeTitle, View.TRANSLATION_Y, 0f, -8.dpToPx())
        val fadeIn = ObjectAnimator.ofFloat(binding.tvWelcomeTitle, View.ALPHA, 0f, 1f)
        val slideIn = ObjectAnimator.ofFloat(binding.tvWelcomeTitle, View.TRANSLATION_Y, 10.dpToPx(), 0f)

        welcomePromptAnimator = AnimatorSet().apply {
            playTogether(fadeOut, slideOut)
            duration = WELCOME_PROMPT_ANIMATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isWelcomePromptCycleRunning) return
                    typeWelcomePrompt(nextPrompt)
                    AnimatorSet().apply {
                        playTogether(fadeIn, slideIn)
                        duration = WELCOME_PROMPT_ANIMATION_MS
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun typeWelcomePrompt(prompt: String) {
        welcomePromptText = prompt
        welcomePromptVisibleChars = 0
        welcomePromptCursorVisible = true
        renderWelcomePrompt()
        welcomePromptHandler.postDelayed(welcomePromptTypingRunnable, WELCOME_PROMPT_TYPE_STEP_MS)
    }

    private fun renderWelcomePrompt(showCursor: Boolean = true) {
        val visibleText = welcomePromptText.take(welcomePromptVisibleChars)
        val cursor = if (showCursor && welcomePromptCursorVisible) WELCOME_PROMPT_CURSOR else ""
        binding.tvWelcomeTitle.text = visibleText + cursor
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

        if (mode == "search") {
            loadPopularNewsQueries()
        } else {
            syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
        }
    }

    override fun showFilePreview(fileUri: Uri) {
        retainedEditingAttachment = null
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
        setInputContext(
            title = LocaleHelper.getString(this, "action_create_image"),
            iconRes = R.drawable.ic_palette,
            hint = LocaleHelper.getString(this, "hint_create_image"),
            mode = "create_image"
        )
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
            assistantHandoffAttachmentPath = handoff.attachmentPath
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            showFilePreview(uri)
        }
    }

    private fun syncQuickSuggestions(query: String) {
        if (suppressSuggestionUpdates || currentPreviewUri != null || isSending || hasCompletedChatExchange()) {
            hideQuickSuggestions()
            return
        }

        if (chatViewModel.currentMode == "search") {
            if (query.isBlank()) {
                showPopularNewsQueries(chatViewModel.popularNewsQueries)
            } else {
                hideQuickSuggestions()
            }
            return
        }

        if (chatViewModel.currentMode == "create_image") {
            if (query.isBlank()) {
                showQuickSuggestions(QuickSuggestionCategory.IMAGE)
            } else {
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
                if (chatViewModel.currentMode == "search" && binding.etInput.text.isNullOrBlank()) {
                    showPopularNewsQueries(queries)
                }
            }
        }
    }

    private fun showPopularNewsQueries(queries: List<String>) {
        val visibleQueries = queries.filter { it.isNotBlank() }.take(4)
        if (visibleQueries.isEmpty() || chatViewModel.currentMode != "search" || currentPreviewUri != null || isSending) {
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
        activeGenerationId += 1
        cancelPendingStreamUiWork(cancelScroll = true)
        streamLastRenderedText = null
        return activeGenerationId
    }

    private fun finishGeneration(requestId: Long) {
        if (requestId == activeGenerationId) {
            activeGenerationId += 1
        }
        cancelScheduledStreamFlush()
        streamPendingRequestId = 0L
        streamPendingWrapper = null
        streamPendingText = null
        streamLastRenderedText = null
    }

    private fun invalidateActiveGeneration(cancelScroll: Boolean = true) {
        activeGenerationId += 1
        cancelPendingStreamUiWork(cancelScroll = cancelScroll)
        streamLastRenderedText = null
    }

    private fun isActiveGeneration(requestId: Long, wrapper: AssistantMessageWrapper): Boolean {
        return requestId == activeGenerationId && currentAssistantMessage === wrapper
    }

    private fun enqueueStreamingUpdate(
        requestId: Long,
        wrapper: AssistantMessageWrapper,
        text: String
    ) {
        if (!isActiveGeneration(requestId, wrapper)) return
        if (text == streamPendingText || text == streamLastRenderedText) return

        streamPendingRequestId = requestId
        streamPendingWrapper = wrapper
        streamPendingText = text

        if (streamFlushRunnable != null) return
        val runnable = Runnable {
            streamFlushRunnable = null
            val pendingRequestId = streamPendingRequestId
            val pendingWrapper = streamPendingWrapper ?: return@Runnable
            val pendingText = streamPendingText ?: return@Runnable
            applyStreamingUpdate(pendingRequestId, pendingWrapper, pendingText, isFinal = false)
        }
        streamFlushRunnable = runnable
        streamUiHandler.postDelayed(runnable, STREAM_UI_FLUSH_INTERVAL_MS)
    }

    private fun flushStreamingUpdate(
        requestId: Long,
        wrapper: AssistantMessageWrapper,
        text: String,
        isFinal: Boolean
    ) {
        cancelScheduledStreamFlush()
        streamPendingRequestId = requestId
        streamPendingWrapper = wrapper
        streamPendingText = text
        applyStreamingUpdate(requestId, wrapper, text, isFinal)
    }

    private fun applyStreamingUpdate(
        requestId: Long,
        wrapper: AssistantMessageWrapper,
        text: String,
        isFinal: Boolean
    ) {
        if (!isActiveGeneration(requestId, wrapper)) return
        if (!isFinal && text == streamLastRenderedText) return

        streamPendingRequestId = 0L
        streamPendingWrapper = null
        streamPendingText = null
        streamLastRenderedText = text

        wrapper.updateContent(text, animate = false, isFinal = isFinal)
        scheduleScrollToBottomIfPinned()
        updateGeneratingIndicatorVisibility()
    }

    private fun cancelPendingStreamUiWork(cancelScroll: Boolean) {
        cancelScheduledStreamFlush()
        streamPendingRequestId = 0L
        streamPendingWrapper = null
        streamPendingText = null
        if (cancelScroll) {
            cancelPendingAutoScroll()
        }
    }

    private fun cancelScheduledStreamFlush() {
        streamFlushRunnable?.let { streamUiHandler.removeCallbacks(it) }
        streamFlushRunnable = null
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
            onAssistantContentChanged = ::scheduleScrollToBottomIfPinned
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
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
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
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            navigationBarInsetBottom = insets.systemWindowInsetBottom
            systemWindowInsetTop = insets.systemWindowInsetTop
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
        binding.bottomInputScrim.alpha = 0.6f
        
        binding.etInput.setOnFocusChangeListener { _, hasFocus ->
            val targetTranslation = if (hasFocus) -dp(6f) else 0f
            
            binding.bottomInputArea.animate()
                .translationY(targetTranslation)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                .start()
                
            binding.bottomInputScrim.animate()
                .alpha(if (hasFocus) 1.0f else 0.6f)
                .setDuration(300)
                .start()
        }

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

        val scrimParams = binding.bottomInputScrim.layoutParams
        val targetScrimHeight = navigationBarInsetBottom + dp(180f).toInt()
        if (scrimParams.height != targetScrimHeight) {
            scrimParams.height = targetScrimHeight
            binding.bottomInputScrim.layoutParams = scrimParams
        }
    }

    private fun updateTopInputSystemInset() {
        val targetTopMargin = systemWindowInsetTop
        val topBarParams = binding.topBar.layoutParams as ViewGroup.MarginLayoutParams
        if (topBarParams.topMargin != targetTopMargin) {
            topBarParams.topMargin = targetTopMargin
            binding.topBar.layoutParams = topBarParams
        }

        val topScrim = binding.root.findViewById<View>(R.id.topInputScrim)
        if (topScrim != null) {
            val topScrimParams = topScrim.layoutParams
            val targetTopScrimHeight = systemWindowInsetTop + dp(100f).toInt()
            if (topScrimParams.height != targetTopScrimHeight) {
                topScrimParams.height = targetTopScrimHeight
                topScrim.layoutParams = topScrimParams
            }
        }
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
        startWelcomePromptCycle(resetIndex = true)
        setWelcomeActionButtonsVisible(chatViewModel.currentMode == null)
        binding.anonymousWelcomeScreen.isGone = true
        binding.messagesScrollView.isGone = true
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true

        updateTopBarIcons()

        binding.tvBtnCreateImage.text = LocaleHelper.getString(this, "button_create_image")
        binding.tvBtnIdea.text = LocaleHelper.getString(this, "button_create_idea")
        binding.tvBtnMore.text = LocaleHelper.getString(this, "button_more")
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
    }

    private fun showAnonymousWelcomeState() {
        stopWelcomePromptCycle()
        resetTopActionsAnimation()
        binding.welcomeScreen.isGone = true
        binding.anonymousWelcomeScreen.isVisible = true
        binding.messagesScrollView.isGone = true
        binding.topRightMain.isVisible = true
        binding.topRightChat.isGone = true

        updateTopBarIcons()

        binding.tvAnonymousDesc.text = LocaleHelper.getString(this, "anonymous_mode_desc")
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
    }

    private fun showMessagesState(animateTopActions: Boolean = false) {
        stopWelcomePromptCycle()
        binding.welcomeScreen.isGone = true
        binding.anonymousWelcomeScreen.isGone = true
        binding.messagesScrollView.isVisible = true
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
                    messageRenderer.addAssistantMessage(
                        text = assistantContent,
                        animate = false,
                        isImageMode = AssistantMessageWrapper.containsImageReply(assistantContent)
                    )
                }
            }

            showMessagesState()
            hideQuickSuggestions()
            isUserAtBottom = true
            scheduleScrollToBottomIfPinned()
            refreshDrawerSelection()
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

        val previewUri = currentPreviewUri
        if (text.isBlank() && previewUri == null) return

        val attachmentPayload = try {
            attachmentHelper.buildAttachmentPayload(previewUri)
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: LocaleHelper.getString(this, "attachment_read_error"))
            return
        }

        if (!chatViewModel.consumeLimit()) {
            refreshDailyQuotaUi()
            toast(LocaleHelper.getString(this, "toast_limits_exhausted"))
            return
        }

        val mimeType = attachmentPayload?.mimeType
        val userHistoryIndex = chatViewModel.chatHistory.size

        val shouldAnimateTopActions = chatViewModel.isFirstMessage && binding.topRightMain.isVisible
        isSending = true
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

        val isImageRequest = chatViewModel.currentMode == "create_image"
        val wrapper = messageRenderer.addAssistantMessage(
            text = if (isImageRequest) "" else LocaleHelper.getString(this, "ai_thinking"),
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

        val requestId = activeGenerationId
        val wrapper = currentAssistantMessage
        chatViewModel.cancelActiveResponse()
        cancelScheduledStreamFlush()
        if (wrapper != null && isActiveGeneration(requestId, wrapper)) {
            val latestText = streamPendingText ?: wrapper.rawText
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
        retainedEditingAttachment = MessageInputHelper.attachmentPayloadFromHistory(historyMessage)
        showRetainedAttachmentPreview(retainedEditingAttachment)
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
        retainedEditingAttachment = null
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
        if (payload == null) return

        binding.previewContainer.isVisible = true
        if (payload.mimeType.startsWith("image/", ignoreCase = true)) {
            binding.previewImage.isVisible = true
            binding.previewFileContainer.isGone = true
            val imageSet = payload.base64Data?.let { base64 ->
                runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    binding.previewImage.setImageBitmap(bitmap)
                }.isSuccess
            } == true

            if (!imageSet && payload.fileUri.isNotBlank()) {
                runCatching { binding.previewImage.setImageURI(Uri.parse(payload.fileUri)) }
            }
        } else {
            binding.previewImage.isGone = true
            binding.previewImage.setImageDrawable(null)
            binding.previewFileContainer.isVisible = true
            binding.previewFileName.text = payload.fileName?.takeIf { it.isNotBlank() }
                ?: LocaleHelper.getString(this, "label_file_analysis")
        }
        updateSendState()
    }

    private fun editUserMessage(historyIndex: Int, newText: String) {
        if (isSending) return
        if (historyIndex !in 0 until chatViewModel.chatHistory.size) return

        val attachmentPayload = try {
            currentPreviewUri?.let { attachmentHelper.buildAttachmentPayload(it) } ?: retainedEditingAttachment
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: LocaleHelper.getString(this, "attachment_read_error"))
            return
        }

        if (newText.isBlank() && attachmentPayload == null) return

        if (!chatViewModel.consumeLimit()) {
            refreshDailyQuotaUi()
            toast(LocaleHelper.getString(this, "toast_limits_exhausted"))
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
                    toast(error)
                }
            },
            onPrepared = {
                runOnUiThread {
                    removeViewsFromUserMessage(historyIndex)
                    clearEditingMessageState()
                    binding.etInput.text?.clear()
                    retainedEditingAttachment = null
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
            chatViewModel.currentChatTitle = when {
                text.isNotBlank() -> text.take(60)
                !attachmentPayload?.fileName.isNullOrBlank() -> attachmentPayload?.fileName?.take(60).orEmpty()
                attachmentPayload != null -> LocaleHelper.getString(this, "label_file_analysis")
                else -> LocaleHelper.getString(this, "label_new_chat")
            }
            chatViewModel.isFirstMessage = false
            chatViewModel.currentChatId?.let { chatId ->
                chatViewModel.renameChat(chatId, chatViewModel.currentChatTitle) {
                    refreshChats()
                }
            }
        }

        val isImageRequest = chatViewModel.currentMode == "create_image"
        val wrapper = messageRenderer.addAssistantMessage(
            text = if (isImageRequest) "" else LocaleHelper.getString(this, "ai_thinking"),
            animate = false,
            isImageMode = isImageRequest
        )
        currentAssistantMessage = wrapper
        scheduleScrollToBottomIfPinned()
        updateSendState()

        retryWithCurrentProvider(
            wrapper = wrapper,
            requestId = requestId,
            modeOverride = if (isImageRequest) "create_image" else null,
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
            useModeOverride = useModeOverride
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
            text = if (isImageRequest) "" else LocaleHelper.getString(this, "ai_thinking"),
            animate = false,
            isImageMode = isImageRequest
        )
        currentAssistantMessage = freshWrapper
        scheduleScrollToBottomIfPinned()
        retryWithCurrentProvider(
            wrapper = freshWrapper,
            requestId = requestId,
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
        retainedEditingAttachment = null
        chatViewModel.selectedFileUri = null
        binding.previewContainer.isGone = true
        binding.previewImage.setImageDrawable(null)
        binding.previewImage.isGone = true
        binding.previewFileContainer.isGone = true
        assistantHandoffAttachmentPath?.let { path ->
            runCatching { java.io.File(path).delete() }
        }
        assistantHandoffAttachmentPath = null
        updateSendState()
        syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
    }

    private fun clearInputContext() {
        binding.contextChipContainer.isGone = true
        binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        chatViewModel.currentMode = null
        setWelcomeActionButtonsVisible(true)
        syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
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

        binding.messagesScrollView.viewTreeObserver.addOnScrollChangedListener {

            if (isMessagesAtBottom()) {
                isUserAtBottom = true
            } else if (isUserTouchingMessages) {
                isUserAtBottom = false
            }

            updateGeneratingIndicatorVisibility()
        }
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
    }

    private fun setWelcomeActionButtonsVisible(isVisible: Boolean) {
        binding.btnCreateImage.isVisible = isVisible
        binding.btnIdea.isVisible = isVisible
        binding.btnCenterMore.isVisible = isVisible
    }

    private fun updateSendState() {
        val hasInput = binding.etInput.text?.isNotBlank() == true ||
            currentPreviewUri != null ||
            retainedEditingAttachment != null
        binding.btnSend.isEnabled = isSending || hasInput

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

    // Биометрический gate завязан на Activity lifecycle и системные диалоги, поэтому пока оставлен рядом с экраном.
    private fun startBiometricUnlockGate() {
        if (!isBiometricGateActive || isFinishing || isDestroyed) return
        when (biometricAvailability()) {
            BiometricManager.BIOMETRIC_SUCCESS -> showBiometricUnlockPrompt()
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                showBiometricGateDialog("security_biometric_not_enrolled")
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                showBiometricGateDialog("security_biometric_no_hardware")
            }
            else -> {
                showBiometricGateDialog("security_biometric_unavailable")
            }
        }
    }

    private fun showBiometricUnlockPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(LocaleHelper.getString(this, "security_biometric_unlock_title"))
            .setSubtitle(LocaleHelper.getString(this, "security_biometric_unlock_subtitle"))
            .setNegativeButtonText(LocaleHelper.getString(this, "button_cancel"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    unlockAfterBiometric()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (!isBiometricGateActive) return
                    val messageKey = when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> "security_biometric_required_message"
                        else -> "security_biometric_auth_failed"
                    }
                    showBiometricGateDialog(messageKey)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    toast(LocaleHelper.getString(this@FreeChatActivity, "security_biometric_auth_failed"))
                }
            }
        ).authenticate(promptInfo)
    }

    private fun unlockAfterBiometric() {
        isBiometricGateActive = false
        biometricGateDialog?.dismiss()
        biometricGateDialog = null
        initializeChatUi(intent)
        binding.root.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
    }

    private fun showBiometricGateDialog(messageKey: String) {
        if (!isBiometricGateActive || isFinishing || isDestroyed) return
        biometricGateDialog?.dismiss()
        biometricGateDialog = AlertDialog.Builder(this)
            .setTitle(LocaleHelper.getString(this, "security_biometric_required_title"))
            .setMessage(LocaleHelper.getString(this, messageKey))
            .setPositiveButton(LocaleHelper.getString(this, "security_biometric_retry")) { _, _ ->
                startBiometricUnlockGate()
            }
            .setNegativeButton(LocaleHelper.getString(this, "security_biometric_use_login")) { _, _ ->
                SharedPrefsAccountSessionStore(applicationContext).clearSession()
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_SKIP_BIOMETRIC_ONCE_AFTER_LOGIN, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun biometricAvailability(): Int {
        return BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
