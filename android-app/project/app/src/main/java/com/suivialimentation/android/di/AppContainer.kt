package com.suivialimentation.android.di

import android.content.Context
import com.suivialimentation.android.auth.HomeAssistantAuthManager
import com.suivialimentation.android.auth.OAuthConfig
import com.suivialimentation.android.auth.SecureTokenStore
import com.suivialimentation.android.data.ha.HomeAssistantApi
import com.suivialimentation.android.data.ha.HomeAssistantWebSocketClient
import com.suivialimentation.android.data.repository.DefaultNutritionRepository
import com.suivialimentation.android.data.repository.NutritionRepository
import com.suivialimentation.android.data.repository.OperationStore
import com.suivialimentation.android.data.repository.RevisionTracker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    val oauthConfig = OAuthConfig()
    private val tokenStore = SecureTokenStore(appContext)
    val authManager = HomeAssistantAuthManager(appContext, httpClient, tokenStore, oauthConfig)
    private val wsClient = HomeAssistantWebSocketClient(httpClient, authManager, appScope)
    private val api = HomeAssistantApi(wsClient)
    private val operationStore = OperationStore(appContext)
    private val revisionTracker = RevisionTracker()

    val repository: NutritionRepository = DefaultNutritionRepository(
        ws = wsClient,
        api = api,
        operationStore = operationStore,
        revisionTracker = revisionTracker,
        scope = appScope,
    )
}
