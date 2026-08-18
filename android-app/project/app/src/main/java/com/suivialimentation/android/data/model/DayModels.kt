package com.suivialimentation.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DayHistory(
    val profileId: String,
    val localDate: String,
    val mealIds: List<String> = emptyList(),
    val validatedMealCount: Int = 0,
    val totals: NutrientSnapshot,
    val revision: Long,
    val updatedAt: String,
)

@Serializable
data class Meal(
    val id: String,
    val profileId: String,
    val mealType: String,
    val label: String? = null,
    val status: String,
    val consumptionLocalDate: String,
    val consumedAtUtc: String? = null,
    val timeZone: String? = null,
    val datePrecision: String? = null,
    val totalsSnapshot: NutrientSnapshot? = null,
    val goalVersionId: String? = null,
    val origin: String? = null,
    val supersedesMealId: String? = null,
    val supersededByMealId: String? = null,
    val createdAt: String,
    val validatedAt: String? = null,
    val voidedAt: String? = null,
    val revision: Long,
    val legacyRef: JsonObject? = null,
)

@Serializable
data class MealItem(
    val id: String,
    val mealId: String,
    val kind: String,
    val foodRefId: String? = null,
    val recipeId: String? = null,
    val recipeRevisionId: String? = null,
    val labelSnapshot: String,
    val quantityValue: Double? = null,
    val quantityUnit: String? = null,
    val gramsEquivalent: Double? = null,
    val portionId: String? = null,
    val portionLabelSnapshot: String? = null,
    val nutritionSnapshot: NutrientSnapshot? = null,
    val provenanceId: String? = null,
    val createdFromProposalId: String? = null,
    val position: Int = 0,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class DayResponse(
    val history: DayHistory? = null,
    val meals: List<Meal> = emptyList(),
    val items: List<MealItem> = emptyList(),
    val storeRevision: Long,
)

@Serializable
data class RecentItem(
    val label: String,
    val foodRefId: String? = null,
    val recipeId: String? = null,
    val recipeRevisionId: String? = null,
    val lastUsedLocalDate: String,
)

@Serializable
data class RecentResponse(
    val items: List<RecentItem> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
)

@Serializable
data class FavoriteItem(
    val id: String,
    val profileId: String,
    val foodRefId: String,
    val createdAt: String,
    val updatedAt: String,
    val revision: Long,
)
