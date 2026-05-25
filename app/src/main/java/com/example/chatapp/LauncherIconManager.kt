package com.example.chatapp

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIconManager {

    private const val PREFS_NAME = "launcher_icon_settings"
    private const val KEY_SELECTED_ICON = "selected_icon"

    enum class LauncherIcon(
        val preferenceValue: String,
        private val aliasClassSuffix: String,
    ) {
        DEFAULT("default", ".MainActivityDefaultIconAlias"),
        TRANSPARENT("transparent", ".MainActivityTransparentIconAlias");

        fun componentName(context: Context): ComponentName {
            val packageName = context.packageName
            return ComponentName(packageName, packageName + aliasClassSuffix)
        }
    }

    fun getSelectedIcon(context: Context): LauncherIcon {
        val storedValue = prefs(context).getString(KEY_SELECTED_ICON, null)
        return LauncherIcon.values().firstOrNull { it.preferenceValue == storedValue }
            ?: LauncherIcon.DEFAULT
    }

    fun setSelectedIcon(context: Context, icon: LauncherIcon): Boolean {
        val appContext = context.applicationContext
        val previousIcon = getSelectedIcon(appContext)
        if (previousIcon == icon) return false

        val packageManager = appContext.packageManager
        packageManager.setComponentEnabledSetting(
            icon.componentName(appContext),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        LauncherIcon.values()
            .filterNot { it == icon }
            .forEach { launcherIcon ->
                packageManager.setComponentEnabledSetting(
                    launcherIcon.componentName(appContext),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

        prefs(appContext)
            .edit()
            .putString(KEY_SELECTED_ICON, icon.preferenceValue)
            .apply()

        return true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
