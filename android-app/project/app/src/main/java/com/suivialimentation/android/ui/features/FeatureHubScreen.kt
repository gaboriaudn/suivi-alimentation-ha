package com.suivialimentation.android.ui.features

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suivialimentation.android.data.features.HistoryAnalysis
import com.suivialimentation.android.data.features.RecipeSummary
import com.suivialimentation.android.data.photo.PhotoFoodSuggestion
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import com.suivialimentation.android.ui.photo.PhotoMealUiState
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureHubScreen(
    featureState: FeatureHubUiState,
    photoState: PhotoMealUiState,
    todayMeals: List<MealWithItems>,
    onAnalyzePhoto: (Uri) -> Unit,
    onClearPhoto: () -> Unit,
    onCreateFromPhoto: (List<PhotoFoodSuggestion>, String) -> Unit,
    onSaveRecipe: (String, String) -> Unit,
    onCreateFromRecipe: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var photoMealType by remember { mutableStateOf("Déjeuner") }
    var recipeMealType by remember { mutableStateOf("Déjeuner") }
    var pendingRecipe by remember { mutableStateOf<RecipeSummary?>(null) }
    var pendingSaveMeal by remember { mutableStateOf<MealWithItems?>(null) }
    var recipeName by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAnalyzePhoto(uri)
    }

    pendingRecipe?.let { recipe ->
        MealTypeDialog(
            title = "Ajouter « ${recipe.name} »",
            selected = recipeMealType,
            onSelect = { recipeMealType = it },
            onConfirm = {
                onCreateFromRecipe(recipe.id, recipeMealType)
                pendingRecipe = null
            },
            onDismiss = { pendingRecipe = null },
        )
    }

    pendingSaveMeal?.let { meal ->
        AlertDialog(
            onDismissRequest = { pendingSaveMeal = null },
            title = { Text("Enregistrer comme recette") },
            text = {
                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nom de la recette") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveRecipe(meal.meal.id, recipeName)
                        recipeName = ""
                        pendingSaveMeal = null
                    },
                    enabled = recipeName.isNotBlank() && !featureState.busy,
                ) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { pendingSaveMeal = null }) { Text("Annuler") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo, recettes et analyse") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinimumTouchTarget)) {
                        Text("‹ Retour")
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
                Text("J1.8 · Saisie par photo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "La photo identifie les aliments et estime leurs quantités. Les valeurs nutritionnelles sont ensuite recherchées dans vos aliments ou CIQUAL.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Button(
                            onClick = { imagePicker.launch("image/*") },
                            enabled = !photoState.loading && !featureState.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                        ) {
                            Text(if (photoState.loading) "Analyse en cours…" else "Prendre ou choisir une photo")
                        }
                        if (photoState.loading) CircularProgressIndicator()
                        photoState.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        photoState.title?.let { Text(it, fontWeight = FontWeight.Bold) }
                        photoState.suggestions.forEach { suggestion ->
                            Text("• ${suggestion.label} · ${format(suggestion.estimatedGrams)} g")
                        }
                        if (photoState.suggestions.isNotEmpty()) {
                            MealTypeSelector(photoMealType) { photoMealType = it }
                            Button(
                                onClick = { onCreateFromPhoto(photoState.suggestions, photoMealType) },
                                enabled = !featureState.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                            ) { Text("Créer un brouillon à vérifier") }
                            TextButton(onClick = onClearPhoto, modifier = Modifier.fillMaxWidth()) { Text("Effacer l’analyse") }
                        }
                    }
                }
            }

            item {
                Text("J1.9 · Recettes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (featureState.recipes.isEmpty()) {
                item { Text("Aucune recette enregistrée pour le moment.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(featureState.recipes, key = { it.id }) { recipe ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text(recipe.name, fontWeight = FontWeight.Bold)
                            Text(recipe.ingredients.joinToString(" · ").ifBlank { "Ingrédients enregistrés" }, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = { pendingRecipe = recipe },
                                enabled = !featureState.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                            ) { Text("Ajouter à un repas") }
                        }
                    }
                }
            }
            item {
                Text("Créer une recette depuis un repas validé aujourd’hui :", style = MaterialTheme.typography.bodySmall)
            }
            items(todayMeals.filter { it.meal.status == "validated" }, key = { "save-${it.meal.id}" }) { meal ->
                TextButton(
                    onClick = {
                        pendingSaveMeal = meal
                        recipeName = meal.meal.label.orEmpty().ifBlank { meal.meal.mealType }
                    },
                    enabled = !featureState.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                ) {
                    Text("Enregistrer ${meal.meal.mealType} comme recette")
                }
            }

            item {
                Text("J1.10 · Historique et analyse", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item { HistoryCard("7 derniers jours", featureState.history7) }
            item { HistoryCard("30 derniers jours", featureState.history30) }

            if (featureState.busy) item { CircularProgressIndicator() }
            featureState.error?.let { error -> item { Text(error, style = MaterialTheme.typography.bodySmall) } }
            featureState.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun MealTypeSelector(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Petit-déjeuner", "Déjeuner", "Collation", "Dîner").chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { type ->
                    val label = if (selected == type) "✓ $type" else type
                    TextButton(onClick = { onSelect(type) }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun MealTypeDialog(
    title: String,
    selected: String,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { MealTypeSelector(selected, onSelect) },
        confirmButton = { Button(onClick = onConfirm) { Text("Créer le brouillon") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun HistoryCard(title: String, analysis: HistoryAnalysis?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (analysis == null) {
                Text("Chargement…", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${analysis.recordedDayCount} jour(s) avec données", style = MaterialTheme.typography.bodySmall)
                Text("Moyenne : ${format(analysis.averages.energyKcal)} kcal · ${format(analysis.averages.proteinG)} g protéines")
                Text(
                    "Glucides ${format(analysis.averages.carbsG)} g · Lipides ${format(analysis.averages.fatG)} g · Fibres ${format(analysis.averages.fiberG)} g",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun format(value: Double?): String = if (value == null) "—" else NumberFormat.getNumberInstance(Locale.FRANCE).apply {
    maximumFractionDigits = 1
}.format(value)
