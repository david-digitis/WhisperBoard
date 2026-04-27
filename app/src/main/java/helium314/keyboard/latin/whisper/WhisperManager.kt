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
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.*

private const val TAG = "WhisperManager"

enum class TranscriptionMode {
    LOCAL,  // Parakeet on-device via sherpa-onnx (private, no internet)
    CLOUD,  // Deepgram streaming (fast, requires internet)
    AUTO;   // Cloud if available, fallback to local

    companion object {
        fun fromPref(value: String): TranscriptionMode =
            entries.find { it.name.lowercase() == value.lowercase() } ?: AUTO
    }
}

class WhisperManager(private val context: Context) {
    init { FileLogger.init(context) }
    private var sessionCount = 0
    private var sherpaClient: SherpaClient? = null
    private val sherpaModelManager = SherpaModelManager(context)
    private val recorder = AudioRecorder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var transcriptionJob: Job? = null
    private var isTranscribing = false
    private var deepgramClient: DeepgramClient? = null
    private var cloudTimeoutJob: Job? = null
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

    private val language: String
        get() = prefs.getString(Settings.PREF_WHISPER_LANGUAGE, Defaults.PREF_WHISPER_LANGUAGE)!!

    private val transcriptionMode: TranscriptionMode
        get() = TranscriptionMode.fromPref(
            prefs.getString(Settings.PREF_TRANSCRIPTION_MODE, Defaults.PREF_TRANSCRIPTION_MODE)!!
        )

    private val deepgramApiKey: String
        get() = prefs.getString(Settings.PREF_DEEPGRAM_API_KEY, "")!!

    fun toggleRecording() {
        sessionCount++
        FileLogger.log(TAG, "--- toggleRecording #$sessionCount (isActive=${recorder.isActive}, isTranscribing=$isTranscribing, deepgramClient=${deepgramClient != null})")
        if (recorder.isActive) {
            stopRecording()
        } else {
            if (isTranscribing) {
                FileLogger.log(TAG, "BLOCKED: previous transcription still running")
                Toast.makeText(context, "Transcription in progress...", Toast.LENGTH_SHORT).show()
                return
            }
            // Cancel any pending timeout from previous session
            cloudTimeoutJob?.cancel()
            cloudTimeoutJob = null
            // Clean up any leftover Deepgram client from previous session
            if (deepgramClient != null) {
                FileLogger.log(TAG, "WARNING: cleaning up leftover Deepgram client — hard kill")
                deepgramClient?.hardClose()
                deepgramClient = null
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
        if (sherpaClient != null) return
        if (!sherpaModelManager.isInstalled()) {
            Log.w(TAG, "Parakeet model not installed — skipping preload")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (loadSherpaInternal()) Log.d(TAG, "Parakeet preloaded")
                else Log.w(TAG, "Parakeet preload failed")
            } catch (e: Exception) {
                Log.e(TAG, "Parakeet preload failed", e)
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
        FileLogger.log(TAG, "startCloudRecording — creating DeepgramClient")
        finalSegments.clear()

        val client = DeepgramClient(
            apiKey = deepgramApiKey,
            language = language,
            onPartialResult = { text ->
                scope.launch { onPartialResult?.invoke(text) }
            },
            onFinalResult = { text ->
                FileLogger.log(TAG, "onFinalResult: \"${text.take(60)}\"")
                finalSegments.add(text)
                scope.launch { onPartialResult?.invoke(finalSegments.joinToString(" ") + " ...") }
            },
            onError = { error ->
                FileLogger.log(TAG, "onError: $error")
                scope.launch {
                    Toast.makeText(context, "Cloud STT error: $error", Toast.LENGTH_SHORT).show()
                    onStateChanged?.invoke(RecordingState.IDLE)
                }
            },
            onStreamClosed = {
                FileLogger.log(TAG, "onStreamClosed")
                scope.launch { commitCloudResults() }
            }
        )
        client.connect()
        deepgramClient = client

        // Stream audio chunks to Deepgram
        recorder.onAudioChunk = { bytes -> client.sendAudio(bytes) }
        if (!recorder.start()) {
            Log.e(TAG, "Failed to start audio recording")
            Toast.makeText(context, "Microphone unavailable", Toast.LENGTH_SHORT).show()
            client.forceClose()
            deepgramClient = null
            onStateChanged?.invoke(RecordingState.IDLE)
            return
        }
        vibrate(50)
        onStateChanged?.invoke(RecordingState.RECORDING)
    }

    private fun startLocalRecording() {
        Log.d(TAG, "Starting local recording (Parakeet)")
        if (sherpaClient == null) {
            if (!sherpaModelManager.isInstalled()) {
                Toast.makeText(context, "Parakeet model not downloaded — open Whisper settings.", Toast.LENGTH_LONG).show()
                return
            }
            onStateChanged?.invoke(RecordingState.TRANSCRIBING)
            Log.d(TAG, "Loading Parakeet on first use...")
            if (!loadSherpaSync()) {
                onStateChanged?.invoke(RecordingState.IDLE)
                Toast.makeText(context, "Failed to load Parakeet model.", Toast.LENGTH_LONG).show()
                return
            }
        }

        recorder.onAudioChunk = null // local mode: accumulate
        if (!recorder.start()) {
            Log.e(TAG, "Failed to start audio recording")
            Toast.makeText(context, "Microphone unavailable", Toast.LENGTH_SHORT).show()
            onStateChanged?.invoke(RecordingState.IDLE)
            return
        }
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
        FileLogger.log(TAG, "stopCloudRecording — hasPendingFinal=${deepgramClient?.hasPendingFinal}")
        recorder.onAudioChunk = null
        recorder.stop()
        onStateChanged?.invoke(RecordingState.TRANSCRIBING)

        val client = deepgramClient
        if (client != null && !client.hasPendingFinal) {
            // All final results already received — commit immediately
            FileLogger.log(TAG, "No pending results — committing immediately")
            commitCloudResults()
            return
        }

        client?.closeGracefully()

        // Failsafe: reduced to 2s since we only wait when there's actually pending audio
        cloudTimeoutJob = scope.launch {
            delay(2000)
            if (deepgramClient != null) {
                FileLogger.log(TAG, "TIMEOUT 2s — forcing commit")
                commitCloudResults()
            }
        }
    }

    private fun commitCloudResults() {
        val client = deepgramClient ?: return // already committed
        cloudTimeoutJob?.cancel()
        cloudTimeoutJob = null
        val fullText = finalSegments.joinToString(" ").trim()
        FileLogger.log(TAG, "commitCloudResults: \"${fullText.take(80)}\" (${finalSegments.size} segments)")
        if (fullText.isNotBlank()) {
            onTranscriptionResult?.invoke(fullText)
        }
        client.forceClose()
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

        transcriptionJob = scope.launch {
            try {
                val client = sherpaClient ?: return@launch
                val text = withContext(Dispatchers.IO) {
                    client.transcribe(audioData)
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
    private fun loadSherpaSync(): Boolean = loadSherpaInternal()

    private fun loadSherpaInternal(): Boolean {
        if (sherpaClient != null) return true
        return try {
            val dir = sherpaModelManager.getModelDir()
            sherpaClient = SherpaClient(dir)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init SherpaClient", e)
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
            sherpaClient?.release()
            sherpaClient = null
        }
    }
}
