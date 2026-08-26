// storage.js - v0.27
// Charge et sauvegarde les données du profil lié à l'utilisateur HA connecté

import { toNumber } from "./utils.js";

let _activeProfileId = null;

export function setActiveProfileId(id) { _activeProfileId = id; }
export function getActiveProfileId() { return _activeProfileId; }

export async function loadCaloriesData(hass, profileId = null) {
  if (!hass) throw new Error("hass non disponible");
  try {
    const res = await hass.callWS({ type: "suivi_alimentation/get_profile_for_user" });
    const profile = res.profile;
    if (!_activeProfileId) _activeProfileId = profile.id;
    if (profileId && profileId !== profile.id) {
      const specificProfile = await hass.callWS({
        type: "suivi_alimentation/get_profile_data", profile_id: profileId,
      });
      _activeProfileId = profileId;
      return _extractProfileData(specificProfile);
    }
    _activeProfileId = profile.id;
    return { ..._extractProfileData(profile), isAdmin: res.is_admin, haUserId: res.ha_user_id };
  } catch (e) {
    console.warn("Suivi Alimentation: fallback get_data", e);
    const data = await hass.callWS({ type: "suivi_alimentation/get_data" });
    if (data?.profiles) {
      const profile = data.profiles["default"] || Object.values(data.profiles)[0];
      _activeProfileId = profile?.id || "default";
      return _extractProfileData(profile);
    }
    _activeProfileId = "default";
    return {
      goal: data.goal === undefined ? 2000 : toNumber(data.goal, 2000),
      proteinGoal: data.proteinGoal ?? 100,
      foods: Array.isArray(data.foods) ? data.foods : [],
      entriesByDate: typeof data.entriesByDate === "object" && !Array.isArray(data.entriesByDate) ? data.entriesByDate : {},
    };
  }
}

function _extractProfileData(profile) {
  return {
    goal: profile.goal ?? 2000,
    proteinGoal: profile.proteinGoal ?? 100,
    foods: Array.isArray(profile.foods) ? profile.foods : [],
    entriesByDate: typeof profile.entriesByDate === "object" && !Array.isArray(profile.entriesByDate) ? profile.entriesByDate : {},
  };
}

function _withPreciseProteins(data) {
  const foods = Array.isArray(data.foods) ? data.foods : [];
  const entriesByDate = {};
  for (const [date, entries] of Object.entries(data.entriesByDate || {})) {
    entriesByDate[date] = (Array.isArray(entries) ? entries : []).map(entry => {
      const quantity = toNumber(entry.quantity, 0);
      if (quantity <= 0) return entry;
      const food = foods.find(item => item.name === entry.name);
      if (!food) return entry;
      let proteins = null;
      if (food.mode === "grams" && entry.quantityUnit === "g") {
        proteins = toNumber(food.proteinsPer100g, 0) * quantity / 100;
      } else if (food.mode !== "grams") {
        proteins = toNumber(food.proteinsPerUnit, 0) * quantity;
      }
      if (proteins === null) return entry;
      return { ...entry, proteins: Math.round(proteins * 10) / 10 };
    });
  }
  return { ...data, entriesByDate };
}

export async function saveCaloriesData(hass, data) {
  if (!hass) return { ok: true, testMode: true };
  const profileId = _activeProfileId || "default";
  const normalized = _withPreciseProteins(data);
  // Keep the caller's in-memory object coherent with what is persisted.
  data.entriesByDate = normalized.entriesByDate;
  try {
    await hass.callWS({
      type: "suivi_alimentation/save_profile_data",
      profile_id: profileId,
      data: {
        goal: normalized.goal,
        proteinGoal: normalized.proteinGoal,
        foods: normalized.foods,
        entriesByDate: normalized.entriesByDate,
      },
    });
    return { ok: true, testMode: false };
  } catch (e) {
    console.warn("Suivi Alimentation: fallback save_data", e);
    const current = await hass.callWS({ type: "suivi_alimentation/get_data" });
    if (current?.profiles) {
      const updatedData = {
        ...current,
        profiles: {
          ...current.profiles,
          [profileId]: {
            ...(current.profiles[profileId] || {}),
            goal: normalized.goal,
            proteinGoal: normalized.proteinGoal,
            foods: normalized.foods,
            entriesByDate: normalized.entriesByDate,
          },
        },
      };
      await hass.callWS({ type: "suivi_alimentation/save_data", data: updatedData });
    } else {
      await hass.callWS({ type: "suivi_alimentation/save_data", data: normalized });
    }
    return { ok: true, testMode: false };
  }
}

export async function loadAllProfiles(hass) {
  if (!hass) return [];
  try {
    const res = await hass.callWS({ type: "suivi_alimentation/get_profiles" });
    return res.profiles || [];
  } catch (e) {
    console.error("Suivi Alimentation: erreur loadAllProfiles", e);
    return [];
  }
}
