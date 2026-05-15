package com.example.chatapp.assistant

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.databinding.ViewDigitalAssistantOverlayBinding
import com.example.chatapp.ui.ChatMessageRenderer
import com.example.chatapp.ui.MarkdownTableRenderer
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.SafeImageLoader
import com.example.chatapp.util.SyntaxHighlighter
import com.example.chatapp.util.setHapticClickListener
import io.noties.markwon.Markwon
import kotlin.math.max
import kotlin.math.min

private const val REACTION_LIKE = "like"
private const val REACTION_DISLIKE = "dislike"

class DigitalAssistantOverlayView(
    context: Context,
    private val viewModel: DigitalAssistantViewModel,
    private val host: DigitalAssistantHost
) : FrameLayout(context) {
    private val binding = ViewDigitalAssistantOverlayBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    private val smoothOut = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val panelOut = DecelerateInterpolator(1.55f)
    private val markwon = Markwon.create(context)
    private val messageTextViews = mutableMapOf<Long, TextView>()
    private val assistantContentViews = mutableMapOf<Long, LinearLayout>()
    private val attachmentRows = mutableMapOf<Long, View>()
    private val messageRoles = mutableMapOf<Long, AssistantMessageRole>()
    private val messageActionRows = mutableMapOf<Long, View>()
    private val typingViews = mutableMapOf<Long, TextView>()

    private var latestState = DigitalAssistantState()
    private var lastRenderedMessageIds = emptyList<Long>()
    private var lastHasAttachment = false
    private var lastAttachmentId: String? = null
    private var expandedPanelVisible: Boolean? = null
    private var closingStarted = false
    private var attachmentMenuVisible = false
    private var bottomInset = 0
    private var topInset = 0
    private var startInset = 0
    private var endInset = 0
    private var keyboardInset = 0
    private var keyboardVisible = false
    private var typingAnimator: Animator? = null
    private var typingView: TextView? = null
    private var dragStartY = 0f
    private var dragLastY = 0f
    private var draggingHandle = false
    private val assistantReactions = mutableMapOf<Long, String>()

    private val listener: (DigitalAssistantState) -> Unit = { state ->
        latestState = state
        render(state)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false
        binding.dimView.setBackgroundColor(Color.TRANSPARENT)
        binding.dimView.alpha = 1f
        configureStaticText()
        configureInput()
        configureClicks()
        binding.bottomPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMessageMaxWidths()
            updateFloatingPositions()
            expandDragHandleTouchTarget()
        }
        setOnApplyWindowInsetsListener { _, insets ->
            updateInsets(insets)
            insets
        }
        viewModel.addListener(listener)
        post {
            requestFocus()
            updateFloatingPositions()
            expandDragHandleTouchTarget()
            playIntroAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        stopTypingDots()
        viewModel.removeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            requestClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun configureStaticText() {
        binding.assistantTitle.text = buildAssistantTitle()
        binding.openFreeChatButton.text = LocaleHelper.getString(context, "digital_assistant_open_freechat")
        binding.translateScreenButton.text = LocaleHelper.getString(context, "digital_assistant_translate_screen")
        binding.askScreenButton.text = LocaleHelper.getString(context, "digital_assistant_ask_screen")
        binding.menuCameraText.text = LocaleHelper.getString(context, "button_camera")
        binding.menuPhotoText.text = LocaleHelper.getString(context, "button_photo")
        binding.menuFilesText.text = LocaleHelper.getString(context, "button_files")
        binding.confirmText.text = LocaleHelper.getString(context, "digital_assistant_close_generating_message")
        binding.cancelCloseButton.text = LocaleHelper.getString(context, "button_cancel")
        binding.confirmCloseButton.text = LocaleHelper.getString(context, "digital_assistant_close")
        binding.inputField.hint = LocaleHelper.getString(context, "main_panel_input")
        listOf(
            binding.openFreeChatButton,
            binding.translateScreenButton,
            binding.askScreenButton,
            binding.menuCameraButton,
            binding.menuPhotoButton,
            binding.menuFilesButton,
            binding.cancelCloseButton,
            binding.confirmCloseButton
        ).forEach { button ->
            button.isClickable = true
            button.isFocusable = true
        }
    }

    private fun buildAssistantTitle(): SpannableString {
        val brand = LocaleHelper.getString(context, "app_brand")
        val assistant = LocaleHelper.getString(context, "digital_assistant_short_title")
        val title = "$brand | $assistant"
        return SpannableString(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(Color.WHITE),
                0,
                brand.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(Color.parseColor("#D9D9D9")),
                brand.length,
                title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun configureInput() {
        binding.inputField.apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            doAfterTextChanged { updateSendButton() }
            setOnEditorActionListener { _, _, _ ->
                if (binding.sendButton.isEnabled) {
                    submitFromInput()
                    true
                } else {
                    false
                }
            }
        }
        updateSendButton()
    }

    private fun configureClicks() {
        binding.dimView.setOnClickListener {
            requestFocus()
        }
        binding.dragHandle.setOnTouchListener { _, event -> handleDragGesture(event) }
        binding.openFreeChatButton.setHapticClickListener { openFreeChat() }
        binding.translateScreenButton.setHapticClickListener { requestScreenTranslation() }
        binding.askScreenButton.setHapticClickListener { requestOneShotScreenAttachment() }
        binding.addButton.setHapticClickListener { toggleAttachmentMenu() }
        binding.menuCameraButton.setHapticClickListener {
            launchAttachmentPicker(DigitalAssistantAttachmentPickerActivity.SOURCE_CAMERA)
        }
        binding.menuPhotoButton.setHapticClickListener {
            launchAttachmentPicker(DigitalAssistantAttachmentPickerActivity.SOURCE_PHOTO)
        }
        binding.menuFilesButton.setHapticClickListener {
            launchAttachmentPicker(DigitalAssistantAttachmentPickerActivity.SOURCE_FILES)
        }
        binding.removePreview.setHapticClickListener { viewModel.clearAttachment() }
        binding.sendButton.setHapticClickListener {
            if (binding.sendButton.isEnabled) {
                playSendTap()
                submitFromInput()
            }
        }
        binding.cancelCloseButton.setHapticClickListener { binding.confirmPanel.isGone = true }
        binding.confirmCloseButton.setHapticClickListener { closeWithAnimation(force = true) }
    }

    private fun expandDragHandleTouchTarget() {
        val parent = binding.dragHandle.parent as? View ?: return
        parent.post {
            val rect = Rect()
            binding.dragHandle.getHitRect(rect)
            rect.inset(-dp(34), -dp(16))
            parent.touchDelegate = TouchDelegate(rect, binding.dragHandle)
        }
    }

    private fun submitFromInput() {
        if (viewModel.submit(binding.inputField.text?.toString().orEmpty())) {
            hideAttachmentMenu(immediate = true)
            binding.inputField.text?.clear()
        }
    }

    private fun openFreeChat() {
        hideAttachmentMenu(immediate = true)
        val token = viewModel.createHandoff(context, binding.inputField.text?.toString().orEmpty())
        host.openFreeChat(token)
    }

    private fun requestOneShotScreenAttachment() {
        hideAttachmentMenu(immediate = true)
        host.requestScreenCapture { result ->
            result.onSuccess { attachment ->
                viewModel.setScreenAttachment(attachment)
            }.onFailure {
                host.showMessage(
                    it.message
                        ?: LocaleHelper.getString(context, "digital_assistant_assist_screenshot_unavailable")
                )
            }
        }
    }

    private fun requestScreenTranslation() {
        hideAttachmentMenu(immediate = true)
        host.requestScreenCapture { result ->
            result.onSuccess { attachment ->
                viewModel.setScreenAttachment(attachment)
                viewModel.submit(LocaleHelper.getString(context, "digital_assistant_translate_screen_prompt"))
            }.onFailure {
                host.showMessage(
                    it.message
                        ?: LocaleHelper.getString(context, "digital_assistant_assist_screenshot_unavailable")
                )
            }
        }
    }

    private fun toggleAttachmentMenu() {
        if (attachmentMenuVisible) {
            hideAttachmentMenu()
        } else {
            showAttachmentMenu()
        }
    }

    private fun showAttachmentMenu() {
        if (closingStarted) return
        attachmentMenuVisible = true
        binding.attachmentMenu.animate().cancel()
        binding.attachmentMenu.apply {
            isVisible = true
            alpha = 0f
            translationY = dp(180).toFloat()
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(smoothOut)
                .start()
        }
    }

    private fun hideAttachmentMenu(immediate: Boolean = false) {
        if (!attachmentMenuVisible && binding.attachmentMenu.isGone) return
        attachmentMenuVisible = false
        binding.attachmentMenu.animate().cancel()
        if (immediate) {
            binding.attachmentMenu.isGone = true
            binding.attachmentMenu.alpha = 0f
            binding.attachmentMenu.translationY = dp(180).toFloat()
            return
        }
        binding.attachmentMenu.animate()
            .alpha(0f)
            .translationY(dp(180).toFloat())
            .setDuration(190L)
            .setInterpolator(smoothOut)
            .withEndAction {
                if (!attachmentMenuVisible) {
                    binding.attachmentMenu.isGone = true
                }
            }
            .start()
    }

    private fun launchAttachmentPicker(source: String) {
        hideAttachmentMenu(immediate = true)
        runCatching {
            val intent = Intent(context, DigitalAssistantAttachmentPickerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(DigitalAssistantAttachmentPickerActivity.EXTRA_SOURCE, source)
            }
            context.startActivity(intent)
            host.hideForExternalPicker()
        }.onFailure {
            host.showMessage(
                it.message ?: LocaleHelper.getString(context, "attachment_read_error")
            )
        }
    }

    private fun render(state: DigitalAssistantState) {
        updatePanelMode(state)
        renderAttachment(state)
        renderMessages(state.messages)
        updateSendButton()
        updateFloatingPositions()
    }

    private fun updatePanelMode(state: DigitalAssistantState) {
        val expanded = state.messages.isNotEmpty()
        val changed = expandedPanelVisible != expanded
        expandedPanelVisible = expanded

        binding.dragHandle.isVisible = expanded
        binding.assistantTitle.isVisible = expanded
        binding.messagesFrame.isVisible = expanded
        binding.sheetContent.gravity = if (expanded) Gravity.NO_GRAVITY else Gravity.BOTTOM
        setInputRowBottomMargin(if (expanded) dp(14) else dp(4))
        binding.bottomPanel.clipChildren = expanded
        binding.bottomPanel.clipToPadding = expanded
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            binding.bottomPanel.clipToOutline = expanded
        }

        binding.dimView.setBackgroundColor(Color.TRANSPARENT)
        binding.dimView.alpha = 1f

        if (expanded) {
            if (changed) {
                binding.bottomPanel.setBackgroundResource(R.drawable.bg_da_bottom_panel)
                binding.dragHandle.alpha = 0f
                binding.assistantTitle.alpha = 0f
                binding.messagesFrame.alpha = 0f
                listOf(binding.dragHandle, binding.assistantTitle, binding.messagesFrame).forEach { view ->
                    view.animate()
                        .alpha(1f)
                        .setDuration(190L)
                        .setInterpolator(smoothOut)
                        .start()
                }
            }
        } else {
            binding.bottomPanel.background = null
            binding.responseScroll.scrollTo(0, 0)
        }

        setCompactActionsVisible(
            visible = !expanded && state.attachment == null && !closingStarted,
            animate = changed
        )
    }

    private fun setInputRowBottomMargin(margin: Int) {
        val params = binding.inputRow.layoutParams as LinearLayout.LayoutParams
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            binding.inputRow.layoutParams = params
        }
    }

    private fun setCompactActionsVisible(visible: Boolean, animate: Boolean = true) {
        binding.actionsColumn.animate().cancel()
        if (visible) {
            binding.actionsColumn.isVisible = true
            if (animate) {
                binding.actionsColumn.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(190L)
                    .setInterpolator(smoothOut)
                    .start()
            } else {
                binding.actionsColumn.alpha = 1f
                binding.actionsColumn.translationY = 0f
            }
        } else if (animate) {
            binding.actionsColumn.animate()
                .alpha(0f)
                .translationY(dp(14).toFloat())
                .setDuration(140L)
                .setInterpolator(smoothOut)
                .withEndAction {
                    if (latestState.messages.isNotEmpty() || latestState.attachment != null || closingStarted) {
                        binding.actionsColumn.isGone = true
                    }
                }
                .start()
        } else {
            binding.actionsColumn.isGone = true
            binding.actionsColumn.alpha = 0f
            binding.actionsColumn.translationY = dp(14).toFloat()
        }
    }

    private fun renderAttachment(state: DigitalAssistantState) {
        val attachment = state.attachment
        val hasAttachment = attachment != null
        val attachmentId = attachment?.cacheFilePath ?: attachment?.fileName

        if (hasAttachment && attachmentId != lastAttachmentId) {
            if (attachment?.mimeType?.startsWith("image/", ignoreCase = true) == true) {
                binding.previewImage.setPadding(0, 0, 0, 0)
                binding.previewImage.clearColorFilter()
                binding.previewImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                SafeImageLoader.loadBase64Image(
                    imageView = binding.previewImage,
                    base64Data = attachment.base64Data,
                    fileName = attachment.fileName,
                    widthPx = dp(64),
                    heightPx = dp(104)
                )
            } else {
                binding.previewImage.setPadding(dp(16), dp(16), dp(16), dp(16))
                binding.previewImage.setColorFilter(Color.parseColor("#8E8E93"))
                binding.previewImage.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                binding.previewImage.setImageResource(R.drawable.ic_file_new)
            }
        }

        when {
            hasAttachment && !lastHasAttachment -> showPreview()
            !hasAttachment && lastHasAttachment -> hidePreview()
            hasAttachment -> binding.previewContainer.isVisible = true
            else -> binding.previewContainer.isGone = true
        }

        lastHasAttachment = hasAttachment
        lastAttachmentId = attachmentId
    }

    private fun showPreview() {
        binding.previewContainer.isVisible = true
        binding.previewFrame.alpha = 0f
        binding.previewFrame.scaleX = 0.9f
        binding.previewFrame.scaleY = 0.9f
        binding.previewFrame.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240L)
            .setInterpolator(smoothOut)
            .start()
    }

    private fun hidePreview() {
        binding.previewFrame.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(160L)
            .setInterpolator(smoothOut)
            .withEndAction {
                if (latestState.attachment == null) {
                    binding.previewImage.setImageDrawable(null)
                    binding.previewImage.clearColorFilter()
                    binding.previewImage.setPadding(0, 0, 0, 0)
                    binding.previewContainer.isGone = true
                    binding.previewFrame.alpha = 1f
                    binding.previewFrame.scaleX = 1f
                    binding.previewFrame.scaleY = 1f
                    updateFloatingPositions()
                }
            }
            .start()
    }

    private fun renderMessages(messages: List<AssistantMessage>) {
        val ids = messages.map { it.id }
        val idsChanged = ids != lastRenderedMessageIds
        val shouldScroll = idsChanged || isScrolledNearBottom()
        assistantReactions.keys.retainAll(ids.toSet())

        if (idsChanged) {
            val previousIds = lastRenderedMessageIds.toSet()
            rebuildMessages(messages, previousIds)
            lastRenderedMessageIds = ids
        } else {
            messages.forEach(::updateMessageView)
        }

        val hasActiveTyping = messages.any {
            it.role == AssistantMessageRole.ASSISTANT &&
                it.status == AssistantResponseStatus.LOADING &&
                it.text.isBlank()
        }
        if (!hasActiveTyping) {
            stopTypingDots()
        }

        if (shouldScroll) {
            binding.responseScroll.post { smoothScrollToBottom() }
        }
    }

    private fun rebuildMessages(messages: List<AssistantMessage>, previousIds: Set<Long>) {
        stopTypingDots()
        binding.responseContent.removeAllViews()
        messageTextViews.clear()
        assistantContentViews.clear()
        attachmentRows.clear()
        messageRoles.clear()
        messageActionRows.clear()
        typingViews.clear()
        messages.forEachIndexed { index, message ->
            addMessageView(message, index, animate = message.id !in previousIds)
        }
    }

    private fun addMessageView(message: AssistantMessage, index: Int, animate: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (message.role == AssistantMessageRole.USER) Gravity.END else Gravity.START
            clipChildren = false
            clipToPadding = false
        }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = when {
                index == 0 -> 0
                message.role == AssistantMessageRole.USER -> dp(13)
                else -> dp(17)
            }
        }

        if (message.role == AssistantMessageRole.USER) {
            addUserMessage(row, message)
        } else {
            addAssistantMessage(row, message)
        }

        binding.responseContent.addView(row)
        updateMessageView(message)
        if (animate) {
            playMessageAnimation(row, fromRight = message.role == AssistantMessageRole.USER)
        }
    }

    private fun addUserMessage(row: LinearLayout, message: AssistantMessage) {
        if (message.attachments.isNotEmpty()) {
            val attachments = createAttachmentList(message.attachments)
            attachmentRows[message.id] = attachments
            row.addView(attachments)
        }
        val bubble = TextView(context).apply {
            id = View.generateViewId()
            background = context.getDrawable(R.drawable.bg_da_user_bubble)
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = false
            setHorizontallyScrolling(false)
        }
        bubble.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            marginEnd = dp(2)
        }
        messageTextViews[message.id] = bubble
        messageRoles[message.id] = message.role
        if (message.text.isNotBlank()) {
            row.addView(bubble)
        }
    }

    private fun addAssistantMessage(row: LinearLayout, message: AssistantMessage) {
        val content = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        content.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        assistantContentViews[message.id] = content
        messageRoles[message.id] = message.role
        row.addView(content)

        val dots = TextView(context).apply {
            includeFontPadding = false
            gravity = Gravity.CENTER
            this.text = "..."
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        dots.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(28)
        ).apply {
            topMargin = dp(1)
        }
        typingViews[message.id] = dots
        row.addView(dots)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        actions.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(24)
        ).apply {
            topMargin = dp(5)
        }
        addAssistantActionButtons(actions, message)
        messageActionRows[message.id] = actions
        row.addView(actions)
    }

    private fun addAssistantActionButtons(actions: LinearLayout, message: AssistantMessage) {
        var likeButton: ImageButton? = null
        var dislikeButton: ImageButton? = null
        var currentReaction = assistantReactions[message.id]
        val buttons = listOf(
            R.drawable.ic_copy to { view: ImageButton ->
                animateActionTap(view)
                FileUtils.copyToClipboard(context, messageTextById(message.id))
            },
            R.drawable.ic_thumb_up to { view: ImageButton ->
                animateActionTap(view)
                currentReaction = if (currentReaction == REACTION_LIKE) null else REACTION_LIKE
                setAssistantReaction(message.id, currentReaction)
                updateAssistantReactionButtons(
                    likeButton,
                    dislikeButton,
                    currentReaction,
                    animate = true,
                    visibleWidth = dp(22),
                    visibleMarginEnd = dp(1)
                )
            },
            R.drawable.ic_thumb_down to { view: ImageButton ->
                animateActionTap(view)
                currentReaction = if (currentReaction == REACTION_DISLIKE) null else REACTION_DISLIKE
                setAssistantReaction(message.id, currentReaction)
                updateAssistantReactionButtons(
                    likeButton,
                    dislikeButton,
                    currentReaction,
                    animate = true,
                    visibleWidth = dp(22),
                    visibleMarginEnd = dp(1)
                )
            },
            R.drawable.ic_share to { view: ImageButton ->
                animateActionTap(view)
                FileUtils.shareText(context, messageTextById(message.id))
            },
            R.drawable.ic_more_vertical to { view: ImageButton ->
                animateActionTap(view)
                showAssistantMessageMenu(view, message.id)
            }
        )
        buttons.forEachIndexed { index, item ->
            val button = createActionButton(item.first, item.second)
            if (index == 1) likeButton = button
            if (index == 2) dislikeButton = button
            actions.addView(button)
        }
        updateAssistantReactionButtons(
            likeButton,
            dislikeButton,
            currentReaction,
            animate = false,
            visibleWidth = dp(22),
            visibleMarginEnd = dp(1)
        )
    }

    private fun setAssistantReaction(messageId: Long, reaction: String?) {
        if (reaction == null) {
            assistantReactions.remove(messageId)
        } else {
            assistantReactions[messageId] = reaction
        }
    }

    private fun updateAssistantReactionButtons(
        likeButton: ImageButton?,
        dislikeButton: ImageButton?,
        reaction: String?,
        animate: Boolean,
        visibleWidth: Int,
        visibleMarginEnd: Int
    ) {
        setAssistantReactionButtonState(
            button = likeButton,
            isActive = reaction == REACTION_LIKE,
            isVisibleChoice = reaction == null || reaction == REACTION_LIKE,
            animate = animate,
            visibleWidth = visibleWidth,
            visibleMarginEnd = visibleMarginEnd
        )
        setAssistantReactionButtonState(
            button = dislikeButton,
            isActive = reaction == REACTION_DISLIKE,
            isVisibleChoice = reaction == null || reaction == REACTION_DISLIKE,
            animate = animate,
            visibleWidth = visibleWidth,
            visibleMarginEnd = visibleMarginEnd
        )
    }

    private fun setAssistantReactionButtonState(
        button: ImageButton?,
        isActive: Boolean,
        isVisibleChoice: Boolean,
        animate: Boolean,
        visibleWidth: Int,
        visibleMarginEnd: Int
    ) {
        button ?: return
        val targetColor = Color.parseColor(if (isActive) "#FFFFFF" else "#A6A6A6")
        val targetAlpha = when {
            !isVisibleChoice -> 0f
            isActive -> 1f
            else -> 0.86f
        }
        val targetScale = if (isVisibleChoice) 1f else 0.72f
        val targetWidth = if (isVisibleChoice) visibleWidth else 0
        val targetMarginEnd = if (isVisibleChoice) visibleMarginEnd else 0
        button.animate().cancel()
        button.isSelected = isActive
        button.isEnabled = isVisibleChoice
        button.isClickable = isVisibleChoice
        if (!animate) {
            button.setColorFilter(targetColor)
            button.alpha = targetAlpha
            button.scaleX = targetScale
            button.scaleY = targetScale
            (button.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = targetWidth
                params.marginEnd = targetMarginEnd
                button.layoutParams = params
            }
            button.visibility = if (isVisibleChoice) View.VISIBLE else View.GONE
            button.tag = targetColor
            return
        }

        if (isVisibleChoice) {
            button.visibility = View.VISIBLE
        }

        val currentColor = (button.tag as? Int) ?: Color.parseColor("#A6A6A6")
        ValueAnimator.ofArgb(currentColor, targetColor).apply {
            duration = 140L
            addUpdateListener {
                val color = it.animatedValue as Int
                button.setColorFilter(color)
                button.tag = color
            }
            start()
        }
        (button.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val startWidth = params.width
            val startMarginEnd = params.marginEnd
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 140L
                addUpdateListener {
                    val fraction = it.animatedFraction
                    params.width = (startWidth + (targetWidth - startWidth) * fraction).toInt()
                    params.marginEnd = (startMarginEnd + (targetMarginEnd - startMarginEnd) * fraction).toInt()
                    button.layoutParams = params
                }
                start()
            }
        }
        button.animate()
            .alpha(targetAlpha)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(140L)
            .withEndAction {
                if (!isVisibleChoice) {
                    button.visibility = View.GONE
                }
            }
            .start()
    }

    private fun createActionButton(icon: Int, onClick: (ImageButton) -> Unit): ImageButton =
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(1)
            }
            background = selectableBorderlessDrawable()
            contentDescription = null
            setImageResource(icon)
            setColorFilter(Color.parseColor("#A6A6A6"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxWidth = dp(12)
            maxHeight = dp(12)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(this) }
        }

    private fun updateMessageView(message: AssistantMessage) {
        val showTyping = message.role == AssistantMessageRole.ASSISTANT &&
            message.status == AssistantResponseStatus.LOADING &&
            message.text.isBlank()

        if (message.role == AssistantMessageRole.USER) {
            val textView = messageTextViews[message.id]
            textView?.text = message.text
            textView?.isVisible = message.text.isNotBlank()
        } else {
            val content = assistantContentViews[message.id]
            content?.isVisible = !showTyping
            if (!showTyping && content != null && content.tag != message.text) {
                renderAssistantMarkdown(content, message.text)
                content.tag = message.text
            }
        }

        typingViews[message.id]?.let { dots ->
            dots.isVisible = showTyping
            if (showTyping) {
                startTypingDots(dots)
            }
        }
        messageActionRows[message.id]?.isVisible =
            message.role == AssistantMessageRole.ASSISTANT &&
                message.status == AssistantResponseStatus.SUCCESS &&
                message.text.isNotBlank()
        updateMessageMaxWidths()
    }

    private fun updateMessageMaxWidths() {
        val panelWidth = binding.bottomPanel.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(12))
        val assistantMax = (panelWidth * 0.82f).toInt()
        val userMax = (panelWidth * 0.74f).toInt()
        messageTextViews.forEach { (id, textView) ->
            textView.maxWidth = if (messageRoles[id] == AssistantMessageRole.USER) {
                userMax
            } else {
                assistantMax
            }
        }
        assistantContentViews.values.forEach { content ->
            val params = content.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            if (params.width != assistantMax) {
                params.width = assistantMax
                content.layoutParams = params
            }
        }
        attachmentRows.values.forEach { attachment ->
            val params = attachment.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            if (params.width != userMax) {
                params.width = userMax
                attachment.layoutParams = params
            }
        }
    }

    private fun renderAssistantMarkdown(content: LinearLayout, text: String) {
        content.removeAllViews()
        val chunks = MarkdownTableRenderer.splitIntoChunks(text)
        chunks.forEach { chunk ->
            when (chunk) {
                is MarkdownTableRenderer.Chunk.Text -> {
                    if (chunk.content.isNotBlank()) {
                        val tv = TextView(context).apply {
                            setTextColor(Color.WHITE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            includeFontPadding = false
                            setLineSpacing(dp(2).toFloat(), 1.08f)
                            setPadding(0, dp(3), 0, dp(5))
                            isSingleLine = false
                            setHorizontallyScrolling(false)
                            movementMethod = LinkMovementMethod.getInstance()
                            linksClickable = true
                        }
                        markwon.setMarkdown(tv, chunk.content)
                        content.addView(tv, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ))
                    }
                }
                is MarkdownTableRenderer.Chunk.Code -> {
                    val (codeBlock, codeText) = ChatMessageRenderer.createCodeBlockView(
                        context,
                        chunk.content,
                        chunk.language
                    )
                    codeText.text = SyntaxHighlighter.highlight(chunk.content, chunk.language)
                    content.addView(codeBlock, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(7)
                        bottomMargin = dp(7)
                    })
                }
                is MarkdownTableRenderer.Chunk.Table -> {
                    val table = MarkdownTableRenderer.createTableView(context, chunk.parsed, chunk.raw)
                    content.addView(table, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(7)
                        bottomMargin = dp(7)
                    })
                }
            }
        }
    }

    private fun createAttachmentList(attachments: List<AssistantAttachment>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                bottomMargin = dp(6)
                marginEnd = dp(2)
            }
            attachments.forEach { attachment ->
                addView(createAttachmentView(attachment))
            }
        }

    private fun createAttachmentView(attachment: AssistantAttachment): View {
        return if (attachment.mimeType.startsWith("image/", ignoreCase = true)) {
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(86), dp(86)).apply {
                    gravity = Gravity.END
                    bottomMargin = dp(6)
                }
                background = context.getDrawable(R.drawable.bg_da_preview)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                SafeImageLoader.loadBase64Image(
                    imageView = this,
                    base64Data = attachment.base64Data,
                    fileName = attachment.fileName,
                    widthPx = dp(86),
                    heightPx = dp(86)
                )
                isClickable = true
                isFocusable = true
                foreground = selectableBorderlessDrawable()
                setOnClickListener {
                    FileUtils.openBase64File(context, attachment.base64Data, attachment.fileName, attachment.mimeType)
                }
            }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = context.getDrawable(R.drawable.bg_da_user_bubble)
                setPadding(dp(13), dp(10), dp(13), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                    bottomMargin = dp(6)
                }
                isClickable = true
                isFocusable = true
                foreground = selectableBorderlessDrawable()
                setOnClickListener {
                    FileUtils.openBase64File(context, attachment.base64Data, attachment.fileName, attachment.mimeType)
                }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_file_new)
                    setColorFilter(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        marginEnd = dp(10)
                    }
                })
                addView(TextView(context).apply {
                    this.text = attachment.fileName.ifBlank {
                        LocaleHelper.getString(context, "label_file_analysis")
                    }
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }
    }

    private fun showAssistantMessageMenu(anchor: View, messageId: Long) {
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.popup_menu_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(createMenuItem(R.drawable.ic_copy, LocaleHelper.getString(context, "menu_copy_text")) {
                FileUtils.copyToClipboard(context, messageTextById(messageId))
            })
            addView(createMenuItem(R.drawable.ic_share, LocaleHelper.getString(context, "share")) {
                FileUtils.shareText(context, messageTextById(messageId))
            })
            addView(createMenuItem(android.R.drawable.ic_popup_sync, LocaleHelper.getString(context, "menu_regenerate")) {
                viewModel.retry()
            })
        }
        val popup = PopupWindow(menu, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(14).toFloat()
        }
        popup.showAsDropDown(anchor, -dp(184), dp(2))
    }

    private fun messageTextById(messageId: Long): String =
        latestState.messages.firstOrNull { it.id == messageId }?.text.orEmpty()

    private fun createMenuItem(icon: Int, label: String, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = selectableItemDrawable()
            isClickable = true
            isFocusable = true
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    marginEnd = dp(12)
                }
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            setOnClickListener { onClick() }
        }

    private fun animateActionTap(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(70L)
            .setInterpolator(smoothOut)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .setInterpolator(panelOut)
                    .start()
            }
            .start()
    }

    private fun selectableBorderlessDrawable() =
        selectableDrawable(android.R.attr.selectableItemBackgroundBorderless)

    private fun selectableItemDrawable() =
        selectableDrawable(android.R.attr.selectableItemBackground)

    private fun selectableDrawable(attr: Int) =
        TypedValue().let { outValue ->
            context.theme.resolveAttribute(attr, outValue, true)
            ContextCompat.getDrawable(context, outValue.resourceId)
        }

    private fun playMessageAnimation(view: View, fromRight: Boolean) {
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.translationX = if (fromRight) dp(12).toFloat() else 0f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .translationX(0f)
            .setDuration(230L)
            .setInterpolator(smoothOut)
            .start()
    }

    private fun isScrolledNearBottom(): Boolean {
        val child = binding.responseScroll.getChildAt(0) ?: return true
        val distance = child.bottom - (binding.responseScroll.height + binding.responseScroll.scrollY)
        return distance <= dp(42)
    }

    private fun smoothScrollToBottom() {
        val child = binding.responseScroll.getChildAt(0) ?: return
        binding.responseScroll.smoothScrollTo(0, child.bottom)
    }

    private fun updateSendButton() {
        val enabled = (binding.inputField.text?.isNotBlank() == true || latestState.attachment != null) &&
            !latestState.isGenerating
        binding.sendButton.isEnabled = enabled
        binding.sendButton.alpha = 1f
        binding.sendButton.setColorFilter(if (enabled) Color.BLACK else Color.parseColor("#1F1F1F"))
    }

    private fun startTypingDots(view: TextView) {
        if (typingView === view && typingAnimator != null) return
        stopTypingDots()
        typingView = view
        view.alpha = 0.45f
        typingAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 0.35f, 1f, 0.35f).apply {
            duration = 980L
            repeatCount = ValueAnimator.INFINITE
            interpolator = smoothOut
            start()
        }
    }

    private fun stopTypingDots() {
        typingAnimator?.cancel()
        typingAnimator = null
        typingView?.alpha = 1f
        typingView = null
    }

    private fun playIntroAnimation() {
        binding.dimView.animate().cancel()
        binding.dimView.setBackgroundColor(Color.TRANSPARENT)
        binding.dimView.alpha = 1f

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.bottomPanel, View.SCALE_X, 0.98f, 1f),
                ObjectAnimator.ofFloat(binding.bottomPanel, View.SCALE_Y, 0.98f, 1f),
                ObjectAnimator.ofFloat(binding.bottomPanel, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.bottomPanel, View.TRANSLATION_Y, dp(52).toFloat(), 0f)
            )
            duration = 360L
            interpolator = panelOut
            start()
        }
    }

    private fun playSendTap() {
        binding.sendButton.animate().cancel()
        binding.sendButton.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(70L)
            .setInterpolator(smoothOut)
            .withEndAction {
                binding.sendButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .setInterpolator(panelOut)
                    .start()
            }
            .start()
    }

    private fun handleDragGesture(event: MotionEvent): Boolean {
        if (latestState.messages.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = true
                dragStartY = event.rawY
                dragLastY = event.rawY
                binding.bottomPanel.animate().cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggingHandle) return false
                dragLastY = event.rawY
                val dy = dragLastY - dragStartY
                binding.bottomPanel.translationY = if (dy < 0f) dy * 0.38f else dy
                val progress = (kotlin.math.abs(dy) / dp(130).toFloat()).coerceIn(0f, 1f)
                binding.bottomPanel.scaleX = 1f + if (dy < 0f) progress * 0.025f else -progress * 0.018f
                binding.bottomPanel.scaleY = 1f + if (dy < 0f) progress * 0.025f else -progress * 0.018f
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!draggingHandle) return false
                draggingHandle = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val dy = event.rawY - dragStartY
                when {
                    dy <= -dp(72) -> expandIntoFreeChat()
                    dy >= dp(86) -> closeWithAnimation(force = false)
                    else -> resetPanelAfterDrag()
                }
                return true
            }
        }
        return false
    }

    private fun resetPanelAfterDrag() {
        binding.bottomPanel.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210L)
            .setInterpolator(panelOut)
            .start()
    }

    private fun expandIntoFreeChat() {
        hideAttachmentMenu(immediate = true)
        binding.bottomPanel.animate()
            .translationY(-dp(120).toFloat())
            .scaleX(1.04f)
            .scaleY(1.04f)
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(smoothOut)
            .withEndAction { openFreeChat() }
            .start()
        binding.dimView.animate().cancel()
        binding.dimView.alpha = 1f
    }

    private fun requestClose() {
        if (attachmentMenuVisible) {
            hideAttachmentMenu()
            return
        }
        if (latestState.isGenerating) {
            binding.confirmPanel.isVisible = true
        } else {
            closeWithAnimation(force = false)
        }
    }

    private fun closeWithAnimation(force: Boolean) {
        if (closingStarted) return
        closingStarted = true
        stopTypingDots()
        hideAttachmentMenu(immediate = true)
        binding.confirmPanel.isGone = true
        binding.actionsColumn.animate()
            .alpha(0f)
            .translationY(dp(14).toFloat())
            .setDuration(150L)
            .setInterpolator(smoothOut)
            .start()
        binding.bottomPanel.animate()
            .alpha(0f)
            .scaleX(0.98f)
            .scaleY(0.98f)
            .translationY(dp(52).toFloat())
            .setDuration(235L)
            .setInterpolator(smoothOut)
            .withEndAction {
                host.closeAssistant(force = force)
            }
            .start()
        binding.dimView.animate().cancel()
        binding.dimView.alpha = 1f
    }

    private fun updateInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            val gestures = insets.getInsets(WindowInsets.Type.systemGestures())
            val status = insets.getInsets(WindowInsets.Type.statusBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            bottomInset = max(nav.bottom, gestures.bottom)
            startInset = nav.left
            endInset = nav.right
            topInset = status.top
            keyboardVisible = insets.isVisible(WindowInsets.Type.ime()) && ime.bottom > bottomInset
            keyboardInset = if (keyboardVisible) ime.bottom else 0
        } else {
            @Suppress("DEPRECATION")
            val rawBottomInset = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            startInset = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            endInset = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            topInset = insets.systemWindowInsetTop
            var navBottomInset = rawBottomInset
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                navBottomInset = max(navBottomInset, insets.systemGestureInsets.bottom)
            }
            keyboardVisible = binding.inputField.hasFocus() && rawBottomInset > dp(120)
            keyboardInset = if (keyboardVisible) rawBottomInset else 0
            bottomInset = if (keyboardVisible) 0 else navBottomInset
        }
        updateFloatingPositions()
    }

    private fun updateFloatingPositions() {
        val compact = latestState.messages.isEmpty()
        val bottom = if (keyboardVisible) {
            keyboardInset + dp(6)
        } else {
            bottomInset + dp(6)
        }
        val targetHeight = if (compact) {
            if (latestState.attachment != null) dp(158) else dp(48)
        } else {
            val availableHeight = if (height > 0) {
                height - topInset - bottom - dp(8)
            } else {
                resources.displayMetrics.heightPixels - bottom - dp(8)
            }
            min(dp(352), max(dp(286), availableHeight))
        }
        setFrameLayoutParams(
            binding.bottomPanel,
            start = startInset + dp(6),
            end = endInset + dp(6),
            bottom = bottom,
            height = targetHeight
        )
        setFrameLayoutParams(
            binding.actionsColumn,
            start = startInset + dp(6),
            bottom = bottom + targetHeight + dp(6)
        )
        setFrameLayoutParams(
            binding.attachmentMenu,
            start = startInset,
            end = endInset,
            bottom = if (keyboardVisible) keyboardInset else bottomInset
        )
    }

    private fun setFrameLayoutParams(
        view: View,
        start: Int? = null,
        end: Int? = null,
        bottom: Int? = null,
        height: Int? = null
    ) {
        val params = view.layoutParams as LayoutParams
        var changed = false
        if (start != null && params.marginStart != start) {
            params.marginStart = start
            changed = true
        }
        if (end != null && params.marginEnd != end) {
            params.marginEnd = end
            changed = true
        }
        if (bottom != null && params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            params.gravity = params.gravity.takeIf { it != Gravity.NO_GRAVITY } ?: Gravity.BOTTOM
            changed = true
        }
        if (height != null && params.height != height) {
            params.height = height
            changed = true
        }
        if (changed) {
            view.layoutParams = params
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
