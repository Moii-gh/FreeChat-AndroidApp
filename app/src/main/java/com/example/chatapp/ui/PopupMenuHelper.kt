package com.example.chatapp.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.chatapp.ChatEntity
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.dpToPx

/**
 * Помощник для создания popup-меню и диалогов.
 * Управляет: контекстным меню чата (long press), меню текущего чата (три точки),
 * диалогом переименования.
 */
class PopupMenuHelper(
    private val activity: Activity,
    private val onRename: (ChatEntity, String, () -> Unit) -> Unit,
    private val onTogglePin: (ChatEntity) -> Unit,
    private val onShare: (ChatEntity) -> Unit,
    private val onSearchChat: (ChatEntity) -> Unit,
    private val onRevokeShares: (ChatEntity) -> Unit,
    private val onDelete: (ChatEntity) -> Unit,
    private val onRegenerate: ((AssistantMessageWrapper) -> Unit)? = null,
    private val onEditUserMessage: ((Int, String) -> Unit)? = null
) {
    private val standardMenuWidth by lazy { 280.dpToPx() }

    /**
     * Popup при long press на элемент чата в drawer.
     * Показывает реплику элемента + меню поверх затемнённого фона.
     */
    fun showChatPopupMenu(anchorView: View, chat: ChatEntity) {
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        anchorView.visibility = View.INVISIBLE
        dialog.setOnDismissListener {
            anchorView.visibility = View.VISIBLE
        }

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#26000000"))
            setOnClickListener { dialog.dismiss() }
        }

        val location = IntArray(2)
        anchorView.getLocationInWindow(location)
        val x = location[0]
        var y = location[1]

        // Коррекция смещения от status bar для полупрозрачного диалога
        val rectangle = android.graphics.Rect()
        activity.window.decorView.getWindowVisibleDisplayFrame(rectangle)
        if (rectangle.top > 0) y -= rectangle.top

        // Реплика элемента чата
        val replica = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 13.dpToPx(), 16.dpToPx(), 13.dpToPx())
            background = ContextCompat.getDrawable(activity, R.drawable.chat_item_highlight_bg)

            if (chat.isPinned) {
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_pin)
                    setColorFilter(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx()).apply {
                        marginEnd = 8.dpToPx()
                    }
                })
            }
            addView(TextView(activity).apply {
                text = chat.title
                setTextColor(Color.WHITE)
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            layoutParams = FrameLayout.LayoutParams(anchorView.width, anchorView.height).apply {
                leftMargin = x
                topMargin = y
            }
            alpha = 0f
            scaleX = 0.95f
            scaleY = 0.95f
        }

        val menuLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.popup_menu_bg)
            elevation = 28f
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                outlineSpotShadowColor = Color.parseColor("#4D000000")
                outlineAmbientShadowColor = Color.parseColor("#4D000000")
            }
            setPadding(10.dpToPx(), 12.dpToPx(), 10.dpToPx(), 12.dpToPx())

            layoutParams = FrameLayout.LayoutParams(standardMenuWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x + 72.dpToPx()
            }
            alpha = 0f
            scaleX = 0.95f
            scaleY = 0.95f
        }

        // Пункты меню
        menuLayout.addView(createPopupMenuItem(R.drawable.ic_rename, LocaleHelper.getString(activity, "menu_rename"), Color.WHITE) {
            dialog.dismiss()
            showRenameDialog(chat)
        })

        val pinText = if (chat.isPinned) {
            LocaleHelper.getString(activity, "menu_unpin_chat")
        } else {
            LocaleHelper.getString(activity, "menu_pin_chat")
        }
        menuLayout.addView(createPopupMenuItem(R.drawable.ic_pin, pinText, Color.WHITE) {
            dialog.dismiss()
            onTogglePin(chat)
        })

        menuLayout.addView(createPopupMenuItem(R.drawable.ic_share, LocaleHelper.getString(activity, "share"), Color.WHITE) {
            dialog.dismiss()
            onShare(chat)
        })

        menuLayout.addView(createPopupMenuItem(R.drawable.ic_home, LocaleHelper.getString(activity, "menu_add_to_home_screen"), Color.WHITE) {
            dialog.dismiss()
            pinChatShortcut(activity, chat)
        })

        menuLayout.addView(createPopupMenuItem(R.drawable.ic_delete, LocaleHelper.getString(activity, "button_delete"), Color.parseColor("#FF453A")) {
            dialog.dismiss()
            onDelete(chat)
        })

        container.addView(replica)
        container.addView(menuLayout)
        dialog.setContentView(container)

        // Позиционирование меню относительно реплики
        menuLayout.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                menuLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val menuHeight = menuLayout.height
                val menuWidth = menuLayout.width
                val containerHeight = container.height

                val lp = menuLayout.layoutParams as FrameLayout.LayoutParams
                val minLeft = 16.dpToPx()
                val preferredLeft = x + 72.dpToPx()
                val maxLeft = (container.width - menuWidth - 16.dpToPx()).coerceAtLeast(minLeft)
                lp.leftMargin = preferredLeft.coerceIn(minLeft, maxLeft)

                if (y + replica.height + menuHeight + 12.dpToPx() > containerHeight) {
                    lp.topMargin = (y - menuHeight - 2.dpToPx()).coerceAtLeast(8.dpToPx())
                    menuLayout.pivotY = menuHeight.toFloat()
                } else {
                    lp.topMargin = y + replica.height + 2.dpToPx()
                    menuLayout.pivotY = 0f
                }
                menuLayout.layoutParams = lp
                menuLayout.pivotX = 0f

                menuLayout.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            }
        })

        replica.animate().alpha(1f).scaleX(1.02f).scaleY(1.02f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = 45.dpToPx()
            }
        }
        dialog.show()
    }

    /**
     * Popup при нажатии на три точки в заголовке текущего чата.
     */
    fun showCurrentChatOptionsMenu(anchorView: View, chat: ChatEntity) {
        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.popup_menu_translucent_bg)
            elevation = 28f
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                outlineSpotShadowColor = Color.parseColor("#4D000000")
                outlineAmbientShadowColor = Color.parseColor("#4D000000")
            }
            setPadding(10.dpToPx(), 12.dpToPx(), 10.dpToPx(), 12.dpToPx())
        }

        val popupWindow = PopupWindow(
            popupView,
            standardMenuWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 28f
            isOutsideTouchable = true
        }

        // Заголовок
        val displayTitle = if (chat.title.length > 25) chat.title.substring(0, 25) + "..." else chat.title
        popupView.addView(TextView(activity).apply {
            text = displayTitle
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setPadding(18.dpToPx(), 14.dpToPx(), 18.dpToPx(), 8.dpToPx())
        })

        // Переименовать
        popupView.addView(createPopupMenuItem(R.drawable.ic_rename, LocaleHelper.getString(activity, "menu_rename"), Color.WHITE) {
            popupWindow.dismiss()
            showRenameDialog(chat)
        })

        popupView.addView(createPopupMenuItem(R.drawable.ic_search, LocaleHelper.getString(activity, "menu_search_chat"), Color.WHITE) {
            popupWindow.dismiss()
            onSearchChat(chat)
        })

        // Закрепить/Открепить
        val pinText = if (chat.isPinned) {
            LocaleHelper.getString(activity, "menu_unpin_chat")
        } else {
            LocaleHelper.getString(activity, "menu_pin_chat")
        }
        popupView.addView(createPopupMenuItem(R.drawable.ic_pin, pinText, Color.WHITE) {
            popupWindow.dismiss()
            onTogglePin(chat)
        })

        popupView.addView(createPopupMenuItem(R.drawable.ic_share, LocaleHelper.getString(activity, "share"), Color.WHITE) {
            popupWindow.dismiss()
            onShare(chat)
        })

        popupView.addView(createPopupMenuItem(R.drawable.ic_home, LocaleHelper.getString(activity, "menu_add_to_home_screen"), Color.WHITE) {
            popupWindow.dismiss()
            pinChatShortcut(activity, chat)
        })


        // Удалить
        popupView.addView(createPopupMenuItem(R.drawable.ic_delete, LocaleHelper.getString(activity, "button_delete"), Color.parseColor("#FF453A")) {
            popupWindow.dismiss()
            onDelete(chat)
        })

        popupView.alpha = 0f
        popupView.scaleX = 0.95f
        popupView.scaleY = 0.95f

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val x = screenWidth - standardMenuWidth - 12.dpToPx()
        val y = (location[1] - 12.dpToPx()).coerceAtLeast(16.dpToPx())

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, x, y)
        try {
            val container = popupView.rootView
            val p = container.layoutParams as? WindowManager.LayoutParams
            if (p != null) {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                p.dimAmount = 0.10f
                val wm = activity.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(container, p)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        popupView.pivotX = standardMenuWidth.toFloat()
        popupView.pivotY = 0f
        popupView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    /**
     * Popup для ответа ассистента (регенерация).
     */
    fun showAssistantMessageOptionsMenu(anchorView: View, wrapper: AssistantMessageWrapper) {
        val imageUrl = AssistantMessageWrapper.extractImageUrl(wrapper.rawText)
        val hasImageActions = AssistantMessageWrapper.isRenderableImageUrl(imageUrl)

        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                activity,
                if (hasImageActions) R.drawable.bg_popup_menu_image_options else R.drawable.bg_popup_menu_pill
            )
            elevation = 24f
            val verticalPadding = if (hasImageActions) 8.dpToPx() else 4.dpToPx()
            setPadding(12.dpToPx(), verticalPadding, 12.dpToPx(), verticalPadding)
        }

        val popupWindow = PopupWindow(
            popupView,
            standardMenuWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 24f
            isOutsideTouchable = true
        }

        popupView.addView(createPopupMenuItem(
            android.R.drawable.ic_popup_sync,
            LocaleHelper.getString(activity, "menu_regenerate"),
            Color.WHITE,
            compact = true
        ) {
            popupWindow.dismiss()
            onRegenerate?.invoke(wrapper)
        })

        if (hasImageActions) {
            popupView.addView(createPopupMenuItem(
                R.drawable.ic_share,
                LocaleHelper.getString(activity, "share"),
                Color.WHITE,
                compact = true
            ) {
                popupWindow.dismiss()
                FileUtils.shareImageFromUrl(activity, imageUrl)
            })

            popupView.addView(createPopupMenuItem(
                R.drawable.ic_download_simple,
                LocaleHelper.getString(activity, "button_save"),
                Color.WHITE,
                compact = true
            ) {
                popupWindow.dismiss()
                FileUtils.saveImageFromUrl(activity, imageUrl)
            })
        }

        popupView.alpha = 0f
        popupView.scaleX = 0.92f
        popupView.scaleY = 0.92f

        val xOffset = anchorView.width - standardMenuWidth
        popupWindow.showAsDropDown(anchorView, xOffset, 4.dpToPx())

        popupView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
    }

    fun showUserMessageOptionsMenu(anchorView: View, message: String, historyIndex: Int) {
        val userMenuWidth = 280.dpToPx()
        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.popup_menu_user_bg)
            elevation = 24f
            setPadding(6.dpToPx(), 8.dpToPx(), 6.dpToPx(), 8.dpToPx())
        }

        val popupWindow = PopupWindow(
            popupView,
            userMenuWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 24f
            isOutsideTouchable = true
        }

        // Заголовок времени (Сегодня, h:mm a)
        val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val formattedTime = timeFormat.format(java.util.Date())
        val todayStr = if (java.util.Locale.getDefault().language == "ru" || java.util.Locale.getDefault().language == "uk") "Сегодня" else "Today"
        val headerText = "$todayStr, $formattedTime"

        popupView.addView(TextView(activity).apply {
            text = headerText
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 8.dpToPx())
        })

        // 1. Копировать
        popupView.addView(createUserPopupMenuItem(R.drawable.ic_copy, LocaleHelper.getString(activity, "menu_copy_text"), Color.WHITE) {
            popupWindow.dismiss()
            FileUtils.copyToClipboard(activity, message)
        })

        // 2. Поделиться
        popupView.addView(createUserPopupMenuItem(R.drawable.ic_share, LocaleHelper.getString(activity, "share"), Color.WHITE) {
            popupWindow.dismiss()
            FileUtils.shareText(activity, message)
        })

        // 3. Редактировать сообщение
        popupView.addView(createUserPopupMenuItem(R.drawable.ic_pen, LocaleHelper.getString(activity, "menu_edit_message"), Color.WHITE) {
            popupWindow.dismiss()
            onEditUserMessage?.invoke(historyIndex, message)
        })

        popupView.alpha = 0f
        popupView.scaleX = 0.92f
        popupView.scaleY = 0.92f

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val screenHeight = activity.resources.displayMetrics.heightPixels
        val preferredX = location[0] + anchorView.width - userMenuWidth
        val x = preferredX.coerceIn(12.dpToPx(), screenWidth - userMenuWidth - 12.dpToPx())
        val y = (location[1] + anchorView.height + 6.dpToPx()).coerceAtMost(screenHeight - 180.dpToPx())

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, x, y)

        popupView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
    }

    /** Создаёт один пункт popup-меню */
    private fun createPopupMenuItem(
        iconRes: Int,
        text: String,
        tintColor: Int,
        compact: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            
            val pHorizontal = if (compact) 12.dpToPx() else 16.dpToPx()
            val pVertical = if (compact) 6.dpToPx() else 10.dpToPx()
            setPadding(pHorizontal, pVertical, pHorizontal, pVertical)
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!compact) {
                    topMargin = 1.dpToPx()
                    bottomMargin = 1.dpToPx()
                }
            }
            minimumHeight = if (compact) 36.dpToPx() else 48.dpToPx()
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            background = ContextCompat.getDrawable(activity, outValue.resourceId)

            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(tintColor)
                layoutParams = LinearLayout.LayoutParams(
                    if (compact) 16.dpToPx() else 24.dpToPx(),
                    if (compact) 16.dpToPx() else 24.dpToPx()
                )
            })

            addView(TextView(activity).apply {
                this.text = text
                setTextColor(tintColor)
                textSize = if (compact) 14f else 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(if (compact) 10.dpToPx() else 14.dpToPx(), 0, 0, 0)
            })

            setOnClickListener { onClick() }
        }
    }

    private fun createUserPopupMenuItem(
        iconRes: Int,
        text: String,
        tintColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            
            val pHorizontal = 16.dpToPx()
            val pVertical = 10.dpToPx()
            setPadding(pHorizontal, pVertical, pHorizontal, pVertical)
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 1.dpToPx()
                bottomMargin = 1.dpToPx()
            }
            minimumHeight = 44.dpToPx()
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            background = ContextCompat.getDrawable(activity, outValue.resourceId)

            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(tintColor)
                layoutParams = LinearLayout.LayoutParams(20.dpToPx(), 20.dpToPx())
            })

            addView(TextView(activity).apply {
                this.text = text
                setTextColor(tintColor)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(14.dpToPx(), 0, 0, 0)
            })

            setOnClickListener { onClick() }
        }
    }

    /** Диалог переименования чата */
    private fun showRenameDialog(chat: ChatEntity) {
        showTextInputDialog(
            initialText = chat.title,
            hintText = LocaleHelper.getString(activity, "dialog_rename_hint"),
            widthFraction = 0.82f,
            configureInput = {
                setSingleLine()
                gravity = Gravity.CENTER
                selectAll()
            },
            onConfirmed = { newTitle, complete -> onRename(chat, newTitle, complete) }
        )
    }

    private fun showEditUserMessageDialog(originalText: String, onEdited: (String) -> Unit) {
        showTextInputDialog(
            initialText = originalText,
            hintText = LocaleHelper.getString(activity, "menu_edit_message"),
            widthFraction = 0.92f,
            configureInput = {
                minLines = 3
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
                setSelection(text?.length ?: 0)
            },
            onConfirmed = { editedText, complete ->
                onEdited(editedText)
                complete()
            }
        )
    }

    private fun showTextInputDialog(
        initialText: String,
        hintText: String,
        widthFraction: Float,
        configureInput: EditText.() -> Unit,
        onConfirmed: (String, () -> Unit) -> Unit
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        var isSaving = false
        var isDismissing = false
        var enterAnimationStarted = false

        val root = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        val scrim = View(activity).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            alpha = 0f
            isClickable = true
        }

        val dialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.rename_dialog_glass_bg)
            elevation = 28f
            setPadding(20.dpToPx(), 22.dpToPx(), 20.dpToPx(), 18.dpToPx())
            setOnClickListener { }
            alpha = 0f
            translationY = 30.dpToPx().toFloat()
            scaleX = 0.965f
            scaleY = 0.965f
        }

        fun dismissAnimated() {
            if (isDismissing) return
            isDismissing = true
            dialogView.animate().cancel()
            scrim.animate().cancel()

            scrim.animate()
                .alpha(0f)
                .setDuration(130L)
                .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
                .start()

            dialogView.animate()
                .alpha(0f)
                .translationY(12.dpToPx().toFloat())
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(150L)
                .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                }
                .start()
        }

        fun playEnterAnimationOnce() {
            if (enterAnimationStarted || isDismissing) return
            enterAnimationStarted = true
            dialogView.post {
                dialogView.pivotX = dialogView.width / 2f
                dialogView.pivotY = dialogView.height.toFloat()
                scrim.animate()
                    .alpha(1f)
                    .setDuration(180L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
                dialogView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
                    .start()
            }
        }

        scrim.setOnClickListener {
            if (!isSaving) {
                dismissAnimated()
            }
        }

        val input = EditText(activity).apply {
            setText(initialText)
            setTextColor(Color.WHITE)
            textSize = 15f
            setHintTextColor(Color.parseColor("#8E8E93"))
            hint = hintText
            background = ContextCompat.getDrawable(activity, R.drawable.rename_dialog_input_bg)
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            configureInput()
        }
        dialogView.addView(input)

        val buttonsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 18.dpToPx() }
        }

        val cancelBtn = TextView(activity).apply {
            text = LocaleHelper.getString(activity, "button_cancel")
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(activity, R.drawable.btn_cancel_grey_bg)
            layoutParams = LinearLayout.LayoutParams(0, 42.dpToPx(), 1f).apply {
                marginEnd = 6.dpToPx()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissAnimated() }
        }
        buttonsContainer.addView(cancelBtn)

        val confirmLabel = TextView(activity).apply {
            text = LocaleHelper.getString(activity, "button_ok")
            setTextColor(Color.BLACK)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val confirmProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            isGone = true
            indeterminateDrawable.setTint(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(22.dpToPx(), 22.dpToPx(), Gravity.CENTER)
        }

        val confirmBtn = FrameLayout(activity).apply {
            background = ContextCompat.getDrawable(activity, R.drawable.btn_ok_white_bg)
            layoutParams = LinearLayout.LayoutParams(0, 42.dpToPx(), 1f).apply {
                marginStart = 6.dpToPx()
            }
            isClickable = true
            isFocusable = true
            addView(confirmLabel)
            addView(confirmProgress)
        }
        buttonsContainer.addView(confirmBtn)

        fun setSaving(saving: Boolean) {
            isSaving = saving
            input.isEnabled = !saving
            cancelBtn.isEnabled = !saving
            cancelBtn.alpha = if (saving) 0.55f else 1f
            confirmLabel.isGone = saving
            confirmProgress.isVisible = saving
        }

        fun updateConfirmState() {
            val enabled = !isSaving && input.text?.toString()?.trim()?.isNotEmpty() == true
            confirmBtn.isEnabled = enabled
            confirmBtn.isClickable = enabled
            confirmBtn.alpha = if (enabled) 1f else 0.45f
        }

        input.doAfterTextChanged { updateConfirmState() }
        confirmBtn.setOnClickListener {
            val newText = input.text?.toString()?.trim().orEmpty()
            if (newText.isBlank() || isSaving) {
                return@setOnClickListener
            }
            setSaving(true)
            updateConfirmState()
            runCatching {
                onConfirmed(newText) {
                    if (dialog.isShowing) {
                        dismissAnimated()
                    }
                }
            }.onFailure {
                setSaving(false)
                updateConfirmState()
            }
        }
        updateConfirmState()

        dialogView.addView(buttonsContainer)

        val restingBottomMargin = 16.dpToPx()
        val keyboardGap = 6.dpToPx()
        val panelWidth = ((activity.resources.displayMetrics.widthPixels * widthFraction).toInt())
            .coerceAtMost(activity.resources.displayMetrics.widthPixels - 32.dpToPx())
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            dialogView,
            FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = restingBottomMargin
            }
        )

        fun updatePanelBottomMargin(bottomInset: Int) {
            val lp = dialogView.layoutParams as FrameLayout.LayoutParams
            val targetBottomMargin = if (bottomInset > 0) {
                bottomInset + keyboardGap
            } else {
                restingBottomMargin
            }
            if (lp.bottomMargin != targetBottomMargin) {
                lp.bottomMargin = targetBottomMargin
                dialogView.layoutParams = lp
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updatePanelBottomMargin(maxOf(imeBottom, navBottom))
            if (isImeVisible && imeBottom > 0) {
                playEnterAnimationOnce()
            }
            insets
        }

        val visibleFrame = Rect()
        val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = activity.resources.displayMetrics.heightPixels
            val keyboardHeight = (screenHeight - visibleFrame.bottom).coerceAtLeast(0)
            val bottomInset = if (keyboardHeight > 80.dpToPx()) keyboardHeight else 0
            updatePanelBottomMargin(bottomInset)
            if (bottomInset > 0) {
                playEnterAnimationOnce()
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        dialog.setOnDismissListener {
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            }
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dialog.show()

        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }

        input.requestFocus()
        ViewCompat.requestApplyInsets(root)
        input.post {
            val inputMethodManager = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            inputMethodManager.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        root.postDelayed({ playEnterAnimationOnce() }, 360L)
    }

    private fun createMenuDivider() = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1.dpToPx()
        ).apply {
            topMargin = 4.dpToPx()
            bottomMargin = 4.dpToPx()
            marginStart = 14.dpToPx()
            marginEnd = 14.dpToPx()
        }
        setBackgroundColor(Color.parseColor("#4E4E52"))
    }

    private fun pinChatShortcut(context: android.content.Context, chat: ChatEntity) {
        if (androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            val shortcutId = "chat_${chat.id}"
            val intent = android.content.Intent(context, com.example.chatapp.FreeChatActivity::class.java).apply {
                action = android.content.Intent.ACTION_VIEW
                putExtra(com.example.chatapp.FreeChatActivity.EXTRA_OPEN_CHAT_ID, chat.id)
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val iconResource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                R.mipmap.ic_shortcut_chat
            } else {
                R.drawable.ic_shortcut_chat
            }
            val icon = androidx.core.graphics.drawable.IconCompat.createWithResource(context, iconResource)

            val shortcutInfo = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(chat.title)
                .setLongLabel(chat.title)
                .setIcon(icon)
                .setIntent(intent)
                .build()

            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        } else {
            android.widget.Toast.makeText(context, "Not supported on this launcher", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
