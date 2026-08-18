"""WebSocket API for Suivi Alimentation v2."""
from __future__ import annotations

from typing import Any

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .migration_v2 import async_migrate_v1_to_v2
from .repository import (
    EVENT_V2_CHANGED,
    RepositoryValidationError,
    RevisionConflict,
)


def _repository(hass: HomeAssistant):
    return hass.data.get(f"{DOMAIN}_repository_v2")


def _profile_access(connection: websocket_api.ActiveConnection, profile: dict[str, Any]) -> bool:
    user = connection.user
    if not user:
        return False
    return bool(user.is_admin or profile.get("ownerHaUserId") == user.id)


def _require_profile(
    connection: websocket_api.ActiveConnection,
    repository,
    profile_id: str,
) -> dict[str, Any] | None:
    profile = repository.snapshot().get("profilesById", {}).get(profile_id)
    if profile is None or not _profile_access(connection, profile):
        return None
    return profile


def _send_repo_error(connection, msg_id: int, err: Exception) -> None:
    if isinstance(err, RevisionConflict):
        connection.send_error(msg_id, "revision_conflict", str(err))
    elif isinstance(err, RepositoryValidationError):
        connection.send_error(msg_id, "validation_error", str(err))
    else:
        connection.send_error(msg_id, "operation_failed", str(err))


@callback
def async_setup(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, websocket_v2_status)
    websocket_api.async_register_command(hass, websocket_v2_migrate_shadow)
    websocket_api.async_register_command(hass, websocket_v2_get_data)
    websocket_api.async_register_command(hass, websocket_v2_get_my_profile)
    websocket_api.async_register_command(hass, websocket_v2_get_profile)
    websocket_api.async_register_command(hass, websocket_v2_get_day)
    websocket_api.async_register_command(hass, websocket_v2_get_recent)
    websocket_api.async_register_command(hass, websocket_v2_create_meal)
    websocket_api.async_register_command(hass, websocket_v2_add_meal_item)
    websocket_api.async_register_command(hass, websocket_v2_update_meal_item)
    websocket_api.async_register_command(hass, websocket_v2_remove_meal_item)
    websocket_api.async_register_command(hass, websocket_v2_validate_meal)
    websocket_api.async_register_command(hass, websocket_v2_subscribe)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/status"})
