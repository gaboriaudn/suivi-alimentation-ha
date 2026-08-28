package com.suivialimentation.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.suivialimentation.android.BuildConfig
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ProfileScreen(modifier: Modifier = Modifier, state: ProfileUiState, onSave: (ProfileForm) -> Unit, onReload: () -> Unit, onLogout: () -> Unit) {
    val context = state.context
    if (state.loading && context == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Chargement du profil…", modifier = Modifier.padding(top = AppSpacing.sm)) }; return
    }
    if (context == null) {
        Column(modifier.fillMaxSize().padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) { Text("Profil", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(state.error ?: "Profil indisponible", color = MaterialTheme.colorScheme.error); Button(onClick = onReload, modifier = Modifier.fillMaxWidth()) { Text("Réessayer") } }; return
    }
    var form by remember(context.profile.revision, context.storeRevision) { mutableStateOf(ProfileForm.from(context)) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        item { Text("Profil", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(context.profile.displayName) }
        item { SectionCard("Informations personnelles") {
            CompactChoiceField("Sexe", form.sex, listOf("male" to "Homme", "female" to "Femme")) { form = form.copy(sex = it) }
            OutlinedTextField(form.birthDate, { form = form.copy(birthDate = it.filter { c -> c.isDigit() || c == '/' }.take(10)) }, Modifier.fillMaxWidth(), label = { Text("Date de naissance") }, placeholder = { Text("JJ/MM/AAAA") }, singleLine = true)
            OutlinedTextField(form.heightCm, { form = form.copy(heightCm = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Taille (cm)") }, singleLine = true)
        } }
        item { SectionCard("Objectif") {
            CompactChoiceField("Niveau d’activité", form.activityLevel, listOf("sedentary" to "Sédentaire", "light" to "Légère", "moderate" to "Modérée", "active" to "Active", "very_active" to "Très active")) { form = form.copy(activityLevel = it) }
            CompactChoiceField("Évolution recherchée", form.objective, listOf("lose" to "Perte de poids", "maintain" to "Maintien", "gain" to "Prise de poids")) { form = form.copy(objective = it) }
            OutlinedTextField(form.targetWeightKg, { form = form.copy(targetWeightKg = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Poids cible (kg)") }, singleLine = true)
        } }
        item { SectionCard("Poids actuel") {
            val resolved = context.resolvedWeight
            if (form.useHaWeight) {
                Text(if (resolved.available && resolved.valueKg != null) "${resolved.valueKg} kg" else "Indisponible", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Synchronisé automatiquement depuis Home Assistant", style = MaterialTheme.typography.bodyMedium)
                resolved.lastUpdated?.let { Text("Dernière mesure : ${formatHaDate(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                OutlinedTextField(form.manualWeightKg, { form = form.copy(manualWeightKg = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Poids actuel (kg)") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Synchronisation Home Assistant", Modifier.weight(1f)); Switch(form.useHaWeight, { form = form.copy(useHaWeight = it) }) }
            if (form.useHaWeight) {
                Text("Source : ${form.weightEntityId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        item { SectionCard("Objectifs nutritionnels") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Calcul automatique", Modifier.weight(1f)); Switch(form.automaticGoals, { form = form.copy(automaticGoals = it) }) }
            if (form.automaticGoals) {
                val rec = context.automaticRecommendation
                if (rec != null) {
                    Text("${rec.energyKcal?.toInt()} kcal / jour", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${rec.proteinG?.toInt()} g de protéines / jour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Calcul basé sur vos informations personnelles, votre poids actuel et votre niveau d’activité.", style = MaterialTheme.typography.bodySmall)
                } else Text("Complétez le sexe, la date de naissance, la taille et rendez le poids disponible pour calculer les objectifs.")
            } else {
                OutlinedTextField(form.manualEnergyKcal, { form = form.copy(manualEnergyKcal = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), label = { Text("Objectif calories (kcal)") }, singleLine = true)
                OutlinedTextField(form.manualProteinG, { form = form.copy(manualProteinG = it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, Modifier.fillMaxWidth(), label = { Text("Objectif protéines (g)") }, singleLine = true)
            }
        } }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }; state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item { Button({ onSave(form) }, Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget), enabled = !state.saving) { Text(if (state.saving) "Enregistrement…" else "Enregistrer le profil") } }
        item { SectionCard("Compte et connexion") { OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget)) { Text("Se déconnecter de Home Assistant") } } }
        item { Text("Version ${BuildConfig.VERSION_NAME} — build ${BuildConfig.VERSION_CODE}", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun SectionCard(title: String, content: @Composable () -> Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content() } } }

@Composable private fun CompactChoiceField(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = MinimumTouchTarget)) { Text(options.firstOrNull { it.first == value }?.second ?: "Choisir") }
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem({ Text(option.second) }, { expanded = false; onSelect(option.first) }) } }
    }
}

private fun formatHaDate(value: String): String = runCatching {
    OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE))
}.getOrDefault(value)
