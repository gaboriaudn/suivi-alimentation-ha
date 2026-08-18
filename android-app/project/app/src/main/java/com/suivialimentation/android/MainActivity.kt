package com.suivialimentation.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suivialimentation.android.data.repository.MealWithItems
import com.suivialimentation.android.di.AppContainer
import com.suivialimentation.android.ui.AppEvent
import com.suivialimentation.android.ui.AppUiState
import com.suivialimentation.android.ui.AppViewModel
import com.suivialimentation.android.ui.LoginScreen
import com.suivialimentation.android.ui.mealentry.MealEntryScreen
import com.suivialimentation.android.ui.mealentry.MealEntryViewModel
import com.suivialimentation.android.ui.theme.SuiviAlimentationTheme
import com.suivialimentation.android.ui.today.TodayScreen
import com.suivialimentation.android.ui.today.TodayViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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

    if (route == null) {
        TodayScreen(
            state = todayState,
            onRetry = todayViewModel::retry,
            onLogout = appViewModel::logout,
            onAddMeal = {
                val content = todayState.content ?: return@TodayScreen
                mealEntryRoute = MealEntryRoute(
                    token = UUID.randomUUID().toString(),
                    profileId = content.profile.id,
                    localDate = content.localDate,
                    existingMeals = content.meals,
                )
            },
            onContinueDraft = { draft ->
                val content = todayState.content ?: return@TodayScreen
                mealEntryRoute = MealEntryRoute(
                    token = "${draft.meal.id}-${UUID.randomUUID()}",
                    profileId = content.profile.id,
                    localDate = content.localDate,
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
