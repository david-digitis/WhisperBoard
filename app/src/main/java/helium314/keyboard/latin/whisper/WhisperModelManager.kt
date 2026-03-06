package helium314.keyboard.latin.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "WhisperModelManager"
private const val HF_GENERIC = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
private const val GH_RELEASE = "https://github.com/david-digitis/WhisperBoard/releases/download/v1.0.0"
private const val MAX_REDIRECTS = 5

enum class WhisperModel(
    val fileName: String,
    val displayName: String,
    val sizeMB: Int,
    val url: String,
    val category: ModelCategory = ModelCategory.GENERIC
) {
    BASE("ggml-base.bin", "Base", 142,
        "$HF_GENERIC/ggml-base.bin"),
    SMALL("ggml-small.bin", "Small", 488,
        "$HF_GENERIC/ggml-small.bin"),
    SMALL_FR("ggml-small-fr.bin", "Small FR", 488,
        "$GH_RELEASE/ggml-small-fr.bin",
        ModelCategory.FRENCH);

    companion object {
        fun fromPref(value: String): WhisperModel =
            entries.find { it.name.lowercase() == value.lowercase() } ?: SMALL_FR
    }
}

enum class ModelCategory { GENERIC, FRENCH }

class WhisperModelManager(private val context: Context) {

    fun getModelsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isDownloaded(model: WhisperModel): Boolean =
        File(getModelsDir(), model.fileName).exists()

    fun getModelFile(model: WhisperModel): File? {
        val file = File(getModelsDir(), model.fileName)
        return if (file.exists()) file else null
    }

    fun deleteModel(model: WhisperModel): Boolean {
        val file = File(getModelsDir(), model.fileName)
        return if (file.exists()) file.delete() else false
    }

    fun getDownloadedModels(): List<WhisperModel> =
        WhisperModel.entries.filter { isDownloaded(it) }

    private fun openConnectionFollowRedirects(urlString: String): HttpURLConnection {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < MAX_REDIRECTS) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "WhisperBoard/1.0")
            connection.connect()

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw Exception("Redirect without Location header")
                currentUrl = if (location.startsWith("http")) location
                             else URL(URL(currentUrl), location).toString()
                Log.d(TAG, "Redirect $code -> $currentUrl")
                redirects++
            } else {
                return connection
            }
        }
        throw Exception("Too many redirects ($MAX_REDIRECTS)")
    }

    suspend fun downloadModel(
        model: WhisperModel,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(getModelsDir(), model.fileName)
        val tempFile = File(getModelsDir(), "${model.fileName}.tmp")

        try {
            Log.d(TAG, "Downloading ${model.displayName} from ${model.url}")
            onProgress(0f)

            val connection = openConnectionFollowRedirects(model.url)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ${connection.responseCode}: ${connection.responseMessage}")
                connection.disconnect()
                return@withContext false
            }

            val totalBytes = connection.contentLengthLong
            val expectedBytes = model.sizeMB * 1_000_000L
            Log.d(TAG, "Content-Length: $totalBytes, expected ~$expectedBytes")
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val total = if (totalBytes > 0) totalBytes else expectedBytes
                        onProgress((downloadedBytes.toFloat() / total).coerceAtMost(0.99f))
                    }
                }
            }
            connection.disconnect()

            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

            Log.d(TAG, "${model.displayName} downloaded: ${targetFile.length()} bytes")
            onProgress(1f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.displayName}", e)
            tempFile.delete()
            false
        }
    }
}
