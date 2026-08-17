package com.suivialimentation.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class V2Status(
    val schemaVersion: Int,
    val shadowMode: Boolean,
    val storeRevision: Long,
    val sourceFingerprint: String? = null,
    val lastMigrationAt: String? = null,
    val lastMigrationReport: JsonObject? = null,
    val counts: JsonObject? = null,
)
