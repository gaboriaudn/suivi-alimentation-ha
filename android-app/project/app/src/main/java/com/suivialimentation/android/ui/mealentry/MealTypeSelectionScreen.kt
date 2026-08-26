package com.suivialimentation.android.ui.mealentry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget

private val selectableMealTypes = listOf(
    "breakfast" to "Petit-déjeuner",
    "lunch" to "Déjeuner",
    "dinner" to "Dîner",
    "snack" to "Collation",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealTypeSelectionScreen(
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajouter un repas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            item {
                Text(
                    "Quel repas souhaitez-vous renseigner ?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Text(
                    "Choisissez d'abord le moment du repas. Les aliments, recettes, repas types, le scanner et la photo seront proposés à l'étape suivante.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            selectableMealTypes.forEach { (value, label) ->
                item(key = value) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinimumTouchTarget)
                            .clickable { onSelect(value) },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                        ) {
                            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Continuer vers la composition du repas", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
