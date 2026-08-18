package com.suivialimentation.android.data.ha

import com.suivialimentation.android.auth.AccessTokenProvider
import com.suivialimentation.android.auth.AuthenticationRequiredException
import com.suivialimentation.android.auth.InstanceUrlPolicy
import com.suivialimentation.android.util.AppJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class HomeAssistantWebSocketClient(
    private val httpClient: OkHttpClient,
    private val tokenProvider: AccessTokenProvider,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val subscriptions = ConcurrentHashMap<String, SubscriptionSpec>()
    private val activeSubscriptionIds = ConcurrentHashMap<Int, String>()
    private val lifecycleMutex = Mutex()
    private val sendMutex = Mutex()
    private var connectionJob: Job? = null
    @Volatile private var active = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var authenticated = false
    @Volatile private var forceRefreshOnNextConnect = false

    suspend fun start() = lifecycleMutex.withLock {
        if (active) return@withLock
        active = true
        connectionJob = scope.launch { connectionLoop() }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        active = false
        socket?.close(1000, "Client disconnect")
        socket = null
        authenticated = false
        connectionJob?.cancel()
        connectionJob = null
        failPending(TransportDisconnectedException())
        activeSubscriptionIds.clear()
        _state.value = ConnectionState.Disconnected
    }

    suspend fun command(type: String, payload: JsonObject = JsonObject(emptyMap())): JsonElement {
        if (!authenticated || state.value !is ConnectionState.Connected) {
            throw TransportDisconnectedException()
        }
        return sendCommand(type, payload, subscriptionKey = null)
    }

    suspend fun subscribe(
        key: String,
        type: String,
        payload: JsonObject = JsonObject(emptyMap()),
    ): Flow<JsonElement> {
        val existing = subscriptions[key]
        if (existing != null && existing.type == type && existing.payload == payload) {
            if (authenticated && activeSubscriptionIds.none { it.value == key }) activateSubscription(existing)
            return existing.events.asSharedFlow()
        }
        if (existing != null) removeSubscription(key)
        val spec = SubscriptionSpec(key, type, payload)
        subscriptions[key] = spec
        if (authenticated) activateSubscription(spec)
        return spec.events.asSharedFlow()
    }

    fun removeSubscription(key: String) {
        subscriptions.remove(key)
        val ids = activeSubscriptionIds.filterValues { it == key }.keys
        ids.forEach(activeSubscriptionIds::remove)
    }

    private suspend fun connectionLoop() {
        var attempt = 0
        while (scope.isActive && active) {
            try {
                if (attempt == 0) {
                    _state.value = ConnectionState.Connecting
                } else {
                    val wait = reconnectDelay(attempt)
                    _state.value = ConnectionState.Reconnecting(attempt, wait)
                    delay(wait)
                }

                val session = tokenProvider.currentSession() ?: throw AuthenticationRequiredException()
                val token = tokenProvider.validAccessToken(forceRefreshOnNextConnect)
                forceRefreshOnNextConnect = false
                val outcome = openSocket(session.instanceUrl, token)
                authenticated = false
                socket = null
                failPending(TransportDisconnectedException())
                activeSubscriptionIds.clear()

                if (!active) break
                if (outcome.authenticatedOnce) attempt = 0
                when (outcome.reason) {
                    CloseReason.AUTH_INVALID -> {
                        forceRefreshOnNextConnect = true
                        attempt = 0
                    }
                    CloseReason.NORMAL, CloseReason.FAILURE -> attempt += 1
                }
            } catch (_: AuthenticationRequiredException) {
                active = false
                authenticated = false
                _state.value = ConnectionState.AuthenticationRequired
                failPending(AuthenticationRequiredException())
                break
            } catch (t: Throwable) {
                authenticated = false
                socket = null
                failPending(TransportDisconnectedException(t.message ?: "Connexion interrompue."))
                attempt += 1
            }
        }
        if (_state.value !is ConnectionState.AuthenticationRequired && _state.value !is ConnectionState.Error) {
            _state.value = ConnectionState.Disconnected
        }
    }

    private suspend fun openSocket(instanceUrl: String, token: String): SocketOutcome {
        val closed = CompletableDeferred<CloseReason>()
        val authenticatedOnce = AtomicBoolean(false)
        val wsUrl = websocketUrl(InstanceUrlPolicy.normalize(instanceUrl))
        val request = Request.Builder().url(wsUrl).build()
        socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    _state.value = ConnectionState.Authenticating
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    when (message["type"]?.jsonPrimitive?.content) {
                        "auth_required" -> {
                            val auth = buildJsonObject {
                                put("type", "auth")
                                put("access_token", token)
                            }
                            webSocket.send(auth.toString())
                        }
                        "auth_ok" -> {
                            authenticatedOnce.set(true)
                            authenticated = true
                            val version = message["ha_version"]?.jsonPrimitive?.content
                            _state.value = ConnectionState.Connected(version)
                            scope.launch { restoreSubscriptions() }
                        }
                        "auth_invalid" -> {
                            authenticated = false
                            closed.complete(CloseReason.AUTH_INVALID)
                            webSocket.close(1008, "Authentication invalid")
                        }
                        "result" -> handleResult(message)
                        "event" -> handleEvent(message)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(if (code == 1000) CloseReason.NORMAL else CloseReason.FAILURE)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(CloseReason.FAILURE)
                }
            },
        )
        return SocketOutcome(closed.await(), authenticatedOnce.get())
    }

    private suspend fun sendCommand(type: String, payload: JsonObject, subscriptionKey: String?): JsonElement {
        val (id, deferred) = sendMutex.withLock {
            val ws = socket ?: throw TransportDisconnectedException()
            val commandId = nextId.getAndIncrement()
            val commandResult = CompletableDeferred<JsonElement>()
            pending[commandId] = commandResult
            if (subscriptionKey != null) activeSubscriptionIds[commandId] = subscriptionKey
            val command = buildJsonObject {
                put("id", commandId)
                put("type", type)
                payload.forEach { (key, value) -> put(key, value) }
            }
            if (!ws.send(command.toString())) {
                pending.remove(commandId)
                activeSubscriptionIds.remove(commandId)
                throw TransportDisconnectedException("Impossible d'envoyer la commande Home Assistant.")
            }
            commandId to commandResult
        }
        return try {
            withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            if (subscriptionKey != null) activeSubscriptionIds.remove(id)
            throw t
        } finally {
            pending.remove(id, deferred)
        }
    }

    private fun handleResult(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
        val deferred = pending.remove(id) ?: return
        val success = message["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (success) {
            deferred.complete(message["result"] ?: JsonNull)
        } else {
            val error = message["error"] as? JsonObject
            val code = error?.get("code")?.jsonPrimitive?.content
            val errorMessage = error?.get("message")?.jsonPrimitive?.content ?: "Commande Home Assistant refusée."
            deferred.completeExceptionally(HomeAssistantCommandException(code, errorMessage, error))
        }
    }

    private fun handleEvent(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
        val key = activeSubscriptionIds[id] ?: return
        subscriptions[key]?.events?.tryEmit(message["event"] ?: message)
    }

    private suspend fun activateSubscription(spec: SubscriptionSpec) {
        if (!authenticated || activeSubscriptionIds.any { it.value == spec.key }) return
        try {
            sendCommand(spec.type, spec.payload, spec.key)
        } catch (t: Throwable) {
            activeSubscriptionIds.entries.removeIf { it.value == spec.key }
            throw t
        }
    }

    private suspend fun restoreSubscriptions() {
        for (spec in subscriptions.values) {
            runCatching { activateSubscription(spec) }
        }
    }

    private fun failPending(t: Throwable) {
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
    }

    private fun websocketUrl(instanceUrl: String): String {
        val prefix = when {
            instanceUrl.startsWith("https://", ignoreCase = true) -> "wss://${instanceUrl.substringAfter("://")}" 
            else -> "ws://${instanceUrl.substringAfter("://")}" 
        }
        return "$prefix/api/websocket"
    }

    private fun reconnectDelay(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 8_000L
        5 -> 16_000L
        else -> 30_000L
    }

    private data class SubscriptionSpec(
        val key: String,
        val type: String,
        val payload: JsonObject,
        val events: MutableSharedFlow<JsonElement> = MutableSharedFlow(extraBufferCapacity = 32),
    )

    private data class SocketOutcome(val reason: CloseReason, val authenticatedOnce: Boolean)

    private enum class CloseReason { NORMAL, AUTH_INVALID, FAILURE }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 30_000L
    }
}
