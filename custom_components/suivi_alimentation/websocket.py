"""WebSocket API for Suivi Alimentation - v0.21"""
from __future__ import annotations

import logging
import uuid
from typing import Any

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .store import calculate_nutrition_goals

_LOGGER = logging.getLogger(__name__)


@callback
def async_setup(hass: HomeAssistant) -> None:
    """Set up WebSocket API."""
    websocket_api.async_register_command(hass, websocket_get_data)
    websocket_api.async_register_command(hass, websocket_save_data)
    websocket_api.async_register_command(hass, websocket_get_profiles)
    websocket_api.async_register_command(hass, websocket_get_profile_data)
    websocket_api.async_register_command(hass, websocket_save_profile_data)
    websocket_api.async_register_command(hass, websocket_get_profile_for_user)
    websocket_api.async_register_command(hass, websocket_add_profile)
    websocket_api.async_register_command(hass, websocket_update_profile)
    websocket_api.async_register_command(hass, websocket_delete_profile)
    websocket_api.async_register_command(hass, websocket_calculate_goals)


@websocket_api.websocket_command({"type": f"{DOMAIN}/get_data"})
@websocket_api.async_response
async def websocket_get_data(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    connection.send_result(msg["id"], store.data)


@websocket_api.websocket_command({"type": f"{DOMAIN}/save_data", "data": dict})
@websocket_api.async_response
async def websocket_save_data(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    try:
        await store.async_save(msg["data"])
        connection.send_result(msg["id"], {"ok": True})
    except Exception as err:
        _LOGGER.error("Suivi Alimentation: erreur sauvegarde: %s", err)
        connection.send_error(msg["id"], "save_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/get_profiles"})
@websocket_api.async_response
async def websocket_get_profiles(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    profiles = store.get_profiles()
    summary = [{
        "id": pid, "name": p.get("name", "Profil"), "ha_user_id": p.get("ha_user_id"),
        "goal": p.get("goal", 2000), "proteinGoal": p.get("proteinGoal", 100),
        "bmi": p.get("bmi"), "idealWeight": p.get("idealWeight"), "sex": p.get("sex"),
        "weight": p.get("weight"), "height": p.get("height"), "age": p.get("age"),
        "activity": p.get("activity"), "targetWeight": p.get("targetWeight"),
    } for pid, p in profiles.items()]
    connection.send_result(msg["id"], {"profiles": summary})


@websocket_api.websocket_command({"type": f"{DOMAIN}/get_profile_data", "profile_id": str})
@websocket_api.async_response
async def websocket_get_profile_data(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    profile = store.get_profile(msg["profile_id"])
    if profile is None:
        connection.send_error(msg["id"], "not_found", "Profile not found")
        return
    connection.send_result(msg["id"], profile)


@websocket_api.websocket_command({"type": f"{DOMAIN}/get_profile_for_user"})
@websocket_api.async_response
async def websocket_get_profile_for_user(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    user = connection.user
    ha_user_id = user.id if user else None
    profile = store.get_profile_for_user(ha_user_id) if ha_user_id else None
    if profile is None:
        connection.send_error(msg["id"], "not_found", "No profile found")
        return
    connection.send_result(msg["id"], {
        "profile": profile, "ha_user_id": ha_user_id, "is_admin": user.is_admin if user else False,
    })


@websocket_api.websocket_command({"type": f"{DOMAIN}/save_profile_data", "profile_id": str, "data": dict})
@websocket_api.async_response
async def websocket_save_profile_data(hass, connection, msg) -> None:
    """Save legacy dashboard data, then import genuinely new entries into V2."""
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    try:
        ok = await store.update_profile(msg["profile_id"], msg["data"])
        if not ok:
            connection.send_error(msg["id"], "not_found", "Profile not found")
            return
        repository = hass.data.get(f"{DOMAIN}_repository_v2")
        if repository is not None and hasattr(repository, "async_ingest_legacy_entries"):
            await repository.async_ingest_legacy_entries(
                profile_id=msg["profile_id"],
                entries_by_date=msg["data"].get("entriesByDate") or {},
                operation_id=f"legacy-dashboard-save-{uuid.uuid4()}",
            )
        connection.send_result(msg["id"], {"ok": True})
    except Exception as err:
        _LOGGER.error("Suivi Alimentation: erreur save_profile_data: %s", err)
        connection.send_error(msg["id"], "save_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/add_profile", "profile": dict})
@websocket_api.async_response
async def websocket_add_profile(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    try:
        profile = await store.add_profile(msg["profile"])
        connection.send_result(msg["id"], {"ok": True, "profile": profile})
    except Exception as err:
        _LOGGER.error("Suivi Alimentation: erreur add_profile: %s", err)
        connection.send_error(msg["id"], "add_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/update_profile", "profile_id": str, "profile": dict})
@websocket_api.async_response
async def websocket_update_profile(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    try:
        ok = await store.update_profile(msg["profile_id"], msg["profile"])
        if not ok:
            connection.send_error(msg["id"], "not_found", "Profile not found")
            return
        connection.send_result(msg["id"], {"ok": True})
    except Exception as err:
        _LOGGER.error("Suivi Alimentation: erreur update_profile: %s", err)
        connection.send_error(msg["id"], "update_failed", str(err))


@websocket_api.websocket_command({"type": f"{DOMAIN}/delete_profile", "profile_id": str})
@websocket_api.async_response
async def websocket_delete_profile(hass, connection, msg) -> None:
    store = hass.data.get(DOMAIN)
    if store is None:
        connection.send_error(msg["id"], "not_ready", "Store not initialized")
        return
    try:
        ok = await store.delete_profile(msg["profile_id"])
        if not ok:
            connection.send_error(msg["id"], "delete_failed", "Profil introuvable ou dernier profil (suppression impossible)")
            return
        connection.send_result(msg["id"], {"ok": True})
    except Exception as err:
        _LOGGER.error("Suivi Alimentation: erreur delete_profile: %s", err)
        connection.send_error(msg["id"], "delete_failed", str(err))


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/calculate_goals", "age": int, "sex": str, "weight": float,
    "height": float, "target_weight": float, "activity": str,
})
@websocket_api.async_response
async def websocket_calculate_goals(hass, connection, msg) -> None:
    try:
        result = calculate_nutrition_goals(
            age=msg["age"], sex=msg["sex"], weight=msg["weight"], height=msg["height"],
            target_weight=msg["target_weight"], activity=msg["activity"],
        )
        connection.send_result(msg["id"], result)
    except Exception as err:
        _LOGGER.error("Suivi Alimentation: erreur calculate_goals: %s", err)
        connection.send_error(msg["id"], "calc_failed", str(err))
