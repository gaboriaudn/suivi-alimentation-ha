"""Administrative maintenance commands for Suivi Alimentation v2."""
from __future__ import annotations

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN

PRESERVED_COLLECTIONS = {"profilesById", "goalVersionsById"}


def _repository(hass: HomeAssistant):
    return hass.data.get(f"{DOMAIN}_repository_v2")


@callback
def async_setup(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, websocket_v2_reset_usage_data)


@websocket_api.websocket_command({
    "type": f"{DOMAIN}/v2/reset_usage_data",
    "operation_id": str,
})
@websocket_api.async_response
async def websocket_v2_reset_usage_data(hass, connection, msg) -> None:
    user = connection.user
    if not user or not user.is_admin:
        connection.send_error(msg["id"], "unauthorized", "Administrator required")
        return
    repository = _repository(hass)
    if repository is None:
        connection.send_error(msg["id"], "not_ready", "Repository unavailable")
        return

    data = repository.snapshot()
    removed: dict[str, int] = {}
    for key, value in list(data.items()):
        if key == "meta" or key in PRESERVED_COLLECTIONS:
            continue
        if isinstance(value, dict):
            removed[key] = len(value)
            data[key] = {}

    meta = data.setdefault("meta", {})
    meta["sourceFingerprint"] = None
    meta["lastMigrationAt"] = None
    meta["lastMigrationReport"] = None

    try:
        result = await repository.async_replace_shadow_snapshot(
            data,
            operation_id=msg["operation_id"],
            event={
                "entityType": "store",
                "entityId": "v2",
                "operation": "reset_usage_data",
                "profileId": None,
            },
        )
        result["removed"] = removed
        result["preservedProfiles"] = len(data.get("profilesById", {}))
        result["preservedGoalVersions"] = len(data.get("goalVersionsById", {}))
        connection.send_result(msg["id"], result)
    except Exception as err:
        connection.send_error(msg["id"], "operation_failed", str(err))
