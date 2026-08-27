package com.suivialimentation.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.suivialimentation.android.ui.components.AppSpacing

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    state: ProfileUiState,
    onSave: (ProfileForm) -> Unit,
    onReload: () -> Unit,
) {
    val context = state.context
    if (state.loading && context == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        return
    }
    if (context == null) {
        Column(modifier.fillMaxSize().padding(AppSpacing.lg)) {
            Text(state.error ?: "Profil indisponible")
            Button(onClick = onReload) { Text("Réessayer") }
        }
        return
    }
    var form by remember(context.profile.revision, context.storeRevision) { mutableStateOf(ProfileForm.from(context)) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        item {
            Text("Profil", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(context.profile.displayName, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text("Informations personnelles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    ChoiceField("Sexe", form.sex, listOf("male" to "Homme", "female" to "Femme")) { form = form.copy(sex = it) }
                    OutlinedTextField(form.birthDate, { form = form.copy(birthDate = it) }, Modifier.fillMaxWidth(), label = { Text("Date de naissance (AAAA-MM-JJ)") }, singleLine = true)
                    OutlinedTextField(form.heightCm, { form = form.copy(heightCm = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Taille (cm)") }, singleLine = true)
                    ChoiceField("Niveau d’activité", form.activityLevel, listOf("sedentary" to "Sédentaire", "light" to "Légère", "moderate" to "Modérée", "active" to "Active", "very_active" to "Très active")) { form = form.copy(activityLevel = it) }
                    ChoiceField("Objectif", form.objective, listOf("lose" to "Perte de poids", "maintain" to "Maintien", "gain" to "Prise de poids")) { form = form.copy(objective = it) }
                    OutlinedTextField(form.targetWeightKg, { form = form.copy(targetWeightKg = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Poids cible (kg)") }, singleLine = true)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text("Poids", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Synchroniser depuis Home Assistant")
                        Switch(checked = form.useHaWeight, onCheckedChange = { form = form.copy(useHaWeight = it) })
                    }
                    if (form.useHaWeight) {
                        OutlinedTextField(form.weightEntityId, { form = form.copy(weightEntityId = it) }, Modifier.fillMaxWidth(), label = { Text("Entité Home Assistant") }, singleLine = true)
                        val resolved = context.resolvedWeight
                        Text(if (resolved.available && resolved.valueKg != null) "Poids actuel : ${resolved.valueKg} kg" else "Poids Home Assistant indisponible", style = MaterialTheme.typography.bodyMedium)
                        resolved.lastUpdated?.let { Text("Dernière mesure : $it", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        OutlinedTextField(form.manualWeightKg, { form = form.copy(manualWeightKg = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Poids actuel (kg)") }, singleLine = true)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text("Objectifs nutritionnels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Calcul automatique")
                        Switch(checked = form.automaticGoals, onCheckedChange = { form = form.copy(automaticGoals = it) })
                    }
                    if (form.automaticGoals) {
                        val rec = context.automaticRecommendation
                        if (rec != null) {
                            Text("Proposition : ${rec.energyKcal?.toInt()} kcal · ${rec.proteinG?.toInt()} g de protéines", fontWeight = FontWeight.SemiBold)
                            Text("Calcul basé sur le profil, le poids actuel et le niveau d’activité.", style = MaterialTheme.typography.bodySmall)
                        } else Text("Complétez le profil et rendez le poids disponible pour calculer les objectifs.")
                    } else {
                        OutlinedTextField(form.manualEnergyKcal, { form = form.copy(manualEnergyKcal = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), label = { Text("Objectif calories (kcal)") }, singleLine = true)
                        OutlinedTextField(form.manualProteinG, { form = form.copy(manualProteinG = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Objectif protéines (g)") }, singleLine = true)
                    }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.message?.let { item { Text(it) } }
        item {
            Button(onClick = { onSave(form) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "Enregistrement…" else "Enregistrer") }
        }
    }
}

@Composable
private fun ChoiceField(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == value }?.second ?: "Choisir")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.second) }, onClick = { expanded = false; onSelect(option.first) }) }
        }
    }
}
