"""Idempotent v1 -> v2 shadow migrator."""
from __future__ import annotations

import hashlib
import json
import uuid
from copy import deepcopy
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from .repository import SuiviAlimentationRepository
from .store_v2 import empty_store_v2, utc_now_iso

_NS = uuid.UUID("f7fcdbe4-52e5-4cd4-83cb-21d13d569e56")


def _id(kind: str, *parts: object) -> str:
    return str(uuid.uuid5(_NS, ":".join([kind, *map(str, parts)])))


def _fp(data: dict[str, Any]) -> str:
    raw = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def _n(kcal=None, protein=None) -> dict[str, Any]:
    return {
        "energyKcal": kcal, "proteinG": protein, "carbsG": None,
        "fatG": None, "fiberG": None, "saltG": None,
    }


def _meal_type(value: str | None) -> str:
    key = (value or "").strip().lower()
    return {
        "petit-dÃ©jeuner": "breakfast", "petit dejeuner": "breakfast",
        "dÃ©jeuner": "lunch", "dejeuner": "lunch",
        "dÃ®ner": "dinner", "diner": "dinner", "collation": "snack",
    }.get(key, "other" if key else "unknown")


def _prov(pid: str, note: str, now: str) -> dict[str, Any]:
    return {
        "id": pid, "sourceType": "legacy_unknown", "sourceExternalId": None,
        "sourceVersion": None, "retrievedAt": None,
        "calculationMethod": "legacy_unknown", "derivedFromProvenanceIds": [],
        "confidence": None, "aiModel": None, "notes": note, "createdAt": now,
    }


