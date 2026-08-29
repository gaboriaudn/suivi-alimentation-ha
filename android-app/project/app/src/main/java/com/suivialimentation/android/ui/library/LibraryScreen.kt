package com.suivialimentation.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.suivialimentation.android.data.library.LibraryComponent
import com.suivialimentation.android.data.library.LibraryData
import com.suivialimentation.android.data.library.LibraryItemInput
import com.suivialimentation.android.data.library.LibraryMealTemplate
import com.suivialimentation.android.data.library.LibraryRecipe
import com.suivialimentation.android.data.library.LibraryRepository
import com.suivialimentation.android.data.model.FoodReference
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import java.util.UUID
import kotlinx.coroutines.launch

private enum class LibrarySection(val label: String) {
    FOODS("Aliments"),
    RECIPES("Recettes"),
    TEMPLATES("Repas types"),
}

private sealed interface LibraryEditor {
    data class Food(val value: FoodReference) : LibraryEditor
    data class Recipe(val value: LibraryRecipe) : LibraryEditor
    data class Template(val value: LibraryMealTemplate) : LibraryEditor
}

private data class DeleteTarget(val kind: LibrarySection, val id: String, val name: String)

private data class EditableComponent(
    val localId: String = UUID.randomUUID().toString(),
    val foodRefId: String,
    val label: String,
    val quantity: String,
    val quantityUnit: String,
    val portionId: String?,
)

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    profileId: String,
    repository: LibraryRepository,
) {
    val scope = rememberCoroutineScope()
    var reloadToken by remember { mutableIntStateOf(0) }
    var library by remember { mutableStateOf(LibraryData()) }
    var loading by remember { mutableStateOf(true) }
    var mutating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(LibrarySection.FOODS) }
    var editor by remember { mutableStateOf<LibraryEditor?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    LaunchedEffect(profileId, reloadToken) {
        loading = true
        error = null
        runCatching { repository.load(profileId) }
            .onSuccess { library = it }
            .onFailure { error = it.message ?: "Chargement de la bibliothèque impossible." }
        loading = false
    }

    fun mutate(action: suspend () -> Unit) {
        if (mutating) return
        scope.launch {
            mutating = true
            error = null
            runCatching { action() }
                .onSuccess {
                    editor = null
                    deleteTarget = null
                    reloadToken += 1
                }
                .onFailure { error = it.message ?: "Modification impossible." }
            mutating = false
        }
    }

    val currentEditor = editor
    if (currentEditor != null) {
        when (currentEditor) {
            is LibraryEditor.Food -> FoodEditor(
                modifier = modifier,
                food = currentEditor.value,
                busy = mutating,
                error = error,
                onBack = { editor = null; error = null },
                onSave = { label, nutrients -> mutate { repository.updateFood(profileId, currentEditor.value.id, label, nutrients) } },
            )
            is LibraryEditor.Recipe -> ReusableEditor(
                modifier = modifier,
                title = "Modifier la recette",
                name = currentEditor.value.name,
                initialComponents = currentEditor.value.components,
                foods = library.foods,
                busy = mutating,
                error = error,
                onBack = { editor = null; error = null },
                onSave = { name, items -> mutate { repository.updateRecipe(profileId, currentEditor.value.id, name, items) } },
            )
            is LibraryEditor.Template -> ReusableEditor(
                modifier = modifier,
                title = "Modifier le repas type",
                name = currentEditor.value.name,
                initialComponents = currentEditor.value.components,
                foods = library.foods,
                busy = mutating,
                error = error,
                onBack = { editor = null; error = null },
                onSave = { name, items -> mutate { repository.updateMealTemplate(profileId, currentEditor.value.id, name, currentEditor.value.defaultMealType, items) } },
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Text("Bibliothèque", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Retrouvez et gérez vos aliments, recettes et repas types.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            LibrarySection.entries.forEach { item ->
                FilterChip(
                    selected = section == item,
                    onClick = { section = item; search = "" },
                    label = { Text(item.label) },
                )
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Rechercher") },
            singleLine = true,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) {
            CircularProgressIndicator()
        } else {
            when (section) {
                LibrarySection.FOODS -> FoodList(
                    foods = library.foods.filter { it.label.contains(search, ignoreCase = true) },
                    onEdit = { editor = LibraryEditor.Food(it) },
                    onDelete = { deleteTarget = DeleteTarget(section, it.id, it.label) },
                )
                LibrarySection.RECIPES -> RecipeList(
                    recipes = library.recipes.filter { it.name.contains(search, ignoreCase = true) },
                    onEdit = { editor = LibraryEditor.Recipe(it) },
                    onDelete = { deleteTarget = DeleteTarget(section, it.id, it.name) },
                )
                LibrarySection.TEMPLATES -> TemplateList(
                    templates = library.mealTemplates.filter { it.name.contains(search, ignoreCase = true) },
                    onEdit = { editor = LibraryEditor.Template(it) },
                    onDelete = { deleteTarget = DeleteTarget(section, it.id, it.name) },
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!mutating) deleteTarget = null },
            title = { Text("Supprimer ${target.name} ?") },
            text = { Text("L’élément disparaîtra de la bibliothèque mobile.") },
            confirmButton = {
                Button(
                    enabled = !mutating,
                    onClick = {
                        mutate {
                            when (target.kind) {
                                LibrarySection.FOODS -> repository.deleteFood(profileId, target.id)
                                LibrarySection.RECIPES -> repository.deleteRecipe(profileId, target.id)
                                LibrarySection.TEMPLATES -> repository.deleteMealTemplate(profileId, target.id)
                            }
                        }
                    },
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(enabled = !mutating, onClick = { deleteTarget = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun FoodList(foods: List<FoodReference>, onEdit: (FoodReference) -> Unit, onDelete: (FoodReference) -> Unit) {
    if (foods.isEmpty()) {
        Text("Aucun aliment dans cette bibliothèque.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        items(foods, key = { it.id }) { food ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(food) }) {
                Column(Modifier.fillMaxWidth().padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(food.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(foodNutritionSummary(food), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        TextButton(onClick = { onEdit(food) }) { Text("Modifier") }
                        TextButton(onClick = { onDelete(food) }) { Text("Supprimer") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeList(recipes: List<LibraryRecipe>, onEdit: (LibraryRecipe) -> Unit, onDelete: (LibraryRecipe) -> Unit) {
    if (recipes.isEmpty()) {
        Text("Aucune recette dans cette bibliothèque.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        items(recipes, key = { it.id }) { recipe ->
            LibraryReusableCard(recipe.name, recipe.components, { onEdit(recipe) }, { onDelete(recipe) })
        }
    }
}

@Composable
private fun TemplateList(templates: List<LibraryMealTemplate>, onEdit: (LibraryMealTemplate) -> Unit, onDelete: (LibraryMealTemplate) -> Unit) {
    if (templates.isEmpty()) {
        Text("Aucun repas type dans cette bibliothèque.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        items(templates, key = { it.id }) { template ->
            LibraryReusableCard(template.name, template.components, { onEdit(template) }, { onDelete(template) })
        }
    }
}

@Composable
private fun LibraryReusableCard(name: String, components: List<LibraryComponent>, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (components.isEmpty()) "Aucun composant" else components.joinToString(" · ") { it.label },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                TextButton(onClick = onEdit) { Text("Modifier") }
                TextButton(onClick = onDelete) { Text("Supprimer") }
            }
        }
    }
}

@Composable
private fun FoodEditor(
    modifier: Modifier,
    food: FoodReference,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: (String, NutrientSnapshot) -> Unit,
) {
    var label by remember(food.id) { mutableStateOf(food.label) }
    var kcal by remember(food.id) { mutableStateOf(numberText(food.nutrientsPer100g?.energyKcal)) }
    var protein by remember(food.id) { mutableStateOf(numberText(food.nutrientsPer100g?.proteinG)) }
    var carbs by remember(food.id) { mutableStateOf(numberText(food.nutrientsPer100g?.carbsG)) }
    var fat by remember(food.id) { mutableStateOf(numberText(food.nutrientsPer100g?.fatG)) }
    var fiber by remember(food.id) { mutableStateOf(numberText(food.nutrientsPer100g?.fiberG)) }
    var salt by remember(food.id) { mutableStateOf(numberText(food.nutrientsPer100g?.saltG)) }
    fun number(value: String): Double? = value.replace(',', '.').toDoubleOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        item { TextButton(onClick = onBack, enabled = !busy) { Text("‹ Retour à la bibliothèque") } }
        item { Text("Modifier l’aliment", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Valeurs nutritionnelles pour 100 g", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true) }
        item { NumericField("Calories (kcal)", kcal) { kcal = it } }
        item { NumericField("Protéines (g)", protein) { protein = it } }
        item { NumericField("Glucides (g)", carbs) { carbs = it } }
        item { NumericField("Lipides (g)", fat) { fat = it } }
        item { NumericField("Fibres (g)", fiber) { fiber = it } }
        item { NumericField("Sel (g)", salt) { salt = it } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                enabled = !busy && label.isNotBlank() && number(kcal) != null && number(protein) != null,
                onClick = {
                    onSave(
                        label.trim(),
                        NutrientSnapshot(number(kcal), number(protein), number(carbs), number(fat), number(fiber), number(salt)),
                    )
                },
            ) { if (busy) CircularProgressIndicator() else Text("Enregistrer les modifications") }
        }
    }
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

@Composable
private fun ReusableEditor(
    modifier: Modifier,
    title: String,
    name: String,
    initialComponents: List<LibraryComponent>,
    foods: List<FoodReference>,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: (String, List<LibraryItemInput>) -> Unit,
) {
    var editedName by remember(name) { mutableStateOf(name) }
    var foodQuery by remember { mutableStateOf("") }
    val components = remember(initialComponents) {
        mutableStateListOf<EditableComponent>().apply {
            addAll(initialComponents.map { component ->
                EditableComponent(
                    foodRefId = component.foodRefId,
                    label = component.label,
                    quantity = numberText(component.quantityValue),
                    quantityUnit = component.quantityUnit,
                    portionId = component.portionId,
                )
            })
        }
    }
    fun parsedItems(): List<LibraryItemInput> = components.mapNotNull { component ->
        val quantity = component.quantity.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        LibraryItemInput(component.foodRefId, quantity, component.quantityUnit, component.portionId)
    }
    val candidates = foods.filter { food -> foodQuery.isNotBlank() && food.label.contains(foodQuery, ignoreCase = true) }.take(8)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        item { TextButton(onClick = onBack, enabled = !busy) { Text("‹ Retour à la bibliothèque") } }
        item { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(editedName, { editedName = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true) }
        item { Text("Composition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(components, key = { it.localId }) { component ->
            val index = components.indexOfFirst { it.localId == component.localId }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Text(component.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        TextButton(onClick = { components.removeAll { it.localId == component.localId } }, enabled = !busy) { Text("Retirer") }
                    }
                    OutlinedTextField(
                        value = component.quantity,
                        onValueChange = { value -> if (index >= 0) components[index] = component.copy(quantity = value) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Quantité") },
                        suffix = { Text(component.quantityUnit) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = foodQuery,
                onValueChange = { foodQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ajouter un aliment de la bibliothèque") },
                singleLine = true,
            )
        }
        items(candidates, key = { it.id }) { food ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(AppSpacing.sm), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(food.label, Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            components.add(EditableComponent(foodRefId = food.id, label = food.label, quantity = "100", quantityUnit = "g", portionId = null))
                            foodQuery = ""
                        },
                        enabled = !busy,
                    ) { Text("Ajouter") }
                }
            }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            val validItems = parsedItems()
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
                enabled = !busy && editedName.isNotBlank() && components.isNotEmpty() && validItems.size == components.size,
                onClick = { onSave(editedName.trim(), validItems) },
            ) { if (busy) CircularProgressIndicator() else Text("Enregistrer les modifications") }
        }
    }
}

private fun foodNutritionSummary(food: FoodReference): String {
    val nutrients = food.nutrientsPer100g
    val kcal = nutrients?.energyKcal?.let(::formatNumber) ?: "—"
    val protein = nutrients?.proteinG?.let(::formatNumber) ?: "—"
    return "$kcal kcal · $protein g prot. / 100 g"
}

private fun numberText(value: Double?): String = value?.let(::formatNumber).orEmpty()
private fun numberText(value: Double): String = formatNumber(value)
private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.FRANCE, "%.1f", value)
