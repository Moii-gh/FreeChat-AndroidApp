package com.example.chatapp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.example.chatapp.LocaleHelper

internal object FreeChatAttachmentWidgetStateStore {
    private const val PREFS_NAME = "freechat_attachment_widget_state"
    private const val KEY_WIDGET_IDS = "widget_ids"

    private const val KEY_SELECTED_MODE = "selected_mode"
    private const val KEY_ACTIVE_ACTION = "active_action"
    private const val KEY_DISPLAY_TEXT = "display_text"
    private const val KEY_LAST_LAYOUT = "last_layout"
    private const val KEY_MIN_WIDTH = "min_width"
    private const val KEY_MIN_HEIGHT = "min_height"
    private const val KEY_MAX_WIDTH = "max_width"
    private const val KEY_MAX_HEIGHT = "max_height"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_UPDATED_AT = "updated_at"

    private const val MODE_MESSAGE = "message"
    private const val MODE_CAMERA = "camera"
    private const val MODE_GALLERY = "gallery"
    private const val MODE_DOCUMENT = "document"
    private const val MODE_VOICE = "voice"

    data class State(
        val appWidgetId: Int,
        val selectedMode: String,
        val activeAction: String,
        val displayText: String,
        val lastLayout: String,
        val minWidth: Int,
        val minHeight: Int,
        val maxWidth: Int,
        val maxHeight: Int,
        val language: String,
        val updatedAtMillis: Long
    )

    fun load(context: Context, appWidgetId: Int): State {
        val prefs = prefs(context)
        val language = LocaleHelper.getSelectedLanguage(context)
        val displayText = LocaleHelper.getString(context, "main_panel_input")
        return State(
            appWidgetId = appWidgetId,
            selectedMode = prefs.getString(key(appWidgetId, KEY_SELECTED_MODE), null) ?: MODE_MESSAGE,
            activeAction = prefs.getString(key(appWidgetId, KEY_ACTIVE_ACTION), null)
                ?: HomeWidgetActionActivity.ACTION_MESSAGE,
            displayText = prefs.getString(key(appWidgetId, KEY_DISPLAY_TEXT), null) ?: displayText,
            lastLayout = prefs.getString(key(appWidgetId, KEY_LAST_LAYOUT), null).orEmpty(),
            minWidth = prefs.getInt(key(appWidgetId, KEY_MIN_WIDTH), 0),
            minHeight = prefs.getInt(key(appWidgetId, KEY_MIN_HEIGHT), 0),
            maxWidth = prefs.getInt(key(appWidgetId, KEY_MAX_WIDTH), 0),
            maxHeight = prefs.getInt(key(appWidgetId, KEY_MAX_HEIGHT), 0),
            language = prefs.getString(key(appWidgetId, KEY_LANGUAGE), null) ?: language,
            updatedAtMillis = prefs.getLong(key(appWidgetId, KEY_UPDATED_AT), 0L)
        )
    }

    fun saveRenderedState(
        context: Context,
        appWidgetId: Int,
        size: WidgetSize,
        layoutName: String,
        displayText: String
    ) {
        if (!isValidWidgetId(appWidgetId)) return
        val language = LocaleHelper.getSelectedLanguage(context)
        val prefs = prefs(context)
        val editor = prefs.edit()
        editor.registerWidget(prefs, appWidgetId)
            .putStringIfAbsent(prefs, key(appWidgetId, KEY_SELECTED_MODE), MODE_MESSAGE)
            .putStringIfAbsent(
                prefs,
                key(appWidgetId, KEY_ACTIVE_ACTION),
                HomeWidgetActionActivity.ACTION_MESSAGE
            )
            .putString(key(appWidgetId, KEY_DISPLAY_TEXT), displayText)
            .putString(key(appWidgetId, KEY_LAST_LAYOUT), layoutName)
            .putInt(key(appWidgetId, KEY_MIN_WIDTH), size.minWidth)
            .putInt(key(appWidgetId, KEY_MIN_HEIGHT), size.minHeight)
            .putInt(key(appWidgetId, KEY_MAX_WIDTH), size.maxWidth)
            .putInt(key(appWidgetId, KEY_MAX_HEIGHT), size.maxHeight)
            .putString(key(appWidgetId, KEY_LANGUAGE), language)
            .putLong(key(appWidgetId, KEY_UPDATED_AT), System.currentTimeMillis())
            .commit()
    }

    fun saveAction(context: Context, appWidgetId: Int, action: String, displayText: String) {
        if (!isValidWidgetId(appWidgetId)) return
        val prefs = prefs(context)
        prefs.edit()
            .registerWidget(prefs, appWidgetId)
            .putString(key(appWidgetId, KEY_SELECTED_MODE), modeForAction(action))
            .putString(key(appWidgetId, KEY_ACTIVE_ACTION), action)
            .putString(key(appWidgetId, KEY_DISPLAY_TEXT), displayText)
            .putString(key(appWidgetId, KEY_LANGUAGE), LocaleHelper.getSelectedLanguage(context))
            .putLong(key(appWidgetId, KEY_UPDATED_AT), System.currentTimeMillis())
            .commit()
    }

