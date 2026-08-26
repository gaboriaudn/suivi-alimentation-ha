package com.suivialimentation.android.data.features

import com.suivialimentation.android.data.ha.HomeAssistantApi
import com.suivialimentation.android.data.model.Meal
import com.suivialimentation.android.data.model.MealItem
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.util.AppJson
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RecipeSummary(
    val id: String,
    val name: String,
    val ingredients: List<String>,
)

data class MealTemplateSummary(
    val id: String,
    val name: String,
    val defaultMealType: String?,
    val items: List<String>,
)

data class HistoryDaySummary(
    val localDate: String,
    val totals: NutrientSnapshot,
)

data class HistoryAnalysis(
    val recordedDayCount: Int,
    val averages: NutrientSnapshot,
    val days: List<HistoryDaySummary>,
)

class FeatureRepository(private val api: HomeAssistantApi) {
    suspend fun getRecipes(profileId: String): List<RecipeSummary> {
        val root = api.rawCommand(
            "suivi_alimentation/v2/get_recipes",
            buildJsonObject { put("profile_id", profileId) },
        ).jsonObject
        return root["recipes"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val recipe = obj["recipe"]?.jsonObject ?: return@mapNotNull null
            val id = recipe["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = recipe["name"]?.jsonPrimitive?.content ?: "Recette"
            val ingredients = obj["ingredients"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["labelSnapshot"]?.jsonPrimitive?.content
            }
            RecipeSummary(id, name, ingredients)
        }
    }

    suspend fun getMealTemplates(profileId: String): List<MealTemplateSummary> {
        val root = api.rawCommand(
            "suivi_alimentation/v2/get_meal_templates",
            buildJsonObject { put("profile_id", profileId) },
        ).jsonObject
        return root["templates"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val template = obj["template"]?.jsonObject ?: return@mapNotNull null
            val id = template["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = template["name"]?.jsonPrimitive?.content ?: "Repas type"
            val mealType = template["defaultMealType"]?.jsonPrimitive?.content
            val items = obj["items"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["labelSnapshot"]?.jsonPrimitive?.content
            }
            MealTemplateSummary(id, name, mealType, items)
        }
    }

    suspend fun saveMealAsRecipe(sourceMealId: String, name: String): RecipeSummary {
        val root = api.rawCommand(
            "suivi_alimentation/v2/save_meal_as_recipe",
            buildJsonObject {
                put("source_meal_id", sourceMealId)
                put("name", name.trim())
                put("operation_id", UUID.randomUUID().toString())
            },
        ).jsonObject
        val recipe = root["recipe"]?.jsonObject ?: error("Recette non retournée par Home Assistant.")
        val ingredients = root["ingredients"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["labelSnapshot"]?.jsonPrimitive?.content
        }
        return RecipeSummary(
            id = recipe["id"]?.jsonPrimitive?.content ?: error("Identifiant de recette absent."),
            name = recipe["name"]?.jsonPrimitive?.content ?: name,
            ingredients = ingredients,
        )
    }

    suspend fun saveMealAsTemplate(sourceMealId: String, name: String): MealTemplateSummary {
        val root = api.rawCommand(
            "suivi_alimentation/v2/save_meal_as_template",
            buildJsonObject {
                put("source_meal_id", sourceMealId)
                put("name", name.trim())
                put("operation_id", UUID.randomUUID().toString())
            },
        ).jsonObject
        val template = root["template"]?.jsonObject ?: error("Repas type non retourné par Home Assistant.")
        val items = root["items"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["labelSnapshot"]?.jsonPrimitive?.content
        }
        return MealTemplateSummary(
            id = template["id"]?.jsonPrimitive?.content ?: error("Identifiant du repas type absent."),
            name = template["name"]?.jsonPrimitive?.content ?: name,
            defaultMealType = template["defaultMealType"]?.jsonPrimitive?.content,
            items = items,
        )
    }

    suspend fun createMealFromRecipe(recipeId: String, mealType: String, localDate: String): MealWithItems {
        val root = api.rawCommand(
            "suivi_alimentation/v2/create_meal_from_recipe",
            buildJsonObject {
                put("recipe_id", recipeId)
                put("meal_type", mealType)
                put("local_date", localDate)
                put("operation_id", UUID.randomUUID().toString())
            },
        ).jsonObject
        return root.toMealWithItems()
    }

    suspend fun createMealFromTemplate(templateId: String, mealType: String, localDate: String): MealWithItems {
        val root = api.rawCommand(
            "suivi_alimentation/v2/create_meal_from_template",
            buildJsonObject {
                put("template_id", templateId)
                put("meal_type", mealType)
                put("local_date", localDate)
                put("operation_id", UUID.randomUUID().toString())
            },
        ).jsonObject
        return root.toMealWithItems()
    }

    suspend fun getHistoryRange(profileId: String, startDate: String, endDate: String): HistoryAnalysis {
        val root = api.rawCommand(
            "suivi_alimentation/v2/get_history_range",
            buildJsonObject {
                put("profile_id", profileId)
                put("start_date", startDate)
                put("end_date", endDate)
            },
        ).jsonObject
        val averages = root["averages"]?.jsonObject.toNutrients()
        val days = root["days"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val date = obj["localDate"]?.jsonPrimitive?.content ?: return@mapNotNull null
            HistoryDaySummary(date, obj["totals"]?.jsonObject.toNutrients())
        }
        return HistoryAnalysis(
            recordedDayCount = root["recordedDayCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: days.size,
            averages = averages,
            days = days,
        )
    }

    private fun JsonObject.toMealWithItems(): MealWithItems {
        val meal = AppJson.decodeFromJsonElement(Meal.serializer(), this["meal"] ?: error("Repas absent."))
        val items = this["items"]?.jsonArray.orEmpty().map {
            AppJson.decodeFromJsonElement(MealItem.serializer(), it)
        }
        return MealWithItems(meal, items)
    }

    private fun JsonObject?.toNutrients(): NutrientSnapshot {
        fun number(key: String) = this?.get(key)?.jsonPrimitive?.doubleOrNull
        return NutrientSnapshot(
            energyKcal = number("energyKcal"),
            proteinG = number("proteinG"),
            carbsG = number("carbsG"),
            fatG = number("fatG"),
            fiberG = number("fiberG"),
            saltG = number("saltG"),
        )
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}
