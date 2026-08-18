"""Repository layer for Suivi Alimentation Store v2."""
from __future__ import annotations

import asyncio
import uuid
from copy import deepcopy
from typing import Any

from homeassistant.core import HomeAssistant

from .const import DOMAIN
from .store_v2 import SuiviAlimentationStoreV2, utc_now_iso

EVENT_V2_CHANGED = f"{DOMAIN}_v2_changed"

NUTRIENT_KEYS = ("energyKcal", "proteinG", "carbsG", "fatG", "fiberG", "saltG")


class RevisionConflict(Exception):
    """Optimistic locking conflict."""


class RepositoryValidationError(Exception):
    """Invalid repository operation."""


def _nutrients_zero() -> dict[str, float | None]:
    return {key: 0.0 for key in NUTRIENT_KEYS}


def _sum_snapshots(items: list[dict[str, Any]]) -> dict[str, float | None]:
    totals: dict[str, float | None] = _nutrients_zero()
    known = {key: False for key in NUTRIENT_KEYS}
    for item in items:
        snap = item.get("nutritionSnapshot") or {}
        for key in NUTRIENT_KEYS:
            value = snap.get(key)
            if value is not None:
                totals[key] = float(totals[key] or 0) + float(value)
                known[key] = True
    for key in NUTRIENT_KEYS:
        if not known[key]:
            totals[key] = None
    return totals


