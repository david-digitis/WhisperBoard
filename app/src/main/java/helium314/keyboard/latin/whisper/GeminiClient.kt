package helium314.keyboard.latin.whisper

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val TAG = "GeminiClient"
private const val MODEL = "gemini-2.5-flash-lite"
private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

class GeminiClient(private val apiKey: String) {

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        val PROMPTS = mapOf(
            "translate" to """Detect the language of the following text. If it is French, translate it to English. Otherwise, translate it to French. No formatting, no introduction, return ONLY the translation.""",

            "grammar" to """Corrige les fautes d'orthographe, de grammaire et de ponctuation du texte suivant. Ne reformule pas, ne change pas le style, ne rajoute rien. Renvoie UNIQUEMENT le texte corrige.""",

            "mail_fr" to """Redige un email professionnel en francais a partir du texte suivant. Detecte le ton (tutoiement/vouvoiement) et adapte la signature :
- Si tutoiement : "Bien a toi,\nDavid"
- Si vouvoiement : "Cordialement,\nDavid"

Renvoie UNIQUEMENT l'email, rien d'autre. Pas d'objet.""",

            "mail_en" to """Redige un email professionnel en anglais a partir du texte suivant. Detecte le ton :
- Si informel : "Best,\nDavid"
- Si formel : "Best regards,\nDavid"

Renvoie UNIQUEMENT l'email en anglais, rien d'autre. Pas d'objet."""
        )
    }

    fun process(
        text: String,
        action: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val prompt = PROMPTS[action]
        if (prompt == null) {
            onError("Unknown action: $action")
            return
        }

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", "$prompt\n\nText:\n$text")
                ))
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 2048)
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/$MODEL:generateContent?key=$apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        sharedClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Gemini request failed", e)
                onError(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Gemini HTTP ${response.code}: $body")
                        onError("Gemini error ${response.code}")
                        return
                    }

                    val json = JSONObject(body ?: "")
                    val candidates = json.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        onError("No response from Gemini")
                        return
                    }

                    val content = candidates.getJSONObject(0)
                        .optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.getJSONObject(0)
                        ?.optString("text", "")
                        ?: ""

                    if (content.isNotBlank()) {
                        Log.d(TAG, "Gemini result ($action): ${content.take(100)}...")
                        onResult(content.trim())
                    } else {
                        onError("Empty response from Gemini")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Gemini response", e)
                    onError("Parse error: ${e.message}")
                }
            }
        })
    }
}
