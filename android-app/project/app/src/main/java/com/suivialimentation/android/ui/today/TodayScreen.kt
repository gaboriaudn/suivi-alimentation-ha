package com.suivialimentation.android.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.data.repository.TodayData
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import com.suivialimentation.android.ui.components.SectionHeader
import com.suivialimentation.android.ui.components.ScreenHeading
import com.suivialimentation.android.ui.theme.NutritionCalories
import com.suivialimentation.android.ui.theme.NutritionProteins
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun TodayScreen(modifier: Modifier = Modifier, state: TodayUiState, onRetry: () -> Unit, onContinueDraft: (MealWithItems) -> Unit, onDuplicateMeal: (MealWithItems) -> Unit, onCorrectMeal: (MealWithItems) -> Unit, onDeleteMeal: (MealWithItems) -> Unit, onPreviousDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit) {
    Column(modifier = modifier.fillMaxSize()) {
        ConnectionBanner(state.connection)
        when {
            state.loading && state.content == null -> LoadingBody()
            state.content != null -> TodayContent(state.content, state.error, onRetry, onContinueDraft, onDuplicateMeal, onCorrectMeal, onDeleteMeal, onPreviousDay, onNextDay, onToday, state.duplicatingMealId, state.deletingMealId)
            else -> ErrorBody(state.error ?: "Données indisponibles.", onRetry)
        }
    }
}

@Composable private fun ConnectionBanner(connection: TodayConnection) { val message = when (connection) { TodayConnection.Connected -> null; TodayConnection.Disconnected -> "Déconnecté de Home Assistant"; TodayConnection.Connecting -> "Connexion à Home Assistant…"; is TodayConnection.Reconnecting -> "Reconnexion à Home Assistant (tentative ${connection.attempt})…"; TodayConnection.AuthenticationRequired -> "Session Home Assistant expirée"; is TodayConnection.Error -> connection.message }; if (message != null) Card(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) { Text(message, modifier = Modifier.padding(AppSpacing.md), style = MaterialTheme.typography.bodyMedium) } }
@Composable private fun LoadingBody() { Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(AppSpacing.md)); Text("Chargement de la journée…") } }
@Composable private fun ErrorBody(message: String, onRetry: () -> Unit) { Column(modifier = Modifier.padding(AppSpacing.xl)) { Text(message, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(AppSpacing.md)); Button(onClick = onRetry) { Text("Réessayer") } } }

@Composable
private fun TodayContent(data: TodayData, error: String?, onRetry: () -> Unit, onContinueDraft: (MealWithItems) -> Unit, onDuplicateMeal: (MealWithItems) -> Unit, onCorrectMeal: (MealWithItems) -> Unit, onDeleteMeal: (MealWithItems) -> Unit, onPreviousDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit, duplicatingMealId: String?, deletingMealId: String?) {
    var pendingDeletion by remember { mutableStateOf<MealWithItems?>(null) }
    pendingDeletion?.let { meal ->
        val draft = meal.meal.status == "draft"
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(if (draft) "Supprimer ce brouillon ?" else "Supprimer ce repas ?") },
            text = { Text(if (draft) "Ce brouillon sera supprimé de la journée et ne vous sera plus proposé." else "Le repas sera retiré des totaux et de l’historique visible. Sa trace restera archivée dans Home Assistant.") },
            confirmButton = { Button(onClick = { pendingDeletion = null; onDeleteMeal(meal) }) { Text(if (draft) "Supprimer le brouillon" else "Supprimer") } },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Annuler") } },
        )
    }
    val zone = runCatching { ZoneId.of(data.profile.defaultTimeZone) }.getOrDefault(ZoneId.systemDefault()); val isToday = data.localDate == LocalDate.now(zone).toString(); val orderedMeals = remember(data.meals) { data.meals.sortedWith(compareBy<MealWithItems>({ mealTypeOrder(it.meal.mealType) }, { it.meal.createdAt })) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        item { ScreenHeading(title = if (isToday) "Aujourd’hui" else formatDate(data.localDate), subtitle = data.profile.displayName); DateNavigator(data.localDate, isToday, onPreviousDay, onNextDay, onToday) }
        if (error != null) item { Card(modifier = Modifier.fillMaxWidth()) { Row(modifier = Modifier.fillMaxWidth().padding(AppSpacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = onRetry) { Text("Actualiser") } } } }
        item { PrimaryNutritionSummary(data) }; item { SectionHeader("Repas") }
        if (orderedMeals.isEmpty()) item { Card(modifier = Modifier.fillMaxWidth()) { Text("Aucun repas enregistré pour cette journée.", modifier = Modifier.padding(AppSpacing.lg)) } }
        else items(orderedMeals, key = { it.meal.id }) { meal -> MealCard(meal, onContinueDraft, onDuplicateMeal, onCorrectMeal, { pendingDeletion = it }, duplicatingMealId == meal.meal.id || deletingMealId == meal.meal.id) }
    }
}

