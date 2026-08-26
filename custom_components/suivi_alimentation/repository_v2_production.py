"""Production repository policies layered on the v2 repository."""
from __future__ import annotations

import uuid
from copy import deepcopy
from typing import Any

from .repository import SuiviAlimentationRepository, _rebuild_day
from .store_v2 import utc_now_iso

LEGACY_MEAL_TYPES = {
    "Petit-déjeuner": "breakfast",
    "Déjeuner": "lunch",
    "Dîner": "dinner",
    "Collation": "snack",
}


class ProductionSuiviAlimentationRepository(SuiviAlimentationRepository):
    """V2 repository with user-facing meal invariants."""

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
        """Reuse the latest matching draft instead of creating a duplicate draft."""
        current = self.snapshot()
        matching_drafts = [
            meal for meal in current.get("mealsById", {}).values()
            if meal.get("profileId") == profile_id
            and meal.get("mealType") == meal_type
            and meal.get("consumptionLocalDate") == consumption_local_date
            and meal.get("status") == "draft"
        ]
        if matching_drafts:
            matching_drafts.sort(key=lambda meal: meal.get("createdAt") or "", reverse=True)
            meal = matching_drafts[0]
            result = self.idempotent_result()
            result["meal"] = meal
            result["reusedDraft"] = True
            return result

        result = await super().async_create_meal(
            profile_id=profile_id,
            meal_type=meal_type,
            consumption_local_date=consumption_local_date,
            operation_id=operation_id,
            origin=origin,
            label=label,
            consumed_at_utc=consumed_at_utc,
            time_zone=time_zone,
        )
        result["reusedDraft"] = False
        return result

    async def async_ingest_legacy_entries(
        self,
        *,
        profile_id: str,
        entries_by_date: dict[str, Any],
        operation_id: str,
    ) -> dict[str, Any]:
        """Import dashboard-v1 entries missing from V2.

        V2 remains authoritative. Existing V2 item ids are never overwritten; this
        bridge only turns genuinely new legacy dashboard entries into validated V2
        meals so Android sees them immediately.
        """
        candidate = self.snapshot()
        existing_item_ids = set(candidate.get("mealItemsById", {}))
        now = utc_now_iso()
        imported = 0
        touched_dates: set[str] = set()

        for local_date, raw_entries in (entries_by_date or {}).items():
            if not isinstance(raw_entries, list):
                continue
            for raw in raw_entries:
                if not isinstance(raw, dict):
                    continue
                legacy_id = str(raw.get("id") or "").strip()
                if not legacy_id or legacy_id in existing_item_ids:
                    continue
                calories = float(raw.get("calories") or 0)
                proteins = float(raw.get("proteins") or 0)
                if calories <= 0:
                    continue

                meal_type = LEGACY_MEAL_TYPES.get(raw.get("category"), "lunch")
                meal_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"suivi-alimentation:legacy:{profile_id}:{legacy_id}"))
                provenance_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"suivi-alimentation:legacy-provenance:{profile_id}:{legacy_id}"))
                created_at = raw.get("createdAt") or now
                nutrition = {
                    "energyKcal": calories,
                    "proteinG": proteins,
                    "carbsG": None,
                    "fatG": None,
                    "fiberG": None,
                    "saltG": None,
                }
                candidate["nutritionProvenanceById"][provenance_id] = {
                    "id": provenance_id,
                    "sourceType": "legacy_dashboard",
                    "sourceExternalId": legacy_id,
                    "sourceVersion": "v1-production-bridge",
                    "retrievedAt": now,
                    "calculationMethod": "legacy_dashboard_snapshot",
                    "derivedFromProvenanceIds": [],
                    "confidence": None,
                    "aiModel": None,
                    "notes": "Saisie effectuée depuis le dashboard Home Assistant historique",
                    "createdAt": now,
                }
                candidate["mealsById"][meal_id] = {
                    "id": meal_id,
                    "profileId": profile_id,
                    "mealType": meal_type,
                    "label": None,
                    "status": "validated",
                    "consumptionLocalDate": local_date,
                    "consumedAtUtc": created_at,
                    "timeZone": None,
                    "datePrecision": "date_only",
                    "totalsSnapshot": deepcopy(nutrition),
                    "goalVersionId": None,
                    "origin": "legacy_dashboard",
                    "supersedesMealId": None,
                    "supersededByMealId": None,
                    "createdAt": created_at,
                    "updatedAt": now,
                    "validatedAt": now,
                    "voidedAt": None,
                    "revision": 1,
                }
                candidate["mealItemsById"][legacy_id] = {
                    "id": legacy_id,
                    "mealId": meal_id,
                    "kind": "manual_estimate",
                    "foodRefId": None,
                    "recipeId": None,
                    "recipeRevisionId": None,
                    "labelSnapshot": raw.get("name") or "Aliment",
                    "quantityValue": raw.get("quantity"),
                    "quantityUnit": raw.get("quantityUnit"),
                    "gramsEquivalent": raw.get("quantity") if raw.get("quantityUnit") == "g" else None,
                    "nutritionSnapshot": deepcopy(nutrition),
                    "provenanceId": provenance_id,
                    "createdFromProposalId": None,
                    "portionId": None,
                    "portionLabelSnapshot": None,
                    "position": 0,
                    "revision": 1,
                    "createdAt": created_at,
                    "updatedAt": now,
                }
                existing_item_ids.add(legacy_id)
                touched_dates.add(local_date)
                imported += 1

        if not imported:
            return self.idempotent_result() | {"importedLegacyEntries": 0}

        for local_date in touched_dates:
            _rebuild_day(candidate, profile_id=profile_id, local_date=local_date, now=now)

        result = await self.async_replace_shadow_snapshot(
            candidate,
            operation_id=operation_id,
            expected_store_revision=int(candidate["meta"].get("storeRevision", 0)),
            event={
                "entityType": "legacyDashboard",
                "entityId": profile_id,
                "operation": "import_entries",
                "entityRevision": None,
                "profileId": profile_id,
                "importedCount": imported,
            },
        )
        result["importedLegacyEntries"] = imported
        return result
