package com.qali.aterm.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qali.aterm.api.ApiProviderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionDialog(
    providerType: ApiProviderType,
    currentModel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var selectedModel by remember { mutableStateOf(currentModel) }
    var customModel by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    
    val suggestedModels = getSuggestedModels(providerType)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Suggested models
                Text(
                    "Suggested Models",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(suggestedModels) { model ->
                        ModelOption(
                            model = model,
                            isSelected = selectedModel == model.value,
                            onClick = {
                                selectedModel = model.value
                                showCustomInput = false
                            }
                        )
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Custom model option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = showCustomInput,
                        onClick = { showCustomInput = true }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Custom Model", fontWeight = FontWeight.Medium)
                        Text(
                            "Enter a custom model name",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (showCustomInput) {
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = {
                            customModel = it
                            selectedModel = it
                        },
                        label = { Text("Model Name") },
                        placeholder = { Text("e.g., gemini-2.5-pro") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedModel.isNotBlank()) {
                        onSave(selectedModel)
                    }
                },
                enabled = selectedModel.isNotBlank()
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
private fun ModelOption(
    model: ModelSuggestion,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        model.name,
                        fontWeight = FontWeight.Medium
                    )
                    if (model.supportsWebSearch) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "Web Search",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    model.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

data class ModelSuggestion(
    val name: String,
    val value: String,
    val description: String,
    val supportsWebSearch: Boolean = false
)

private fun getSuggestedModels(providerType: ApiProviderType): List<ModelSuggestion> {
    return when (providerType) {
        ApiProviderType.GOOGLE -> listOf(
            ModelSuggestion(
                name = "Gemini 2.0 Flash (Experimental)",
                value = "gemini-2.0-flash-exp",
                description = "Latest experimental model with web search support. Fast and capable.",
                supportsWebSearch = true
            ),
            ModelSuggestion(
                name = "Gemini 2.5 Pro",
                value = "gemini-2.5-pro",
                description = "Most capable model for complex tasks requiring deep reasoning and creativity."
            ),
            ModelSuggestion(
                name = "Gemini 2.5 Flash",
                value = "gemini-2.5-flash",
                description = "Balanced model for tasks needing speed and reasoning."
            ),
            ModelSuggestion(
                name = "Gemini 2.5 Flash Lite",
                value = "gemini-2.5-flash-lite",
                description = "Fastest model for simple tasks that need quick responses."
            ),
            ModelSuggestion(
                name = "Gemini 1.5 Pro",
                value = "gemini-1.5-pro",
                description = "Previous generation Pro model with large context window."
            ),
            ModelSuggestion(
                name = "Gemini 1.5 Flash",
                value = "gemini-1.5-flash",
                description = "Previous generation Flash model, fast and efficient."
            )
        )
        ApiProviderType.OPENAI -> listOf(
            ModelSuggestion(
                name = "GPT-4",
                value = "gpt-4",
                description = "Most capable model for complex reasoning tasks."
            ),
            ModelSuggestion(
                name = "GPT-4 Turbo",
                value = "gpt-4-turbo-preview",
                description = "Faster version of GPT-4 with improved performance."
            ),
            ModelSuggestion(
                name = "GPT-3.5 Turbo",
                value = "gpt-3.5-turbo",
                description = "Fast and cost-effective for most tasks."
            )
        )
        ApiProviderType.ANTHROPIC -> listOf(
            ModelSuggestion(
                name = "Claude 3.5 Sonnet",
                value = "claude-3-5-sonnet-20241022",
                description = "Latest and most capable Claude model."
            ),
            ModelSuggestion(
                name = "Claude 3 Opus",
                value = "claude-3-opus-20240229",
                description = "Most powerful Claude model for complex tasks."
            ),
            ModelSuggestion(
                name = "Claude 3 Haiku",
                value = "claude-3-haiku-20240307",
                description = "Fastest Claude model for quick responses."
            )
        )
        ApiProviderType.COHERE -> listOf(
            ModelSuggestion(
                name = "Command R+",
                value = "command-r-plus",
                description = "Most capable Cohere model."
            ),
            ModelSuggestion(
                name = "Command R",
                value = "command-r",
                description = "Fast and efficient Cohere model."
            )
        )
        ApiProviderType.MISTRAL -> listOf(
            ModelSuggestion(
                name = "Mistral Large",
                value = "mistral-large-latest",
                description = "Most capable Mistral model."
            ),
            ModelSuggestion(
                name = "Mistral Medium",
                value = "mistral-medium-latest",
                description = "Balanced Mistral model."
            )
        )
        ApiProviderType.CUSTOM -> emptyList()
    }
}
