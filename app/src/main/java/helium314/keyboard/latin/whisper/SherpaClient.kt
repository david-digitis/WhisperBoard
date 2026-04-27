package helium314.keyboard.latin.whisper

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

private const val TAG = "SherpaClient"
private const val SAMPLE_RATE = 16000
private const val NUM_THREADS = 2

/**
 * Wrapper sherpa-onnx pour le moteur Parakeet TDT v3 multilingue.
 *
 * Le modele Parakeet est compose de 4 fichiers (encoder/decoder/joiner/tokens)
 * places dans [modelDir]. Format audio attendu : FloatArray PCM mono [-1.0, 1.0]
 * a 16 kHz, ce que produit deja AudioRecorder.stop().
 */
class SherpaClient(modelDir: File) {

    private val recognizer: OfflineRecognizer

    init {
        val files = ModelFiles(modelDir)
        files.requireAll()

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = files.encoder.absolutePath,
                    decoder = files.decoder.absolutePath,
                    joiner = files.joiner.absolutePath,
                ),
                tokens = files.tokens.absolutePath,
                modelType = "nemo_transducer",
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
        Log.d(TAG, "Parakeet loaded from ${modelDir.absolutePath}")
    }

    @Synchronized
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer.release()
    }

    private class ModelFiles(dir: File) {
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        val joiner = File(dir, "joiner.int8.onnx")
        val tokens = File(dir, "tokens.txt")

        fun requireAll() {
            val missing = listOf(encoder, decoder, joiner, tokens).filterNot { it.exists() }
            require(missing.isEmpty()) {
                "Missing Parakeet model files: ${missing.joinToString { it.name }}"
            }
        }
    }

    companion object {
        const val MODEL_DIR_NAME = "sherpa-parakeet-v3"

        fun isModelInstalled(parentDir: File): Boolean {
            val dir = File(parentDir, MODEL_DIR_NAME)
            return dir.isDirectory &&
                File(dir, "encoder.int8.onnx").exists() &&
                File(dir, "decoder.int8.onnx").exists() &&
                File(dir, "joiner.int8.onnx").exists() &&
                File(dir, "tokens.txt").exists()
        }
    }
}
