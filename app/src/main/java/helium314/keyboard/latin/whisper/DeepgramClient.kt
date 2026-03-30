package helium314.keyboard.latin.whisper

import android.util.Log
import okhttp3.*
import org.json.JSONObject

private const val TAG = "DeepgramClient"

class DeepgramClient(
    private val apiKey: String,
    private val language: String = "fr",
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStreamClosed: () -> Unit = {}
) {
    companion object {
        // Shared OkHttpClient across all DeepgramClient instances to avoid thread/connection leaks
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    private var webSocket: WebSocket? = null
    private val pendingAudio = mutableListOf<ByteArray>()
    @Volatile
    var isConnected = false
        private set
    @Volatile
    private var isClosing = false
    @Volatile
    private var hasNotifiedClose = false

    private var connectTime = 0L
    private var chunkCount = 0
    @Volatile
    var hasPendingFinal = false
        private set

    fun connect() {
        connectTime = System.currentTimeMillis()
        chunkCount = 0
        FileLogger.log(TAG, "connect() — opening WebSocket")
        val url = "wss://api.deepgram.com/v1/listen" +
            "?model=nova-3" +
            "&language=$language" +
            "&encoding=linear16" +
            "&sample_rate=16000" +
            "&channels=1" +
            "&interim_results=true" +
            "&smart_format=true" +
            "&punctuate=true" +
            "&endpointing=300"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val elapsed = System.currentTimeMillis() - connectTime
                isConnected = true
                // Flush any audio buffered while connecting
                synchronized(pendingAudio) {
                    FileLogger.log(TAG, "WebSocket connected in ${elapsed}ms — flushing ${pendingAudio.size} buffered chunks")
                    for (chunk in pendingAudio) {
                        webSocket.send(okio.ByteString.of(*chunk))
                    }
                    pendingAudio.clear()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val channel = json.optJSONObject("channel") ?: return
                    val alternatives = channel.optJSONArray("alternatives") ?: return
                    if (alternatives.length() == 0) return

                    val transcript = alternatives.getJSONObject(0).optString("transcript", "")
                    if (transcript.isBlank()) return

                    val isFinal = json.optBoolean("is_final", false)
                    if (isFinal) {
                        hasPendingFinal = false
                        onFinalResult(transcript)
                        // If we already sent CloseStream, this is the last result
                        if (isClosing) {
                            Log.d(TAG, "Final result received after CloseStream, finishing")
                            forceClose()
                            notifyClosedOnce()
                        }
                    } else {
                        hasPendingFinal = true
                        onPartialResult(transcript)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                if (isClosing || this@DeepgramClient.webSocket == null) {
                    // Expected failure after close/cancel — not an error
                    FileLogger.log(TAG, "WebSocket closed (expected): ${t.message}")
                    notifyClosedOnce()
                } else {
                    FileLogger.log(TAG, "WebSocket FAILURE: ${t.message}")
                    onError(t.message ?: "Connection failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                FileLogger.log(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                if (isClosing) {
                    notifyClosedOnce()
                }
            }
        })
    }

    fun sendAudio(audioBytes: ByteArray) {
        chunkCount++
        if (isConnected) {
            webSocket?.send(okio.ByteString.of(*audioBytes))
        } else {
            synchronized(pendingAudio) {
                pendingAudio.add(audioBytes.copyOf())
            }
        }
    }

    fun closeGracefully() {
        FileLogger.log(TAG, "closeGracefully — sent $chunkCount audio chunks total, hasPendingFinal=$hasPendingFinal")
        isClosing = true
        isConnected = false
        try {
            webSocket?.send("{\"type\": \"CloseStream\"}")
        } catch (e: Exception) {
            FileLogger.log(TAG, "Error sending CloseStream: ${e.message}")
            forceClose()
            notifyClosedOnce()
        }
    }

    @Synchronized
    private fun notifyClosedOnce() {
        if (hasNotifiedClose) return
        hasNotifiedClose = true
        onStreamClosed()
    }

    fun forceClose() {
        FileLogger.log(TAG, "forceClose()")
        val ws = webSocket
        webSocket = null
        isConnected = false
        isClosing = false
        synchronized(pendingAudio) { pendingAudio.clear() }
        try {
            ws?.close(1000, "Done")
        } catch (e: Exception) {
            try { ws?.cancel() } catch (_: Exception) {}
        }
    }

    /** Hard kill — for abandoned sessions that need immediate cleanup */
    fun hardClose() {
        FileLogger.log(TAG, "hardClose()")
        val ws = webSocket
        webSocket = null
        isConnected = false
        isClosing = false
        synchronized(pendingAudio) { pendingAudio.clear() }
        try { ws?.cancel() } catch (_: Exception) {}
    }
}
