package com.suivialimentation.android.data.library

import com.suivialimentation.android.data.ha.HomeAssistantApi
import com.suivialimentation.android.data.model.FoodReference
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.util.AppJson
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class LibraryComponent(
    val foodRefId: String,
    val label: String,
    val quantityValue: Double,
    val quantityUnit: String,
    val portionId: String? = null,
)

data class LibraryRecipe(
    val id: String,
    val name: String,
    val components: List<LibraryComponent>,
)

data class LibraryMealTemplate(
    val id: String,
    val name: String,
    val defaultMealType: String?,
    val components: List<LibraryComponent>,
)

data class LibraryData(
    val foods: List<FoodReference> = emptyList(),
    val recipes: List<LibraryRecipe> = emptyList(),
    val mealTemplates: List<LibraryMealTemplate> = emptyList(),
    val storeRevision: Long = 0,
)

data class LibraryItemInput(
    val foodRefId: String,
    val quantityValue: Double,
    val quantityUnit: String = "g",
    val portionId: String? = null,
)

class LibraryRepository(private val api: HomeAssistantApi) {
    suspend fun load(profileId: String): LibraryData {
        val root = api.rawCommand(
            "suivi_alimentation/v2/library/get",
            buildJsonObject { put("profile_id", profileId) },
        ).jsonObject
        val foods = root["foods"]?.jsonArray.orEmpty().map {
            AppJson.decodeFromJsonElement(FoodReference.serializer(), it)
        }
        val recipes = root["recipes"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val recipe = obj["recipe"]?.jsonObject ?: return@mapNotNull null
            LibraryRecipe(
                id = recipe["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = recipe["name"]?.jsonPrimitive?.content ?: "Recette",
                components = parseComponents(obj["ingredients"] as? JsonArray),
            )
        }
        val templates = root["templates"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val template = obj["template"]?.jsonObject ?: return@mapNotNull null
            LibraryMealTemplate(
                id = template["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = template["name"]?.jsonPrimitive?.content ?: "Repas type",
                defaultMealType = template["defaultMealType"]?.jsonPrimitive?.contentOrNull,
                components = parseComponents(obj["items"] as? JsonArray),
            )
        }
        return LibraryData(
            foods = foods,
            recipes = recipes,
            mealTemplates = templates,
            storeRevision = root["storeRevision"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
        )
    }

    suspend fun updateFood(profileId: String, foodId: String, label: String, nutrients: NutrientSnapshot) {
        api.rawCommand(
            "suivi_alimentation/v2/library/update_food",
            buildJsonObject {
                put("profile_id", profileId)
                put("food_id", foodId)
                put("label", label.trim())
                put("nutrients_per_100g", nutrientsJson(nutrients))
                put("operation_id", UUID.randomUUID().toString())
            },
        )
    }

    suspend fun deleteFood(profileId: String, foodId: String) {
        api.rawCommand(
            "suivi_alimentation/v2/library/delete_food",
            buildJsonObject {
                put("profile_id", profileId)
                put("food_id", foodId)
                put("operation_id", UUID.randomUUID().toString())
            },
        )
    }

    suspend fun updateRecipe(profileId: String, recipeId: String, name: String, items: List<LibraryItemInput>) {
        api.rawCommand(
            "suivi_alimentation/v2/library/update_recipe",
            buildJsonObject {
                put("profile_id", profileId)
                put("recipe_id", recipeId)
                put("name", name.trim())
                put("items", itemsJson(items))
                put("operation_id", UUID.randomUUID().toString())
            },
        )
    }

    suspend fun deleteRecipe(profileId: String, recipeId: String) {
        api.rawCommand(
            "suivi_alimentation/v2/library/delete_recipe",
            buildJsonObject {
                put("profile_id", profileId)
                put("recipe_id", recipeId)
                put("operation_id", UUID.randomUUID().toString())
            },
        )
    }

    suspend fun updateMealTemplate(profileId: String, templateId: String, name: String, defaultMealType: String?, items: List<LibraryItemInput>) {
        api.rawCommand(
            "suivi_alimentation/v2/library/update_meal_template",
            buildJsonObject {
                put("profile_id", profileId)
                put("template_id", templateId)
                put("name", name.trim())
                defaultMealType?.let { put("default_meal_type", it) }
                put("items", itemsJson(items))
                put("operation_id", UUID.randomUUID().toString())
            },
        )
    }

    suspend fun deleteMealTemplate(profileId: String, templateId: String) {
        api.rawCommand(
            "suivi_alimentation/v2/library/delete_meal_template",
            buildJsonObject {
                put("profile_id", profileId)
                put("template_id", templateId)
                put("operation_id", UUID.randomUUID().toString())
            },
        )
    }

    private fun parseComponents(array: JsonArray?): List<LibraryComponent> = array.orEmpty().mapNotNull { element ->
        val obj = element.jsonObject
        val foodRefId = obj["foodRefId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        LibraryComponent(
            foodRefId = foodRefId,
            label = obj["labelSnapshot"]?.jsonPrimitive?.contentOrNull ?: "Aliment",
            quantityValue = obj["quantityValue"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            quantityUnit = obj["quantityUnit"]?.jsonPrimitive?.contentOrNull ?: "g",
            portionId = obj["portionId"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun itemsJson(items: List<LibraryItemInput>) = buildJsonArray {
        items.forEach { item ->
            add(buildJsonObject {
                put("food_ref_id", item.foodRefId)
                put("quantity_value", item.quantityValue)
                put("quantity_unit", item.quantityUnit)
                item.portionId?.let { put("portion_id", it) }
            })
        }
    }

    private fun nutrientsJson(nutrients: NutrientSnapshot): JsonObject = buildJsonObject {
        nutrients.energyKcal?.let { put("energyKcal", it) }
        nutrients.proteinG?.let { put("proteinG", it) }
        nutrients.carbsG?.let { put("carbsG", it) }
        nutrients.fatG?.let { put("fatG", it) }
        nutrients.fiberG?.let { put("fiberG", it) }
        nutrients.saltG?.let { put("saltG", it) }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}
