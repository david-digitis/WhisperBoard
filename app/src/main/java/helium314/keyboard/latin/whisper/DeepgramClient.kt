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
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    @Volatile
    var isConnected = false
        private set
    @Volatile
    private var isClosing = false
    @Volatile
    private var hasNotifiedClose = false

    fun connect() {
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

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
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
                        Log.d(TAG, "Final: $transcript")
                        onFinalResult(transcript)
                        // If we already sent CloseStream, this is the last result
                        if (isClosing) {
                            Log.d(TAG, "Final result received after CloseStream, finishing")
                            forceClose()
                            notifyClosedOnce()
                        }
                    } else {
                        onPartialResult(transcript)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                isConnected = false
                if (isClosing) {
                    notifyClosedOnce()
                } else {
                    onError(t.message ?: "Connection failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                if (isClosing) {
                    notifyClosedOnce()
                }
            }
        })
    }

    fun sendAudio(audioBytes: ByteArray) {
        if (!isConnected) return
        webSocket?.send(okio.ByteString.of(*audioBytes))
    }

    fun closeGracefully() {
        isClosing = true
        isConnected = false
        try {
            webSocket?.send("{\"type\": \"CloseStream\"}")
        } catch (e: Exception) {
            Log.w(TAG, "Error sending CloseStream", e)
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
        try {
            webSocket?.close(1000, "Done")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
        isConnected = false
    }
}
