package com.suivialimentation.android.data.model

import com.suivialimentation.android.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2ContractFixtureTest {
    @Test
    fun parsesHistoricalDayExactlyLikeCurrentHomeAssistantContract() {
        val fixture = """
            {
              "history": {
                "profileId": "default",
                "localDate": "2026-05-07",
                "mealIds": ["meal-1"],
                "validatedMealCount": 1,
                "totals": {"energyKcal":150.0,"proteinG":12.0,"carbsG":null,"fatG":null,"fiberG":null,"saltG":null},
                "revision": 1,
                "updatedAt": "2026-08-17T14:12:46.955925+00:00"
              },
              "meals": [{
                "id":"meal-1","profileId":"default","mealType":"lunch","label":"Oeuf","status":"validated",
                "consumptionLocalDate":"2026-05-07","consumedAtUtc":null,"timeZone":null,"datePrecision":"date_only_legacy",
                "totalsSnapshot":{"energyKcal":150,"proteinG":12,"carbsG":null,"fatG":null,"fiberG":null,"saltG":null},
                "goalVersionId":null,"origin":"legacy_migration","supersedesMealId":null,"supersededByMealId":null,
                "createdAt":"2026-05-07T12:24:58.240Z","validatedAt":null,"voidedAt":null,"revision":1
              }],
              "items": [{
                "id":"item-1","mealId":"meal-1","kind":"manual_estimate","foodRefId":null,"recipeId":null,"recipeRevisionId":null,
                "labelSnapshot":"Oeuf","quantityValue":2,"quantityUnit":"portion","gramsEquivalent":null,
                "nutritionSnapshot":{"energyKcal":150,"proteinG":12,"carbsG":null,"fatG":null,"fiberG":null,"saltG":null},
                "provenanceId":"prov-1","createdFromProposalId":null,"position":0,"revision":1,
                "createdAt":"2026-05-07T12:24:58.240Z","updatedAt":"2026-05-07T12:24:58.240Z"
              }],
              "storeRevision": 1
            }
        """.trimIndent()

        val day = AppJson.decodeFromString<DayResponse>(fixture)
        assertEquals(150.0, day.history?.totals?.energyKcal ?: 0.0, 0.0)
        assertEquals(12.0, day.items.single().nutritionSnapshot?.proteinG ?: 0.0, 0.0)
        assertNull(day.items.single().nutritionSnapshot?.carbsG)
        assertEquals(1L, day.storeRevision)
    }
}
