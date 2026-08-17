package com.suivialimentation.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suivialimentation.android.di.AppContainer
import com.suivialimentation.android.ui.AppEvent
import com.suivialimentation.android.ui.AppUiState
import com.suivialimentation.android.ui.AppViewModel
import com.suivialimentation.android.ui.LoginScreen
import com.suivialimentation.android.ui.theme.SuiviAlimentationTheme
import com.suivialimentation.android.ui.today.TodayScreen
import com.suivialimentation.android.ui.today.TodayViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as SuiviAlimentationApplication).container

    private val appViewModel: AppViewModel by viewModels {
        AppViewModel.Factory(container.authManager, container.repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            appViewModel.events.collect { event ->
                when (event) {
                    is AppEvent.OpenAuthorization -> CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                        .launchUrl(this@MainActivity, event.url)
                }
            }
        }
        setContent {
            SuiviAlimentationTheme {
                AppRoot(appViewModel, container)
            }
        }
        handleCallback(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent.data)
    }

    private fun handleCallback(uri: Uri?) {
        if (uri == null) return
        appViewModel.handleAuthCallback(uri)
        intent?.data = null
    }
}

@Composable
private fun AppRoot(appViewModel: AppViewModel, container: AppContainer) {
    val appState by appViewModel.state.collectAsStateWithLifecycle()
    when (val state = appState) {
        AppUiState.Loading -> FullScreenLoading("Restauration de la session…")
        is AppUiState.SignedOut -> LoginScreen(
            authenticating = false,
            error = state.error,
            oauthConfigured = !container.oauthConfig.isPlaceholder,
            onLogin = appViewModel::startLogin,
            onCancel = appViewModel::cancelLogin,
        )
        AppUiState.Authenticating -> LoginScreen(
            authenticating = true,
            error = null,
            oauthConfigured = !container.oauthConfig.isPlaceholder,
            onLogin = appViewModel::startLogin,
            onCancel = appViewModel::cancelLogin,
        )
        is AppUiState.SignedIn -> {
            val todayViewModel: TodayViewModel = viewModel(
                key = "today-${state.sessionGeneration}",
                factory = TodayViewModel.Factory(container.repository),
            )
            val todayState by todayViewModel.state.collectAsStateWithLifecycle()
            TodayScreen(
                state = todayState,
                onRetry = todayViewModel::retry,
                onLogout = appViewModel::logout,
            )
        }
    }
}

@Composable
private fun FullScreenLoading(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}
