package com.qali.aterm.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.settings.Settings
import com.qali.aterm.agent.utils.CommandAllowlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettings() {
    var useApiSearch by remember { mutableStateOf(Settings.use_api_search) }
    var recursiveCurls by remember { mutableStateOf(Settings.custom_search_recursive_curls) }
    
    // Command Allowlist state
    val context = LocalContext.current
    var allowedCommands by remember { 
        mutableStateOf(CommandAllowlist.getAllowedCommands(context).sorted())
    }
    var showResetDialog by remember { mutableStateOf(false) }
    
    PreferenceGroup(heading = "Agent Settings") {
        SettingsCard(
            title = { Text("Use API Search") },
            description = { 
                Text(
                    if (useApiSearch) {
                        "Uses Gemini API's built-in Google Search. Faster but requires API support."
                    } else {
                        "Uses custom search engine with direct API calls and AI analysis. More control and recursive searches."
                    }
                )
            },
            startWidget = {
                Switch(
                    checked = useApiSearch,
                    onCheckedChange = {
                        useApiSearch = it
                        Settings.use_api_search = it
                    }
                )
            },
            onClick = {
                useApiSearch = !useApiSearch
                Settings.use_api_search = useApiSearch
            }
        )
        
        if (!useApiSearch) {
            SettingsCard(
                title = { Text("Custom Search Recursive Levels") },
                description = { 
                    Column {
                        Text(
                            "Number of recursive search levels (1-8). Higher values gather more comprehensive information but take longer. Current: $recursiveCurls"
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = recursiveCurls.toFloat(),
                            onValueChange = { newValue ->
                                recursiveCurls = newValue.toInt().coerceIn(1, 8)
                                Settings.custom_search_recursive_curls = recursiveCurls
                            },
                            valueRange = 1f..8f,
                            steps = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Levels: $recursiveCurls",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                onClick = { /* No action needed, slider handles interaction */ }
            )
        }
        
        // Command Allowlist Settings
        PreferenceGroup(heading = "Command Allowlist") {
            // Display allowed commands
            SettingsCard(
                title = { Text("Allowed Commands") },
                description = { 
                    Text(
                        if (allowedCommands.isNotEmpty()) {
                            "${allowedCommands.size} command(s) allowed"
                        } else {
                            "No commands in allowlist"
                        }
                    )
                },
                onClick = {
                    // Could show a dialog with full list if needed
                }
            )
            
            // Show list of commands (first 10)
            if (allowedCommands.isNotEmpty()) {
                allowedCommands.take(10).forEach { command ->
                    SettingsCard(
                        title = { 
                            Text(
                                CommandAllowlist.formatCommand(command),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = { /* View command details if needed */ }
                    )
                }
                if (allowedCommands.size > 10) {
                    Text(
                        "... and ${allowedCommands.size - 10} more commands",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            
            // Reset to base allowlist button
            SettingsCard(
                title = { Text("Reset Allowlist") },
                description = { 
                    Text("Reset allowlist to base startup commands only")
                },
                startWidget = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                onClick = {
                    showResetDialog = true
                }
            )
        }
        
        // Reset confirmation dialog
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Allowlist?") },
                text = { 
                    Text("This will remove all custom commands from the allowlist and reset to base startup commands only. This action cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            CommandAllowlist.resetToBase(context)
                            allowedCommands = CommandAllowlist.getAllowedCommands(context).sorted()
                            showResetDialog = false
                        }
                    ) {
                        Text("Reset", color = MaterialTheme.colorScheme.error)
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
