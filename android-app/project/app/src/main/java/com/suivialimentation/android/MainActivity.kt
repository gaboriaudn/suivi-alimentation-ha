package com.suivialimentation.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.suivialimentation.android.data.photo.appendPhotoSuggestionsToMeal
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.di.AppContainer
import com.suivialimentation.android.ui.AppEvent
import com.suivialimentation.android.ui.AppUiState
import com.suivialimentation.android.ui.AppViewModel
import com.suivialimentation.android.ui.LoginScreen
import com.suivialimentation.android.ui.add.AddScreen
import com.suivialimentation.android.ui.add.LibraryCreateScreen
import com.suivialimentation.android.ui.add.LibraryCreationKind
import com.suivialimentation.android.ui.features.FeatureHubScreen
import com.suivialimentation.android.ui.features.FeatureHubSection
import com.suivialimentation.android.ui.features.FeatureHubViewModel
import com.suivialimentation.android.ui.library.LibraryScreen
import com.suivialimentation.android.ui.mealentry.MealEntryScreen
import com.suivialimentation.android.ui.mealentry.MealEntryViewModel
import com.suivialimentation.android.ui.mealentry.MealTypeSelectionScreen
import com.suivialimentation.android.ui.photo.MealPhotoOverlay
import com.suivialimentation.android.ui.photo.PhotoMealViewModel
import com.suivialimentation.android.ui.profile.ProfileScreen
import com.suivialimentation.android.ui.profile.ProfileViewModel
import com.suivialimentation.android.ui.reusable.SaveMealAsReusableButton
import com.suivialimentation.android.ui.theme.SuiviAlimentationTheme
import com.suivialimentation.android.ui.today.TodayScreen
import com.suivialimentation.android.ui.today.TodayViewModel
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as SuiviAlimentationApplication).container
    private val appViewModel: AppViewModel by viewModels { AppViewModel.Factory(container.authManager, container.repository) }
    private val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result -> when (result.resultCode) { AuthTabIntent.RESULT_OK -> result.resultUri?.let(::handleCallback) ?: appViewModel.cancelLogin(); else -> appViewModel.cancelLogin() } }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); lifecycleScope.launch { appViewModel.events.collect { event -> if (event is AppEvent.OpenAuthorization) { val scheme = Uri.parse(container.oauthConfig.redirectUri).scheme ?: error("Le schéma de retour OAuth est invalide."); AuthTabIntent.Builder().build().launch(authTabLauncher, event.url, scheme) } } }; setContent { SuiviAlimentationTheme { AppRoot(appViewModel, container) } }; handleCallback(intent?.data) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleCallback(intent.data) }
    private fun handleCallback(uri: Uri?) { if (uri == null) return; appViewModel.handleAuthCallback(uri); intent?.data = null }
}

private data class MealEntryRoute(val token: String, val profileId: String, val localDate: String, val draft: MealWithItems? = null, val existingMeals: List<MealWithItems> = emptyList(), val initialMealType: String? = null)
private enum class MainDestination(val label: String) { TODAY("Aujourd’hui"), ADD("Ajouter"), LIBRARY("Bibliothèque"), HISTORY("Historique"), PROFILE("Profil") }

@Composable
private fun AppRoot(appViewModel: AppViewModel, container: AppContainer) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        AppUiState.Loading -> FullScreenLoading("Restauration de la session…")
        is AppUiState.SignedOut -> LoginScreen(false, s.error, !container.oauthConfig.isPlaceholder, s.instanceUrl, appViewModel::startLogin, appViewModel::cancelLogin)
        AppUiState.Authenticating -> LoginScreen(true, null, !container.oauthConfig.isPlaceholder, "", appViewModel::startLogin, appViewModel::cancelLogin)
        is AppUiState.SignedIn -> SignedInRoot(s.sessionGeneration, container, appViewModel::logout)
    }
}

