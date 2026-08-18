package com.suivialimentation.android.data.photo

import android.content.Context
import android.net.Uri
import com.suivialimentation.android.auth.AccessTokenProvider
import com.suivialimentation.android.util.AppJson
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class PhotoAnalysisMode {
    FOOD,
    MEAL,
}

data class PhotoFoodSuggestion(
    val label: String,
    val estimatedGrams: Double,
)

data class PhotoAnalysisResult(
    val title: String,
    val suggestions: List<PhotoFoodSuggestion>,
)

/**
 * J1.8: image interpretation only. This service never returns authoritative nutrients.
 * Nutrient values are still resolved later through personal foods / CIQUAL / OFF.
 */
class PhotoAnalysisService(
    context: Context,
    private val httpClient: OkHttpClient,
    private val tokenProvider: AccessTokenProvider,
) {
    private val appContext = context.applicationContext

    suspend fun analyze(uri: Uri, mode: PhotoAnalysisMode): PhotoAnalysisResult = withContext(Dispatchers.IO) {
        val session = tokenProvider.currentSession() ?: error("Session Home Assistant absente.")
        val token = tokenProvider.validAccessToken()
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Impossible de lire la photo.")
        val mime = appContext.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"

        val mediaId = upload(session.instanceUrl, token, bytes, mime)
        try {
            val result = runAiTask(session.instanceUrl, token, mediaId, mime, mode)
            parseResult(result, mode)
        } finally {
            runCatching { removeMedia(session.instanceUrl, token, mediaId) }
        }
    }

    private fun upload(baseUrl: String, token: String, bytes: ByteArray, mime: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("media_content_id", "media-source://media_source/local/.")
            .addFormDataPart("file", "photo_alimentation.jpg", bytes.toRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/media_source/local_source/upload")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("Échec de l’envoi de la photo (${response.code}).")
            val obj = AppJson.parseToJsonElement(text).jsonObject
            return obj["media_content_id"]?.jsonPrimitive?.content
                ?: error("Identifiant média absent.")
        }
    }

    private fun runAiTask(
        baseUrl: String,
        token: String,
        mediaId: String,
        mime: String,
        mode: PhotoAnalysisMode,
    ): JsonObject {
        val instructions = when (mode) {
            PhotoAnalysisMode.FOOD ->
                "La photo représente un aliment isolé. Identifie uniquement l’aliment principal réellement visible, avec le nom générique le plus simple possible. " +
                    "N’invente jamais de recette, de préparation, d’accompagnement ou d’ingrédient non visible. " +
                    "Exemple important : une pêche seule doit être nommée Pêche, jamais Pêche Melba. " +
                    "N’invente aucune valeur nutritionnelle. Donne une seule ligne au format exact NOM | GRAMMES. " +
                    "Les grammes sont une estimation visuelle à faire confirmer par l’utilisateur."
            PhotoAnalysisMode.MEAL ->
                "La photo représente un repas ou une assiette composée. Identifie uniquement les aliments réellement visibles et décompose-les en aliments simples lorsque cela est raisonnablement possible. " +
                    "N’invente pas de sauce, recette ou ingrédient invisible. Si un plat composé ne peut pas être décomposé visuellement avec fiabilité, utilise un nom descriptif simple sans inventer sa recette. " +
                    "N’invente aucune valeur nutritionnelle. Donne une ligne par aliment au format exact NOM | GRAMMES. " +
                    "Les grammes sont des estimations visuelles à faire confirmer par l’utilisateur."
        }
        val payload = buildJsonObject {
            put("task_name", if (mode == PhotoAnalysisMode.FOOD) "Identification d’un aliment" else "Identification d’un repas")
            put("entity_id", "ai_task.openai_ai_task")
            put("instructions", instructions)
            put("structure", buildJsonObject {
                put("title", buildJsonObject {
                    put("selector", buildJsonObject { put("text", buildJsonObject {}) })
                    put("description", if (mode == PhotoAnalysisMode.FOOD) "Nom générique court de l’aliment en français" else "Description courte du repas en français")
                })
                put("items_text", buildJsonObject {
                    put("selector", buildJsonObject { put("text", buildJsonObject {}) })
                    put("description", "Une ligne par aliment : NOM | GRAMMES")
                })
            })
            put("attachments", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("media_content_id", mediaId)
                    put("media_content_type", mime)
                })
            })
        }
        val request = Request.Builder()
            .url("$baseUrl/api/services/ai_task/generate_data?return_response")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("Analyse photo indisponible (${response.code}).")
            return AppJson.parseToJsonElement(text).jsonObject
        }
    }

    private fun parseResult(root: JsonObject, mode: PhotoAnalysisMode): PhotoAnalysisResult {
        val data = root["service_response"]?.jsonObject?.get("data")?.jsonObject
            ?: root["response"]?.jsonObject?.get("data")?.jsonObject
            ?: root["data"]?.jsonObject
            ?: JsonObject(emptyMap())
        val fallbackTitle = if (mode == PhotoAnalysisMode.FOOD) "Aliment photographié" else "Repas photographié"
        val title = data["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { fallbackTitle }
        val lines = data["items_text"]?.jsonPrimitive?.content.orEmpty().lineSequence()
        val maxItems = if (mode == PhotoAnalysisMode.FOOD) 1 else 12
        val suggestions = lines.mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            val label = parts.getOrNull(0)?.trim().orEmpty()
            val grams = parts.getOrNull(1)?.trim()?.replace(',', '.')?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            if (label.isBlank() || grams == null || grams <= 0.0) null else PhotoFoodSuggestion(label, grams)
        }.take(maxItems).toList()
        if (suggestions.isEmpty()) error("Aucun aliment exploitable n’a été reconnu sur cette photo.")
        return PhotoAnalysisResult(title, suggestions)
    }

    private fun removeMedia(baseUrl: String, token: String, mediaId: String) {
        val payload = buildJsonObject { put("media_content_id", mediaId) }
        val request = Request.Builder()
            .url("$baseUrl/api/media_source/local_source/remove")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().close()
    }
}
