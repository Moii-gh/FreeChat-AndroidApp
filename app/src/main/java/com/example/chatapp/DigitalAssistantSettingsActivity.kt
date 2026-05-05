package com.example.chatapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.chatapp.assistant.DigitalAssistantOverlayService
import com.example.chatapp.assistant.DigitalAssistantPermissionManager
import com.example.chatapp.assistant.DigitalAssistantSettingsStore
import com.example.chatapp.util.setHapticClickListener
import com.google.android.material.switchmaterial.SwitchMaterial

class DigitalAssistantSettingsActivity : AppCompatActivity() {

    private lateinit var digitalAssistantSettings: DigitalAssistantSettingsStore
    private lateinit var digitalAssistantPermissions: DigitalAssistantPermissionManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_digital_assistant_settings)

        window.statusBarColor = Color.TRANSPARENT

        digitalAssistantSettings = DigitalAssistantSettingsStore(this)
        digitalAssistantPermissions = DigitalAssistantPermissionManager(this)

        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }

        setupDigitalAssistantSettings()
        applyTranslations()
        updateDigitalAssistantUi()
    }

    override fun onResume() {
        super.onResume()
        applyTranslations()
        updateDigitalAssistantUi()
    }

    private fun applyTranslations() {
        findViewById<TextView>(R.id.tvToolbarTitle)?.text =
            LocaleHelper.getString(this, "digital_assistant_short_title")
        findViewById<TextView>(R.id.tvDigitalAssistantTitle)?.text =
            LocaleHelper.getString(this, "digital_assistant_title")
        findViewById<TextView>(R.id.tvDigitalAssistantDescription)?.text =
            LocaleHelper.getString(this, "digital_assistant_description")
        findViewById<TextView>(R.id.tvDigitalAssistantEnable)?.text =
            LocaleHelper.getString(this, "digital_assistant_enable")
        findViewById<TextView>(R.id.tvDigitalAssistantPermissions)?.text =
            LocaleHelper.getString(this, "digital_assistant_permissions_explanation")
        findViewById<TextView>(R.id.btnAssignDigitalAssistant)?.text =
            LocaleHelper.getString(this, "digital_assistant_assign_default")
        findViewById<TextView>(R.id.btnGrantDigitalAssistantPermissions)?.text =
            LocaleHelper.getString(this, "digital_assistant_grant_permissions")
        findViewById<TextView>(R.id.tvHowItWorksTitle)?.text =
            LocaleHelper.getString(this, "digital_assistant_how_it_works_title")
        findViewById<TextView>(R.id.tvHowItWorksDesc)?.text =
            LocaleHelper.getString(this, "digital_assistant_how_it_works_desc")
    }

    private fun setupDigitalAssistantSettings() {
        findViewById<View>(R.id.digitalAssistantEnableRow).setHapticClickListener {
            val switch = findViewById<SwitchMaterial>(R.id.switchDigitalAssistant)
            switch.isChecked = !switch.isChecked
        }

        findViewById<SwitchMaterial>(R.id.switchDigitalAssistant).setOnCheckedChangeListener { _, isChecked ->
            if (digitalAssistantSettings.isEnabled == isChecked) return@setOnCheckedChangeListener
            if (isChecked && !digitalAssistantPermissions.isFreeChatDefaultAssistant()) {
                digitalAssistantSettings.isEnabled = false
                findViewById<SwitchMaterial>(R.id.switchDigitalAssistant).isChecked = false
                openAssistantSettingsOrExplain()
                updateDigitalAssistantUi()
                return@setOnCheckedChangeListener
            }
            digitalAssistantSettings.isEnabled = isChecked
            if (isChecked) {
                requestDigitalAssistantPermissions()
                startFallbackNotificationIfPossible()
            }
            updateDigitalAssistantUi()
        }

        findViewById<View>(R.id.btnAssignDigitalAssistant).setHapticClickListener {
            openAssistantSettingsOrExplain()
        }
        findViewById<View>(R.id.btnGrantDigitalAssistantPermissions).setHapticClickListener {
            requestDigitalAssistantPermissions()
        }
    }

    private fun updateDigitalAssistantUi() {
        val switch = findViewById<SwitchMaterial>(R.id.switchDigitalAssistant)
        if (switch.isChecked != digitalAssistantSettings.isEnabled) {
            switch.isChecked = digitalAssistantSettings.isEnabled
        }
        val status = digitalAssistantPermissions.status(digitalAssistantSettings)
        findViewById<TextView>(R.id.tvDigitalAssistantStatus)?.text =
            digitalAssistantPermissions.statusLabel(status)
    }

    private fun openAssistantSettingsOrExplain() {
        val intent = digitalAssistantPermissions.voiceInputSettingsIntent()
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(
                this,
                LocaleHelper.getString(this, "digital_assistant_settings_unavailable"),
                Toast.LENGTH_LONG
            ).show()
            startFallbackNotificationIfPossible()
        }
    }

    private fun requestDigitalAssistantPermissions() {
        if (!digitalAssistantPermissions.canDrawOverlays()) {
            val intent = digitalAssistantPermissions.overlaySettingsIntent()
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun startFallbackNotificationIfPossible() {
        if (!digitalAssistantSettings.isFallbackNotificationEnabled) return
        if (!digitalAssistantPermissions.hasNotificationPermission()) return
        runCatching {
            DigitalAssistantOverlayService.startFallbackNotification(this)
        }.onFailure {
            Toast.makeText(
                this,
                LocaleHelper.getString(this, "digital_assistant_fallback_unavailable"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 214
    }
}
