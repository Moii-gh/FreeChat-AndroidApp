package com.example.chatapp.assistant

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.setHapticClickListener

class DigitalAssistantOverlayView(
    context: Context,
    private val viewModel: DigitalAssistantViewModel,
    private val host: DigitalAssistantHost
) : FrameLayout(context) {
    private val dimView = View(context)
    private val actionsColumn = LinearLayout(context)
    private val responseCard = LinearLayout(context)
    private val responseScroll = ScrollView(context)
    private val responseContent = LinearLayout(context)
    private val userBubble = TextView(context)
    private val badge = TextView(context)
    private val answerText = TextView(context)
    private val errorActions = LinearLayout(context)
    private val bottomPanel = LinearLayout(context)
    private val previewContainer = FrameLayout(context)
    private val previewImage = ImageView(context)
    private val removePreview = ImageButton(context)
    private val inputField = EditText(context)
    private val sendButton = ImageButton(context)
    private val confirmPanel = LinearLayout(context)
    private var bottomInset = 0
    private var latestState = DigitalAssistantState()

    private val listener: (DigitalAssistantState) -> Unit = { state ->
        latestState = state
        render(state)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false
        buildLayout()
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            bottomInset = insets.systemWindowInsetBottom
            applyBottomInsets()
            insets
        }
        viewModel.addListener(listener)
        post {
            requestFocus()
            playIntroAnimation()
        }
    }

    override fun onDetachedFromWindow() {
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

    private fun buildLayout() {
        dimView.setBackgroundColor(Color.argb(120, 0, 0, 0))
        dimView.alpha = 0f
        dimView.setOnClickListener {
            if (!latestState.isGenerating) requestClose()
        }
        addView(dimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildResponseCard()
        buildActions()
        buildBottomPanel()
        buildConfirmPanel()
    }

    private fun buildActions() {
        actionsColumn.orientation = LinearLayout.VERTICAL
        actionsColumn.gravity = Gravity.START
        actionsColumn.alpha = 0f
        actionsColumn.translationY = dp(14).toFloat()

        val openButton = pillButton(LocaleHelper.getString(context, "digital_assistant_open_freechat") + "  ›")
        val askScreenButton = pillButton(LocaleHelper.getString(context, "digital_assistant_ask_screen"))
        openButton.setHapticClickListener {
            val token = viewModel.createHandoff(context, inputField.text?.toString().orEmpty())
            host.openFreeChat(token)
        }
        askScreenButton.setHapticClickListener {
            requestOneShotScreenAttachment()
        }

        actionsColumn.addView(openButton)
        actionsColumn.addView(askScreenButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply { topMargin = dp(8) })
        addView(actionsColumn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dp(24)
            bottomMargin = dp(92)
        })
    }

    private fun requestOneShotScreenAttachment() {
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

    private fun buildBottomPanel() {
        bottomPanel.orientation = LinearLayout.VERTICAL
        bottomPanel.background = rounded("#D9101010", dp(30), "#33FFFFFF", 1)
        bottomPanel.elevation = dp(18).toFloat()
        bottomPanel.setPadding(dp(8), dp(8), dp(8), dp(8))
        bottomPanel.scaleX = 0.82f
        bottomPanel.scaleY = 0.82f
        bottomPanel.alpha = 0f

        previewContainer.isGone = true
        previewContainer.setPadding(dp(4), dp(4), dp(4), dp(8))
        previewImage.scaleType = ImageView.ScaleType.CENTER_CROP
        previewImage.background = rounded("#202020", dp(12), "#33FFFFFF", 1)
        previewImage.clipToOutline = true
        previewContainer.addView(previewImage, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.START))
        removePreview.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        removePreview.setColorFilter(Color.WHITE)
        removePreview.background = rounded("#AA1C1C1E", dp(12), "#33FFFFFF", 1)
        removePreview.setPadding(dp(4), dp(4), dp(4), dp(4))
        removePreview.setHapticClickListener { viewModel.clearAttachment() }
        previewContainer.addView(removePreview, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        bottomPanel.addView(previewContainer)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val plus = ImageButton(context).apply {
            setImageResource(R.drawable.ic_plus)
            setColorFilter(Color.parseColor("#BDBDBD"))
            background = rounded("#553A3A3C", dp(22), "#33FFFFFF", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setHapticClickListener {
                host.showMessage(LocaleHelper.getString(context, "content_desc_add_attachment"))
            }
        }
        row.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })

        val inputCapsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded("#B8141414", dp(24), "#33FFFFFF", 1)
            setPadding(dp(10), 0, dp(6), 0)
        }
        inputField.apply {
            setTextColor(Color.parseColor("#F2F2F2"))
            setHintTextColor(Color.parseColor("#A8A8A8"))
            hint = LocaleHelper.getString(context, "main_panel_input")
            textSize = 16f
            maxLines = 4
            minLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            doAfterTextChanged { updateSendButton() }
        }
        inputCapsule.addView(inputField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mic = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.parseColor("#A8A8A8"))
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        inputCapsule.addView(mic, LinearLayout.LayoutParams(dp(30), dp(30)))
        row.addView(inputCapsule, LinearLayout.LayoutParams(0, dp(44), 1f))

        sendButton.apply {
            setImageResource(R.drawable.ic_send_arrow_up)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setHapticClickListener {
                if (viewModel.submit(inputField.text?.toString().orEmpty())) {
                    inputField.text?.clear()
                }
            }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        bottomPanel.addView(row)

        addView(bottomPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(12)
        })
        updateSendButton()
    }

    private fun buildResponseCard() {
        responseCard.orientation = LinearLayout.VERTICAL
        responseCard.background = rounded("#F20B0B0B", dp(32), "#2EFFFFFF", 1)
        responseCard.elevation = dp(20).toFloat()
        responseCard.setPadding(dp(22), dp(16), dp(22), dp(16))
        responseCard.isGone = true

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = LocaleHelper.getString(context, "app_brand") + "›"
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 16f
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#A8A8A8"))
            background = null
            setHapticClickListener { requestClose() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))
        responseCard.addView(header)

        userBubble.setTextColor(Color.WHITE)
        userBubble.textSize = 14f
        userBubble.background = rounded("#2B2B2D", dp(20), null, 0)
        userBubble.setPadding(dp(16), dp(8), dp(16), dp(8))
        responseCard.addView(userBubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            topMargin = dp(10)
        })

        badge.text = LocaleHelper.getString(context, "digital_assistant_screen_analysis")
        badge.setTextColor(Color.parseColor("#202020"))
        badge.textSize = 12f
        badge.background = rounded("#F2F2F2", dp(14), null, 0)
        badge.setPadding(dp(10), dp(4), dp(10), dp(4))
        responseCard.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        responseContent.orientation = LinearLayout.VERTICAL
        answerText.setTextColor(Color.parseColor("#F2F2F2"))
        answerText.textSize = 15f
        answerText.setLineSpacing(dp(2).toFloat(), 1f)
        responseContent.addView(answerText)
        responseContent.addView(actionIcons(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(34)
        ).apply { topMargin = dp(8) })
        buildErrorActions()
        responseContent.addView(errorActions)
        responseScroll.addView(responseContent)
        responseCard.addView(responseScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(12) })

        addView(responseCard, LayoutParams(LayoutParams.MATCH_PARENT, dp(330)).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(10)
            marginEnd = dp(10)
            bottomMargin = dp(166)
        })
    }

    private fun buildErrorActions() {
        errorActions.orientation = LinearLayout.HORIZONTAL
        errorActions.isGone = true
        val retry = pillButton(LocaleHelper.getString(context, "digital_assistant_retry"))
        val open = pillButton(LocaleHelper.getString(context, "digital_assistant_open_freechat"))
        retry.setHapticClickListener { viewModel.retry() }
        open.setHapticClickListener {
            val token = viewModel.createHandoff(context, inputField.text?.toString().orEmpty())
            host.openFreeChat(token)
        }
        errorActions.addView(retry)
        errorActions.addView(open, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(40)
        ).apply { marginStart = dp(8) })
    }

    private fun buildConfirmPanel() {
        confirmPanel.orientation = LinearLayout.VERTICAL
        confirmPanel.gravity = Gravity.CENTER_HORIZONTAL
        confirmPanel.background = rounded("#F2161616", dp(22), "#33FFFFFF", 1)
        confirmPanel.setPadding(dp(18), dp(14), dp(18), dp(14))
        confirmPanel.elevation = dp(22).toFloat()
        confirmPanel.isGone = true

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            text = LocaleHelper.getString(context, "digital_assistant_close_generating_message")
        }
        confirmPanel.addView(text)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancel = pillButton(LocaleHelper.getString(context, "button_cancel"))
        val close = pillButton(LocaleHelper.getString(context, "digital_assistant_close"))
        cancel.setHapticClickListener { confirmPanel.isGone = true }
        close.setHapticClickListener { host.closeAssistant(force = true) }
        row.addView(cancel)
        row.addView(close, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(40)
        ).apply { marginStart = dp(8) })
        confirmPanel.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        addView(confirmPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            marginStart = dp(24)
            marginEnd = dp(24)
        })
    }

    private fun actionIcons(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(R.drawable.ic_thumb_up, R.drawable.ic_thumb_down, R.drawable.ic_share, R.drawable.ic_more_vertical)
            .forEach { icon ->
                row.addView(ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(Color.parseColor("#A8A8A8"))
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
            }
        return row
    }

    private fun render(state: DigitalAssistantState) {
        val hasAttachment = state.attachment != null
        previewContainer.isVisible = hasAttachment
        if (hasAttachment) {
            val bytes = Base64.decode(state.attachment?.base64Data, Base64.DEFAULT)
            previewImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } else {
            previewImage.setImageDrawable(null)
        }
        updateSendButton()

        val hasResponse = state.responseStatus != AssistantResponseStatus.IDLE
        responseCard.isVisible = hasResponse
        if (hasResponse) {
            userBubble.text = state.userQuestion
            badge.isVisible = state.isScreenAnalysis
            errorActions.isVisible = state.responseStatus == AssistantResponseStatus.ERROR
            answerText.text = when (state.responseStatus) {
                AssistantResponseStatus.LOADING -> {
                    state.answerText.ifBlank { LocaleHelper.getString(context, "digital_assistant_thinking") }
                }
                AssistantResponseStatus.ERROR -> {
                    state.errorText ?: LocaleHelper.getString(context, "digital_assistant_response_error")
                }
                else -> state.answerText
            }
        }
    }

    private fun updateSendButton() {
        val hasInput = inputField.text?.isNotBlank() == true || latestState.attachment != null
        sendButton.isEnabled = hasInput && !latestState.isGenerating
        sendButton.alpha = if (sendButton.isEnabled) 1f else 0.45f
        sendButton.background = rounded(
            if (sendButton.isEnabled) "#E6F2F2F2" else "#663A3A3C",
            dp(22),
            null,
            0
        )
        sendButton.setColorFilter(if (sendButton.isEnabled) Color.BLACK else Color.parseColor("#A8A8A8"))
    }

    private fun requestClose() {
        if (latestState.isGenerating) {
            confirmPanel.isVisible = true
        } else {
            host.closeAssistant(force = false)
        }
    }

    private fun playIntroAnimation() {
        dimView.animate().alpha(1f).setDuration(220L).start()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(bottomPanel, SCALE_X, 0.82f, 1f),
                ObjectAnimator.ofFloat(bottomPanel, SCALE_Y, 0.82f, 1f),
                ObjectAnimator.ofFloat(bottomPanel, ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(bottomPanel, TRANSLATION_Y, dp(42).toFloat(), 0f)
            )
            duration = 360L
            interpolator = DecelerateInterpolator(1.6f)
            start()
        }
        actionsColumn.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(140L)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun applyBottomInsets() {
        (bottomPanel.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = dp(12) + bottomInset
            bottomPanel.layoutParams = it
        }
        (actionsColumn.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = dp(92) + bottomInset
            actionsColumn.layoutParams = it
        }
        (responseCard.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = dp(166) + bottomInset
            responseCard.layoutParams = it
        }
    }

    private fun pillButton(text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#202020"))
            textSize = 14f
            gravity = Gravity.CENTER
            background = rounded("#F2F2F2", dp(22), null, 0)
            setPadding(dp(18), 0, dp(18), 0)
            minHeight = dp(40)
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = true
        }

    private fun rounded(color: String, radius: Int, strokeColor: String?, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(color))
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(dp(strokeWidth), Color.parseColor(strokeColor))
            }
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
