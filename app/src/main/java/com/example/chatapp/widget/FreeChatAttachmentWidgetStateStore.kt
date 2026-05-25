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
    private const val KEY_TRANSPARENCY_PERCENT = "transparency_percent"
    private const val KEY_WIDGET_STYLE = "widget_style"
    private const val KEY_BLUR_INTENSITY = "blur_intensity"
    private const val KEY_BORDER_GLOW = "border_glow"
    private const val KEY_NOISE_TEXTURE = "noise_texture"
    private const val KEY_CORNER_RADIUS_DP = "corner_radius_dp"
    private const val KEY_DYNAMIC_REFLECTIONS = "dynamic_reflections"
    private const val KEY_MATCH_WALLPAPER_COLORS = "match_wallpaper_colors"
    private const val KEY_GLASS_DEPTH = "glass_depth"
    private const val KEY_BACKGROUND_IMAGE_URI = "background_image_uri"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_UPDATED_AT = "updated_at"

    private const val MODE_MESSAGE = "message"
    private const val MODE_CAMERA = "camera"
    private const val MODE_GALLERY = "gallery"
    private const val MODE_DOCUMENT = "document"
    private const val MODE_VOICE = "voice"

    const val MIN_TRANSPARENCY_PERCENT = 0
    const val MAX_TRANSPARENCY_PERCENT = 80
    const val DEFAULT_TRANSPARENCY_PERCENT = 18

    const val MIN_BLUR_INTENSITY = 0
    const val MAX_BLUR_INTENSITY = 100
    const val DEFAULT_BLUR_INTENSITY = 72

    const val MIN_BORDER_GLOW = 0
    const val MAX_BORDER_GLOW = 100
    const val DEFAULT_BORDER_GLOW = 74

    const val MIN_CORNER_RADIUS_DP = 18
    const val MAX_CORNER_RADIUS_DP = 46
    const val DEFAULT_CORNER_RADIUS_DP = 34

    const val MIN_GLASS_DEPTH = 0
    const val MAX_GLASS_DEPTH = 100
    const val DEFAULT_GLASS_DEPTH = 78

    const val DEFAULT_NOISE_TEXTURE = true
    const val DEFAULT_DYNAMIC_REFLECTIONS = true
    const val DEFAULT_MATCH_WALLPAPER_COLORS = true

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
        val transparencyPercent: Int,
        val widgetStyle: WidgetStyle,
        val blurIntensity: Int,
        val borderGlow: Int,
        val noiseTexture: Boolean,
        val cornerRadiusDp: Int,
        val dynamicReflections: Boolean,
        val matchWallpaperColors: Boolean,
        val glassDepth: Int,
        val backgroundImageUri: String?,
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
            transparencyPercent = prefs.getInt(
                key(appWidgetId, KEY_TRANSPARENCY_PERCENT),
                DEFAULT_TRANSPARENCY_PERCENT
            ).coerceTransparency(),
            widgetStyle = WidgetStyle.fromPrefValue(
                prefs.getString(key(appWidgetId, KEY_WIDGET_STYLE), null)
            ),
            blurIntensity = prefs.getInt(
                key(appWidgetId, KEY_BLUR_INTENSITY),
                DEFAULT_BLUR_INTENSITY
            ).coercePercent(),
            borderGlow = prefs.getInt(
                key(appWidgetId, KEY_BORDER_GLOW),
                DEFAULT_BORDER_GLOW
            ).coercePercent(),
            noiseTexture = prefs.getBoolean(
                key(appWidgetId, KEY_NOISE_TEXTURE),
                DEFAULT_NOISE_TEXTURE
            ),
            cornerRadiusDp = prefs.getInt(
                key(appWidgetId, KEY_CORNER_RADIUS_DP),
                DEFAULT_CORNER_RADIUS_DP
            ).coerceCornerRadius(),
            dynamicReflections = prefs.getBoolean(
                key(appWidgetId, KEY_DYNAMIC_REFLECTIONS),
                DEFAULT_DYNAMIC_REFLECTIONS
            ),
            matchWallpaperColors = prefs.getBoolean(
                key(appWidgetId, KEY_MATCH_WALLPAPER_COLORS),
                DEFAULT_MATCH_WALLPAPER_COLORS
            ),
            glassDepth = prefs.getInt(
                key(appWidgetId, KEY_GLASS_DEPTH),
                DEFAULT_GLASS_DEPTH
            ).coercePercent(),
            backgroundImageUri = prefs.getString(key(appWidgetId, KEY_BACKGROUND_IMAGE_URI), null)
                ?.takeIf { it.isNotBlank() },
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
            .putIntIfAbsent(
                prefs,
                key(appWidgetId, KEY_TRANSPARENCY_PERCENT),
                DEFAULT_TRANSPARENCY_PERCENT
            )
            .putStringIfAbsent(
                prefs,
                key(appWidgetId, KEY_WIDGET_STYLE),
                WidgetStyle.LiquidGlass.prefValue
            )
            .putIntIfAbsent(prefs, key(appWidgetId, KEY_BLUR_INTENSITY), DEFAULT_BLUR_INTENSITY)
            .putIntIfAbsent(prefs, key(appWidgetId, KEY_BORDER_GLOW), DEFAULT_BORDER_GLOW)
            .putBooleanIfAbsent(prefs, key(appWidgetId, KEY_NOISE_TEXTURE), DEFAULT_NOISE_TEXTURE)
            .putIntIfAbsent(
                prefs,
                key(appWidgetId, KEY_CORNER_RADIUS_DP),
                DEFAULT_CORNER_RADIUS_DP
            )
            .putBooleanIfAbsent(
                prefs,
                key(appWidgetId, KEY_DYNAMIC_REFLECTIONS),
                DEFAULT_DYNAMIC_REFLECTIONS
            )
            .putBooleanIfAbsent(
                prefs,
                key(appWidgetId, KEY_MATCH_WALLPAPER_COLORS),
                DEFAULT_MATCH_WALLPAPER_COLORS
            )
            .putIntIfAbsent(prefs, key(appWidgetId, KEY_GLASS_DEPTH), DEFAULT_GLASS_DEPTH)
            .putStringIfAbsent(prefs, key(appWidgetId, KEY_BACKGROUND_IMAGE_URI), "")
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

    fun saveAppearance(
        context: Context,
        appWidgetId: Int,
        widgetStyle: WidgetStyle,
        blurIntensity: Int,
        transparencyPercent: Int,
        borderGlow: Int,
        noiseTexture: Boolean,
        cornerRadiusDp: Int,
        dynamicReflections: Boolean,
        matchWallpaperColors: Boolean,
        glassDepth: Int
    ) {
        if (!isValidWidgetId(appWidgetId)) return
        val prefs = prefs(context)
        prefs.edit()
            .registerWidget(prefs, appWidgetId)
            .putString(key(appWidgetId, KEY_WIDGET_STYLE), widgetStyle.prefValue)
            .putInt(key(appWidgetId, KEY_BLUR_INTENSITY), blurIntensity.coercePercent())
            .putInt(
                key(appWidgetId, KEY_TRANSPARENCY_PERCENT),
                transparencyPercent.coerceTransparency()
            )
            .putInt(key(appWidgetId, KEY_BORDER_GLOW), borderGlow.coercePercent())
            .putBoolean(key(appWidgetId, KEY_NOISE_TEXTURE), noiseTexture)
            .putInt(key(appWidgetId, KEY_CORNER_RADIUS_DP), cornerRadiusDp.coerceCornerRadius())
            .putBoolean(key(appWidgetId, KEY_DYNAMIC_REFLECTIONS), dynamicReflections)
            .putBoolean(key(appWidgetId, KEY_MATCH_WALLPAPER_COLORS), matchWallpaperColors)
            .putInt(key(appWidgetId, KEY_GLASS_DEPTH), glassDepth.coercePercent())
            .putString(key(appWidgetId, KEY_LANGUAGE), LocaleHelper.getSelectedLanguage(context))
            .putLong(key(appWidgetId, KEY_UPDATED_AT), System.currentTimeMillis())
            .commit()
    }

    fun saveTransparency(context: Context, appWidgetId: Int, transparencyPercent: Int) {
        if (!isValidWidgetId(appWidgetId)) return
        val prefs = prefs(context)
        prefs.edit()
            .registerWidget(prefs, appWidgetId)
            .putInt(
                key(appWidgetId, KEY_TRANSPARENCY_PERCENT),
                transparencyPercent.coerceTransparency()
            )
            .putString(key(appWidgetId, KEY_LANGUAGE), LocaleHelper.getSelectedLanguage(context))
            .putLong(key(appWidgetId, KEY_UPDATED_AT), System.currentTimeMillis())
            .commit()
    }

    fun saveBackgroundImageUri(context: Context, appWidgetId: Int, backgroundImageUri: String) {
        if (!isValidWidgetId(appWidgetId)) return
        val prefs = prefs(context)
        prefs.edit()
            .registerWidget(prefs, appWidgetId)
            .putString(key(appWidgetId, KEY_BACKGROUND_IMAGE_URI), backgroundImageUri)
            .putString(key(appWidgetId, KEY_LANGUAGE), LocaleHelper.getSelectedLanguage(context))
            .putLong(key(appWidgetId, KEY_UPDATED_AT), System.currentTimeMillis())
            .commit()
    }

    fun clearBackgroundImageUri(context: Context, appWidgetId: Int) {
        if (!isValidWidgetId(appWidgetId)) return
        val prefs = prefs(context)
        prefs.edit()
            .registerWidget(prefs, appWidgetId)
            .remove(key(appWidgetId, KEY_BACKGROUND_IMAGE_URI))
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
            copyKey(prefs, editor, oldId, newId, KEY_TRANSPARENCY_PERCENT, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_WIDGET_STYLE, ValueType.StringValue)
            copyKey(prefs, editor, oldId, newId, KEY_BLUR_INTENSITY, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_BORDER_GLOW, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_NOISE_TEXTURE, ValueType.BooleanValue)
            copyKey(prefs, editor, oldId, newId, KEY_CORNER_RADIUS_DP, ValueType.IntValue)
            copyKey(
                prefs,
                editor,
                oldId,
                newId,
                KEY_DYNAMIC_REFLECTIONS,
                ValueType.BooleanValue
            )
            copyKey(
                prefs,
                editor,
                oldId,
                newId,
                KEY_MATCH_WALLPAPER_COLORS,
                ValueType.BooleanValue
            )
            copyKey(prefs, editor, oldId, newId, KEY_GLASS_DEPTH, ValueType.IntValue)
            copyKey(prefs, editor, oldId, newId, KEY_BACKGROUND_IMAGE_URI, ValueType.StringValue)
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

    fun alphaForTransparency(transparencyPercent: Int): Float {
        val transparentPart = transparencyPercent.coerceTransparency() / 100f
        return 1f - transparentPart
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

    private fun SharedPreferences.Editor.putIntIfAbsent(
        prefs: SharedPreferences,
        key: String,
        value: Int
    ): SharedPreferences.Editor {
        if (!prefs.contains(key)) {
            putInt(key, value)
        }
        return this
    }

    private fun SharedPreferences.Editor.putBooleanIfAbsent(
        prefs: SharedPreferences,
        key: String,
        value: Boolean
    ): SharedPreferences.Editor {
        if (!prefs.contains(key)) {
            putBoolean(key, value)
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
        remove(key(appWidgetId, KEY_TRANSPARENCY_PERCENT))
        remove(key(appWidgetId, KEY_WIDGET_STYLE))
        remove(key(appWidgetId, KEY_BLUR_INTENSITY))
        remove(key(appWidgetId, KEY_BORDER_GLOW))
        remove(key(appWidgetId, KEY_NOISE_TEXTURE))
        remove(key(appWidgetId, KEY_CORNER_RADIUS_DP))
        remove(key(appWidgetId, KEY_DYNAMIC_REFLECTIONS))
        remove(key(appWidgetId, KEY_MATCH_WALLPAPER_COLORS))
        remove(key(appWidgetId, KEY_GLASS_DEPTH))
        remove(key(appWidgetId, KEY_BACKGROUND_IMAGE_URI))
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
            ValueType.BooleanValue -> editor.putBoolean(newKey, prefs.getBoolean(oldKey, false))
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

    private fun Int.coerceTransparency(): Int {
        return coerceIn(MIN_TRANSPARENCY_PERCENT, MAX_TRANSPARENCY_PERCENT)
    }

    private fun Int.coercePercent(): Int {
        return coerceIn(0, 100)
    }

    private fun Int.coerceCornerRadius(): Int {
        return coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
    }

    private fun isValidWidgetId(appWidgetId: Int): Boolean {
        return appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && appWidgetId > 0
    }

    private enum class ValueType {
        StringValue,
        IntValue,
        BooleanValue,
        LongValue
    }
}

internal enum class WidgetStyle(val prefValue: String) {
    LiquidGlass("liquid_glass"),
    DarkMatte("dark_matte"),
    Solid("solid"),
    AdaptiveSystem("adaptive_system");

    companion object {
        fun fromPrefValue(value: String?): WidgetStyle {
            return entries.firstOrNull { it.prefValue == value } ?: LiquidGlass
        }
    }
}

internal data class WidgetSize(
    val minWidth: Int,
    val minHeight: Int,
    val maxWidth: Int,
    val maxHeight: Int
)
