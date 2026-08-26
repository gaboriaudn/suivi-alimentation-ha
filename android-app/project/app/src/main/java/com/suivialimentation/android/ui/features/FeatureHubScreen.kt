package com.suivialimentation.android.ui.features

import android.content.Context
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.suivialimentation.android.data.features.HistoryAnalysis
import com.suivialimentation.android.data.photo.PhotoAnalysisMode
import com.suivialimentation.android.data.photo.PhotoFoodSuggestion
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import com.suivialimentation.android.ui.photo.PhotoMealUiState
import java.io.File
import java.text.NumberFormat
import java.util.Locale

enum class FeatureHubSection {
    HISTORY,
    MORE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureHubScreen(
    modifier: Modifier = Modifier,
    section: FeatureHubSection,
    featureState: FeatureHubUiState,
    photoState: PhotoMealUiState,
    todayMeals: List<MealWithItems>,
    onAnalyzeFoodPhoto: (Uri) -> Unit,
    onAnalyzeMealPhoto: (Uri) -> Unit,
    onClearPhoto: () -> Unit,
    onCreateFromPhoto: (List<PhotoFoodSuggestion>, String) -> Unit,
    onSaveRecipe: (String, String) -> Unit,
    onCreateFromRecipe: (String, String) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selectedMealType by remember { mutableStateOf("") }
    var pendingSaveMeal by remember { mutableStateOf<MealWithItems?>(null) }
    var reusableName by remember { mutableStateOf("") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraMode by remember { mutableStateOf(PhotoAnalysisMode.MEAL) }
    var pendingPickerMode by remember { mutableStateOf(PhotoAnalysisMode.MEAL) }

    fun analyze(uri: Uri, mode: PhotoAnalysisMode) {
        if (mode == PhotoAnalysisMode.FOOD) onAnalyzeFoodPhoto(uri) else onAnalyzeMealPhoto(uri)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyze(uri, pendingPickerMode)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) analyze(uri, pendingCameraMode)
        pendingCameraUri = null
    }

    pendingSaveMeal?.let { meal ->
        AlertDialog(
            onDismissRequest = { pendingSaveMeal = null },
            title = { Text("Enregistrer ce repas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        "Un repas type correspond à un ensemble que vous mangez régulièrement. Une recette correspond à une préparation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = reusableName,
                        onValueChange = { reusableName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nom") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            onSaveRecipe(meal.meal.id, "__meal_template__:$reusableName")
                            reusableName = ""
                            pendingSaveMeal = null
                        },
                        enabled = reusableName.isNotBlank() && !featureState.busy,
                    ) { Text("Enregistrer comme repas type") }
                    TextButton(
                        onClick = {
                            onSaveRecipe(meal.meal.id, reusableName)
                            reusableName = ""
                            pendingSaveMeal = null
                        },
                        enabled = reusableName.isNotBlank() && !featureState.busy,
                    ) { Text("Enregistrer comme recette") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingSaveMeal = null }) { Text("Annuler") } },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (section == FeatureHubSection.HISTORY) "Historique" else "Plus") },
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
            if (section == FeatureHubSection.MORE) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text("1. Moment du repas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Choisissez d’abord le moment. Les actions d’ajout deviennent ensuite disponibles.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            MealTypeSelector(selectedMealType) { selectedMealType = it }
                        }
                    }
                }

                if (selectedMealType.isNotBlank()) {
                    item {
                        Text("2. Choisir le contenu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    item {
                        Text("Ajouter depuis une photo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Les valeurs nutritionnelles restent recherchées dans vos aliments ou CIQUAL.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item {
                        PhotoModeCard(
                            title = "Photographier un aliment",
                            description = "Pour un fruit, un yaourt, une tranche de pain, un morceau de fromage… L’analyse cherche un seul aliment et évite d’inventer une recette.",
                            loading = photoState.loading,
                            busy = featureState.busy,
                            onTakePhoto = {
                                val uri = createCameraUri(context, "food")
                                pendingCameraMode = PhotoAnalysisMode.FOOD
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            },
                            onChoosePhoto = {
                                pendingPickerMode = PhotoAnalysisMode.FOOD
                                imagePicker.launch("image/*")
                            },
                        )
                    }
                    item {
                        PhotoModeCard(
                            title = "Photographier un repas",
                            description = "Pour une assiette ou un repas composé. L’analyse essaie de distinguer les différents aliments réellement visibles.",
                            loading = photoState.loading,
                            busy = featureState.busy,
                            onTakePhoto = {
                                val uri = createCameraUri(context, "meal")
                                pendingCameraMode = PhotoAnalysisMode.MEAL
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            },
                            onChoosePhoto = {
                                pendingPickerMode = PhotoAnalysisMode.MEAL
                                imagePicker.launch("image/*")
                            },
                        )
                    }
                    if (photoState.loading) item { CircularProgressIndicator() }
                    photoState.error?.let { error -> item { Text(error, style = MaterialTheme.typography.bodySmall) } }
                    if (photoState.title != null || photoState.suggestions.isNotEmpty()) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                    Text(
                                        if (photoState.mode == PhotoAnalysisMode.FOOD) "Aliment reconnu" else "Repas reconnu",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    photoState.title?.let { Text(it, fontWeight = FontWeight.Bold) }
                                    photoState.suggestions.forEach { suggestion ->
                                        Text("• ${suggestion.label} · ${format(suggestion.estimatedGrams)} g")
                                    }
                                    if (photoState.suggestions.isNotEmpty()) {
                                        Button(
                                            onClick = { onCreateFromPhoto(photoState.suggestions, selectedMealType) },
                                            enabled = !featureState.busy,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                                        ) { Text("Ajouter au repas") }
                                        TextButton(onClick = onClearPhoto, modifier = Modifier.fillMaxWidth()) { Text("Effacer l’analyse") }
                                    }
                                }
                            }
                        }
                    }

                    item { Text("Repas types", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    if (featureState.mealTemplates.isEmpty()) {
                        item { Text("Aucun repas type enregistré pour le moment.", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        items(featureState.mealTemplates, key = { "template-${it.id}" }) { template ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                    Text(template.name, fontWeight = FontWeight.Bold)
                                    Text(template.items.joinToString(" · ").ifBlank { "Contenu enregistré" }, style = MaterialTheme.typography.bodySmall)
                                    Button(
                                        onClick = { onCreateFromRecipe("meal-template:${template.id}", selectedMealType) },
                                        enabled = !featureState.busy,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                                    ) { Text("Ajouter ce repas type") }
                                }
                            }
                        }
                    }

                    item { Text("Recettes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    if (featureState.recipes.isEmpty()) {
                        item { Text("Aucune recette enregistrée pour le moment.", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        items(featureState.recipes, key = { "recipe-${it.id}" }) { recipe ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                    Text(recipe.name, fontWeight = FontWeight.Bold)
                                    Text(recipe.ingredients.joinToString(" · ").ifBlank { "Ingrédients enregistrés" }, style = MaterialTheme.typography.bodySmall)
                                    Button(
                                        onClick = { onCreateFromRecipe(recipe.id, selectedMealType) },
                                        enabled = !featureState.busy,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                                    ) { Text("Ajouter cette recette") }
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Enregistrer un repas validé aujourd’hui", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Vous pourrez le conserver comme repas type ou comme recette.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(todayMeals.filter { it.meal.status == "validated" }, key = { "save-${it.meal.id}" }) { meal ->
                    TextButton(
                        onClick = {
                            pendingSaveMeal = meal
                            reusableName = meal.meal.label.orEmpty().ifBlank { mealTypeLabel(meal.meal.mealType) }
                        },
                        enabled = !featureState.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                    ) { Text("Enregistrer ${mealTypeLabel(meal.meal.mealType)}") }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text("Compte et connexion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Home Assistant reste la source de toutes les données de l’application.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = onLogout,
                                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                            ) { Text("Se déconnecter") }
                        }
                    }
                }
            }

            if (section == FeatureHubSection.HISTORY) {
                item {
                    Text("Tendances nutritionnelles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Les moyennes sont calculées uniquement sur les journées qui contiennent des données.", style = MaterialTheme.typography.bodySmall)
                }
                item { HistoryCard("7 derniers jours", featureState.history7) }
                item { HistoryCard("30 derniers jours", featureState.history30) }
            }

            if (featureState.busy) item { CircularProgressIndicator() }
            featureState.error?.let { error -> item { Text(error, style = MaterialTheme.typography.bodySmall) } }
            featureState.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun PhotoModeCard(
    title: String,
    description: String,
    loading: Boolean,
    busy: Boolean,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onTakePhoto,
                enabled = !loading && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
            ) { Text("Prendre une photo") }
            OutlinedButton(
                onClick = onChoosePhoto,
                enabled = !loading && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
            ) { Text("Choisir une photo") }
        }
    }
}

private fun createCameraUri(context: Context, prefix: String): Uri {
    val imageDir = File(context.cacheDir, "images").apply { mkdirs() }
    val imageFile = File.createTempFile("${prefix}_", ".jpg", imageDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
}

@Composable
private fun MealTypeSelector(selected: String, onSelect: (String) -> Unit) {
    val types = listOf(
        "breakfast" to "Petit-déjeuner",
        "lunch" to "Déjeuner",
        "snack" to "Collation",
        "dinner" to "Dîner",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        types.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    val text = if (selected == value) "✓ $label" else label
                    TextButton(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) { Text(text) }
                }
            }
        }
    }
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

private fun mealTypeLabel(value: String): String = when (value) {
    "breakfast" -> "Petit-déjeuner"
    "lunch" -> "Déjeuner"
    "dinner" -> "Dîner"
    "snack" -> "Collation"
    else -> value
}

private fun format(value: Double?): String = if (value == null) "—" else NumberFormat.getNumberInstance(Locale.FRANCE).apply {
    maximumFractionDigits = 1
}.format(value)
