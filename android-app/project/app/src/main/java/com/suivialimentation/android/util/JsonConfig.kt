package com.suivialimentation.android.util

import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}
