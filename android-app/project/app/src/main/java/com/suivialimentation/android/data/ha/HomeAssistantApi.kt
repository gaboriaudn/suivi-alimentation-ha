package com.suivialimentation.android.data.ha

import com.suivialimentation.android.data.model.CiqualSearchResponse
import com.suivialimentation.android.data.model.DayResponse
import com.suivialimentation.android.data.model.MyProfileResponse
import com.suivialimentation.android.data.model.ProfileResponse
import com.suivialimentation.android.data.model.RecentResponse
import com.suivialimentation.android.data.model.V2Status
import com.suivialimentation.android.util.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HomeAssistantApi(private val ws: HomeAssistantWebSocketClient) {
    suspend fun status(): V2Status = typed("suivi_alimentation/v2/status", V2Status.serializer())

    suspend fun getMyProfile(): MyProfileResponse =
        typed("suivi_alimentation/v2/get_my_profile", MyProfileResponse.serializer())

    suspend fun getProfile(profileId: String): ProfileResponse = typed(
        "suivi_alimentation/v2/get_profile",
        ProfileResponse.serializer(),
        buildJsonObject { put("profile_id", profileId) },
    )

    suspend fun getDay(profileId: String, localDate: String): DayResponse = typed(
        "suivi_alimentation/v2/get_day",
        DayResponse.serializer(),
        buildJsonObject {
            put("profile_id", profileId)
            put("local_date", localDate)
        },
    )

    suspend fun getRecent(profileId: String): RecentResponse = typed(
        "suivi_alimentation/v2/get_recent",
        RecentResponse.serializer(),
        buildJsonObject { put("profile_id", profileId) },
    )

    suspend fun searchCiqual(profileId: String, query: String, limit: Int = 20): CiqualSearchResponse = typed(
        "suivi_alimentation/v2/search_ciqual",
        CiqualSearchResponse.serializer(),
        buildJsonObject {
            put("profile_id", profileId)
            put("query", query)
            put("limit", limit)
        },
    )

    suspend fun rawCommand(type: String, payload: JsonObject): JsonElement = ws.command(type, payload)

    suspend fun subscribeToV2Changes(profileId: String): Flow<JsonElement> {
        val key = "suivi-v2-$profileId"
        val withProfile = buildJsonObject { put("profile_id", profileId) }
        return try {
            ws.subscribe(key, "suivi_alimentation/v2/subscribe", withProfile)
        } catch (e: HomeAssistantCommandException) {
            ws.removeSubscription(key)
            ws.subscribe(key, "suivi_alimentation/v2/subscribe", JsonObject(emptyMap()))
        }
    }

    private suspend fun <T> typed(
        type: String,
        serializer: KSerializer<T>,
        payload: JsonObject = JsonObject(emptyMap()),
    ): T = AppJson.decodeFromJsonElement(serializer, ws.command(type, payload))
}
