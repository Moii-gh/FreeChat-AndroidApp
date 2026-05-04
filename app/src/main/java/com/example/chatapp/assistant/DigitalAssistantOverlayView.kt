package com.example.chatapp.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.databinding.ViewDigitalAssistantOverlayBinding
import com.example.chatapp.util.setHapticClickListener
import kotlin.math.max
import kotlin.math.min

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
    private val panelOut = DecelerateInterpolator(1.6f)
    private val typingAnimators = mutableListOf<Animator>()

    private var latestState = DigitalAssistantState()
    private var lastHasAttachment = false
    private var lastAttachmentId: String? = null
    private var lastHasResponse = false
    private var closingStarted = false
    private var attachmentMenuVisible = false
    private var bottomInset = 0
    private var topInset = 0
    private var startInset = 0
    private var endInset = 0
    private var keyboardInset = 0
    private var keyboardVisible = false

    private val listener: (DigitalAssistantState) -> Unit = { state ->
        latestState = state
        render(state)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false
        configureStaticText()
        configureInput()
        configureClicks()
        configureLayoutAnimation()
        binding.bottomPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateFloatingPositions()
        }
        setOnApplyWindowInsetsListener { _, insets ->
            updateInsets(insets)
            insets
        }
        viewModel.addListener(listener)
        post {
            requestFocus()
            updateFloatingPositions()
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
        binding.assistantTitle.text = LocaleHelper.getString(context, "app_brand")
        binding.openFreeChatButton.text = LocaleHelper.getString(context, "digital_assistant_open_freechat")
        binding.translateScreenButton.text = LocaleHelper.getString(context, "digital_assistant_translate_screen")
        binding.askScreenButton.text = LocaleHelper.getString(context, "digital_assistant_ask_screen")
        binding.menuCameraText.text = LocaleHelper.getString(context, "button_camera")
        binding.menuPhotoText.text = LocaleHelper.getString(context, "button_photo")
        binding.menuFilesText.text = LocaleHelper.getString(context, "button_files")
        binding.screenBadge.text = LocaleHelper.getString(context, "digital_assistant_screen_analysis")
        binding.retryButton.text = LocaleHelper.getString(context, "digital_assistant_retry")
        binding.openFromErrorButton.text = LocaleHelper.getString(context, "digital_assistant_open_freechat")
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
            binding.retryButton,
            binding.openFromErrorButton,
            binding.cancelCloseButton,
            binding.confirmCloseButton
        ).forEach { button ->
            button.isClickable = true
            button.isFocusable = true
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
        binding.openFreeChatButton.setHapticClickListener { openFreeChat() }
        binding.openFromErrorButton.setHapticClickListener { openFreeChat() }
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
        binding.retryButton.setHapticClickListener { viewModel.retry() }
        binding.cancelCloseButton.setHapticClickListener { binding.confirmPanel.isGone = true }
        binding.confirmCloseButton.setHapticClickListener { closeWithAnimation(force = true) }
    }

    private fun configureLayoutAnimation() {
        binding.bottomPanel.layoutTransition = LayoutTransition().apply {
            setDuration(240L)
            setInterpolator(LayoutTransition.CHANGE_APPEARING, smoothOut)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, smoothOut)
            setInterpolator(LayoutTransition.APPEARING, smoothOut)
            setInterpolator(LayoutTransition.DISAPPEARING, smoothOut)
        }
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
        renderAttachment(state)
        renderResponse(state)
        updateSendButton()
        updateFloatingPositions()
    }

    private fun renderAttachment(state: DigitalAssistantState) {
        val attachment = state.attachment
        val hasAttachment = attachment != null
        val attachmentId = attachment?.cacheFilePath ?: attachment?.fileName

        if (hasAttachment && attachmentId != lastAttachmentId) {
            val bytes = runCatching {
                Base64.decode(attachment?.base64Data.orEmpty(), Base64.DEFAULT)
            }.getOrNull()
            val bitmap = bytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            if (bitmap != null) {
                binding.previewImage.setPadding(0, 0, 0, 0)
                binding.previewImage.clearColorFilter()
                binding.previewImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                binding.previewImage.setImageBitmap(bitmap)
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

    private fun renderResponse(state: DigitalAssistantState) {
        val hasResponse = state.responseStatus != AssistantResponseStatus.IDLE
        if (hasResponse && !lastHasResponse) {
            showResponseCard()
        } else if (!hasResponse && lastHasResponse) {
            hideResponseCard()
        }
        setActionButtonsVisible(!hasResponse && state.attachment == null && !closingStarted)

        if (!hasResponse) {
            stopTypingDots()
            lastHasResponse = false
            return
        }

        binding.userBubble.text = state.userQuestion
        binding.userBubble.isVisible = state.userQuestion.isNotBlank()
        binding.screenBadge.isVisible = state.isScreenAnalysis
        binding.errorActions.isVisible = state.responseStatus == AssistantResponseStatus.ERROR

        val answer = when (state.responseStatus) {
            AssistantResponseStatus.LOADING -> state.answerText
            AssistantResponseStatus.ERROR -> {
                state.errorText ?: LocaleHelper.getString(context, "digital_assistant_response_error")
            }
            else -> state.answerText
        }
        val showDots = state.responseStatus == AssistantResponseStatus.LOADING && answer.isBlank()
        binding.answerText.text = answer
        binding.answerText.isVisible = !showDots
        binding.typingDots.isVisible = showDots
        binding.answerActions.isVisible = !showDots && state.responseStatus != AssistantResponseStatus.ERROR
        if (showDots) {
            startTypingDots()
        } else {
            stopTypingDots()
        }
        binding.responseScroll.post { binding.responseScroll.fullScroll(View.FOCUS_DOWN) }
        lastHasResponse = true
    }

    private fun showResponseCard() {
        updateFloatingPositions()
        binding.responseCard.apply {
            isVisible = true
            alpha = 0f
            translationY = dp(46).toFloat()
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(310L)
                .setInterpolator(smoothOut)
                .start()
        }
    }

    private fun hideResponseCard() {
        binding.responseCard.animate()
            .alpha(0f)
            .translationY(dp(42).toFloat())
            .setDuration(220L)
            .setInterpolator(smoothOut)
            .withEndAction {
                if (latestState.responseStatus == AssistantResponseStatus.IDLE) {
                    binding.responseCard.isGone = true
                    binding.responseCard.translationY = dp(34).toFloat()
                }
            }
            .start()
    }

    private fun setActionButtonsVisible(visible: Boolean) {
        binding.actionsColumn.animate().cancel()
        if (visible) {
            binding.actionsColumn.isVisible = true
            binding.actionsColumn.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(smoothOut)
                .start()
        } else {
            binding.actionsColumn.animate()
                .alpha(0f)
                .translationY(dp(14).toFloat())
                .setDuration(140L)
                .setInterpolator(smoothOut)
                .withEndAction {
                    if (latestState.responseStatus != AssistantResponseStatus.IDLE || closingStarted) {
                        binding.actionsColumn.isGone = true
                    }
                }
                .start()
        }
    }

    private fun updateSendButton() {
        val enabled = (binding.inputField.text?.isNotBlank() == true || latestState.attachment != null) &&
            !latestState.isGenerating
        binding.sendButton.isEnabled = enabled
        binding.sendButton.alpha = 1f
        binding.sendButton.setColorFilter(if (enabled) Color.BLACK else Color.parseColor("#1F1F1F"))
    }

    private fun startTypingDots() {
        if (typingAnimators.isNotEmpty()) return
        listOf(binding.typingDot1, binding.typingDot2, binding.typingDot3).forEachIndexed { index, dot ->
            dot.alpha = 0.35f
            val alpha = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.35f, 1f, 0.35f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                startDelay = index * 140L
                interpolator = smoothOut
            }
            val lift = ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(2).toFloat(), 0f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                startDelay = index * 140L
                interpolator = smoothOut
            }
            AnimatorSet().apply {
                playTogether(alpha, lift)
                start()
                typingAnimators += this
            }
        }
    }

    private fun stopTypingDots() {
        typingAnimators.forEach { it.cancel() }
        typingAnimators.clear()
        listOf(binding.typingDot1, binding.typingDot2, binding.typingDot3).forEach {
            it.alpha = 0.35f
            it.translationY = 0f
        }
    }

    private fun playIntroAnimation() {
        binding.dimView.animate()
            .alpha(1f)
            .setDuration(210L)
            .setInterpolator(smoothOut)
            .start()

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.bottomPanel, View.SCALE_X, 0.82f, 1f),
                ObjectAnimator.ofFloat(binding.bottomPanel, View.SCALE_Y, 0.82f, 1f),
                ObjectAnimator.ofFloat(binding.bottomPanel, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.bottomPanel, View.TRANSLATION_Y, dp(42).toFloat(), 0f)
            )
            duration = 360L
            interpolator = panelOut
            start()
        }

        binding.actionsColumn.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(145L)
            .setDuration(210L)
            .setInterpolator(smoothOut)
            .start()
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
        binding.responseCard.animate()
            .alpha(0f)
            .translationY(dp(50).toFloat())
            .setDuration(220L)
            .setInterpolator(smoothOut)
            .start()
        binding.actionsColumn.animate()
            .alpha(0f)
            .translationY(dp(14).toFloat())
            .setDuration(150L)
            .setInterpolator(smoothOut)
            .start()
        binding.bottomPanel.animate()
            .alpha(0f)
            .scaleX(0.84f)
            .scaleY(0.84f)
            .translationY(dp(42).toFloat())
            .setDuration(230L)
            .setInterpolator(smoothOut)
            .start()
        binding.dimView.animate()
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(smoothOut)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.dimView.animate().setListener(null)
                    host.closeAssistant(force = force)
                }
            })
            .start()
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
        val panelHeight = if (binding.bottomPanel.height > 0) {
            binding.bottomPanel.height
        } else if (latestState.attachment != null) {
            dp(180)
        } else {
            dp(68)
        }
        val bottom = if (keyboardVisible) {
            keyboardInset
        } else {
            bottomInset + dp(8)
        }
        setFrameLayoutParams(
            binding.bottomPanel,
            start = startInset + dp(6),
            end = endInset + dp(6),
            bottom = bottom
        )
        setFrameLayoutParams(
            binding.actionsColumn,
            start = startInset + dp(6),
            bottom = bottom + panelHeight + dp(6)
        )
        val responseBottom = bottom + panelHeight + dp(10)
        val availableForCard = if (height > 0) {
            height - responseBottom - topInset - dp(18)
        } else {
            dp(284)
        }
        val cardHeight = min(dp(310), max(dp(220), availableForCard))
        setFrameLayoutParams(
            binding.responseCard,
            start = startInset + dp(6),
            end = endInset + dp(6),
            bottom = responseBottom,
            height = cardHeight
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
