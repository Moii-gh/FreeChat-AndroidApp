package com.example.chatapp.shortcuts

import android.content.Context
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.chatapp.util.SafeLog

class FreeChatShortcutManager(
    context: Context
) {
    private val appContext = context.applicationContext

    fun initialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return

        val shortcutManager = appContext.getSystemService(ShortcutManager::class.java) ?: return
        val shortcutTemplates = buildShortcutTemplates()
        SafeLog.d(
            "FreeChatShortcutManager",
            "Launcher shortcuts available count=${shortcutTemplates.size} max=${shortcutManager.maxShortcutCountPerActivity}"
        )
    }

    fun buildShortcutTemplates(): List<ShortcutInfoCompat> {
        return FreeChatShortcut.entries
            .sortedBy { it.rank }
            .map(::buildShortcutInfo)
    }

    fun buildShortcutInfo(shortcut: FreeChatShortcut): ShortcutInfoCompat {
        return ShortcutInfoCompat.Builder(appContext, shortcut.shortcutId)
            .setShortLabel(appContext.getString(shortcut.shortLabelRes))
            .setLongLabel(appContext.getString(shortcut.longLabelRes))
            .setDisabledMessage(appContext.getString(shortcut.disabledMessageRes))
            .setIcon(IconCompat.createWithResource(appContext, shortcut.iconRes))
            .setIntent(shortcut.buildIntent(appContext))
            .setRank(shortcut.rank)
            .build()
    }
}
