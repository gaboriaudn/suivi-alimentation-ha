package com.suivialimentation.android.ui.mealentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealEntryUxTest {
    @Test
    fun `broadens filet de poulet toward poulet first`() {
        assertEquals(listOf("poulet", "filet"), buildBroaderCiqualQueries("filet de poulet"))
    }

    @Test
    fun `recognizes raw preparation`() {
        assertEquals("CRU · poids avant cuisson", preparationLabel("Poulet, filet, cru"))
    }

    @Test
    fun `recognizes cooked preparation`() {
        assertEquals("CUIT · poids après cuisson", preparationLabel("Œuf à la coque"))
    }

    @Test
    fun `does not invent preparation when label is ambiguous`() {
        assertNull(preparationLabel("Pomme Golden"))
    }
}
