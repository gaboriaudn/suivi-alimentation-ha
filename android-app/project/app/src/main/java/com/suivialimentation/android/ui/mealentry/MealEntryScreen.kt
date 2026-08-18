package com.suivialimentation.android.ui.mealentry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.suivialimentation.android.data.model.CiqualFoodCandidate
import com.suivialimentation.android.data.model.MealItem
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.model.OffProductCandidate
import com.suivialimentation.android.data.model.PersonalFoodCandidate
import com.suivialimentation.android.data.repository.QuickFood
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import com.suivialimentation.android.ui.components.SectionHeader
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
    onComplementExistingMeal: () -> Unit,
    onCreateSeparateMeal: () -> Unit,
    onCancelExistingMealChoice: () -> Unit,
    onEditItem: (MealItem) -> Unit,
    onEditQuantityChange: (String) -> Unit,
    onConfirmItemEdit: () -> Unit,
    onDismissItemEdit: () -> Unit,
    onRemoveItem: (MealItem) -> Unit,
    onValidate: () -> Unit,
    onBack: () -> Unit,
    onValidated: () -> Unit,
) {
    var showManualBarcode by remember { mutableStateOf(false) }
    var favoritesExpanded by remember { mutableStateOf(false) }
    var recentsExpanded by remember { mutableStateOf(false) }

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

    state.pendingExistingMeal?.let { existing ->
        val type = mealTypes.firstOrNull { it.first == existing.meal.mealType }?.second ?: existing.meal.mealType
        AlertDialog(
            onDismissRequest = { if (!state.mutating) onCancelExistingMealChoice() },
            title = { Text("$type déjà enregistré") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text("Un repas de ce type existe déjà aujourd’hui.")
                    Button(
                        onClick = onComplementExistingMeal,
                        enabled = !state.mutating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                    ) { Text("Ajouter au repas existant") }
                    TextButton(
                        onClick = onCreateSeparateMeal,
                        enabled = !state.mutating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                    ) { Text("Créer un autre repas") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onCancelExistingMealChoice, enabled = !state.mutating) { Text("Annuler") }
            },
        )
    }

    state.editingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!state.mutating) onDismissItemEdit() },
            title = { Text("Modifier ${item.labelSnapshot}") },
            text = {
                OutlinedTextField(
                    value = state.editQuantityText,
                    onValueChange = onEditQuantityChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.mutating,
                    singleLine = true,
                    label = { Text("Quantité") },
                    suffix = { Text(item.quantityUnit.orEmpty()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmItemEdit,
                    enabled = !state.mutating && state.editQuantityText.isNotBlank(),
                ) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = onDismissItemEdit, enabled = !state.mutating) { Text("Annuler") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.draftMeal == null) "Ajouter un repas" else "Modifier le repas") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinimumTouchTarget)) { Text("‹ Retour") }
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
                SectionHeader("Type de repas")
                Spacer(Modifier.height(AppSpacing.sm))
                MealTypeSelector(
                    selected = state.mealType,
                    enabled = state.draftMeal == null && !state.mutating,
                    onSelect = onSelectMealType,
                )
            }

            if (state.error != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(state.error, modifier = Modifier.padding(AppSpacing.md), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.draftMeal != null) {
                item {
                    DraftSummary(
                        items = state.items,
                        busy = state.mutating,
                        onEditItem = onEditItem,
                        onRemoveItem = onRemoveItem,
                        onValidate = onValidate,
                    )
                }
            }

            item {
                SectionHeader(if (state.draftMeal == null) "Ajouter un aliment" else "Ajouter un autre aliment")
                Spacer(Modifier.height(AppSpacing.sm))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.searching && !state.mutating,
                    singleLine = true,
                    label = { Text("Rechercher un aliment") },
                    placeholder = { Text("Ex. poulet, pomme, riz") },
                )
                Spacer(Modifier.height(AppSpacing.sm))
                Button(
                    onClick = onSearch,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                    enabled = state.mealType != null && !state.searching && !state.mutating,
                ) {
                    if (state.searching) CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    else Text("Rechercher")
                }
                if (state.searching) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    Text("Recherche dans Mes aliments et CIQUAL…", style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Button(
                    onClick = onScanBarcode,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                    enabled = state.mealType != null && !state.barcodeSearching && !state.mutating,
                ) { Text("Scanner un produit") }
                TextButton(
                    onClick = { showManualBarcode = !showManualBarcode },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                    enabled = !state.barcodeSearching && !state.mutating,
                ) { Text(if (showManualBarcode) "Masquer la saisie du code-barres" else "Saisir un code-barres") }
            }

            if (showManualBarcode) {
                item {
                    OutlinedTextField(
                        value = state.barcodeText,
                        onValueChange = onBarcodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.barcodeSearching && !state.mutating,
                        singleLine = true,
                        label = { Text("Code-barres EAN/UPC") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    Button(
                        onClick = onLookupBarcode,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                        enabled = state.mealType != null && state.barcodeText.length >= 8 && !state.barcodeSearching && !state.mutating,
                    ) { Text("Rechercher le produit") }
                }
            }

            if (state.barcodeSearching) {
                item { Text("Recherche dans Open Food Facts…", style = MaterialTheme.typography.bodySmall) }
            }

            state.barcodeProduct?.let { product ->
                item {
                    ResultCard(
                        title = product.label,
                        subtitle = buildList {
                            product.brand?.takeIf(String::isNotBlank)?.let(::add)
                            add("Pour 100 g : ${nutritionSummary(product.nutrientsPer100g)}")
                            product.servingDefinitions.firstOrNull()?.let {
                                add("Portion : ${it.label}${it.gramsEquivalent?.let { grams -> " · ${formatNumber(grams)} g" }.orEmpty()}")
                            }
                            add("Open Food Facts · ${product.barcode}")
                        }.joinToString("\n"),
                        enabled = !state.mutating,
                        onSelect = { onSelectOffProduct(product) },
                    )
                }
            }

            if (state.favoriteFoods.isNotEmpty()) {
                item {
                    QuickFoodsSection(
                        title = "Favoris",
                        foods = if (favoritesExpanded) state.favoriteFoods else state.favoriteFoods.take(3),
                        totalCount = state.favoriteFoods.size,
                        expanded = favoritesExpanded,
                        busy = state.mutating,
                        onSelect = onSelectQuickFood,
                        onToggleFavorite = onToggleFavorite,
                        onToggleExpanded = { favoritesExpanded = !favoritesExpanded },
                    )
                }
            }

            val nonFavoriteRecents = state.recentFoods.filterNot { it.isFavorite }
            if (nonFavoriteRecents.isNotEmpty()) {
                item {
                    QuickFoodsSection(
                        title = "Récents",
                        foods = if (recentsExpanded) nonFavoriteRecents else nonFavoriteRecents.take(3),
                        totalCount = nonFavoriteRecents.size,
                        expanded = recentsExpanded,
                        busy = state.mutating,
                        onSelect = onSelectQuickFood,
                        onToggleFavorite = onToggleFavorite,
                        onToggleExpanded = { recentsExpanded = !recentsExpanded },
                    )
                }
            }

            if (!state.searching && state.searchAttempted && state.searchResults.isEmpty() && state.personalSearchResults.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(AppSpacing.md)) {
                            Text("Aucun résultat trouvé", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Aucun aliment trouvé pour « ${state.searchedQuery.orEmpty()} ». Essayez un terme plus simple.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (state.personalSearchResults.isNotEmpty()) {
                item { SectionHeader("Mes aliments") }
                items(state.personalSearchResults, key = { it.sourceExternalId }) { food ->
                    itemResultCard(
                        title = food.label,
                        subtitle = buildList {
                            food.nutrientsPer100g?.let { add("Pour 100 g : ${nutritionSummary(it)}") }
                            food.nutrientsPerUnit?.let {
                                val unit = food.servingDefinitions.firstOrNull()?.unitLabel ?: "unité"
                                add("Pour 1 $unit : ${nutritionSummary(it)}")
                            }
                            add("Aliment personnel Home Assistant")
                        }.joinToString("\n"),
                        enabled = !state.mutating,
                        onSelect = { onSelectPersonalFood(food) },
                    )
                }
            }

            if (state.searchResults.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader("Résultats CIQUAL")
                        val searched = state.searchedQuery
                        val effective = state.effectiveSearchQuery
                        if (!searched.isNullOrBlank() && !effective.isNullOrBlank() && !searched.equals(effective, ignoreCase = true)) {
                            Text("Résultats élargis avec « $effective ».", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(state.searchResults, key = { it.sourceExternalId }) { food ->
                    val prep = preparationLabel(food.label)
                    itemResultCard(
                        title = food.label,
                        subtitle = buildList {
                            prep?.let(::add)
                            add("Pour 100 g : ${nutritionSummary(food.nutrientsPer100g)}")
                            add("CIQUAL ${food.sourceVersion ?: ""}".trim())
                        }.joinToString("\n"),
                        enabled = !state.mutating,
                        onSelect = { onSelectFood(food) },
                    )
                }
            }

            if (state.draftMeal == null) {
                item {
                    Text(
                        "Le repas sera créé seulement après l’ajout du premier aliment.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MealTypeSelector(selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        mealTypes.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                row.forEach { (value, label) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f).heightIn(min = MinimumTouchTarget),
                        enabled = enabled,
                        label = { Text(label, maxLines = 2) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftSummary(
    items: List<MealItem>,
    busy: Boolean,
    onEditItem: (MealItem) -> Unit,
    onRemoveItem: (MealItem) -> Unit,
    onValidate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            SectionHeader("Repas en cours")
            if (items.isEmpty()) {
                Text("Aucun aliment dans ce repas.", style = MaterialTheme.typography.bodySmall)
            } else {
                items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable(enabled = !busy && item.foodRefId != null) { onEditItem(item) },
                        ) {
                            Text(item.labelSnapshot, fontWeight = FontWeight.Medium)
                            Text(
                                "${formatNumber(item.quantityValue)} ${item.quantityUnit.orEmpty()}".trim(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            item.nutritionSnapshot?.let { Text(primaryNutritionSummary(it), style = MaterialTheme.typography.labelSmall) }
                        }
                        TextButton(
                            onClick = { onEditItem(item) },
                            enabled = !busy && item.foodRefId != null,
                            modifier = Modifier.heightIn(min = MinimumTouchTarget),
                        ) { Text("Modifier") }
                        TextButton(
                            onClick = { onRemoveItem(item) },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = MinimumTouchTarget),
                        ) { Text("Suppr.") }
                    }
                }
            }
            Button(
                onClick = onValidate,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                enabled = items.isNotEmpty() && !busy,
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.height(18.dp))
                else Text("Enregistrer le repas")
            }
        }
    }
}

@Composable
private fun QuickFoodsSection(
    title: String,
    foods: List<QuickFood>,
    totalCount: Int,
    expanded: Boolean,
    busy: Boolean,
    onSelect: (QuickFood) -> Unit,
    onToggleFavorite: (QuickFood) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        SectionHeader(title)
        foods.forEach { quick ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinimumTouchTarget)
                        .clickable(enabled = !busy) { onSelect(quick) }
                        .padding(start = AppSpacing.md, end = AppSpacing.xs, top = AppSpacing.sm, bottom = AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(quick.food.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        quick.food.brand?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(
                        onClick = { onToggleFavorite(quick) },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = MinimumTouchTarget),
                    ) { Text(if (quick.isFavorite) "★" else "☆") }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        if (totalCount > 3) {
            TextButton(onClick = onToggleExpanded, modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget)) {
                Text(if (expanded) "Réduire" else "Voir les $totalCount")
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, subtitle: String, enabled: Boolean, onSelect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemResultCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    item { ResultCard(title, subtitle, enabled, onSelect) }
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
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                preparationLabel(food.label)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(preparationHelp(food.label), style = MaterialTheme.typography.bodySmall)
                }
                food.nutrientsPer100g?.let { Text("Pour 100 g : ${nutritionSummary(it)}", style = MaterialTheme.typography.bodySmall) }
                food.nutrientsPerUnit?.let { Text("Par unité : ${nutritionSummary(it)}", style = MaterialTheme.typography.bodySmall) }
                if (food.servingDefinitions.isNotEmpty() || food.nutrientsPer100g != null) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
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
                                label = { Text(portion.label) },
                            )
                        }
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
                selectedPortion?.gramsEquivalent?.let {
                    Text("${selectedPortion.label} = ${formatNumber(it)} g · source ${selectedPortion.sourceType}", style = MaterialTheme.typography.labelSmall)
                }
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

private fun primaryNutritionSummary(snapshot: NutrientSnapshot): String = buildList {
    snapshot.energyKcal?.let { add("${formatNumber(it)} kcal") }
    snapshot.proteinG?.let { add("${formatNumber(it)} g prot.") }
}.joinToString(" · ").ifBlank { "Valeurs non renseignées" }

internal fun preparationLabel(label: String): String? {
    val value = label.lowercase(Locale.FRANCE)
    return when {
        Regex("\\b(cru|crue|crus|crues)\\b").containsMatchIn(value) -> "CRU · poids avant cuisson"
        Regex("\\b(cuit|cuite|cuits|cuites|grillé|grillée|grilles|grillées|rôti|rôtie|rotie|bouilli|bouillie|poché|pochée|vapeur|à la coque)\\b").containsMatchIn(value) -> "CUIT · poids après cuisson"
        else -> null
    }
}

private fun preparationHelp(label: String): String = when {
    preparationLabel(label)?.startsWith("CRU") == true -> "Saisissez le poids avant cuisson."
    preparationLabel(label)?.startsWith("CUIT") == true -> "Saisissez le poids tel qu'il est consommé, après cuisson."
    else -> "Saisissez la quantité consommée."
}

private fun formatNumber(value: Double?): String {
    if (value == null) return "—"
    return NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = 1 }.format(value)
}
