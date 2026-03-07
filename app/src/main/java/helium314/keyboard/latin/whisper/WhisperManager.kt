package helium314.keyboard.latin.whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperContext
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.*
import java.io.File

private const val TAG = "WhisperManager"

enum class TranscriptionMode {
    LOCAL,  // Whisper on-device (slow but private)
    CLOUD,  // Deepgram streaming (fast, requires internet)
    AUTO;   // Cloud if available, fallback to local

    companion object {
        fun fromPref(value: String): TranscriptionMode =
            entries.find { it.name.lowercase() == value.lowercase() } ?: AUTO
    }
}

class WhisperManager(private val context: Context) {
    private var whisperContext: WhisperContext? = null
    private var loadedModelName: String? = null
    private val recorder = AudioRecorder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var transcriptionJob: Job? = null
    private var isTranscribing = false
    private var deepgramClient: DeepgramClient? = null
    private val finalSegments = mutableListOf<String>()
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val isRecording: Boolean get() = recorder.isActive

    var onTranscriptionResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onStateChanged: ((RecordingState) -> Unit)? = null

    enum class RecordingState { IDLE, RECORDING, TRANSCRIBING }

    private val prefs get() = context.prefs()

    private val activeModel: WhisperModel
        get() = WhisperModel.fromPref(
            prefs.getString(Settings.PREF_WHISPER_MODEL, Defaults.PREF_WHISPER_MODEL)!!
        )

    private val language: String
        get() = prefs.getString(Settings.PREF_WHISPER_LANGUAGE, Defaults.PREF_WHISPER_LANGUAGE)!!

    private val transcriptionMode: TranscriptionMode
        get() = TranscriptionMode.fromPref(
            prefs.getString(Settings.PREF_TRANSCRIPTION_MODE, Defaults.PREF_TRANSCRIPTION_MODE)!!
        )

    private val deepgramApiKey: String
        get() = prefs.getString(Settings.PREF_DEEPGRAM_API_KEY, "")!!

    fun toggleRecording() {
        if (recorder.isActive) {
            stopRecording()
        } else {
            if (isTranscribing) {
                Log.d(TAG, "Cancelling previous transcription")
                transcriptionJob?.cancel()
                isTranscribing = false
            }
            startRecording()
        }
    }

