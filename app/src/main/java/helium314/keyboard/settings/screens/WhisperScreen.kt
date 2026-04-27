// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.whisper.SherpaModelManager
import helium314.keyboard.latin.whisper.TranscriptionMode
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.ListPreference
import kotlinx.coroutines.launch

@Composable
fun WhisperSettingsScreen(onClickBack: () -> Unit) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.whisper_settings_title)) },
                navigationIcon = {
                    BackButton(onClickBack)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            TranscriptionModeSelector()
            DeepgramApiKeyField()

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            GeminiApiKeyField()

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            LanguageSelector()

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Local model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ParakeetModelCard()
        }
    }
}

@Composable
private fun TranscriptionModeSelector() {
    val context = LocalContext.current
    val prefs = context.prefs()
    val modes = TranscriptionMode.entries
    val labels = listOf(
        stringResource(R.string.whisper_mode_local),
        stringResource(R.string.whisper_mode_cloud),
        stringResource(R.string.whisper_mode_auto)
    )
    var selected by remember {
        mutableStateOf(
            TranscriptionMode.fromPref(
                prefs.getString(Settings.PREF_TRANSCRIPTION_MODE, Defaults.PREF_TRANSCRIPTION_MODE)!!
            )
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.whisper_mode_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.whisper_mode_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = {
                        selected = mode
                        prefs.edit { putString(Settings.PREF_TRANSCRIPTION_MODE, mode.name.lowercase()) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                ) {
                    Text(labels[index], style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DeepgramApiKeyField() {
    val context = LocalContext.current
    val prefs = context.prefs()
    val mode = TranscriptionMode.fromPref(
        prefs.getString(Settings.PREF_TRANSCRIPTION_MODE, Defaults.PREF_TRANSCRIPTION_MODE)!!
    )

    // Only show if cloud or auto mode
    if (mode == TranscriptionMode.LOCAL) return

    var apiKey by remember {
        mutableStateOf(prefs.getString(Settings.PREF_DEEPGRAM_API_KEY, "") ?: "")
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = apiKey,
            onValueChange = { newValue ->
                apiKey = newValue
                prefs.edit { putString(Settings.PREF_DEEPGRAM_API_KEY, newValue.trim()) }
            },
            label = { Text(stringResource(R.string.whisper_deepgram_api_key)) },
            supportingText = { Text(stringResource(R.string.whisper_deepgram_api_key_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GeminiApiKeyField() {
    val context = LocalContext.current
    val prefs = context.prefs()

    var apiKey by remember {
        mutableStateOf(prefs.getString(Settings.PREF_GEMINI_API_KEY, "") ?: "")
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.gemini_settings_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { newValue ->
                apiKey = newValue
                prefs.edit { putString(Settings.PREF_GEMINI_API_KEY, newValue.trim()) }
            },
            label = { Text(stringResource(R.string.gemini_api_key)) },
            supportingText = { Text(stringResource(R.string.gemini_api_key_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LanguageSelector() {
    val context = LocalContext.current
    val prefs = context.prefs()
    val items = listOf("fr", "en", "nl", "de", "auto")
    val labels = listOf("Francais", "English", "Nederlands", "Deutsch", "Auto-detect")
    var selected by remember {
        mutableStateOf(
            prefs.getString(Settings.PREF_WHISPER_LANGUAGE, Defaults.PREF_WHISPER_LANGUAGE)!!
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.whisper_language_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = {
                        selected = value
                        prefs.edit { putString(Settings.PREF_WHISPER_LANGUAGE, value) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, items.size)
                ) {
                    Text(labels[index], style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

fun createWhisperSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_WHISPER_LANGUAGE, R.string.whisper_language_title) {
        val items = listOf(
            "Francais" to "fr",
            "English" to "en",
            "Nederlands" to "nl",
            "Deutsch" to "de",
            "Auto-detect" to "auto",
        )
        ListPreference(it, items, Defaults.PREF_WHISPER_LANGUAGE)
    },
)

@Composable
private fun ParakeetModelCard() {
    val context = LocalContext.current
    val modelManager = remember { SherpaModelManager(context) }
    val scope = rememberCoroutineScope()

    var isInstalled by remember { mutableStateOf(modelManager.isInstalled()) }
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadFailed by remember { mutableStateOf(false) }

    val sizeMB = modelManager.totalDownloadSizeBytes() / 1_000_000

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Parakeet TDT v3",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Multilingual (FR, EN, ES, DE, IT and 20 more)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$sizeMB MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isInstalled) {
                    Text(
                        text = stringResource(R.string.whisper_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isDownloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            if (downloadFailed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.whisper_download_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isInstalled && !isDownloading) {
                    OutlinedButton(onClick = {
                        isDownloading = true
                        downloadFailed = false
                        scope.launch {
                            val ok = modelManager.downloadModel { progress = it }
                            isDownloading = false
                            isInstalled = ok
                            downloadFailed = !ok
                        }
                    }) {
                        Text(stringResource(R.string.whisper_download))
                    }
                }
                if (isInstalled) {
                    TextButton(onClick = {
                        modelManager.deleteModel()
                        isInstalled = false
                    }) {
                        Text(
                            stringResource(R.string.whisper_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
