package com.suivialimentation.android.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget

/**
 * Première étape de l'ajout d'un repas.
 *
 * Cet écran reste volontairement court : il sert uniquement à choisir le
 * moment. La composition du repas s'ouvre ensuite sur un écran distinct.
 */
@Composable
fun AddScreen(
    modifier: Modifier = Modifier,
    onAddMeal: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        Text(
            "Ajouter un repas",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Choisissez le moment du repas.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            MealMomentCard("Petit-déjeuner", Modifier.weight(1f)) { onAddMeal("breakfast") }
            MealMomentCard("Déjeuner", Modifier.weight(1f)) { onAddMeal("lunch") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            MealMomentCard("Collation", Modifier.weight(1f)) { onAddMeal("snack") }
            MealMomentCard("Dîner", Modifier.weight(1f)) { onAddMeal("dinner") }
        }
    }
}

@Composable
private fun MealMomentCard(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .heightIn(min = MinimumTouchTarget)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