    private fun shouldUseCloud(): Boolean {
        val mode = transcriptionMode
        if (mode == TranscriptionMode.LOCAL) return false
        if (deepgramApiKey.isBlank()) return false
        if (mode == TranscriptionMode.CLOUD) return true
        // AUTO: check network
        return isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun preloadModel() {
        if (whisperContext != null && loadedModelName == activeModel.fileName) return
        scope.launch(Dispatchers.IO) {
            try {
                val loaded = loadModelInternal()
                if (loaded) {
                    Log.d(TAG, "Model preloaded successfully")
                } else {
                    Log.w(TAG, "No model found to preload")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model preload failed", e)
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }

        if (shouldUseCloud()) {
            startCloudRecording()
        } else {
            startLocalRecording()
        }
    }

    private fun startCloudRecording() {
        Log.d(TAG, "Starting cloud recording (Deepgram)")
        finalSegments.clear()

        val client = DeepgramClient(
            apiKey = deepgramApiKey,
            language = language,
            onPartialResult = { text ->
                scope.launch { onPartialResult?.invoke(text) }
            },
            onFinalResult = { text ->
                finalSegments.add(text)
                scope.launch { onPartialResult?.invoke(finalSegments.joinToString(" ") + " ...") }
            },
            onError = { error ->
                Log.e(TAG, "Deepgram error: $error")
                scope.launch {
                    Toast.makeText(context, "Cloud STT error: $error", Toast.LENGTH_SHORT).show()
                    onStateChanged?.invoke(RecordingState.IDLE)
                }
            },
            onStreamClosed = {
                // Called when Deepgram has flushed all final results and closed
                scope.launch { commitCloudResults() }
            }
        )
        client.connect()
        deepgramClient = client

        // Stream audio chunks to Deepgram
        recorder.onAudioChunk = { bytes -> client.sendAudio(bytes) }
        recorder.start()
        vibrate(50)
        onStateChanged?.invoke(RecordingState.RECORDING)
    }

    private fun startLocalRecording() {
        Log.d(TAG, "Starting local recording (Whisper)")
        val model = activeModel
        if (whisperContext == null || loadedModelName != model.fileName) {
            onStateChanged?.invoke(RecordingState.TRANSCRIBING)
            Log.d(TAG, "Loading model ${model.displayName} on first use...")
            if (!loadModelSync()) {
                onStateChanged?.invoke(RecordingState.IDLE)
                Toast.makeText(context, "Model ${model.displayName} not found. Download it in Whisper settings.", Toast.LENGTH_LONG).show()
                return
            }
        }

        recorder.onAudioChunk = null // local mode: accumulate
        recorder.start()
        vibrate(50)
        onStateChanged?.invoke(RecordingState.RECORDING)
    }

    private fun stopRecording() {
        vibrate(100)
        if (deepgramClient != null) {
            stopCloudRecording()
        } else {
            stopLocalRecording()
        }
    }

    private fun stopCloudRecording() {
        recorder.onAudioChunk = null
        recorder.stop() // discard the FloatArray, we already streamed
        onStateChanged?.invoke(RecordingState.TRANSCRIBING)
        // Send CloseStream — Deepgram will flush remaining audio and send final results.
        // commitCloudResults() is called via onStreamClosed callback when done.
        deepgramClient?.closeGracefully()
    }

    private fun commitCloudResults() {
        val fullText = finalSegments.joinToString(" ").trim()
        Log.d(TAG, "Cloud transcription: $fullText")
        if (fullText.isNotBlank()) {
            onTranscriptionResult?.invoke(fullText)
        }
        deepgramClient?.forceClose()
        deepgramClient = null
        onStateChanged?.invoke(RecordingState.IDLE)
    }

    private fun stopLocalRecording() {
        val audioData = recorder.stop()
        Log.d(TAG, "Recording stopped, ${audioData.size} samples")

        if (audioData.isEmpty()) {
            Log.w(TAG, "No audio data recorded")
            onStateChanged?.invoke(RecordingState.IDLE)
            return
        }

        onStateChanged?.invoke(RecordingState.TRANSCRIBING)
        isTranscribing = true

        val lang = language
        transcriptionJob = scope.launch {
            try {
                val ctx = whisperContext ?: return@launch
                val text = withContext(Dispatchers.IO) {
                    ctx.transcribeData(audioData, language = lang)
                }
                Log.d(TAG, "Transcription: $text")
                if (text.isNotBlank()) {
                    onTranscriptionResult?.invoke(text)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Transcription cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
            } finally {
                isTranscribing = false
                onStateChanged?.invoke(RecordingState.IDLE)
            }
        }
    }

    @Synchronized
    private fun loadModelSync(): Boolean = loadModelInternal()

    private fun loadModelInternal(): Boolean {
        val model = activeModel
        val modelFileName = model.fileName

        // Release previous context if switching models
        if (whisperContext != null && loadedModelName != modelFileName) {
            Log.d(TAG, "Releasing previous model $loadedModelName")
            runBlocking { whisperContext?.release() }
            whisperContext = null
            loadedModelName = null
        }

        val externalModel = File(context.getExternalFilesDir(null), "models/$modelFileName")
        if (externalModel.exists()) {
            Log.d(TAG, "Loading model from ${externalModel.absolutePath}")
            return try {
                whisperContext = WhisperContext.createContextFromFile(externalModel.absolutePath)
                loadedModelName = modelFileName
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model from file", e)
                false
            }
        }

        val internalModel = File(context.filesDir, "models/$modelFileName")
        if (internalModel.exists()) {
            Log.d(TAG, "Loading model from ${internalModel.absolutePath}")
            return try {
                whisperContext = WhisperContext.createContextFromFile(internalModel.absolutePath)
                loadedModelName = modelFileName
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model from internal file", e)
                false
            }
        }

        return try {
            val models = context.assets.list("models/")
            if (models != null && models.contains(modelFileName)) {
                Log.d(TAG, "Loading model from assets: models/$modelFileName")
                whisperContext = WhisperContext.createContextFromAsset(context.assets, "models/$modelFileName")
                loadedModelName = modelFileName
                true
            } else {
                Log.w(TAG, "Model $modelFileName not found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets", e)
            false
        }
    }

    private fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    fun release() {
        transcriptionJob?.cancel()
        deepgramClient?.forceClose()
        deepgramClient = null
        recorder.onAudioChunk = null
        scope.cancel()
        runBlocking(Dispatchers.IO) {
            whisperContext?.release()
            whisperContext = null
        }
    }
}
