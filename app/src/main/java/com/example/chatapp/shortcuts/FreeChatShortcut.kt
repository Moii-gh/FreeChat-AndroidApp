package com.example.chatapp.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.chatapp.MainActivity
import com.example.chatapp.R

enum class FreeChatShortcut(
    val shortcutId: String,
    val intentAction: String,
    val deepLinkPath: String,
    @param:StringRes val shortLabelRes: Int,
    @param:StringRes val longLabelRes: Int,
    @param:StringRes val disabledMessageRes: Int,
    @param:DrawableRes val iconRes: Int,
    val rank: Int
) {
    NewChat(
        shortcutId = "new_chat",
        intentAction = "com.example.chatapp.action.NEW_CHAT",
        deepLinkPath = "/new-chat",
        shortLabelRes = R.string.button_new_chat,
        longLabelRes = R.string.button_new_chat,
        disabledMessageRes = R.string.shortcut_disabled_message,
        iconRes = R.drawable.ic_shortcut_new_chat,
        rank = 0
    ),
    LastChat(
        shortcutId = "last_chat",
        intentAction = "com.example.chatapp.action.LAST_CHAT",
        deepLinkPath = "/last-chat",
        shortLabelRes = R.string.shortcut_last_chat,
        longLabelRes = R.string.shortcut_last_chat,
        disabledMessageRes = R.string.shortcut_disabled_message,
        iconRes = R.drawable.ic_shortcut_last_chat,
        rank = 1
    ),
    Photo(
        shortcutId = "photo",
        intentAction = "com.example.chatapp.action.PHOTO",
        deepLinkPath = "/photo",
        shortLabelRes = R.string.button_photo,
        longLabelRes = R.string.button_photo,
        disabledMessageRes = R.string.shortcut_disabled_message,
        iconRes = R.drawable.ic_shortcut_photo,
        rank = 2
    );

    fun deepLinkUri(): Uri = Uri.Builder()
        .scheme(DEEP_LINK_SCHEME)
        .authority(DEEP_LINK_HOST)
        .path(deepLinkPath)
        .build()

    fun buildIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = intentAction
            data = deepLinkUri()
            putExtra(EXTRA_SHORTCUT_ID, shortcutId)
            putExtra(EXTRA_SHORTCUT_SOURCE, SOURCE_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    companion object {
        const val EXTRA_SHORTCUT_ID = "com.example.chatapp.shortcuts.EXTRA_SHORTCUT_ID"
        const val EXTRA_SHORTCUT_SOURCE = "com.example.chatapp.shortcuts.EXTRA_SHORTCUT_SOURCE"
        const val SOURCE_LAUNCHER = "launcher"
        const val DEEP_LINK_SCHEME = "freechat"
        const val DEEP_LINK_HOST = "shortcut"

        fun fromIntent(intent: Intent?): FreeChatShortcut? {
            if (intent == null) return null

            val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)
            entries.firstOrNull { it.shortcutId == shortcutId }?.let { return it }

            val action = intent.action
            entries.firstOrNull { it.intentAction == action }?.let { return it }

            val data = intent.data ?: return null
            if (
                data.scheme != DEEP_LINK_SCHEME ||
                data.host != DEEP_LINK_HOST
            ) {
                return null
            }

            return entries.firstOrNull { it.deepLinkPath == data.path }
        }

        fun copyToIntent(shortcut: FreeChatShortcut, target: Intent) {
            target.action = shortcut.intentAction
            target.data = shortcut.deepLinkUri()
            target.putExtra(EXTRA_SHORTCUT_ID, shortcut.shortcutId)
            target.putExtra(EXTRA_SHORTCUT_SOURCE, SOURCE_LAUNCHER)
        }

        fun consume(intent: Intent?) {
            if (fromIntent(intent) == null) return
            intent?.removeExtra(EXTRA_SHORTCUT_ID)
            intent?.removeExtra(EXTRA_SHORTCUT_SOURCE)
            intent?.action = null
            intent?.data = null
        }
    }
}
