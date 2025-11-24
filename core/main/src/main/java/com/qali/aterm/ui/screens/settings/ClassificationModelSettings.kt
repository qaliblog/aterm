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
import com.qali.aterm.autogent.ClassificationModelManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassificationModelSettings() {
    var models by remember { mutableStateOf(ClassificationModelManager.getAvailableModels()) }
    var selectedModel by remember { mutableStateOf(ClassificationModelManager.getSelectedModel()) }
    var showAddModelDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf<ClassificationModelManager.ClassificationModel?>(null) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        models = ClassificationModelManager.getAvailableModels()
        selectedModel = ClassificationModelManager.getSelectedModel()
    }
    
    PreferenceGroup(heading = "Text Classification Model (Required for AutoAgent)") {
        // Warning if no model selected
        if (selectedModel == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Classification Model Required",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Please select or download a classification model to enable AutoAgent functionality.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        
        // Selected model display
        selectedModel?.let { model ->
            SettingsCard(
                title = { Text("Selected Model") },
                description = {
                    Column {
                        Text(
                            model.name,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            model.description,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (model.isDownloaded && model.filePath != null) {
                            Text(
                                "✓ Model downloaded and ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "⚠ Model not downloaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                endWidget = {
                    IconButton(onClick = {
                        ClassificationModelManager.setSelectedModel("")
                        selectedModel = null
                        models = ClassificationModelManager.getAvailableModels()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Deselect")
                    }
                },
                onClick = { /* Display only, no action */ }
            )
        }
        
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        
        // Built-in models
        PreferenceGroup(heading = "Built-in Models") {
            models.filter { it.isBuiltIn }.forEach { model ->
                ModelCard(
                    model = model,
                    isSelected = selectedModel?.id == model.id,
                    onSelect = {
                        ClassificationModelManager.setSelectedModel(model.id)
                        selectedModel = ClassificationModelManager.getSelectedModel()
                    },
                    onDownload = {
                        showDownloadDialog = model
                    }
                )
            }
        }
        
        // Custom models
        PreferenceGroup(heading = "Custom Models") {
            val customModels = models.filter { !it.isBuiltIn }
            
            if (customModels.isEmpty()) {
                SettingsCard(
                    title = { Text("No custom models") },
                    description = { Text("Add a custom model using the button below") },
                    onClick = { /* Display only, no action */ }
                )
            } else {
                customModels.forEach { model ->
                    ModelCard(
                        model = model,
                        isSelected = selectedModel?.id == model.id,
                        onSelect = {
                            ClassificationModelManager.setSelectedModel(model.id)
                            selectedModel = ClassificationModelManager.getSelectedModel()
                        },
                        onDelete = {
                            scope.launch {
                                ClassificationModelManager.removeCustomModel(model.id)
                                models = ClassificationModelManager.getAvailableModels()
                                if (selectedModel?.id == model.id) {
                                    selectedModel = null
                                }
                            }
                        }
                    )
                }
            }
            
            SettingsCard(
                title = { Text("Add Custom Model") },
                description = { Text("Add a model from URL or local file") },
                startWidget = {
                    Icon(Icons.Default.Add, contentDescription = null)
                },
                onClick = { showAddModelDialog = true }
            )
        }
    }
    
    // Add model dialog
    if (showAddModelDialog) {
        AddModelDialog(
            onDismiss = { showAddModelDialog = false },
            onAdd = { model ->
                scope.launch {
                    if (ClassificationModelManager.addCustomModel(model)) {
                        models = ClassificationModelManager.getAvailableModels()
                        showAddModelDialog = false
                    }
                }
            }
        )
    }
    
    // Download dialog
    showDownloadDialog?.let { model ->
        DownloadModelDialog(
            model = model,
            onDismiss = { showDownloadDialog = null },
            onDownloadComplete = {
                models = ClassificationModelManager.getAvailableModels()
                selectedModel = ClassificationModelManager.getSelectedModel()
                showDownloadDialog = null
            }
        )
    }
}

@Composable
private fun ModelCard(
    model: ClassificationModelManager.ClassificationModel,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    SettingsCard(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(model.name, fontWeight = FontWeight.Medium)
                if (isSelected) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "Selected",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        description = {
            Column {
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall
                )
                if (model.isDownloaded) {
                    Text(
                        "✓ Downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (model.downloadUrl != null) {
                    Text(
                        "Available for download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        startWidget = {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
        },
        endWidget = {
            Row {
                if (onDownload != null && !model.isDownloaded) {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        },
        onClick = onSelect
    )
}

@Composable
private fun AddModelDialog(
    onDismiss: () -> Unit,
    onAdd: (ClassificationModelManager.ClassificationModel) -> Unit
) {
    var modelName by remember { mutableStateOf("") }
    var modelDescription by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf("") }
    var useFilePicker by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedFile = uri
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Model") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("Model Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = modelDescription,
                    onValueChange = { modelDescription = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !useFilePicker,
                        onClick = { useFilePicker = false }
                    )
                    Text("Download from URL", modifier = Modifier.padding(start = 8.dp))
                }
                
                if (!useFilePicker) {
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text("Download URL") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = useFilePicker,
                        onClick = { useFilePicker = true }
                    )
                    Text("Select from file", modifier = Modifier.padding(start = 8.dp))
                }
                
                if (useFilePicker) {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (selectedFile != null) "File selected" else "Choose File")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (modelName.isNotBlank()) {
                        val model = ClassificationModelManager.ClassificationModel(
                            id = modelName.lowercase().replace(" ", "_"),
                            name = modelName,
                            description = modelDescription,
                            modelType = ClassificationModelManager.ModelType.CUSTOM,
                            downloadUrl = if (!useFilePicker && downloadUrl.isNotBlank()) downloadUrl else null,
                            filePath = if (useFilePicker && selectedFile != null) selectedFile.toString() else null,
                            isDownloaded = useFilePicker && selectedFile != null
                        )
                        onAdd(model)
                    }
                },
                enabled = modelName.isNotBlank() && ((!useFilePicker && downloadUrl.isNotBlank()) || (useFilePicker && selectedFile != null))
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
private fun DownloadModelDialog(
    model: ClassificationModelManager.ClassificationModel,
    onDismiss: () -> Unit,
    onDownloadComplete: () -> Unit
) {
    var downloadProgress by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mainScope = rememberCoroutineScope()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Model") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Model: ${model.name}")
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall
                )
                
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = downloadProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Downloading... ${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isDownloading && model.downloadUrl != null) {
                        isDownloading = true
                        error = null
                        scope.launch {
                            try {
                                val filePath = ClassificationModelManager.getModelFilePath(model.id)
                                if (filePath != null && model.downloadUrl != null) {
                                    val outputFile = File(filePath)
                                    outputFile.parentFile?.mkdirs()
                                    
                                    withContext(Dispatchers.IO) {
                                        downloadModelFile(
                                            url = model.downloadUrl,
                                            outputFile = outputFile,
                                            onProgress = { downloaded, total ->
                                                // Update progress on main thread using coroutine scope
                                                mainScope.launch(Dispatchers.Main) {
                                                    downloadProgress = downloaded.toFloat() / total
                                                }
                                            }
                                        )
                                    }
                                    
                                    ClassificationModelManager.updateModelDownloadStatus(
                                        model.id,
                                        filePath,
                                        true
                                    )
                                    onDownloadComplete()
                                } else {
                                    error = "Invalid file path or download URL"
                                    isDownloading = false
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "Download failed"
                                isDownloading = false
                            }
                        }
                    }
                },
                enabled = !isDownloading && model.downloadUrl != null
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDownloading) {
                Text("Cancel")
            }
        }
    )
}

private suspend fun downloadModelFile(
    url: String,
    outputFile: File,
    onProgress: (Long, Long) -> Unit
) {
    OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw Exception("Failed to download file: ${response.code}")
        
        val body = response.body ?: throw Exception("Empty response body")
        val totalBytes = body.contentLength()
        var downloadedBytes = 0L
        
        outputFile.outputStream().use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    onProgress(downloadedBytes, totalBytes)
                }
            }
        }
    }
}

