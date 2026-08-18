package com.suivialimentation.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class NutrientSnapshot(
    val energyKcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val fiberG: Double? = null,
    val saltG: Double? = null,
)

@Serializable
data class ServingDefinition(
    val unitLabel: String? = null,
    val gramsEquivalent: Double? = null,
)
