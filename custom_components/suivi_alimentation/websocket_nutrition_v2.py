"""Nutrition WebSocket API for Suivi Alimentation v2."""
from __future__ import annotations

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .repository import RevisionConflict


def _services(hass: HomeAssistant):
    return (
        hass.data.get(f"{DOMAIN}_repository_v2"),
        hass.data.get(f"{DOMAIN}_nutrition_v2"),
        hass.data.get(f"{DOMAIN}_nutrition_commands_v2"),
    )


def _personal_foods(hass: HomeAssistant, profile_id: str) -> list[dict]:
    store_v1 = hass.data.get(DOMAIN)
    profile = store_v1.get_profile(profile_id) if store_v1 else None
    return list((profile or {}).get("foods") or [])


def _existing_personal_by_legacy_id(repository, profile_id: str) -> dict[str, str]:
    result = {}
    for food_id, food in repository.snapshot().get("foodReferencesById", {}).items():
        if food.get("ownerProfileId") != profile_id:
            continue
        legacy_id = str((food.get("legacyRef") or {}).get("foodId") or "").strip()
        if legacy_id:
            result[legacy_id] = food_id
    return result


def _profile_access(connection, repository, profile_id: str) -> bool:
    user = connection.user
    profile = repository.snapshot().get("profilesById", {}).get(profile_id)
    return bool(user and profile and (user.is_admin or profile.get("ownerHaUserId") == user.id))


def _error(connection, msg_id: int, err: Exception) -> None:
    code = "revision_conflict" if isinstance(err, RevisionConflict) else "nutrition_error"
    connection.send_error(msg_id, code, str(err))


@callback
def async_setup(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, search_ciqual)
    websocket_api.async_register_command(hass, search_personal_foods)
    websocket_api.async_register_command(hass, get_off_product)
    websocket_api.async_register_command(hass, import_ciqual_food)
    websocket_api.async_register_command(hass, import_personal_food)
    websocket_api.async_register_command(hass, import_off_food)
    websocket_api.async_register_command(hass, add_food_to_meal)
    websocket_api.async_register_command(hass, nutrition_status)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/nutrition_status"})
@websocket_api.async_response
async def nutrition_status(hass, connection, msg) -> None:
    repository, nutrition, commands = _services(hass)
    if not all((repository, nutrition, commands)):
        connection.send_error(msg["id"], "not_ready", "Nutrition v2 not initialized")
        return
    connection.send_result(msg["id"], {
        "ciqualVersion": "2025-11-03",
        "ciqualDatasetDoi": "10.57745/RDMHWY",
        "openFoodFactsApi": "v3",
        "portionCatalogVersion": "fdc-sr-legacy-2019.04-j1.2.1",
        "normalizedNutrients": ["energyKcal", "proteinG", "carbsG", "fatG", "fiberG", "saltG"],
    })


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/search_ciqual", "profile_id": str, "query": str, vol.Optional("limit", default=20): int})
@websocket_api.async_response
async def search_ciqual(hass, connection, msg) -> None:
    repository, nutrition, _ = _services(hass)
    if repository is None or nutrition is None or not _profile_access(connection, repository, msg["profile_id"]):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        items = await nutrition.async_search_ciqual(msg["query"], int(msg.get("limit", 20)))
        connection.send_result(msg["id"], {"items": items, "sourceVersion": "2025-11-03"})
    except Exception as err:
        _error(connection, msg["id"], err)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/search_personal_foods",
    "profile_id": str,
    "query": str,
    vol.Optional("limit", default=20): int,
})
@websocket_api.async_response
async def search_personal_foods(hass, connection, msg) -> None:
    repository, nutrition, _ = _services(hass)
    profile_id = msg["profile_id"]
    if repository is None or nutrition is None or not _profile_access(connection, repository, profile_id):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        items = nutrition.search_personal_foods(
            _personal_foods(hass, profile_id),
            msg["query"],
            _existing_personal_by_legacy_id(repository, profile_id),
            int(msg.get("limit", 20)),
        )
        connection.send_result(msg["id"], {"items": items})
    except Exception as err:
        _error(connection, msg["id"], err)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/get_off_product", "profile_id": str, "barcode": str})
