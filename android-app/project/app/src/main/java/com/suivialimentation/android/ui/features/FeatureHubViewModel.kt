package com.suivialimentation.android.ui.features

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.features.FeatureRepository
import com.suivialimentation.android.data.features.HistoryAnalysis
import com.suivialimentation.android.data.features.RecipeSummary
import com.suivialimentation.android.data.repository.MealWithItems
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeatureHubUiState(
    val recipes: List<RecipeSummary> = emptyList(),
    val history7: HistoryAnalysis? = null,
    val history30: HistoryAnalysis? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val createdDraft: MealWithItems? = null,
)

class FeatureHubViewModel(
    private val repository: FeatureRepository,
    private val profileId: String,
    private val localDate: String,
) : ViewModel() {
    private val _state = MutableStateFlow(FeatureHubUiState())
    val state: StateFlow<FeatureHubUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val date = LocalDate.parse(localDate)
                val recipes = repository.getRecipes(profileId)
                val history7 = repository.getHistoryRange(profileId, date.minusDays(6).toString(), date.toString())
                val history30 = repository.getHistoryRange(profileId, date.minusDays(29).toString(), date.toString())
                _state.update { it.copy(recipes = recipes, history7 = history7, history30 = history30, busy = false) }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Chargement impossible.") }
            }
        }
    }

    fun saveRecipe(sourceMealId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                val recipe = repository.saveMealAsRecipe(sourceMealId, name)
                _state.update {
                    it.copy(
                        recipes = (it.recipes + recipe).distinctBy(RecipeSummary::id).sortedBy(RecipeSummary::name),
                        busy = false,
                        message = "Recette « ${recipe.name} » enregistrée.",
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Enregistrement impossible.") }
            }
        }
    }

    fun createFromRecipe(recipeId: String, mealType: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, createdDraft = null) }
            try {
                val draft = repository.createMealFromRecipe(recipeId, mealType, localDate)
                _state.update { it.copy(busy = false, createdDraft = draft) }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Création du brouillon impossible.") }
            }
        }
    }

    fun consumeCreatedDraft() {
        _state.update { it.copy(createdDraft = null) }
    }

    class Factory(
        private val repository: FeatureRepository,
        private val profileId: String,
        private val localDate: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FeatureHubViewModel::class.java))
            return FeatureHubViewModel(repository, profileId, localDate) as T
        }
    }
}
