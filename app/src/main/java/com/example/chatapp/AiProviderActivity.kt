package com.example.chatapp

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.network.AiProvider
import com.example.chatapp.network.AiProviderSettings
import com.example.chatapp.util.setHapticClickListener

class AiProviderActivity : AppCompatActivity() {

    private lateinit var providerSettings: AiProviderSettings

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_provider)

        window.statusBarColor = Color.TRANSPARENT

        providerSettings = AiProviderSettings(this)

        findViewById<View>(R.id.btnBack).setHapticClickListener { finish() }

        setupProviderSelection()
        setupApiKeyInput()
        applyTranslations()
        updateUi()
    }

    private fun setupProviderSelection() {
        findViewById<View>(R.id.optionVsegpt).setHapticClickListener {
            providerSettings.setProvider(AiProvider.VSEGPT)
            updateUi()
        }

        findViewById<View>(R.id.optionOpenai).setHapticClickListener {
            providerSettings.setProvider(AiProvider.OPENAI)
            updateUi()
        }
    }

    private fun setupApiKeyInput() {
        val etApiKey = findViewById<EditText>(R.id.etApiKey)

        etApiKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveApiKey()
                true
            } else false
        }

        findViewById<View>(R.id.btnSaveApiKey).setHapticClickListener {
            saveApiKey()
        }
    }

    private fun saveApiKey() {
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val key = etApiKey.text.toString().trim()

        if (key.isBlank()) {
            Toast.makeText(this, LocaleHelper.getString(this, "ai_provider_key_empty"), Toast.LENGTH_SHORT).show()
            return
        }

        providerSettings.setOpenAiApiKey(key)
        Toast.makeText(this, LocaleHelper.getString(this, "ai_provider_key_saved"), Toast.LENGTH_SHORT).show()

        // Прячем клавиатуру после сохранения ключа.
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etApiKey.windowToken, 0)
    }

    private fun updateUi() {
        val currentProvider = providerSettings.getProvider()
        val isOpenAi = currentProvider == AiProvider.OPENAI

        // Обновляем отметки выбранного провайдера.
        findViewById<ImageView>(R.id.ivVsegptCheck).visibility =
            if (!isOpenAi) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.ivOpenaiCheck).visibility =
            if (isOpenAi) View.VISIBLE else View.GONE

        // Показываем настройки OpenAI только для прямого режима.
        findViewById<LinearLayout>(R.id.openaiKeySection).visibility =
            if (isOpenAi) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.openaiModelInfoSection).visibility =
            if (isOpenAi) View.VISIBLE else View.GONE

        // Не показываем сохраненный ключ целиком.
        val existingKey = providerSettings.getOpenAiApiKey()
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        if (existingKey.isNotBlank() && etApiKey.text.isNullOrBlank()) {
            etApiKey.hint = maskApiKey(existingKey)
        }
    }

    private fun maskApiKey(key: String): String {
        if (key.length <= 8) return "••••••••"
        return key.take(4) + "•".repeat(key.length - 8) + key.takeLast(4)
    }

    private fun applyTranslations() {
        findViewById<TextView>(R.id.tvToolbarTitle)?.text =
            LocaleHelper.getString(this, "ai_provider_title")
        findViewById<TextView>(R.id.tvSectionProvider)?.text =
            LocaleHelper.getString(this, "ai_provider_section")
        findViewById<TextView>(R.id.tvVsegptTitle)?.text =
            LocaleHelper.getString(this, "ai_provider_vsegpt_title")
        findViewById<TextView>(R.id.tvVsegptDesc)?.text =
            LocaleHelper.getString(this, "ai_provider_vsegpt_desc")
        findViewById<TextView>(R.id.tvOpenaiTitle)?.text =
            LocaleHelper.getString(this, "ai_provider_openai_title")
        findViewById<TextView>(R.id.tvOpenaiDesc)?.text =
            LocaleHelper.getString(this, "ai_provider_openai_desc")
        findViewById<TextView>(R.id.tvSectionApiKey)?.text =
            LocaleHelper.getString(this, "ai_provider_api_key_section")
        findViewById<TextView>(R.id.tvApiKeyHint)?.text =
            LocaleHelper.getString(this, "ai_provider_api_key_hint")
        findViewById<TextView>(R.id.btnSaveApiKey)?.text =
            LocaleHelper.getString(this, "button_save")
        findViewById<TextView>(R.id.tvSectionModel)?.text =
            LocaleHelper.getString(this, "ai_provider_model_section")
        findViewById<TextView>(R.id.tvToolsInfo)?.text =
            LocaleHelper.getString(this, "ai_provider_tools_info")
    }
}
