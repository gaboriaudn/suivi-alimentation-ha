"""Profile settings and automatic nutrition goals for Suivi Alimentation v2."""
from __future__ import annotations

from datetime import date
from typing import Any
import uuid

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .repository import RepositoryValidationError, RevisionConflict

_ACTIVITY_FACTORS = {
    "sedentary": 1.20,
    "light": 1.375,
    "moderate": 1.55,
    "active": 1.725,
    "very_active": 1.90,
}
_GOAL_ADJUSTMENTS = {
    "lose": -400.0,
    "maintain": 0.0,
    "gain": 300.0,
}
_PROTEIN_FACTORS = {
    "lose": 1.6,
    "maintain": 1.2,
    "gain": 1.6,
}


def _repository(hass: HomeAssistant):
    return hass.data.get(f"{DOMAIN}_repository_v2")


def _has_access(connection: websocket_api.ActiveConnection, profile: dict[str, Any]) -> bool:
    user = connection.user
    return bool(user and (user.is_admin or profile.get("ownerHaUserId") == user.id))


def _number(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if result > 0 else None


def _resolved_weight(hass: HomeAssistant, settings: dict[str, Any]) -> dict[str, Any]:
    source = settings.get("weightSource") or {"type": "manual"}
    source_type = source.get("type") or "manual"
    if source_type == "home_assistant":
        entity_id = str(source.get("entityId") or "").strip()
        state = hass.states.get(entity_id) if entity_id else None
        value = _number(state.state) if state and state.state not in {"unknown", "unavailable"} else None
        unit = state.attributes.get("unit_of_measurement") if state else None
        if value is not None and str(unit).lower() in {"lb", "lbs", "pound", "pounds"}:
            value = value * 0.45359237
        return {
            "sourceType": "home_assistant",
            "entityId": entity_id or None,
            "valueKg": round(value, 3) if value is not None else None,
            "lastUpdated": state.last_updated.isoformat() if state else None,
            "available": value is not None,
        }
    value = _number(source.get("manualWeightKg"))
    return {
        "sourceType": "manual",
        "entityId": None,
        "valueKg": round(value, 3) if value is not None else None,
        "lastUpdated": None,
        "available": value is not None,
    }


def _age_on(birth_date: str, today: date) -> int | None:
    try:
        born = date.fromisoformat(birth_date)
    except (TypeError, ValueError):
        return None
    if born > today:
        return None
    return today.year - born.year - ((today.month, today.day) < (born.month, born.day))


def _automatic_goal(settings: dict[str, Any], weight_kg: float | None) -> dict[str, Any] | None:
    sex = settings.get("sex")
    birth_date = settings.get("birthDate")
    height_cm = _number(settings.get("heightCm"))
    activity = settings.get("activityLevel") or "moderate"
    objective = settings.get("objective") or "maintain"
    age = _age_on(birth_date, date.today()) if birth_date else None
    if sex not in {"male", "female"} or age is None or height_cm is None or weight_kg is None:
        return None
    factor = _ACTIVITY_FACTORS.get(activity)
    if factor is None or objective not in _GOAL_ADJUSTMENTS:
        return None
    sex_constant = 5.0 if sex == "male" else -161.0
    bmr = (10.0 * weight_kg) + (6.25 * height_cm) - (5.0 * age) + sex_constant
    maintenance = bmr * factor
    calories = max(1200.0, maintenance + _GOAL_ADJUSTMENTS[objective])
    protein = weight_kg * _PROTEIN_FACTORS[objective]
    return {
        "energyKcal": round(calories),
        "proteinG": round(protein),
        "carbsG": None,
        "fatG": None,
        "fiberG": None,
        "saltG": None,
        "calculation": {
            "formula": "mifflin_st_jeor",
            "age": age,
            "bmrKcal": round(bmr),
            "maintenanceKcal": round(maintenance),
            "activityFactor": factor,
            "calorieAdjustmentKcal": _GOAL_ADJUSTMENTS[objective],
            "proteinFactorGPerKg": _PROTEIN_FACTORS[objective],
            "weightKg": round(weight_kg, 3),
        },
    }


def _public_context(hass: HomeAssistant, profile: dict[str, Any], goals: list[dict[str, Any]]) -> dict[str, Any]:
    settings = dict(profile.get("nutritionProfile") or {})
    legacy = profile.get("legacyRef") or {}
    settings.setdefault("sex", legacy.get("sex"))
    settings.setdefault("heightCm", legacy.get("height"))
    settings.setdefault("activityLevel", legacy.get("activity") or "moderate")
    settings.setdefault("objective", "lose" if legacy.get("targetWeight") and legacy.get("weight") and legacy.get("targetWeight") < legacy.get("weight") else "maintain")
    settings.setdefault("targetWeightKg", legacy.get("targetWeight"))
    settings.setdefault("goalCalculationMode", "manual")
    settings.setdefault("weightSource", {"type": "manual", "manualWeightKg": legacy.get("weight")})
    resolved = _resolved_weight(hass, settings)
    recommendation = _automatic_goal(settings, resolved.get("valueKg"))
    active_goals = sorted(goals, key=lambda g: (g.get("versionNumber", 0), g.get("createdAt", "")), reverse=True)
    return {
        "profile": profile,
        "settings": settings,
        "resolvedWeight": resolved,
        "automaticRecommendation": recommendation,
        "currentGoal": active_goals[0] if active_goals else None,
    }


@callback
def async_setup(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, websocket_profile_get)
    websocket_api.async_register_command(hass, websocket_profile_update)


@websocket_api.websocket_command({"type": f"{DOMAIN}/v2/profile/get", "profile_id": str})
@websocket_api.async_response
async def websocket_profile_get(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    profile = data.get("profilesById", {}).get(msg["profile_id"])
    if profile is None or not _has_access(connection, profile):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return
    goals = [g for g in data.get("goalVersionsById", {}).values() if g.get("profileId") == profile["id"]]
    result = _public_context(hass, profile, goals)
    result["storeRevision"] = data.get("meta", {}).get("storeRevision", 0)
    connection.send_result(msg["id"], result)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/profile/update",
    "profile_id": str,
    "settings": dict,
    "expected_profile_revision": int,
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_profile_update(hass, connection, msg) -> None:
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return
    data = repository.snapshot()
    profile = data.get("profilesById", {}).get(msg["profile_id"])
    if profile is None or not _has_access(connection, profile):
        connection.send_error(msg["id"], "unauthorized", "Profile unavailable")
        return

    incoming = dict(msg.get("settings") or {})
    allowed = {
        "sex", "birthDate", "heightCm", "activityLevel", "objective",
        "targetWeightKg", "goalCalculationMode", "weightSource",
        "manualEnergyKcal", "manualProteinG",
    }
    settings = {key: incoming.get(key) for key in allowed if key in incoming}
    if settings.get("sex") not in {None, "male", "female"}:
        connection.send_error(msg["id"], "validation_error", "Invalid sex")
        return
    if settings.get("activityLevel") not in {None, *_ACTIVITY_FACTORS.keys()}:
        connection.send_error(msg["id"], "validation_error", "Invalid activity level")
        return
    if settings.get("objective") not in {None, *_GOAL_ADJUSTMENTS.keys()}:
        connection.send_error(msg["id"], "validation_error", "Invalid objective")
        return
    if settings.get("goalCalculationMode") not in {None, "manual", "automatic"}:
        connection.send_error(msg["id"], "validation_error", "Invalid goal calculation mode")
        return
    weight_source = settings.get("weightSource") or {}
    if weight_source and weight_source.get("type") not in {"manual", "home_assistant"}:
        connection.send_error(msg["id"], "validation_error", "Invalid weight source")
        return

    try:
        patch_result = await repository.async_patch_entity(
            "profilesById",
            profile["id"],
            {"nutritionProfile": settings},
            operation_id=msg["operation_id"],
            expected_revision=msg["expected_profile_revision"],
        )
        updated_profile = patch_result["entity"]
        goal_result = None
        resolved = _resolved_weight(hass, settings)
        mode = settings.get("goalCalculationMode") or "manual"
        if mode == "automatic":
            recommendation = _automatic_goal(settings, resolved.get("valueKg"))
            if recommendation is None:
                raise RepositoryValidationError("Profile incomplete for automatic goal calculation")
            current = repository.snapshot()
            profile_goals = [g for g in current.get("goalVersionsById", {}).values() if g.get("profileId") == profile["id"]]
            version_number = max([int(g.get("versionNumber", 0)) for g in profile_goals] or [0]) + 1
            goal_id = str(uuid.uuid4())
            goal = {
                "id": goal_id,
                "profileId": profile["id"],
                "versionNumber": version_number,
                "effectiveFromLocalDate": date.today().isoformat(),
                "effectiveToLocalDate": None,
                "targets": {key: recommendation[key] for key in ("energyKcal", "proteinG", "carbsG", "fatG", "fiberG", "saltG")},
                "sourceType": "automatic_profile",
                "sourceDetails": recommendation["calculation"],
                "createdByHaUserId": connection.user.id if connection.user else None,
            }
            goal_result = await repository.async_create_entity(
                "goalVersionsById",
                goal,
                operation_id=f"{msg['operation_id']}:goal",
            )
        elif mode == "manual":
            energy = _number(settings.get("manualEnergyKcal"))
            protein = _number(settings.get("manualProteinG"))
            if energy is not None or protein is not None:
                current = repository.snapshot()
                profile_goals = [g for g in current.get("goalVersionsById", {}).values() if g.get("profileId") == profile["id"]]
                version_number = max([int(g.get("versionNumber", 0)) for g in profile_goals] or [0]) + 1
                goal = {
                    "id": str(uuid.uuid4()),
                    "profileId": profile["id"],
                    "versionNumber": version_number,
                    "effectiveFromLocalDate": date.today().isoformat(),
                    "effectiveToLocalDate": None,
                    "targets": {"energyKcal": energy, "proteinG": protein, "carbsG": None, "fatG": None, "fiberG": None, "saltG": None},
                    "sourceType": "manual_profile",
                    "sourceDetails": {"note": "Objectifs saisis depuis l’application Android"},
                    "createdByHaUserId": connection.user.id if connection.user else None,
                }
                goal_result = await repository.async_create_entity(
                    "goalVersionsById",
                    goal,
                    operation_id=f"{msg['operation_id']}:goal",
                )
        current = repository.snapshot()
        goals = [g for g in current.get("goalVersionsById", {}).values() if g.get("profileId") == profile["id"]]
        result = _public_context(hass, updated_profile, goals)
        result["storeRevision"] = current.get("meta", {}).get("storeRevision", 0)
        result["profileUpdate"] = patch_result
        result["goalUpdate"] = goal_result
        connection.send_result(msg["id"], result)
    except RevisionConflict as err:
        connection.send_error(msg["id"], "revision_conflict", str(err))
    except RepositoryValidationError as err:
        connection.send_error(msg["id"], "validation_error", str(err))
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))
