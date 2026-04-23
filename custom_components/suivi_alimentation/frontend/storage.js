// storage.js - v0.21
// Charge et sauvegarde les données du profil lié à l'utilisateur HA connecté

import { toNumber } from "./utils.js";

// Identifiant du profil actif (peut être changé par l'admin)
let _activeProfileId = null;

export function setActiveProfileId(id) {
  _activeProfileId = id;
}

export function getActiveProfileId() {
  return _activeProfileId;
}

/**
 * Charge les données du profil lié à l'utilisateur HA connecté.
 * Si admin, peut passer un profileId pour charger un autre profil.
 */
export async function loadCaloriesData(hass, profileId = null) {
  if (!hass) throw new Error("hass non disponible");

  try {
    // Utiliser la nouvelle commande qui détecte l'utilisateur connecté
    const res = await hass.callWS({ type: "suivi_alimentation/get_profile_for_user" });
    const profile = res.profile;

    // Mémoriser si l'utilisateur est admin et l'ID du profil actif
    if (!_activeProfileId) {
      _activeProfileId = profile.id;
    }

    // Si un profileId spécifique est demandé (admin qui switche)
    if (profileId && profileId !== profile.id) {
      const specificProfile = await hass.callWS({
        type: "suivi_alimentation/get_profile_data",
        profile_id: profileId,
      });
      _activeProfileId = profileId;
      return _extractProfileData(specificProfile);
    }

    _activeProfileId = profile.id;
    return {
      ..._extractProfileData(profile),
      isAdmin: res.is_admin,
      haUserId: res.ha_user_id,
    };

  } catch (e) {
    // Fallback : ancienne méthode get_data
    console.warn("Suivi Alimentation: fallback get_data", e);
    const data = await hass.callWS({ type: "suivi_alimentation/get_data" });

    if (data?.profiles) {
      const profile = data.profiles["default"] || Object.values(data.profiles)[0];
      _activeProfileId = profile?.id || "default";
      return _extractProfileData(profile);
    }

    // Très ancien format
    _activeProfileId = "default";
    return {
      goal:          data.goal === undefined ? 2000 : toNumber(data.goal, 2000),
      proteinGoal:   data.proteinGoal ?? 100,
      foods:         Array.isArray(data.foods) ? data.foods : [],
      entriesByDate: typeof data.entriesByDate === "object" && !Array.isArray(data.entriesByDate)
        ? data.entriesByDate : {},
    };
  }
}

function _extractProfileData(profile) {
  return {
    goal:          profile.goal ?? 2000,
    proteinGoal:   profile.proteinGoal ?? 100,
    foods:         Array.isArray(profile.foods) ? profile.foods : [],
    entriesByDate: typeof profile.entriesByDate === "object" && !Array.isArray(profile.entriesByDate)
      ? profile.entriesByDate : {},
  };
}

/**
 * Sauvegarde les données dans le profil actif.
 */
export async function saveCaloriesData(hass, data) {
  if (!hass) return { ok: true, testMode: true };

  const profileId = _activeProfileId || "default";

  try {
    // Essayer la nouvelle API save_profile_data
    await hass.callWS({
      type: "suivi_alimentation/save_profile_data",
      profile_id: profileId,
      data: {
        goal:          data.goal,
        proteinGoal:   data.proteinGoal,
        foods:         data.foods,
        entriesByDate: data.entriesByDate,
      },
    });
    return { ok: true, testMode: false };

  } catch (e) {
    // Fallback : ancienne méthode save_data
    console.warn("Suivi Alimentation: fallback save_data", e);
    const current = await hass.callWS({ type: "suivi_alimentation/get_data" });

    if (current?.profiles) {
      const updatedData = {
        ...current,
        profiles: {
          ...current.profiles,
          [profileId]: {
            ...(current.profiles[profileId] || {}),
            goal:          data.goal,
            proteinGoal:   data.proteinGoal,
            foods:         data.foods,
            entriesByDate: data.entriesByDate,
          },
        },
      };
      await hass.callWS({ type: "suivi_alimentation/save_data", data: updatedData });
    } else {
      await hass.callWS({ type: "suivi_alimentation/save_data", data });
    }
    return { ok: true, testMode: false };
  }
}

/**
 * Charge la liste de tous les profils (pour l'admin).
 */
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
