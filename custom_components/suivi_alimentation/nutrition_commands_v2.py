"""Atomic nutrition commands for Suivi Alimentation v2."""
from __future__ import annotations

import uuid
from copy import deepcopy
from typing import Any

from .nutrition_v2 import NutritionService
from .repository import RevisionConflict, SuiviAlimentationRepository
from .store_v2 import utc_now_iso


class NutritionCommands:
    """Apply nutrition source results through the v2 atomic repository."""

    def __init__(self, nutrition: NutritionService, repository: SuiviAlimentationRepository) -> None:
        self._nutrition = nutrition
        self._repository = repository

    async def async_import_source(
        self, profile_id: str, source: dict[str, Any], operation_id: str
    ) -> dict[str, Any]:
        replay = self._repository.operation_event(operation_id)
        if replay and replay.get("entityType") == "foodReference":
            current_store = self._repository.snapshot()
            current_food = current_store["foodReferencesById"].get(replay.get("entityId"))
            if current_food is not None:
                result = self._repository.idempotent_result()
                result["food"] = current_food
                provenance_id = current_food.get("provenanceId")
                provenance = current_store["nutritionProvenanceById"].get(provenance_id)
                if provenance is not None:
                    result["provenance"] = provenance
                return result
        candidate = self._repository.snapshot()
        store_revision = int(candidate["meta"].get("storeRevision", 0))
        if profile_id not in candidate["profilesById"]:
            raise ValueError("Profile not found")
        food, provenance = self._nutrition.build_food_reference(profile_id, source)
        food_id = food["id"]
        current = candidate["foodReferencesById"].get(food_id)
        if current and current.get("provenanceId") == food.get("provenanceId"):
            return {
                "ok": True, "idempotent": True,
                "storeRevision": store_revision, "food": current,
            }
        if current:
            food["createdAt"] = current.get("createdAt") or food["createdAt"]
            food["revision"] = int(current.get("revision", 1)) + 1
        candidate["nutritionProvenanceById"].setdefault(provenance["id"], provenance)
        candidate["foodReferencesById"][food_id] = food
        result = await self._repository.async_replace_shadow_snapshot(
            candidate, operation_id=operation_id,
            expected_store_revision=store_revision,
            event={
                "entityType": "foodReference", "entityId": food_id,
                "operation": "create" if current is None else "refresh",
                "entityRevision": food["revision"], "profileId": profile_id,
            },
        )
        result["food"] = food
        result["provenance"] = provenance
        return result

    async def async_add_food_to_meal(
        self, *, meal_id: str, food_id: str, quantity_value: float,
        quantity_unit: str, operation_id: str, expected_meal_revision: int,
        portion_id: str | None = None,
    ) -> dict[str, Any]:
        replay = self._repository.operation_event(operation_id)
        if replay and replay.get("entityType") == "mealItem" and replay.get("operation") == "create_from_food":
            current_store = self._repository.snapshot()
            persisted_item = current_store["mealItemsById"].get(replay.get("entityId"))
            persisted_meal = current_store["mealsById"].get(replay.get("mealId"))
            if persisted_item is not None and persisted_meal is not None:
                result = self._repository.idempotent_result()
                result["item"] = persisted_item
                result["meal"] = persisted_meal
                provenance_id = persisted_item.get("provenanceId")
                provenance = current_store["nutritionProvenanceById"].get(provenance_id)
                if provenance is not None:
                    result["provenance"] = provenance
                return result
        candidate = self._repository.snapshot()
        store_revision = int(candidate["meta"].get("storeRevision", 0))
        meal = candidate["mealsById"].get(meal_id)
        food = candidate["foodReferencesById"].get(food_id)
        if meal is None or food is None:
            raise ValueError("Meal or food not found")
        if meal.get("status") != "draft":
            raise ValueError("Only draft meals can be edited")
        if meal.get("profileId") != food.get("ownerProfileId"):
            raise ValueError("Food does not belong to meal profile")
        current_revision = int(meal.get("revision", 1))
        if current_revision != expected_meal_revision:
            raise RevisionConflict("Meal revision conflict")
        if float(quantity_value) <= 0:
            raise ValueError("Quantity must be greater than zero")
        snapshot, grams, method, portion = self._nutrition.build_consumed_snapshot(
            food, float(quantity_value), quantity_unit, portion_id
        )
        provenance = self._nutrition.build_calculation_provenance(
            food, method, float(quantity_value), quantity_unit, grams, portion
        )
        candidate["nutritionProvenanceById"][provenance["id"]] = provenance
        item_id = str(uuid.uuid4())
        now = utc_now_iso()
        positions = [
            int(item.get("position", 0))
            for item in candidate["mealItemsById"].values()
            if item.get("mealId") == meal_id
        ]
        item = {
            "id": item_id, "mealId": meal_id, "kind": "food",
            "foodRefId": food_id, "recipeId": None, "recipeRevisionId": None,
            "labelSnapshot": food.get("label") or "Aliment",
            "quantityValue": float(quantity_value),
            "quantityUnit": (
                portion.get("unitLabel") if portion else quantity_unit
            ),
            "gramsEquivalent": grams, "nutritionSnapshot": deepcopy(snapshot),
            "provenanceId": provenance["id"], "createdFromProposalId": None,
            "portionId": portion.get("id") if portion else None,
            "portionLabelSnapshot": portion.get("label") if portion else None,
            "position": (max(positions) + 1) if positions else 0,
            "revision": 1, "createdAt": now, "updatedAt": now,
        }
        candidate["mealItemsById"][item_id] = item
        meal["revision"] = current_revision + 1
        meal["updatedAt"] = now
        result = await self._repository.async_replace_shadow_snapshot(
            candidate, operation_id=operation_id,
            expected_store_revision=store_revision,
            event={
                "entityType": "mealItem", "entityId": item_id,
                "operation": "create_from_food", "entityRevision": 1,
                "profileId": meal["profileId"], "mealId": meal_id,
                "mealRevision": meal["revision"],
            },
        )
        result["item"] = item
        result["meal"] = meal
        result["provenance"] = provenance
        return result

    async def async_update_food_meal_item(
        self, *, item_id: str, quantity_value: float, quantity_unit: str,
        operation_id: str, expected_item_revision: int,
        expected_meal_revision: int, portion_id: str | None = None,
    ) -> dict[str, Any]:
        """Recalculate one draft food item from its authoritative food reference."""
        replay = self._repository.operation_event(operation_id)
        if replay and replay.get("operation") == "update_food_quantity":
            current_store = self._repository.snapshot()
            persisted_item = current_store["mealItemsById"].get(replay.get("entityId"))
            persisted_meal = current_store["mealsById"].get(replay.get("mealId"))
            if persisted_item is not None and persisted_meal is not None:
                result = self._repository.idempotent_result()
                result["item"] = persisted_item
                result["meal"] = persisted_meal
                return result

        candidate = self._repository.snapshot()
        store_revision = int(candidate["meta"].get("storeRevision", 0))
        item = candidate["mealItemsById"].get(item_id)
        meal = candidate["mealsById"].get(item.get("mealId")) if item else None
        food = candidate["foodReferencesById"].get(item.get("foodRefId")) if item else None
        if item is None or meal is None or food is None:
            raise ValueError("Meal item or food not found")
        if meal.get("status") != "draft":
            raise ValueError("Only draft meals can be edited")
        if int(item.get("revision", 1)) != expected_item_revision:
            raise RevisionConflict("Meal item revision conflict")
        if int(meal.get("revision", 1)) != expected_meal_revision:
            raise RevisionConflict("Meal revision conflict")
        if float(quantity_value) <= 0:
            raise ValueError("Quantity must be greater than zero")

        snapshot, grams, method, portion = self._nutrition.build_consumed_snapshot(
            food, float(quantity_value), quantity_unit, portion_id
        )
        provenance = self._nutrition.build_calculation_provenance(
            food, method, float(quantity_value), quantity_unit, grams, portion
        )
        candidate["nutritionProvenanceById"][provenance["id"]] = provenance
        now = utc_now_iso()
        item["quantityValue"] = float(quantity_value)
        item["quantityUnit"] = portion.get("unitLabel") if portion else quantity_unit
        item["gramsEquivalent"] = grams
        item["nutritionSnapshot"] = deepcopy(snapshot)
        item["provenanceId"] = provenance["id"]
        item["portionId"] = portion.get("id") if portion else None
        item["portionLabelSnapshot"] = portion.get("label") if portion else None
        item["revision"] = int(item.get("revision", 1)) + 1
        item["updatedAt"] = now
        meal["revision"] = int(meal.get("revision", 1)) + 1
        meal["updatedAt"] = now
        result = await self._repository.async_replace_shadow_snapshot(
            candidate,
            operation_id=operation_id,
            expected_store_revision=store_revision,
            event={
                "entityType": "mealItem", "entityId": item_id,
                "operation": "update_food_quantity",
                "entityRevision": item["revision"],
                "profileId": meal["profileId"], "mealId": meal["id"],
                "mealRevision": meal["revision"],
            },
        )
        result["item"] = item
        result["meal"] = meal
        result["provenance"] = provenance
        return result