@Composable
private fun SignedInRoot(sessionGeneration: Long, container: AppContainer, onLogout: () -> Unit) {
    val todayViewModel: TodayViewModel = viewModel(key = "today-$sessionGeneration", factory = TodayViewModel.Factory(container.repository))
    val todayState by todayViewModel.state.collectAsStateWithLifecycle()
    var route by remember(sessionGeneration) { mutableStateOf<MealEntryRoute?>(null) }
    var creationKind by remember(sessionGeneration) { mutableStateOf<LibraryCreationKind?>(null) }
    var destination by remember(sessionGeneration) { mutableStateOf(MainDestination.TODAY) }
    LaunchedEffect(todayState.duplicatedDraft?.meal?.id) { val draft = todayState.duplicatedDraft ?: return@LaunchedEffect; val content = todayState.content ?: return@LaunchedEffect; route = MealEntryRoute("${draft.meal.id}-${UUID.randomUUID()}", content.profile.id, content.localDate, draft); todayViewModel.consumeDuplicatedDraft() }
    val content = todayState.content

    if (route == null && creationKind != null && content != null) {
        LibraryCreateScreen(
            kind = creationKind!!,
            profileId = content.profile.id,
            nutritionRepository = container.repository,
            featureRepository = container.featureRepository,
            onBack = { creationKind = null },
            onDone = { creationKind = null; destination = MainDestination.ADD; todayViewModel.retry() },
        )
        return
    }

    if (route == null && content != null && destination == MainDestination.PROFILE) {
        val vm: ProfileViewModel = viewModel(key = "profile-${content.profile.id}", factory = ProfileViewModel.Factory(container.repository, content.profile.id)); val profileState by vm.state.collectAsStateWithLifecycle()
        Scaffold(bottomBar = { MainNavigationBar(destination, { destination = MainDestination.ADD }) { destination = it } }) { p -> ProfileScreen(Modifier.padding(p), profileState, vm::save, vm::reload, onLogout) }
        return
    }

    if (route == null && content != null && destination == MainDestination.ADD) {
        Scaffold(bottomBar = { MainNavigationBar(destination, { destination = MainDestination.ADD }) { destination = it } }) { p ->
            AddScreen(
                modifier = Modifier.padding(p),
                onAddMeal = { mealType -> route = MealEntryRoute(UUID.randomUUID().toString(), content.profile.id, content.localDate, existingMeals = content.meals, initialMealType = mealType) },
                onCreateFood = { creationKind = LibraryCreationKind.FOOD },
                onCreateRecipe = { creationKind = LibraryCreationKind.RECIPE },
                onCreateMealTemplate = { creationKind = LibraryCreationKind.MEAL_TEMPLATE },
            )
        }
        return
    }

    if (route == null && content != null && destination == MainDestination.LIBRARY) {
        Scaffold(bottomBar = { MainNavigationBar(destination, { destination = MainDestination.ADD }) { destination = it } }) { p ->
            LibraryScreen(
                modifier = Modifier.padding(p),
                profileId = content.profile.id,
                repository = container.libraryRepository,
            )
        }
        return
    }

    if (route == null && content != null && destination == MainDestination.HISTORY) {
        val featureVm: FeatureHubViewModel = viewModel(key = "features-${content.profile.id}-${content.localDate}", factory = FeatureHubViewModel.Factory(container.featureRepository, container.repository, content.profile.id, content.localDate)); val featureState by featureVm.state.collectAsStateWithLifecycle(); val photoVm: PhotoMealViewModel = viewModel(key = "photo-${content.profile.id}-${content.localDate}", factory = PhotoMealViewModel.Factory(container.photoAnalysisService)); val photoState by photoVm.state.collectAsStateWithLifecycle()
        Scaffold(bottomBar = { MainNavigationBar(destination, { destination = MainDestination.ADD }) { destination = it } }) { p -> FeatureHubScreen(Modifier.padding(p), FeatureHubSection.HISTORY, featureState, photoState, content.meals, photoVm::analyzeFood, photoVm::analyzeMeal, photoVm::clear, featureVm::createFromPhoto, featureVm::saveRecipe, featureVm::createFromRecipe, {}, { destination = MainDestination.TODAY; photoVm.clear(); todayViewModel.retry() }) }
        return
    }

    if (route == null) {
        Scaffold(bottomBar = { MainNavigationBar(destination, { destination = MainDestination.ADD }) { destination = it } }) { p -> TodayScreen(Modifier.padding(p), todayState, todayViewModel::retry, { draft -> val c = todayState.content ?: return@TodayScreen; route = MealEntryRoute("${draft.meal.id}-${UUID.randomUUID()}", c.profile.id, c.localDate, draft) }, todayViewModel::duplicateMeal, todayViewModel::correctMeal, todayViewModel::deleteMeal, todayViewModel::previousDay, todayViewModel::nextDay, todayViewModel::today) }
        return
    }

    val r = route ?: return
    val vm: MealEntryViewModel = viewModel(key = "meal-entry-${r.token}", factory = MealEntryViewModel.Factory(container.repository, r.profileId, r.localDate, r.draft, r.existingMeals)); val mealState by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(r.initialMealType) { if (r.draft == null && mealState.mealType == null) r.initialMealType?.let(vm::selectMealType) }
    if (r.draft == null && mealState.mealType == null) { if (r.initialMealType != null) FullScreenLoading("Préparation du repas…") else MealTypeSelectionScreen(vm::selectMealType) { route = null; todayViewModel.retry() }; return }

    val context = LocalContext.current; val scope = rememberCoroutineScope(); val scanner = remember(context) { GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).enableAutoZoom().build()) }; val photoVm: PhotoMealViewModel = viewModel(key = "meal-photo-${r.token}", factory = PhotoMealViewModel.Factory(container.photoAnalysisService)); val photoState by photoVm.state.collectAsStateWithLifecycle(); var applyingPhoto by remember(r.token) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        MealEntryScreen(mealState, vm::selectMealType, vm::updateQuery, vm::search, vm::selectFood, vm::selectPersonalFood, vm::updateBarcode, { scanner.startScan().addOnSuccessListener { it.rawValue?.let(vm::barcodeScanned) }.addOnFailureListener { vm.barcodeScanFailed(it.localizedMessage) } }, vm::lookupBarcode, vm::selectOffProduct, vm::selectQuickFood, vm::toggleFavorite, vm::selectPortion, vm::dismissFood, vm::updateQuantity, vm::addSelectedFood, vm::complementExistingMeal, vm::createSeparateMeal, vm::cancelExistingMealChoice, vm::editItem, vm::updateEditQuantity, vm::confirmItemEdit, vm::dismissItemEdit, vm::removeItem, vm::validateMeal, { route = null; todayViewModel.retry() }, { route = null; todayViewModel.retry() })
        MealPhotoOverlay(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp), state = photoState, busy = mealState.mutating || applyingPhoto, onAnalyzeFood = photoVm::analyzeFood, onAnalyzeMeal = photoVm::analyzeMeal, onApplySuggestions = { suggestions -> val mealType = mealState.mealType ?: return@MealPhotoOverlay; scope.launch { applyingPhoto = true; val currentDraft = mealState.draftMeal?.let { MealWithItems(it, mealState.items) }; runCatching { appendPhotoSuggestionsToMeal(container.repository, r.profileId, mealType, r.localDate, currentDraft, suggestions) }.onSuccess { updatedDraft -> photoVm.clear(); route = r.copy(token = UUID.randomUUID().toString(), draft = updatedDraft, initialMealType = null) }.onFailure { Toast.makeText(context, it.message ?: "Ajout depuis la photo impossible.", Toast.LENGTH_LONG).show() }; applyingPhoto = false } }, onClear = photoVm::clear)
        val meal = mealState.draftMeal
        if (meal != null && mealState.items.isNotEmpty()) SaveMealAsReusableButton(modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp), defaultName = meal.label.orEmpty().ifBlank { mealTypeLabel(meal.mealType) }, enabled = !mealState.mutating, onSaveAsTemplate = { name -> scope.launch { runCatching { container.featureRepository.saveMealAsTemplate(meal.id, name) }.onSuccess { Toast.makeText(context, "Repas type enregistré.", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(context, it.message ?: "Enregistrement impossible.", Toast.LENGTH_LONG).show() } } }, onSaveAsRecipe = { name -> scope.launch { runCatching { container.featureRepository.saveMealAsRecipe(meal.id, name) }.onSuccess { Toast.makeText(context, "Recette enregistrée.", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(context, it.message ?: "Enregistrement impossible.", Toast.LENGTH_LONG).show() } } })
    }
}

