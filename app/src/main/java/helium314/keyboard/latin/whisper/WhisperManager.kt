package helium314.keyboard.latin.whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

class WhisperManager(private val context: Context) {
    private var whisperContext: WhisperContext? = null
    private var loadedModelName: String? = null
    private val recorder = AudioRecorder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var transcriptionJob: Job? = null
    private var isTranscribing = false
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val isRecording: Boolean get() = recorder.isActive

    var onTranscriptionResult: ((String) -> Unit)? = null
    var onStateChanged: ((RecordingState) -> Unit)? = null

    enum class RecordingState { IDLE, RECORDING, TRANSCRIBING }

    private val prefs get() = context.prefs()

    private val activeModel: WhisperModel
        get() = WhisperModel.fromPref(
            prefs.getString(Settings.PREF_WHISPER_MODEL, Defaults.PREF_WHISPER_MODEL)!!
        )

    private val language: String
        get() = prefs.getString(Settings.PREF_WHISPER_LANGUAGE, Defaults.PREF_WHISPER_LANGUAGE)!!

    fun toggleRecording() {
        if (recorder.isActive) {
            stopAndTranscribe()
        } else {
            if (isTranscribing) {
                Log.d(TAG, "Cancelling previous transcription")
                transcriptionJob?.cancel()
                isTranscribing = false
            }
            startRecording()
        }
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

        recorder.start()
        vibrate(50) // short buzz on start
        onStateChanged?.invoke(RecordingState.RECORDING)
        Log.d(TAG, "Recording started")
    }

    private fun stopAndTranscribe() {
        val audioData = recorder.stop()
        vibrate(100) // longer buzz on stop
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
        scope.cancel()
        runBlocking(Dispatchers.IO) {
            whisperContext?.release()
            whisperContext = null
        }
    }
}
