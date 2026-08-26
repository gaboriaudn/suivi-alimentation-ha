package com.suivialimentation.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.di.AppContainer
import com.suivialimentation.android.ui.AppEvent
import com.suivialimentation.android.ui.AppUiState
import com.suivialimentation.android.ui.AppViewModel
import com.suivialimentation.android.ui.LoginScreen
import com.suivialimentation.android.ui.features.FeatureHubScreen
import com.suivialimentation.android.ui.features.FeatureHubSection
import com.suivialimentation.android.ui.features.FeatureHubViewModel
import com.suivialimentation.android.ui.mealentry.MealEntryScreen
import com.suivialimentation.android.ui.mealentry.MealEntryViewModel
import com.suivialimentation.android.ui.mealentry.MealTypeSelectionScreen
import com.suivialimentation.android.ui.photo.PhotoMealViewModel
import com.suivialimentation.android.ui.theme.SuiviAlimentationTheme
import com.suivialimentation.android.ui.today.TodayScreen
import com.suivialimentation.android.ui.today.TodayViewModel
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as SuiviAlimentationApplication).container

    private val appViewModel: AppViewModel by viewModels {
        AppViewModel.Factory(container.authManager, container.repository)
    }

    private val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> {
                result.resultUri?.let(::handleCallback) ?: appViewModel.cancelLogin()
            }
            AuthTabIntent.RESULT_CANCELED -> appViewModel.cancelLogin()
            else -> appViewModel.cancelLogin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            appViewModel.events.collect { event ->
                when (event) {
                    is AppEvent.OpenAuthorization -> {
                        val redirectScheme = Uri.parse(container.oauthConfig.redirectUri).scheme
                            ?: error("Le schéma de retour OAuth est invalide.")
                        AuthTabIntent.Builder()
                            .build()
                            .launch(authTabLauncher, event.url, redirectScheme)
                    }
                }
            }
        }
        setContent {
            SuiviAlimentationTheme {
                AppRoot(appViewModel, container)
            }
        }
        handleCallback(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent.data)
    }

    private fun handleCallback(uri: Uri?) {
        if (uri == null) return
        appViewModel.handleAuthCallback(uri)
        intent?.data = null
    }
}

private data class MealEntryRoute(
    val token: String,
    val profileId: String,
    val localDate: String,
    val draft: MealWithItems? = null,
    val existingMeals: List<MealWithItems> = emptyList(),
)

private enum class MainDestination(val label: String) {
    TODAY("Aujourd’hui"),
    ADD("Ajouter"),
    HISTORY("Historique"),
    MORE("Plus"),
}

@Composable
private fun AppRoot(appViewModel: AppViewModel, container: AppContainer) {
    val appState by appViewModel.state.collectAsStateWithLifecycle()
    when (val state = appState) {
        AppUiState.Loading -> FullScreenLoading("Restauration de la session…")
        is AppUiState.SignedOut -> LoginScreen(
            authenticating = false,
            error = state.error,
            oauthConfigured = !container.oauthConfig.isPlaceholder,
            onLogin = appViewModel::startLogin,
            onCancel = appViewModel::cancelLogin,
        )
        AppUiState.Authenticating -> LoginScreen(
            authenticating = true,
            error = null,
            oauthConfigured = !container.oauthConfig.isPlaceholder,
            onLogin = appViewModel::startLogin,
            onCancel = appViewModel::cancelLogin,
        )
        is AppUiState.SignedIn -> SignedInRoot(state.sessionGeneration, appViewModel, container)
    }
}