private fun mealTypeLabel(value: String): String = when (value) { "breakfast" -> "Petit-déjeuner"; "lunch" -> "Déjeuner"; "dinner" -> "Dîner"; "snack" -> "Collation"; else -> "Repas" }
@Composable private fun MainNavigationBar(current: MainDestination, onAdd: () -> Unit, onNavigate: (MainDestination) -> Unit) { NavigationBar { MainNavigationItem(MainDestination.TODAY, current == MainDestination.TODAY, Modifier.weight(1f)) { onNavigate(MainDestination.TODAY) }; MainNavigationItem(MainDestination.ADD, current == MainDestination.ADD, Modifier.weight(1f), onAdd); MainNavigationItem(MainDestination.LIBRARY, current == MainDestination.LIBRARY, Modifier.weight(1f)) { onNavigate(MainDestination.LIBRARY) }; MainNavigationItem(MainDestination.HISTORY, current == MainDestination.HISTORY, Modifier.weight(1f)) { onNavigate(MainDestination.HISTORY) }; MainNavigationItem(MainDestination.PROFILE, current == MainDestination.PROFILE, Modifier.weight(1f)) { onNavigate(MainDestination.PROFILE) } } }
@Composable private fun MainNavigationItem(destination: MainDestination, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) { val icon = when (destination) { MainDestination.TODAY -> Icons.Filled.Home; MainDestination.ADD -> Icons.Filled.AddCircle; MainDestination.LIBRARY -> Icons.Filled.List; MainDestination.HISTORY -> Icons.Filled.CalendarMonth; MainDestination.PROFILE -> Icons.Filled.Person }; val color = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant; Column(modifier.clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { Icon(icon, destination.label, tint = color); Text(destination.label, color = color, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, maxLines = 1) } }
@Composable private fun FullScreenLoading(label: String) { Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(label) } }
