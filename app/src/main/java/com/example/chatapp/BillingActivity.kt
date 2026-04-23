package com.example.chatapp

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.data.AuthRepository
import com.example.chatapp.data.NetworkResult
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.dto.BillingStatusResponse
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class BillingActivity : AppCompatActivity() {

    private lateinit var sessionStore: SharedPrefsAccountSessionStore
    private lateinit var authRepository: AuthRepository

    private lateinit var progressBar: ProgressBar
    private lateinit var tvPlanValue: TextView
    private lateinit var tvStatusValue: TextView
    private lateinit var tvExpiresValue: TextView
    private lateinit var tvPriceValue: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnPrimary: TextView
    private lateinit var btnSecondary: TextView

    private var currentStatus: BillingStatusResponse? = null
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_billing)

        window.statusBarColor = Color.TRANSPARENT

        sessionStore = SharedPrefsAccountSessionStore(this)
        authRepository = AuthRepository(
            service = NetworkModule.createAuthApiService(BuildConfig.APP_API_BASE_URL)
        )

        progressBar = findViewById(R.id.progressBar)
        tvPlanValue = findViewById(R.id.tvPlanValue)
        tvStatusValue = findViewById(R.id.tvStatusValue)
        tvExpiresValue = findViewById(R.id.tvExpiresValue)
        tvPriceValue = findViewById(R.id.tvPriceValue)
        tvHint = findViewById(R.id.tvHint)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPrimary.setOnClickListener { handlePrimaryAction() }
        btnSecondary.setOnClickListener { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val token = sessionStore.getAuthToken()?.trim().orEmpty()
        if (token.isBlank()) {
            toast("Session expired. Sign in again.")
            finish()
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            when (val result = authRepository.getBillingStatus(token)) {
                is NetworkResult.Success -> {
                    currentStatus = result.data
                    refreshProfile(token)
                    renderStatus(result.data)
                }

                is NetworkResult.Error -> {
                    toast(result.message)
                }
            }
            setBusy(false)
        }
    }

    private suspend fun refreshProfile(token: String) {
        when (val result = authRepository.getProfile(token)) {
            is NetworkResult.Success -> sessionStore.saveAuthenticatedUser(result.data.user, null)
            is NetworkResult.Error -> Unit
        }
    }

    private fun renderStatus(status: BillingStatusResponse) {
        tvPlanValue.text = if (status.isPro) "Pro" else "Free"
        tvStatusValue.text = humanStatus(status)
        tvExpiresValue.text = formatDate(status.currentPeriodEnd ?: status.planExpiresAt)
        tvPriceValue.text = "${status.priceRub} RUB / 30 days"
        tvHint.text = when {
            status.subscriptionStatus == "pending" ->
                "Complete payment in the browser. Access switches on after the YooKassa webhook reaches the server."
            status.isPro && status.cancelAtPeriodEnd ->
                "Pro access remains active until the end of the paid period. Auto-renew is disabled."
            status.isPro ->
                "Your account can use server-side pro models. Renewals are handled by YooKassa."
            else ->
                "This unlocks higher-tier models on the server. The client no longer selects the model itself."
        }

        btnSecondary.visibility = View.VISIBLE
        btnPrimary.text = if (status.isPro && !status.cancelAtPeriodEnd) {
            "Cancel auto-renew"
        } else {
            "Upgrade for ${status.priceRub} RUB"
        }
    }

    private fun handlePrimaryAction() {
        if (isBusy) {
            return
        }

        val token = sessionStore.getAuthToken()?.trim().orEmpty()
        if (token.isBlank()) {
            toast("Session expired. Sign in again.")
            finish()
            return
        }

        if (currentStatus?.isPro == true && currentStatus?.cancelAtPeriodEnd == false) {
            cancelSubscription(token)
        } else {
            startCheckout(token)
        }
    }

    private fun startCheckout(token: String) {
        lifecycleScope.launch {
            setBusy(true)
            when (val result = authRepository.startBillingCheckout(token)) {
                is NetworkResult.Success -> {
                    val url = result.data.confirmationUrl
                    if (url.isNullOrBlank()) {
                        toast("Checkout URL is missing.")
                    } else {
                        CustomTabsIntent.Builder().build().launchUrl(this@BillingActivity, Uri.parse(url))
                        toast("Payment page opened.")
                    }
                    refreshStatus()
                }

                is NetworkResult.Error -> {
                    toast(result.message)
                }
            }
            setBusy(false)
        }
    }

    private fun cancelSubscription(token: String) {
        lifecycleScope.launch {
            setBusy(true)
            when (val result = authRepository.cancelBillingSubscription(token)) {
                is NetworkResult.Success -> {
                    currentStatus = result.data
                    renderStatus(result.data)
                    toast("Auto-renew disabled.")
                }

                is NetworkResult.Error -> {
                    toast(result.message)
                }
            }
            setBusy(false)
        }
    }

    private fun humanStatus(status: BillingStatusResponse): String {
        return when (status.subscriptionStatus) {
            "active" -> if (status.cancelAtPeriodEnd) "Active until period end" else "Active"
            "pending" -> "Pending payment"
            "canceled" -> "Auto-renew disabled"
            "past_due" -> "Payment issue"
            "expired" -> "Expired"
            "inactive" -> "Inactive"
            else -> status.subscriptionStatus
        }
    }

    private fun formatDate(value: String?): String {
        if (value.isNullOrBlank()) {
            return "-"
        }

        return runCatching {
            OffsetDateTime.parse(value).format(
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault())
            )
        }.getOrDefault(value)
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        btnPrimary.isEnabled = !busy
        btnSecondary.isEnabled = !busy
        btnPrimary.alpha = if (busy) 0.6f else 1f
        btnSecondary.alpha = if (busy) 0.6f else 1f
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
