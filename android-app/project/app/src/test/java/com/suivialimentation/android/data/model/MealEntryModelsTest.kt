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
              }],
              "sourceVersion": "2025-11-03"
            }
        """.trimIndent()

        val response = AppJson.decodeFromString(CiqualSearchResponse.serializer(), json)

        assertEquals(1, response.items.size)
        assertEquals("36022", response.items.single().sourceExternalId)
        assertEquals(155.0, response.items.single().nutrientsPer100g.energyKcal ?: 0.0, 0.001)
        assertEquals("2025-11-03", response.sourceVersion)
    }

    @Test
    fun `decodes serving definition with null values from Home Assistant`() {
        val json = """{"unitLabel":null,"gramsEquivalent":null}"""

        val serving = AppJson.decodeFromString(ServingDefinition.serializer(), json)

        assertNull(serving.unitLabel)
        assertNull(serving.gramsEquivalent)
    }
}
