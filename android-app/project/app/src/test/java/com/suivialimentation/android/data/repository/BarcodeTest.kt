package com.suivialimentation.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BarcodeTest {
    @Test
    fun normalizesScannerFormatting() {
        assertEquals("3017620422003", normalizeBarcode("3017 6204 2200 3"))
    }

    @Test
    fun rejectsTooShortCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeBarcode("1234")
        }
    }
}
