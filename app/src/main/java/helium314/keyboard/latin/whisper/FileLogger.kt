package helium314.keyboard.latin.whisper

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple file logger — writes to /sdcard/Android/data/<pkg>/files/whisper_debug.log
 * Readable via: adb shell cat /sdcard/Android/data/helium314.keyboard.debug/files/whisper_debug.log
 */
object FileLogger {
    private var writer: FileWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var startTime = 0L

    fun init(context: Context) {
        try {
            val file = File(context.getExternalFilesDir(null), "whisper_debug.log")
            // Truncate on each keyboard restart
            writer = FileWriter(file, false)
            startTime = System.currentTimeMillis()
            log("FileLogger", "=== Keyboard started ===")
        } catch (e: Exception) {
            android.util.Log.e("FileLogger", "Failed to init file logger", e)
        }
    }

    fun log(tag: String, msg: String) {
        try {
            val elapsed = System.currentTimeMillis() - startTime
            val time = dateFormat.format(Date())
            val line = "$time [+${elapsed}ms] $tag: $msg\n"
            synchronized(this) {
                writer?.write(line)
                writer?.flush()
            }
        } catch (_: Exception) {}
    }

    fun close() {
        try { writer?.close() } catch (_: Exception) {}
    }
}
