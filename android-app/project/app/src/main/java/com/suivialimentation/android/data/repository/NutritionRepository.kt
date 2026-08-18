package com.suivialimentation.android.data.repository

import com.suivialimentation.android.data.ha.ConnectionState
import com.suivialimentation.android.data.model.AddFoodToMealResponse
import com.suivialimentation.android.data.model.CiqualFoodCandidate
import com.suivialimentation.android.data.model.CreateMealResponse
import com.suivialimentation.android.data.model.DuplicateMealResponse
import com.suivialimentation.android.data.model.GoalVersion
import com.suivialimentation.android.data.model.FoodReference
import com.suivialimentation.android.data.model.ImportFoodResponse
import com.suivialimentation.android.data.model.Meal
import com.suivialimentation.android.data.model.MealItem
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.model.OffProductCandidate
import com.suivialimentation.android.data.model.PersonalFoodCandidate
import com.suivialimentation.android.data.model.Profile
import com.suivialimentation.android.data.model.ValidateMealResponse
import com.suivialimentation.android.data.model.UpdateMealItemResponse
import com.suivialimentation.android.data.model.RemoveMealItemResponse
import com.suivialimentation.android.data.model.VoidMealResponse
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

data class QuickFood(
    val food: FoodReference,
    val isFavorite: Boolean,
    val lastUsedLocalDate: String? = null,
)

data class QuickFoods(
    val favorites: List<QuickFood>,
    val recents: List<QuickFood>,
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
    suspend fun loadDay(profileId: String, localDate: String): TodayData
    suspend fun changes(profileId: String): Flow<Unit>

    suspend fun searchCiqual(profileId: String, query: String, limit: Int = 20): List<CiqualFoodCandidate>
    suspend fun searchPersonalFoods(profileId: String, query: String, limit: Int = 20): List<PersonalFoodCandidate>
    suspend fun importCiqualFood(profileId: String, ciqualCode: String): ImportFoodResponse
    suspend fun importPersonalFood(profileId: String, legacyFoodId: String): ImportFoodResponse
    suspend fun getOffProduct(profileId: String, barcode: String): OffProductCandidate
    suspend fun importOffFood(profileId: String, barcode: String): ImportFoodResponse
    suspend fun loadQuickFoods(profileId: String): QuickFoods
    suspend fun setFavorite(profileId: String, foodRefId: String, favorite: Boolean)
    suspend fun createMeal(profileId: String, mealType: String, localDate: String): CreateMealResponse
    suspend fun addFoodToMeal(
        mealId: String,
        foodId: String,
        quantityValue: Double,
        quantityUnit: String,
        portionId: String?,
        expectedMealRevision: Long,
    ): AddFoodToMealResponse
    suspend fun validateMeal(mealId: String, expectedMealRevision: Long): ValidateMealResponse
    suspend fun duplicateMeal(sourceMealId: String, targetLocalDate: String): DuplicateMealResponse
    suspend fun startMealCorrection(sourceMealId: String): DuplicateMealResponse
    suspend fun updateMealItemQuantity(
        itemId: String,
        quantityValue: Double,
        quantityUnit: String,
        portionId: String?,
        expectedItemRevision: Long,
        expectedMealRevision: Long,
    ): UpdateMealItemResponse
    suspend fun removeMealItem(
        itemId: String,
        expectedItemRevision: Long,
        expectedMealRevision: Long,
    ): RemoveMealItemResponse
    suspend fun voidMeal(mealId: String, expectedMealRevision: Long): VoidMealResponse

    suspend fun executeMutation(
        commandType: String,
        payload: JsonObject,
        operationId: String? = null,
    ): JsonElement

    suspend fun retryPendingOperations()
}
