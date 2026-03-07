package helium314.keyboard.latin.whisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

private const val TAG = "AudioRecorder"
private const val SAMPLE_RATE = 16000

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false
    private val allSamples = mutableListOf<Short>()

    /** Optional callback for streaming mode — receives raw PCM 16-bit chunks as ByteArray */
    var onAudioChunk: ((ByteArray) -> Unit)? = null

    val isActive: Boolean get() = isRecording

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return
        allSamples.clear()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            return
        }

        audioRecord = recorder
        isRecording = true

        recordingThread = Thread({
            val buffer = ShortArray(bufferSize / 2)
            recorder.startRecording()
            Log.d(TAG, "Recording started")

            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunkCallback = onAudioChunk
                    if (chunkCallback != null) {
                        // Streaming mode: convert shorts to little-endian bytes and send
                        val byteBuffer = java.nio.ByteBuffer.allocate(read * 2)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) {
                            byteBuffer.putShort(buffer[i])
                        }
                        chunkCallback(byteBuffer.array())
                    } else {
                        // Local mode: accumulate samples
                        synchronized(allSamples) {
                            for (i in 0 until read) {
                                allSamples.add(buffer[i])
                            }
                        }
                    }
                }
            }

            recorder.stop()
            recorder.release()
            Log.d(TAG, "Recording stopped, ${allSamples.size} samples collected")
        }, "WhisperAudioRecorder")

        recordingThread?.start()
    }

    fun stop(): FloatArray {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null
        audioRecord = null

        val samples: ShortArray
        synchronized(allSamples) {
            samples = allSamples.toShortArray()
            allSamples.clear()
        }

        return shortToFloat(samples)
    }

    private fun shortToFloat(data: ShortArray): FloatArray {
        return FloatArray(data.size) { i ->
            (data[i] / 32767.0f).coerceIn(-1f, 1f)
        }
    }
}
