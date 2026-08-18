"""Pure repository tests for J1.6 history corrections and deletions."""
from __future__ import annotations

import importlib.util
import sys
import types
import unittest
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).parents[1] / "custom_components" / "suivi_alimentation"
PACKAGE = "suivi_alimentation_j16_test"


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
sys.modules["homeassistant"] = types.ModuleType("homeassistant")
sys.modules["homeassistant.core"] = core

const = types.ModuleType(f"{PACKAGE}.const")
const.DOMAIN = "suivi_alimentation"
sys.modules[f"{PACKAGE}.const"] = const

store_v2 = types.ModuleType(f"{PACKAGE}.store_v2")
store_v2.SuiviAlimentationStoreV2 = object
store_v2.utc_now_iso = lambda: datetime.now(timezone.utc).isoformat()
sys.modules[f"{PACKAGE}.store_v2"] = store_v2

repository_module = _load(f"{PACKAGE}.repository", ROOT / "repository.py")
nutrition_v2 = types.ModuleType(f"{PACKAGE}.nutrition_v2")
nutrition_v2.NutritionService = object
sys.modules[f"{PACKAGE}.nutrition_v2"] = nutrition_v2
commands_module = _load(f"{PACKAGE}.nutrition_commands_v2", ROOT / "nutrition_commands_v2.py")


class FakeBus:
    def __init__(self):
        self.events = []

    def async_fire(self, event_type, payload):
        self.events.append((event_type, payload))


class FakeHass:
    def __init__(self):
        self.bus = FakeBus()


class FakeStore:
    def __init__(self, data):
        self.data = deepcopy(data)

    def snapshot(self):
        return deepcopy(self.data)

    async def async_save(self, data):
        self.data = deepcopy(data)


def fixture():
    nutrients = {
        "energyKcal": 70.0,
        "proteinG": 6.0,
        "carbsG": 0.5,
        "fatG": 5.0,
        "fiberG": 0.0,
        "saltG": 0.2,
    }
    return {
        "meta": {
            "schemaVersion": 2,
            "shadowMode": True,
            "storeRevision": 1,
            "appliedOperationIds": [],
            "appliedOperationEventsById": {},
        },
        "profilesById": {"p1": {"id": "p1", "ownerHaUserId": "u1"}},
        "goalVersionsById": {},
        "foodReferencesById": {
            "f1": {"id": "f1", "ownerProfileId": "p1", "label": "Œuf"}
        },
        "recipeDefinitionsById": {},
        "recipeRevisionsById": {},
        "mealsById": {
            "m1": {
                "id": "m1", "profileId": "p1", "mealType": "breakfast",
                "status": "validated", "consumptionLocalDate": "2026-08-18",
                "totalsSnapshot": nutrients, "origin": "manual",
                "supersedesMealId": None, "supersededByMealId": None,
                "createdAt": "2026-08-18T06:00:00+00:00",
                "updatedAt": "2026-08-18T06:01:00+00:00",
                "validatedAt": "2026-08-18T06:01:00+00:00",
                "voidedAt": None, "revision": 2,
            }
        },
        "mealItemsById": {
            "i1": {
                "id": "i1", "mealId": "m1", "kind": "food",
                "foodRefId": "f1", "labelSnapshot": "Œuf",
                "quantityValue": 1.0, "quantityUnit": "œuf",
                "nutritionSnapshot": nutrients, "provenanceId": "prov1",
                "position": 0, "revision": 1,
                "createdAt": "2026-08-18T06:00:00+00:00",
                "updatedAt": "2026-08-18T06:00:00+00:00",
            }
        },
        "nutritionProvenanceById": {"prov1": {"id": "prov1"}},
        "dailyHistoryByProfileDate": {
            "p1|2026-08-18": {
                "profileId": "p1", "localDate": "2026-08-18",
                "mealIds": ["m1"], "validatedMealCount": 1,
                "totals": nutrients, "revision": 1,
                "updatedAt": "2026-08-18T06:01:00+00:00",
            }
        },
        "favoritesById": {},
    }


class J16HistoryTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.store = FakeStore(fixture())
        self.repository = repository_module.SuiviAlimentationRepository(FakeHass(), self.store)

    async def test_correction_preserves_source_until_replacement_is_validated(self):
        correction = await self.repository.async_start_meal_correction(
            source_meal_id="m1", operation_id="start-1"
        )

        draft = correction["meal"]
        self.assertEqual("draft", draft["status"])
        self.assertEqual("m1", draft["supersedesMealId"])
        self.assertEqual("validated", self.store.data["mealsById"]["m1"]["status"])
        self.assertEqual(1, len(correction["items"]))
        self.assertNotEqual("i1", correction["items"][0]["id"])

        validated = await self.repository.async_validate_meal(
            meal_id=draft["id"], operation_id="validate-1",
            expected_meal_revision=draft["revision"],
        )

        source = self.store.data["mealsById"]["m1"]
        self.assertEqual("voided", source["status"])
        self.assertEqual(draft["id"], source["supersededByMealId"])
        self.assertEqual([draft["id"]], validated["dailyHistory"]["mealIds"])
        self.assertEqual(70.0, validated["dailyHistory"]["totals"]["energyKcal"])

    async def test_void_removes_meal_from_active_day_without_erasing_audit_record(self):
        result = await self.repository.async_void_meal(
            meal_id="m1", operation_id="void-1", expected_meal_revision=2
        )

        self.assertEqual("voided", result["meal"]["status"])
        self.assertIsNone(result["dailyHistory"])
        self.assertIn("m1", self.store.data["mealsById"])
        self.assertNotIn("p1|2026-08-18", self.store.data["dailyHistoryByProfileDate"])

    async def test_void_rejects_stale_revision(self):
        with self.assertRaises(repository_module.RevisionConflict):
            await self.repository.async_void_meal(
                meal_id="m1", operation_id="void-stale", expected_meal_revision=1
            )

    async def test_quantity_correction_recalculates_snapshot_server_side(self):
        correction = await self.repository.async_start_meal_correction(
            source_meal_id="m1", operation_id="start-quantity"
        )
        item = correction["items"][0]
        meal = correction["meal"]

        class FakeNutrition:
            def build_consumed_snapshot(self, _food, quantity, _unit, portion_id):
                portion = {
                    "id": portion_id, "unitLabel": "œuf", "label": "1 œuf"
                } if portion_id else None
                return ({"energyKcal": quantity * 70.0, "proteinG": quantity * 6.0}, quantity * 50.0, "test", portion)

            def build_calculation_provenance(self, *_args):
                return {"id": "prov-updated"}

        commands = commands_module.NutritionCommands(FakeNutrition(), self.repository)
        result = await commands.async_update_food_meal_item(
            item_id=item["id"], quantity_value=2.0, quantity_unit="œuf",
            portion_id="egg-portion", operation_id="update-quantity",
            expected_item_revision=item["revision"],
            expected_meal_revision=meal["revision"],
        )

        self.assertEqual(140.0, result["item"]["nutritionSnapshot"]["energyKcal"])
        self.assertEqual(100.0, result["item"]["gramsEquivalent"])
        self.assertEqual("prov-updated", result["item"]["provenanceId"])
        self.assertEqual(meal["revision"] + 1, result["meal"]["revision"])


if __name__ == "__main__":
    unittest.main()
