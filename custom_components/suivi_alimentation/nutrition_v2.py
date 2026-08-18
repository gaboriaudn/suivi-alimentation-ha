"""Nutrition providers and normalization for Suivi Alimentation v2."""
from __future__ import annotations

import asyncio
import hashlib
import json
import unicodedata
import uuid
import xml.etree.ElementTree as ET
from copy import deepcopy
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .portion_catalog import CATALOG_VERSION, portions_for_ciqual
from .store_v2 import utc_now_iso

CIQUAL_VERSION = "2025-11-03"
CIQUAL_DATASET_DOI = "10.57745/RDMHWY"
CIQUAL_ALIM_FILE_ID = 666252
CIQUAL_COMPO_FILE_ID = 666249
CIQUAL_BASE = "https://entrepot.recherche.data.gouv.fr/api/access/datafile"
OFF_API_BASE = "https://world.openfoodfacts.org/api/v3/product"

NUTRIENT_KEYS = ("energyKcal", "proteinG", "carbsG", "fatG", "fiberG", "saltG")
CIQUAL_CODES = {
    "energyKcal": "328",
    "proteinG": "25000",
    "carbsG": "31000",
    "fatG": "40000",
    "fiberG": "34100",
    "saltG": "10004",
}
_NS = uuid.UUID("7a6d1a94-df32-4f24-8e06-9fa85b146e06")


def _id(kind: str, *parts: object) -> str:
    return str(uuid.uuid5(_NS, ":".join([kind, *map(str, parts)])))


def _clean(value: str | None) -> str:
    return (value or "").strip()


def _norm_text(value: str | None) -> str:
    text = _clean(value).lower().replace("œ", "oe").replace("æ", "ae")
    text = unicodedata.normalize("NFKD", text)
    return " ".join("".join(ch for ch in text if not unicodedata.combining(ch)).split())


def _search_token_matches(token: str, label: str) -> bool:
    """Match a query token, including a conservative French plural in ``s``."""
    if token in label:
        return True
    if len(token) > 3 and token.endswith("s"):
        singular = token[:-1]
        words = [word.strip(".,;:!?()[]{}'\"") for word in label.split()]
        return singular in words
    return False


