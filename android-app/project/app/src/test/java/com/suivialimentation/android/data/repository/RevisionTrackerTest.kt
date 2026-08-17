package com.suivialimentation.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RevisionTrackerTest {
    @Test
    fun neverMovesRevisionBackwards() {
        val tracker = RevisionTracker()
        tracker.recordStoreRevision(4)
        tracker.recordStoreRevision(3)
        tracker.recordEntityRevision("meal-1", 7)
        tracker.recordEntityRevision("meal-1", 5)
        assertEquals(4L, tracker.storeRevision.value)
        assertEquals(7L, tracker.entityRevision("meal-1"))
    }
}
