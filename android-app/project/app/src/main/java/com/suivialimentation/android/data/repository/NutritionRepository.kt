package com.suivialimentation.android.data.repository

import com.suivialimentation.android.data.ha.ConnectionState
import com.suivialimentation.android.data.model.GoalVersion
import com.suivialimentation.android.data.model.Meal
import com.suivialimentation.android.data.model.MealItem
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class MealWithItems(
    val meal: Meal,
    val items: List<MealItem>,
)

data class TodayData(
    val profile: Profile,
    val localDate: String,
    val activeGoal: GoalVersion?,
    val totals: NutrientSnapshot,
    val hasHistory: Boolean,
    val meals: List<MealWithItems>,
    val storeRevision: Long,
)

sealed interface RepositoryIssue {
    data class Conflict(
        val operationId: String,
        val code: String?,
        val message: String,
    ) : RepositoryIssue

    data class MutationRejected(
        val operationId: String,
        val message: String,
    ) : RepositoryIssue
}

interface NutritionRepository {
    val connectionState: StateFlow<ConnectionState>
    val issues: Flow<RepositoryIssue>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun loadToday(): TodayData
    suspend fun changes(profileId: String): Flow<Unit>

    suspend fun executeMutation(
        commandType: String,
        payload: JsonObject,
        operationId: String? = null,
    ): JsonElement

    suspend fun retryPendingOperations()
}
