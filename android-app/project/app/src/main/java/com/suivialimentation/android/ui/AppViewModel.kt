package com.suivialimentation.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.auth.AuthResult
import com.suivialimentation.android.auth.HomeAssistantAuthManager
import com.suivialimentation.android.data.ha.ConnectionState
import com.suivialimentation.android.data.repository.NutritionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AppUiState {
    data object Loading : AppUiState
    data class SignedOut(val error: String? = null, val instanceUrl: String = "") : AppUiState
    data object Authenticating : AppUiState
    data class SignedIn(val sessionGeneration: Long) : AppUiState
}

sealed interface AppEvent {
    data class OpenAuthorization(val url: Uri) : AppEvent
}

class AppViewModel(
    private val authManager: HomeAssistantAuthManager,
    private val repository: NutritionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()
    private var generation = 0L

    init {
        viewModelScope.launch {
            val session = authManager.restore()
            if (session == null) {
                _state.value = AppUiState.SignedOut(instanceUrl = authManager.lastInstanceUrl())
            } else {
                generation += 1
                _state.value = AppUiState.SignedIn(generation)
                repository.connect()
            }
        }
        viewModelScope.launch {
            repository.connectionState.collect { connection ->
                if (connection is ConnectionState.AuthenticationRequired && _state.value is AppUiState.SignedIn) {
                    _state.value = AppUiState.SignedOut(
                        error = "La session Home Assistant a expiré. Reconnectez-vous.",
                        instanceUrl = authManager.lastInstanceUrl(),
                    )
                }
            }
        }
    }

    fun startLogin(instanceUrl: String) {
        runCatching { authManager.createAuthorizationRequest(instanceUrl) }
            .onSuccess { request ->
                _state.value = AppUiState.Authenticating
                _events.tryEmit(AppEvent.OpenAuthorization(Uri.parse(request.authorizationUrl)))
            }
            .onFailure { error ->
                viewModelScope.launch {
                    _state.value = AppUiState.SignedOut(
                        error = error.message ?: "Impossible de démarrer l'authentification.",
                        instanceUrl = instanceUrl,
                    )
                }
            }
    }

    fun cancelLogin() {
        if (_state.value is AppUiState.Authenticating) {
            authManager.cancelPendingAuthorization()
            viewModelScope.launch {
                _state.value = AppUiState.SignedOut(instanceUrl = authManager.lastInstanceUrl())
            }
        }
    }

    fun handleAuthCallback(uri: Uri) {
        viewModelScope.launch {
            _state.value = AppUiState.Authenticating
            when (val result = authManager.handleCallback(uri)) {
                AuthResult.Success -> {
                    generation += 1
                    _state.value = AppUiState.SignedIn(generation)
                    repository.connect()
                }
                is AuthResult.Failure -> _state.value = AppUiState.SignedOut(
                    error = result.message,
                    instanceUrl = authManager.lastInstanceUrl(),
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.disconnect()
            authManager.revokeAndClear()
            _state.value = AppUiState.SignedOut(instanceUrl = authManager.lastInstanceUrl())
        }
    }

    class Factory(
        private val authManager: HomeAssistantAuthManager,
        private val repository: NutritionRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppViewModel::class.java))
            return AppViewModel(authManager, repository) as T
        }
    }
}
