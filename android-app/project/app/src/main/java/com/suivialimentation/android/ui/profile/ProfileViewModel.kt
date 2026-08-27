package com.suivialimentation.android.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.model.NutrientSnapshot
import com.suivialimentation.android.data.model.Profile
import com.suivialimentation.android.data.repository.NutritionRepository
import com.suivialimentation.android.util.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ResolvedWeight(val sourceType: String, val entityId: String? = null, val valueKg: Double? = null, val lastUpdated: String? = null, val available: Boolean = false)
@Serializable
data class AutomaticRecommendation(val energyKcal: Double? = null, val proteinG: Double? = null)
@Serializable
data class ProfileSettings(
    val sex: String? = null,
    val birthDate: String? = null,
    val heightCm: Double? = null,
    val activityLevel: String? = null,
    val objective: String? = null,
    val targetWeightKg: Double? = null,
    val goalCalculationMode: String? = null,
    val weightSource: JsonObject? = null,
    val manualEnergyKcal: Double? = null,
    val manualProteinG: Double? = null,
)
@Serializable
data class ProfileContext(
    val profile: Profile,
    val settings: ProfileSettings,
    val resolvedWeight: ResolvedWeight,
    val automaticRecommendation: AutomaticRecommendation? = null,
    val storeRevision: Long,
)

data class ProfileForm(
    val sex: String = "",
    val birthDate: String = "",
    val heightCm: String = "",
    val activityLevel: String = "moderate",
    val objective: String = "maintain",
    val targetWeightKg: String = "",
    val useHaWeight: Boolean = false,
    val weightEntityId: String = "sensor.withings_poids",
    val manualWeightKg: String = "",
    val automaticGoals: Boolean = false,
    val manualEnergyKcal: String = "",
    val manualProteinG: String = "",
) {
    companion object {
        fun from(context: ProfileContext): ProfileForm {
            val s = context.settings
            val ws = s.weightSource
            fun primitive(key: String): String? = ws?.get(key)?.toString()?.trim('"')?.takeIf { it != "null" }
            return ProfileForm(
                sex = s.sex.orEmpty(),
                birthDate = s.birthDate.orEmpty(),
                heightCm = s.heightCm?.toString().orEmpty(),
                activityLevel = s.activityLevel ?: "moderate",
                objective = s.objective ?: "maintain",
                targetWeightKg = s.targetWeightKg?.toString().orEmpty(),
                useHaWeight = primitive("type") == "home_assistant",
                weightEntityId = primitive("entityId") ?: "sensor.withings_poids",
                manualWeightKg = primitive("manualWeightKg").orEmpty(),
                automaticGoals = s.goalCalculationMode == "automatic",
                manualEnergyKcal = s.manualEnergyKcal?.toString().orEmpty(),
                manualProteinG = s.manualProteinG?.toString().orEmpty(),
            )
        }
    }
}

data class ProfileUiState(val loading: Boolean = true, val saving: Boolean = false, val context: ProfileContext? = null, val error: String? = null, val message: String? = null)

class ProfileViewModel(private val repository: NutritionRepository, private val profileId: String) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    init { reload() }

    fun reload() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.loadProfileContext(profileId) }
            .onSuccess { _state.value = ProfileUiState(loading = false, context = it) }
            .onFailure { _state.update { s -> s.copy(loading = false, error = it.message ?: "Impossible de charger le profil.") } }
    }

    fun save(form: ProfileForm) = viewModelScope.launch {
        val context = _state.value.context ?: return@launch
        _state.update { it.copy(saving = true, error = null, message = null) }
        val settings = buildJsonObject {
            put("sex", form.sex)
            put("birthDate", form.birthDate)
            form.heightCm.number()?.let { put("heightCm", it) }
            put("activityLevel", form.activityLevel)
            put("objective", form.objective)
            form.targetWeightKg.number()?.let { put("targetWeightKg", it) }
            put("goalCalculationMode", if (form.automaticGoals) "automatic" else "manual")
            put("weightSource", buildJsonObject {
                if (form.useHaWeight) { put("type", "home_assistant"); put("entityId", form.weightEntityId.trim()) }
                else { put("type", "manual"); form.manualWeightKg.number()?.let { put("manualWeightKg", it) } }
            })
            if (!form.automaticGoals) {
                form.manualEnergyKcal.number()?.let { put("manualEnergyKcal", it) }
                form.manualProteinG.number()?.let { put("manualProteinG", it) }
            }
        }
        runCatching { repository.updateProfileContext(profileId, settings, context.profile.revision) }
            .onSuccess { _state.value = ProfileUiState(loading = false, context = it, message = "Profil enregistré.") }
            .onFailure { _state.update { s -> s.copy(saving = false, error = it.message ?: "Impossible d’enregistrer le profil.") } }
    }

    private fun String.number(): Double? = replace(',', '.').toDoubleOrNull()

    class Factory(private val repository: NutritionRepository, private val profileId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(repository, profileId) as T
    }
}
