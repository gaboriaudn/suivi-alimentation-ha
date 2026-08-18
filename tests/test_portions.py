"""Pure unit tests for J1.2 portion conversion."""
from __future__ import annotations

import importlib.util
import sys
import types
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).parents[1] / "custom_components" / "suivi_alimentation"
PACKAGE = "suivi_alimentation_test"


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package

core = types.ModuleType("homeassistant.core")
core.HomeAssistant = object
aiohttp_client = types.ModuleType("homeassistant.helpers.aiohttp_client")
aiohttp_client.async_get_clientsession = lambda _hass: None
sys.modules["homeassistant"] = types.ModuleType("homeassistant")
sys.modules["homeassistant.core"] = core
sys.modules["homeassistant.helpers"] = types.ModuleType("homeassistant.helpers")
sys.modules["homeassistant.helpers.aiohttp_client"] = aiohttp_client

store_v2 = types.ModuleType(f"{PACKAGE}.store_v2")
store_v2.utc_now_iso = lambda: datetime.now(timezone.utc).isoformat()
sys.modules[f"{PACKAGE}.store_v2"] = store_v2

catalog = _load(f"{PACKAGE}.portion_catalog", ROOT / "portion_catalog.py")
nutrition = _load(f"{PACKAGE}.nutrition_v2", ROOT / "nutrition_v2.py")


class PortionConversionTest(unittest.TestCase):
    def setUp(self):
        self.service = object.__new__(nutrition.NutritionService)

    def test_catalog_is_versioned_and_returns_copies(self):
        first = catalog.portions_for_ciqual("22000")
        second = catalog.portions_for_ciqual("22000")
        self.assertEqual(50.0, next(p for p in first if p["id"] == "fdc:171287:88374")["gramsEquivalent"])
        self.assertTrue(all(p["sourceVersion"] == catalog.CATALOG_VERSION for p in first))
        first[0]["gramsEquivalent"] = 999
        self.assertNotEqual(first[0]["gramsEquivalent"], second[0]["gramsEquivalent"])

    def test_sourced_portion_is_resolved_server_side(self):
        food = {
            "nutrientsPer100g": {"energyKcal": 140, "proteinG": 12},
            "nutrientsPerUnit": None,
            "servingDefinitions": catalog.portions_for_ciqual("22000"),
        }
        snapshot, grams, method, portion = self.service.build_consumed_snapshot(
            food, 2, "œuf(s)", "fdc:171287:88374"
        )
        self.assertEqual(100.0, grams)
        self.assertEqual(140.0, snapshot["energyKcal"])
        self.assertEqual(12.0, snapshot["proteinG"])
        self.assertEqual("scaled_by_sourced_portion", method)
        self.assertEqual("food_data_central", portion["sourceType"])

    def test_personal_unit_without_weight_never_invents_grams(self):
        food = {
            "nutrientsPer100g": None,
            "nutrientsPerUnit": {"energyKcal": 80, "proteinG": 4},
            "servingDefinitions": [{
                "id": "personal:test:unit",
                "label": "1 pot",
                "unitLabel": "pot",
                "gramsEquivalent": None,
                "sourceType": "user_defined",
            }],
        }
        snapshot, grams, method, _portion = self.service.build_consumed_snapshot(
            food, 2, "pot", "personal:test:unit"
        )
        self.assertIsNone(grams)
        self.assertEqual(160.0, snapshot["energyKcal"])
        self.assertEqual(8.0, snapshot["proteinG"])
        self.assertEqual("scaled_by_personal_unit", method)

    def test_unknown_portion_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unknown"):
            self.service.build_consumed_snapshot(
                {"nutrientsPer100g": {"energyKcal": 100}, "servingDefinitions": []},
                1,
                "portion",
                "fdc:missing",
            )

    def test_personal_food_search_accepts_oeuf_plural(self):
        foods = [{
            "id": "personal-egg",
            "name": "Œuf",
            "mode": "unit",
            "unitLabel": "œuf",
            "caloriesPerUnit": 70,
            "proteinsPerUnit": 6,
        }]

        results = self.service.search_personal_foods(foods, "oeufs", {})

        self.assertEqual(["Œuf"], [item["label"] for item in results])


if __name__ == "__main__":
    unittest.main()