    fun restoreIds(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        val knownIds = currentWidgetIds(prefs).toMutableSet()
        oldWidgetIds.zip(newWidgetIds).forEach { (oldId, newId) ->
            copyKey(prefs, editor, oldId, newId, KEY_SELECTED_MODE, ValueType.StringValue)
            copyKey(prefs, editor, oldId, newId, KEY_ACTIVE_ACTION, ValueType.StringValue)
            copyKey(prefs, editor, oldId, newId, KEY_DISPLAY_TEXT, ValueType.StringValue)
            copyKey(prefs, editor, oldId, newId, KEY_LAST_LAYOUT, ValueType.StringValue)
            copyKey(prefs, editor, oldId, newId, KEY_MIN_WIDTH, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_MIN_HEIGHT, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_MAX_WIDTH, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_MAX_HEIGHT, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_LANGUAGE, ValueType.StringValue)
            copyKey(prefs, editor, oldId, newId, KEY_UPDATED_AT, ValueType.LongValue)
            editor.removeWidgetData(oldId)
            knownIds.remove(oldId.toString())
        }
        newWidgetIds
            .filter(::isValidWidgetId)
            .map(Int::toString)
            .forEach(knownIds::add)
        editor.putStringSet(KEY_WIDGET_IDS, knownIds)
        editor.commit()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val prefs = prefs(context)
        val editor = prefs.edit()
        val knownIds = currentWidgetIds(prefs).toMutableSet()
        appWidgetIds.forEach {
            editor.removeWidgetData(it)
            knownIds.remove(it.toString())
        }
        editor.putStringSet(KEY_WIDGET_IDS, knownIds)
        editor.commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun knownWidgetIds(context: Context): IntArray {
        return prefs(context)
            .getStringSet(KEY_WIDGET_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toIntArray()
    }

    fun sizeFrom(options: Bundle?, state: State): WidgetSize {
        return WidgetSize(
            minWidth = options.dimensionOrSaved(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                state.minWidth
            ),
            minHeight = options.dimensionOrSaved(
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                state.minHeight
            ),
            maxWidth = options.dimensionOrSaved(
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                state.maxWidth
            ),
            maxHeight = options.dimensionOrSaved(
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                state.maxHeight
            )
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Bundle?.dimensionOrSaved(key: String, savedValue: Int): Int {
        val optionValue = this?.getInt(key)?.takeIf { it > 0 }
        return optionValue ?: savedValue.takeIf { it > 0 } ?: 0
    }

    private fun SharedPreferences.Editor.putStringIfAbsent(
        prefs: SharedPreferences,
        key: String,
        value: String
    ): SharedPreferences.Editor {
        if (!prefs.contains(key)) {
            putString(key, value)
        }
        return this
    }

    private fun SharedPreferences.Editor.registerWidget(
        prefs: SharedPreferences,
        appWidgetId: Int
    ): SharedPreferences.Editor {
        return registerWidgets(prefs, intArrayOf(appWidgetId))
    }

    private fun SharedPreferences.Editor.registerWidgets(
        prefs: SharedPreferences,
        appWidgetIds: IntArray
    ): SharedPreferences.Editor {
        val ids = currentWidgetIds(prefs).toMutableSet()
        appWidgetIds
            .filter(::isValidWidgetId)
            .map(Int::toString)
            .forEach(ids::add)
        putStringSet(KEY_WIDGET_IDS, ids)
        return this
    }

    private fun SharedPreferences.Editor.removeWidgetData(
        appWidgetId: Int
    ): SharedPreferences.Editor {
        remove(key(appWidgetId, KEY_SELECTED_MODE))
        remove(key(appWidgetId, KEY_ACTIVE_ACTION))
        remove(key(appWidgetId, KEY_DISPLAY_TEXT))
        remove(key(appWidgetId, KEY_LAST_LAYOUT))
        remove(key(appWidgetId, KEY_MIN_WIDTH))
        remove(key(appWidgetId, KEY_MIN_HEIGHT))
        remove(key(appWidgetId, KEY_MAX_WIDTH))
        remove(key(appWidgetId, KEY_MAX_HEIGHT))
        remove(key(appWidgetId, KEY_LANGUAGE))
        remove(key(appWidgetId, KEY_UPDATED_AT))
        return this
    }

    private fun currentWidgetIds(prefs: SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY_WIDGET_IDS, emptySet()).orEmpty()
    }

    private fun copyKey(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        oldWidgetId: Int,
        newWidgetId: Int,
        suffix: String,
        type: ValueType
    ) {
        val oldKey = key(oldWidgetId, suffix)
        if (!prefs.contains(oldKey)) return
        val newKey = key(newWidgetId, suffix)
        when (type) {
            ValueType.StringValue -> editor.putString(newKey, prefs.getString(oldKey, null))
            ValueType.IntValue -> editor.putInt(newKey, prefs.getInt(oldKey, 0))
            ValueType.LongValue -> editor.putLong(newKey, prefs.getLong(oldKey, 0L))
        }
    }

    private fun modeForAction(action: String): String {
        return when (action) {
            HomeWidgetActionActivity.ACTION_CAMERA -> MODE_CAMERA
            HomeWidgetActionActivity.ACTION_GALLERY -> MODE_GALLERY
            HomeWidgetActionActivity.ACTION_DOCUMENT -> MODE_DOCUMENT
            HomeWidgetActionActivity.ACTION_MIC -> MODE_VOICE
            else -> MODE_MESSAGE
        }
    }

    private fun key(appWidgetId: Int, suffix: String) = "widget_${appWidgetId}_$suffix"

    private fun isValidWidgetId(appWidgetId: Int): Boolean {
        return appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && appWidgetId > 0
    }

    private enum class ValueType {
        StringValue,
        IntValue,
        LongValue
    }
}

internal data class WidgetSize(
    val minWidth: Int,
    val minHeight: Int,
    val maxWidth: Int,
    val maxHeight: Int
)
