// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.whisper.ModelCategory
import helium314.keyboard.latin.whisper.WhisperModel
import helium314.keyboard.latin.whisper.WhisperModelManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.ListPreference
import kotlinx.coroutines.launch

@Composable
fun WhisperSettingsScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()

    // Shared reactive state — triggers recomposition of ALL model cards
    var activeModelPref by remember {
        mutableStateOf(
            prefs.getString(Settings.PREF_WHISPER_MODEL, Defaults.PREF_WHISPER_MODEL)!!
        )
    }

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
            // Language selector
            LanguageSelector()

            Spacer(Modifier.height(16.dp))

            // French-optimized models first
            Text(
                text = stringResource(R.string.whisper_models_french),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            WhisperModel.entries.filter { it.category == ModelCategory.FRENCH }.forEach { model ->
                WhisperModelItem(
                    model = model,
                    isActive = activeModelPref == model.name.lowercase(),
                    onActivate = {
                        prefs.edit { putString(Settings.PREF_WHISPER_MODEL, model.name.lowercase()) }
                        activeModelPref = model.name.lowercase()
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Generic multilingual models
            Text(
                text = stringResource(R.string.whisper_models_generic),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            WhisperModel.entries.filter { it.category == ModelCategory.GENERIC }.forEach { model ->
                WhisperModelItem(
                    model = model,
                    isActive = activeModelPref == model.name.lowercase(),
                    onActivate = {
                        prefs.edit { putString(Settings.PREF_WHISPER_MODEL, model.name.lowercase()) }
                        activeModelPref = model.name.lowercase()
                    }
                )
            }
        }
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
) + WhisperModel.entries.map { model ->
    Setting(context, "whisper_model_${model.name}",
        R.string.whisper_model_item_title,
        R.string.whisper_model_item_summary
    ) {
        WhisperModelItem(model)
    }
}

@Composable
private fun WhisperModelItem(
    model: WhisperModel,
    isActive: Boolean = false,
    onActivate: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val modelManager = remember { WhisperModelManager(context) }
    val scope = rememberCoroutineScope()
    val prefs = context.prefs()

    var isDownloaded by remember { mutableStateOf(modelManager.isDownloaded(model)) }
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadFailed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
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
                Column {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${model.sizeMB} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isDownloaded && !isActive && onActivate != null) {
                        Button(onClick = onActivate) {
                            Text(stringResource(R.string.whisper_activate))
                        }
                    }
                    if (isActive && isDownloaded) {
                        Text(
                            text = stringResource(R.string.whisper_active),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
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
                if (!isDownloaded && !isDownloading) {
                    OutlinedButton(onClick = {
                        isDownloading = true
                        downloadFailed = false
                        scope.launch {
                            val success = modelManager.downloadModel(model) { progress = it }
                            isDownloading = false
                            isDownloaded = success
                            downloadFailed = !success
                            if (success && modelManager.getDownloadedModels().size == 1) {
                                prefs.edit { putString(Settings.PREF_WHISPER_MODEL, model.name.lowercase()) }
                                onActivate?.invoke()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.whisper_download))
                    }
                }
                if (isDownloaded && !isActive) {
                    TextButton(onClick = {
                        modelManager.deleteModel(model)
                        isDownloaded = false
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
