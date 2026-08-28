"""J1.9 recipes and J1.10 history analysis WebSocket API."""
from __future__ import annotations

import uuid
from copy import deepcopy
from typing import Any

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .repository import RepositoryValidationError
from .store_v2 import utc_now_iso


def _repo(hass: HomeAssistant):
    return hass.data.get(f"{DOMAIN}_repository_v2")


def _profile_access(connection, profile: dict[str, Any]) -> bool:
    user = connection.user
    return bool(user and (user.is_admin or profile.get("ownerHaUserId") == user.id))


def _require_profile(connection, repository, profile_id: str) -> dict[str, Any] | None:
    profile = repository.snapshot().get("profilesById", {}).get(profile_id)
    return profile if profile and _profile_access(connection, profile) else None


@callback
def async_setup(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, websocket_get_recipes)
    websocket_api.async_register_command(hass, websocket_save_meal_as_recipe)
    websocket_api.async_register_command(hass, websocket_create_meal_from_recipe)
    websocket_api.async_register_command(hass, websocket_get_history_range)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/get_recipes", "profile_id": str})
@websocket_api.async_response
async def websocket_get_recipes(hass, connection, msg) -> None:
    repository = _repo(hass)
    if repository is None or _require_profile(connection, repository, msg["profile_id"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    recipes = [
        r for r in data["recipesById"].values()
        if r.get("profileId") == msg["profile_id"] and not r.get("archived", False)
    ]
    recipes.sort(key=lambda r: (r.get("name") or "").casefold())
    result = []
    for recipe in recipes:
        revision = data["recipeRevisionsById"].get(recipe.get("currentRevisionId"))
        ingredients = [
            i for i in data["recipeIngredientsById"].values()
            if revision and i.get("recipeRevisionId") == revision.get("id")
        ]
        ingredients.sort(key=lambda i: int(i.get("position", 0)))
        result.append({"recipe": recipe, "revision": revision, "ingredients": ingredients})
    connection.send_result(msg["id"], {"recipes": result, "storeRevision": data["meta"]["storeRevision"]})


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/save_meal_as_recipe",
    "source_meal_id": str,
    "name": str,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_save_meal_as_recipe(hass, connection, msg) -> None:
    repository = _repo(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    source = data["mealsById"].get(msg["source_meal_id"])
    if source is None or source.get("status") not in {"draft", "validated"} or _require_profile(connection, repository, source.get("profileId")) is None:
        connection.send_error(msg["id"], "unauthorized", "Meal unavailable")
        return
    name = msg["name"].strip()
    if not name:
        connection.send_error(msg["id"], "validation_error", "Recipe name is required")
        return
    items = sorted(
        (i for i in data["mealItemsById"].values() if i.get("mealId") == source["id"]),
        key=lambda i: int(i.get("position", 0)),
    )
    if not items:
        connection.send_error(msg["id"], "validation_error", "Meal is empty")
        return
    now = utc_now_iso()
    recipe_id, revision_id = str(uuid.uuid4()), str(uuid.uuid4())
    recipe = {
        "id": recipe_id, "profileId": source["profileId"], "name": name,
        "currentRevisionId": revision_id, "archived": False,
        "createdAt": now, "updatedAt": now, "revision": 1,
    }
    revision = {
        "id": revision_id, "recipeId": recipe_id, "profileId": source["profileId"],
        "name": name, "servings": 1.0, "totalsSnapshot": deepcopy(source.get("totalsSnapshot")),
        "createdFromMealId": source["id"], "createdAt": now, "updatedAt": now, "revision": 1,
    }
    data["recipesById"][recipe_id] = recipe
    data["recipeRevisionsById"][revision_id] = revision
    saved_ingredients = []
    for position, source_item in enumerate(items):
        ingredient_id = str(uuid.uuid4())
        ingredient = {
            "id": ingredient_id,
            "recipeRevisionId": revision_id,
            "position": position,
            "foodRefId": source_item.get("foodRefId"),
            "labelSnapshot": source_item.get("labelSnapshot"),
            "quantityValue": source_item.get("quantityValue"),
            "quantityUnit": source_item.get("quantityUnit"),
            "portionId": source_item.get("portionId"),
            "nutritionSnapshot": deepcopy(source_item.get("nutritionSnapshot")),
            "nutritionProvenanceId": source_item.get("nutritionProvenanceId"),
            "createdAt": now,
            "updatedAt": now,
            "revision": 1,
        }
        data["recipeIngredientsById"][ingredient_id] = ingredient
        saved_ingredients.append(ingredient)
    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "recipe", "entityId": recipe_id, "operation": "create", "profileId": source["profileId"]},
        )
        result.update({"recipe": recipe, "revision": revision, "ingredients": saved_ingredients})
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/create_meal_from_recipe",
    "recipe_id": str,
    "meal_type": str,
    "local_date": str,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_create_meal_from_recipe(hass, connection, msg) -> None:
    repository = _repo(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    recipe = data["recipesById"].get(msg["recipe_id"])
    if recipe is None or _require_profile(connection, repository, recipe.get("profileId")) is None:
        connection.send_error(msg["id"], "unauthorized", "Recipe unavailable")
        return
    revision = data["recipeRevisionsById"].get(recipe.get("currentRevisionId"))
    if revision is None:
        connection.send_error(msg["id"], "validation_error", "Recipe revision unavailable")
        return
    ingredients = sorted(
        (i for i in data["recipeIngredientsById"].values() if i.get("recipeRevisionId") == revision["id"]),
        key=lambda i: int(i.get("position", 0)),
    )
    if not ingredients:
        connection.send_error(msg["id"], "validation_error", "Recipe is empty")
        return
    now, meal_id = utc_now_iso(), str(uuid.uuid4())
    meal = {
        "id": meal_id, "profileId": recipe["profileId"], "mealType": msg["meal_type"],
        "label": recipe["name"], "status": "draft", "consumptionLocalDate": msg["local_date"],
        "consumedAtUtc": None, "timeZone": None, "datePrecision": "date_only", "totalsSnapshot": None,
        "goalVersionId": None, "origin": "recipe", "supersedesMealId": None, "supersededByMealId": None,
        "createdAt": now, "updatedAt": now, "validatedAt": None, "voidedAt": None, "revision": 1,
    }
    data["mealsById"][meal_id] = meal
    new_items = []
    for position, ingredient in enumerate(ingredients):
        item_id = str(uuid.uuid4())
        item = {
            "id": item_id, "mealId": meal_id, "position": position,
            "foodRefId": ingredient.get("foodRefId"), "recipeId": recipe["id"], "recipeRevisionId": revision["id"],
            "labelSnapshot": ingredient.get("labelSnapshot"), "quantityValue": ingredient.get("quantityValue"),
            "quantityUnit": ingredient.get("quantityUnit"), "portionId": ingredient.get("portionId"),
            "nutritionSnapshot": deepcopy(ingredient.get("nutritionSnapshot")),
            "nutritionProvenanceId": ingredient.get("nutritionProvenanceId"), "createdFromProposalId": None,
            "createdAt": now, "updatedAt": now, "revision": 1,
        }
        data["mealItemsById"][item_id] = item
        new_items.append(item)
    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "meal", "entityId": meal_id, "operation": "create_from_recipe", "profileId": recipe["profileId"]},
        )
        result.update({"meal": meal, "items": new_items})
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/get_history_range",
    "profile_id": str,
    "start_date": str,
    "end_date": str,
})
@websocket_api.async_response
async def websocket_get_history_range(hass, connection, msg) -> None:
    repository = _repo(hass)
    if repository is None or _require_profile(connection, repository, msg["profile_id"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    if msg["start_date"] > msg["end_date"]:
        connection.send_error(msg["id"], "validation_error", "Invalid date range")
        return
    data = repository.snapshot()
    days = [
        deepcopy(day) for day in data["dailyHistoryByProfileDate"].values()
        if day.get("profileId") == msg["profile_id"] and msg["start_date"] <= day.get("localDate", "") <= msg["end_date"]
    ]
    days.sort(key=lambda day: day.get("localDate", ""))
    keys = ("energyKcal", "proteinG", "carbsG", "fatG", "fiberG", "saltG")
    sums = {key: 0.0 for key in keys}
    known = {key: 0 for key in keys}
    for day in days:
        totals = day.get("totals") or {}
        for key in keys:
            value = totals.get(key)
            if value is not None:
                sums[key] += float(value)
                known[key] += 1
    averages = {key: (sums[key] / known[key] if known[key] else None) for key in keys}
    connection.send_result(msg["id"], {
        "startDate": msg["start_date"], "endDate": msg["end_date"],
        "recordedDayCount": len(days), "averages": averages, "days": days,
        "storeRevision": data["meta"]["storeRevision"],
    })