def build_v2(v1: dict[str, Any], migration_date: str) -> tuple[dict, dict]:
    now = utc_now_iso()
    out = empty_store_v2()
    out["meta"]["createdAt"] = now
    source_cal = source_pro = 0.0
    entries = foods = 0
    by_day: dict[str, tuple[float, float]] = {}

    for profile_id, p in v1.get("profiles", {}).items():
        out["profilesById"][profile_id] = {
            "id": profile_id, "displayName": p.get("name") or "Profil",
            "ownerHaUserId": p.get("ha_user_id"), "defaultTimeZone": "Europe/Paris",
            "locale": "fr-FR", "status": "active", "revision": 1,
            "createdAt": now, "updatedAt": now, "archivedAt": None,
            "legacyRef": {
                k: p.get(k) for k in (
                    "age", "sex", "weight", "height", "targetWeight",
                    "activity", "bmi", "idealWeight"
                )
            },
        }
        gid = _id("goal", profile_id, "migration-current")
        out["goalVersionsById"][gid] = {
            "id": gid, "profileId": profile_id, "versionNumber": 1,
            "effectiveFromLocalDate": migration_date, "effectiveToLocalDate": None,
            "targets": _n(p.get("goal"), p.get("proteinGoal")),
            "sourceType": "legacy_unknown",
            "sourceDetails": {"note": "Current v1 goal; not applied retroactively."},
            "createdByHaUserId": p.get("ha_user_id"), "createdAt": now, "revision": 1,
        }

        for f in p.get("foods", []):
            legacy_id = str(f.get("id") or _id("food-name", profile_id, f.get("name")))
            fid = _id("food", profile_id, legacy_id)
            prid = _id("prov-food", profile_id, legacy_id)
            mode = f.get("mode")
            out["nutritionProvenanceById"][prid] = _prov(
                prid, "Migrated v1 food; source provenance unknown.", now
            )
            out["foodReferencesById"][fid] = {
                "id": fid, "sourceType": "legacy_unknown", "sourceExternalId": None,
                "ownerProfileId": profile_id, "label": f.get("name") or "Aliment historique",
                "brand": None, "barcode": None,
                "nutritionBasis": "per_100g" if mode == "grams" else (
                    "per_unit" if mode == "unit" else "unknown"
                ),
                "nutrientsPer100g": _n(
                    f.get("caloriesPer100g"), f.get("proteinsPer100g")
                ) if mode == "grams" else None,
                "nutrientsPerUnit": _n(
                    f.get("caloriesPerUnit"), f.get("proteinsPerUnit")
                ) if mode == "unit" else None,
                "servingDefinition": {
                    "unitLabel": f.get("unitLabel"), "gramsEquivalent": None
                },
                "defaultMealCategoryLegacy": f.get("defaultCategory"),
                "provenanceId": prid, "derivedFromFoodRefId": None,
                "revision": 1, "createdAt": now, "updatedAt": now,
                "archivedAt": None,
                "legacyRef": {"profileId": profile_id, "foodId": legacy_id},
            }
            foods += 1

        for local_date, day_entries in p.get("entriesByDate", {}).items():
            meal_ids = []
            day_cal = day_pro = 0.0
            for pos, e in enumerate(day_entries):
                eid = str(e.get("id") or _id("entry-fallback", profile_id, local_date, pos))
                mid = _id("meal", profile_id, local_date, eid)
                iid = _id("item", profile_id, local_date, eid)
                prid = _id("prov-entry", profile_id, local_date, eid)
                snap = _n(e.get("calories"), e.get("proteins"))
                created = e.get("createdAt") or now
                out["nutritionProvenanceById"][prid] = _prov(
                    prid, "Frozen values copied exactly from v1 history.", now
                )
                out["mealsById"][mid] = {
                    "id": mid, "profileId": profile_id,
                    "mealType": _meal_type(e.get("category")), "label": e.get("name"),
                    "status": "validated", "consumptionLocalDate": local_date,
                    "consumedAtUtc": None, "timeZone": None,
                    "datePrecision": "date_only_legacy",
                    "totalsSnapshot": deepcopy(snap), "goalVersionId": None,
                    "origin": "legacy_migration", "supersedesMealId": None,
                    "supersededByMealId": None, "createdAt": created,
                    "validatedAt": None, "voidedAt": None, "revision": 1,
                    "legacyRef": {"entryId": eid, "category": e.get("category")},
                }
                out["mealItemsById"][iid] = {
                    "id": iid, "mealId": mid, "kind": "manual_estimate",
                    "foodRefId": None, "recipeId": None, "recipeRevisionId": None,
                    "labelSnapshot": e.get("name") or "Ã‰lÃ©ment historique",
                    "quantityValue": e.get("quantity"), "quantityUnit": e.get("quantityUnit"),
                    "gramsEquivalent": e.get("quantity") if e.get("quantityUnit") == "g" else None,
                    "nutritionSnapshot": deepcopy(snap), "provenanceId": prid,
                    "createdFromProposalId": None, "position": 0, "revision": 1,
                    "createdAt": created, "updatedAt": created,
                }
                meal_ids.append(mid)
                c, pr = float(e.get("calories") or 0), float(e.get("proteins") or 0)
                source_cal += c; source_pro += pr; day_cal += c; day_pro += pr; entries += 1

            dkey = f"{profile_id}|{local_date}"
            out["dailyHistoryByProfileDate"][dkey] = {
                "profileId": profile_id, "localDate": local_date, "mealIds": meal_ids,
                "validatedMealCount": len(meal_ids), "totals": _n(day_cal, day_pro),
                "revision": 1, "updatedAt": now,
            }
            by_day[dkey] = (day_cal, day_pro)

    target_cal = sum(float(m["totalsSnapshot"]["energyKcal"] or 0) for m in out["mealsById"].values())
    target_pro = sum(float(m["totalsSnapshot"]["proteinG"] or 0) for m in out["mealsById"].values())
    orphan_meals = sum(m["profileId"] not in out["profilesById"] for m in out["mealsById"].values())
    orphan_items = sum(i["mealId"] not in out["mealsById"] for i in out["mealItemsById"].values())
    missing_prov = sum(
        i["provenanceId"] not in out["nutritionProvenanceById"]
        for i in out["mealItemsById"].values()
    )
    report = {
        "sourceProfileCount": len(v1.get("profiles", {})),
        "targetProfileCount": len(out["profilesById"]),
        "sourceFoodCount": foods, "targetFoodCount": len(out["foodReferencesById"]),
        "sourceEntryCount": entries, "targetMealCount": len(out["mealsById"]),
        "targetMealItemCount": len(out["mealItemsById"]),
        "sourceCaloriesTotal": source_cal, "targetCaloriesTotal": target_cal,
        "sourceProteinTotal": source_pro, "targetProteinTotal": target_pro,
        "datesCompared": len(by_day), "orphanMealCount": orphan_meals,
        "orphanMealItemCount": orphan_items, "missingProvenanceCount": missing_prov,
    }
    report["ok"] = (
        report["sourceProfileCount"] == report["targetProfileCount"]
        and foods == report["targetFoodCount"] and entries == report["targetMealCount"]
        and entries == report["targetMealItemCount"]
        and abs(source_cal - target_cal) < 1e-9 and abs(source_pro - target_pro) < 1e-9
        and orphan_meals == 0 and orphan_items == 0 and missing_prov == 0
    )
    return out, report


async def async_migrate_v1_to_v2(
    hass: HomeAssistant, v1: dict[str, Any], repository: SuiviAlimentationRepository
) -> dict[str, Any]:
    fingerprint = _fp(v1)
    meta = repository.snapshot().get("meta", {})
    if meta.get("sourceFingerprint") == fingerprint:
        report = meta.get("lastMigrationReport") or {}
        return {
            "ok": bool(report.get("ok", True)), "idempotent": True,
            "sourceFingerprint": fingerprint, "storeRevision": repository.store_revision,
            "report": report,
        }

    candidate, report = build_v2(v1, dt_util.now().date().isoformat())
    if not report["ok"]:
        return {"ok": False, "idempotent": False, "report": report,
                "error": "Integrity validation failed before Store v2 write"}

    candidate["meta"]["sourceFingerprint"] = fingerprint
    candidate["meta"]["lastMigrationAt"] = utc_now_iso()
    candidate["meta"]["lastMigrationReport"] = deepcopy(report)
    commit = await repository.async_replace_shadow_snapshot(
        candidate, operation_id=f"migration:{fingerprint}",
        expected_store_revision=repository.store_revision,
        event={"entityType": "store", "entityId": "v2",
               "operation": "shadow_migration", "entityRevision": None, "profileId": None},
    )
    return {
        "ok": True, "idempotent": commit["idempotent"],
        "sourceFingerprint": fingerprint, "storeRevision": commit["storeRevision"],
        "report": report,
    }
