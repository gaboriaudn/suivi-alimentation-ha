"""One-way compatibility bridge from Store v2 to the legacy dashboard store."""
from __future__ import annotations

from copy import deepcopy
from typing import Any

from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .repository import EVENT_V2_CHANGED

MEAL_CATEGORY = {
    "breakfast": "Petit-déjeuner",
    "lunch": "Déjeuner",
    "dinner": "Dîner",
    "snack": "Collation",
}


def _legacy_food(food: dict[str, Any]) -> dict[str, Any]:
    per100 = food.get("nutrientsPer100g") or {}
    perunit = food.get("nutrientsPerUnit") or {}
    serving = food.get("servingDefinition") or {}
    is_grams = food.get("nutritionBasis") == "per_100g"
    result = {
        "id": food["id"],
        "name": food.get("label") or "Aliment",
        "mode": "grams" if is_grams else "unit",
        "unitLabel": "g" if is_grams else (serving.get("unitLabel") or "portion"),
        "defaultCategory": "Déjeuner",
    }
    if is_grams:
        result["caloriesPer100g"] = per100.get("energyKcal")
        result["proteinsPer100g"] = per100.get("proteinG")
    else:
        result["caloriesPerUnit"] = perunit.get("energyKcal")
        result["proteinsPerUnit"] = perunit.get("proteinG")
    return result


def _profile_projection(data: dict[str, Any], profile: dict[str, Any]) -> dict[str, Any]:
    profile_id = profile["id"]
    legacy = deepcopy(profile.get("legacyRef") or {})
    goals = [
        goal for goal in data.get("goalVersionsById", {}).values()
        if goal.get("profileId") == profile_id
    ]
    goals.sort(key=lambda goal: goal.get("effectiveFromLocalDate") or "")
    active_goal = goals[-1] if goals else None
    targets = (active_goal or {}).get("targets") or {}

    foods = [
        _legacy_food(food)
        for food in data.get("foodReferencesById", {}).values()
        if food.get("ownerProfileId") in (None, profile_id) and not food.get("archivedAt")
    ]
    foods.sort(key=lambda food: (food.get("name") or "").casefold())

    meals = {
        meal_id: meal for meal_id, meal in data.get("mealsById", {}).items()
        if meal.get("profileId") == profile_id and meal.get("status") == "validated"
    }
    entries_by_date: dict[str, list[dict[str, Any]]] = {}
    for item in data.get("mealItemsById", {}).values():
        meal = meals.get(item.get("mealId"))
        if meal is None:
            continue
        local_date = meal.get("consumptionLocalDate")
        if not local_date:
            continue
        nutrients = item.get("nutritionSnapshot") or {}
        entries_by_date.setdefault(local_date, []).append({
            "id": item.get("id"),
            "name": item.get("labelSnapshot") or "Aliment",
            "calories": nutrients.get("energyKcal") or 0,
            "proteins": nutrients.get("proteinG") or 0,
            "category": MEAL_CATEGORY.get(meal.get("mealType"), "Déjeuner"),
            "quantity": item.get("quantityValue"),
            "quantityUnit": item.get("quantityUnit"),
            "createdAt": item.get("createdAt"),
        })

    return {
        "id": profile_id,
        "name": profile.get("displayName") or "Profil",
        "ha_user_id": profile.get("ownerHaUserId"),
        "goal": targets.get("energyKcal") or 2000,
        "proteinGoal": targets.get("proteinG") or 100,
        "foods": foods,
        "entriesByDate": entries_by_date,
        **legacy,
    }


async def async_sync_legacy_store(hass: HomeAssistant) -> None:
    repository = hass.data.get(f"{DOMAIN}_repository_v2")
    legacy_store = hass.data.get(DOMAIN)
    if repository is None or legacy_store is None:
        return
    data = repository.snapshot()
    profiles = {
        profile_id: _profile_projection(data, profile)
        for profile_id, profile in data.get("profilesById", {}).items()
    }
    await legacy_store.async_save({"profiles": profiles})


@callback
def async_setup(hass: HomeAssistant) -> None:
    @callback
    def _on_v2_changed(_event) -> None:
        hass.async_create_task(async_sync_legacy_store(hass))

    hass.bus.async_listen(EVENT_V2_CHANGED, _on_v2_changed)
    hass.async_create_task(async_sync_legacy_store(hass))
