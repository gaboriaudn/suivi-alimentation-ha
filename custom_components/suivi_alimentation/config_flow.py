"""Config flow for Suivi Alimentation - v0.22c"""
from __future__ import annotations

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.data_entry_flow import FlowResult

from .const import DOMAIN
from .store import calculate_nutrition_goals


class SuiviAlimentationConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Suivi Alimentation."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict | None = None
    ) -> FlowResult:
        if self._async_current_entries():
            return self.async_abort(reason="single_instance_allowed")
        if user_input is not None:
            return self.async_create_entry(title="Suivi Alimentation", data={})
        return self.async_show_form(step_id="user", data_schema=vol.Schema({}))

    @staticmethod
    def async_get_options_flow(config_entry):
        return SuiviAlimentationOptionsFlow(config_entry)


class SuiviAlimentationOptionsFlow(config_entries.OptionsFlow):
    """Handle options flow for managing profiles."""

    def __init__(self, config_entry) -> None:
        self._config_entry = config_entry
        # Données partagées création + modification
        self._profile_data = {}
        self._calculated_goals = None
        self._editing_profile_id = None
        self._mode = "create"  # "create" ou "edit"

    async def _get_ha_users(self) -> dict:
        users = await self.hass.auth.async_get_users()
        return {
            u.id: u.name or u.id
            for u in users
            if not u.system_generated
        }

    async def async_step_init(self, user_input=None) -> FlowResult:
        return await self.async_step_menu(user_input)

    # ── Menu principal ─────────────────────────────────────────

    async def async_step_menu(self, user_input=None) -> FlowResult:
        store = self.hass.data.get(DOMAIN)
        profiles = store.get_profiles() if store else {}

        profiles_display = "\n".join(
            f"• {p.get('name', 'Profil')} "
            f"({'lié à un compte HA' if p.get('ha_user_id') else 'non lié'})"
            for p in profiles.values()
        ) or "Aucun profil"

        if user_input is not None:
            action = user_input.get("action")
            if action == "add":
                self._mode = "create"
                self._profile_data = {}
                self._calculated_goals = None
                self._editing_profile_id = None
                return await self.async_step_profil_infos()
            if action == "edit":
                self._mode = "edit"
                self._calculated_goals = None
                return await self.async_step_choisir_profil()
            if action == "link":
                return await self.async_step_lier_profil()
            if action == "delete":
                return await self.async_step_supprimer_profil()
            return self.async_create_entry(title="", data={})

        return self.async_show_form(
            step_id="menu",
            data_schema=vol.Schema({
                vol.Required("action", default="add"): vol.In({
                    "add":    "➕ Créer un nouveau profil",
                    "edit":   "✏️  Modifier un profil existant",
                    "link":   "🔗 Lier un profil à un compte Home Assistant",
                    "delete": "🗑️  Supprimer un profil",
                    "done":   "✅ Terminer",
                }),
            }),
            description_placeholders={"profils": profiles_display},
        )

    # ── Choisir le profil à modifier ───────────────────────────

    async def async_step_choisir_profil(self, user_input=None) -> FlowResult:
        """Sélectionner quel profil on veut modifier."""
        store = self.hass.data.get(DOMAIN)
        profiles = store.get_profiles() if store else {}

        if user_input is not None:
            self._editing_profile_id = user_input["profil"]
            # Pré-remplir les données avec les valeurs actuelles du profil
            profile = profiles.get(self._editing_profile_id, {})
            self._profile_data = {
                "name":          profile.get("name", ""),
                "age":           profile.get("age"),
                "sex":           profile.get("sex"),
                "weight":        profile.get("weight"),
                "height":        profile.get("height"),
                "target_weight": profile.get("targetWeight"),
                "activity":      profile.get("activity"),
                "ha_user_id":    profile.get("ha_user_id"),
                "goal":          profile.get("goal", 2000),
                "proteinGoal":   profile.get("proteinGoal", 100),
                "bmi":           profile.get("bmi"),
                "idealWeight":   profile.get("idealWeight"),
            }
            return await self.async_step_profil_infos()

        return self.async_show_form(
            step_id="choisir_profil",
            data_schema=vol.Schema({
                vol.Required("profil"): vol.In({
                    pid: p.get("name", "Profil")
                    for pid, p in profiles.items()
                }),
            }),
        )

    # ── Étapes communes création / modification ────────────────

    async def async_step_profil_infos(self, user_input=None) -> FlowResult:
        """Étape 1 : Informations personnelles."""
        errors = {}
        current = self._profile_data

        if user_input is not None:
            if not user_input.get("prenom", "").strip():
                errors["prenom"] = "Le prénom est requis"
            if not errors:
                self._profile_data.update({
                    "name":          user_input["prenom"].strip(),
                    "age":           user_input["age"],
                    "sex":           user_input["sexe"],
                    "weight":        user_input["poids"],
                    "height":        user_input["taille"],
                    "target_weight": user_input["poids_cible"],
                })
                # Réinitialiser le calcul car les données ont changé
                self._calculated_goals = None
                return await self.async_step_profil_activite()

        # Valeurs par défaut (pré-remplissage en mode édition)
        defaults = {
            "prenom":       current.get("name", ""),
            "age":          current.get("age") or 30,
            "sexe":         current.get("sex") or "male",
            "poids":        current.get("weight") or 70.0,
            "taille":       current.get("height") or 170.0,
            "poids_cible":  current.get("target_weight") or current.get("weight") or 70.0,
        }

        return self.async_show_form(
            step_id="profil_infos",
            data_schema=vol.Schema({
                vol.Required("prenom",      default=defaults["prenom"]):      str,
                vol.Required("age",         default=defaults["age"]):          vol.All(vol.Coerce(int),   vol.Range(min=10, max=120)),
                vol.Required("sexe",        default=defaults["sexe"]):         vol.In({"male": "Homme", "female": "Femme"}),
                vol.Required("poids",       default=defaults["poids"]):        vol.All(vol.Coerce(float), vol.Range(min=20, max=300)),
                vol.Required("taille",      default=defaults["taille"]):       vol.All(vol.Coerce(float), vol.Range(min=100, max=250)),
                vol.Required("poids_cible", default=defaults["poids_cible"]):  vol.All(vol.Coerce(float), vol.Range(min=20, max=300)),
            }),
            errors=errors,
        )

    async def async_step_profil_activite(self, user_input=None) -> FlowResult:
        """Étape 2 : Niveau d'activité physique."""
        current_activity = self._profile_data.get("activity") or "never"

        if user_input is not None:
            self._profile_data["activity"] = user_input["activite"]
            self._calculated_goals = None
            return await self.async_step_profil_objectif()

        return self.async_show_form(
            step_id="profil_activite",
            data_schema=vol.Schema({
                vol.Required("activite", default=current_activity): vol.In({
                    "never":    "🛋️  Sédentaire (jamais de sport)",
                    "light":    "🚶 Légèrement actif (1-2x/semaine)",
                    "moderate": "🏃 Modérément actif (3-4x/semaine)",
                    "active":   "💪 Très actif (5x+/semaine)",
                }),
            }),
        )

    async def async_step_profil_objectif(self, user_input=None) -> FlowResult:
        """Étape 3 : Objectif avec calcul automatique."""
        if self._calculated_goals is None:
            self._calculated_goals = calculate_nutrition_goals(
                age=self._profile_data["age"],
                sex=self._profile_data["sex"],
                weight=self._profile_data["weight"],
                height=self._profile_data["height"],
                target_weight=self._profile_data.get("target_weight", self._profile_data["weight"]),
                activity=self._profile_data["activity"],
            )

        goals = self._calculated_goals
        cal = goals["calories"]; prot = goals["proteins"]
        bmi = goals["bmi"]; ideal = goals["idealWeight"]; tdee = goals["tdee"]

        bmi_label = "Normal"
        if bmi < 18.5:  bmi_label = "Insuffisance pondérale"
        elif bmi >= 30: bmi_label = "Obésité"
        elif bmi >= 25: bmi_label = "Surpoids"

        description = (
            f"📊 Résultats calculés\n\n"
            f"• IMC : {bmi} ({bmi_label})\n"
            f"• Poids idéal : {ideal} kg\n"
            f"• Dépense journalière estimée : {tdee} kcal\n\n"
            f"🏋️ Prise de masse : {cal['bulk']} kcal / {prot['bulk']} g prot.\n"
            f"⚖️  Stabilisation : {cal['maintain']} kcal / {prot['maintain']} g prot.\n"
            f"🎯 Perte de poids : {cal['cut']} kcal / {prot['cut']} g prot."
        )

        if user_input is not None:
            objectif = user_input.get("objectif", "maintain")
            self._profile_data.update({
                "goal":        cal[objectif],
                "proteinGoal": prot[objectif],
                "bmi":         bmi,
                "idealWeight": ideal,
            })
            return await self.async_step_profil_utilisateur_ha()

        return self.async_show_form(
            step_id="profil_objectif",
            data_schema=vol.Schema({
                vol.Required("objectif", default="maintain"): vol.In({
                    "bulk":     f"🏋️  Prise de masse ({cal['bulk']} kcal / {prot['bulk']} g prot.)",
                    "maintain": f"⚖️  Stabilisation ({cal['maintain']} kcal / {prot['maintain']} g prot.)",
                    "cut":      f"🎯 Perte de poids ({cal['cut']} kcal / {prot['cut']} g prot.)",
                }),
            }),
            description_placeholders={"resultats": description},
        )

    async def async_step_profil_utilisateur_ha(self, user_input=None) -> FlowResult:
        """Étape 4 : Liaison compte Home Assistant."""
        ha_users = await self._get_ha_users()
        current_ha = self._profile_data.get("ha_user_id") or "aucun"

        user_options = {"aucun": "— Aucun lien (profil partagé) —"}
        user_options.update({uid: name for uid, name in ha_users.items()})

        if user_input is not None:
            ha_user_id = user_input.get("utilisateur_ha")
            self._profile_data["ha_user_id"] = ha_user_id if ha_user_id != "aucun" else None

            store = self.hass.data.get(DOMAIN)
            if store:
                if self._mode == "edit" and self._editing_profile_id:
                    # Mise à jour du profil existant
                    await store.update_profile(self._editing_profile_id, {
                        "name":         self._profile_data["name"],
                        "age":          self._profile_data["age"],
                        "sex":          self._profile_data["sex"],
                        "weight":       self._profile_data["weight"],
                        "height":       self._profile_data["height"],
                        "targetWeight": self._profile_data.get("target_weight"),
                        "activity":     self._profile_data["activity"],
                        "bmi":          self._profile_data.get("bmi"),
                        "idealWeight":  self._profile_data.get("idealWeight"),
                        "goal":         self._profile_data["goal"],
                        "proteinGoal":  self._profile_data["proteinGoal"],
                        "ha_user_id":   self._profile_data.get("ha_user_id"),
                    })
                else:
                    # Création d'un nouveau profil
                    await store.add_profile({
                        "name":         self._profile_data["name"],
                        "age":          self._profile_data["age"],
                        "sex":          self._profile_data["sex"],
                        "weight":       self._profile_data["weight"],
                        "height":       self._profile_data["height"],
                        "targetWeight": self._profile_data.get("target_weight"),
                        "activity":     self._profile_data["activity"],
                        "bmi":          self._profile_data.get("bmi"),
                        "idealWeight":  self._profile_data.get("idealWeight"),
                        "goal":         self._profile_data["goal"],
                        "proteinGoal":  self._profile_data["proteinGoal"],
                        "ha_user_id":   self._profile_data.get("ha_user_id"),
                    })

            # Réinitialiser
            self._profile_data = {}
            self._calculated_goals = None
            self._editing_profile_id = None
            self._mode = "create"
            return self.async_create_entry(title="", data={})

        return self.async_show_form(
            step_id="profil_utilisateur_ha",
            data_schema=vol.Schema({
                vol.Required("utilisateur_ha", default=current_ha): vol.In(user_options),
            }),
            description_placeholders={
                "info": (
                    "Lier ce profil à un compte Home Assistant permet de le charger "
                    "automatiquement quand cet utilisateur ouvre l'application.\n"
                    "Vous pouvez laisser 'Aucun lien' et configurer cela plus tard."
                )
            },
        )

    # ── Liaison rapide profil ↔ utilisateur HA ─────────────────

    async def async_step_lier_profil(self, user_input=None) -> FlowResult:
        """Lier rapidement un profil existant à un compte HA."""
        store = self.hass.data.get(DOMAIN)
        profiles = store.get_profiles() if store else {}
        ha_users = await self._get_ha_users()

        if not profiles:
            return self.async_show_form(
                step_id="lier_profil",
                data_schema=vol.Schema({}),
                description_placeholders={"info": "⚠️ Aucun profil créé."},
                errors={"base": "no_profiles"},
            )

        if user_input is not None:
            ha_user_id = user_input["utilisateur_ha"]
            await store.update_profile(user_input["profil"], {
                "ha_user_id": ha_user_id if ha_user_id != "aucun" else None
            })
            return self.async_create_entry(title="", data={})

        user_options = {"aucun": "— Supprimer le lien —"}
        user_options.update({uid: name for uid, name in ha_users.items()})

        return self.async_show_form(
            step_id="lier_profil",
            data_schema=vol.Schema({
                vol.Required("profil"):         vol.In({pid: p.get("name","Profil") for pid, p in profiles.items()}),
                vol.Required("utilisateur_ha"): vol.In(user_options),
            }),
            description_placeholders={
                "info": "Sélectionnez le profil à lier et le compte Home Assistant correspondant."
            },
        )

    # ── Suppression de profil ──────────────────────────────────

    async def async_step_supprimer_profil(self, user_input=None) -> FlowResult:
        store = self.hass.data.get(DOMAIN)
        profiles = store.get_profiles() if store else {}

        if len(profiles) <= 1:
            return self.async_show_form(
                step_id="supprimer_profil",
                data_schema=vol.Schema({}),
                description_placeholders={"info": "⚠️ Impossible de supprimer le dernier profil."},
                errors={"base": "dernier_profil"},
            )

        if user_input is not None:
            await store.delete_profile(user_input["profil"])
            return self.async_create_entry(title="", data={})

        return self.async_show_form(
            step_id="supprimer_profil",
            data_schema=vol.Schema({
                vol.Required("profil"): vol.In({
                    pid: p.get("name", "Profil")
                    for pid, p in profiles.items()
                }),
            }),
            description_placeholders={
                "info": "⚠️ Cette action est irréversible. Toutes les données du profil seront perdues."
            },
        )