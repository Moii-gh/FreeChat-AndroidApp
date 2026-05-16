package com.example.chatapp.developer

import android.content.Context
import com.example.chatapp.LanguageManager
import com.example.chatapp.data.AccountExitLimiter
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.util.SafeLog
import java.io.File

object DeveloperActions {

    fun clearTemporaryCache(context: Context): Boolean {
        val appContext = context.applicationContext
        val cacheRoots = listOfNotNull(appContext.cacheDir, appContext.externalCacheDir)
        var success = true

        cacheRoots.forEach { root ->
            success = clearDirectoryContents(root) && success
        }

        SafeLog.d("DeveloperActions", "Temporary cache clear completed: success=$success")
        return success
    }

    fun resetAppSettings(context: Context) {
        val appContext = context.applicationContext
        AccountScopedSettings(appContext).resetInterfaceAndBehaviorSettings()
        LanguageManager.resetAppLanguage(appContext)
        SafeLog.d("DeveloperActions", "Interface and behavior settings reset")
    }

    fun resetLogoutLimits(context: Context) {
        AccountExitLimiter(context.applicationContext).resetLimits()
        SafeLog.d("DeveloperActions", "Account logout limits reset")
    }

    private fun clearDirectoryContents(directory: File): Boolean {
        if (!directory.exists() || !directory.isDirectory) return true
        return directory.listFiles()
            ?.fold(true) { success, child ->
                runCatching { child.deleteRecursively() }.getOrDefault(false) && success
            }
            ?: true
    }
}