class SuiviAlimentationRepository:
    """Only writer allowed to mutate Store v2."""

    def __init__(self, hass: HomeAssistant, store: SuiviAlimentationStoreV2) -> None:
        self._hass = hass
        self._store = store
        self._lock = asyncio.Lock()

    @property
    def store_revision(self) -> int:
        return int(self._store.data.get("meta", {}).get("storeRevision", 0))

    def snapshot(self) -> dict[str, Any]:
        return self._store.snapshot()

    def operation_event(self, operation_id: str) -> dict[str, Any] | None:
        events = self._store.data.get("meta", {}).get("appliedOperationEventsById", {})
        event = events.get(operation_id) if isinstance(events, dict) else None
        return deepcopy(event) if isinstance(event, dict) else None

    def idempotent_result(self) -> dict[str, Any]:
        return {"ok": True, "idempotent": True, "storeRevision": self.store_revision}

    async def _commit(
        self,
        candidate: dict[str, Any],
        *,
        operation_id: str,
        expected_store_revision: int | None = None,
        event: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        candidate_revision = int(candidate.get("meta", {}).get("storeRevision", 0))
        if not operation_id:
            raise RepositoryValidationError("operation_id is required")

        async with self._lock:
            current = self._store.data
            meta = current.setdefault("meta", {})
            current_revision = int(meta.get("storeRevision", 0))
            applied = list(meta.get("appliedOperationIds", []))

            if operation_id in applied:
                return {
                    "ok": True,
                    "idempotent": True,
                    "storeRevision": current_revision,
                }

            if (candidate_revision if expected_store_revision is None else expected_store_revision) != current_revision:
                raise RevisionConflict(
                    f"Expected store revision {expected_store_revision}, current is {current_revision}"
                )

            next_data = deepcopy(candidate)
            next_meta = next_data.setdefault("meta", {})
            next_revision = current_revision + 1
            next_meta["schemaVersion"] = 2
            next_meta["shadowMode"] = True
            next_meta["storeRevision"] = next_revision
            next_meta["updatedAt"] = utc_now_iso()
            next_applied = (applied + [operation_id])[-500:]
            next_meta["appliedOperationIds"] = next_applied
            previous_events = meta.get("appliedOperationEventsById", {})
            if not isinstance(previous_events, dict):
                previous_events = {}
            next_events = {
                oid: deepcopy(previous_events[oid])
                for oid in next_applied
                if oid in previous_events and isinstance(previous_events[oid], dict)
            }
            next_events[operation_id] = deepcopy(event or {})
            next_meta["appliedOperationEventsById"] = next_events

            await self._store.async_save(next_data)

            payload = {
                "storeRevision": next_revision,
                "operationId": operation_id,
                **(event or {}),
            }
            self._hass.bus.async_fire(EVENT_V2_CHANGED, payload)
            return {"ok": True, "idempotent": False, "storeRevision": next_revision}

    async def async_replace_shadow_snapshot(
        self,
        candidate: dict[str, Any],
        *,
        operation_id: str,
        expected_store_revision: int | None = None,
        event: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return await self._commit(
            candidate,
            operation_id=operation_id,
            expected_store_revision=expected_store_revision,
            event=event,
        )

    async def async_create_entity(
        self,
        collection: str,
        entity: dict[str, Any],
        *,
        operation_id: str,
        expected_store_revision: int | None = None,
    ) -> dict[str, Any]:
        entity_id = entity.get("id")
        if not entity_id:
            raise RepositoryValidationError("entity.id is required")
        candidate = self.snapshot()
        target = candidate.get(collection)
        if not isinstance(target, dict):
            raise RepositoryValidationError(f"Unknown collection: {collection}")
        if entity_id in target:
            raise RepositoryValidationError("Entity already exists")
        now = utc_now_iso()
        item = deepcopy(entity)
        item.setdefault("revision", 1)
        item.setdefault("createdAt", now)
        item.setdefault("updatedAt", now)
        target[entity_id] = item
        result = await self._commit(
            candidate,
            operation_id=operation_id,
            expected_store_revision=expected_store_revision,
            event={
                "entityType": collection,
                "entityId": entity_id,
                "operation": "create",
                "entityRevision": item["revision"],
                "profileId": item.get("profileId") or item.get("ownerProfileId"),
            },
        )
        result["entity"] = item
        return result

    async def async_patch_entity(
        self,
        collection: str,
        entity_id: str,
        patch: dict[str, Any],
        *,
        operation_id: str,
        expected_revision: int,
    ) -> dict[str, Any]:
        candidate = self.snapshot()
        target = candidate.get(collection)
        if not isinstance(target, dict) or entity_id not in target:
            raise RepositoryValidationError("Entity not found")
        item = deepcopy(target[entity_id])
        current_revision = int(item.get("revision", 1))
        if expected_revision != current_revision:
            raise RevisionConflict(
                f"Expected entity revision {expected_revision}, current is {current_revision}"
            )
        forbidden = {"id", "createdAt", "revision"}
        for key, value in patch.items():
            if key not in forbidden:
                item[key] = value
        item["revision"] = current_revision + 1
        item["updatedAt"] = utc_now_iso()
        target[entity_id] = item
        result = await self._commit(
            candidate,
            operation_id=operation_id,
            event={
                "entityType": collection,
                "entityId": entity_id,
                "operation": "update",
                "entityRevision": item["revision"],
                "profileId": item.get("profileId") or item.get("ownerProfileId"),
            },
        )
        result["entity"] = item
        return result

    async def async_create_meal(
        self,
        *,
        profile_id: str,
        meal_type: str,
        consumption_local_date: str,
        operation_id: str,
        origin: str = "manual",
        label: str | None = None,
        consumed_at_utc: str | None = None,
        time_zone: str | None = None,
    ) -> dict[str, Any]:
        replay = self.operation_event(operation_id)
        if replay and replay.get("entityType") == "meal" and replay.get("operation") == "create":
            current = self.snapshot()
            persisted = current["mealsById"].get(replay.get("entityId"))
            if persisted is not None:
                result = self.idempotent_result()
                result["meal"] = persisted
                return result
        candidate = self.snapshot()
        if profile_id not in candidate["profilesById"]:
            raise RepositoryValidationError("Profile not found")
        now = utc_now_iso()
        meal_id = str(uuid.uuid4())
        meal = {
            "id": meal_id,
            "profileId": profile_id,
            "mealType": meal_type,
            "label": label,
            "status": "draft",
            "consumptionLocalDate": consumption_local_date,
            "consumedAtUtc": consumed_at_utc,
            "timeZone": time_zone,
            "datePrecision": "datetime" if consumed_at_utc else "date_only",
            "totalsSnapshot": None,
            "goalVersionId": None,
            "origin": origin,
            "supersedesMealId": None,
            "supersededByMealId": None,
            "createdAt": now,
            "updatedAt": now,
            "validatedAt": None,
            "voidedAt": None,
            "revision": 1,
        }
        candidate["mealsById"][meal_id] = meal
        result = await self._commit(
            candidate,
            operation_id=operation_id,
            event={
                "entityType": "meal",
                "entityId": meal_id,
                "operation": "create",
                "entityRevision": 1,
                "profileId": profile_id,
            },
        )
        result["meal"] = meal
        return result

    async def async_set_favorite(
        self,
        *,
        profile_id: str,
        food_ref_id: str,
        favorite: bool,
        operation_id: str,
    ) -> dict[str, Any]:
        """Create or remove one profile-scoped food favorite atomically."""
        candidate = self.snapshot()
        if profile_id not in candidate["profilesById"]:
            raise RepositoryValidationError("Profile not found")
        food = candidate["foodReferencesById"].get(food_ref_id)
        if food is None or food.get("ownerProfileId") not in (None, profile_id):
            raise RepositoryValidationError("Food not found")

        favorite_id = str(uuid.uuid5(
            uuid.NAMESPACE_URL,
            f"{DOMAIN}:favorite:{profile_id}:{food_ref_id}",
        ))
        current = candidate["favoritesById"].get(favorite_id)
        now = utc_now_iso()
        entity = current
        if favorite:
            entity = {
                "id": favorite_id,
                "profileId": profile_id,
                "foodRefId": food_ref_id,
                "createdAt": current.get("createdAt", now) if current else now,
                "updatedAt": now,
                "revision": int(current.get("revision", 0)) + 1 if current else 1,
            }
            candidate["favoritesById"][favorite_id] = entity
        else:
            candidate["favoritesById"].pop(favorite_id, None)

        result = await self._commit(
            candidate,
            operation_id=operation_id,
            event={
                "entityType": "favorite",
                "entityId": favorite_id,
                "operation": "create" if favorite else "delete",
                "entityRevision": entity.get("revision") if favorite and entity else None,
                "profileId": profile_id,
                "foodRefId": food_ref_id,
            },
        )
        result["favorite"] = entity if favorite else None
        result["foodRefId"] = food_ref_id
        return result

    async def async_add_meal_item(
        self,
        *,
        meal_id: str,
        item: dict[str, Any],
        operation_id: str,
        expected_meal_revision: int,
    ) -> dict[str, Any]:
        candidate = self.snapshot()
        meal = candidate["mealsById"].get(meal_id)
        if meal is None:
            raise RepositoryValidationError("Meal not found")
        if meal.get("status") != "draft":
            raise RepositoryValidationError("Only draft meals can be edited")
        current_revision = int(meal.get("revision", 1))
        if current_revision != expected_meal_revision:
            raise RevisionConflict(
                f"Expected meal revision {expected_meal_revision}, current is {current_revision}"
            )
        item_id = str(uuid.uuid4())
        now = utc_now_iso()
        new_item = {
            "id": item_id,
            "mealId": meal_id,
            "kind": item.get("kind", "manual_estimate"),
            "foodRefId": item.get("foodRefId"),
            "recipeId": item.get("recipeId"),
            "recipeRevisionId": item.get("recipeRevisionId"),
            "labelSnapshot": item.get("labelSnapshot") or "Aliment",
            "quantityValue": item.get("quantityValue"),
            "quantityUnit": item.get("quantityUnit"),
            "gramsEquivalent": item.get("gramsEquivalent"),
            "nutritionSnapshot": deepcopy(item.get("nutritionSnapshot")),
            "provenanceId": item.get("provenanceId"),
            "createdFromProposalId": item.get("createdFromProposalId"),
            "position": int(item.get("position", 0)),
            "revision": 1,
            "createdAt": now,
            "updatedAt": now,
        }
        prov_id = new_item.get("provenanceId")
        if prov_id and prov_id not in candidate["nutritionProvenanceById"]:
            raise RepositoryValidationError("Unknown provenanceId")
        candidate["mealItemsById"][item_id] = new_item
        meal["revision"] = current_revision + 1
        meal["updatedAt"] = now
        result = await self._commit(
            candidate,
            operation_id=operation_id,
            event={
                "entityType": "mealItem",
                "entityId": item_id,
                "operation": "create",
                "entityRevision": 1,
                "profileId": meal["profileId"],
                "mealId": meal_id,
                "mealRevision": meal["revision"],
            },
        )
        result["item"] = new_item
        result["meal"] = meal
        return result

    async def async_update_meal_item(
        self,
        *,
        item_id: str,
        patch: dict[str, Any],
        operation_id: str,
        expected_item_revision: int,
        expected_meal_revision: int,
    ) -> dict[str, Any]:
        candidate = self.snapshot()
        item = candidate["mealItemsById"].get(item_id)
        if item is None:
            raise RepositoryValidationError("Meal item not found")
        meal = candidate["mealsById"].get(item["mealId"])
        if meal is None or meal.get("status") != "draft":
            raise RepositoryValidationError("Only draft meals can be edited")
        if int(item.get("revision", 1)) != expected_item_revision:
            raise RevisionConflict("Meal item revision conflict")
        if int(meal.get("revision", 1)) != expected_meal_revision:
            raise RevisionConflict("Meal revision conflict")
        forbidden = {"id", "mealId", "createdAt", "revision"}
        for key, value in patch.items():
            if key not in forbidden:
                item[key] = deepcopy(value)
        prov_id = item.get("provenanceId")
        if prov_id and prov_id not in candidate["nutritionProvenanceById"]:
            raise RepositoryValidationError("Unknown provenanceId")
        now = utc_now_iso()
        item["revision"] = int(item.get("revision", 1)) + 1
        item["updatedAt"] = now
        meal["revision"] = int(meal.get("revision", 1)) + 1
        meal["updatedAt"] = now
        result = await self._commit(
            candidate,
            operation_id=operation_id,
            event={
                "entityType": "mealItem",
                "entityId": item_id,
                "operation": "update",
                "entityRevision": item["revision"],
                "profileId": meal["profileId"],
                "mealId": meal["id"],
                "mealRevision": meal["revision"],
            },
        )
        result["item"] = item
        result["meal"] = meal
        return result

    async def async_remove_meal_item(
        self,
        *,
        item_id: str,
        operation_id: str,
        expected_item_revision: int,
        expected_meal_revision: int,
    ) -> dict[str, Any]:
        candidate = self.snapshot()
        item = candidate["mealItemsById"].get(item_id)
        if item is None:
            raise RepositoryValidationError("Meal item not found")
        meal = candidate["mealsById"].get(item["mealId"])
        if meal is None or meal.get("status") != "draft":
            raise RepositoryValidationError("Only draft meals can be edited")
        if int(item.get("revision", 1)) != expected_item_revision:
            raise RevisionConflict("Meal item revision conflict")
        if int(meal.get("revision", 1)) != expected_meal_revision:
            raise RevisionConflict("Meal revision conflict")
        del candidate["mealItemsById"][item_id]
        meal["revision"] = int(meal.get("revision", 1)) + 1
        meal["updatedAt"] = utc_now_iso()
        result = await self._commit(
            candidate,
            operation_id=operation_id,
            event={
                "entityType": "mealItem",
                "entityId": item_id,
                "operation": "delete",
                "entityRevision": None,
                "profileId": meal["profileId"],
                "mealId": meal["id"],
                "mealRevision": meal["revision"],
            },
        )
        result["meal"] = meal
        return result

    async def async_validate_meal(
        self,
        *,
        meal_id: str,
        operation_id: str,
        expected_meal_revision: int,
    ) -> dict[str, Any]:
        replay = self.operation_event(operation_id)
        if replay and replay.get("entityType") == "meal" and replay.get("operation") == "validate":
            current = self.snapshot()
            persisted = current["mealsById"].get(replay.get("entityId"))
            if persisted is not None:
                result = self.idempotent_result()
                result["meal"] = persisted
                dkey = f"{persisted['profileId']}|{persisted['consumptionLocalDate']}"
                day = current["dailyHistoryByProfileDate"].get(dkey)
                if day is not None:
                    result["dailyHistory"] = day
                return result
        candidate = self.snapshot()
        meal = candidate["mealsById"].get(meal_id)
        if meal is None:
            raise RepositoryValidationError("Meal not found")
        if meal.get("status") != "draft":
            raise RepositoryValidationError("Meal is not a draft")
        if int(meal.get("revision", 1)) != expected_meal_revision:
            raise RevisionConflict("Meal revision conflict")
        items = [
            i for i in candidate["mealItemsById"].values()
            if i.get("mealId") == meal_id
        ]
        if not items:
            raise RepositoryValidationError("Cannot validate an empty meal")
        for item in items:
            if item.get("nutritionSnapshot") is None:
                raise RepositoryValidationError("Every item needs a nutrition snapshot")
            if not item.get("provenanceId"):
                raise RepositoryValidationError("Every item needs a provenanceId")
        totals = _sum_snapshots(items)
        now = utc_now_iso()
        meal["totalsSnapshot"] = totals
        meal["status"] = "validated"
        meal["validatedAt"] = now
        meal["updatedAt"] = now
        meal["revision"] = int(meal.get("revision", 1)) + 1

        dkey = f"{meal['profileId']}|{meal['consumptionLocalDate']}"
        day = candidate["dailyHistoryByProfileDate"].get(dkey)
        if day is None:
            day = {
                "profileId": meal["profileId"],
                "localDate": meal["consumptionLocalDate"],
                "mealIds": [],
                "validatedMealCount": 0,
                "totals": _nutrients_zero(),
                "revision": 0,
                "updatedAt": now,
            }
        if meal_id not in day["mealIds"]:
            day["mealIds"].append(meal_id)
        active_meals = [
            candidate["mealsById"][mid]
            for mid in day["mealIds"]
            if mid in candidate["mealsById"]
            and candidate["mealsById"][mid].get("status") == "validated"
        ]
        day["validatedMealCount"] = len(active_meals)
        day["totals"] = _sum_snapshots(
            [{"nutritionSnapshot": m.get("totalsSnapshot")} for m in active_meals]
        )
        day["revision"] = int(day.get("revision", 0)) + 1
        day["updatedAt"] = now
        candidate["dailyHistoryByProfileDate"][dkey] = day

        result = await self._commit(
            candidate,
            operation_id=operation_id,
            event={
                "entityType": "meal",
                "entityId": meal_id,
                "operation": "validate",
                "entityRevision": meal["revision"],
                "profileId": meal["profileId"],
                "localDate": meal["consumptionLocalDate"],
            },
        )
        result["meal"] = meal
        result["dailyHistory"] = day
        return result
