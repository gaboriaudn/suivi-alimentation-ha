package com.suivialimentation.android.data.repository

import com.suivialimentation.android.data.ha.ConnectionState
import com.suivialimentation.android.data.ha.HomeAssistantApi
import com.suivialimentation.android.data.ha.HomeAssistantCommandException
import com.suivialimentation.android.data.ha.HomeAssistantWebSocketClient
import com.suivialimentation.android.data.ha.TransportDisconnectedException
import com.suivialimentation.android.data.model.AddFoodToMealResponse
import com.suivialimentation.android.data.model.CiqualFoodCandidate
import com.suivialimentation.android.data.model.CreateMealResponse
import com.suivialimentation.android.data.model.DuplicateMealResponse
import com.suivialimentation.android.data.model.GoalVersion
import com.suivialimentation.android.data.model.ImportFoodResponse
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.model.OffProductCandidate
import com.suivialimentation.android.data.model.PersonalFoodCandidate
import com.suivialimentation.android.data.model.ValidateMealResponse
import com.suivialimentation.android.data.model.UpdateMealItemResponse
import com.suivialimentation.android.data.model.RemoveMealItemResponse
import com.suivialimentation.android.data.model.VoidMealResponse
import com.suivialimentation.android.ui.profile.ProfileContext
import com.suivialimentation.android.util.AppJson
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DefaultNutritionRepository(
    private val ws: HomeAssistantWebSocketClient,
    private val api: HomeAssistantApi,
    private val operationStore: OperationStore,
    private val revisionTracker: RevisionTracker,
    private val scope: CoroutineScope,
) : NutritionRepository {
    override val connectionState = ws.state
    private val _issues = MutableSharedFlow<RepositoryIssue>(extraBufferCapacity = 16)
    override val issues: Flow<RepositoryIssue> = _issues
    init { scope.launch { connectionState.filterIsInstance<ConnectionState.Connected>().collect { retryPendingOperations() } } }
    override suspend fun connect() = ws.start()
    override suspend fun disconnect() = ws.stop()

    override suspend fun loadToday(): TodayData {
        val mine = api.getMyProfile(); val profile = mine.profile
        val zone = runCatching { ZoneId.of(profile.defaultTimeZone) }.getOrDefault(ZoneId.systemDefault())
        return loadDay(profile.id, LocalDate.now(zone).toString())
    }
    override suspend fun loadDay(profileId: String, localDate: String): TodayData {
        val parsedLocalDate = LocalDate.parse(localDate)
        val (profileResponse, dayResponse) = coroutineScope { val p = async { api.getProfile(profileId) }; val d = async { api.getDay(profileId, localDate) }; p.await() to d.await() }
        val profile = profileResponse.profile
        revisionTracker.recordStoreRevision(profileResponse.storeRevision); revisionTracker.recordStoreRevision(dayResponse.storeRevision); revisionTracker.recordEntityRevision(profile.id, profile.revision)
        profileResponse.goalVersions.forEach { revisionTracker.recordEntityRevision(it.id, it.revision) }
        dayResponse.history?.let { revisionTracker.recordEntityRevision("${it.profileId}:${it.localDate}", it.revision) }
        dayResponse.meals.forEach { revisionTracker.recordEntityRevision(it.id, it.revision) }; dayResponse.items.forEach { revisionTracker.recordEntityRevision(it.id, it.revision) }
        val itemsByMeal = dayResponse.items.groupBy { it.mealId }
        return TodayData(profile, localDate, selectGoal(profileResponse.goalVersions, parsedLocalDate), dayResponse.history?.totals ?: NutrientSnapshot(energyKcal = 0.0, proteinG = 0.0), dayResponse.history != null, dayResponse.meals.map { MealWithItems(it, itemsByMeal[it.id].orEmpty().sortedBy { item -> item.position }) }, maxOf(profileResponse.storeRevision, dayResponse.storeRevision))
    }
    override suspend fun changes(profileId: String): Flow<Unit> = api.subscribeToV2Changes(profileId).map { Unit }
    override suspend fun loadProfileContext(profileId: String): ProfileContext = AppJson.decodeFromJsonElement(ProfileContext.serializer(), api.rawCommand("suivi_alimentation/v2/profile/get", buildJsonObject { put("profile_id", profileId) }))
    override suspend fun updateProfileContext(profileId: String, settings: JsonObject, expectedProfileRevision: Long): ProfileContext {
        val result = executeMutation("suivi_alimentation/v2/profile/update", buildJsonObject { put("profile_id", profileId); put("settings", settings); put("expected_profile_revision", expectedProfileRevision) })
        return AppJson.decodeFromJsonElement(ProfileContext.serializer(), result)
    }
    override suspend fun loadQuickFoods(profileId: String): QuickFoods {
        val (profile, recent) = coroutineScope { val p = async { api.getProfile(profileId) }; val r = async { api.getRecent(profileId) }; p.await() to r.await() }
        val foodsById = profile.foods.associateBy { it.id }; val favoriteIds = recent.favorites.mapTo(linkedSetOf()) { it.foodRefId }
        return QuickFoods(favoriteIds.mapNotNull { id -> foodsById[id]?.let { QuickFood(it, true) } }, recent.items.mapNotNull { item -> item.foodRefId?.let { id -> foodsById[id]?.let { QuickFood(it, id in favoriteIds, item.lastUsedLocalDate) } } })
    }
    override suspend fun setFavorite(profileId: String, foodRefId: String, favorite: Boolean) { executeMutation("suivi_alimentation/v2/set_favorite", buildJsonObject { put("profile_id", profileId); put("food_ref_id", foodRefId); put("favorite", favorite) }) }
    override suspend fun searchCiqual(profileId: String, query: String, limit: Int): List<CiqualFoodCandidate> = api.searchCiqual(profileId, query.trim(), limit).items
    override suspend fun searchPersonalFoods(profileId: String, query: String, limit: Int): List<PersonalFoodCandidate> = api.searchPersonalFoods(profileId, query.trim(), limit).items
    override suspend fun importCiqualFood(profileId: String, ciqualCode: String): ImportFoodResponse = AppJson.decodeFromJsonElement(ImportFoodResponse.serializer(), executeMutation("suivi_alimentation/v2/import_ciqual_food", buildJsonObject { put("profile_id", profileId); put("ciqual_code", ciqualCode) }))
    override suspend fun importPersonalFood(profileId: String, legacyFoodId: String): ImportFoodResponse = AppJson.decodeFromJsonElement(ImportFoodResponse.serializer(), executeMutation("suivi_alimentation/v2/import_personal_food", buildJsonObject { put("profile_id", profileId); put("legacy_food_id", legacyFoodId) }))
    override suspend fun createManualFood(profileId: String, label: String, nutrientsPer100g: NutrientSnapshot): ImportFoodResponse {
        val nutrients = buildJsonObject {
            nutrientsPer100g.energyKcal?.let { put("energyKcal", it) }
            nutrientsPer100g.proteinG?.let { put("proteinG", it) }
            nutrientsPer100g.carbsG?.let { put("carbsG", it) }
            nutrientsPer100g.fatG?.let { put("fatG", it) }
            nutrientsPer100g.fiberG?.let { put("fiberG", it) }
            nutrientsPer100g.saltG?.let { put("saltG", it) }
        }
        return AppJson.decodeFromJsonElement(ImportFoodResponse.serializer(), executeMutation("suivi_alimentation/v2/create_manual_food", buildJsonObject { put("profile_id", profileId); put("label", label.trim()); put("nutrients_per_100g", nutrients) }))
    }
    override suspend fun getOffProduct(profileId: String, barcode: String): OffProductCandidate = api.getOffProduct(profileId, normalizeBarcode(barcode))
    override suspend fun importOffFood(profileId: String, barcode: String): ImportFoodResponse = AppJson.decodeFromJsonElement(ImportFoodResponse.serializer(), executeMutation("suivi_alimentation/v2/import_off_food", buildJsonObject { put("profile_id", profileId); put("barcode", normalizeBarcode(barcode)) }))
    override suspend fun createMeal(profileId: String, mealType: String, localDate: String): CreateMealResponse = AppJson.decodeFromJsonElement(CreateMealResponse.serializer(), executeMutation("suivi_alimentation/v2/create_meal", buildJsonObject { put("profile_id", profileId); put("meal_type", mealType); put("consumption_local_date", localDate) }))
    override suspend fun addFoodToMeal(mealId: String, foodId: String, quantityValue: Double, quantityUnit: String, portionId: String?, expectedMealRevision: Long): AddFoodToMealResponse { require(quantityValue > 0.0); return AppJson.decodeFromJsonElement(AddFoodToMealResponse.serializer(), executeMutation("suivi_alimentation/v2/add_food_to_meal", buildJsonObject { put("meal_id", mealId); put("food_id", foodId); put("quantity_value", quantityValue); put("quantity_unit", quantityUnit); portionId?.let { put("portion_id", it) }; put("expected_meal_revision", expectedMealRevision) })) }
    override suspend fun validateMeal(mealId: String, expectedMealRevision: Long): ValidateMealResponse = AppJson.decodeFromJsonElement(ValidateMealResponse.serializer(), executeMutation("suivi_alimentation/v2/validate_meal", buildJsonObject { put("meal_id", mealId); put("expected_meal_revision", expectedMealRevision) }))
    override suspend fun duplicateMeal(sourceMealId: String, targetLocalDate: String): DuplicateMealResponse = AppJson.decodeFromJsonElement(DuplicateMealResponse.serializer(), executeMutation("suivi_alimentation/v2/duplicate_meal", buildJsonObject { put("source_meal_id", sourceMealId); put("target_local_date", targetLocalDate) }))
    override suspend fun startMealCorrection(sourceMealId: String): DuplicateMealResponse = AppJson.decodeFromJsonElement(DuplicateMealResponse.serializer(), executeMutation("suivi_alimentation/v2/start_meal_correction", buildJsonObject { put("source_meal_id", sourceMealId) }))
    override suspend fun updateMealItemQuantity(itemId: String, quantityValue: Double, quantityUnit: String, portionId: String?, expectedItemRevision: Long, expectedMealRevision: Long): UpdateMealItemResponse { require(quantityValue > 0.0); return AppJson.decodeFromJsonElement(UpdateMealItemResponse.serializer(), executeMutation("suivi_alimentation/v2/update_food_meal_item", buildJsonObject { put("item_id", itemId); put("quantity_value", quantityValue); put("quantity_unit", quantityUnit); portionId?.let { put("portion_id", it) }; put("expected_item_revision", expectedItemRevision); put("expected_meal_revision", expectedMealRevision) })) }
    override suspend fun removeMealItem(itemId: String, expectedItemRevision: Long, expectedMealRevision: Long): RemoveMealItemResponse = AppJson.decodeFromJsonElement(RemoveMealItemResponse.serializer(), executeMutation("suivi_alimentation/v2/remove_meal_item", buildJsonObject { put("item_id", itemId); put("expected_item_revision", expectedItemRevision); put("expected_meal_revision", expectedMealRevision) }))
    override suspend fun voidMeal(mealId: String, expectedMealRevision: Long): VoidMealResponse = AppJson.decodeFromJsonElement(VoidMealResponse.serializer(), executeMutation("suivi_alimentation/v2/void_meal", buildJsonObject { put("meal_id", mealId); put("expected_meal_revision", expectedMealRevision) }))

    override suspend fun executeMutation(commandType: String, payload: JsonObject, operationId: String?): JsonElement {
        val id = operationId ?: java.util.UUID.randomUUID().toString(); val payloadWithOperationId = buildJsonObject { payload.forEach { (key, value) -> put(key, value) }; put("operation_id", id) }
        return executePending(operationStore.prepare(commandType, payloadWithOperationId, id))
    }
    override suspend fun retryPendingOperations() { for (pending in operationStore.list()) { operationStore.complete(pending.operationId); _issues.tryEmit(RepositoryIssue.MutationRejected(pending.operationId, "Une écriture interrompue n'a pas été rejouée automatiquement. Les données Home Assistant doivent être rechargées avant de continuer.")) } }
    private suspend fun executePending(pending: PendingOperation): JsonElement {
        val payload = AppJson.parseToJsonElement(pending.payloadJson) as JsonObject
        return try { val result = api.rawCommand(pending.commandType, payload); operationStore.complete(pending.operationId); recordRevisionsFromResult(result); result }
        catch (e: HomeAssistantCommandException) { operationStore.complete(pending.operationId); if (e.isConflict) _issues.tryEmit(RepositoryIssue.Conflict(pending.operationId, e.commandCode, e.message)) else _issues.tryEmit(RepositoryIssue.MutationRejected(pending.operationId, e.message)); throw e }
        catch (e: TransportDisconnectedException) { operationStore.complete(pending.operationId); _issues.tryEmit(RepositoryIssue.MutationRejected(pending.operationId, "Connexion interrompue pendant l'écriture. Vérifiez les données Home Assistant avant de réessayer.")); throw e }
    }
    private fun recordRevisionsFromResult(result: JsonElement) { val obj = result as? JsonObject ?: return; obj["storeRevision"]?.jsonPrimitive?.content?.toLongOrNull()?.let(revisionTracker::recordStoreRevision); recordEntityObject(obj); listOf("meal", "item", "food", "entity", "profile").forEach { key -> (obj[key] as? JsonObject)?.let(::recordEntityObject) } }
    private fun recordEntityObject(obj: JsonObject) { val id = obj["id"]?.jsonPrimitive?.content; val revision = obj["revision"]?.jsonPrimitive?.content?.toLongOrNull(); if (id != null && revision != null) revisionTracker.recordEntityRevision(id, revision) }
    private fun selectGoal(goals: List<GoalVersion>, localDate: LocalDate): GoalVersion? = goals.filter { goal -> val from = runCatching { LocalDate.parse(goal.effectiveFromLocalDate) }.getOrNull() ?: return@filter false; val to = goal.effectiveToLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }; !localDate.isBefore(from) && (to == null || !localDate.isAfter(to)) }.maxByOrNull { it.versionNumber }
}

internal fun normalizeBarcode(value: String): String { val digits = value.filter(Char::isDigit); require(digits.length in 8..14) { "Le code-barres doit contenir entre 8 et 14 chiffres." }; return digits }