@websocket_api.async_response
async def get_off_product(hass, connection, msg) -> None:
    repository, nutrition, _ = _services(hass)
    if repository is None or nutrition is None or not _profile_access(connection, repository, msg["profile_id"]):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        product = await nutrition.async_get_off(msg["barcode"])
        if product is None:
            connection.send_error(msg["id"], "not_found", "Product not found")
            return
        connection.send_result(msg["id"], product)
    except Exception as err:
        _error(connection, msg["id"], err)


async def _import_source(hass, connection, msg, source) -> None:
    repository, _, commands = _services(hass)
    if repository is None or commands is None or not _profile_access(connection, repository, msg["profile_id"]):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        result = await commands.async_import_source(msg["profile_id"], source, msg["operation_id"])
        connection.send_result(msg["id"], result)
    except Exception as err:
        _error(connection, msg["id"], err)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/import_ciqual_food", "profile_id": str, "ciqual_code": str, "operation_id": str})
@websocket_api.async_response
async def import_ciqual_food(hass, connection, msg) -> None:
    repository, nutrition, _ = _services(hass)
    if repository is None or nutrition is None or not _profile_access(connection, repository, msg["profile_id"]):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        source = await nutrition.async_get_ciqual(msg["ciqual_code"])
        if source is None:
            connection.send_error(msg["id"], "not_found", "Ciqual food not found")
            return
        await _import_source(hass, connection, msg, source)
    except Exception as err:
        _error(connection, msg["id"], err)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/import_personal_food",
    "profile_id": str,
    "legacy_food_id": str,
    "operation_id": str,
})
@websocket_api.async_response
async def import_personal_food(hass, connection, msg) -> None:
    repository, nutrition, _ = _services(hass)
    profile_id = msg["profile_id"]
    if repository is None or nutrition is None or not _profile_access(connection, repository, profile_id):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        legacy_id = msg["legacy_food_id"].strip()
        legacy_food = next(
            (food for food in _personal_foods(hass, profile_id) if str(food.get("id") or "") == legacy_id),
            None,
        )
        if legacy_food is None:
            connection.send_error(msg["id"], "not_found", "Personal food not found")
            return
        source = nutrition.personal_source(legacy_food)
        source["legacyRef"] = {"profileId": profile_id, "foodId": legacy_id}
        existing_id = _existing_personal_by_legacy_id(repository, profile_id).get(legacy_id)
        if existing_id:
            source["foodId"] = existing_id
        await _import_source(hass, connection, msg, source)
    except Exception as err:
        _error(connection, msg["id"], err)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/import_off_food", "profile_id": str, "barcode": str, "operation_id": str})
@websocket_api.async_response
async def import_off_food(hass, connection, msg) -> None:
    repository, nutrition, _ = _services(hass)
    if repository is None or nutrition is None or not _profile_access(connection, repository, msg["profile_id"]):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        source = await nutrition.async_get_off(msg["barcode"])
        if source is None:
            connection.send_error(msg["id"], "not_found", "Product not found")
            return
        await _import_source(hass, connection, msg, source)
    except Exception as err:
        _error(connection, msg["id"], err)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/add_food_to_meal", "meal_id": str, "food_id": str,
    "quantity_value": float, "quantity_unit": str, "operation_id": str,
    "expected_meal_revision": int,
    vol.Optional("portion_id"): str,
})
@websocket_api.async_response
async def add_food_to_meal(hass, connection, msg) -> None:
    repository, _, commands = _services(hass)
    if repository is None or commands is None:
        connection.send_error(msg["id"], "not_ready", "Nutrition v2 not initialized")
        return
    meal = repository.snapshot().get("mealsById", {}).get(msg["meal_id"])
    if not meal or not _profile_access(connection, repository, meal["profileId"]):
        connection.send_error(msg["id"], "unauthorized", "Meal unavailable")
        return
    try:
        result = await commands.async_add_food_to_meal(
            meal_id=msg["meal_id"], food_id=msg["food_id"],
            quantity_value=msg["quantity_value"], quantity_unit=msg["quantity_unit"],
            operation_id=msg["operation_id"],
            expected_meal_revision=msg["expected_meal_revision"],
            portion_id=msg.get("portion_id"),
        )
        connection.send_result(msg["id"], result)
    except Exception as err:
        _error(connection, msg["id"], err)
