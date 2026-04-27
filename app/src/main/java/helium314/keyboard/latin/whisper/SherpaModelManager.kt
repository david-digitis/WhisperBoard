package helium314.keyboard.latin.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SherpaModelManager"
private const val HF_BASE =
    "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
private const val MAX_REDIRECTS = 5

private data class ParakeetFile(val name: String, val expectedSize: Long) {
    val url: String get() = "$HF_BASE/$name"
}

private val PARAKEET_FILES = listOf(
    ParakeetFile("encoder.int8.onnx", 652_000_000L),
    ParakeetFile("decoder.int8.onnx", 12_000_000L),
    ParakeetFile("joiner.int8.onnx", 7_000_000L),
    ParakeetFile("tokens.txt", 100_000L),
)

private val TOTAL_BYTES = PARAKEET_FILES.sumOf { it.expectedSize }

/**
 * Telecharge et gere le modele Parakeet TDT v3 multilingue depuis HuggingFace
 * vers externalFilesDir/sherpa-parakeet-v3/.
 *
 * Multi-file atomique : chaque fichier est ecrit en .tmp puis renomme. Un fichier
 * present dans le dossier final est considere complet (rename apres succes uniquement).
 */
class SherpaModelManager(private val context: Context) {

    fun getModelDir(): File {
        val dir = File(context.getExternalFilesDir(null), SherpaClient.MODEL_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isInstalled(): Boolean = SherpaClient.isModelInstalled(context.getExternalFilesDir(null)!!)

    fun totalDownloadSizeBytes(): Long = TOTAL_BYTES

    fun deleteModel(): Boolean = getModelDir().deleteRecursively()

    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val dir = getModelDir()
            var cumulative = 0L
            onProgress(0f)

            for (file in PARAKEET_FILES) {
                val target = File(dir, file.name)
                if (target.exists() && target.length() > 0) {
                    cumulative += target.length()
                    onProgress((cumulative.toFloat() / TOTAL_BYTES).coerceIn(0f, 0.99f))
                    Log.d(TAG, "${file.name} already present (${target.length()} bytes)")
                    continue
                }

                val temp = File(dir, "${file.name}.tmp")
                Log.d(TAG, "Downloading ${file.name} (~${file.expectedSize / 1_000_000} MB)")
                val base = cumulative
                val ok = downloadFile(file.url, temp) { fileBytes ->
                    onProgress(((base + fileBytes).toFloat() / TOTAL_BYTES).coerceIn(0f, 0.99f))
                }
                if (!ok) {
                    temp.delete()
                    return@withContext false
                }

                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    Log.e(TAG, "Rename failed: ${temp.name} -> ${target.name}")
                    temp.delete()
                    return@withContext false
                }
                cumulative += target.length()
            }

            onProgress(1f)
            Log.d(TAG, "Parakeet installed in ${dir.absolutePath}")
            true
        }

    private fun downloadFile(url: String, target: File, onBytes: (Long) -> Unit): Boolean = try {
        val connection = openConnectionFollowRedirects(url)
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "HTTP ${connection.responseCode} for $url")
            connection.disconnect()
            false
        } else {
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var fileBytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        fileBytes += n
                        onBytes(fileBytes)
                    }
                }
            }
            connection.disconnect()
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "downloadFile($url) failed", e)
        false
    }

    private fun openConnectionFollowRedirects(urlString: String): HttpURLConnection {
        var currentUrl = urlString
        repeat(MAX_REDIRECTS) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "WhisperBoard/1.0")
            connection.connect()
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
                ?: throw Exception("Redirect without Location header")
            connection.disconnect()
            currentUrl = if (location.startsWith("http")) location
            else URL(URL(currentUrl), location).toString()
        }
        throw Exception("Too many redirects ($MAX_REDIRECTS)")
    }
}
