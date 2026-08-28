package com.suivialimentation.android.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.suivialimentation.android.data.features.FeatureRepository
import com.suivialimentation.android.data.features.ReusableItemInput
import com.suivialimentation.android.data.model.CiqualFoodCandidate
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.repository.NutritionRepository
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import kotlinx.coroutines.launch

enum class LibraryCreationKind { FOOD, RECIPE, MEAL_TEMPLATE }

private data class DraftReusableItem(val foodRefId: String, val label: String, val grams: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCreateScreen(
    kind: LibraryCreationKind,
    profileId: String,
    nutritionRepository: NutritionRepository,
    featureRepository: FeatureRepository,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val title = when (kind) {
        LibraryCreationKind.FOOD -> "Créer un aliment"
        LibraryCreationKind.RECIPE -> "Créer une recette"
        LibraryCreationKind.MEAL_TEMPLATE -> "Créer un repas type"
    }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour") } }) }) { padding ->
        if (kind == LibraryCreationKind.FOOD) {
            FoodForm(Modifier.padding(padding), busy, error) { name, nutrients ->
                scope.launch {
                    busy = true; error = null
                    runCatching { nutritionRepository.createManualFood(profileId, name, nutrients) }
                        .onSuccess { onDone() }
                        .onFailure { error = it.message ?: "Création impossible." }
                    busy = false
                }
            }
        } else {
            ReusableForm(Modifier.padding(padding), kind, profileId, nutritionRepository, featureRepository, busy, error, onBusy = { busy = it }, onError = { error = it }, onDone = onDone)
        }
    }
}

@Composable
private fun FoodForm(modifier: Modifier, busy: Boolean, error: String?, onSave: (String, NutrientSnapshot) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }; var protein by remember { mutableStateOf("") }; var carbs by remember { mutableStateOf("") }; var fat by remember { mutableStateOf("") }; var fiber by remember { mutableStateOf("") }; var salt by remember { mutableStateOf("") }
    fun num(value: String) = value.replace(',', '.').toDoubleOrNull()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        item { Text("Valeurs nutritionnelles pour 100 g", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom de l’aliment") }, singleLine = true) }
        item { OutlinedTextField(kcal, { kcal = it }, Modifier.fillMaxWidth(), label = { Text("Calories (kcal)") }, singleLine = true) }
        item { OutlinedTextField(protein, { protein = it }, Modifier.fillMaxWidth(), label = { Text("Protéines (g)") }, singleLine = true) }
        item { OutlinedTextField(carbs, { carbs = it }, Modifier.fillMaxWidth(), label = { Text("Glucides (g)") }, singleLine = true) }
        item { OutlinedTextField(fat, { fat = it }, Modifier.fillMaxWidth(), label = { Text("Lipides (g)") }, singleLine = true) }
        item { OutlinedTextField(fiber, { fiber = it }, Modifier.fillMaxWidth(), label = { Text("Fibres (g)") }, singleLine = true) }
        item { OutlinedTextField(salt, { salt = it }, Modifier.fillMaxWidth(), label = { Text("Sel (g)") }, singleLine = true) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Button(onClick = { onSave(name.trim(), NutrientSnapshot(num(kcal), num(protein), num(carbs), num(fat), num(fiber), num(salt))) }, enabled = !busy && name.isNotBlank() && num(kcal) != null && num(protein) != null, modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget)) {
                if (busy) CircularProgressIndicator() else Text("Enregistrer l’aliment")
            }
        }
    }
}

@Composable
private fun ReusableForm(
    modifier: Modifier,
    kind: LibraryCreationKind,
    profileId: String,
    nutritionRepository: NutritionRepository,
    featureRepository: FeatureRepository,
    busy: Boolean,
    error: String?,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CiqualFoodCandidate>>(emptyList()) }
    val selected = remember { mutableStateListOf<DraftReusableItem>() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(if (kind == LibraryCreationKind.RECIPE) "Nom de la recette" else "Nom du repas type") }, singleLine = true) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Ajouter un aliment") }, singleLine = true)
                Button(onClick = { scope.launch { searching = true; onError(null); runCatching { nutritionRepository.searchCiqual(profileId, query, 10) }.onSuccess { results = it }.onFailure { onError(it.message) }; searching = false } }, enabled = query.isNotBlank() && !searching) { Text("Chercher") }
            }
        }
        if (searching) item { CircularProgressIndicator() }
        items(results, key = { it.sourceExternalId }) { candidate ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(AppSpacing.sm)) {
                Text(candidate.label, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { scope.launch { onBusy(true); runCatching { nutritionRepository.importCiqualFood(profileId, candidate.sourceExternalId) }.onSuccess { imported -> selected.add(DraftReusableItem(imported.food.id, candidate.label, "100")); results = emptyList(); query = "" }.onFailure { onError(it.message) }; onBusy(false) } }) { Text("Ajouter") }
            } }
        }
        if (selected.isNotEmpty()) item { Text("Composition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(selected, key = { it.foodRefId }) { item ->
            val index = selected.indexOf(item)
            Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(AppSpacing.sm), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text(item.label, Modifier.weight(1f))
                OutlinedTextField(item.grams, { value -> if (index >= 0) selected[index] = item.copy(grams = value) }, Modifier.weight(.45f), label = { Text("g") }, singleLine = true)
                TextButton(onClick = { selected.remove(item) }) { Text("Retirer") }
            } }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            val validItems = selected.mapNotNull { it.grams.replace(',', '.').toDoubleOrNull()?.takeIf { g -> g > 0 }?.let { g -> ReusableItemInput(it.foodRefId, it.label, g) } }
            Button(onClick = {
                scope.launch {
                    onBusy(true); onError(null)
                    runCatching {
                        if (kind == LibraryCreationKind.RECIPE) featureRepository.createRecipe(profileId, name, validItems)
                        else featureRepository.createMealTemplate(profileId, name, validItems)
                    }.onSuccess { onDone() }.onFailure { onError(it.message ?: "Création impossible.") }
                    onBusy(false)
                }
            }, enabled = !busy && name.isNotBlank() && validItems.isNotEmpty() && validItems.size == selected.size, modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget)) {
                if (busy) CircularProgressIndicator() else Text(if (kind == LibraryCreationKind.RECIPE) "Enregistrer la recette" else "Enregistrer le repas type")
            }
        }
    }
}
