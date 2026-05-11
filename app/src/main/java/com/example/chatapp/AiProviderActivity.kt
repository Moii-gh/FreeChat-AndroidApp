package com.example.chatapp

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.AiModelCatalog
import com.example.chatapp.network.AiModelOption
import com.example.chatapp.network.AiProvider
import com.example.chatapp.network.AiProviderSettings
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.dto.AiModelDescriptorResponse
import com.example.chatapp.util.setHapticClickListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiProviderActivity : AppCompatActivity() {

    private lateinit var providerSettings: AiProviderSettings
    private var availableModels: List<AiModelOption> = AiModelCatalog.fallbackModels

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
        applyTranslations()
        updateUi()
        loadServerModels()
    }

    private fun setupProviderSelection() {
        findViewById<View>(R.id.optionOpenai).setHapticClickListener {
            providerSettings.setProvider(AiProvider.OPENAI)
            updateUi()
        }

        findViewById<View>(R.id.optionVsegpt).setHapticClickListener {
            providerSettings.setProvider(AiProvider.VSEGPT)
            updateUi()
        }
    }

    private fun updateUi() {
        val currentProvider = providerSettings.getProvider()
        val isOpenAi = currentProvider == AiProvider.OPENAI

        findViewById<ImageView>(R.id.ivOpenaiCheck).visibility =
            if (isOpenAi) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.ivVsegptCheck).visibility =
            if (!isOpenAi) View.VISIBLE else View.GONE

        findViewById<LinearLayout>(R.id.modelInfoSection).visibility = View.VISIBLE
        renderModelOptions(currentProvider)

        findViewById<TextView>(R.id.tvToolsInfo)?.visibility = View.GONE
    }

    private fun renderModelOptions(provider: AiProvider) {
        val container = findViewById<LinearLayout>(R.id.modelOptionsContainer)
        container.removeAllViews()

        val selectedModelKey = providerSettings.getModelKey()
        modelsForProvider(provider).forEach { model ->
            container.addView(createModelOptionView(model, model.modelKey == selectedModelKey))
        }
    }

    private fun createModelOptionView(model: AiModelOption, selected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setSelectableBackground()
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textColumn.addView(TextView(this).apply {
            text = model.displayName
            setTextColor(Color.WHITE)
            textSize = 16f
        })

        row.addView(textColumn)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(Color.WHITE)
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginStart = dp(8)
            }
        })

        row.setHapticClickListener {
            providerSettings.setModelKey(model.modelKey)
            updateUi()
        }

        return row
    }

    private fun modelsForProvider(provider: AiProvider): List<AiModelOption> {
        val models = availableModels.filter { it.provider == provider }
            .ifEmpty { AiModelCatalog.modelsFor(provider) }

        if (provider == AiProvider.OPENAI) {
            return listOf(AiModelCatalog.find(provider, AiModelCatalog.OPENAI_DEFAULT_MODEL_KEY))
        }

        return models
    }

    private fun loadServerModels() {
        val token = SharedPrefsAccountSessionStore(applicationContext)
            .getAuthToken()
            ?.trim()
            .orEmpty()
        if (token.isBlank()) {
            return
        }

        lifecycleScope.launch {
            val remoteModels = withContext(Dispatchers.IO) {
                runCatching {
                    val service = NetworkModule.createAiLimitsApiService(BuildConfig.APP_API_BASE_URL, token)
                    val response = service.getModels()
                    if (!response.isSuccessful) {
                        return@runCatching emptyList<AiModelOption>()
                    }

                    response.body()?.models.orEmpty().mapNotNull { it.toModelOption() }
                }.getOrDefault(emptyList())
            }

            if (remoteModels.isNotEmpty()) {
                availableModels = remoteModels
                updateUi()
            }
        }
    }

    private fun AiModelDescriptorResponse.toModelOption(): AiModelOption? {
        val provider = AiProvider.entries.firstOrNull { it.code == this.provider } ?: return null
        if (modelKey.isBlank() || displayName.isBlank()) {
            return null
        }

        return AiModelOption(
            provider = provider,
            modelKey = modelKey,
            displayName = displayName,
            isDefault = isDefault,
            capabilities = capabilities.toSet()
        )
    }

    private fun View.setSelectableBackground() {
        val value = TypedValue()
        this@AiProviderActivity.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            value,
            true
        )
        setBackgroundResource(value.resourceId)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
        findViewById<TextView>(R.id.tvSectionModel)?.text =
            LocaleHelper.getString(this, "ai_provider_model_section")
    }
}