def _number(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip().replace(",", ".")
    if text in {"", "-", "tr", "trace", "<"}:
        return None
    if text.startswith("<"):
        text = text[1:].strip()
    try:
        return float(text)
    except ValueError:
        return None


def normalize_nutrition(values: dict[str, Any] | None) -> dict[str, float | None]:
    source = values or {}
    return {key: _number(source.get(key)) for key in NUTRIENT_KEYS}


def scale_nutrition(per100g: dict[str, Any], grams: float) -> dict[str, float | None]:
    if grams <= 0:
        raise ValueError("grams must be greater than zero")
    base = normalize_nutrition(per100g)
    factor = float(grams) / 100.0
    return {key: (None if base[key] is None else round(base[key] * factor, 6)) for key in NUTRIENT_KEYS}


def scale_unit_nutrition(per_unit: dict[str, Any], count: float) -> dict[str, float | None]:
    if count <= 0:
        raise ValueError("count must be greater than zero")
    base = normalize_nutrition(per_unit)
    return {key: (None if base[key] is None else round(base[key] * count, 6)) for key in NUTRIENT_KEYS}


class NutritionService:
    """Read external sources and build traceable normalized nutrition objects."""

    def __init__(self, hass: HomeAssistant) -> None:
        self._session = async_get_clientsession(hass)
        self._ciqual_lock = asyncio.Lock()
        self._ciqual_records: dict[str, dict[str, Any]] | None = None

    async def _download_xml(self, file_id: int) -> bytes:
        async with self._session.get(f"{CIQUAL_BASE}/{file_id}", timeout=30) as response:
            response.raise_for_status()
            return await response.read()

    async def _ensure_ciqual(self) -> None:
        if self._ciqual_records is not None:
            return
        async with self._ciqual_lock:
            if self._ciqual_records is not None:
                return
            alim_raw, compo_raw = await asyncio.gather(
                self._download_xml(CIQUAL_ALIM_FILE_ID),
                self._download_xml(CIQUAL_COMPO_FILE_ID),
            )
            foods: dict[str, dict[str, Any]] = {}
            for node in ET.fromstring(alim_raw).findall("ALIM"):
                code = _clean(node.findtext("alim_code"))
                if not code:
                    continue
                label = _clean(node.findtext("alim_nom_fr"))
                foods[code] = {
                    "sourceType": "ciqual",
                    "sourceExternalId": code,
                    "label": label,
                    "labelNormalized": _norm_text(label),
                    "groupCode": _clean(node.findtext("alim_grp_code")) or None,
                    "subgroupCode": _clean(node.findtext("alim_ssgrp_code")) or None,
                    "nutrientsPer100g": {key: None for key in NUTRIENT_KEYS},
                    "confidenceByNutrient": {},
                }
            reverse_codes = {v: k for k, v in CIQUAL_CODES.items()}
            for node in ET.fromstring(compo_raw).findall("COMPO"):
                code = _clean(node.findtext("alim_code"))
                nutrient = reverse_codes.get(_clean(node.findtext("const_code")))
                if not nutrient or code not in foods:
                    continue
                foods[code]["nutrientsPer100g"][nutrient] = _number(node.findtext("teneur"))
                confidence = _clean(node.findtext("code_confiance"))
                if confidence:
                    foods[code]["confidenceByNutrient"][nutrient] = confidence
            self._ciqual_records = foods

    async def async_search_ciqual(self, query: str, limit: int = 20) -> list[dict[str, Any]]:
        await self._ensure_ciqual()
        needle = _norm_text(query)
        if not needle:
            return []
        tokens = needle.split()
        ranked = []
        for record in self._ciqual_records.values():
            label = record["labelNormalized"]
            if all(_search_token_matches(token, label) for token in tokens):
                score = 0 if label == needle else (1 if label.startswith(needle) else 2)
                ranked.append((score, len(label), record))
        ranked.sort(key=lambda row: (row[0], row[1], row[2]["label"]))
        return [self._public_ciqual(row[2]) for row in ranked[: max(1, min(limit, 50))]]

    async def async_get_ciqual(self, code: str) -> dict[str, Any] | None:
        await self._ensure_ciqual()
        record = self._ciqual_records.get(str(code).strip())
        return self._public_ciqual(record) if record else None

    def search_personal_foods(
        self,
        foods: list[dict[str, Any]],
        query: str,
        existing_by_legacy_id: dict[str, str],
        limit: int = 20,
    ) -> list[dict[str, Any]]:
        needle = _norm_text(query)
        if not needle:
            return []
        tokens = needle.split()
        ranked = []
        for food in foods:
            label = _norm_text(food.get("name"))
            if all(_search_token_matches(token, label) for token in tokens):
                score = 0 if label == needle else (1 if label.startswith(needle) else 2)
                ranked.append((score, len(label), food))
        ranked.sort(key=lambda row: (row[0], row[1], row[2].get("name") or ""))
        return [
            self.personal_candidate(
                row[2], existing_by_legacy_id.get(str(row[2].get("id") or ""))
            )
            for row in ranked[: max(1, min(limit, 50))]
        ]

    @staticmethod
    def _public_ciqual(record: dict[str, Any]) -> dict[str, Any]:
        result = deepcopy(record)
        result.pop("labelNormalized", None)
        result["sourceVersion"] = CIQUAL_VERSION
        result["datasetDoi"] = CIQUAL_DATASET_DOI
        result["servingDefinitions"] = portions_for_ciqual(result["sourceExternalId"])
        result["portionCatalogVersion"] = CATALOG_VERSION
        return result

    @staticmethod
    def personal_source(legacy_food: dict[str, Any]) -> dict[str, Any]:
        """Normalize one v1 user-created article without inventing missing weights."""
        legacy_id = str(legacy_food.get("id") or "").strip()
        if not legacy_id:
            raise ValueError("Personal food id is required")
        mode = legacy_food.get("mode")
        unit_label = _clean(legacy_food.get("unitLabel")) or "unité"
        grams_per_unit = _number(
            legacy_food.get("gramsPerUnit")
            or legacy_food.get("servingGrams")
            or legacy_food.get("gramsEquivalent")
        )
        per_100g = normalize_nutrition({
            "energyKcal": legacy_food.get("caloriesPer100g"),
            "proteinG": legacy_food.get("proteinsPer100g"),
        }) if mode == "grams" else None
        per_unit = normalize_nutrition({
            "energyKcal": legacy_food.get("caloriesPerUnit"),
            "proteinG": legacy_food.get("proteinsPerUnit"),
        }) if mode == "unit" else None
        portions = []
        if mode == "unit":
            portions.append({
                "id": f"personal:{legacy_id}:unit",
                "label": f"1 {unit_label}",
                "unitLabel": unit_label,
                "gramsEquivalent": grams_per_unit,
                "sourceType": "user_defined",
                "sourceExternalId": legacy_id,
                "sourcePortionId": None,
                "sourceVersion": None,
                "sourceUrl": None,
            })
        fingerprint_payload = {
            "name": legacy_food.get("name"), "mode": mode,
            "caloriesPer100g": legacy_food.get("caloriesPer100g"),
            "proteinsPer100g": legacy_food.get("proteinsPer100g"),
            "caloriesPerUnit": legacy_food.get("caloriesPerUnit"),
            "proteinsPerUnit": legacy_food.get("proteinsPerUnit"),
            "unitLabel": legacy_food.get("unitLabel"),
            "gramsPerUnit": grams_per_unit,
        }
        fingerprint = hashlib.sha256(
            json.dumps(fingerprint_payload, ensure_ascii=False, sort_keys=True).encode()
        ).hexdigest()[:16]
        return {
            "sourceType": "personal",
            "sourceExternalId": legacy_id,
            "sourceVersion": f"v1:{fingerprint}",
            "label": legacy_food.get("name") or "Aliment personnel",
            "nutritionBasis": "per_100g" if mode == "grams" else "per_unit",
            "nutrientsPer100g": per_100g,
            "nutrientsPerUnit": per_unit,
            "servingDefinitions": portions,
            "servingSize": unit_label if mode == "unit" else None,
            "servingQuantityG": grams_per_unit,
            "legacyRef": {"foodId": legacy_id},
        }

    @staticmethod
    def personal_candidate(
        legacy_food: dict[str, Any], existing_food_id: str | None = None
    ) -> dict[str, Any]:
        source = NutritionService.personal_source(legacy_food)
        return {
            "sourceType": "personal",
            "sourceExternalId": source["sourceExternalId"],
            "foodId": existing_food_id,
            "label": source["label"],
            "nutritionBasis": source["nutritionBasis"],
            "nutrientsPer100g": source["nutrientsPer100g"],
            "nutrientsPerUnit": source["nutrientsPerUnit"],
            "servingDefinitions": source["servingDefinitions"],
            "sourceVersion": source["sourceVersion"],
        }

    async def async_get_off(self, barcode: str) -> dict[str, Any] | None:
        barcode = "".join(ch for ch in str(barcode) if ch.isdigit())
        if not barcode:
            return None
        params = {"fields": "code,product_name,brands,serving_size,serving_quantity,nutriments,last_modified_t"}
        headers = {"User-Agent": "SuiviAlimentation/0.23 (Home Assistant integration)"}
        async with self._session.get(
            f"{OFF_API_BASE}/{barcode}", params=params, headers=headers, timeout=20
        ) as response:
            if response.status == 404:
                return None
            response.raise_for_status()
            payload = await response.json()
        product = payload.get("product") or {}
        if not product:
            return None
        nutr = product.get("nutriments") or {}

        def per100(name: str) -> float | None:
            return _number(nutr.get(f"{name}_100g"))

        energy = per100("energy-kcal")
        if energy is None:
            kj = per100("energy")
            energy = None if kj is None else kj / 4.184
        return {
            "sourceType": "open_food_facts",
            "sourceExternalId": barcode,
            "sourceVersion": "api-v3:" + str(product.get("last_modified_t") or "unknown"),
            "label": product.get("product_name") or f"Produit {barcode}",
            "brand": product.get("brands"),
            "barcode": barcode,
            "nutrientsPer100g": normalize_nutrition({
                "energyKcal": energy,
                "proteinG": per100("proteins"),
                "carbsG": per100("carbohydrates"),
                "fatG": per100("fat"),
                "fiberG": per100("fiber"),
                "saltG": per100("salt"),
            }),
            "servingSize": product.get("serving_size"),
            "servingQuantityG": _number(product.get("serving_quantity")),
            "servingDefinitions": ([{
                "id": f"off:{barcode}:serving",
                "label": product.get("serving_size") or "1 portion",
                "unitLabel": product.get("serving_size") or "portion",
                "gramsEquivalent": _number(product.get("serving_quantity")),
                "sourceType": "open_food_facts",
                "sourceExternalId": barcode,
                "sourcePortionId": "serving",
                "sourceVersion": "api-v3:" + str(product.get("last_modified_t") or "unknown"),
                "sourceUrl": f"https://world.openfoodfacts.org/product/{barcode}",
            }] if _number(product.get("serving_quantity")) else []),
            "sourceLastModifiedUnix": product.get("last_modified_t"),
        }

    def build_food_reference(self, profile_id: str, source: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        now = utc_now_iso()
        source_type = source["sourceType"]
        external_id = str(source["sourceExternalId"])
        fid = str(source.get("foodId") or _id("food", profile_id, source_type, external_id))
        pid = _id("prov-source", profile_id, source_type, external_id, source.get("sourceVersion") or "")
        provenance = {
            "id": pid,
            "sourceType": source_type,
            "sourceExternalId": external_id,
            "sourceVersion": source.get("sourceVersion"),
            "retrievedAt": now,
            "calculationMethod": "source_per_100g" if source.get("nutritionBasis", "per_100g") == "per_100g" else "source_per_unit",
            "derivedFromProvenanceIds": [],
            "confidence": source.get("confidenceByNutrient"),
            "aiModel": None,
            "notes": (
                f"Ciqual DOI {source.get('datasetDoi')}"
                if source_type == "ciqual"
                else ("Article personnel Home Assistant" if source_type == "personal" else "Open Food Facts API v3")
            ),
            "createdAt": now,
        }
        food = {
            "id": fid,
            "sourceType": source_type,
            "sourceExternalId": external_id,
            "ownerProfileId": profile_id,
            "label": source.get("label") or "Aliment",
            "brand": source.get("brand"),
            "barcode": source.get("barcode"),
            "nutritionBasis": source.get("nutritionBasis", "per_100g"),
            "nutrientsPer100g": (
                normalize_nutrition(source.get("nutrientsPer100g"))
                if source.get("nutrientsPer100g") is not None else None
            ),
            "nutrientsPerUnit": (
                normalize_nutrition(source.get("nutrientsPerUnit"))
                if source.get("nutrientsPerUnit") is not None else None
            ),
            "servingDefinition": {
                "unitLabel": source.get("servingSize"),
                "gramsEquivalent": _number(source.get("servingQuantityG")),
            },
            "servingDefinitions": deepcopy(source.get("servingDefinitions") or []),
            "provenanceId": pid,
            "derivedFromFoodRefId": None,
            "revision": 1,
            "createdAt": now,
            "updatedAt": now,
            "archivedAt": None,
            "legacyRef": deepcopy(source.get("legacyRef")),
        }
        return food, provenance

    def build_consumed_snapshot(
        self,
        food: dict[str, Any],
        quantity_value: float,
        quantity_unit: str,
        portion_id: str | None = None,
    ) -> tuple[dict[str, float | None], float | None, str, dict[str, Any] | None]:
        unit = (quantity_unit or "").strip().lower()
        if unit in {"g", "gram", "grams", "gramme", "grammes"}:
            grams = float(quantity_value)
            if food.get("nutrientsPer100g") is None:
                raise ValueError("This personal article has no nutrition values per 100 g")
            return scale_nutrition(food["nutrientsPer100g"], grams), grams, "scaled_by_weight", None
        if portion_id:
            portion = next(
                (item for item in food.get("servingDefinitions", []) if item.get("id") == portion_id),
                None,
            )
            if portion is None:
                raise ValueError("Unknown or unavailable portion")
            grams_per_unit = _number(portion.get("gramsEquivalent"))
            if grams_per_unit is not None:
                if food.get("nutrientsPer100g") is None:
                    raise ValueError("This portion has a weight but the food has no values per 100 g")
                grams = float(quantity_value) * grams_per_unit
                return scale_nutrition(food["nutrientsPer100g"], grams), grams, "scaled_by_sourced_portion", portion
            if food.get("nutrientsPerUnit") is not None:
                return scale_unit_nutrition(food["nutrientsPerUnit"], float(quantity_value)), None, "scaled_by_personal_unit", portion
            raise ValueError("This portion has no reliable weight or per-unit nutrition")
        grams_per_unit = _number((food.get("servingDefinition") or {}).get("gramsEquivalent"))
        if unit in {"portion", "unit", "unite", "unité"} and grams_per_unit:
            grams = float(quantity_value) * grams_per_unit
            return scale_nutrition(food["nutrientsPer100g"], grams), grams, "scaled_by_weight", None
        raise ValueError("Quantity must be in grams, or a known serving weight is required")

    def build_calculation_provenance(
        self,
        food: dict[str, Any],
        method: str,
        quantity_value: float,
        quantity_unit: str,
        grams_equivalent: float | None,
        portion: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        now = utc_now_iso()
        return {
            "id": str(uuid.uuid4()),
            "sourceType": food.get("sourceType"),
            "sourceExternalId": food.get("sourceExternalId"),
            "sourceVersion": portion.get("sourceVersion") if portion else None,
            "retrievedAt": None,
            "calculationMethod": method,
            "derivedFromProvenanceIds": [food.get("provenanceId")] if food.get("provenanceId") else [],
            "confidence": None,
            "aiModel": None,
            "notes": {
                "quantityValue": quantity_value,
                "quantityUnit": quantity_unit,
                "gramsEquivalent": grams_equivalent,
                "portionId": portion.get("id") if portion else None,
                "portionLabel": portion.get("label") if portion else None,
                "portionSourceType": portion.get("sourceType") if portion else None,
                "portionSourceExternalId": portion.get("sourceExternalId") if portion else None,
                "portionSourcePortionId": portion.get("sourcePortionId") if portion else None,
                "portionSourceUrl": portion.get("sourceUrl") if portion else None,
            },
            "createdAt": now,
        }
