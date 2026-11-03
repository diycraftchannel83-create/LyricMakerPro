package com.winsmediatama.lyricmakerpro.ads

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collects the current UMP consent decision before any Google Mobile Ads request.
 * Consent is refreshed on every app launch; the SDK remains the source of truth.
 */
class AdsConsentManager {
    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds = _canRequestAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired = _privacyOptionsRequired.asStateFlow()

    private var consentInformation: ConsentInformation? = null
    private var requestInProgress = false

    fun requestConsent(activity: Activity) {
        if (requestInProgress || activity.isFinishing || activity.isDestroyed) return
        requestInProgress = true
        val information = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation = information
        val parameters = ConsentRequestParameters.Builder().build()

        information.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                if (activity.isFinishing || activity.isDestroyed) {
                    requestInProgress = false
                } else {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                        requestInProgress = false
                        refreshState(information)
                    }
                }
            },
            {
                requestInProgress = false
                // UMP may still have a valid consent decision from a previous session.
                refreshState(information)
            }
        )
        // Existing consent can be valid while the fresh update is in flight.
        refreshState(information)
    }

    fun showPrivacyOptions(activity: Activity) {
        val information = consentInformation ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            refreshState(information)
        }
    }

    private fun refreshState(information: ConsentInformation) {
        _canRequestAds.value = information.canRequestAds()
        _privacyOptionsRequired.value =
            information.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
}
