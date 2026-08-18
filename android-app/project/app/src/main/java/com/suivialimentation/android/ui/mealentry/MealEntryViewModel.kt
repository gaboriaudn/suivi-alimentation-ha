package com.suivialimentation.android.ui.mealentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.ha.HomeAssistantCommandException
import com.suivialimentation.android.data.model.CiqualFoodCandidate
import com.suivialimentation.android.data.model.Meal
import com.suivialimentation.android.data.model.MealItem
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.data.repository.NutritionRepository
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MealEntryUiState(
    val profileId: String,
    val localDate: String,
    val mealType: String? = null,
    val draftMeal: Meal? = null,
    val items: List<MealItem> = emptyList(),
    val query: String = "",
    val searchResults: List<CiqualFoodCandidate> = emptyList(),
    val searchAttempted: Boolean = false,
    val searchedQuery: String? = null,
    val effectiveSearchQuery: String? = null,
    val selectedFood: CiqualFoodCandidate? = null,
    val quantityText: String = "",
    val searching: Boolean = false,
    val mutating: Boolean = false,
    val error: String? = null,
    val validated: Boolean = false,
)

class MealEntryViewModel(
    private val repository: NutritionRepository,
    profileId: String,
    localDate: String,
    initialDraft: MealWithItems?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        MealEntryUiState(
            profileId = profileId,
            localDate = localDate,
            mealType = initialDraft?.meal?.mealType,
            draftMeal = initialDraft?.meal,
            items = initialDraft?.items.orEmpty(),
        ),
    )
    val state: StateFlow<MealEntryUiState> = _state.asStateFlow()

    fun selectMealType(type: String) {
        if (_state.value.draftMeal != null) return
        _state.update { it.copy(mealType = type, error = null) }
    }

    fun updateQuery(value: String) {
        _state.update {
            it.copy(
                query = value,
                searchResults = emptyList(),
                searchAttempted = false,
                searchedQuery = null,
                effectiveSearchQuery = null,
                selectedFood = null,
                quantityText = "",
                error = null,
            )
        }
    }

    fun search() {
        val current = _state.value
        val query = current.query.trim()
        if (current.mealType == null) {
            _state.update { it.copy(error = "Choisissez d'abord le type de repas.") }
            return
        }
        if (query.length < 2) {
            _state.update { it.copy(error = "Saisissez au moins deux caractères.") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    searching = true,
                    searchResults = emptyList(),
                    searchAttempted = true,
                    searchedQuery = query,
                    effectiveSearchQuery = query,
                    selectedFood = null,
                    quantityText = "",
                    error = null,
                )
            }
            try {
                var effectiveQuery = query
                var results = repository.searchCiqual(current.profileId, query)

                if (results.isEmpty()) {
                    for (broaderQuery in buildBroaderCiqualQueries(query)) {
                        val broaderResults = repository.searchCiqual(current.profileId, broaderQuery)
                        if (broaderResults.isNotEmpty()) {
                            effectiveQuery = broaderQuery
                            results = broaderResults
                            break
                        }
                    }
                }

                _state.update {
                    it.copy(
                        searching = false,
                        searchResults = results,
                        effectiveSearchQuery = effectiveQuery,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(searching = false, error = userMessage(t, "Recherche CIQUAL impossible.")) }
            }
        }
    }

    fun selectFood(food: CiqualFoodCandidate) {
        _state.update { it.copy(selectedFood = food, quantityText = "", error = null) }
    }

    fun dismissFood() {
        _state.update { it.copy(selectedFood = null, quantityText = "", error = null) }
    }

    fun updateQuantity(value: String) {
        val accepted = value.filter { it.isDigit() || it == ',' || it == '.' }
        _state.update { it.copy(quantityText = accepted, error = null) }
    }

    fun addSelectedFood() {
        val current = _state.value
        val selected = current.selectedFood ?: return
        val mealType = current.mealType ?: return
        val grams = current.quantityText.replace(',', '.').toDoubleOrNull()
        if (grams == null || grams <= 0.0) {
            _state.update { it.copy(error = "Indiquez une quantité en grammes supérieure à zéro.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(mutating = true, error = null) }
            try {
                val imported = repository.importCiqualFood(current.profileId, selected.sourceExternalId)
                var meal = _state.value.draftMeal
                if (meal == null) {
                    meal = repository.createMeal(current.profileId, mealType, current.localDate).meal
                    _state.update { it.copy(draftMeal = meal) }
                }
                val added = repository.addFoodToMeal(
                    mealId = meal.id,
                    foodId = imported.food.id,
                    grams = grams,
                    expectedMealRevision = meal.revision,
                )
                _state.update {
                    it.copy(
                        draftMeal = added.meal,
                        items = it.items + added.item,
                        selectedFood = null,
                        quantityText = "",
                        mutating = false,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(mutating = false, error = userMessage(t, "Impossible d'ajouter cet aliment.")) }
            }
        }
    }

    fun validateMeal() {
        val current = _state.value
        val meal = current.draftMeal ?: return
        if (current.items.isEmpty()) {
            _state.update { it.copy(error = "Ajoutez au moins un aliment avant de valider le repas.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(mutating = true, error = null) }
            try {
                val result = repository.validateMeal(meal.id, meal.revision)
                _state.update { it.copy(draftMeal = result.meal, mutating = false, validated = true, error = null) }
            } catch (t: Throwable) {
                _state.update { it.copy(mutating = false, error = userMessage(t, "Impossible de valider le repas.")) }
            }
        }
    }

    private fun userMessage(t: Throwable, fallback: String): String = when (t) {
        is HomeAssistantCommandException -> when {
            t.isConflict -> "Le repas a été modifié ailleurs. Revenez à Aujourd'hui puis reprenez le brouillon."
            else -> t.message?.takeIf { it.isNotBlank() } ?: fallback
        }
        else -> t.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    class Factory(
        private val repository: NutritionRepository,
        private val profileId: String,
        private val localDate: String,
        private val initialDraft: MealWithItems?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MealEntryViewModel::class.java))
            return MealEntryViewModel(repository, profileId, localDate, initialDraft) as T
        }
    }
}

internal fun buildBroaderCiqualQueries(query: String): List<String> {
    val normalized = query
        .lowercase(Locale.FRANCE)
        .replace('’', '\'')
    val stopWords = setOf("de", "du", "des", "la", "le", "les", "un", "une", "d", "l", "au", "aux", "a")
    val tokens = Regex("[\\p{L}\\p{Nd}]+")
        .findAll(normalized)
        .mapIndexed { index, match -> Triple(match.value, match.value.length, index) }
        .filter { (token) -> token.length >= 3 && token !in stopWords }
        .toList()

    return tokens
        .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
        .distinct()
        .filterNot { it == normalized.trim() }
        .take(2)
}
