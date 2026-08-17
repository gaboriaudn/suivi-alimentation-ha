package com.suivialimentation.android.auth

import java.net.URI

object InstanceUrlPolicy {
    fun normalize(raw: String): String {
        val candidate = raw.trim().trimEnd('/')
        val uri = runCatching { URI(candidate) }.getOrElse {
            throw IllegalArgumentException("Adresse Home Assistant invalide.")
        }
        val scheme = uri.scheme?.lowercase()
            ?: throw IllegalArgumentException("L'adresse doit commencer par https:// ou http://.")
        if (scheme != "https" && scheme != "http") {
            throw IllegalArgumentException("Seuls HTTPS et HTTP local sont acceptés.")
        }
        val host = uri.host ?: throw IllegalArgumentException("Hôte Home Assistant invalide.")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "L'adresse Home Assistant ne doit contenir ni identifiants, ni paramètres, ni fragment."
        }
        if (scheme == "http" && !isLocalOrPrivateHost(host)) {
            throw IllegalArgumentException("Une adresse HTTP n'est acceptée que sur le réseau local. Utilisez HTTPS à distance.")
        }
        return candidate
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            octets[0] == 127
    }
}
