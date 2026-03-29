package helium314.keyboard.latin.whisper

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

private const val TAG = "ActionBarController"

class ActionBarController(
    private val actionBarView: View,
    private val onMicClick: Runnable,
    private val onActionResult: java.util.function.Consumer<String>,
    private val onSettingsClick: Runnable? = null
) {
    private val micButton: ImageButton = actionBarView.findViewById(R.id.action_bar_toggle)
    private val btnTranslate: Button = actionBarView.findViewById(R.id.action_btn_translate)
    private val btnGrammar: Button = actionBarView.findViewById(R.id.action_btn_grammar)
    private val btnMailFr: Button = actionBarView.findViewById(R.id.action_btn_mail_fr)
    private val btnMailEn: Button = actionBarView.findViewById(R.id.action_btn_mail_en)

    private var pulseAnimator: ObjectAnimator? = null
    private var isProcessing = false
    private var getSelectedText: java.util.function.Supplier<String?>? = null

    fun init(selectedTextProvider: java.util.function.Supplier<String?>) {
        getSelectedText = selectedTextProvider

        micButton.setOnClickListener { onMicClick.run() }
        micButton.setOnLongClickListener {
            onSettingsClick?.run()
            true
        }
        btnTranslate.setOnClickListener { processAction("translate") }
        btnGrammar.setOnClickListener { processAction("grammar") }
        btnMailFr.setOnClickListener { processAction("mail_fr") }
        btnMailEn.setOnClickListener { processAction("mail_en") }
    }

    fun updateRecordingState(state: WhisperManager.RecordingState) {
        when (state) {
            WhisperManager.RecordingState.RECORDING -> {
                setMicColor(Color.parseColor("#CC0000"))
                startPulseAnimation()
                setButtonsEnabled(false)
            }
            WhisperManager.RecordingState.TRANSCRIBING -> {
                stopPulseAnimation()
                setMicColor(Color.parseColor("#FF8800"))
                setButtonsEnabled(false)
            }
            WhisperManager.RecordingState.IDLE -> {
                stopPulseAnimation()
                setMicColor(Color.TRANSPARENT)
                micButton.alpha = 1f
                setButtonsEnabled(!isProcessing)
            }
        }
    }

    private fun processAction(action: String) {
        if (isProcessing) return

        val selectedText = getSelectedText?.get()
        if (selectedText.isNullOrBlank()) {
            Toast.makeText(actionBarView.context, R.string.action_bar_no_selection, Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = actionBarView.context.prefs().getString(Settings.PREF_GEMINI_API_KEY, "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(actionBarView.context, "Configure Gemini API key in settings", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        setButtonsEnabled(false)
        setMicColor(Color.parseColor("#FF8800"))

        GeminiClient(apiKey).process(
            text = selectedText,
            action = action,
            onResult = { result ->
                actionBarView.post {
                    isProcessing = false
                    setMicColor(Color.TRANSPARENT)
                    setButtonsEnabled(true)
                    onActionResult.accept(result)
                }
            },
            onError = { error ->
                actionBarView.post {
                    isProcessing = false
                    setMicColor(Color.TRANSPARENT)
                    setButtonsEnabled(true)
                    Log.e(TAG, "Gemini error: $error")
                    Toast.makeText(actionBarView.context, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        btnTranslate.isEnabled = enabled; btnTranslate.alpha = alpha
        btnGrammar.isEnabled = enabled; btnGrammar.alpha = alpha
        btnMailFr.isEnabled = enabled; btnMailFr.alpha = alpha
        btnMailEn.isEnabled = enabled; btnMailEn.alpha = alpha
    }

    private fun setMicColor(color: Int) {
        micButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()
        pulseAnimator = ObjectAnimator.ofFloat(micButton, "alpha", 1f, 0.4f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }
}
