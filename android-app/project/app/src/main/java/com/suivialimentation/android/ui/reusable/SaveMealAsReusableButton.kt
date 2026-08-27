package com.suivialimentation.android.ui.reusable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.suivialimentation.android.ui.components.AppSpacing

@Composable
fun SaveMealAsReusableButton(
    modifier: Modifier = Modifier,
    defaultName: String,
    enabled: Boolean,
    onSaveAsTemplate: (String) -> Unit,
    onSaveAsRecipe: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var name by remember(defaultName) { mutableStateOf(defaultName) }

    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        modifier = modifier,
    ) {
        Text("Réutiliser")
    }

    if (!open) return

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Réutiliser ce repas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text(
                    "Enregistrez le repas actuellement affiché comme modèle réutilisable, sans modifier le repas d’origine.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nom") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Button(
                    onClick = {
                        onSaveAsTemplate(name.trim())
                        open = false
                    },
                    enabled = enabled && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer comme repas type") }
                OutlinedButton(
                    onClick = {
                        onSaveAsRecipe(name.trim())
                        open = false
                    },
                    enabled = enabled && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer comme recette") }
            }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text("Annuler") }
        },
    )
}
