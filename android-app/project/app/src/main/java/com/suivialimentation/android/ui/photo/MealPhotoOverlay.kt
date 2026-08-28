package com.suivialimentation.android.ui.photo

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.suivialimentation.android.data.photo.PhotoAnalysisMode
import com.suivialimentation.android.data.photo.PhotoFoodSuggestion
import com.suivialimentation.android.ui.components.AppSpacing
import com.suivialimentation.android.ui.components.MinimumTouchTarget
import java.io.File

@Composable
fun MealPhotoOverlay(
    modifier: Modifier = Modifier,
    state: PhotoMealUiState,
    busy: Boolean,
    onAnalyzeFood: (Uri) -> Unit,
    onAnalyzeMeal: (Uri) -> Unit,
    onApplySuggestions: (List<PhotoFoodSuggestion>) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var showSourceChoice by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraMode by remember { mutableStateOf(PhotoAnalysisMode.MEAL) }
    var pendingPickerMode by remember { mutableStateOf(PhotoAnalysisMode.MEAL) }

    fun analyze(uri: Uri, mode: PhotoAnalysisMode) {
        if (mode == PhotoAnalysisMode.FOOD) onAnalyzeFood(uri) else onAnalyzeMeal(uri)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyze(uri, pendingPickerMode)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) analyze(uri, pendingCameraMode)
        pendingCameraUri = null
    }

    ExtendedFloatingActionButton(
        onClick = { showSourceChoice = true },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(if (state.loading) "Analyse…" else "Ajouter par photo")
    }

    if (showSourceChoice) {
        AlertDialog(
            onDismissRequest = { if (!state.loading && !busy) showSourceChoice = false },
            title = { Text("Ajouter par photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text("Choisissez ce que la photo représente. Les valeurs nutritionnelles seront ensuite recherchées dans vos aliments ou dans CIQUAL.")
                    PhotoSourceBlock(
                        title = "Un aliment",
                        enabled = !state.loading && !busy,
                        onCamera = {
                            val uri = createCameraUri(context, "food")
                            pendingCameraMode = PhotoAnalysisMode.FOOD
                            pendingCameraUri = uri
                            showSourceChoice = false
                            camera.launch(uri)
                        },
                        onGallery = {
                            pendingPickerMode = PhotoAnalysisMode.FOOD
                            showSourceChoice = false
                            picker.launch("image/*")
                        },
                    )
                    PhotoSourceBlock(
                        title = "Une assiette ou un repas",
                        enabled = !state.loading && !busy,
                        onCamera = {
                            val uri = createCameraUri(context, "meal")
                            pendingCameraMode = PhotoAnalysisMode.MEAL
                            pendingCameraUri = uri
                            showSourceChoice = false
                            camera.launch(uri)
                        },
                        onGallery = {
                            pendingPickerMode = PhotoAnalysisMode.MEAL
                            showSourceChoice = false
                            picker.launch("image/*")
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSourceChoice = false }) { Text("Annuler") } },
        )
    }

    if (state.loading || state.error != null || state.suggestions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!state.loading && !busy) onClear() },
            title = {
                Text(
                    when {
                        state.loading -> "Analyse de la photo"
                        state.error != null -> "Analyse impossible"
                        state.mode == PhotoAnalysisMode.FOOD -> "Aliment reconnu"
                        else -> "Repas reconnu"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    if (state.loading) {
                        Text("Analyse de l’image en cours…")
                    }
                    state.error?.let { Text(it) }
                    state.title?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    state.suggestions.forEach { suggestion ->
                        Text("• ${suggestion.label} · ${formatGrams(suggestion.estimatedGrams)} g")
                    }
                    if (state.suggestions.isNotEmpty()) {
                        Text(
                            "Les quantités sont des estimations. Vous pourrez les corriger dans le repas après l’ajout.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                if (state.suggestions.isNotEmpty()) {
                    Button(
                        onClick = { onApplySuggestions(state.suggestions) },
                        enabled = !busy,
                    ) { Text("Ajouter au repas") }
                }
            },
            dismissButton = {
                if (!state.loading) {
                    TextButton(onClick = onClear, enabled = !busy) { Text("Annuler") }
                }
            },
        )
    }
}

@Composable
private fun PhotoSourceBlock(
    title: String,
    enabled: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Button(
                onClick = onCamera,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = MinimumTouchTarget),
            ) { Text("Prendre une photo") }
            OutlinedButton(
                onClick = onGallery,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = MinimumTouchTarget),
            ) { Text("Galerie") }
        }
    }
}

private fun createCameraUri(context: Context, prefix: String): Uri {
    val imageDir = File(context.cacheDir, "images").apply { mkdirs() }
    val imageFile = File.createTempFile("${prefix}_", ".jpg", imageDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
}

private fun formatGrams(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.FRANCE, "%.1f", value)
