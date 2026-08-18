package com.suivialimentation.android.ui.mealentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.ha.HomeAssistantCommandException
import com.suivialimentation.android.data.model.CiqualFoodCandidate
import com.suivialimentation.android.data.model.Meal
import com.suivialimentation.android.data.model.MealItem
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.model.OffProductCandidate
import com.suivialimentation.android.data.model.PersonalFoodCandidate
import com.suivialimentation.android.data.model.PortionOption
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.data.repository.NutritionRepository
import com.suivialimentation.android.data.repository.QuickFood
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FoodChoice(
    val foodId: String? = null,
    val sourceType: String,
    val sourceExternalId: String,
    val label: String,
    val nutrientsPer100g: NutrientSnapshot?,
    val nutrientsPerUnit: NutrientSnapshot? = null,
    val servingDefinitions: List<PortionOption> = emptyList(),
)

data class MealEntryUiState(
    val profileId: String,
    val localDate: String,
    val mealType: String? = null,
    val draftMeal: Meal? = null,
    val items: List<MealItem> = emptyList(),
    val query: String = "",
    val searchResults: List<CiqualFoodCandidate> = emptyList(),
    val personalSearchResults: List<PersonalFoodCandidate> = emptyList(),
    val searchAttempted: Boolean = false,
    val searchedQuery: String? = null,
    val effectiveSearchQuery: String? = null,
    val selectedFood: FoodChoice? = null,
    val selectedPortionId: String? = null,
    val quantityText: String = "",
    val barcodeText: String = "",
    val barcodeProduct: OffProductCandidate? = null,
    val barcodeSearching: Boolean = false,
    val favoriteFoods: List<QuickFood> = emptyList(),
    val recentFoods: List<QuickFood> = emptyList(),
    val quickFoodsLoading: Boolean = true,
    val searching: Boolean = false,
    val mutating: Boolean = false,
    val error: String? = null,
    val validated: Boolean = false,
    val pendingExistingMeal: MealWithItems? = null,
    val editingItem: MealItem? = null,
    val editQuantityText: String = "",
)

