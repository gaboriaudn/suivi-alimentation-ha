package com.suivialimentation.android.data.ha

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Authenticating : ConnectionState
    data class Connected(val haVersion: String?) : ConnectionState
    data class Reconnecting(val attempt: Int, val delayMillis: Long) : ConnectionState
    data object AuthenticationRequired : ConnectionState
    data class Error(val message: String) : ConnectionState
}
