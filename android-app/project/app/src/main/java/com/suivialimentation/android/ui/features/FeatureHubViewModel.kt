package com.suivialimentation.android.ui.features

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.features.FeatureRepository
import com.suivialimentation.android.data.features.HistoryAnalysis
import com.suivialimentation.android.data.features.MealTemplateSummary
import com.suivialimentation.android.data.features.RecipeSummary
import com.suivialimentation.android.data.photo.PhotoFoodSuggestion
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.data.repository.NutritionRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeatureHubUiState(
    val recipes: List<RecipeSummary> = emptyList(),
    val mealTemplates: List<MealTemplateSummary> = emptyList(),
    val history7: HistoryAnalysis? = null,
    val history30: HistoryAnalysis? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val createdDraft: MealWithItems? = null,
)

class FeatureHubViewModel(
    private val repository: FeatureRepository,
    private val nutritionRepository: NutritionRepository,
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
                val templates = repository.getMealTemplates(profileId)
                val history7 = repository.getHistoryRange(profileId, date.minusDays(6).toString(), date.toString())
                val history30 = repository.getHistoryRange(profileId, date.minusDays(29).toString(), date.toString())
                _state.update {
                    it.copy(
                        recipes = recipes,
                        mealTemplates = templates,
                        history7 = history7,
                        history30 = history30,
                        busy = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Chargement impossible.") }
            }
        }
    }

    fun saveRecipe(sourceMealId: String, name: String) {
        if (name.isBlank()) return
        val templatePrefix = "__meal_template__:"
        val asTemplate = name.startsWith(templatePrefix)
        val cleanName = if (asTemplate) name.removePrefix(templatePrefix) else name
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                if (asTemplate) {
                    val template = repository.saveMealAsTemplate(sourceMealId, cleanName)
                    _state.update {
                        it.copy(
                            mealTemplates = (it.mealTemplates + template)
                                .distinctBy(MealTemplateSummary::id)
                                .sortedBy(MealTemplateSummary::name),
                            busy = false,
                            message = "Repas type « ${template.name} » enregistré.",
                        )
                    }
                } else {
                    val recipe = repository.saveMealAsRecipe(sourceMealId, cleanName)
                    _state.update {
                        it.copy(
                            recipes = (it.recipes + recipe).distinctBy(RecipeSummary::id).sortedBy(RecipeSummary::name),
                            busy = false,
                            message = "Recette « ${recipe.name} » enregistrée.",
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Enregistrement impossible.") }
            }
        }
    }

    fun createFromRecipe(recipeId: String, mealType: String) {
        val templatePrefix = "meal-template:"
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, createdDraft = null) }
            try {
                val draft = if (recipeId.startsWith(templatePrefix)) {
                    repository.createMealFromTemplate(recipeId.removePrefix(templatePrefix), mealType, localDate)
                } else {
                    repository.createMealFromRecipe(recipeId, mealType, localDate)
                }
                _state.update { it.copy(busy = false, createdDraft = draft) }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Création du brouillon impossible.") }
            }
        }
    }

    fun createFromPhoto(suggestions: List<PhotoFoodSuggestion>, mealType: String) {
        if (suggestions.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null, createdDraft = null) }
            try {
                var meal = nutritionRepository.createMeal(profileId, mealType, localDate).meal
                val createdItems = mutableListOf<com.suivialimentation.android.data.model.MealItem>()
                val unresolved = mutableListOf<String>()
                for (suggestion in suggestions) {
                    val personal = nutritionRepository.searchPersonalFoods(profileId, suggestion.label, 3).firstOrNull()
                    val foodId = if (personal != null) {
                        nutritionRepository.importPersonalFood(profileId, personal.sourceExternalId).food.id
                    } else {
                        val ciqual = nutritionRepository.searchCiqual(profileId, suggestion.label, 5).firstOrNull()
                        if (ciqual == null) {
                            unresolved += suggestion.label
                            continue
                        }
                        nutritionRepository.importCiqualFood(profileId, ciqual.sourceExternalId).food.id
                    }
                    val added = nutritionRepository.addFoodToMeal(
                        mealId = meal.id,
                        foodId = foodId,
                        quantityValue = suggestion.estimatedGrams,
                        quantityUnit = "g",
                        portionId = null,
                        expectedMealRevision = meal.revision,
                    )
                    meal = added.meal
                    createdItems += added.item
                }
                if (createdItems.isEmpty()) {
                    nutritionRepository.voidMeal(meal.id, meal.revision)
                    error("Aucun aliment reconnu n’a pu être relié à vos aliments ou à CIQUAL.")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        createdDraft = MealWithItems(meal, createdItems),
                        message = unresolved.takeIf { names -> names.isNotEmpty() }?.let { names ->
                            "À vérifier : ${names.joinToString()} n’a pas été ajouté automatiquement."
                        },
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "Création depuis la photo impossible.") }
            }
        }
    }

    fun consumeCreatedDraft() {
        _state.update { it.copy(createdDraft = null) }
    }

    class Factory(
        private val repository: FeatureRepository,
        private val nutritionRepository: NutritionRepository,
        private val profileId: String,
        private val localDate: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FeatureHubViewModel::class.java))
            return FeatureHubViewModel(repository, nutritionRepository, profileId, localDate) as T
        }
    }
}
