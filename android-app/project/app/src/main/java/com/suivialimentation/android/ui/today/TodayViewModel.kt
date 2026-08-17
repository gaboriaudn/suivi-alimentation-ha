package com.suivialimentation.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.ha.ConnectionState
import com.suivialimentation.android.data.ha.TransportDisconnectedException
import com.suivialimentation.android.data.repository.NutritionRepository
import com.suivialimentation.android.data.repository.TodayData
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface TodayConnection {
    data object Disconnected : TodayConnection
    data object Connecting : TodayConnection
    data object Connected : TodayConnection
    data class Reconnecting(val attempt: Int) : TodayConnection
    data object AuthenticationRequired : TodayConnection
    data class Error(val message: String) : TodayConnection
}

data class TodayUiState(
    val loading: Boolean = true,
    val content: TodayData? = null,
    val connection: TodayConnection = TodayConnection.Disconnected,
    val error: String? = null,
)

class TodayViewModel(private val repository: NutritionRepository) : ViewModel() {
    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()
    private val refreshMutex = Mutex()
    private var changesJob: Job? = null
    private var subscribedProfileId: String? = null

    init {
        viewModelScope.launch { repository.connect() }
        viewModelScope.launch {
            repository.connectionState.collect { connection ->
                _state.update { it.copy(connection = connection.toUiConnection()) }
                if (connection is ConnectionState.Connected) {
                    refresh(showLoader = _state.value.content == null)
                }
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            repository.connect()
            refresh(showLoader = _state.value.content == null)
        }
    }

    private suspend fun refresh(showLoader: Boolean) = refreshMutex.withLock {
        if (showLoader) _state.update { it.copy(loading = true, error = null) }
        try {
            val data = repository.loadToday()
            _state.update { it.copy(loading = false, content = data, error = null) }
            ensureChangesSubscription(data.profile.id)
        } catch (_: TransportDisconnectedException) {
            _state.update { it.copy(loading = false) }
        } catch (t: Throwable) {
            _state.update { it.copy(loading = false, error = t.message ?: "Impossible de charger la journée.") }
        }
    }

    private fun ensureChangesSubscription(profileId: String) {
        if (subscribedProfileId == profileId && changesJob?.isActive == true) return
        changesJob?.cancel()
        subscribedProfileId = profileId
        changesJob = viewModelScope.launch {
            try {
                repository.changes(profileId).conflate().collect { refresh(showLoader = false) }
            } catch (_: TransportDisconnectedException) {
            } catch (t: Throwable) {
                _state.update { it.copy(error = "Synchronisation temps réel indisponible : ${t.message}") }
            }
        }
    }

    private fun ConnectionState.toUiConnection(): TodayConnection = when (this) {
        ConnectionState.Disconnected -> TodayConnection.Disconnected
        ConnectionState.Connecting, ConnectionState.Authenticating -> TodayConnection.Connecting
        is ConnectionState.Connected -> TodayConnection.Connected
        is ConnectionState.Reconnecting -> TodayConnection.Reconnecting(attempt)
        ConnectionState.AuthenticationRequired -> TodayConnection.AuthenticationRequired
        is ConnectionState.Error -> TodayConnection.Error(message)
    }

    class Factory(private val repository: NutritionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TodayViewModel::class.java))
            return TodayViewModel(repository) as T
        }
    }
}
