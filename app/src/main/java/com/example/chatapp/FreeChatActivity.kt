package com.example.chatapp

import android.animation.AnimatorSet
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import com.example.chatapp.ui.FreeChatAttentionDrawable
import com.example.chatapp.ui.PopupMenuHelper
import com.example.chatapp.util.FileUtils
import com.example.chatapp.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.example.chatapp.util.setHapticClickListener
import com.example.chatapp.util.OnSwipeTouchListener
import com.example.chatapp.ads.RewardedAdManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

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
        val attachmentContext: String?
    )

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
        const val MAX_EXTRACTED_TEXT_CHARS = 120_000
        const val MAX_ATTACHMENT_CONTEXT_CHARS = 4_000
        const val SUGGESTIONS_SHOW_DURATION_MS = 180L
        const val SUGGESTIONS_HIDE_DURATION_MS = 120L
        const val TOP_ACTIONS_SPLIT_DURATION_MS = 260L
        const val WELCOME_PROMPT_ROTATION_MS = 5_000L
        const val WELCOME_PROMPT_ANIMATION_MS = 260L
        const val WELCOME_PROMPT_TYPE_STEP_MS = 26L
        const val WELCOME_PROMPT_CURSOR_BLINK_MS = 460L
        const val WELCOME_PROMPT_CURSOR = "|"
        const val FREE_CHAT_ATTENTION_INTERVAL_MS = 60_000L
        const val FREE_CHAT_ATTENTION_RESUME_DELAY_MS = 2_500L
    }

    private lateinit var binding: ActivityMainBinding
    private val chatViewModel: ChatViewModel by viewModels()

    private lateinit var drawerManager: DrawerManager
    private lateinit var popupMenuHelper: PopupMenuHelper
    private lateinit var messageRenderer: ChatMessageRenderer
    private lateinit var speechRecognizerManager: SpeechRecognizerManager

    private var currentPreviewUri: Uri? = null
    private var retainedEditingAttachment: AttachmentPayload? = null
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
            freeChatAttentionHandler.postDelayed(this, FREE_CHAT_ATTENTION_INTERVAL_MS)
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
        binding.messagesScrollView.setOnTouchListener(swipeListener)
        binding.messagesContainer.setOnTouchListener(swipeListener)
        binding.welcomeScreen.setOnTouchListener(swipeListener)

        showWelcomeState()
        loadChats()
        handleSharedChatIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        drawerManager.updateUserProfile()
        refreshDailyQuotaUi()
        applyTranslations()
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

        // Drawer texts
        findViewById<android.widget.TextView>(R.id.tvDrawerTitle)?.text = LocaleHelper.getString(this, "app_brand")
        findViewById<android.widget.TextView>(R.id.tvDrawerNewChat)?.text = LocaleHelper.getString(this, "button_new_chat")
        findViewById<android.widget.EditText>(R.id.etDrawerSearch)?.hint = LocaleHelper.getString(this, "panel_search")
        drawerManager.populateChats(chatViewModel.cachedChats) // Automatically re-renders chat titles correctly

        // Also restore the correct hint based on the current mode
        when (chatViewModel.currentMode) {
            "create_image" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_create_image")
            "search" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_panel_search")
            "shopping" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input_purchase_research")
            "study" -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_study_training")
            else -> binding.etInput.hint = LocaleHelper.getString(this, "main_panel_input")
        }
    }

    override fun onDestroy() {
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

    private fun syncQuickSuggestions(query: String) {
        if (suppressSuggestionUpdates || currentPreviewUri != null || isSending) {
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
            return
        }

        val slideDistance = 8f * resources.displayMetrics.density
        container.animate()
            .translationY(slideDistance)
            .alpha(0f)
            .setDuration(SUGGESTIONS_HIDE_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                container.isGone = true
                container.translationY = 0f
                container.alpha = 1f
                binding.suggestionsList.removeAllViews()
            }
            .start()
    }

    private fun showSuggestionsContainerAnimated() {
        val container = binding.suggestionsContainer
        container.animate().cancel()

        if (!container.isVisible) {
            val slideDistance = 12f * resources.displayMetrics.density
            container.translationY = slideDistance
            container.alpha = 0f
            container.isVisible = true
        }

        container.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(SUGGESTIONS_SHOW_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
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
            messagesScrollView = binding.messagesScrollView,
            popupMenuHelper = popupMenuHelper,
            onRegenerate = ::regenerateAssistantResponse,
            onUserMessageLongClick = { anchor, message, historyIndex ->
                popupMenuHelper.showUserMessageOptionsMenu(anchor, message, historyIndex)
            }
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
        startFreeChatAttentionLoop()
        setupMainButtonPressAnimations()
    }

    private fun startFreeChatAttentionLoop() {
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
        freeChatAttentionHandler.postDelayed(freeChatAttentionRunnable, FREE_CHAT_ATTENTION_INTERVAL_MS)
    }

    private fun pauseFreeChatAttention() {
        isFreeChatButtonInteracting = true
        freeChatAttentionDrawable?.isInteracting = true
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
    }

    private fun resumeFreeChatAttentionAfterInteraction() {
        isFreeChatButtonInteracting = false
        freeChatAttentionDrawable?.isInteracting = false
        freeChatAttentionHandler.removeCallbacks(freeChatAttentionRunnable)
        freeChatAttentionHandler.postDelayed(
            freeChatAttentionRunnable,
            FREE_CHAT_ATTENTION_INTERVAL_MS + FREE_CHAT_ATTENTION_RESUME_DELAY_MS
        )
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
        touchSource.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (touchSource === binding.btnAddLimits) {
                        pauseFreeChatAttention()
                    }
                    target.animate().cancel()
                    target.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .translationY(pressedOffset)
                        .translationZ(pressedTranslationZ)
                        .setDuration(80L)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (touchSource === binding.btnAddLimits) {
                        resumeFreeChatAttentionAfterInteraction()
                    }
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .translationZ(0f)
                        .setDuration(160L)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                }
            }
            false
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
        binding.messagesScrollView.setOnApplyWindowInsetsListener { _, insets ->
            updateFloatingInputPadding()
            insets
        }
        binding.bottomInputArea.viewTreeObserver.addOnGlobalLayoutListener {
            updateFloatingInputPadding()
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
        val topPadding = dp(8f).toInt()
        val bottomPadding = binding.bottomInputArea.height + dp(18f).toInt()
        if (
            binding.messagesScrollView.paddingTop == topPadding &&
            binding.messagesScrollView.paddingBottom == bottomPadding
        ) {
            return
        }
        binding.messagesScrollView.setPadding(
            binding.messagesScrollView.paddingLeft,
            topPadding,
            binding.messagesScrollView.paddingRight,
            bottomPadding
        )
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
        listOf(binding.topRightMain, binding.topRightChat, binding.btnNewChat, binding.btnMore).forEach { view ->
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
        }
    }

    private fun animateTopActionsSplit() {
        topActionsAnimator?.cancel()
        binding.topRightMain.isVisible = true
        binding.topRightChat.isVisible = true

        binding.topRightChat.alpha = 0f
        binding.topRightChat.scaleX = 0.48f
        binding.topRightChat.scaleY = 1f
        binding.btnNewChat.alpha = 0f
        binding.btnNewChat.translationX = dp(18f)
        binding.btnNewChat.scaleX = 0.9f
        binding.btnNewChat.scaleY = 0.9f
        binding.btnMore.alpha = 0f
        binding.btnMore.translationX = dp(-8f)
        binding.btnMore.scaleX = 0.9f
        binding.btnMore.scaleY = 0.9f

        binding.topRightChat.post {
            binding.topRightMain.pivotX = binding.topRightMain.width.toFloat()
            binding.topRightMain.pivotY = binding.topRightMain.height / 2f
            binding.topRightChat.pivotX = binding.topRightChat.width.toFloat()
            binding.topRightChat.pivotY = binding.topRightChat.height / 2f

            val expandedScale = if (binding.topRightMain.width > 0) {
                binding.topRightChat.width.toFloat() / binding.topRightMain.width.toFloat()
            } else {
                2.1f
            }

            val stretch = ObjectAnimator.ofFloat(binding.topRightMain, View.SCALE_X, 1f, expandedScale).apply {
                duration = 105L
                interpolator = AccelerateDecelerateInterpolator()
            }

            val reveal = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.topRightMain, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(binding.topRightChat, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(binding.topRightChat, View.SCALE_X, 0.48f, 1f),
                    ObjectAnimator.ofFloat(binding.btnNewChat, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(binding.btnNewChat, View.TRANSLATION_X, dp(18f), 0f),
                    ObjectAnimator.ofFloat(binding.btnNewChat, View.SCALE_X, 0.9f, 1f),
                    ObjectAnimator.ofFloat(binding.btnNewChat, View.SCALE_Y, 0.9f, 1f),
                    ObjectAnimator.ofFloat(binding.btnMore, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(binding.btnMore, View.TRANSLATION_X, dp(-8f), 0f),
                    ObjectAnimator.ofFloat(binding.btnMore, View.SCALE_X, 0.9f, 1f),
                    ObjectAnimator.ofFloat(binding.btnMore, View.SCALE_Y, 0.9f, 1f)
                )
                duration = TOP_ACTIONS_SPLIT_DURATION_MS - stretch.duration
                interpolator = OvershootInterpolator(1.15f)
            }

            topActionsAnimator = AnimatorSet().apply {
                playSequentially(stretch, reveal)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        binding.topRightMain.isGone = true
                        topActionsAnimator = null
                        resetTopActionsTransforms()
                        binding.topRightChat.isVisible = true
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        binding.topRightMain.isGone = true
                        topActionsAnimator = null
                        resetTopActionsTransforms()
                        binding.topRightChat.isVisible = true
                    }
                })
                start()
            }
        }
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }

    private fun startFreshChat() {
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

    private fun openChat(chatId: String) {
        lifecycleScope.launch {
            val chat = chatViewModel.getChatById(chatId) ?: return@launch
            val messages = try {
                chatViewModel.getMessages(chatId)
            } catch (e: android.database.sqlite.SQLiteBlobTooBigException) {
                e.printStackTrace()
                // Auto-delete the corrupted chat to prevent infinite crashes
                chatViewModel.deleteChat(chatId) {
                    runOnUiThread {
                        toast(LocaleHelper.getString(this@FreeChatActivity, "toast_error") + ": Chat corrupted and was deleted")
                        startFreshChat()
                    }
                }
                return@launch
            } catch (e: Exception) {
                e.printStackTrace()
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
            refreshDrawerSelection()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
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
            buildAttachmentPayload(previewUri)
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

        binding.etInput.text?.clear()
        clearPreview()
        updateSendState()

        chatViewModel.addToChatHistoryAndSend(
            content = text,
            base64Data = attachmentPayload?.base64Data,
            fileUri = attachmentPayload?.fileUri,
            mimeType = mimeType,
            fileName = attachmentPayload?.fileName,
            fileContext = attachmentPayload?.attachmentContext,
            onError = { error ->
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    wrapper.updateContent(error, animate = false)
                    refreshDailyQuotaUi()
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
                    refreshDailyQuotaUi()
                    refreshChats()
                }
            }
        )
    }

    private fun stopGeneration() {
        if (!isSending) return

        chatViewModel.cancelActiveResponse()
        isSending = false
        updateSendState()

        val wrapper = currentAssistantMessage ?: return
        val thinkingText = LocaleHelper.getString(this, "ai_thinking")
        if (wrapper.rawText.isBlank() || wrapper.rawText == thinkingText) {
            binding.messagesContainer.removeView(wrapper.container)
            currentAssistantMessage = null
        }
    }

    private fun beginEditingUserMessage(historyIndex: Int, message: String) {
        if (isSending || historyIndex !in 0 until chatViewModel.chatHistory.size) return

        val historyMessage = chatViewModel.chatHistory[historyIndex]
        editingMessageHistoryIndex = historyIndex
        binding.tvEditMessageTitle.text = LocaleHelper.getString(this, "menu_edit_message")
        setInputCapsuleTopOffset(dp(38f).toInt())
        showEditMessagePanel()
        clearPreview()
        retainedEditingAttachment = attachmentPayloadFromHistory(historyMessage)
        showRetainedAttachmentPreview(retainedEditingAttachment)
        hideQuickSuggestions()
        updateInputText(editableTextFromHistory(historyMessage, message), keepSuggestions = false)
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

    private fun editableTextFromHistory(message: JSONObject, fallback: String): String {
        val rawContent = message.optNonBlankString("content") ?: fallback
        val baseText = rawContent.substringBefore("\n\n[")
        val attachmentPlaceholder = LocaleHelper.getString(this, "attachment_empty_text")
        return if (baseText == attachmentPlaceholder) "" else baseText
    }

    private fun attachmentPayloadFromHistory(message: JSONObject): AttachmentPayload? {
        val base64Data = message.optNonBlankString("base64")
        val fileUri = message.optNonBlankString("imageUri")
        val mimeType = message.optNonBlankString("mimeType")
        val fileName = message.optNonBlankString("fileName")
        val fileContext = message.optNonBlankString("fileContext")

        if (base64Data == null && fileUri == null && mimeType == null && fileName == null && fileContext == null) {
            return null
        }

        return AttachmentPayload(
            fileUri = fileUri.orEmpty(),
            mimeType = mimeType ?: resolveMimeTypeFromName(fileName),
            fileName = fileName,
            base64Data = base64Data,
            attachmentContext = fileContext
        )
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

    private fun JSONObject.optNonBlankString(key: String): String? {
        return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }

    private fun editUserMessage(historyIndex: Int, newText: String) {
        if (isSending) return
        if (historyIndex !in 0 until chatViewModel.chatHistory.size) return

        val attachmentPayload = try {
            currentPreviewUri?.let { buildAttachmentPayload(it) } ?: retainedEditingAttachment
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

        removeViewsFromUserMessage(historyIndex)
        chatViewModel.truncateHistoryFrom(historyIndex) {
            runOnUiThread {
                clearEditingMessageState()
                binding.etInput.text?.clear()
                retainedEditingAttachment = null
                clearPreview()
                submitEditedUserMessage(historyIndex, newText, attachmentPayload)
            }
        }
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
        updateSendState()

        chatViewModel.addToChatHistoryAndSend(
            content = text,
            base64Data = attachmentPayload?.base64Data,
            fileUri = attachmentPayload?.fileUri?.takeIf { it.isNotBlank() },
            mimeType = attachmentPayload?.mimeType,
            fileName = attachmentPayload?.fileName,
            fileContext = attachmentPayload?.attachmentContext,
            onError = { error ->
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    wrapper.updateContent(error, animate = false)
                    refreshDailyQuotaUi()
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
                    refreshDailyQuotaUi()
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
                    refreshDailyQuotaUi()
                    refreshChats()
                }
            },
            onError = { error ->
                runOnUiThread {
                    isSending = false
                    updateSendState()
                    wrapper.updateContent(error, animate = false)
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
        retainedEditingAttachment = null
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
        setWelcomeActionButtonsVisible(true)
        syncQuickSuggestions(binding.etInput.text?.toString().orEmpty())
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

        val attachmentContext = if (extractedText != null) {
            buildAttachmentContext(fileName, mimeType, extractedText)
        } else {
            null
        }

        val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

        return AttachmentPayload(
            fileUri = uri.toString(),
            mimeType = mimeType,
            fileName = fileName,
            base64Data = base64Data,
            attachmentContext = attachmentContext
        )
    }

    private fun buildAttachmentContext(
        fileName: String?,
        mimeType: String,
        extractedText: String
    ): String {
        val preview = extractedText.trim().take(MAX_ATTACHMENT_CONTEXT_CHARS)
        return buildString {
            append(LocaleHelper.getString(this@FreeChatActivity, "attachment_context_file_summary"))
            if (!fileName.isNullOrBlank()) append(": ").append(fileName)
            append("\n").append(LocaleHelper.getString(this@FreeChatActivity, "attachment_context_mime")).append(": ").append(mimeType)
            append("\n").append(LocaleHelper.getString(this@FreeChatActivity, "attachment_context_preview")).append(":\n").append(preview)
            if (extractedText.length > preview.length) {
                append("\n\n").append(LocaleHelper.getString(this@FreeChatActivity, "attachment_context_truncated"))
            }
        }
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

    private fun resolveMimeTypeFromName(fileName: String?): String {
        return fileName?.let { java.net.URLConnection.guessContentTypeFromName(it) }
            ?: "application/octet-stream"
    }

    private fun readAttachmentBytes(uri: Uri): ByteArray {
        val declaredSize = queryAttachmentSize(uri)
        if (declaredSize != null && declaredSize > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException(LocaleHelper.getString(this, "attachment_too_large"))
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
                    throw IllegalArgumentException(LocaleHelper.getString(this, "attachment_too_large"))
                }
                output.write(buffer, 0, read)
            }
        } ?: throw IllegalArgumentException(LocaleHelper.getString(this, "attachment_read_error"))

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
            text.take(MAX_EXTRACTED_TEXT_CHARS) + "\n\n" + LocaleHelper.getString(this, "attachment_text_truncated")
        } else {
            text
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
