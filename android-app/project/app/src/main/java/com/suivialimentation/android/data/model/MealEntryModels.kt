package com.suivialimentation.android.data.model

import kotlinx.serialization.Serializable

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
)

@Serializable
data class CiqualSearchResponse(
    val items: List<CiqualFoodCandidate> = emptyList(),
    val sourceVersion: String? = null,
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
