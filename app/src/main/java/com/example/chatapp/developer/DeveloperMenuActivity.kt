package com.example.chatapp.developer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.BuildConfig
import com.example.chatapp.ChatResponseNotifications
import com.example.chatapp.LanguageManager
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.SafeLog
import com.example.chatapp.util.setHapticClickListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeveloperMenuActivity : AppCompatActivity() {

    private var pendingNotificationTest = false

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val shouldSendTest = pendingNotificationTest
        pendingNotificationTest = false
        if (granted && shouldSendTest) {
            postTestNotification()
        } else if (shouldSendTest) {
            showMessage("debug_notifications_permission_missing")
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_developer_menu)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.security_background)

        bindStaticText()
        bindActions()
    }

    private fun bindStaticText() {
        findViewById<TextView>(R.id.tvDeveloperBuild)?.text = LocaleHelper.formatString(
            this,
            "debug_menu_build",
            BuildConfig.VERSION_NAME
        )
    }

    private fun bindActions() {
        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }

        findViewById<View>(R.id.cardClearCache).setHapticClickListener {
            showConfirmation(
                titleKey = "debug_clear_cache_title",
                messageKey = "debug_clear_cache_confirm"
            ) {
                clearCache()
            }
        }

        findViewById<View>(R.id.cardTestNotifications).setHapticClickListener {
            sendTestNotification()
        }

        findViewById<View>(R.id.cardResetSettings).setHapticClickListener {
            showConfirmation(
                titleKey = "debug_reset_settings_title",
                messageKey = "debug_reset_settings_confirm"
            ) {
                DeveloperActions.resetAppSettings(applicationContext)
                Toast.makeText(
                    this,
                    text("debug_settings_reset_done"),
                    Toast.LENGTH_SHORT
                ).show()
                recreate()
            }
        }

        findViewById<View>(R.id.cardResetLogoutLimits).setHapticClickListener {
            showConfirmation(
                titleKey = "debug_reset_logout_limits_title",
                messageKey = "debug_reset_logout_limits_confirm"
            ) {
                DeveloperActions.resetLogoutLimits(applicationContext)
                showMessage("debug_logout_limits_reset_done")
            }
        }

        findViewById<View>(R.id.cardLogs).setHapticClickListener {
            showLogsSheet()
        }
    }

    private fun clearCache() {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                DeveloperActions.clearTemporaryCache(applicationContext)
            }
            showMessage(
                if (success) "debug_cache_cleared" else "debug_cache_clear_partial"
            )
        }
    }

    private fun sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationTest = true
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        postTestNotification()
    }

    private fun postTestNotification() {
        if (ChatResponseNotifications.showDebugTest(this)) {
            showMessage("debug_notifications_test_sent")
        } else {
            showMessage("debug_notifications_permission_missing")
        }
    }

    private fun showLogsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_developer_logs, null)
        dialog.setContentView(view)
        dialog.setOnShowListener { dialogInterface ->
            val sheetDialog = dialogInterface as BottomSheetDialog
            sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }

        fun renderLogs() {
            val entries = SafeLog.recentEntries()
            val hasLogs = entries.isNotEmpty()
            view.findViewById<TextView>(R.id.tvLogsEmpty).isVisible = !hasLogs
            view.findViewById<View>(R.id.logsScroll).isVisible = hasLogs
            view.findViewById<TextView>(R.id.tvLogsContent).text =
                if (hasLogs) SafeLog.formatEntries(entries) else ""
        }

        view.findViewById<View>(R.id.btnCopyLogs).setHapticClickListener {
            val formattedLogs = SafeLog.formatEntries(SafeLog.recentEntries())
            if (formattedLogs.isBlank()) {
                showMessage("debug_logs_empty")
                return@setHapticClickListener
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(text("debug_logs_clip_label"), formattedLogs)
            )
            showMessage("debug_logs_copied")
        }

        view.findViewById<View>(R.id.btnClearLogs).setHapticClickListener {
            showConfirmation(
                titleKey = "debug_logs_clear_title",
                messageKey = "debug_logs_clear_confirm",
                confirmKey = "button_delete"
            ) {
                SafeLog.clearEntries()
                renderLogs()
                showMessage("debug_logs_cleared")
            }
        }

        renderLogs()
        dialog.show()
    }

    private fun showConfirmation(
        titleKey: String,
        messageKey: String,
        confirmKey: String = "button_confirm",
        onConfirm: () -> Unit
    ) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_debug_confirm, null)
        dialog.setContentView(view)
        dialog.setOnShowListener { dialogInterface ->
            val sheetDialog = dialogInterface as BottomSheetDialog
            sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }

        view.findViewById<TextView>(R.id.tvConfirmTitle).text = text(titleKey)
        view.findViewById<TextView>(R.id.tvConfirmMessage).text = text(messageKey)
        view.findViewById<TextView>(R.id.btnConfirmCancel).text = text("button_cancel")
        view.findViewById<TextView>(R.id.btnConfirmAction).text = text(confirmKey)

        view.findViewById<View>(R.id.btnConfirmCancel).setHapticClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnConfirmAction).setHapticClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
    }

    private fun showMessage(key: String) {
        val message = text(key)
        runCatching {
            Snackbar.make(findViewById(R.id.developerRoot), message, Snackbar.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun text(key: String): String = LocaleHelper.getString(this, key)
}
