package com.suivialimentation.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PortionOption(
    val id: String,
    val label: String,
    val unitLabel: String,
    val gramsEquivalent: Double? = null,
    val sourceType: String,
    val sourceExternalId: String? = null,
    val sourcePortionId: String? = null,
    val sourceVersion: String? = null,
    val sourceUrl: String? = null,
)

@Serializable
data class CiqualFoodCandidate(
    val sourceType: String = "ciqual",
    val sourceExternalId: String,
    val label: String,
    val groupCode: String? = null,
    val subgroupCode: String? = null,
    val nutrientsPer100g: NutrientSnapshot,
    val sourceVersion: String? = null,
    val datasetDoi: String? = null,
    val servingDefinitions: List<PortionOption> = emptyList(),
)

@Serializable
data class CiqualSearchResponse(
    val items: List<CiqualFoodCandidate> = emptyList(),
    val sourceVersion: String? = null,
)

@Serializable
data class PersonalFoodCandidate(
    val sourceType: String = "personal",
    val sourceExternalId: String,
    val foodId: String? = null,
    val label: String,
    val nutritionBasis: String,
    val nutrientsPer100g: NutrientSnapshot? = null,
    val nutrientsPerUnit: NutrientSnapshot? = null,
    val servingDefinitions: List<PortionOption> = emptyList(),
    val sourceVersion: String? = null,
)

@Serializable
data class PersonalFoodSearchResponse(
    val items: List<PersonalFoodCandidate> = emptyList(),
)

@Serializable
data class OffProductCandidate(
    val sourceType: String = "open_food_facts",
    val sourceExternalId: String,
    val sourceVersion: String? = null,
    val label: String,
    val brand: String? = null,
    val barcode: String,
    val nutrientsPer100g: NutrientSnapshot,
    val servingSize: String? = null,
    val servingQuantityG: Double? = null,
    val servingDefinitions: List<PortionOption> = emptyList(),
)

@Serializable
data class ImportFoodResponse(
    val ok: Boolean = true,
    val idempotent: Boolean = false,
    val storeRevision: Long,
    val food: FoodReference,
)

@Serializable
data class CreateMealResponse(
    val ok: Boolean = true,
    val idempotent: Boolean = false,
    val storeRevision: Long,
    val meal: Meal,
)

@Serializable
data class AddFoodToMealResponse(
    val ok: Boolean = true,
    val idempotent: Boolean = false,
    val storeRevision: Long,
    val item: MealItem,
    val meal: Meal,
)

@Serializable
data class ValidateMealResponse(
    val ok: Boolean = true,
    val idempotent: Boolean = false,
    val storeRevision: Long,
    val meal: Meal,
    val dailyHistory: DayHistory? = null,
)
