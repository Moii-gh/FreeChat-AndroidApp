package com.example.chatapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.notifications.SmartNotificationsPermissionManager
import com.example.chatapp.notifications.SmartNotificationsSettingsStore
import com.example.chatapp.util.setHapticClickListener
import com.google.android.material.switchmaterial.SwitchMaterial

class SmartNotificationsSettingsActivity : AppCompatActivity() {

    private lateinit var smartNotificationsSettings: SmartNotificationsSettingsStore
    private var updatingSwitch = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_notifications_settings)

        window.statusBarColor = Color.TRANSPARENT

        smartNotificationsSettings = SmartNotificationsSettingsStore(this)

        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }
        setupSmartNotificationsSettings()
        applyTranslations()
        updateSmartNotificationsUi()
    }

    override fun onResume() {
        super.onResume()
        applyTranslations()
        updateSmartNotificationsUi()
    }

    private fun setupSmartNotificationsSettings() {
        findViewById<View>(R.id.smartNotificationsEnableRow).setHapticClickListener {
            val switch = findViewById<SwitchMaterial>(R.id.switchSmartNotifications)
            switch.isChecked = !switch.isChecked
        }

        findViewById<SwitchMaterial>(R.id.switchSmartNotifications).setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                enableSmartNotifications(openSettingsIfNeeded = true)
            } else {
                smartNotificationsSettings.isEnabled = false
                updateSmartNotificationsUi()
            }
        }

        findViewById<View>(R.id.btnSmartNotificationsAllowAccess).setHapticClickListener {
            enableSmartNotifications(openSettingsIfNeeded = true)
        }
    }

    private fun applyTranslations() {
        findViewById<TextView>(R.id.tvToolbarTitle)?.text =
            LocaleHelper.getString(this, "smart_notifications_title")
        findViewById<TextView>(R.id.tvSmartNotificationsTitle)?.text =
            LocaleHelper.getString(this, "smart_notifications_title")
        findViewById<TextView>(R.id.tvSmartNotificationsDescription)?.text =
            LocaleHelper.getString(this, "smart_notifications_description")
        findViewById<TextView>(R.id.tvSmartNotificationsEnable)?.text =
            LocaleHelper.getString(this, "smart_notifications_enable")
        findViewById<TextView>(R.id.tvSmartNotificationsPermissions)?.text =
            LocaleHelper.getString(this, "smart_notifications_permissions_explanation")
        findViewById<TextView>(R.id.tvSmartNotificationsApiKeyLabel)?.text =
            LocaleHelper.getString(this, "smart_notifications_api_key_label")
        findViewById<EditText>(R.id.etSmartNotificationsApiKey)?.hint =
            LocaleHelper.getString(this, "smart_notifications_api_key_hint")
        findViewById<TextView>(R.id.tvSmartNotificationsApiKeySavedHint)?.text =
            LocaleHelper.getString(this, "smart_notifications_api_key_saved_hint")
        findViewById<TextView>(R.id.btnSmartNotificationsAllowAccess)?.text =
            LocaleHelper.getString(this, "smart_notifications_allow_access")
        findViewById<TextView>(R.id.tvHowItWorksTitle)?.text =
            LocaleHelper.getString(this, "smart_notifications_how_it_works_title")
        findViewById<TextView>(R.id.tvHowItWorksDesc)?.text =
            LocaleHelper.getString(this, "smart_notifications_how_it_works_desc")
        findViewById<TextView>(R.id.tvPrivacyTitle)?.text =
            LocaleHelper.getString(this, "smart_notifications_privacy_title")
        findViewById<TextView>(R.id.tvPrivacyDesc)?.text =
            LocaleHelper.getString(this, "smart_notifications_privacy_desc")
    }

    private fun enableSmartNotifications(openSettingsIfNeeded: Boolean) {
        val enteredApiKey = findViewById<EditText>(R.id.etSmartNotificationsApiKey)
            ?.text
            ?.toString()
            ?.trim()
            .orEmpty()
        if (enteredApiKey.isNotBlank()) {
            smartNotificationsSettings.saveVseGptApiKey(enteredApiKey)
        }

        if (!smartNotificationsSettings.hasVseGptApiKey()) {
            smartNotificationsSettings.isEnabled = false
            Toast.makeText(
                this,
                LocaleHelper.getString(this, "smart_notifications_key_missing"),
                Toast.LENGTH_SHORT
            ).show()
            updateSmartNotificationsUi()
            return
        }

        smartNotificationsSettings.isEnabled = true

        if (
            openSettingsIfNeeded &&
            !SmartNotificationsPermissionManager.hasNotificationListenerAccess(this)
        ) {
            openSmartNotificationsAccessSettings()
        }

        updateSmartNotificationsUi()
    }

    private fun updateSmartNotificationsUi() {
        val hasApiKey = smartNotificationsSettings.hasVseGptApiKey()
        val hasAccess = SmartNotificationsPermissionManager.hasNotificationListenerAccess(this)
        val isEffectivelyEnabled = smartNotificationsSettings.isEnabled && hasApiKey && hasAccess

        updatingSwitch = true
        findViewById<SwitchMaterial>(R.id.switchSmartNotifications).isChecked = isEffectivelyEnabled
        updatingSwitch = false

        findViewById<TextView>(R.id.tvSmartNotificationsStatus)?.text = when {
            !hasApiKey -> LocaleHelper.getString(this, "smart_notifications_status_api_key_needed")
            smartNotificationsSettings.isEnabled && !hasAccess ->
                LocaleHelper.getString(this, "smart_notifications_status_permission_needed")
            isEffectivelyEnabled -> LocaleHelper.getString(this, "smart_notifications_status_enabled")
            else -> LocaleHelper.getString(this, "smart_notifications_status_disabled")
        }

        findViewById<TextView>(R.id.tvSmartNotificationsApiKeySavedHint)?.visibility =
            if (hasApiKey) View.VISIBLE else View.GONE
    }

    private fun openSmartNotificationsAccessSettings() {
        try {
            startActivity(SmartNotificationsPermissionManager.notificationListenerSettingsIntent())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                LocaleHelper.getString(this, "smart_notifications_settings_unavailable"),
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
            Toast.makeText(
                this,
                LocaleHelper.getString(this, "smart_notifications_settings_unavailable"),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
