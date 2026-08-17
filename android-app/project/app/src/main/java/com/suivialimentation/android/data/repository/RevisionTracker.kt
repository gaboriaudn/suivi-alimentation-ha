package com.suivialimentation.android.data.repository

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RevisionTracker {
    private val _storeRevision = MutableStateFlow<Long?>(null)
    val storeRevision: StateFlow<Long?> = _storeRevision.asStateFlow()
    private val entityRevisions = ConcurrentHashMap<String, Long>()

    fun recordStoreRevision(revision: Long) {
        val current = _storeRevision.value
        if (current == null || revision >= current) _storeRevision.value = revision
    }

    fun recordEntityRevision(entityId: String, revision: Long) {
        entityRevisions.compute(entityId) { _, current -> maxOf(current ?: revision, revision) }
    }

    fun entityRevision(entityId: String): Long? = entityRevisions[entityId]
}
