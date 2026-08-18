package com.suivialimentation.android.ui.photo

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.photo.PhotoAnalysisMode
import com.suivialimentation.android.data.photo.PhotoAnalysisService
import com.suivialimentation.android.data.photo.PhotoFoodSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoMealUiState(
    val loading: Boolean = false,
    val mode: PhotoAnalysisMode = PhotoAnalysisMode.MEAL,
    val title: String? = null,
    val suggestions: List<PhotoFoodSuggestion> = emptyList(),
    val error: String? = null,
)

class PhotoMealViewModel(
    private val service: PhotoAnalysisService,
) : ViewModel() {
    private val _state = MutableStateFlow(PhotoMealUiState())
    val state: StateFlow<PhotoMealUiState> = _state.asStateFlow()

    fun analyzeFood(uri: Uri) = analyze(uri, PhotoAnalysisMode.FOOD)

    fun analyzeMeal(uri: Uri) = analyze(uri, PhotoAnalysisMode.MEAL)

    private fun analyze(uri: Uri, mode: PhotoAnalysisMode) {
        viewModelScope.launch {
            _state.update { PhotoMealUiState(loading = true, mode = mode) }
            try {
                val result = service.analyze(uri, mode)
                _state.update {
                    PhotoMealUiState(
                        loading = false,
                        mode = mode,
                        title = result.title,
                        suggestions = result.suggestions,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    PhotoMealUiState(
                        mode = mode,
                        error = t.message?.takeIf(String::isNotBlank) ?: "Analyse photo impossible.",
                    )
                }
            }
        }
    }

    fun clear() {
        _state.value = PhotoMealUiState()
    }

    class Factory(private val service: PhotoAnalysisService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PhotoMealViewModel::class.java))
            return PhotoMealViewModel(service) as T
        }
    }
}
