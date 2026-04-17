package com.example.chatapp.ads

import android.app.Activity
import android.widget.Toast
import com.example.chatapp.LocaleHelper
import com.yandex.mobile.ads.common.AdRequestConfiguration
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader

/**
 * Менеджер рекламных видео с наградой (Yandex Mobile Ads).
 * При успешном просмотре видео вызывает onRewarded callback.
 */
class RewardedAdManager(
    private val activity: Activity,
    private val onRewarded: () -> Unit
) {
    private var rewardedAd: RewardedAd? = null
    private var rewardedAdLoader: RewardedAdLoader? = null

    companion object {
        private const val AD_UNIT_ID = "R-M-19101069-1"
    }

    /** Инициализация загрузчика рекламы */
    fun initialize() {
        rewardedAdLoader = RewardedAdLoader(activity)
        rewardedAdLoader?.setAdLoadListener(object : RewardedAdLoadListener {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
            }
            override fun onAdFailedToLoad(error: AdRequestError) {
                // Тихо игнорируем — реклама необязательна
            }
        })
        loadAd()
    }

    /** Загрузка следующей рекламы (вызывается при инициализации и после показа) */
    private fun loadAd() {
        val config = AdRequestConfiguration.Builder(AD_UNIT_ID).build()
        rewardedAdLoader?.loadAd(config)
    }

    /** Показ рекламного видео. Если не загружено — показывает toast */
    fun show() {
        rewardedAd?.apply {
            setAdEventListener(object : RewardedAdEventListener {
                override fun onAdShown() {}
                override fun onAdFailedToShow(error: com.yandex.mobile.ads.common.AdError) {}
                override fun onAdDismissed() {
                    rewardedAd = null
                    loadAd() // Предзагружаем следующую рекламу
                }
                override fun onAdClicked() {}
                override fun onAdImpression(data: ImpressionData?) {}
                override fun onRewarded(reward: Reward) {
                    onRewarded()
                }
            })
            show(activity)
        } ?: run {
            Toast.makeText(activity, LocaleHelper.getString(activity, "toast_ad_loading"), Toast.LENGTH_SHORT).show()
            loadAd()
        }
    }
}
