package com.example.chatapp.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
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
    private val onRename: (ChatEntity, String) -> Unit,
    private val onTogglePin: (ChatEntity) -> Unit,
    private val onShare: (ChatEntity) -> Unit,
    private val onRevokeShares: (ChatEntity) -> Unit,
    private val onDelete: (ChatEntity) -> Unit,
    private val onRegenerate: ((AssistantMessageWrapper) -> Unit)? = null,
    private val onEditUserMessage: ((Int, String) -> Unit)? = null
) {
    private val standardMenuWidth by lazy { 258.dpToPx() }

    /**
     * Popup при long press на элемент чата в drawer.
     * Показывает реплику элемента + меню поверх затемнённого фона.
     */
    fun showChatPopupMenu(anchorView: View, chat: ChatEntity) {
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#B3000000"))
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
            elevation = 24f
            setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())

            layoutParams = FrameLayout.LayoutParams(standardMenuWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x + 72.dpToPx()
            }
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
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


        menuLayout.addView(createMenuDivider())

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
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                    .start()
            }
        })

        replica.animate().alpha(1f).scaleX(1.02f).scaleY(1.02f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /**
     * Popup при нажатии на три точки в заголовке текущего чата.
     */
    fun showCurrentChatOptionsMenu(anchorView: View, chat: ChatEntity) {
        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.popup_menu_bg)
            elevation = 24f
            setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
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

        // Заголовок
        val displayTitle = if (chat.title.length > 25) chat.title.substring(0, 25) + "..." else chat.title
        popupView.addView(TextView(activity).apply {
            text = displayTitle
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 8.dpToPx())
        })

        // Переименовать
        popupView.addView(createPopupMenuItem(R.drawable.ic_rename, LocaleHelper.getString(activity, "menu_rename"), Color.WHITE) {
            popupWindow.dismiss()
            showRenameDialog(chat)
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


        popupView.addView(createMenuDivider())

        // Удалить
        popupView.addView(createPopupMenuItem(R.drawable.ic_delete, LocaleHelper.getString(activity, "button_delete"), Color.parseColor("#FF453A")) {
            popupWindow.dismiss()
            onDelete(chat)
        })

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

    /**
     * Popup для ответа ассистента (регенерация).
     */
    fun showAssistantMessageOptionsMenu(anchorView: View, wrapper: AssistantMessageWrapper) {
        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.popup_menu_bg)
            elevation = 24f
            setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
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
            Color.WHITE
        ) {
            popupWindow.dismiss()
            onRegenerate?.invoke(wrapper)
        })

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
        val userMenuWidth = 272.dpToPx()
        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.popup_menu_bg)
            elevation = 24f
            setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
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

        popupView.addView(createPopupMenuItem(R.drawable.ic_pen, LocaleHelper.getString(activity, "menu_edit_message"), Color.WHITE) {
            popupWindow.dismiss()
            onEditUserMessage?.invoke(historyIndex, message)
        })

        popupView.addView(createPopupMenuItem(R.drawable.ic_copy, LocaleHelper.getString(activity, "menu_copy_text"), Color.WHITE) {
            popupWindow.dismiss()
            FileUtils.copyToClipboard(activity, message)
        })

        popupView.addView(createPopupMenuItem(R.drawable.ic_share, LocaleHelper.getString(activity, "share"), Color.WHITE) {
            popupWindow.dismiss()
            FileUtils.shareText(activity, message)
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
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = 46.dpToPx()
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            background = ContextCompat.getDrawable(activity, outValue.resourceId)

            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(tintColor)
                layoutParams = LinearLayout.LayoutParams(18.dpToPx(), 18.dpToPx())
            })

            addView(TextView(activity).apply {
                this.text = text
                setTextColor(tintColor)
                textSize = 15f
                setPadding(12.dpToPx(), 0, 0, 0)
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
            onConfirmed = { newTitle -> onRename(chat, newTitle) }
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
            onConfirmed = onEdited
        )
    }

    private fun showTextInputDialog(
        initialText: String,
        hintText: String,
        widthFraction: Float,
        configureInput: EditText.() -> Unit,
        onConfirmed: (String) -> Unit
    ) {
        val dialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.dialog_bg)
            setPadding(20.dpToPx(), 24.dpToPx(), 20.dpToPx(), 20.dpToPx())
        }

        val input = EditText(activity).apply {
            setText(initialText)
            setTextColor(Color.WHITE)
            textSize = 15f
            setHintTextColor(Color.parseColor("#636366"))
            hint = hintText
            background = ContextCompat.getDrawable(activity, R.drawable.dialog_input_bg)
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

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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
            setOnClickListener { dialog.dismiss() }
        }
        buttonsContainer.addView(cancelBtn)

        val confirmBtn = TextView(activity).apply {
            text = LocaleHelper.getString(activity, "button_ok")
            setTextColor(Color.BLACK)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = ContextCompat.getDrawable(activity, R.drawable.btn_ok_white_bg)
            layoutParams = LinearLayout.LayoutParams(0, 42.dpToPx(), 1f).apply {
                marginStart = 6.dpToPx()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val newText = input.text?.toString()?.trim().orEmpty()
                if (newText.isNotEmpty()) {
                    onConfirmed(newText)
                }
                dialog.dismiss()
            }
        }
        buttonsContainer.addView(confirmBtn)

        dialogView.addView(buttonsContainer)
        dialog.show()

        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * widthFraction).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
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
}