@Composable
private fun SignedInRoot(sessionGeneration: Long, appViewModel: AppViewModel, container: AppContainer) {
    val todayViewModel: TodayViewModel = viewModel(
        key = "today-$sessionGeneration",
        factory = TodayViewModel.Factory(container.repository),
    )
    val todayState by todayViewModel.state.collectAsStateWithLifecycle()
    var mealEntryRoute by remember(sessionGeneration) { mutableStateOf<MealEntryRoute?>(null) }
    var destination by remember(sessionGeneration) { mutableStateOf(MainDestination.TODAY) }
    val route = mealEntryRoute

    LaunchedEffect(todayState.duplicatedDraft?.meal?.id) {
        val duplicated = todayState.duplicatedDraft ?: return@LaunchedEffect
        val content = todayState.content ?: return@LaunchedEffect
        mealEntryRoute = MealEntryRoute(
            token = "${duplicated.meal.id}-${UUID.randomUUID()}",
            profileId = content.profile.id,
            localDate = content.localDate,
            draft = duplicated,
        )
        todayViewModel.consumeDuplicatedDraft()
    }

    val content = todayState.content
    val openMealEntry: () -> Unit = {
        val current = todayState.content
        if (current != null) {
            mealEntryRoute = MealEntryRoute(
                token = UUID.randomUUID().toString(),
                profileId = current.profile.id,
                localDate = current.localDate,
                existingMeals = current.meals,
            )
        }
    }
    if (route == null && destination in setOf(MainDestination.HISTORY, MainDestination.MORE) && content != null) {
        val featureViewModel: FeatureHubViewModel = viewModel(
            key = "features-${content.profile.id}-${content.localDate}",
            factory = FeatureHubViewModel.Factory(
                repository = container.featureRepository,
                nutritionRepository = container.repository,
                profileId = content.profile.id,
                localDate = content.localDate,
            ),
        )
        val featureState by featureViewModel.state.collectAsStateWithLifecycle()
        val photoViewModel: PhotoMealViewModel = viewModel(
            key = "photo-${content.profile.id}-${content.localDate}",
            factory = PhotoMealViewModel.Factory(container.photoAnalysisService),
        )
        val photoState by photoViewModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(featureState.createdDraft?.meal?.id) {
            val draft = featureState.createdDraft ?: return@LaunchedEffect
            mealEntryRoute = MealEntryRoute(
                token = "${draft.meal.id}-${UUID.randomUUID()}",
                profileId = content.profile.id,
                localDate = content.localDate,
                draft = draft,
            )
            featureViewModel.consumeCreatedDraft()
            destination = MainDestination.TODAY
        }

        Scaffold(bottomBar = { MainNavigationBar(destination, openMealEntry) { destination = it } }) { navigationPadding ->
            FeatureHubScreen(
                modifier = Modifier.padding(navigationPadding),
                section = if (destination == MainDestination.HISTORY) FeatureHubSection.HISTORY else FeatureHubSection.MORE,
                featureState = featureState,
                photoState = photoState,
                todayMeals = content.meals,
                onAnalyzeFoodPhoto = photoViewModel::analyzeFood,
                onAnalyzeMealPhoto = photoViewModel::analyzeMeal,
                onClearPhoto = photoViewModel::clear,
                onCreateFromPhoto = featureViewModel::createFromPhoto,
                onSaveRecipe = featureViewModel::saveRecipe,
                onCreateFromRecipe = featureViewModel::createFromRecipe,
                onLogout = appViewModel::logout,
                onBack = {
                    destination = MainDestination.TODAY
                    photoViewModel.clear()
                    todayViewModel.retry()
                },
            )
        }
    } else if (route == null) {
        Scaffold(
            bottomBar = { MainNavigationBar(destination, openMealEntry) { destination = it } },
        ) { navigationPadding ->
            TodayScreen(
                modifier = Modifier.padding(navigationPadding),
                state = todayState,
                onRetry = todayViewModel::retry,
                onAddMeal = openMealEntry,
                onContinueDraft = { draft ->
                    val current = todayState.content ?: return@TodayScreen
                    mealEntryRoute = MealEntryRoute(
                        token = "${draft.meal.id}-${UUID.randomUUID()}",
                        profileId = current.profile.id,
                        localDate = current.localDate,
                        draft = draft,
                    )
                },
                onDuplicateMeal = todayViewModel::duplicateMeal,
                onCorrectMeal = todayViewModel::correctMeal,
                onDeleteMeal = todayViewModel::deleteMeal,
                onPreviousDay = todayViewModel::previousDay,
                onNextDay = todayViewModel::nextDay,
                onToday = todayViewModel::today,
            )
        }
    } else {
        val mealEntryViewModel: MealEntryViewModel = viewModel(
            key = "meal-entry-${route.token}",
            factory = MealEntryViewModel.Factory(
                repository = container.repository,
                profileId = route.profileId,
                localDate = route.localDate,
                initialDraft = route.draft,
                existingMeals = route.existingMeals,
            ),
        )
        val mealEntryState by mealEntryViewModel.state.collectAsStateWithLifecycle()

        if (route.draft == null && mealEntryState.mealType == null) {
            MealTypeSelectionScreen(
                onSelect = mealEntryViewModel::selectMealType,
                onBack = {
                    mealEntryRoute = null
                    todayViewModel.retry()
                },
            )
            return
        }

        val context = LocalContext.current
        val barcodeScanner = remember(context) {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                )
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(context, options)
        }
        MealEntryScreen(
            state = mealEntryState,
            onSelectMealType = mealEntryViewModel::selectMealType,
            onQueryChange = mealEntryViewModel::updateQuery,
            onSearch = mealEntryViewModel::search,
            onSelectFood = mealEntryViewModel::selectFood,
            onSelectPersonalFood = mealEntryViewModel::selectPersonalFood,
            onBarcodeChange = mealEntryViewModel::updateBarcode,
            onScanBarcode = {
                barcodeScanner.startScan()
                    .addOnSuccessListener { barcode ->
                        barcode.rawValue?.let(mealEntryViewModel::barcodeScanned)
                    }
                    .addOnFailureListener { error ->
                        mealEntryViewModel.barcodeScanFailed(error.localizedMessage)
                    }
            },
            onLookupBarcode = mealEntryViewModel::lookupBarcode,
            onSelectOffProduct = mealEntryViewModel::selectOffProduct,
            onSelectQuickFood = mealEntryViewModel::selectQuickFood,
            onToggleFavorite = mealEntryViewModel::toggleFavorite,
            onSelectPortion = mealEntryViewModel::selectPortion,
            onDismissFood = mealEntryViewModel::dismissFood,
            onQuantityChange = mealEntryViewModel::updateQuantity,
            onAddFood = mealEntryViewModel::addSelectedFood,
            onComplementExistingMeal = mealEntryViewModel::complementExistingMeal,
            onCreateSeparateMeal = mealEntryViewModel::createSeparateMeal,
            onCancelExistingMealChoice = mealEntryViewModel::cancelExistingMealChoice,
            onEditItem = mealEntryViewModel::editItem,
            onEditQuantityChange = mealEntryViewModel::updateEditQuantity,
            onConfirmItemEdit = mealEntryViewModel::confirmItemEdit,
            onDismissItemEdit = mealEntryViewModel::dismissItemEdit,
            onRemoveItem = mealEntryViewModel::removeItem,
            onValidate = mealEntryViewModel::validateMeal,
            onBack = {
                mealEntryRoute = null
                todayViewModel.retry()
            },
            onValidated = {
                mealEntryRoute = null
                todayViewModel.retry()
            },
        )
    }
}

@Composable
private fun MainNavigationBar(
    current: MainDestination,
    onAdd: () -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    NavigationBar {
        MainNavigationItem(MainDestination.TODAY, current == MainDestination.TODAY, Modifier.weight(1f)) { onNavigate(MainDestination.TODAY) }
        MainNavigationItem(MainDestination.ADD, false, Modifier.weight(1f), onAdd)
        MainNavigationItem(MainDestination.HISTORY, current == MainDestination.HISTORY, Modifier.weight(1f)) { onNavigate(MainDestination.HISTORY) }
        MainNavigationItem(MainDestination.MORE, current == MainDestination.MORE, Modifier.weight(1f)) { onNavigate(MainDestination.MORE) }
    }
}

@Composable
private fun MainNavigationItem(
    destination: MainDestination,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val icon = when (destination) {
        MainDestination.TODAY -> Icons.Filled.Home
        MainDestination.ADD -> Icons.Filled.AddCircle
        MainDestination.HISTORY -> Icons.Filled.CalendarMonth
        MainDestination.MORE -> Icons.Filled.MoreHoriz
    }
    val contentColor = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.primary
    else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = destination.label, tint = contentColor)
        Text(destination.label, color = contentColor, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FullScreenLoading(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}
