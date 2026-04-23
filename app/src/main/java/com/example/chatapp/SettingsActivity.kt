package com.example.chatapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.chatapp.util.setHapticClickListener

class SettingsActivity : AppCompatActivity() {

    private var ivDialogAvatar: ImageView? = null
    private var dialogAvatarLetter: TextView? = null

    private lateinit var sessionStore: SharedPrefsAccountSessionStore
    private lateinit var accountSettings: AccountScopedSettings

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

        sessionStore = SharedPrefsAccountSessionStore(this)
        accountSettings = AccountScopedSettings(this)
        accountSettings.migrateLegacyDataIfNeeded()

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

        findViewById<View>(R.id.itemApps).setHapticClickListener {
            startActivity(Intent(this, AiModeActivity::class.java))
        }

        findViewById<View>(R.id.itemLanguage).setHapticClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }

        findViewById<View>(R.id.itemSecurity).setHapticClickListener {
            startActivity(Intent(this, SecurityActivity::class.java))
        }

        findViewById<View>(R.id.itemSubscription).setHapticClickListener {
            startActivity(Intent(this, BillingActivity::class.java))
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
        updateProfileUi()
        applyTranslations()
    }

    private fun updateProfileUi() {
        val userName = accountSettings.getDisplayName(sessionStore.getCurrentUserName() ?: "User")
        val userEmail = sessionStore.getCurrentUserEmail().orEmpty()
        val avatarUri = accountSettings.getAvatarUri()

        findViewById<TextView>(R.id.tvUserName).text = userName
        findViewById<TextView>(R.id.tvUserEmail).text = userEmail

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
        findViewById<TextView>(R.id.tvSettingsHeader)?.text = LocaleHelper.getString(this, "setting")
        findViewById<TextView>(R.id.tvLabelPersonalization)?.text = LocaleHelper.getString(this, "button_personalization")
        findViewById<TextView>(R.id.tvLabelApps)?.text = LocaleHelper.getString(this, "button_apps")
        findViewById<TextView>(R.id.tvLabelLanguage)?.text = LocaleHelper.getString(this, "button_language")
        findViewById<TextView>(R.id.tvLabelSecurity)?.text = LocaleHelper.getString(this, "button_security")
        findViewById<TextView>(R.id.tvLabelSubscription)?.text = "Pro subscription"
        findViewById<TextView>(R.id.tvLabelAbout)?.text = LocaleHelper.getString(this, "button_Information_about")
        findViewById<TextView>(R.id.tvLabelReport)?.text = LocaleHelper.getString(this, "button_report_a_problem")
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

        view.findViewById<TextView>(R.id.tvLabelDialogName)?.text = LocaleHelper.getString(this, "lable_name")
        view.findViewById<TextView>(R.id.tvLabelDialogEmail)?.text = LocaleHelper.getString(this, "lable_email")
        view.findViewById<TextView>(R.id.btnSaveProfile)?.text = LocaleHelper.getString(this, "button_save")

        view.findViewById<View>(R.id.dialogAvatarContainer).setHapticClickListener {
            pickImage.launch("image/*")
        }

        dialog.setOnDismissListener {
            ivDialogAvatar = null
            dialogAvatarLetter = null
        }

        etName.setText(accountSettings.getDisplayName(sessionStore.getCurrentUserName() ?: "User"))
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
            Toast.makeText(this, "Ссылка недоступна", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}
