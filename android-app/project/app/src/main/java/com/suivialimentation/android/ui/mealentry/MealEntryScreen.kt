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
import androidx.compose.material3.AlertDialog
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
import com.suivialimentation.android.data.model.OffProductCandidate
import com.suivialimentation.android.data.model.PersonalFoodCandidate
import com.suivialimentation.android.data.repository.QuickFood
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
    onSelectPersonalFood: (PersonalFoodCandidate) -> Unit,
    onBarcodeChange: (String) -> Unit,
    onScanBarcode: () -> Unit,
    onLookupBarcode: () -> Unit,
    onSelectOffProduct: (OffProductCandidate) -> Unit,
    onSelectQuickFood: (QuickFood) -> Unit,
    onToggleFavorite: (QuickFood) -> Unit,
    onSelectPortion: (String?) -> Unit,
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

    state.selectedFood?.let { selected ->
        QuantityDialog(
            food = selected,
            quantity = state.quantityText,
            selectedPortionId = state.selectedPortionId,
            busy = state.mutating,
            onSelectPortion = onSelectPortion,
            onQuantityChange = onQuantityChange,
            onAdd = onAddFood,
            onDismiss = onDismissFood,
        )
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

            if (state.favoriteFoods.isNotEmpty()) {
                item {
                    Text("Favoris", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(state.favoriteFoods, key = { "favorite-${it.food.id}" }) { quick ->
                    QuickFoodCard(quick, onSelectQuickFood, onToggleFavorite, state.mutating)
                }
            }

            val nonFavoriteRecents = state.recentFoods.filterNot { it.isFavorite }
            if (nonFavoriteRecents.isNotEmpty()) {
                item {
                    Text("Récents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(nonFavoriteRecents, key = { "recent-${it.food.id}" }) { quick ->
                    QuickFoodCard(quick, onSelectQuickFood, onToggleFavorite, state.mutating)
                }
            }

            item {
                Text("Scanner un produit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.barcodeText,
                    onValueChange = onBarcodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.barcodeSearching && !state.mutating,
                    singleLine = true,
                    label = { Text("Code-barres EAN/UPC") },
                    placeholder = { Text("Scanner ou saisir les chiffres") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onScanBarcode,
                        enabled = state.mealType != null && !state.barcodeSearching && !state.mutating,
                    ) { Text("Scanner") }
                    TextButton(
                        onClick = onLookupBarcode,
                        enabled = state.mealType != null && state.barcodeText.length >= 8 &&
                            !state.barcodeSearching && !state.mutating,
                    ) { Text("Rechercher") }
                }
                if (state.barcodeSearching) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                        Text("Recherche dans Open Food Facts…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            state.barcodeProduct?.let { product ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(product.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            product.brand?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Pour 100 g : ${nutritionSummary(product.nutrientsPer100g)}", style = MaterialTheme.typography.bodySmall)
                            product.servingDefinitions.firstOrNull()?.let {
                                Text("Portion indiquée : ${it.label} · ${formatNumber(it.gramsEquivalent)} g", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Open Food Facts · ${product.barcode}", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onSelectOffProduct(product) }, enabled = !state.mutating) {
                                Text("Choisir")
                            }
                        }
                    }
                }
            }

            item {
                Text("Rechercher un aliment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.searching && !state.mutating,
                    singleLine = true,
                    label = { Text("Aliment") },
                    placeholder = { Text("Ex. filet de poulet, pomme, riz") },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSearch,
                    enabled = state.mealType != null && !state.searching && !state.mutating,
                ) {
                    Text(if (state.searching) "Recherche en cours…" else "Rechercher")
                }
                if (state.searching) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                        Text("Recherche dans Mes aliments et CIQUAL…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!state.searching && state.searchAttempted &&
                state.searchResults.isEmpty() && state.personalSearchResults.isEmpty()
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Aucun résultat trouvé", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Aucun aliment personnel ou CIQUAL trouvé pour « ${state.searchedQuery.orEmpty()} ».",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Essayez un terme plus simple, par exemple « poulet » au lieu de « filet de poulet ».",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (state.personalSearchResults.isNotEmpty()) {
                item {
                    Text(
                        "Mes aliments",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.personalSearchResults, key = { it.sourceExternalId }) { food ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(food.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            food.nutrientsPer100g?.let {
                                Text("Pour 100 g : ${nutritionSummary(it)}", style = MaterialTheme.typography.bodySmall)
                            }
                            food.nutrientsPerUnit?.let {
                                val unit = food.servingDefinitions.firstOrNull()?.unitLabel ?: "unité"
                                Text("Pour 1 $unit : ${nutritionSummary(it)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Article personnel Home Assistant", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onSelectPersonalFood(food) }, enabled = !state.mutating) {
                                Text("Choisir")
                            }
                        }
                    }
                }
            }

            if (state.searchResults.isNotEmpty()) {
                item {
                    Column {
                        Text("Résultats CIQUAL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val searched = state.searchedQuery
                        val effective = state.effectiveSearchQuery
                        if (!searched.isNullOrBlank() && !effective.isNullOrBlank() && !searched.equals(effective, ignoreCase = true)) {
                            Text(
                                "Aucun résultat exact pour « $searched ». Résultats élargis avec « $effective ».",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                items(state.searchResults, key = { it.sourceExternalId }) { food ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(food.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            preparationLabel(food.label)?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
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
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(item.labelSnapshot, fontWeight = FontWeight.Medium)
                                Text("${formatNumber(item.quantityValue)} ${item.quantityUnit.orEmpty()}".trim(), style = MaterialTheme.typography.bodySmall)
                                item.gramsEquivalent?.let {
                                    Text("Équivalent : ${formatNumber(it)} g", style = MaterialTheme.typography.labelSmall)
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
private fun QuickFoodCard(
    quick: QuickFood,
    onSelect: (QuickFood) -> Unit,
    onToggleFavorite: (QuickFood) -> Unit,
    busy: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(quick.food.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            quick.food.brand?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            quick.lastUsedLocalDate?.let {
                Text("Utilisé le $it", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSelect(quick) }, enabled = !busy) { Text("Choisir") }
                TextButton(onClick = { onToggleFavorite(quick) }, enabled = !busy) {
                    Text(if (quick.isFavorite) "Retirer des favoris" else "Ajouter aux favoris")
                }
            }
        }
    }
}

@Composable
private fun QuantityDialog(
    food: FoodChoice,
    quantity: String,
    selectedPortionId: String?,
    busy: Boolean,
    onSelectPortion: (String?) -> Unit,
    onQuantityChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedPortion = food.servingDefinitions.firstOrNull { it.id == selectedPortionId }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(food.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preparationLabel(food.label)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(preparationHelp(food.label), style = MaterialTheme.typography.bodySmall)
                }
                food.nutrientsPer100g?.let {
                    Text("Valeurs pour 100 g : ${nutritionSummary(it)}", style = MaterialTheme.typography.bodySmall)
                }
                food.nutrientsPerUnit?.let {
                    Text("Valeurs par unité : ${nutritionSummary(it)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Mode de saisie", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (food.nutrientsPer100g != null) {
                        item {
                            FilterChip(
                                selected = selectedPortionId == null,
                                onClick = { onSelectPortion(null) },
                                enabled = !busy,
                                label = { Text("Grammes") },
                            )
                        }
                    }
                    items(food.servingDefinitions, key = { it.id }) { portion ->
                        FilterChip(
                            selected = selectedPortionId == portion.id,
                            onClick = { onSelectPortion(portion.id) },
                            enabled = !busy,
                            label = {
                                Text(
                                    portion.label + (portion.gramsEquivalent?.let { " · ${formatNumber(it)} g" } ?: "")
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = quantity,
                    onValueChange = onQuantityChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(if (selectedPortion == null) "Quantité consommée" else "Nombre de portions") },
                    suffix = { Text(if (selectedPortion == null) "g" else selectedPortion.unitLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    when {
                        selectedPortion?.gramsEquivalent != null ->
                            "Conversion effectuée par Home Assistant depuis ${selectedPortion.sourceType}."
                        selectedPortion != null ->
                            "Portion personnelle : les nutriments par unité sont utilisés sans poids inventé."
                        else -> "Le poids en grammes est envoyé à Home Assistant."
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAdd, enabled = !busy && quantity.isNotBlank()) {
                if (busy) CircularProgressIndicator(modifier = Modifier.height(18.dp)) else Text("Ajouter")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuler") } },
    )
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
    snapshot.carbsG?.let { add("${formatNumber(it)} g gluc.") }
    snapshot.fatG?.let { add("${formatNumber(it)} g lip.") }
    snapshot.fiberG?.let { add("${formatNumber(it)} g fibres") }
    snapshot.saltG?.let { add("${formatNumber(it)} g sel") }
}.joinToString(" · ").ifBlank { "valeurs non renseignées" }

internal fun preparationLabel(label: String): String? {
    val value = label.lowercase(Locale.FRANCE)
    return when {
        Regex("\\b(cru|crue|crus|crues)\\b").containsMatchIn(value) -> "CRU · poids avant cuisson"
        Regex("\\b(cuit|cuite|cuits|cuites|grillé|grillée|grilles|grillées|rôti|rôtie|rotie|bouilli|bouillie|poché|pochée|vapeur|à la coque)\\b").containsMatchIn(value) -> "CUIT · poids après cuisson"
        else -> null
    }
}

private fun preparationHelp(label: String): String = when {
    preparationLabel(label)?.startsWith("CRU") == true -> "Saisissez le poids de la partie consommée avant cuisson."
    preparationLabel(label)?.startsWith("CUIT") == true -> "Saisissez le poids de la partie consommée telle qu'elle est mangée, après cuisson."
    else -> "Saisissez le poids de la partie consommée."
}

private fun formatNumber(value: Double?): String {
    if (value == null) return "—"
    return NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = 1 }.format(value)
}
