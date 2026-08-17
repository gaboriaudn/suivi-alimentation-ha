# Architecture Android MVP

Flux : Compose → ViewModel → Repository → HomeAssistantApi → Home Assistant `/api/websocket`.

Home Assistant reste la source de vérité. Android n'effectue pas les calculs nutritionnels.

Le Repository conserve les `operation_id` et les révisions, et le WebSocket se reconnecte/réabonne automatiquement.
