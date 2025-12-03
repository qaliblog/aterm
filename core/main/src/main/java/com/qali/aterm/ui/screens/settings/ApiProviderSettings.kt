package com.qali.aterm.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.qali.aterm.api.ApiKey
import com.qali.aterm.api.ApiProvider
import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.api.ApiProviderType
import com.qali.aterm.api.ProviderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiProviderSettings() {
    var selectedProvider by remember { mutableStateOf(ApiProviderManager.selectedProvider) }
    var providers by remember { mutableStateOf(ApiProviderManager.getProviders()) }
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showEditKeyDialog by remember { mutableStateOf<ApiKey?>(null) }
    var showDeleteKeyDialog by remember { mutableStateOf<ApiKey?>(null) }
    
    // Update providers when selection changes
    LaunchedEffect(selectedProvider) {
        ApiProviderManager.selectedProvider = selectedProvider
        providers = ApiProviderManager.getProviders()
    }
    
    PreferenceGroup(heading = "API Provider Settings") {
        // Provider Selection
        PreferenceGroup(heading = "Select Provider") {
            ApiProviderType.values().forEach { providerType ->
                SettingsCard(
                    title = { Text(providerType.displayName) },
                    startWidget = {
                        RadioButton(
                            selected = selectedProvider == providerType,
                            onClick = {
                                selectedProvider = providerType
                                ApiProviderManager.selectedProvider = providerType
                                providers = ApiProviderManager.getProviders()
                            }
                        )
                    },
                    onClick = {
                        selectedProvider = providerType
                        ApiProviderManager.selectedProvider = providerType
                        providers = ApiProviderManager.getProviders()
                    }
                )
            }
        }
        
        // Model Selection
        PreferenceGroup(heading = "Model Configuration") {
                var currentModel by remember { mutableStateOf(ApiProviderManager.getCurrentModel()) }
                var currentBaseUrl by remember { mutableStateOf(ApiProviderManager.getCurrentBaseUrl()) }
                var currentTemperature by remember { mutableStateOf(ApiProviderManager.getCurrentTemperature()) }
                var currentMaxTokens by remember { mutableStateOf(ApiProviderManager.getCurrentMaxTokens()) }
                var currentTopP by remember { mutableStateOf(ApiProviderManager.getCurrentTopP()) }
                var showModelDialog by remember { mutableStateOf(false) }
                var showConfigDialog by remember { mutableStateOf(false) }
                
                // Update values when provider changes
                LaunchedEffect(selectedProvider) {
                    currentModel = ApiProviderManager.getCurrentModel()
                    currentBaseUrl = ApiProviderManager.getCurrentBaseUrl()
                    currentTemperature = ApiProviderManager.getCurrentTemperature()
                    currentMaxTokens = ApiProviderManager.getCurrentMaxTokens()
                    currentTopP = ApiProviderManager.getCurrentTopP()
                }
                
                SettingsCard(
                    title = { Text("Model") },
                    description = { 
                        val modelText = (currentModel ?: "").takeIf { it.isNotBlank() } ?: "Not set"
                        Text(modelText)
                    },
                    endWidget = {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Model")
                    },
                    onClick = { showModelDialog = true }
                )
                
                // Show base URL for custom provider
                if (selectedProvider == ApiProviderType.CUSTOM) {
                    SettingsCard(
                        title = { Text("Base URL") },
                        description = { 
                            val urlText = (currentBaseUrl ?: "").takeIf { it.isNotBlank() } ?: "Not set"
                            Text(urlText)
                        },
                        endWidget = {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Base URL")
                        },
                        onClick = { showModelDialog = true }
                    )
                }
                
                // LLM Parameters
                SettingsCard(
                    title = { Text("LLM Parameters") },
                    description = { 
                        Text("Temp: ${String.format("%.1f", currentTemperature)}, MaxTokens: $currentMaxTokens, TopP: ${String.format("%.2f", currentTopP)}")
                    },
                    endWidget = {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Parameters")
                    },
                    onClick = { showConfigDialog = true }
                )
                
                if (showModelDialog) {
                    ModelSelectionDialog(
                        providerType = selectedProvider,
                        currentModel = currentModel ?: "",
                        currentBaseUrl = currentBaseUrl ?: "",
                        onDismiss = { showModelDialog = false },
                        onSave = { model ->
                            val safeModel = model ?: ""
                            ApiProviderManager.setCurrentModel(safeModel)
                            currentModel = safeModel
                            // Update config with model defaults
                            currentTemperature = ApiProviderManager.getCurrentTemperature()
                            currentMaxTokens = ApiProviderManager.getCurrentMaxTokens()
                            currentTopP = ApiProviderManager.getCurrentTopP()
                            showModelDialog = false
                        },
                        onBaseUrlSave = { baseUrl ->
                            val safeBaseUrl = baseUrl ?: ""
                            ApiProviderManager.setCurrentBaseUrl(safeBaseUrl)
                            currentBaseUrl = safeBaseUrl
                        }
                    )
                }
                
                if (showConfigDialog) {
                    ProviderConfigDialog(
                        temperature = currentTemperature,
                        maxTokens = currentMaxTokens,
                        topP = currentTopP,
                        onDismiss = { showConfigDialog = false },
                        onSave = { temp, tokens, topP ->
                            ApiProviderManager.setCurrentTemperature(temp)
                            ApiProviderManager.setCurrentMaxTokens(tokens)
                            ApiProviderManager.setCurrentTopP(topP)
                            currentTemperature = temp
                            currentMaxTokens = tokens
                            currentTopP = topP
                            showConfigDialog = false
                        }
                    )
                }
            }
        
        // API Keys Management
        PreferenceGroup(heading = "API Keys for ${selectedProvider.displayName}") {
                val currentProvider = providers[selectedProvider] ?: ApiProvider(selectedProvider)
                val apiKeys = currentProvider.apiKeys
                
                if (apiKeys.isEmpty()) {
                    SettingsCard(
                        title = { Text("No API keys configured") },
                        description = { Text("Tap to add your first API key") },
                        onClick = { showAddKeyDialog = true }
                    )
                } else {
                    apiKeys.forEach { key ->
                        SettingsCard(
                            title = { 
                                val labelText = (key.label ?: "").ifEmpty { "API Key ${(key.id ?: "").take(8)}" }
                                Text(
                                    labelText,
                                    fontWeight = if (key.isActive) FontWeight.Normal else FontWeight.Light
                                )
                            },
                            description = { 
                                Text(
                                    if (key.isActive) "Active" else "Inactive",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            endWidget = {
                                Row {
                                    IconButton(
                                        onClick = { showEditKeyDialog = key }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(
                                        onClick = { showDeleteKeyDialog = key }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            },
                            onClick = { showEditKeyDialog = key }
                        )
                    }
                    
                    SettingsCard(
                        title = { Text("Add API Key") },
                        description = { Text("Add a new API key for ${selectedProvider.displayName}") },
                        startWidget = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        onClick = { showAddKeyDialog = true }
                    )
                }
            }
        }
    
    // Add Key Dialog
    if (showAddKeyDialog) {
        AddApiKeyDialog(
            providerType = selectedProvider,
            onDismiss = { showAddKeyDialog = false },
            onSave = { apiKey ->
                ApiProviderManager.addApiKey(selectedProvider, apiKey)
                providers = ApiProviderManager.getProviders()
                showAddKeyDialog = false
            }
        )
    }
    
    // Edit Key Dialog
    showEditKeyDialog?.let { key ->
        EditApiKeyDialog(
            providerType = selectedProvider,
            apiKey = key,
            onDismiss = { showEditKeyDialog = null },
            onSave = { updatedKey ->
                ApiProviderManager.updateApiKey(selectedProvider, updatedKey)
                providers = ApiProviderManager.getProviders()
                showEditKeyDialog = null
            }
        )
    }
    
    // Delete Key Dialog
    showDeleteKeyDialog?.let { key ->
        AlertDialog(
            onDismissRequest = { showDeleteKeyDialog = null },
            title = { Text("Delete API Key?") },
            text = { Text("Are you sure you want to delete this API key? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ApiProviderManager.removeApiKey(selectedProvider, key.id)
                        providers = ApiProviderManager.getProviders()
                        showDeleteKeyDialog = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteKeyDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AddApiKeyDialog(
    providerType: ApiProviderType,
    onDismiss: () -> Unit,
    onSave: (ApiKey) -> Unit
) {
    var keyText by remember { mutableStateOf("") }
    var labelText by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add API Key") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Label (optional)") },
                    placeholder = { Text("e.g., Primary Key, Backup Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("API Key") },
                    placeholder = { Text("Enter your API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val safeKeyText = keyText ?: ""
                    val safeLabelText = labelText ?: ""
                    if (safeKeyText.isNotBlank()) {
                        onSave(ApiKey(key = safeKeyText, label = safeLabelText))
                    }
                },
                enabled = (keyText ?: "").isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditApiKeyDialog(
    providerType: ApiProviderType,
    apiKey: ApiKey,
    onDismiss: () -> Unit,
    onSave: (ApiKey) -> Unit
) {
    var keyText by remember { mutableStateOf(apiKey.key ?: "") }
    var labelText by remember { mutableStateOf(apiKey.label ?: "") }
    var isActive by remember { mutableStateOf(apiKey.isActive) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit API Key") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isActive,
                        onCheckedChange = { isActive = it }
                    )
                    Text("Active", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val safeKeyText = keyText ?: ""
                    val safeLabelText = labelText ?: ""
                    onSave(apiKey.copy(key = safeKeyText, label = safeLabelText, isActive = isActive))
                },
                enabled = (keyText ?: "").isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ProviderConfigDialog(
    temperature: Float,
    maxTokens: Int,
    topP: Float,
    onDismiss: () -> Unit,
    onSave: (Float, Int, Float) -> Unit
) {
    var tempValue by remember { mutableStateOf(temperature.toString()) }
    var tokensValue by remember { mutableStateOf(maxTokens.toString()) }
    var topPValue by remember { mutableStateOf(topP.toString()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LLM Parameters") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { 
                        val filtered = it.filter { char -> char.isDigit() || char == '.' }
                        tempValue = filtered
                    },
                    label = { Text("Temperature (0.1 - 1.2)") },
                    placeholder = { Text("0.7") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Controls randomness. Lower = more focused, Higher = more creative") }
                )
                OutlinedTextField(
                    value = tokensValue,
                    onValueChange = { 
                        val filtered = it.filter { char -> char.isDigit() }
                        tokensValue = filtered
                    },
                    label = { Text("Max Tokens") },
                    placeholder = { Text("2048") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Maximum tokens in response") }
                )
                OutlinedTextField(
                    value = topPValue,
                    onValueChange = { 
                        val filtered = it.filter { char -> char.isDigit() || char == '.' }
                        topPValue = filtered
                    },
                    label = { Text("Top P (0.0 - 1.0)") },
                    placeholder = { Text("1.0") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Nucleus sampling threshold") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val temp = tempValue.toFloatOrNull()?.coerceIn(0.1f, 1.2f) ?: 0.7f
                    val tokens = tokensValue.toIntOrNull()?.coerceIn(1, 8192) ?: 2048
                    val topP = topPValue.toFloatOrNull()?.coerceIn(0.0f, 1.0f) ?: 1.0f
                    onSave(temp, tokens, topP)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
