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

@Composable
fun AddScreen(
    modifier: Modifier = Modifier,
    onAddMeal: (String) -> Unit,
    onCreateFood: () -> Unit = {},
    onCreateRecipe: () -> Unit = {},
    onCreateMealTemplate: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        Text("Ajouter un repas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Choisissez le moment du repas.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            ActionCard("Petit-déjeuner", Modifier.weight(1f)) { onAddMeal("breakfast") }
            ActionCard("Déjeuner", Modifier.weight(1f)) { onAddMeal("lunch") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            ActionCard("Dîner", Modifier.weight(1f)) { onAddMeal("dinner") }
            ActionCard("Collation", Modifier.weight(1f)) { onAddMeal("snack") }
        }

        Text("Créer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = AppSpacing.md))
        Text("Enrichissez votre bibliothèque pour les prochaines saisies.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            ActionCard("Aliment", Modifier.weight(1f), onCreateFood)
            ActionCard("Recette", Modifier.weight(1f), onCreateRecipe)
        }
        ActionCard("Repas type", Modifier.fillMaxWidth(), onCreateMealTemplate)
    }
}

@Composable
private fun ActionCard(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.heightIn(min = MinimumTouchTarget).clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.md)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
