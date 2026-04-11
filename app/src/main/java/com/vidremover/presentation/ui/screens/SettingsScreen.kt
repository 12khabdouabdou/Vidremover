package com.vidremover.presentation.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vidremover.domain.usecase.DetectionMode
import com.vidremover.presentation.viewmodel.AppTheme
import com.vidremover.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val appVersion by viewModel.appVersion.collectAsState()
    val totalVideos by viewModel.totalVideos.collectAsState()
    val totalStorage by viewModel.totalStorage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Detection Preferences Section
            SettingsSectionHeader(title = "Detection Preferences")

            // Default Detection Mode
            DetectionModeSetting(
                currentMode = settings.detectionMode,
                onModeSelected = { viewModel.setDetectionMode(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // pHash Threshold
            PHashThresholdSetting(
                currentThreshold = settings.pHashThreshold,
                onThresholdChanged = { viewModel.setPHashThreshold(it) }
            )

            // Theme Section
            SettingsSectionHeader(title = "Appearance")

            ThemeSetting(
                currentTheme = settings.theme,
                onThemeSelected = { viewModel.setTheme(it) }
            )

            // Statistics Section
            SettingsSectionHeader(title = "Statistics")

            StatisticItem(
                icon = Icons.Default.VideoLibrary,
                label = "Total Videos",
                value = totalVideos.toString()
            )

            StatisticItem(
                icon = Icons.Default.Storage,
                label = "Total Storage",
                value = viewModel.formatStorage(totalStorage)
            )

            // App Info Section
            SettingsSectionHeader(title = "About")

            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text(appVersion) },
                leadingContent = {
                    Icon(Icons.Default.Info, contentDescription = null)
                }
            )

            // Reset Settings
            ListItem(
                headlineContent = { Text("Reset to Defaults") },
                supportingContent = { Text("Restore all settings to their default values") },
                leadingContent = {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                },
                modifier = Modifier.clickable { showResetDialog = true }
            )
        }

        // Reset Confirmation Dialog
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Settings") },
                text = { Text("Are you sure you want to reset all settings to their default values?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.resetToDefaults()
                            showResetDialog = false
                        }
                    ) {
                        Text("Reset")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun DetectionModeSetting(
    currentMode: DetectionMode,
    onModeSelected: (DetectionMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("Default Detection Mode") },
        supportingContent = { Text(getDetectionModeDescription(currentMode)) },
        leadingContent = {
            Icon(Icons.Default.SettingsSuggest, contentDescription = null)
        },
        trailingContent = {
            Text(
                text = when (currentMode) {
                    DetectionMode.MD5_ONLY -> "MD5"
                    DetectionMode.PHASH_ONLY -> "pHash"
                    DetectionMode.BOTH -> "Both"
                },
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        },
        modifier = Modifier.clickable { expanded = true }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DetectionMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = when (mode) {
                                DetectionMode.MD5_ONLY -> "MD5 Only"
                                DetectionMode.PHASH_ONLY -> "Perceptual Hash"
                                DetectionMode.BOTH -> "Both (Recommended)"
                            }
                        )
                        Text(
                            text = getDetectionModeDescription(mode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = {
                    onModeSelected(mode)
                    expanded = false
                },
                leadingIcon = if (mode == currentMode) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null
            )
        }
    }
}

@Composable
private fun PHashThresholdSetting(
    currentThreshold: Float,
    onThresholdChanged: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "pHash Similarity Threshold",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${(currentThreshold * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Lower values detect more similar videos. Higher values are more strict.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = currentThreshold,
            onValueChange = onThresholdChanged,
            valueRange = 0.8f..1.0f,
            steps = 4, // 0.8, 0.85, 0.9, 0.95, 1.0
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("80%", style = MaterialTheme.typography.bodySmall)
            Text("90%", style = MaterialTheme.typography.bodySmall)
            Text("100%", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThemeSetting(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("Theme") },
        supportingContent = { Text(getThemeDescription(currentTheme)) },
        leadingContent = {
            Icon(
                imageVector = when (currentTheme) {
                    AppTheme.LIGHT -> Icons.Default.LightMode
                    AppTheme.DARK -> Icons.Default.DarkMode
                    AppTheme.SYSTEM -> Icons.Default.BrightnessAuto
                },
                contentDescription = null
            )
        },
        trailingContent = {
            Text(
                text = when (currentTheme) {
                    AppTheme.LIGHT -> "Light"
                    AppTheme.DARK -> "Dark"
                    AppTheme.SYSTEM -> "Auto"
                },
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        },
        modifier = Modifier.clickable { expanded = true }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        AppTheme.entries.forEach { theme ->
            DropdownMenuItem(
                text = { Text(getThemeDescription(theme)) },
                onClick = {
                    onThemeSelected(theme)
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = when (theme) {
                            AppTheme.LIGHT -> Icons.Default.LightMode
                            AppTheme.DARK -> Icons.Default.DarkMode
                            AppTheme.SYSTEM -> Icons.Default.BrightnessAuto
                        },
                        contentDescription = null
                    )
                },
                trailingIcon = if (theme == currentTheme) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null
            )
        }
    }
}

@Composable
private fun StatisticItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value, fontWeight = FontWeight.Medium) },
        leadingContent = {
            Icon(icon, contentDescription = null)
        }
    )
}

private fun getDetectionModeDescription(mode: DetectionMode): String {
    return when (mode) {
        DetectionMode.MD5_ONLY -> "Fast detection for exact duplicates only"
        DetectionMode.PHASH_ONLY -> "Detects similar videos even after re-encoding"
        DetectionMode.BOTH -> "Best accuracy: exact + similar video detection"
    }
}

private fun getThemeDescription(theme: AppTheme): String {
    return when (theme) {
        AppTheme.LIGHT -> "Always use light theme"
        AppTheme.DARK -> "Always use dark theme"
        AppTheme.SYSTEM -> "Follow system setting"
    }
}
