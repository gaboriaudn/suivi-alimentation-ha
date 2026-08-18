package com.suivialimentation.android.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.data.repository.TodayData
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: TodayUiState,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    onAddMeal: () -> Unit,
    onContinueDraft: (MealWithItems) -> Unit,
    onDuplicateMeal: (MealWithItems) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aujourd'hui") },
                actions = { TextButton(onClick = onLogout) { Text("Déconnexion") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(state.connection)
            when {
                state.loading && state.content == null -> LoadingBody()
                state.content != null -> TodayContent(
                    data = state.content,
                    error = state.error,
                    onRetry = onRetry,
                    onAddMeal = onAddMeal,
                    onContinueDraft = onContinueDraft,
                    onDuplicateMeal = onDuplicateMeal,
                    duplicatingMealId = state.duplicatingMealId,
                )
                else -> ErrorBody(state.error ?: "Données indisponibles.", onRetry)
            }
        }
    }
}

@Composable
private fun ConnectionBanner(connection: TodayConnection) {
    val message = when (connection) {
        TodayConnection.Connected -> null
        TodayConnection.Disconnected -> "Déconnecté de Home Assistant"
        TodayConnection.Connecting -> "Connexion à Home Assistant…"
        is TodayConnection.Reconnecting -> "Reconnexion à Home Assistant (tentative ${connection.attempt})…"
        TodayConnection.AuthenticationRequired -> "Session Home Assistant expirée"
        is TodayConnection.Error -> connection.message
    }
    if (message != null) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingBody() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Chargement de la journée…")
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Réessayer") }
    }
}

@Composable
private fun TodayContent(
    data: TodayData,
    error: String?,
    onRetry: () -> Unit,
    onAddMeal: () -> Unit,
    onContinueDraft: (MealWithItems) -> Unit,
    onDuplicateMeal: (MealWithItems) -> Unit,
    duplicatingMealId: String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(data.profile.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(formatDate(data.localDate), style = MaterialTheme.typography.bodyMedium)
        }
        if (error != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error)
                        TextButton(onClick = onRetry) { Text("Actualiser") }
                    }
                }
            }
        }
        item {
            Text("Bilan nutritionnel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        item {
            NutrientRow(
                left = NutrientCardData("Calories", data.totals.energyKcal, data.activeGoal?.targets?.energyKcal, "kcal"),
                right = NutrientCardData("Protéines", data.totals.proteinG, data.activeGoal?.targets?.proteinG, "g"),
            )
        }
        item {
            NutrientRow(
                left = NutrientCardData("Glucides", data.totals.carbsG, data.activeGoal?.targets?.carbsG, "g"),
                right = NutrientCardData("Lipides", data.totals.fatG, data.activeGoal?.targets?.fatG, "g"),
            )
        }
        item {
            NutrientRow(
                left = NutrientCardData("Fibres", data.totals.fiberG, data.activeGoal?.targets?.fiberG, "g"),
                right = NutrientCardData("Sel", data.totals.saltG, data.activeGoal?.targets?.saltG, "g"),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Repas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Button(onClick = onAddMeal) { Text("Ajouter un repas") }
            }
        }
        if (data.meals.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("Aucun repas enregistré aujourd'hui.", modifier = Modifier.padding(16.dp))
                }
            }
        } else {
            items(data.meals, key = { it.meal.id }) { meal ->
                MealCard(meal, onContinueDraft, onDuplicateMeal, duplicatingMealId == meal.meal.id)
            }
        }
        item { Text("Révision serveur ${data.storeRevision}", style = MaterialTheme.typography.labelSmall) }
    }
}

private data class NutrientCardData(val title: String, val value: Double?, val target: Double?, val unit: String)

@Composable
private fun NutrientRow(left: NutrientCardData, right: NutrientCardData) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GoalCard(left.title, left.value, left.target, left.unit, Modifier.weight(1f))
        GoalCard(right.title, right.value, right.target, right.unit, Modifier.weight(1f))
    }
}

@Composable
private fun GoalCard(title: String, value: Double?, target: Double?, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val mainValue = "${formatNumber(value)} $unit"
            val targetValue = target?.let { " / ${formatNumber(it)} $unit" }.orEmpty()
            Text(mainValue + targetValue, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (value != null && target != null && target > 0.0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { (value / target).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MealCard(
    group: MealWithItems,
    onContinueDraft: (MealWithItems) -> Unit,
    onDuplicateMeal: (MealWithItems) -> Unit,
    duplicating: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                group.meal.label?.takeIf { it.isNotBlank() } ?: mealTypeLabel(group.meal.mealType),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (group.meal.status == "draft") Text("Brouillon", style = MaterialTheme.typography.labelMedium)
            group.meal.totalsSnapshot?.let { Text(snapshotSummary(it), style = MaterialTheme.typography.bodySmall) }
            if (group.items.isNotEmpty()) Spacer(Modifier.height(8.dp))
            group.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(item.labelSnapshot, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(quantityLabel(item.quantityValue, item.quantityUnit), style = MaterialTheme.typography.bodySmall)
                    item.nutritionSnapshot?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(snapshotSummary(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (group.meal.status == "draft") {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onContinueDraft(group) }) { Text("Continuer le brouillon") }
            } else if (group.meal.status == "validated") {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onDuplicateMeal(group) }, enabled = !duplicating) {
                    Text(if (duplicating) "Duplication…" else "Dupliquer ce repas")
                }
            }
        }
    }
}

private fun snapshotSummary(snapshot: NutrientSnapshot): String = buildList {
    snapshot.energyKcal?.let { add("${formatNumber(it)} kcal") }
    snapshot.proteinG?.let { add("${formatNumber(it)} g prot.") }
    snapshot.carbsG?.let { add("${formatNumber(it)} g gluc.") }
    snapshot.fatG?.let { add("${formatNumber(it)} g lip.") }
    snapshot.fiberG?.let { add("${formatNumber(it)} g fibres") }
    snapshot.saltG?.let { add("${formatNumber(it)} g sel") }
}.joinToString(" · ").ifBlank { "Valeurs non renseignées" }

private fun quantityLabel(value: Double?, unit: String?): String {
    if (value == null && unit.isNullOrBlank()) return "Quantité non renseignée"
    return listOfNotNull(value?.let(::formatNumber), unit?.takeIf { it.isNotBlank() }).joinToString(" ")
}

private fun formatNumber(value: Double?): String {
    if (value == null) return "—"
    val formatter = NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = 1 }
    return formatter.format(value)
}

private fun mealTypeLabel(type: String): String = when (type.lowercase()) {
    "breakfast" -> "Petit-déjeuner"
    "lunch" -> "Déjeuner"
    "dinner" -> "Dîner"
    "snack" -> "Collation"
    else -> type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRANCE) else it.toString() }
}

private fun formatDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.FRANCE))
}.getOrDefault(value)