class MealEntryViewModel(
    private val repository: NutritionRepository,
    profileId: String,
    localDate: String,
    initialDraft: MealWithItems?,
    private val existingMeals: List<MealWithItems>,
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

    init {
        loadQuickFoods()
    }

    private fun loadQuickFoods() {
        viewModelScope.launch {
            try {
                val quick = repository.loadQuickFoods(_state.value.profileId)
                _state.update {
                    it.copy(
                        favoriteFoods = quick.favorites,
                        recentFoods = quick.recents,
                        quickFoodsLoading = false,
                    )
                }
            } catch (_: Throwable) {
                _state.update { it.copy(quickFoodsLoading = false) }
            }
        }
    }

    fun selectMealType(type: String) {
        if (_state.value.draftMeal != null) return
        val existing = matchingMealForType(existingMeals, type)
        _state.update {
            it.copy(mealType = type, pendingExistingMeal = existing, error = null)
        }
    }

    fun complementExistingMeal() {
        val existing = _state.value.pendingExistingMeal ?: return
        viewModelScope.launch {
            _state.update { it.copy(mutating = true, error = null) }
            try {
                val correction = repository.startMealCorrection(existing.meal.id)
                _state.update {
                    it.copy(
                        draftMeal = correction.meal,
                        items = correction.items,
                        pendingExistingMeal = null,
                        mutating = false,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(mutating = false, error = userMessage(t, "Impossible de compléter ce repas."))
                }
            }
        }
    }

    fun createSeparateMeal() {
        _state.update { it.copy(pendingExistingMeal = null, error = null) }
    }

    fun cancelExistingMealChoice() {
        _state.update { it.copy(mealType = null, pendingExistingMeal = null, error = null) }
    }

    fun updateQuery(value: String) {
        _state.update {
            it.copy(
                query = value,
                searchResults = emptyList(),
                personalSearchResults = emptyList(),
                searchAttempted = false,
                searchedQuery = null,
                effectiveSearchQuery = null,
                selectedFood = null,
                selectedPortionId = null,
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
                    personalSearchResults = emptyList(),
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
                val initial = coroutineScope {
                    val personal = async { repository.searchPersonalFoods(current.profileId, query) }
                    val ciqual = async { repository.searchCiqual(current.profileId, query) }
                    personal.await() to ciqual.await()
                }
                val personalResults = initial.first
                var results = initial.second

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
                        personalSearchResults = personalResults,
                        effectiveSearchQuery = effectiveQuery,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(searching = false, error = userMessage(t, "Recherche impossible.")) }
            }
        }
    }

    fun updateBarcode(value: String) {
        _state.update {
            it.copy(
                barcodeText = value.filter(Char::isDigit).take(14),
                barcodeProduct = null,
                error = null,
            )
        }
    }

    fun barcodeScanned(value: String) {
        updateBarcode(value)
        lookupBarcode()
    }

    fun barcodeScanFailed(message: String?) {
        _state.update { it.copy(error = message?.takeIf(String::isNotBlank) ?: "Le scanner n'a pas pu démarrer.") }
    }

    fun lookupBarcode() {
        val current = _state.value
        if (current.mealType == null) {
            _state.update { it.copy(error = "Choisissez d'abord le type de repas.") }
            return
        }
        val barcode = current.barcodeText.filter(Char::isDigit)
        if (barcode.length !in 8..14) {
            _state.update { it.copy(error = "Le code-barres doit contenir entre 8 et 14 chiffres.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(barcodeSearching = true, barcodeProduct = null, error = null) }
            try {
                val product = repository.getOffProduct(current.profileId, barcode)
                _state.update { it.copy(barcodeSearching = false, barcodeProduct = product, error = null) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        barcodeSearching = false,
                        error = userMessage(t, "Produit introuvable dans Open Food Facts."),
                    )
                }
            }
        }
    }

    fun selectFood(food: CiqualFoodCandidate) {
        _state.update {
            it.copy(
                selectedFood = FoodChoice(
                    sourceType = food.sourceType,
                    sourceExternalId = food.sourceExternalId,
                    label = food.label,
                    nutrientsPer100g = food.nutrientsPer100g,
                    servingDefinitions = food.servingDefinitions,
                ),
                selectedPortionId = null,
                quantityText = "",
                error = null,
            )
        }
    }

    fun selectPersonalFood(food: PersonalFoodCandidate) {
        _state.update {
            it.copy(
                selectedFood = FoodChoice(
                    sourceType = food.sourceType,
                    sourceExternalId = food.sourceExternalId,
                    label = food.label,
                    nutrientsPer100g = food.nutrientsPer100g,
                    nutrientsPerUnit = food.nutrientsPerUnit,
                    servingDefinitions = food.servingDefinitions,
                ),
                selectedPortionId = if (food.nutrientsPer100g == null) {
                    food.servingDefinitions.firstOrNull()?.id
                } else null,
                quantityText = "",
                error = null,
            )
        }
    }

    fun selectOffProduct(food: OffProductCandidate) {
        _state.update {
            it.copy(
                selectedFood = FoodChoice(
                    sourceType = food.sourceType,
                    sourceExternalId = food.sourceExternalId,
                    label = listOfNotNull(food.label, food.brand?.takeIf(String::isNotBlank)).joinToString(" · "),
                    nutrientsPer100g = food.nutrientsPer100g,
                    servingDefinitions = food.servingDefinitions,
                ),
                selectedPortionId = null,
                quantityText = "",
                error = null,
            )
        }
    }

    fun selectQuickFood(quick: QuickFood) {
        val food = quick.food
        _state.update {
            it.copy(
                selectedFood = FoodChoice(
                    foodId = food.id,
                    sourceType = food.sourceType,
                    sourceExternalId = food.sourceExternalId.orEmpty(),
                    label = food.label,
                    nutrientsPer100g = food.nutrientsPer100g,
                    nutrientsPerUnit = food.nutrientsPerUnit,
                    servingDefinitions = food.servingDefinitions,
                ),
                selectedPortionId = if (food.nutrientsPer100g == null) {
                    food.servingDefinitions.firstOrNull()?.id
                } else null,
                quantityText = "",
                error = null,
            )
        }
    }

    fun toggleFavorite(quick: QuickFood) {
        val target = !quick.isFavorite
        viewModelScope.launch {
            try {
                repository.setFavorite(_state.value.profileId, quick.food.id, target)
                val transform: (QuickFood) -> QuickFood = { item ->
                    if (item.food.id == quick.food.id) item.copy(isFavorite = target) else item
                }
                _state.update { current ->
                    val recents = current.recentFoods.map(transform)
                    val favorites = if (target) {
                        (current.favoriteFoods + quick.copy(isFavorite = true)).distinctBy { it.food.id }
                    } else {
                        current.favoriteFoods.filterNot { it.food.id == quick.food.id }
                    }
                    current.copy(favoriteFoods = favorites, recentFoods = recents, error = null)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = userMessage(t, "Impossible de modifier le favori.")) }
            }
        }
    }

    fun selectPortion(portionId: String?) {
        val selected = _state.value.selectedFood ?: return
        if (portionId == null && selected.nutrientsPer100g == null) return
        if (portionId != null && selected.servingDefinitions.none { it.id == portionId }) return
        _state.update { it.copy(selectedPortionId = portionId, quantityText = "", error = null) }
    }

    fun dismissFood() {
        _state.update {
            it.copy(selectedFood = null, selectedPortionId = null, quantityText = "", error = null)
        }
    }

    fun updateQuantity(value: String) {
        val accepted = value.filter { it.isDigit() || it == ',' || it == '.' }
        _state.update { it.copy(quantityText = accepted, error = null) }
    }

    fun editItem(item: MealItem) {
        if (item.foodRefId == null) {
            _state.update { it.copy(error = "Cette ancienne entrée doit être supprimée puis ajoutée de nouveau.") }
            return
        }
        _state.update {
            it.copy(
                editingItem = item,
                editQuantityText = item.quantityValue?.toString().orEmpty(),
                error = null,
            )
        }
    }

    fun updateEditQuantity(value: String) {
        val accepted = value.filter { it.isDigit() || it == ',' || it == '.' }
        _state.update { it.copy(editQuantityText = accepted, error = null) }
    }

    fun dismissItemEdit() {
        _state.update { it.copy(editingItem = null, editQuantityText = "", error = null) }
    }

    fun confirmItemEdit() {
        val current = _state.value
        val item = current.editingItem ?: return
        val meal = current.draftMeal ?: return
        val quantity = current.editQuantityText.replace(',', '.').toDoubleOrNull()
        if (quantity == null || quantity <= 0.0) {
            _state.update { it.copy(error = "Indiquez une quantité supérieure à zéro.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(mutating = true, error = null) }
            try {
                val updated = repository.updateMealItemQuantity(
                    itemId = item.id,
                    quantityValue = quantity,
                    quantityUnit = item.quantityUnit ?: "g",
                    portionId = item.portionId,
                    expectedItemRevision = item.revision,
                    expectedMealRevision = meal.revision,
                )
                _state.update {
                    it.copy(
                        draftMeal = updated.meal,
                        items = it.items.map { existing -> if (existing.id == item.id) updated.item else existing },
                        editingItem = null,
                        editQuantityText = "",
                        mutating = false,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(mutating = false, error = userMessage(t, "Impossible de modifier la quantité.")) }
            }
        }
    }

    fun removeItem(item: MealItem) {
        val meal = _state.value.draftMeal ?: return
        viewModelScope.launch {
            _state.update { it.copy(mutating = true, error = null) }
            try {
                val removed = repository.removeMealItem(
                    itemId = item.id,
                    expectedItemRevision = item.revision,
                    expectedMealRevision = meal.revision,
                )
                _state.update {
                    it.copy(
                        draftMeal = removed.meal,
                        items = it.items.filterNot { existing -> existing.id == item.id },
                        mutating = false,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(mutating = false, error = userMessage(t, "Impossible de supprimer l’aliment.")) }
            }
        }
    }

    fun addSelectedFood() {
        val current = _state.value
        val selected = current.selectedFood ?: return
        val mealType = current.mealType ?: return
        val quantity = current.quantityText.replace(',', '.').toDoubleOrNull()
        if (quantity == null || quantity <= 0.0) {
            _state.update { it.copy(error = "Indiquez une quantité supérieure à zéro.") }
            return
        }
        val portion = current.selectedPortionId?.let { id ->
            selected.servingDefinitions.firstOrNull { it.id == id }
        }

        viewModelScope.launch {
            _state.update { it.copy(mutating = true, error = null) }
            try {
                val foodId = selected.foodId ?: when (selected.sourceType) {
                    "personal" -> repository.importPersonalFood(current.profileId, selected.sourceExternalId).food.id
                    "open_food_facts" -> repository.importOffFood(current.profileId, selected.sourceExternalId).food.id
                    else -> repository.importCiqualFood(current.profileId, selected.sourceExternalId).food.id
                }
                var meal = _state.value.draftMeal
                if (meal == null) {
                    meal = repository.createMeal(current.profileId, mealType, current.localDate).meal
                    _state.update { it.copy(draftMeal = meal) }
                }
                val added = repository.addFoodToMeal(
                    mealId = meal.id,
                    foodId = foodId,
                    quantityValue = quantity,
                    quantityUnit = portion?.unitLabel ?: "g",
                    portionId = portion?.id,
                    expectedMealRevision = meal.revision,
                )
                _state.update {
                    it.copy(
                        draftMeal = added.meal,
                        items = it.items + added.item,
                        query = "",
                        searchResults = emptyList(),
                        personalSearchResults = emptyList(),
                        searchAttempted = false,
                        searchedQuery = null,
                        effectiveSearchQuery = null,
                        selectedFood = null,
                        selectedPortionId = null,
                        quantityText = "",
                        barcodeText = "",
                        barcodeProduct = null,
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
        private val existingMeals: List<MealWithItems> = emptyList(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MealEntryViewModel::class.java))
            return MealEntryViewModel(repository, profileId, localDate, initialDraft, existingMeals) as T
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

internal fun matchingMealForType(
    meals: List<MealWithItems>,
    mealType: String,
): MealWithItems? = meals
    .filter { it.meal.status == "validated" && it.meal.mealType == mealType }
    .maxByOrNull { it.meal.createdAt }
