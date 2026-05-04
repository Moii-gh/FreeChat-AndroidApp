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
import android.view.animation.OvershootInterpolator
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.chatapp.assistant.DigitalAssistantOverlayService
import com.example.chatapp.assistant.DigitalAssistantPermissionManager
import com.example.chatapp.assistant.DigitalAssistantSettingsStore
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.network.AiProviderSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.ui.AnimatedAvatarBorderDrawable
import com.example.chatapp.ui.AnimatedProfileCardDrawable
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.example.chatapp.util.setHapticClickListener

class SettingsActivity : AppCompatActivity() {

    private var ivDialogAvatar: ImageView? = null
    private var dialogAvatarLetter: TextView? = null

    private lateinit var sessionStore: SharedPrefsAccountSessionStore
    private lateinit var accountSettings: AccountScopedSettings
    private lateinit var aiProviderSettings: AiProviderSettings
    private lateinit var digitalAssistantSettings: DigitalAssistantSettingsStore
    private lateinit var digitalAssistantPermissions: DigitalAssistantPermissionManager
    private var appliedLanguageCode: String? = null
    private var avatarBorderDrawable: AnimatedAvatarBorderDrawable? = null
    private var profileCardDrawable: AnimatedProfileCardDrawable? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    private val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            accountSettings.saveAvatarUri(it.toString())
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            updateProfileUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        window.statusBarColor = Color.TRANSPARENT
        setupProfileVisualEffects()

        sessionStore = SharedPrefsAccountSessionStore(this)
        accountSettings = AccountScopedSettings(this)
        accountSettings.migrateLegacyDataIfNeeded()
        aiProviderSettings = AiProviderSettings(accountSettings)
        digitalAssistantSettings = DigitalAssistantSettingsStore(this)
        digitalAssistantPermissions = DigitalAssistantPermissionManager(this)
        appliedLanguageCode = LocaleHelper.getSelectedLanguage(this)

