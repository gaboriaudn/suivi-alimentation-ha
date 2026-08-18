package com.suivialimentation.android.data.model

import com.suivialimentation.android.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealEntryModelsTest {
    @Test
    fun `decodes live CIQUAL response shape`() {
        val json = """
            {
              "items": [{
                "sourceType": "ciqual",
                "sourceExternalId": "36022",
                "label": "Poulet, pilon cru",
                "groupCode": "04",
                "subgroupCode": "0402",
                "nutrientsPer100g": {
                  "energyKcal": 155.0,
                  "proteinG": 18.1,
                  "carbsG": 0.0,
                  "fatG": 9.2,
                  "fiberG": 0.0,
                  "saltG": 0.27
                },
                "confidenceByNutrient": {"energyKcal": "D"},
                "sourceVersion": "2025-11-03",
                "datasetDoi": "10.57745/RDMHWY"
                ,"servingDefinitions": [{
                  "id": "fdc:171287:88374",
                  "label": "1 gros œuf",
                  "unitLabel": "œuf(s)",
                  "gramsEquivalent": 50.0,
                  "sourceType": "food_data_central",
                  "sourceExternalId": "171287",
                  "sourcePortionId": "88374",
                  "sourceVersion": "fdc-sr-legacy-2019.04-j1.2.1"
                }]
              }],
              "sourceVersion": "2025-11-03"
            }
        """.trimIndent()

        val response = AppJson.decodeFromString(CiqualSearchResponse.serializer(), json)

        assertEquals(1, response.items.size)
        assertEquals("36022", response.items.single().sourceExternalId)
        assertEquals(155.0, response.items.single().nutrientsPer100g.energyKcal ?: 0.0, 0.001)
        assertEquals("2025-11-03", response.sourceVersion)
        assertEquals(50.0, response.items.single().servingDefinitions.single().gramsEquivalent ?: 0.0, 0.001)
    }

    @Test
    fun `decodes serving definition with null values from Home Assistant`() {
        val json = """{"unitLabel":null,"gramsEquivalent":null}"""

        val serving = AppJson.decodeFromString(ServingDefinition.serializer(), json)

        assertNull(serving.unitLabel)
        assertNull(serving.gramsEquivalent)
    }

    @Test
    fun `decodes personal article with per-unit nutrition and no invented weight`() {
        val json = """
            {"items":[{
              "sourceType":"personal",
              "sourceExternalId":"yaourt-maison",
              "foodId":"food-v2",
              "label":"Yaourt maison",
              "nutritionBasis":"per_unit",
              "nutrientsPer100g":null,
              "nutrientsPerUnit":{"energyKcal":85.0,"proteinG":4.2},
              "servingDefinitions":[{
                "id":"personal:yaourt-maison:unit",
                "label":"1 pot",
                "unitLabel":"pot",
                "gramsEquivalent":null,
                "sourceType":"user_defined"
              }]
            }]}
        """.trimIndent()

        val response = AppJson.decodeFromString(PersonalFoodSearchResponse.serializer(), json)

        assertEquals("Yaourt maison", response.items.single().label)
        assertEquals(85.0, response.items.single().nutrientsPerUnit?.energyKcal ?: 0.0, 0.001)
        assertNull(response.items.single().servingDefinitions.single().gramsEquivalent)
    }
}
