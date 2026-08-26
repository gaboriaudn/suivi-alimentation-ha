package com.suivialimentation.android.ui.mealentry

import com.suivialimentation.android.data.model.Meal
import com.suivialimentation.android.data.repository.MealWithItems
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

    @Test
    fun `offers the most recent validated meal of the selected type`() {
        val older = meal("older", "breakfast", "validated", "2026-08-18T06:00:00Z")
        val newer = meal("newer", "breakfast", "validated", "2026-08-18T07:00:00Z")
        val draft = meal("draft", "breakfast", "draft", "2026-08-18T08:00:00Z")
        val lunch = meal("lunch", "lunch", "validated", "2026-08-18T12:00:00Z")

        val selected = matchingMealForType(listOf(older, newer, draft, lunch), "breakfast")

        assertEquals("newer", selected?.meal?.id)
        assertNull(matchingMealForType(listOf(lunch), "breakfast"))
    }

    @Test
    fun `resumes the most recent draft before creating another meal`() {
        val olderDraft = meal("draft-old", "breakfast", "draft", "2026-08-18T06:00:00Z")
        val newerDraft = meal("draft-new", "breakfast", "draft", "2026-08-18T07:00:00Z")
        val validated = meal("validated", "breakfast", "validated", "2026-08-18T08:00:00Z")

        val selected = matchingDraftForType(listOf(olderDraft, newerDraft, validated), "breakfast")

        assertEquals("draft-new", selected?.meal?.id)
        assertNull(matchingDraftForType(listOf(validated), "breakfast"))
    }

    private fun meal(id: String, type: String, status: String, createdAt: String) = MealWithItems(
        meal = Meal(
            id = id,
            profileId = "p1",
            mealType = type,
            status = status,
            consumptionLocalDate = "2026-08-18",
            createdAt = createdAt,
            revision = 1,
        ),
        items = emptyList(),
    )
}
