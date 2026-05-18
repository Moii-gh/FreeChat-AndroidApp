package com.example.chatapp.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.chatapp.ChatEntity
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.ui.chat.ChatRenameAnimationPlanner
import com.example.chatapp.util.SafeImageLoader
import com.example.chatapp.util.dpToPx
import java.util.*

/**
 * Управление боковым меню (Drawer): отображение списка чатов,
 * группировка по датам, фильтрация, профиль пользователя.
 *
 * Устранено дублирование updateChatsContainer/filterChats —
 * теперь один метод populateChats() с параметром фильтра.
 */
class DrawerManager(
    private val context: android.app.Activity,
    private val chatsContainer: LinearLayout,
    private val onChatClick: (String) -> Unit,
    private val onChatLongClick: (View, ChatEntity) -> Unit
) {
    private data class PendingTitleAnimation(
        val oldTitle: String,
        val newTitle: String
    )

    private var selectedChatId: String? = null
    private var generatingChatIds: Set<String> = emptySet()
    private var renderedTitles: Map<String, String> = emptyMap()
    private var titleAnimationSerial = 0
    private val titleAnimationVersions = mutableMapOf<String, Int>()
    private val pendingTitleAnimations = mutableMapOf<String, PendingTitleAnimation>()
    private val titleAnimationHandler = Handler(Looper.getMainLooper())

    fun setSelectedChatId(chatId: String?) {
        selectedChatId = chatId
    }

    fun setGeneratingChatIds(chatIds: Set<String>) {
        generatingChatIds = chatIds
    }

    fun queueTitleRenameAnimation(chatId: String, oldTitle: String, newTitle: String) {
        if (oldTitle == newTitle) return
        titleAnimationVersions[chatId] = ++titleAnimationSerial
        pendingTitleAnimations[chatId] = PendingTitleAnimation(oldTitle, newTitle)
    }

    /**
     * Заполняет контейнер чатами с группировкой по дате.
     * @param chats список чатов (уже отфильтрованный, если нужно)
     * @param query поисковый запрос (для подбора текста "нет результатов")
     */
    fun populateChats(chats: List<ChatEntity>, query: String = "") {
        context.runOnUiThread {
            chatsContainer.removeAllViews()

            val pinnedChats = chats.filter { it.isPinned }
            val unpinnedChats = chats.filter { !it.isPinned }

            // Определяем границы "сегодня" и "за 7 дней"
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val weekStart = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -7)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val todayChats = mutableListOf<ChatEntity>()
            val weekChats = mutableListOf<ChatEntity>()
            val earlierChats = mutableListOf<ChatEntity>()

            unpinnedChats.forEach {
                when {
                    it.lastUpdated >= todayStart -> todayChats.add(it)
                    it.lastUpdated >= weekStart -> weekChats.add(it)
                    else -> earlierChats.add(it)
                }
            }

            // Закреплённые
            if (pinnedChats.isNotEmpty()) {
                chatsContainer.addView(createSectionHeader(LocaleHelper.getString(context, "section_pinned")))
                pinnedChats.forEach { chatsContainer.addView(createChatViewItem(it)) }
            }

            // Сегодня
            if (todayChats.isNotEmpty()) {
                chatsContainer.addView(createSectionHeader(LocaleHelper.getString(context, "history_label_today")))
                todayChats.forEach { chatsContainer.addView(createChatViewItem(it)) }
            }

            // За 7 дней
            if (weekChats.isNotEmpty()) {
                chatsContainer.addView(createSectionHeader(LocaleHelper.getString(context, "history_label_last_7_days")))
                weekChats.forEach { chatsContainer.addView(createChatViewItem(it)) }
            }

            // Ранее
            if (earlierChats.isNotEmpty()) {
                chatsContainer.addView(createSectionHeader(LocaleHelper.getString(context, "history_label_previously")))
                earlierChats.forEach { chatsContainer.addView(createChatViewItem(it)) }
            }

            // Пустой список
            if (chats.isEmpty()) {
                chatsContainer.addView(TextView(context).apply {
                    text = if (query.isBlank()) {
                        LocaleHelper.getString(context, "no_chat_history")
                    } else {
                        LocaleHelper.getString(context, "no_search_results")
                    }
                    setTextColor(Color.parseColor("#8E8E93"))
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(16.dpToPx(), 32.dpToPx(), 16.dpToPx(), 32.dpToPx())
                })
            }
            renderedTitles = chats.associate { it.id to it.title }
        }
    }

    private fun createSectionHeader(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#636366"))
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 6.dpToPx())
        letterSpacing = 0.02f
    }

    private fun createChatViewItem(chat: ChatEntity): View {
        val isSelected = chat.id == selectedChatId
        val isGenerating = chat.id in generatingChatIds

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 13.dpToPx(), 16.dpToPx(), 13.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 12.dpToPx()
                marginEnd = 12.dpToPx()
                bottomMargin = 4.dpToPx()
            }
            isClickable = true
            isFocusable = true
            minimumHeight = 48.dpToPx()
            background = if (isSelected) {
                ContextCompat.getDrawable(context, R.drawable.chat_item_highlight_bg)
            } else {
                null
            }

            // Иконка закрепления
            if (chat.isPinned) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_pin)
                    setColorFilter(if (isSelected) Color.WHITE else Color.parseColor("#636366"))
                    layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx()).apply {
                        marginEnd = 8.dpToPx()
                    }
                })
            }

            // Название чата
            val pendingTitleAnimation = pendingTitleAnimations[chat.id]
                ?.takeIf { it.newTitle == chat.title }
            val previousTitle = pendingTitleAnimation?.oldTitle ?: renderedTitles[chat.id]
            val titleToAnimateFrom = previousTitle?.takeIf { it != chat.title }
            val titleView = TextView(context).apply {
                text = titleToAnimateFrom ?: chat.title
                setTextColor(Color.WHITE)
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            addView(titleView)

            if (titleToAnimateFrom != null) {
                titleView.post {
                    animateTitleChange(chat.id, titleView, titleToAnimateFrom, chat.title)
                }
            }

            if (isGenerating) {
                addView(ChatLoadingIndicatorView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(16.dpToPx(), 16.dpToPx()).apply {
                        marginStart = 10.dpToPx()
                    }
                })
            }

            setOnClickListener { onChatClick(chat.id) }
            setOnLongClickListener { view ->
                onChatLongClick(view, chat)
                true
            }
        }
    }

    private fun animateTitleChange(chatId: String, titleView: TextView, oldTitle: String, newTitle: String) {
        val plan = ChatRenameAnimationPlanner.plan(oldTitle, newTitle)
        val frames = plan.deleteSteps + plan.typeSteps
        val deleteFrameCount = plan.deleteSteps.size
        if (frames.isEmpty()) {
            titleView.text = newTitle
            if (pendingTitleAnimations[chatId]?.newTitle == newTitle) {
                pendingTitleAnimations.remove(chatId)
            }
            return
        }

        val serial = ++titleAnimationSerial
        titleAnimationVersions[chatId] = serial
        titleView.animate().cancel()
        titleView.alpha = 1f

        var index = 0
        fun scheduleNext(delayMillis: Long) {
            titleAnimationHandler.postDelayed({
                if (titleAnimationVersions[chatId] != serial) {
                    return@postDelayed
                }
                if (index >= frames.size) {
                    titleView.text = newTitle
                    titleAnimationVersions.remove(chatId)
                    if (pendingTitleAnimations[chatId]?.newTitle == newTitle) {
                        pendingTitleAnimations.remove(chatId)
                    }
                    return@postDelayed
                }

                titleView.text = frames[index]
                index += 1
                scheduleNext(if (index < deleteFrameCount) 42L else 68L)
            }, delayMillis)
        }

        scheduleNext(80L)
    }

    /** Обновление профиля пользователя в drawer header */
    fun updateUserProfile() {
        val sessionStore = SharedPrefsAccountSessionStore(context)
        val accountSettings = AccountScopedSettings(context)
        val userName = accountSettings.getDisplayName(sessionStore.getCurrentUserName() ?: "User")
        val avatarUri = accountSettings.getAvatarUri()

        val tvName = context.findViewById<TextView>(R.id.tvDrawerUserName)
        val ivAvatar = context.findViewById<ImageView>(R.id.ivDrawerAvatar)
        val tvLetter = context.findViewById<TextView>(R.id.tvDrawerAvatarLetter)
        val viewBg = context.findViewById<View>(R.id.drawerAvatarBg)

        tvName?.text = userName

        if (avatarUri != null) {
            ivAvatar?.visibility = View.VISIBLE
            tvLetter?.visibility = View.GONE
            viewBg?.visibility = View.GONE
            try {
                ivAvatar?.let {
                    SafeImageLoader.loadUri(
                        imageView = it,
                        uri = android.net.Uri.parse(avatarUri),
                        widthPx = it.width.takeIf { width -> width > 0 } ?: 56.dpToPx(),
                        heightPx = it.height.takeIf { height -> height > 0 } ?: 56.dpToPx()
                    )
                }
            } catch (e: Exception) {
                ivAvatar?.visibility = View.GONE
                tvLetter?.visibility = View.VISIBLE
                viewBg?.visibility = View.VISIBLE
                tvLetter?.text = userName.firstOrNull()?.uppercase() ?: "P"
            }
        } else {
            ivAvatar?.visibility = View.GONE
            tvLetter?.visibility = View.VISIBLE
            viewBg?.visibility = View.VISIBLE
            tvLetter?.text = userName.firstOrNull()?.uppercase() ?: "P"
        }
    }
}
