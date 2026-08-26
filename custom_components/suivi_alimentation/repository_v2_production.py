"""Production repository policies layered on the v2 repository."""
from __future__ import annotations

from typing import Any

from .repository import SuiviAlimentationRepository


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
