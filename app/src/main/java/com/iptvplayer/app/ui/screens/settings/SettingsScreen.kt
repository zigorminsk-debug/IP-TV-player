package com.iptvplayer.app.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iptvplayer.app.BuildConfig
import com.iptvplayer.app.R
import com.iptvplayer.app.data.repo.AppSettings
import com.iptvplayer.app.data.repo.SettingsRepository
import com.iptvplayer.app.data.repo.UpdateRepository
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.ui.AppViewModel
import com.iptvplayer.app.ui.Routes
import com.iptvplayer.app.ui.components.tvFocusable
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance

    val settings = c.settings.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsRepository.DEFAULTS,
    )

    val playlists = c.playlists.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val epgSources = c.epg.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val updateState = c.updates.state

    val snackbar = MutableStateFlow<String?>(null)

    fun setTheme(v: String) = launch { c.settings.setTheme(v) }
    fun setShowLogos(v: Boolean) = launch { c.settings.setShowLogos(v) }
    fun setChannelFontScale(v: Int) = launch { c.settings.setChannelFontScale(v) }
    fun setProgrammeFontScale(v: Int) = launch { c.settings.setProgrammeFontScale(v) }
    fun setAudioLang(v: String) = launch { c.settings.setPreferredAudioLang(v) }
    fun setSubsLang(v: String) = launch { c.settings.setPreferredSubsLang(v) }
    fun setDefaultSort(v: com.iptvplayer.app.data.model.ChannelSort) =
        launch { c.settings.setDefaultSort(v) }
    fun setParental(v: Boolean) = launch { c.settings.setParentalEnabled(v) }
    fun setAutoRefresh(v: Int) = launch { c.settings.setAutoRefreshHours(v) }
    fun setAutoUpdate(v: Boolean) = launch { c.settings.setAutoUpdateApp(v) }
    fun setUpdateRepo(v: String) = launch { c.settings.setUpdateRepoOverride(v) }

    fun setPin(pin: String) = launch {
        c.settings.setPin(pin)
        snackbar.value = c.appContext.getString(R.string.pin_saved)
    }

    fun clearPin() = launch {
        c.settings.setPin(null)
        snackbar.value = c.appContext.getString(R.string.pin_cleared)
    }

    fun checkUpdate() = launch { c.updates.check(force = true) }

    fun clearEpg() = launch {
        c.epg.clearAllProgrammes()
        snackbar.value = c.appContext.getString(R.string.epg_cache_cleared)
    }

    fun clearProgress() = launch {
        c.progress.clear()
        snackbar.value = c.appContext.getString(R.string.progress_cleared)
    }

    fun exportTo(uri: Uri) = launch {
        runCatching {
            val text = c.backup.export()
            c.appContext.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray())
            }
        }.onSuccess {
            snackbar.value = c.appContext.getString(R.string.export_done)
        }.onFailure {
            snackbar.value = it.message
        }
    }

    fun importFrom(uri: Uri) = launch {
        runCatching {
            val text = c.appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8) ?: error("empty file")
            val count = c.backup.import(text)
            snackbar.value = c.appContext.getString(R.string.import_done, count)
        }.onFailure {
            snackbar.value = it.message
        }
    }

    fun showSnackbar(text: String?) {
        snackbar.value = text
    }

    fun dismissSnackbar() {
        snackbar.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController, appVm: AppViewModel) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val updateState by vm.updateState.collectAsState()

    var showPinDialog by remember { mutableStateOf(false) }
    var showAudioLang by remember { mutableStateOf(false) }
    var showSubsLang by remember { mutableStateOf(false) }
    var showUpdateRepo by remember { mutableStateOf(false) }
    var confirmClearEpg by remember { mutableStateOf(false) }
    var confirmClearProgress by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importFrom) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )

        LazyColumn(Modifier.fillMaxSize()) {
            // ---- playlists / epg
            item {
                SectionHeader(stringResource(R.string.section_sources))
                SettingsRow(
                    icon = Icons.Filled.Link,
                    title = stringResource(R.string.playlists),
                    onClick = { navController.navigate(Routes.PLAYLISTS) },
                )
                SettingsRow(
                    icon = Icons.Filled.DateRange,
                    title = stringResource(R.string.epg_sources),
                    subtitle = stringResource(R.string.epg_sources_subtitle),
                    onClick = { navController.navigate(Routes.EPG_SOURCES) },
                )
            }

            // ---- player
            item {
                SectionHeader(stringResource(R.string.section_player))
                SwitchRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.show_logos),
                    checked = settings.showLogos,
                    onChange = vm::setShowLogos,
                )
                FontScaleRow(stringResource(R.string.channel_font_size), settings.channelFontScale, vm::setChannelFontScale)
                FontScaleRow(stringResource(R.string.programme_font_size), settings.programmeFontScale, vm::setProgrammeFontScale)
                SettingsRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.audio_lang),
                    subtitle = settings.preferredAudioLang.ifBlank {
                        stringResource(R.string.auto_detect)
                    },
                    onClick = { showAudioLang = true },
                )
                SettingsRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.subs_lang),
                    subtitle = settings.preferredSubsLang.ifBlank {
                        stringResource(R.string.auto_detect)
                    },
                    onClick = { showSubsLang = true },
                )
            }

            // ---- appearance
            item {
                SectionHeader(stringResource(R.string.section_appearance))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "SYSTEM" to R.string.theme_system,
                            "DARK" to R.string.theme_dark,
                            "LIGHT" to R.string.theme_light,
                        ).forEach { (value, res) ->
                            FilterChip(
                                selected = settings.theme == value,
                                onClick = { vm.setTheme(value) },
                                label = { Text(stringResource(res)) },
                            )
                        }
                    }
                }
            }

            // ---- parental
            item {
                SectionHeader(stringResource(R.string.section_parental))
                SwitchRow(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.parental_control),
                    subtitle = stringResource(R.string.parental_control_subtitle),
                    checked = settings.parentalEnabled,
                    onChange = vm::setParental,
                )
                if (settings.parentalEnabled) {
                    SettingsRow(
                        icon = Icons.Filled.Security,
                        title = stringResource(R.string.set_pin),
                        onClick = { showPinDialog = true },
                    )
                    playlists.forEach { pl ->
                        SettingsRow(
                            icon = Icons.Filled.Security,
                            title = stringResource(R.string.parental_editor_for, pl.name),
                            onClick = { navController.navigate("${Routes.EDITOR}/${pl.id}") },
                        )
                    }
                }
            }

            // ---- updates
            item {
                SectionHeader(stringResource(R.string.section_updates))
                SwitchRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(R.string.auto_update_app),
                    subtitle = stringResource(R.string.auto_update_app_subtitle),
                    checked = settings.autoUpdateApp,
                    onChange = vm::setAutoUpdate,
                )
                SettingsRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(R.string.check_update_now),
                    subtitle = stringResource(
                        R.string.version_fmt,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.BUILD_NUMBER,
                    ),
                    onClick = { vm.checkUpdate() },
                )
                SettingsRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(R.string.update_channel),
                    subtitle = settings.updateRepoOverride.ifBlank {
                        BuildConfig.UPDATE_REPO_SLUG
                    },
                    onClick = { showUpdateRepo = true },
                )
            }

            // ---- data
            item {
                SectionHeader(stringResource(R.string.section_data))
                SettingsRow(
                    icon = Icons.Filled.Backup,
                    title = stringResource(R.string.export_settings),
                    subtitle = stringResource(R.string.export_settings_subtitle),
                    onClick = { exportLauncher.launch("iptv-player-backup.json") },
                )
                SettingsRow(
                    icon = Icons.Filled.Backup,
                    title = stringResource(R.string.import_settings),
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/*"))
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.Delete,
                    title = stringResource(R.string.clear_epg_cache),
                    onClick = { confirmClearEpg = true },
                )
                SettingsRow(
                    icon = Icons.Filled.Delete,
                    title = stringResource(R.string.clear_progress),
                    onClick = { confirmClearProgress = true },
                )
            }

            // ---- about
            item {
                SectionHeader(stringResource(R.string.section_about))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            R.string.version_fmt,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.BUILD_NUMBER,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.about_build_info,
                            BuildConfig.VERSION_CODE.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_no_content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_license),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showPinDialog) {
        PinSetDialog(
            onDismiss = { showPinDialog = false },
            onSave = {
                vm.setPin(it)
                showPinDialog = false
            },
        )
    }

    if (showAudioLang) {
        TextSettingDialog(
            title = stringResource(R.string.audio_lang),
            initial = settings.preferredAudioLang,
            hint = stringResource(R.string.lang_hint),
            onDismiss = { showAudioLang = false },
            onSave = {
                vm.setAudioLang(it)
                showAudioLang = false
            },
        )
    }

    if (showSubsLang) {
        TextSettingDialog(
            title = stringResource(R.string.subs_lang),
            initial = settings.preferredSubsLang,
            hint = stringResource(R.string.lang_hint),
            onDismiss = { showSubsLang = false },
            onSave = {
                vm.setSubsLang(it)
                showSubsLang = false
            },
        )
    }

    if (showUpdateRepo) {
        TextSettingDialog(
            title = stringResource(R.string.update_channel),
            initial = settings.updateRepoOverride.ifBlank { BuildConfig.UPDATE_REPO_SLUG },
            hint = "owner/repo",
            onDismiss = { showUpdateRepo = false },
            onSave = {
                vm.setUpdateRepo(it)
                showUpdateRepo = false
            },
        )
    }

    if (confirmClearEpg) {
        com.iptvplayer.app.ui.components.ConfirmDialog(
            title = stringResource(R.string.clear_epg_cache),
            text = null,
            onConfirm = {
                vm.clearEpg()
                confirmClearEpg = false
            },
            onDismiss = { confirmClearEpg = false },
        )
    }

    if (confirmClearProgress) {
        com.iptvplayer.app.ui.components.ConfirmDialog(
            title = stringResource(R.string.clear_progress),
            text = null,
            onConfirm = {
                vm.clearProgress()
                confirmClearProgress = false
            },
            onDismiss = { confirmClearProgress = false },
        )
    }
}

// ------------------------------------------------------------------ rows

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FontScaleRow(title: String, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(80, 100, 120, 150, 180).forEach { size ->
                FilterChip(selected = value == size, onClick = { onChange(size) }, label = { Text("${size}%") })
            }
        }
    }
}

// ------------------------------------------------------------- dialogs

@Composable
private fun PinSetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text(stringResource(R.string.pin_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text(stringResource(R.string.pin_confirm)) },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != pin,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (pin.length >= 4 && pin == confirm) onSave(pin) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun TextSettingDialog(
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(hint) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
