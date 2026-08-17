package com.suivialimentation.android.data.repository

import com.suivialimentation.android.data.ha.ConnectionState
import com.suivialimentation.android.data.ha.HomeAssistantApi
import com.suivialimentation.android.data.ha.HomeAssistantCommandException
import com.suivialimentation.android.data.ha.HomeAssistantWebSocketClient
import com.suivialimentation.android.data.ha.TransportDisconnectedException
import com.suivialimentation.android.data.model.GoalVersion
import com.suivialimentation.android.data.model.NutrientSnapshot
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
import kotlinx.serialization.json.jsonObject
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

    init {
        scope.launch {
            connectionState.filterIsInstance<ConnectionState.Connected>().collect {
                retryPendingOperations()
            }
        }
    }

    override suspend fun connect() = ws.start()

    override suspend fun disconnect() = ws.stop()

    override suspend fun loadToday(): TodayData {
        val mine = api.getMyProfile()
        val profile = mine.profile
        val zone = runCatching { ZoneId.of(profile.defaultTimeZone) }.getOrDefault(ZoneId.systemDefault())
        val localDate = LocalDate.now(zone)

        val (profileResponse, dayResponse) = coroutineScope {
            val p = async { api.getProfile(profile.id) }
            val d = async { api.getDay(profile.id, localDate.toString()) }
            p.await() to d.await()
        }

        revisionTracker.recordStoreRevision(profileResponse.storeRevision)
        revisionTracker.recordStoreRevision(dayResponse.storeRevision)
        revisionTracker.recordEntityRevision(profile.id, profile.revision)
        profileResponse.goalVersions.forEach { revisionTracker.recordEntityRevision(it.id, it.revision) }
        dayResponse.history?.let { revisionTracker.recordEntityRevision("${it.profileId}:${it.localDate}", it.revision) }
        dayResponse.meals.forEach { revisionTracker.recordEntityRevision(it.id, it.revision) }
        dayResponse.items.forEach { revisionTracker.recordEntityRevision(it.id, it.revision) }

        val itemsByMeal = dayResponse.items.groupBy { it.mealId }
        val groupedMeals = dayResponse.meals.map { meal ->
            MealWithItems(meal, itemsByMeal[meal.id].orEmpty().sortedBy { it.position })
        }

        return TodayData(
            profile = profile,
            localDate = localDate.toString(),
            activeGoal = selectGoal(profileResponse.goalVersions, localDate),
            totals = dayResponse.history?.totals ?: NutrientSnapshot(
                energyKcal = 0.0,
                proteinG = 0.0,
            ),
            hasHistory = dayResponse.history != null,
            meals = groupedMeals,
            storeRevision = maxOf(profileResponse.storeRevision, dayResponse.storeRevision),
        )
    }

    override suspend fun changes(profileId: String): Flow<Unit> =
        api.subscribeToV2Changes(profileId).map { Unit }

    override suspend fun executeMutation(
        commandType: String,
        payload: JsonObject,
        operationId: String?,
    ): JsonElement {
        val id = operationId ?: java.util.UUID.randomUUID().toString()
        val payloadWithOperationId = buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("operation_id", id)
        }
        val pending = operationStore.prepare(commandType, payloadWithOperationId, id)
        return executePending(pending)
    }

    override suspend fun retryPendingOperations() {
        for (pending in operationStore.list()) {
            try {
                executePending(pending)
            } catch (_: TransportDisconnectedException) {
                return
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun executePending(pending: PendingOperation): JsonElement {
        val payload = AppJson.parseToJsonElement(pending.payloadJson).jsonObject
        return try {
            val result = api.rawCommand(pending.commandType, payload)
            operationStore.complete(pending.operationId)
            recordRevisionsFromResult(result)
            result
        } catch (e: HomeAssistantCommandException) {
            operationStore.complete(pending.operationId)
            if (e.isConflict) {
                _issues.tryEmit(RepositoryIssue.Conflict(pending.operationId, e.commandCode, e.message))
            } else {
                _issues.tryEmit(RepositoryIssue.MutationRejected(pending.operationId, e.message))
            }
            throw e
        } catch (e: TransportDisconnectedException) {
            throw e
        }
    }

    private fun recordRevisionsFromResult(result: JsonElement) {
        val obj = result as? JsonObject ?: return
        obj["storeRevision"]?.jsonPrimitive?.content?.toLongOrNull()?.let(revisionTracker::recordStoreRevision)
        val id = obj["id"]?.jsonPrimitive?.content
        val revision = obj["revision"]?.jsonPrimitive?.content?.toLongOrNull()
        if (id != null && revision != null) revisionTracker.recordEntityRevision(id, revision)
    }

    private fun selectGoal(goals: List<GoalVersion>, localDate: LocalDate): GoalVersion? = goals
        .filter { goal ->
            val from = runCatching { LocalDate.parse(goal.effectiveFromLocalDate) }.getOrNull() ?: return@filter false
            val to = goal.effectiveToLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            !localDate.isBefore(from) && (to == null || !localDate.isAfter(to))
        }
        .maxByOrNull { it.versionNumber }
}
