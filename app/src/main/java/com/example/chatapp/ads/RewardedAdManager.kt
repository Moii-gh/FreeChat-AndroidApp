package com.example.chatapp.ads

import android.app.Activity
import android.util.Log
import android.widget.Toast
import com.example.chatapp.LocaleHelper
import com.yandex.mobile.ads.common.AdError
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
    private var isLoading = false

    companion object {
        private const val TAG = "RewardedAdManager"
        private const val AD_UNIT_ID = "R-M-19145287-1"
    }

    /** Инициализация загрузчика рекламы */
    fun initialize() {
        if (rewardedAdLoader == null) {
            rewardedAdLoader = RewardedAdLoader(activity).apply {
                setAdLoadListener(object : RewardedAdLoadListener {
                    override fun onAdLoaded(ad: RewardedAd) {
                        isLoading = false
                        rewardedAd = ad
                        Log.d(TAG, "Rewarded ad loaded: adUnitId=$AD_UNIT_ID")
                    }

                    override fun onAdFailedToLoad(error: AdRequestError) {
                        isLoading = false
                        Log.w(
                            TAG,
                            "Rewarded ad failed to load: code=${error.code}, " +
                                "description=${error.description}, adUnitId=${error.adUnitId}"
                        )
                        // Тихо игнорируем — реклама необязательна
                    }
                })
            }
        }
        loadAd()
    }

    /** Загрузка следующей рекламы (вызывается при инициализации и после показа) */
    private fun loadAd() {
        val loader = rewardedAdLoader ?: return
        if (isLoading || rewardedAd != null) return

        isLoading = true
        val config = AdRequestConfiguration.Builder(AD_UNIT_ID).build()
        loader.loadAd(config)
    }

    /** Показ рекламного видео. Если не загружено — показывает toast */
    fun show() {
        rewardedAd?.apply {
            setAdEventListener(object : RewardedAdEventListener {
                override fun onAdShown() {}

                override fun onAdFailedToShow(error: AdError) {
                    Log.w(TAG, "Rewarded ad failed to show: description=${error.description}")
                    destroyRewardedAd()
                    loadAd()
                }

                override fun onAdDismissed() {
                    destroyRewardedAd()
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
            Log.d(TAG, "Rewarded ad is not ready yet: isLoading=$isLoading, adUnitId=$AD_UNIT_ID")
            Toast.makeText(activity, LocaleHelper.getString(activity, "toast_ad_loading"), Toast.LENGTH_SHORT).show()
            loadAd()
        }
    }

    fun destroy() {
        rewardedAdLoader?.setAdLoadListener(null)
        rewardedAdLoader = null
        isLoading = false
        destroyRewardedAd()
    }

    private fun destroyRewardedAd() {
        rewardedAd?.setAdEventListener(null)
        rewardedAd = null
    }
}
