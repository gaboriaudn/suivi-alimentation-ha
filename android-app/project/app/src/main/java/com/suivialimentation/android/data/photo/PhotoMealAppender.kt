package com.suivialimentation.android.data.photo

import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.data.repository.NutritionRepository

suspend fun appendPhotoSuggestionsToMeal(
    repository: NutritionRepository,
    profileId: String,
    mealType: String,
    localDate: String,
    currentDraft: MealWithItems?,
    suggestions: List<PhotoFoodSuggestion>,
): MealWithItems {
    require(suggestions.isNotEmpty()) { "Aucun aliment reconnu à ajouter." }

    var meal = currentDraft?.meal ?: repository.createMeal(profileId, mealType, localDate).meal
    val items = currentDraft?.items?.toMutableList() ?: mutableListOf()
    val initialItemCount = items.size
    val unresolved = mutableListOf<String>()

    for (suggestion in suggestions) {
        val personal = repository.searchPersonalFoods(profileId, suggestion.label, 3).firstOrNull()
        val foodId = if (personal != null) {
            repository.importPersonalFood(profileId, personal.sourceExternalId).food.id
        } else {
            val ciqual = repository.searchCiqual(profileId, suggestion.label, 5).firstOrNull()
            if (ciqual == null) {
                unresolved += suggestion.label
                continue
            }
            repository.importCiqualFood(profileId, ciqual.sourceExternalId).food.id
        }

        val added = repository.addFoodToMeal(
            mealId = meal.id,
            foodId = foodId,
            quantityValue = suggestion.estimatedGrams,
            quantityUnit = "g",
            portionId = null,
            expectedMealRevision = meal.revision,
        )
        meal = added.meal
        items += added.item
    }

    if (items.size == initialItemCount) {
        if (currentDraft == null) {
            runCatching { repository.voidMeal(meal.id, meal.revision) }
        }
        error(
            if (unresolved.isEmpty()) "Aucun aliment n’a pu être ajouté."
            else "Aucun aliment reconnu n’a pu être relié à vos aliments ou à CIQUAL : ${unresolved.joinToString()}.",
        )
    }

    return MealWithItems(meal, items)
}
