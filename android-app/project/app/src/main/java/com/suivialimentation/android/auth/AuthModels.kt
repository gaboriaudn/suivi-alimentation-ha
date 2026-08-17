package com.suivialimentation.android.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthSession(
    val instanceUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String,
)

data class AuthorizationRequest(
    val authorizationUrl: String,
    val instanceUrl: String,
)

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val message: String) : AuthResult
}
