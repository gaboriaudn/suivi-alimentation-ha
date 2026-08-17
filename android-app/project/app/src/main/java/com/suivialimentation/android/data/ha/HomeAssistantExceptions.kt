package com.suivialimentation.android.data.ha

import kotlinx.serialization.json.JsonElement

class HomeAssistantCommandException(
    val commandCode: String?,
    override val message: String,
    val details: JsonElement? = null,
) : IllegalStateException(message) {
    val isConflict: Boolean
        get() {
            val haystack = listOfNotNull(commandCode, message).joinToString(" ").lowercase()
            return "conflict" in haystack || "revision" in haystack || "stale" in haystack
        }
}

class TransportDisconnectedException(message: String = "Connexion Home Assistant interrompue.") :
    IllegalStateException(message)
