"""Reusable meal templates WebSocket API for Suivi Alimentation v2."""
from __future__ import annotations

import uuid
from copy import deepcopy
from typing import Any

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
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
    websocket_api.async_register_command(hass, websocket_get_meal_templates)
    websocket_api.async_register_command(hass, websocket_save_meal_as_template)
    websocket_api.async_register_command(hass, websocket_create_meal_from_template)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/get_meal_templates", "profile_id": str})
@websocket_api.async_response
async def websocket_get_meal_templates(hass, connection, msg) -> None:
    repository = _repo(hass)
    if repository is None or _require_profile(connection, repository, msg["profile_id"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    templates = [
        template for template in data.get("mealTemplatesById", {}).values()
        if template.get("profileId") == msg["profile_id"] and not template.get("archived", False)
    ]
    templates.sort(key=lambda template: (template.get("name") or "").casefold())
    result = []
    for template in templates:
        items = [
            item for item in data.get("mealTemplateItemsById", {}).values()
            if item.get("templateId") == template["id"]
        ]
        items.sort(key=lambda item: int(item.get("position", 0)))
        result.append({"template": template, "items": items})
    connection.send_result(msg["id"], {"templates": result, "storeRevision": data["meta"]["storeRevision"]})


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/save_meal_as_template",
    "source_meal_id": str,
    "name": str,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_save_meal_as_template(hass, connection, msg) -> None:
    repository = _repo(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    source = data["mealsById"].get(msg["source_meal_id"])
    if source is None or source.get("status") != "validated" or _require_profile(connection, repository, source.get("profileId")) is None:
        connection.send_error(msg["id"], "unauthorized", "Validated meal unavailable")
        return
    name = msg["name"].strip()
    if not name:
        connection.send_error(msg["id"], "validation_error", "Meal template name is required")
        return
    source_items = sorted(
        (item for item in data["mealItemsById"].values() if item.get("mealId") == source["id"]),
        key=lambda item: int(item.get("position", 0)),
    )
    if not source_items:
        connection.send_error(msg["id"], "validation_error", "Meal is empty")
        return

    now = utc_now_iso()
    template_id = str(uuid.uuid4())
    template = {
        "id": template_id,
        "profileId": source["profileId"],
        "name": name,
        "defaultMealType": source.get("mealType"),
        "archived": False,
        "createdFromMealId": source["id"],
        "createdAt": now,
        "updatedAt": now,
        "revision": 1,
    }
    data["mealTemplatesById"][template_id] = template
    saved_items = []
    for position, source_item in enumerate(source_items):
        item_id = str(uuid.uuid4())
        item = {
            "id": item_id,
            "templateId": template_id,
            "position": position,
            "kind": source_item.get("kind", "food"),
            "foodRefId": source_item.get("foodRefId"),
            "recipeId": source_item.get("recipeId"),
            "recipeRevisionId": source_item.get("recipeRevisionId"),
            "labelSnapshot": source_item.get("labelSnapshot"),
            "quantityValue": source_item.get("quantityValue"),
            "quantityUnit": source_item.get("quantityUnit"),
            "gramsEquivalent": source_item.get("gramsEquivalent"),
            "portionId": source_item.get("portionId"),
            "portionLabelSnapshot": source_item.get("portionLabelSnapshot"),
            "nutritionSnapshot": deepcopy(source_item.get("nutritionSnapshot")),
            "provenanceId": source_item.get("provenanceId") or source_item.get("nutritionProvenanceId"),
            "createdAt": now,
            "updatedAt": now,
            "revision": 1,
        }
        data["mealTemplateItemsById"][item_id] = item
        saved_items.append(item)

    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={
                "entityType": "mealTemplate",
                "entityId": template_id,
                "operation": "create",
                "profileId": source["profileId"],
            },
        )
        result.update({"template": template, "items": saved_items})
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/create_meal_from_template",
    "template_id": str,
    "meal_type": str,
    "local_date": str,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_create_meal_from_template(hass, connection, msg) -> None:
    repository = _repo(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    template = data.get("mealTemplatesById", {}).get(msg["template_id"])
    if template is None or _require_profile(connection, repository, template.get("profileId")) is None:
        connection.send_error(msg["id"], "unauthorized", "Meal template unavailable")
        return
    template_items = sorted(
        (item for item in data.get("mealTemplateItemsById", {}).values() if item.get("templateId") == template["id"]),
        key=lambda item: int(item.get("position", 0)),
    )
    if not template_items:
        connection.send_error(msg["id"], "validation_error", "Meal template is empty")
        return

    now = utc_now_iso()
    meal_id = str(uuid.uuid4())
    meal = {
        "id": meal_id,
        "profileId": template["profileId"],
        "mealType": msg["meal_type"],
        "label": template["name"],
        "status": "draft",
        "consumptionLocalDate": msg["local_date"],
        "consumedAtUtc": None,
        "timeZone": None,
        "datePrecision": "date_only",
        "totalsSnapshot": None,
        "goalVersionId": None,
        "origin": "meal_template",
        "supersedesMealId": None,
        "supersededByMealId": None,
        "createdAt": now,
        "updatedAt": now,
        "validatedAt": None,
        "voidedAt": None,
        "revision": 1,
    }
    data["mealsById"][meal_id] = meal
    new_items = []
    for position, template_item in enumerate(template_items):
        item_id = str(uuid.uuid4())
        item = {
            "id": item_id,
            "mealId": meal_id,
            "kind": template_item.get("kind", "food"),
            "foodRefId": template_item.get("foodRefId"),
            "recipeId": template_item.get("recipeId"),
            "recipeRevisionId": template_item.get("recipeRevisionId"),
            "labelSnapshot": template_item.get("labelSnapshot"),
            "quantityValue": template_item.get("quantityValue"),
            "quantityUnit": template_item.get("quantityUnit"),
            "gramsEquivalent": template_item.get("gramsEquivalent"),
            "nutritionSnapshot": deepcopy(template_item.get("nutritionSnapshot")),
            "provenanceId": template_item.get("provenanceId"),
            "createdFromProposalId": None,
            "portionId": template_item.get("portionId"),
            "portionLabelSnapshot": template_item.get("portionLabelSnapshot"),
            "position": position,
            "revision": 1,
            "createdAt": now,
            "updatedAt": now,
        }
        data["mealItemsById"][item_id] = item
        new_items.append(item)

    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={
                "entityType": "meal",
                "entityId": meal_id,
                "operation": "create_from_meal_template",
                "profileId": template["profileId"],
            },
        )
        result.update({"meal": meal, "items": new_items})
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))
