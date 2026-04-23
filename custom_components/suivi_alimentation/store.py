"""Storage management for Suivi Alimentation - v0.21"""
from __future__ import annotations

import json
import logging
import os
import uuid

from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store

from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

STORAGE_VERSION = 1
STORAGE_KEY = DOMAIN

DEFAULT_PROFILE_ID = "default"


def _make_profile(
    name: str = "Mon profil",
    goal: int = 2000,
    protein_goal: int = 100,
    age: int | None = None,
    sex: str | None = None,
    weight: float | None = None,
    height: float | None = None,
    target_weight: float | None = None,
    activity: str | None = None,
    bmi: float | None = None,
    ideal_weight: float | None = None,
    ha_user_id: str | None = None,
) -> dict:
    """Create a new profile dict."""
    return {
        "id": str(uuid.uuid4()),
        "name": name,
        "ha_user_id": ha_user_id,
        "age": age,
        "sex": sex,
        "weight": weight,
        "height": height,
        "targetWeight": target_weight,
        "activity": activity,
        "bmi": bmi,
        "idealWeight": ideal_weight,
        "goal": goal,
        "proteinGoal": protein_goal,
        "foods": [],
        "entriesByDate": {},
    }


def calculate_nutrition_goals(
    age: int,
    sex: str,
    weight: float,
    height: float,
    target_weight: float,
    activity: str,
) -> dict:
    """Calculate nutrition goals using Mifflin-St Jeor formula."""
    if sex == "male":
        bmr = 10 * weight + 6.25 * height - 5 * age + 5
    else:
        bmr = 10 * weight + 6.25 * height - 5 * age - 161

    activity_multipliers = {
        "never":    1.2,
        "light":    1.375,
        "moderate": 1.55,
        "active":   1.725,
    }
    multiplier = activity_multipliers.get(activity, 1.2)
    tdee = round(bmr * multiplier)

    proteins = {
        "bulk":     round(weight * 2.0),
        "maintain": round(weight * 1.6),
        "cut":      round(weight * 1.8),
    }

    min_calories = 1200 if sex == "female" else 1500
    calories = {
        "bulk":     tdee + 300,
        "maintain": tdee,
        "cut":      max(min_calories, tdee - 400),
    }

    height_m = height / 100
    bmi = round(weight / (height_m ** 2), 1)

    if sex == "male":
        ideal_weight = round(height - 100 - (height - 150) / 4, 1)
    else:
        ideal_weight = round(height - 100 - (height - 150) / 2.5, 1)

    return {
        "tdee": tdee,
        "calories": calories,
        "proteins": proteins,
        "bmi": bmi,
        "idealWeight": ideal_weight,
    }


class SuiviAlimentationStore:
    """Manage storage for Suivi Alimentation."""

    def __init__(self, hass: HomeAssistant) -> None:
        self._store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
        self._data: dict = {}

    async def async_load(self) -> dict:
        data = await self._store.async_load()

        if data is None:
            _LOGGER.info("Suivi Alimentation v0.21: premier démarrage")
            data = await self._migrate_from_json()
            await self._store.async_save(data)
        elif self._is_old_format(data):
            _LOGGER.info("Suivi Alimentation v0.21: migration ancien format → multi-profils")
            data = self._migrate_to_multiprofile(data)
            await self._store.async_save(data)

        self._data = data
        return self._data

    def _is_old_format(self, data: dict) -> bool:
        return "goal" in data and "profiles" not in data

    def _migrate_to_multiprofile(self, old_data: dict) -> dict:
        default_profile = _make_profile(
            name="Mon profil",
            goal=old_data.get("goal", 2000),
            protein_goal=old_data.get("proteinGoal", 100),
        )
        default_profile["id"] = DEFAULT_PROFILE_ID
        default_profile["foods"] = old_data.get("foods", [])
        default_profile["entriesByDate"] = old_data.get("entriesByDate", {})
        return {"profiles": {DEFAULT_PROFILE_ID: default_profile}}

    async def _migrate_from_json(self) -> dict:
        possible_paths = [
            "/config/www/calories/data.json",
            "/config/www/calories_2/data.json",
        ]
        for path in possible_paths:
            if os.path.exists(path):
                try:
                    with open(path, encoding="utf-8") as f:
                        old_data = json.load(f)
                    default_profile = _make_profile(
                        name="Mon profil",
                        goal=old_data.get("goal", 2000),
                        protein_goal=old_data.get("proteinGoal", 100),
                    )
                    default_profile["id"] = DEFAULT_PROFILE_ID
                    default_profile["foods"] = old_data.get("foods", [])
                    default_profile["entriesByDate"] = old_data.get("entriesByDate", {})
                    _LOGGER.info("Suivi Alimentation: migration depuis %s réussie", path)
                    return {"profiles": {DEFAULT_PROFILE_ID: default_profile}}
                except Exception as err:
                    _LOGGER.error("Suivi Alimentation: erreur migration %s: %s", path, err)

        _LOGGER.info("Suivi Alimentation: données par défaut")
        default_profile = _make_profile()
        default_profile["id"] = DEFAULT_PROFILE_ID
        return {"profiles": {DEFAULT_PROFILE_ID: default_profile}}

    async def async_save(self, data: dict) -> None:
        self._data = data
        await self._store.async_save(data)

    def get_profiles(self) -> dict:
        return self._data.get("profiles", {})

    def get_profile(self, profile_id: str) -> dict | None:
        return self._data.get("profiles", {}).get(profile_id)

    def get_profile_for_user(self, ha_user_id: str) -> dict | None:
        """Return the profile linked to a HA user, or the default profile."""
        profiles = self._data.get("profiles", {})
        # Chercher un profil lié à cet utilisateur
        for profile in profiles.values():
            if profile.get("ha_user_id") == ha_user_id:
                return profile
        # Sinon retourner le profil par défaut
        return profiles.get(DEFAULT_PROFILE_ID) or (
            next(iter(profiles.values())) if profiles else None
        )

    async def add_profile(self, profile_data: dict) -> dict:
        profile = _make_profile(
            name=profile_data.get("name", "Nouveau profil"),
            goal=profile_data.get("goal", 2000),
            protein_goal=profile_data.get("proteinGoal", 100),
            age=profile_data.get("age"),
            sex=profile_data.get("sex"),
            weight=profile_data.get("weight"),
            height=profile_data.get("height"),
            target_weight=profile_data.get("targetWeight"),
            activity=profile_data.get("activity"),
            bmi=profile_data.get("bmi"),
            ideal_weight=profile_data.get("idealWeight"),
            ha_user_id=profile_data.get("ha_user_id"),
        )
        if "profiles" not in self._data:
            self._data["profiles"] = {}
        self._data["profiles"][profile["id"]] = profile
        await self._store.async_save(self._data)
        return profile

    async def update_profile(self, profile_id: str, profile_data: dict) -> bool:
        if profile_id not in self._data.get("profiles", {}):
            return False
        self._data["profiles"][profile_id].update(profile_data)
        await self._store.async_save(self._data)
        return True

    async def delete_profile(self, profile_id: str) -> bool:
        profiles = self._data.get("profiles", {})
        if profile_id not in profiles or len(profiles) <= 1:
            return False
        del self._data["profiles"][profile_id]
        await self._store.async_save(self._data)
        return True

    @property
    def data(self) -> dict:
        return self._data
