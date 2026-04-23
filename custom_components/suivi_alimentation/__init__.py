"""Suivi Alimentation integration - v0.20"""
from __future__ import annotations

import logging
import os

from homeassistant.core import HomeAssistant
from homeassistant.config_entries import ConfigEntry
from homeassistant.components import panel_custom
from homeassistant.components.http import StaticPathConfig

from .const import DOMAIN, PANEL_URL, PANEL_TITLE, PANEL_ICON, PANEL_FILENAME
from .store import SuiviAlimentationStore
from .websocket import async_setup as async_setup_websocket

_LOGGER = logging.getLogger(__name__)

FRONTEND_DIR = os.path.join(os.path.dirname(__file__), "frontend")
CARD_FILENAME = "suivi-alimentation-card.js"


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Set up the Suivi Alimentation component."""
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Suivi Alimentation from a config entry."""

    # ── Store ──────────────────────────────────────────────────────────────
    store = SuiviAlimentationStore(hass)
    await store.async_load()
    hass.data[DOMAIN] = store
    _LOGGER.info("Suivi Alimentation v0.20: données chargées")

    # ── WebSocket ──────────────────────────────────────────────────────────
    async_setup_websocket(hass)
    _LOGGER.info("Suivi Alimentation v0.20: WebSocket initialisé")

    # ── Fichiers statiques ─────────────────────────────────────────────────
    panel_url = f"/{DOMAIN}_panel"

    try:
        await hass.http.async_register_static_paths([
            StaticPathConfig(panel_url, FRONTEND_DIR, cache_headers=False)
        ])
        _LOGGER.info("Suivi Alimentation: fichiers statiques → %s", panel_url)
    except Exception as err:
        _LOGGER.error("Suivi Alimentation: erreur fichiers statiques: %s", err)
        return False

    # ── Panneau latéral ────────────────────────────────────────────────────
    try:
        if PANEL_URL not in hass.data.get("frontend_panels", {}):
            await panel_custom.async_register_panel(
                hass,
                webcomponent_name="calorie-tracker-panel",
                frontend_url_path=PANEL_URL,
                sidebar_title=PANEL_TITLE,
                sidebar_icon=PANEL_ICON,
                module_url=f"{panel_url}/{PANEL_FILENAME}",
                embed_iframe=False,
                require_admin=False,
            )
            _LOGGER.info("Suivi Alimentation: panneau enregistré → /%s", PANEL_URL)
        else:
            _LOGGER.info("Suivi Alimentation: panneau déjà enregistré")
    except Exception as err:
        _LOGGER.warning("Suivi Alimentation: panneau: %s", err)

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    hass.data.pop(DOMAIN, None)
    return True