@websocket_api.async_response
async def websocket_v2_status(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Store v2 not initialized")
        return
    data = repository.snapshot()
    meta = data.get("meta", {})
    connection.send_result(msg["id"], {
        "schemaVersion": meta.get("schemaVersion"),
        "shadowMode": meta.get("shadowMode"),
        "storeRevision": meta.get("storeRevision"),
        "sourceFingerprint": meta.get("sourceFingerprint"),
        "lastMigrationAt": meta.get("lastMigrationAt"),
        "lastMigrationReport": meta.get("lastMigrationReport"),
        "counts": {
            "profiles": len(data.get("profilesById", {})),
            "foods": len(data.get("foodReferencesById", {})),
            "meals": len(data.get("mealsById", {})),
            "mealItems": len(data.get("mealItemsById", {})),
            "provenances": len(data.get("nutritionProvenanceById", {})),
            "days": len(data.get("dailyHistoryByProfileDate", {})),
        },
    })


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/migrate_shadow"})
@websocket_api.async_response
async def websocket_v2_migrate_shadow(hass, connection, msg) -> None:
    user = connection.user
    if not user or not user.is_admin:
        connection.send_error(msg["id"], "unauthorized", "Administrator required")
        return
    v1_store = hass.data.get(DOMAIN)
    repository = _repository(hass)
    if v1_store is None or repository is None:
        connection.send_error(msg["id"], "not_ready", "Stores not initialized")
        return
    result = await async_migrate_v1_to_v2(hass, v1_store.data, repository)
    if not result.get("ok"):
        connection.send_error(msg["id"], "migration_integrity_failed", str(result.get("error")))
        return
    connection.send_result(msg["id"], result)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/get_data"})
@websocket_api.async_response
async def websocket_v2_get_data(hass, connection, msg) -> None:
    user = connection.user
    if not user or not user.is_admin:
        connection.send_error(msg["id"], "unauthorized", "Administrator required")
        return
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Store v2 not initialized")
        return
    connection.send_result(msg["id"], repository.snapshot())


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/get_my_profile"})
@websocket_api.async_response
async def websocket_v2_get_my_profile(hass, connection, msg) -> None:
    repository = _repository(hass)
    user = connection.user
    if repository is None or user is None:
        connection.send_error(msg["id"], "not_ready", "Repository or user unavailable")
        return
    data = repository.snapshot()
    profiles = list(data["profilesById"].values())
    if user.is_admin:
        owned = [p for p in profiles if p.get("ownerHaUserId") == user.id]
        profile = owned[0] if owned else (profiles[0] if profiles else None)
    else:
        profile = next((p for p in profiles if p.get("ownerHaUserId") == user.id), None)
    if profile is None:
        connection.send_error(msg["id"], "not_found", "No accessible profile")
        return
    connection.send_result(msg["id"], {"profile": profile, "isAdmin": user.is_admin})


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/get_profile", "profile_id": str})
@websocket_api.async_response
async def websocket_v2_get_profile(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    profile = _require_profile(connection, repository, msg["profile_id"])
    if profile is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    goals = [g for g in data["goalVersionsById"].values() if g.get("profileId") == profile["id"]]
    foods = [f for f in data["foodReferencesById"].values() if f.get("ownerProfileId") in (None, profile["id"])]
    connection.send_result(msg["id"], {
        "profile": profile,
        "goalVersions": goals,
        "foods": foods,
        "storeRevision": data["meta"]["storeRevision"],
    })


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/get_day", "profile_id": str, "local_date": str
})
@websocket_api.async_response
async def websocket_v2_get_day(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None or _require_profile(connection, repository, msg["profile_id"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    key = f"{msg['profile_id']}|{msg['local_date']}"
    history = data["dailyHistoryByProfileDate"].get(key)
    history_meal_ids = history.get("mealIds", []) if history else []
    draft_ids = [m["id"] for m in data["mealsById"].values() if m.get("profileId") == msg["profile_id"] and m.get("consumptionLocalDate") == msg["local_date"] and m.get("status") == "draft"]
    meal_ids = list(dict.fromkeys(history_meal_ids + draft_ids))
    meals = [data["mealsById"][mid] for mid in meal_ids if mid in data["mealsById"]]
    items = [i for i in data["mealItemsById"].values() if i.get("mealId") in meal_ids]
    connection.send_result(msg["id"], {
        "history": history,
        "meals": meals,
        "items": items,
        "storeRevision": data["meta"]["storeRevision"],
    })

@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/get_recent", "profile_id": str
})
@websocket_api.async_response
async def websocket_v2_get_recent(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None or _require_profile(connection, repository, msg["profile_id"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    data = repository.snapshot()
    profile_id = msg["profile_id"]
    validated = [
        m for m in data["mealsById"].values()
        if m.get("profileId") == profile_id and m.get("status") == "validated"
    ]
    validated.sort(key=lambda m: (m.get("consumptionLocalDate") or "", m.get("createdAt") or ""), reverse=True)
    seen = set()
    recent = []
    for meal in validated:
        for item in data["mealItemsById"].values():
            if item.get("mealId") != meal["id"]:
                continue
            key = item.get("foodRefId") or item.get("recipeRevisionId") or item.get("labelSnapshot")
            if not key or key in seen:
                continue
            seen.add(key)
            recent.append({
                "label": item.get("labelSnapshot"),
                "foodRefId": item.get("foodRefId"),
                "recipeId": item.get("recipeId"),
                "recipeRevisionId": item.get("recipeRevisionId"),
                "lastUsedLocalDate": meal.get("consumptionLocalDate"),
            })
            if len(recent) >= 20:
                break
        if len(recent) >= 20:
            break
    connection.send_result(msg["id"], {"items": recent})


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/create_meal",
    "profile_id": str,
    "meal_type": str,
    "consumption_local_date": str,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_v2_create_meal(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None or _require_profile(connection, repository, msg["profile_id"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    try:
        result = await repository.async_create_meal(
            profile_id=msg["profile_id"],
            meal_type=msg["meal_type"],
            consumption_local_date=msg["consumption_local_date"],
            operation_id=msg["operation_id"],
            origin=msg.get("origin", "manual"),
            label=msg.get("label"),
            consumed_at_utc=msg.get("consumed_at_utc"),
            time_zone=msg.get("time_zone"),
        )
        connection.send_result(msg["id"], result)
    except Exception as err:
        _send_repo_error(connection, msg["id"], err)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/add_meal_item",
    "meal_id": str,
    "item": dict,
    "operation_id": str,
    "expected_meal_revision": int,
})
@websocket_api.async_response
async def websocket_v2_add_meal_item(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    meal = data["mealsById"].get(msg["meal_id"])
    if meal is None or _require_profile(connection, repository, meal["profileId"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Meal unavailable")
        return
    try:
        result = await repository.async_add_meal_item(
            meal_id=msg["meal_id"],
            item=msg["item"],
            operation_id=msg["operation_id"],
            expected_meal_revision=msg["expected_meal_revision"],
        )
        connection.send_result(msg["id"], result)
    except Exception as err:
        _send_repo_error(connection, msg["id"], err)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/update_meal_item",
    "item_id": str,
    "patch": dict,
    "operation_id": str,
    "expected_item_revision": int,
    "expected_meal_revision": int,
})
@websocket_api.async_response
async def websocket_v2_update_meal_item(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    item = data["mealItemsById"].get(msg["item_id"])
    meal = data["mealsById"].get(item.get("mealId")) if item else None
    if meal is None or _require_profile(connection, repository, meal["profileId"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Meal unavailable")
        return
    try:
        result = await repository.async_update_meal_item(
            item_id=msg["item_id"],
            patch=msg["patch"],
            operation_id=msg["operation_id"],
            expected_item_revision=msg["expected_item_revision"],
            expected_meal_revision=msg["expected_meal_revision"],
        )
        connection.send_result(msg["id"], result)
    except Exception as err:
        _send_repo_error(connection, msg["id"], err)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/remove_meal_item",
    "item_id": str,
    "operation_id": str,
    "expected_item_revision": int,
    "expected_meal_revision": int,
})
@websocket_api.async_response
async def websocket_v2_remove_meal_item(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    item = data["mealItemsById"].get(msg["item_id"])
    meal = data["mealsById"].get(item.get("mealId")) if item else None
    if meal is None or _require_profile(connection, repository, meal["profileId"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Meal unavailable")
        return
    try:
        result = await repository.async_remove_meal_item(
            item_id=msg["item_id"],
            operation_id=msg["operation_id"],
            expected_item_revision=msg["expected_item_revision"],
            expected_meal_revision=msg["expected_meal_revision"],
        )
        connection.send_result(msg["id"], result)
    except Exception as err:
        _send_repo_error(connection, msg["id"], err)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/validate_meal",
    "meal_id": str,
    "operation_id": str,
    "expected_meal_revision": int,
})
@websocket_api.async_response
async def websocket_v2_validate_meal(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    meal = data["mealsById"].get(msg["meal_id"])
    if meal is None or _require_profile(connection, repository, meal["profileId"]) is None:
        connection.send_error(msg["id"], "unauthorized", "Meal unavailable")
        return
    try:
        result = await repository.async_validate_meal(
            meal_id=msg["meal_id"],
            operation_id=msg["operation_id"],
            expected_meal_revision=msg["expected_meal_revision"],
        )
        connection.send_result(msg["id"], result)
    except Exception as err:
        _send_repo_error(connection, msg["id"], err)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/subscribe"})
@callback
def websocket_v2_subscribe(hass, connection, msg) -> None:
    user = connection.user
    if user is None:
        connection.send_error(msg["id"], "unauthorized", "User unavailable")
        return

    @callback
    def forward(event) -> None:
        profile_id = event.data.get("profileId")
        if user.is_admin:
            connection.send_event(msg["id"], event.data)
            return
        if profile_id is None:
            return
        repository = _repository(hass)
        profile = repository.snapshot()["profilesById"].get(profile_id) if repository else None
        if profile and profile.get("ownerHaUserId") == user.id:
            connection.send_event(msg["id"], event.data)

    connection.subscriptions[msg["id"]] = hass.bus.async_listen(EVENT_V2_CHANGED, forward)
    connection.send_result(msg["id"])
