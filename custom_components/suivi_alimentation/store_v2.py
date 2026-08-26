"""Store v2 for Suivi Alimentation."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store

from .const import DOMAIN

STORAGE_VERSION_V2 = 2
STORAGE_KEY_V2 = f"{DOMAIN}.v2"

COLLECTIONS = (
    "profilesById",
    "goalVersionsById",
    "foodReferencesById",
    "favoritesById",
    "recentItemsById",
    "mealsById",
    "mealItemsById",
    "recipesById",
    "recipeRevisionsById",
    "recipeIngredientsById",
    "mealTemplatesById",
    "mealTemplateItemsById",
    "nutritionProvenanceById",
    "dailyHistoryByProfileDate",
    "temporaryProposalsById",
)


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def empty_store_v2() -> dict[str, Any]:
    now = utc_now_iso()
    data: dict[str, Any] = {
        "meta": {
            "schemaVersion": 2,
            "shadowMode": True,
            "storeRevision": 0,
            "createdAt": now,
            "updatedAt": now,
            "sourceFingerprint": None,
            "lastMigrationAt": None,
            "lastMigrationReport": None,
            "appliedOperationIds": [],
        }
    }
    for collection in COLLECTIONS:
        data[collection] = {}
    return data


class SuiviAlimentationStoreV2:
    """Home Assistant Store used by the v2 data model."""

    def __init__(self, hass: HomeAssistant) -> None:
        self._store = Store(hass, STORAGE_VERSION_V2, STORAGE_KEY_V2)
        self._data: dict[str, Any] = empty_store_v2()

    async def async_load(self) -> dict[str, Any]:
        data = await self._store.async_load()
        if data is None:
            data = empty_store_v2()
            await self._store.async_save(data)
        else:
            baseline = empty_store_v2()
            baseline["meta"].update(data.get("meta", {}))
            for collection in COLLECTIONS:
                baseline[collection] = data.get(collection, {})
            data = baseline
        self._data = data
        return self._data

    async def async_save(self, data: dict[str, Any]) -> None:
        self._data = data
        await self._store.async_save(data)

    def snapshot(self) -> dict[str, Any]:
        return deepcopy(self._data)

    @property
    def data(self) -> dict[str, Any]:
        return self._data
