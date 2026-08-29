"""Library management WebSocket API for Suivi Alimentation v2."""
from __future__ import annotations

import uuid
from copy import deepcopy
from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .store_v2 import utc_now_iso


def _repo(hass: HomeAssistant):
    return hass.data.get(f"{DOMAIN}_repository_v2")


def _nutrition(hass: HomeAssistant):
    return hass.data.get(f"{DOMAIN}_nutrition_v2")


def _profile_access(connection, profile: dict[str, Any]) -> bool:
    user = connection.user
    return bool(user and (user.is_admin or profile.get("ownerHaUserId") == user.id))


def _require_profile(connection, repository, profile_id: str) -> dict[str, Any] | None:
    profile = repository.snapshot().get("profilesById", {}).get(profile_id)
    return profile if profile and _profile_access(connection, profile) else None


def _sum_snapshots(snapshots: list[dict[str, Any]]) -> dict[str, float | None]:
    keys = ("energyKcal", "proteinG", "carbsG", "fatG", "fiberG", "saltG")
    result: dict[str, float | None] = {}
    for key in keys:
        values = [float(snapshot[key]) for snapshot in snapshots if snapshot.get(key) is not None]
        result[key] = sum(values) if values else None
    return result


def _build_items(hass: HomeAssistant, data: dict[str, Any], profile_id: str, specs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    nutrition = _nutrition(hass)
    if nutrition is None:
        raise ValueError("Nutrition service unavailable")
    built = []
    for position, spec in enumerate(specs):
        food_id = str(spec.get("food_ref_id") or "")
        food = data["foodReferencesById"].get(food_id)
        if food is None or food.get("ownerProfileId") != profile_id:
            raise ValueError("Food unavailable")
        quantity = float(spec.get("quantity_value") or 0)
        if quantity <= 0:
            raise ValueError("Quantity must be greater than zero")
        unit = str(spec.get("quantity_unit") or "g")
        portion_id = spec.get("portion_id")
        snapshot, grams, method, portion = nutrition.build_consumed_snapshot(food, quantity, unit, portion_id)
        provenance = nutrition.build_calculation_provenance(food, method, quantity, unit, grams, portion)
        data["nutritionProvenanceById"][provenance["id"]] = provenance
        built.append({
            "position": position,
            "kind": "food",
            "foodRefId": food_id,
            "recipeId": None,
            "recipeRevisionId": None,
            "labelSnapshot": food.get("label") or "Aliment",
            "quantityValue": quantity,
            "quantityUnit": portion.get("unitLabel") if portion else unit,
            "gramsEquivalent": grams,
            "portionId": portion.get("id") if portion else None,
            "portionLabelSnapshot": portion.get("label") if portion else None,
            "nutritionSnapshot": deepcopy(snapshot),
            "provenanceId": provenance["id"],
        })
    return built


def _recipe_payload(data: dict[str, Any], recipe: dict[str, Any]) -> dict[str, Any]:
    revision = data["recipeRevisionsById"].get(recipe.get("currentRevisionId"))
    ingredients = [
        item for item in data["recipeIngredientsById"].values()
        if revision and item.get("recipeRevisionId") == revision.get("id")
    ]
    ingredients.sort(key=lambda item: int(item.get("position", 0)))
    return {"recipe": recipe, "revision": revision, "ingredients": ingredients}


def _template_payload(data: dict[str, Any], template: dict[str, Any]) -> dict[str, Any]:
    items = [item for item in data["mealTemplateItemsById"].values() if item.get("templateId") == template["id"]]
    items.sort(key=lambda item: int(item.get("position", 0)))
    return {"template": template, "items": items}


@callback
def async_setup(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, websocket_library_get)
    websocket_api.async_register_command(hass, websocket_library_update_food)
    websocket_api.async_register_command(hass, websocket_library_delete_food)
    websocket_api.async_register_command(hass, websocket_library_update_recipe)
    websocket_api.async_register_command(hass, websocket_library_delete_recipe)
    websocket_api.async_register_command(hass, websocket_library_update_template)
    websocket_api.async_register_command(hass, websocket_library_delete_template)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/library/get", "profile_id": str})
@websocket_api.async_response
async def websocket_library_get(hass, connection, msg) -> None:
    repository = _repo(hass)
    profile_id = msg["profile_id"]
    if repository is None or _require_profile(connection, repository, profile_id) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    foods = [
        food for food in data["foodReferencesById"].values()
        if food.get("ownerProfileId") == profile_id and not food.get("archivedAt")
    ]
    foods.sort(key=lambda food: (food.get("label") or "").casefold())
    recipes = [
        recipe for recipe in data["recipesById"].values()
        if recipe.get("profileId") == profile_id and not recipe.get("archived", False)
    ]
    recipes.sort(key=lambda recipe: (recipe.get("name") or "").casefold())
    templates = [
        template for template in data.get("mealTemplatesById", {}).values()
        if template.get("profileId") == profile_id and not template.get("archived", False)
    ]
    templates.sort(key=lambda template: (template.get("name") or "").casefold())
    connection.send_result(msg["id"], {
        "foods": foods,
        "recipes": [_recipe_payload(data, recipe) for recipe in recipes],
        "templates": [_template_payload(data, template) for template in templates],
        "storeRevision": data["meta"]["storeRevision"],
    })


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/library/update_food",
    "profile_id": str,
    "food_id": str,
    "label": str,
    "nutrients_per_100g": dict,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_library_update_food(hass, connection, msg) -> None:
    repository = _repo(hass)
    profile_id = msg["profile_id"]
    if repository is None or _require_profile(connection, repository, profile_id) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    food = data["foodReferencesById"].get(msg["food_id"])
    if food is None or food.get("ownerProfileId") != profile_id or food.get("archivedAt"):
        connection.send_error(msg["id"], "not_found", "Food unavailable")
        return
    label = msg["label"].strip()
    if not label:
        connection.send_error(msg["id"], "validation_error", "Food name is required")
        return
    now = utc_now_iso()
    food["label"] = label
    food["nutritionBasis"] = "per_100g"
    food["nutrientsPer100g"] = deepcopy(msg["nutrients_per_100g"])
    food["updatedAt"] = now
    food["revision"] = int(food.get("revision", 0)) + 1
    food["editedManuallyAt"] = now
    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "foodReference", "entityId": food["id"], "operation": "update", "profileId": profile_id},
        )
        result["food"] = food
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/library/delete_food", "profile_id": str, "food_id": str, "operation_id": str})
@websocket_api.async_response
async def websocket_library_delete_food(hass, connection, msg) -> None:
    repository = _repo(hass)
    profile_id = msg["profile_id"]
    if repository is None or _require_profile(connection, repository, profile_id) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    food = data["foodReferencesById"].get(msg["food_id"])
    if food is None or food.get("ownerProfileId") != profile_id:
        connection.send_error(msg["id"], "not_found", "Food unavailable")
        return
    now = utc_now_iso()
    food["archivedAt"] = now
    food["updatedAt"] = now
    food["revision"] = int(food.get("revision", 0)) + 1
    for favorite_id, favorite in list(data["favoritesById"].items()):
        if favorite.get("profileId") == profile_id and favorite.get("foodRefId") == food["id"]:
            data["favoritesById"].pop(favorite_id, None)
    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "foodReference", "entityId": food["id"], "operation": "archive", "profileId": profile_id},
        )
        result["food"] = food
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/library/update_recipe", "profile_id": str, "recipe_id": str, "name": str, "items": list, "operation_id": str})
@websocket_api.async_response
async def websocket_library_update_recipe(hass, connection, msg) -> None:
    repository = _repo(hass)
    profile_id = msg["profile_id"]
    if repository is None or _require_profile(connection, repository, profile_id) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    recipe = data["recipesById"].get(msg["recipe_id"])
    if recipe is None or recipe.get("profileId") != profile_id or recipe.get("archived", False):
        connection.send_error(msg["id"], "not_found", "Recipe unavailable")
        return
    name = msg["name"].strip()
    if not name or not msg["items"]:
        connection.send_error(msg["id"], "validation_error", "Recipe name and composition are required")
        return
    try:
        built = _build_items(hass, data, profile_id, msg["items"])
        now = utc_now_iso()
        revision_id = str(uuid.uuid4())
        revision = {
            "id": revision_id,
            "recipeId": recipe["id"],
            "profileId": profile_id,
            "name": name,
            "servings": 1.0,
            "totalsSnapshot": _sum_snapshots([item["nutritionSnapshot"] for item in built]),
            "createdFromMealId": None,
            "createdAt": now,
            "updatedAt": now,
            "revision": int(recipe.get("revision", 0)) + 1,
        }
        data["recipeRevisionsById"][revision_id] = revision
        ingredients = []
        for source in built:
            ingredient_id = str(uuid.uuid4())
            ingredient = {
                "id": ingredient_id,
                "recipeRevisionId": revision_id,
                **source,
                "nutritionProvenanceId": source.get("provenanceId"),
                "createdAt": now,
                "updatedAt": now,
                "revision": 1,
            }
            data["recipeIngredientsById"][ingredient_id] = ingredient
            ingredients.append(ingredient)
        recipe["name"] = name
        recipe["currentRevisionId"] = revision_id
        recipe["updatedAt"] = now
        recipe["revision"] = int(recipe.get("revision", 0)) + 1
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "recipe", "entityId": recipe["id"], "operation": "update", "profileId": profile_id},
        )
        result.update({"recipe": recipe, "revision": revision, "ingredients": ingredients})
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/library/delete_recipe", "profile_id": str, "recipe_id": str, "operation_id": str})
@websocket_api.async_response
async def websocket_library_delete_recipe(hass, connection, msg) -> None:
    repository = _repo(hass)
    profile_id = msg["profile_id"]
    if repository is None or _require_profile(connection, repository, profile_id) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    recipe = data["recipesById"].get(msg["recipe_id"])
    if recipe is None or recipe.get("profileId") != profile_id:
        connection.send_error(msg["id"], "not_found", "Recipe unavailable")
        return
    recipe["archived"] = True
    recipe["updatedAt"] = utc_now_iso()
    recipe["revision"] = int(recipe.get("revision", 0)) + 1
    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "recipe", "entityId": recipe["id"], "operation": "archive", "profileId": profile_id},
        )
        result["recipe"] = recipe
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/library/update_meal_template",
    "profile_id": str,
    "template_id": str,
    "name": str,
    "items": list,
    vol.Optional("default_meal_type"): str,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_library_update_template(hass, connection, msg) -> None:
    repository = _repo(hass)
    profile_id = msg["profile_id"]
    if repository is None or _require_profile(connection, repository, profile_id) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    template = data["mealTemplatesById"].get(msg["template_id"])
    if template is None or template.get("profileId") != profile_id or template.get("archived", False):
        connection.send_error(msg["id"], "not_found", "Meal template unavailable")
        return
    name = msg["name"].strip()
    if not name or not msg["items"]:
        connection.send_error(msg["id"], "validation_error", "Meal template name and composition are required")
        return
    try:
        built = _build_items(hass, data, profile_id, msg["items"])
        now = utc_now_iso()
        for item_id, item in list(data["mealTemplateItemsById"].items()):
            if item.get("templateId") == template["id"]:
                data["mealTemplateItemsById"].pop(item_id, None)
        saved = []
        for source in built:
            item_id = str(uuid.uuid4())
            item = {"id": item_id, "templateId": template["id"], **source, "createdAt": now, "updatedAt": now, "revision": 1}
            data["mealTemplateItemsById"][item_id] = item
            saved.append(item)
        template["name"] = name
        if "default_meal_type" in msg:
            template["defaultMealType"] = msg.get("default_meal_type")
        template["updatedAt"] = now
        template["revision"] = int(template.get("revision", 0)) + 1
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "mealTemplate", "entityId": template["id"], "operation": "update", "profileId": profile_id},
        )
        result.update({"template": template, "items": saved})
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/library/delete_meal_template", "profile_id": str, "template_id": str, "operation_id": str})
@websocket_api.async_response
async def websocket_library_delete_template(hass, connection, msg) -> None:
    repository = _repo(hass)
    profile_id = msg["profile_id"]
    if repository is None or _require_profile(connection, repository, profile_id) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    template = data["mealTemplatesById"].get(msg["template_id"])
    if template is None or template.get("profileId") != profile_id:
        connection.send_error(msg["id"], "not_found", "Meal template unavailable")
        return
    template["archived"] = True
    template["updatedAt"] = utc_now_iso()
    template["revision"] = int(template.get("revision", 0)) + 1
    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={"entityType": "mealTemplate", "entityId": template["id"], "operation": "archive", "profileId": profile_id},
        )
        result["template"] = template
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))
