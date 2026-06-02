package com.example.chatapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.notifications.SmartNotificationsPermissionManager
import com.example.chatapp.notifications.SmartNotificationsSettingsStore
import com.example.chatapp.notifications.SmartNotificationsWhitelistActivity
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

        findViewById<View>(R.id.smartNotificationsWhitelistRow).setHapticClickListener {
            startActivity(Intent(this, SmartNotificationsWhitelistActivity::class.java))
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
        findViewById<TextView>(R.id.btnSmartNotificationsAllowAccess)?.text =
            LocaleHelper.getString(this, "smart_notifications_allow_access")
        findViewById<TextView>(R.id.tvSmartNotificationsWhitelistTitle)?.text =
            LocaleHelper.getString(this, "smart_notifications_whitelist_title")
        findViewById<TextView>(R.id.tvSmartNotificationsWhitelistDesc)?.text =
            LocaleHelper.getString(this, "smart_notifications_whitelist_desc")
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
        val hasAccess = SmartNotificationsPermissionManager.hasNotificationListenerAccess(this)
        val isEffectivelyEnabled = smartNotificationsSettings.isEnabled && hasAccess

        updatingSwitch = true
        findViewById<SwitchMaterial>(R.id.switchSmartNotifications).isChecked = isEffectivelyEnabled
        updatingSwitch = false

        findViewById<TextView>(R.id.tvSmartNotificationsStatus)?.text = when {
            smartNotificationsSettings.isEnabled && !hasAccess ->
                LocaleHelper.getString(this, "smart_notifications_status_permission_needed")
            isEffectivelyEnabled -> LocaleHelper.getString(this, "smart_notifications_status_enabled")
            else -> LocaleHelper.getString(this, "smart_notifications_status_disabled")
        }

        val visibility = if (isEffectivelyEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.smartNotificationsDivider)?.visibility = visibility
        findViewById<View>(R.id.smartNotificationsWhitelistRow)?.visibility = visibility
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
