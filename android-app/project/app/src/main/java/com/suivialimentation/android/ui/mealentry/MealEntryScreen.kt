package com.suivialimentation.android.ui.mealentry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.suivialimentation.android.data.model.CiqualFoodCandidate
import com.suivialimentation.android.data.model.NutrientSnapshot
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealEntryScreen(
    state: MealEntryUiState,
    onSelectMealType: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectFood: (CiqualFoodCandidate) -> Unit,
    onDismissFood: () -> Unit,
    onQuantityChange: (String) -> Unit,
    onAddFood: () -> Unit,
    onValidate: () -> Unit,
    onBack: () -> Unit,
    onValidated: () -> Unit,
) {
    LaunchedEffect(state.validated) {
        if (state.validated) onValidated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.draftMeal == null) "Ajouter un repas" else "Brouillon du repas") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Retour") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Type de repas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mealTypes, key = { it.first }) { (value, label) ->
                        FilterChip(
                            selected = state.mealType == value,
                            onClick = { onSelectMealType(value) },
                            enabled = state.draftMeal == null && !state.mutating,
                            label = { Text(label) },
                        )
                    }
                }
            }

            if (state.error != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(state.error, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            item {
                Text("Rechercher dans CIQUAL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.searching && !state.mutating,
                    singleLine = true,
                    label = { Text("Aliment") },
                    placeholder = { Text("Ex. poulet, pomme, riz") },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSearch,
                    enabled = state.mealType != null && !state.searching && !state.mutating,
                ) {
                    if (state.searching) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    } else {
                        Text("Rechercher")
                    }
                }
            }

            state.selectedFood?.let { selected ->
                item {
                    SelectedFoodCard(
                        food = selected,
                        quantity = state.quantityText,
                        busy = state.mutating,
                        onQuantityChange = onQuantityChange,
                        onAdd = onAddFood,
                        onDismiss = onDismissFood,
                    )
                }
            }

            if (state.searchResults.isNotEmpty()) {
                item { Text("Résultats CIQUAL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(state.searchResults, key = { it.sourceExternalId }) { food ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(food.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("Pour 100 g : ${nutritionSummary(food.nutrientsPer100g)}", style = MaterialTheme.typography.bodySmall)
                            Text("CIQUAL ${food.sourceVersion ?: ""}".trim(), style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onSelectFood(food) }, enabled = !state.mutating) { Text("Choisir") }
                        }
                    }
                }
            }

            if (state.draftMeal != null) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text("Aliments du brouillon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (state.items.isEmpty()) {
                    item { Text("Le brouillon ne contient encore aucun aliment.") }
                } else {
                    items(state.items, key = { it.id }) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.labelSnapshot, fontWeight = FontWeight.Medium)
                                    Text("${formatNumber(item.quantityValue)} ${item.quantityUnit.orEmpty()}".trim(), style = MaterialTheme.typography.bodySmall)
                                }
                                item.nutritionSnapshot?.let { Text(nutritionSummary(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = onValidate,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.items.isNotEmpty() && !state.mutating,
                    ) {
                        if (state.mutating) CircularProgressIndicator(modifier = Modifier.height(18.dp))
                        else Text("Valider le repas")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedFoodCard(
    food: CiqualFoodCandidate,
    quantity: String,
    busy: Boolean,
    onQuantityChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(food.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("CIQUAL ${food.sourceExternalId} · pour 100 g : ${nutritionSummary(food.nutrientsPer100g)}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = quantity,
                onValueChange = onQuantityChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                label = { Text("Quantité en grammes") },
                suffix = { Text("g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, enabled = !busy && quantity.isNotBlank()) { Text("Ajouter") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuler") }
            }
        }
    }
}

private val mealTypes = listOf(
    "breakfast" to "Petit-déjeuner",
    "lunch" to "Déjeuner",
    "dinner" to "Dîner",
    "snack" to "Collation",
)

private fun nutritionSummary(snapshot: NutrientSnapshot): String = buildList {
    snapshot.energyKcal?.let { add("${formatNumber(it)} kcal") }
    snapshot.proteinG?.let { add("${formatNumber(it)} g prot.") }
}.joinToString(" · ").ifBlank { "valeurs non renseignées" }

private fun formatNumber(value: Double?): String {
    if (value == null) return "—"
    return NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = 1 }.format(value)
}
