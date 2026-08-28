package com.suivialimentation.android.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suivialimentation.android.data.model.GoalVersion
import com.suivialimentation.android.data.model.Profile
import com.suivialimentation.android.data.repository.NutritionRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
data class ProfileSettings(val sex: String? = null, val birthDate: String? = null, val heightCm: Double? = null, val activityLevel: String? = null, val objective: String? = null, val targetWeightKg: Double? = null, val goalCalculationMode: String? = null, val weightSource: JsonObject? = null, val manualEnergyKcal: Double? = null, val manualProteinG: Double? = null)
@Serializable
data class ProfileContext(val profile: Profile, val settings: ProfileSettings, val resolvedWeight: ResolvedWeight, val automaticRecommendation: AutomaticRecommendation? = null, val currentGoal: GoalVersion? = null, val storeRevision: Long)

data class ProfileForm(
    val sex: String = "", val birthDate: String = "", val heightCm: String = "", val activityLevel: String = "moderate",
    val objective: String = "maintain", val targetWeightKg: String = "", val useHaWeight: Boolean = false,
    val weightEntityId: String = "sensor.withings_poids", val manualWeightKg: String = "", val automaticGoals: Boolean = false,
    val manualEnergyKcal: String = "", val manualProteinG: String = "",
) {
    companion object {
        private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
        private val frenchDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        fun from(context: ProfileContext): ProfileForm {
            val settings = context.settings
            val weightSource = settings.weightSource
            fun primitive(key: String): String? = weightSource?.get(key)?.toString()?.trim('"')?.takeIf { it != "null" }
            val displayedBirthDate = settings.birthDate.orEmpty().let { value ->
                runCatching { LocalDate.parse(value, isoDate).format(frenchDate) }.getOrDefault(value)
            }
            return ProfileForm(
                sex = settings.sex.orEmpty(), birthDate = displayedBirthDate, heightCm = settings.heightCm?.toString().orEmpty(),
                activityLevel = settings.activityLevel ?: "moderate", objective = settings.objective ?: "maintain",
                targetWeightKg = settings.targetWeightKg?.toString().orEmpty(), useHaWeight = primitive("type") == "home_assistant",
                weightEntityId = primitive("entityId") ?: "sensor.withings_poids", manualWeightKg = primitive("manualWeightKg").orEmpty(),
                automaticGoals = settings.goalCalculationMode == "automatic",
                manualEnergyKcal = (settings.manualEnergyKcal ?: context.currentGoal?.targets?.energyKcal)?.toString().orEmpty(),
                manualProteinG = (settings.manualProteinG ?: context.currentGoal?.targets?.proteinG)?.toString().orEmpty(),
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
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.message ?: "Impossible de charger le profil.") } }
    }
    fun save(form: ProfileForm) = viewModelScope.launch {
        val context = _state.value.context ?: return@launch
        val birthDateIso = form.birthDate.toIsoDateOrNull()
        if (form.birthDate.isNotBlank() && birthDateIso == null) {
            _state.update { it.copy(error = "Saisissez la date de naissance au format JJ/MM/AAAA.") }
            return@launch
        }
        _state.update { it.copy(saving = true, error = null, message = null) }
        val settings = buildJsonObject {
            put("sex", form.sex)
            put("birthDate", birthDateIso.orEmpty())
            form.heightCm.number()?.let { put("heightCm", it) }
            put("activityLevel", form.activityLevel); put("objective", form.objective)
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
            .onFailure { error -> _state.update { it.copy(saving = false, error = error.message ?: "Impossible d’enregistrer le profil.") } }
    }
    private fun String.number(): Double? = replace(',', '.').toDoubleOrNull()
    private fun String.toIsoDateOrNull(): String? = runCatching {
        LocalDate.parse(trim(), DateTimeFormatter.ofPattern("dd/MM/yyyy")).format(DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()
    class Factory(private val repository: NutritionRepository, private val profileId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(repository, profileId) as T
    }
}