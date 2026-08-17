package com.suivialimentation.android.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.suivialimentation.android.util.AppJson
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

private val Context.operationDataStore by preferencesDataStore(name = "suivi_alimentation_operations")

@Serializable
data class PendingOperation(
    val operationId: String,
    val commandType: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
)

class OperationStore(private val context: Context) {
    private val mutex = Mutex()

    suspend fun prepare(
        commandType: String,
        payload: JsonObject,
        operationId: String = UUID.randomUUID().toString(),
    ): PendingOperation = mutex.withLock {
        val operations = readUnsafe().toMutableList()
        operations.firstOrNull { it.operationId == operationId }?.let { return@withLock it }
        val operation = PendingOperation(
            operationId = operationId,
            commandType = commandType,
            payloadJson = payload.toString(),
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        operations += operation
        writeUnsafe(operations)
        operation
    }

    suspend fun list(): List<PendingOperation> = mutex.withLock { readUnsafe() }

    suspend fun complete(operationId: String) = mutex.withLock {
        writeUnsafe(readUnsafe().filterNot { it.operationId == operationId })
    }

    private suspend fun readUnsafe(): List<PendingOperation> {
        val value = context.operationDataStore.data.first()[KEY_OPERATIONS] ?: return emptyList()
        return runCatching { AppJson.decodeFromString<List<PendingOperation>>(value) }.getOrDefault(emptyList())
    }

    private suspend fun writeUnsafe(operations: List<PendingOperation>) {
        context.operationDataStore.edit { prefs ->
            if (operations.isEmpty()) prefs.remove(KEY_OPERATIONS)
            else prefs[KEY_OPERATIONS] = AppJson.encodeToString(operations)
        }
    }

    private companion object {
        val KEY_OPERATIONS = stringPreferencesKey("pending_operations_json")
    }
}