@Composable private fun DateNavigator(localDate: String, isToday: Boolean, onPreviousDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit) { Column(modifier = Modifier.fillMaxWidth()) { Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { IconButton(onClick = onPreviousDay, modifier = Modifier.heightIn(min = MinimumTouchTarget)) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Jour précédent") }; Text(formatDate(localDate), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium); IconButton(onClick = onNextDay, enabled = !isToday, modifier = Modifier.heightIn(min = MinimumTouchTarget)) { Icon(Icons.Filled.ChevronRight, contentDescription = "Jour suivant") } }; if (!isToday) TextButton(onClick = onToday, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Revenir à aujourd’hui") } } }
private data class NutrientCardData(val title: String, val value: Double?, val target: Double?, val unit: String)
@Composable private fun PrimaryNutritionSummary(data: TodayData) { Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) { GoalCard(NutrientCardData("Calories", data.totals.energyKcal, data.activeGoal?.targets?.energyKcal, "kcal"), NutritionCalories, Modifier.weight(1f)); GoalCard(NutrientCardData("Protéines", data.totals.proteinG, data.activeGoal?.targets?.proteinG, "g"), NutritionProteins, Modifier.weight(1f)) }; Card(modifier = Modifier.fillMaxWidth()) { Text(listOf("Glucides ${formatNumber(data.totals.carbsG)} g", "Lipides ${formatNumber(data.totals.fatG)} g", "Fibres ${formatNumber(data.totals.fiberG)} g", "Sel ${formatNumber(data.totals.saltG)} g").joinToString(" · "), modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm), style = MaterialTheme.typography.bodySmall) } } }
@Composable private fun GoalCard(data: NutrientCardData, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) { Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f))) { Column(Modifier.padding(AppSpacing.md)) { Text(data.title, style = MaterialTheme.typography.titleSmall); val target = data.target?.let { " / ${formatNumber(it)} ${data.unit}" }.orEmpty(); Text("${formatNumber(data.value)} ${data.unit}$target", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold); if (data.value != null && data.target != null && data.target > 0.0) { Spacer(Modifier.height(AppSpacing.sm)); LinearProgressIndicator(progress = { (data.value / data.target).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth()) } } } }

@Composable
private fun MealCard(group: MealWithItems, onContinueDraft: (MealWithItems) -> Unit, onDuplicateMeal: (MealWithItems) -> Unit, onCorrectMeal: (MealWithItems) -> Unit, onDeleteMeal: (MealWithItems) -> Unit, busy: Boolean) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { if (group.meal.status == "draft") onContinueDraft(group) else onCorrectMeal(group) }) {
        Column(Modifier.padding(AppSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) { Text(group.meal.label?.takeIf { it.isNotBlank() } ?: mealTypeLabel(group.meal.mealType), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); if (group.meal.status == "draft") Text("Brouillon", style = MaterialTheme.typography.labelMedium); group.meal.totalsSnapshot?.let { Text(primarySnapshotSummary(it), style = MaterialTheme.typography.bodySmall) } }
                if (group.meal.status == "validated") Box { TextButton(onClick = { menuExpanded = true }, enabled = !busy, modifier = Modifier.heightIn(min = MinimumTouchTarget)) { Icon(Icons.Filled.MoreVert, contentDescription = "Actions du repas") }; DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) { DropdownMenuItem(text = { Text("Modifier le repas") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menuExpanded = false; onCorrectMeal(group) }); DropdownMenuItem(text = { Text("Dupliquer") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menuExpanded = false; onDuplicateMeal(group) }); HorizontalDivider(); DropdownMenuItem(text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) }, onClick = { menuExpanded = false; onDeleteMeal(group) }) } }
            }
            if (group.items.isNotEmpty()) Spacer(Modifier.height(AppSpacing.sm))
            group.items.forEachIndexed { index, item -> if (index > 0) HorizontalDivider(Modifier.padding(vertical = AppSpacing.sm)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) { Text(item.labelSnapshot, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text(quantityLabel(item.quantityValue, item.quantityUnit), style = MaterialTheme.typography.bodySmall) } }
            if (group.meal.status == "draft") {
                Spacer(Modifier.height(AppSpacing.sm)); Button(onClick = { onContinueDraft(group) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget)) { Text("Continuer le brouillon") }
                TextButton(onClick = { onDeleteMeal(group) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget)) { Icon(Icons.Filled.DeleteOutline, null); Spacer(Modifier.width(AppSpacing.sm)); Text("Supprimer le brouillon") }
            }
        }
    }
}

private fun mealTypeOrder(type: String): Int = when (type.lowercase()) { "breakfast" -> 0; "lunch" -> 1; "dinner" -> 2; "snack" -> 3; else -> 4 }
private fun primarySnapshotSummary(snapshot: NutrientSnapshot): String = buildList { snapshot.energyKcal?.let { add("${formatNumber(it)} kcal") }; snapshot.proteinG?.let { add("${formatNumber(it)} g prot.") } }.joinToString(" · ").ifBlank { "Valeurs non renseignées" }
private fun quantityLabel(value: Double?, unit: String?): String = if (value == null && unit.isNullOrBlank()) "—" else listOfNotNull(value?.let(::formatNumber), unit?.takeIf { it.isNotBlank() }).joinToString(" ")
private fun formatNumber(value: Double?): String = if (value == null) "—" else NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = 1 }.format(value)
private fun mealTypeLabel(type: String): String = when (type.lowercase()) { "breakfast" -> "Petit-déjeuner"; "lunch" -> "Déjeuner"; "dinner" -> "Dîner"; "snack" -> "Collation"; else -> type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRANCE) else it.toString() } }
private fun formatDate(value: String): String = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.FRANCE)) }.getOrDefault(value)