        updateProfileUi()

        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }
        findViewById<View>(R.id.flAvatar).setHapticClickListener { pickImage.launch("image/*") }

        findViewById<View>(R.id.btnLogout).setHapticClickListener {
            sessionStore.clearSession()
            Toast.makeText(this, LocaleHelper.getString(this, "toast_logout"), Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }

        findViewById<View>(R.id.btnEditProfile).setHapticClickListener { showEditProfileDialog() }

        findViewById<View>(R.id.itemPersonalization).setHapticClickListener {
            startActivity(Intent(this, PersonalizationActivity::class.java))
        }

        findViewById<View>(R.id.itemAiProvider).setHapticClickListener {
            startActivity(Intent(this, AiProviderActivity::class.java))
        }

        findViewById<View>(R.id.itemAdultMode).setHapticClickListener {
            val enabled = !aiProviderSettings.isAdultModeEnabled()
            aiProviderSettings.setAdultModeEnabled(enabled)
            animateAdultModeSwitch()
            updateAdultModeUi()
        }

        findViewById<SwitchMaterial>(R.id.switchAdultMode).setOnCheckedChangeListener { _, isChecked ->
            if (aiProviderSettings.isAdultModeEnabled() != isChecked) {
                aiProviderSettings.setAdultModeEnabled(isChecked)
                animateAdultModeSwitch()
                updateAdultModeUi()
            }
        }

        setupDigitalAssistantSettings()

        findViewById<View>(R.id.itemLinks).setHapticClickListener {
            startActivity(Intent(this, SharedLinksActivity::class.java))
        }

        findViewById<View>(R.id.itemLanguage).setHapticClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }

        findViewById<View>(R.id.itemSecurity).setHapticClickListener {
            startActivity(Intent(this, SecurityActivity::class.java))
        }

        findViewById<View>(R.id.itemAbout).setHapticClickListener {
            openExternalLink(BuildConfig.PUBLIC_INFO_URL)
        }

        findViewById<View>(R.id.itemReport).setHapticClickListener {
            openExternalLink(BuildConfig.SUPPORT_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        startProfileVisualEffects()
        val currentLanguageCode = LocaleHelper.getSelectedLanguage(this)
        if (appliedLanguageCode != null && appliedLanguageCode != currentLanguageCode) {
            recreate()
            return
        }
        appliedLanguageCode = currentLanguageCode
        updateProfileUi()
        applyTranslations()
        updateDigitalAssistantUi()
    }

    override fun onPause() {
        stopProfileVisualEffects()
        super.onPause()
    }

    private fun setupProfileVisualEffects() {
        val density = resources.displayMetrics.density
        profileCardDrawable = AnimatedProfileCardDrawable(density)
        avatarBorderDrawable = AnimatedAvatarBorderDrawable(density)
        findViewById<View>(R.id.profileCard).background = profileCardDrawable
        findViewById<View>(R.id.avatarGlowContainer).background = avatarBorderDrawable
    }

    private fun startProfileVisualEffects() {
        profileCardDrawable?.start()
        avatarBorderDrawable?.start()
    }

    private fun stopProfileVisualEffects() {
        profileCardDrawable?.stop()
        avatarBorderDrawable?.stop()
    }

    private fun updateProfileUi() {
        val userName = accountSettings.getDisplayName(
            sessionStore.getCurrentUserName() ?: LocaleHelper.getString(this, "label_user")
        )
        val userEmail = sessionStore.getCurrentUserEmail().orEmpty()
        val avatarUri = accountSettings.getAvatarUri()

        findViewById<TextView>(R.id.tvUserName).text = userName
        findViewById<TextView>(R.id.tvUserEmail).text = userEmail
        updateAdultModeUi()

        val tvLetter = findViewById<TextView>(R.id.tvAvatarLetter)
        val ivAvatar = findViewById<ImageView>(R.id.ivAvatar)
        val firstLetter = userName.firstOrNull()?.uppercase() ?: "P"

        if (avatarUri != null) {
            ivAvatar.visibility = View.VISIBLE
            tvLetter.visibility = View.GONE
            ivDialogAvatar?.visibility = View.VISIBLE
            dialogAvatarLetter?.visibility = View.GONE

            try {
                val parsedUri = android.net.Uri.parse(avatarUri)
                ivAvatar.setImageURI(parsedUri)
                ivDialogAvatar?.setImageURI(parsedUri)
            } catch (_: Exception) {
                ivAvatar.visibility = View.GONE
                tvLetter.visibility = View.VISIBLE
                tvLetter.text = firstLetter
                ivDialogAvatar?.visibility = View.GONE
                dialogAvatarLetter?.visibility = View.VISIBLE
                dialogAvatarLetter?.text = firstLetter
            }
        } else {
            ivAvatar.visibility = View.GONE
            tvLetter.visibility = View.VISIBLE
            tvLetter.text = firstLetter
            ivDialogAvatar?.visibility = View.GONE
            dialogAvatarLetter?.visibility = View.VISIBLE
            dialogAvatarLetter?.text = firstLetter
        }
    }

    private fun applyTranslations() {
        findViewById<TextView>(R.id.tvToolbarTitle)?.text = LocaleHelper.getString(this, "profile_and_settings")
        findViewById<TextView>(R.id.btnEditProfile)?.text = LocaleHelper.getString(this, "button_edit_profile")
        findViewById<TextView>(R.id.tvSettingsHeader)?.text = LocaleHelper.getString(this, "settings_title")
        findViewById<TextView>(R.id.tvLabelPersonalization)?.text = LocaleHelper.getString(this, "button_personalization")
        findViewById<TextView>(R.id.tvLabelLinks)?.text = LocaleHelper.getString(this, "settings_shared_links")
        findViewById<TextView>(R.id.tvLabelLanguage)?.text = LocaleHelper.getString(this, "button_language")
        findViewById<TextView>(R.id.tvLabelSecurity)?.text = LocaleHelper.getString(this, "button_security")
        findViewById<TextView>(R.id.tvLabelAiProvider)?.text = LocaleHelper.getString(this, "ai_provider_title")
        findViewById<TextView>(R.id.tvAiProviderValue)?.text = aiProviderSettings.getProvider().displayLabel
        findViewById<TextView>(R.id.tvLabelAdultMode)?.text = LocaleHelper.getString(this, "adult_mode_title")
        findViewById<TextView>(R.id.tvAdultModeValue)?.text = LocaleHelper.getString(this, "adult_replies_title")
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
        updateDigitalAssistantUi()

        findViewById<TextView>(R.id.tvLabelAbout)?.text = LocaleHelper.getString(this, "button_about")
        findViewById<TextView>(R.id.tvLabelReport)?.text = LocaleHelper.getString(this, "button_report_problem")
        findViewById<TextView>(R.id.tvAppVersion)?.text = LocaleHelper.formatString(
            this,
            "app_version",
            BuildConfig.VERSION_NAME
        )
    }

    private fun updateAdultModeUi() {
        val enabled = aiProviderSettings.isAdultModeEnabled()
        val switch = findViewById<SwitchMaterial>(R.id.switchAdultMode)
        if (switch.isChecked != enabled) {
            switch.isChecked = enabled
        }
        findViewById<TextView>(R.id.tvAiProviderValue)?.text = aiProviderSettings.getProvider().displayLabel
        findViewById<TextView>(R.id.tvAdultModeValue)?.text =
            if (enabled) LocaleHelper.getString(this, "settings_value_on")
            else LocaleHelper.getString(this, "adult_replies_title")
    }

    private fun animateAdultModeSwitch() {
        findViewById<SwitchMaterial>(R.id.switchAdultMode)?.animate()
            ?.scaleX(0.94f)
            ?.scaleY(0.94f)
            ?.setDuration(70L)
            ?.withEndAction {
                findViewById<SwitchMaterial>(R.id.switchAdultMode)?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setInterpolator(OvershootInterpolator(2.2f))
                    ?.setDuration(180L)
                    ?.start()
            }
            ?.start()
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
        if (!::digitalAssistantSettings.isInitialized) return
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

    private fun showEditProfileDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_edit_profile, null)
        dialog.setContentView(view)

        dialog.setOnShowListener { dialogInterface ->
            val sheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }

        val etName = view.findViewById<EditText>(R.id.etProfileName)
        val etEmail = view.findViewById<EditText>(R.id.etProfileEmail)

        ivDialogAvatar = view.findViewById(R.id.ivDialogAvatar)
        dialogAvatarLetter = view.findViewById(R.id.dialogAvatarLetter)

        view.findViewById<TextView>(R.id.tvLabelDialogName)?.text = LocaleHelper.getString(this, "label_name")
        view.findViewById<TextView>(R.id.tvLabelDialogEmail)?.text = LocaleHelper.getString(this, "label_email")
        view.findViewById<TextView>(R.id.btnSaveProfile)?.text = LocaleHelper.getString(this, "button_save")

        view.findViewById<View>(R.id.dialogAvatarContainer).setHapticClickListener {
            pickImage.launch("image/*")
        }

        dialog.setOnDismissListener {
            ivDialogAvatar = null
            dialogAvatarLetter = null
        }

        etName.setText(
            accountSettings.getDisplayName(
                sessionStore.getCurrentUserName() ?: LocaleHelper.getString(this, "label_user")
            )
        )
        etEmail.setText(sessionStore.getCurrentUserEmail().orEmpty())
        etEmail.isEnabled = false
        etEmail.alpha = 0.7f
        updateProfileUi()

        view.findViewById<View>(R.id.btnSaveProfile).setHapticClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(this, LocaleHelper.getString(this, "toast_name_empty"), Toast.LENGTH_SHORT).show()
                return@setHapticClickListener
            }

            accountSettings.saveDisplayName(newName)
            updateProfileUi()
            Toast.makeText(this, LocaleHelper.getString(this, "toast_profile_saved"), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openExternalLink(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, LocaleHelper.getString(this, "external_link_unavailable"), Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 214
    }
}
