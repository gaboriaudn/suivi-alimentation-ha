package com.suivialimentation.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Profile(
    val id: String,
    val displayName: String,
    val ownerHaUserId: String,
    val defaultTimeZone: String,
    val locale: String,
    val status: String,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
    val archivedAt: String? = null,
    val legacyRef: JsonObject? = null,
)

@Serializable
data class GoalVersion(
    val id: String,
    val profileId: String,
    val versionNumber: Int,
    val effectiveFromLocalDate: String,
    val effectiveToLocalDate: String? = null,
    val targets: NutrientSnapshot,
    val sourceType: String,
    val sourceDetails: JsonObject? = null,
    val createdByHaUserId: String? = null,
    val createdAt: String,
    val revision: Long,
)

@Serializable
data class FoodReference(
    val id: String,
    val sourceType: String,
    val sourceExternalId: String? = null,
    val ownerProfileId: String? = null,
    val label: String,
    val brand: String? = null,
    val barcode: String? = null,
    val nutritionBasis: String,
    val nutrientsPer100g: NutrientSnapshot? = null,
    val nutrientsPerUnit: NutrientSnapshot? = null,
    val servingDefinition: ServingDefinition? = null,
    val defaultMealCategoryLegacy: String? = null,
    val provenanceId: String? = null,
    val derivedFromFoodRefId: String? = null,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
    val archivedAt: String? = null,
    val legacyRef: JsonObject? = null,
)

@Serializable
data class MyProfileResponse(
    val profile: Profile,
    val isAdmin: Boolean,
)

@Serializable
data class ProfileResponse(
    val profile: Profile,
    val goalVersions: List<GoalVersion> = emptyList(),
    val foods: List<FoodReference> = emptyList(),
    val storeRevision: Long,
)
