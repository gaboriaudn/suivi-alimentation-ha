package com.suivialimentation.android.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget

/**
 * Point d'entrée provisoire de l'onglet Ajouter.
 *
 * L'architecture détaillée de cet écran sera redéfinie avec l'utilisateur.
 * En attendant, on conserve uniquement l'action principale validée : choisir
 * le moment avant de composer un repas. Les réglages de compte et les actions
 * de transformation d'un repas existant n'ont pas leur place ici.
 */
@Composable
fun AddScreen(
    modifier: Modifier = Modifier,
    onAddMeal: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        item {
            Text("Ajouter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Choisissez le moment du repas. Vous pourrez ensuite le composer.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { MealMomentCard("Petit-déjeuner") { onAddMeal("breakfast") } }
        item { MealMomentCard("Déjeuner") { onAddMeal("lunch") } }
        item { MealMomentCard("Dîner") { onAddMeal("dinner") } }
        item { MealMomentCard("Collation") { onAddMeal("snack") } }
    }
}

@Composable
private fun MealMomentCard(label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinimumTouchTarget)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(AppSpacing.md)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
