"""Versioned, source-backed usual portions for selected Ciqual foods."""
from __future__ import annotations

from copy import deepcopy
from typing import Any

CATALOG_VERSION = "fdc-sr-legacy-2019.04-j1.2.1"
FDC_SOURCE_URL = "https://fdc.nal.usda.gov/"


def _fdc_portion(
    ciqual_code: str,
    fdc_id: int,
    portion_id: int,
    label: str,
    grams: float,
    unit_label: str,
) -> dict[str, Any]:
    return {
        "id": f"fdc:{fdc_id}:{portion_id}",
        "label": label,
        "unitLabel": unit_label,
        "gramsEquivalent": float(grams),
        "sourceType": "food_data_central",
        "sourceExternalId": str(fdc_id),
        "sourcePortionId": str(portion_id),
        "sourceVersion": CATALOG_VERSION,
        "sourceUrl": f"{FDC_SOURCE_URL}food-details/{fdc_id}/nutrients",
        "ciqualCode": ciqual_code,
    }


# Only explicit Ciqual-to-FDC links reviewed for the same food and preparation
# are included. A missing mapping intentionally means "grams only".
_CIQUAL_PORTIONS: dict[str, list[dict[str, Any]]] = {
    "22000": [
        _fdc_portion("22000", 171287, 88379, "1 petit œuf", 38, "œuf(s)"),
        _fdc_portion("22000", 171287, 88378, "1 œuf moyen", 44, "œuf(s)"),
        _fdc_portion("22000", 171287, 88374, "1 gros œuf", 50, "œuf(s)"),
        _fdc_portion("22000", 171287, 88375, "1 très gros œuf", 56, "œuf(s)"),
        _fdc_portion("22000", 171287, 88376, "1 œuf jumbo", 63, "œuf(s)"),
    ],
    "22010": [
        _fdc_portion("22010", 173424, 92500, "1 gros œuf dur", 50, "œuf(s)"),
    ],
    "7111": [
        _fdc_portion("7111", 172688, 91108, "1 tranche", 32, "tranche(s)"),
    ],
    "7200": [
        _fdc_portion("7200", 174924, 95178, "1 tranche très fine", 15, "tranche(s)"),
        _fdc_portion("7200", 174924, 95176, "1 tranche fine", 20, "tranche(s)"),
        _fdc_portion("7200", 174924, 95173, "1 grande tranche", 30, "tranche(s)"),
    ],
}


def portions_for_ciqual(ciqual_code: str) -> list[dict[str, Any]]:
    """Return an isolated copy so callers cannot mutate the catalog."""
    return deepcopy(_CIQUAL_PORTIONS.get(str(ciqual_code).strip(), []))
