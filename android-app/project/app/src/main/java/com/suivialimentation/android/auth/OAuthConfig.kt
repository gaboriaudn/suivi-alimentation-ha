package com.suivialimentation.android.auth

import com.suivialimentation.android.BuildConfig

data class OAuthConfig(
    val clientId: String = BuildConfig.HA_OAUTH_CLIENT_ID,
    val redirectUri: String = BuildConfig.HA_OAUTH_REDIRECT_URI,
) {
    val isPlaceholder: Boolean
        get() = clientId.contains("example.invalid")
}
