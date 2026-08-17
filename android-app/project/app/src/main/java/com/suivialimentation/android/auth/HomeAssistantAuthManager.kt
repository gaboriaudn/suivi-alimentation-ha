package com.suivialimentation.android.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.suivialimentation.android.util.AppJson
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

interface AccessTokenProvider {
    suspend fun currentSession(): AuthSession?
    suspend fun validAccessToken(forceRefresh: Boolean = false): String
}

class HomeAssistantAuthManager(
    context: Context,
    private val httpClient: OkHttpClient,
    private val tokenStore: SecureTokenStore,
    private val config: OAuthConfig,
) : AccessTokenProvider {
    private val pendingPrefs = context.getSharedPreferences("oauth_pending", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Volatile private var cachedSession: AuthSession? = null

    suspend fun restore(): AuthSession? = mutex.withLock {
        cachedSession ?: tokenStore.load()?.also { cachedSession = it }
    }

    override suspend fun currentSession(): AuthSession? = restore()

    fun cancelPendingAuthorization() {
        clearPending()
    }

    fun createAuthorizationRequest(rawInstanceUrl: String): AuthorizationRequest {
        require(!config.isPlaceholder) {
            "Le client OAuth n'est pas configuré. Renseignez HA_OAUTH_CLIENT_ID avant de lancer l'authentification."
        }
        val instanceUrl = InstanceUrlPolicy.normalize(rawInstanceUrl)
        val state = randomState()
        pendingPrefs.edit()
            .putString(KEY_PENDING_STATE, state)
            .putString(KEY_PENDING_INSTANCE, instanceUrl)
            .apply()

        val url = Uri.parse("$instanceUrl/auth/authorize").buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("state", state)
            .build()
            .toString()
        return AuthorizationRequest(url, instanceUrl)
    }

    suspend fun handleCallback(callback: Uri): AuthResult = mutex.withLock {
        val expectedRedirect = Uri.parse(config.redirectUri)
        if (
            callback.scheme != expectedRedirect.scheme ||
            callback.host != expectedRedirect.host ||
            callback.port != expectedRedirect.port ||
            callback.path.orEmpty() != expectedRedirect.path.orEmpty()
        ) {
            return@withLock AuthResult.Failure("Retour OAuth inattendu.")
        }
        val expectedState = pendingPrefs.getString(KEY_PENDING_STATE, null)
        val instanceUrl = pendingPrefs.getString(KEY_PENDING_INSTANCE, null)
        val receivedState = callback.getQueryParameter("state")
        val code = callback.getQueryParameter("code")
        if (expectedState.isNullOrBlank() || instanceUrl.isNullOrBlank() || receivedState != expectedState) {
            clearPending()
            return@withLock AuthResult.Failure("État OAuth invalide. Recommencez la connexion.")
        }
        if (code.isNullOrBlank()) {
            val error = callback.getQueryParameter("error") ?: "autorisation refusée"
            clearPending()
            return@withLock AuthResult.Failure("Home Assistant a refusé l'autorisation : $error")
        }

        return@withLock runCatching {
            val token = exchangeAuthorizationCode(instanceUrl, code)
            val session = AuthSession(
                instanceUrl = instanceUrl,
                accessToken = token.accessToken,
                refreshToken = requireNotNull(token.refreshToken) { "Refresh token absent." },
                accessTokenExpiresAtEpochSeconds = nowEpochSeconds() + token.expiresIn,
            )
            tokenStore.save(session)
            cachedSession = session
            clearPending()
            AuthResult.Success
        }.getOrElse {
            clearPending()
            AuthResult.Failure(it.message ?: "Échec de l'échange OAuth.")
        }
    }

    override suspend fun validAccessToken(forceRefresh: Boolean): String = mutex.withLock {
        val session = cachedSession ?: tokenStore.load()?.also { cachedSession = it }
            ?: throw AuthenticationRequiredException()
        if (!forceRefresh && session.accessTokenExpiresAtEpochSeconds - nowEpochSeconds() > REFRESH_MARGIN_SECONDS) {
            return@withLock session.accessToken
        }

        val refreshed = runCatching { refresh(session) }.getOrElse {
            cachedSession = null
            tokenStore.clear()
            throw AuthenticationRequiredException(it)
        }
        val updated = session.copy(
            accessToken = refreshed.accessToken,
            accessTokenExpiresAtEpochSeconds = nowEpochSeconds() + refreshed.expiresIn,
        )
        tokenStore.save(updated)
        cachedSession = updated
        updated.accessToken
    }

    suspend fun revokeAndClear() = mutex.withLock {
        val session = cachedSession ?: tokenStore.load()
        if (session != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val body = FormBody.Builder().add("token", session.refreshToken).build()
                    val request = Request.Builder().url("${session.instanceUrl}/auth/revoke").post(body).build()
                    httpClient.newCall(request).execute().use { }
                }
            }
        }
        cachedSession = null
        tokenStore.clear()
        clearPending()
    }

    private suspend fun exchangeAuthorizationCode(instanceUrl: String, code: String): TokenResponse =
        requestToken(
            instanceUrl,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", config.clientId)
                .build(),
        )

    private suspend fun refresh(session: AuthSession): TokenResponse =
        requestToken(
            session.instanceUrl,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", session.refreshToken)
                .add("client_id", config.clientId)
                .build(),
        )

    private suspend fun requestToken(instanceUrl: String, body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$instanceUrl/auth/token").post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("Échec OAuth Home Assistant (${response.code}).")
            }
            AppJson.decodeFromString<TokenResponse>(responseBody)
        }
    }

    private fun randomState(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun clearPending() {
        pendingPrefs.edit().clear().apply()
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1_000L

    private companion object {
        const val KEY_PENDING_STATE = "state"
        const val KEY_PENDING_INSTANCE = "instance"
        const val REFRESH_MARGIN_SECONDS = 60L
    }
}

class AuthenticationRequiredException(cause: Throwable? = null) :
    IllegalStateException("Authentification Home Assistant requise.", cause)
