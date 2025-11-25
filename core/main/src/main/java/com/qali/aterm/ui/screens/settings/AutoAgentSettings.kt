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
import java.io.InputStream
import java.io.FileOutputStream
import android.net.Uri
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoAgentSettings() {
    var models by remember { mutableStateOf(ClassificationModelManager.getAvailableModels()) }
    var selectedModel by remember { mutableStateOf(ClassificationModelManager.getSelectedModel()) }
    var showAddModelDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf<ClassificationModelManager.ClassificationModel?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        models = ClassificationModelManager.getAvailableModels()
        selectedModel = ClassificationModelManager.getSelectedModel()
        // Set AutoAgent model name from selected model if not already set
        // This also ensures the database is created
        selectedModel?.let { model ->
            val currentAutoAgentModel = ClassificationModelManager.getAutoAgentModelName()
            if (currentAutoAgentModel == "aterm-offline" || currentAutoAgentModel.isEmpty()) {
                ClassificationModelManager.setAutoAgentModelName(model.name)
                // Ensure database is created for this model name
                scope.launch(Dispatchers.IO) {
                    val modelName = ClassificationModelManager.getAutoAgentModelName()
                    com.qali.aterm.autogent.LearningDatabase.getInstance(modelName)
                    com.qali.aterm.autogent.AutoAgentProvider.initialize(modelName)
                    com.qali.aterm.autogent.AutoAgentLearningService.initialize()
                }
            }
        }
    }
    
    PreferenceGroup(heading = "AutoAgent Settings") {
        // Classification Model Selection
        PreferenceGroup(heading = "Classification Model (Required)") {
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
                            val isReady = ClassificationModelManager.isModelReady()
                            if (isReady) {
                                Text(
                                    "✓ Model downloaded and ready",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (model.isDownloaded && model.filePath != null) {
                                Text(
                                    "⚠ Model downloaded but not ready (file may be invalid)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
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
            
            // AutoAgent Database Model Name display
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCard(
                title = { Text("Database Model Name") },
                description = {
                    Column {
                        val dbModelName = ClassificationModelManager.getAutoAgentModelName()
                        Text(
                            dbModelName,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "This is the model name used for the AutoAgent learning database. The database is automatically created and initialized when a classification model is selected.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                onClick = { /* Display only, no action */ }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Built-in models - Group by type
            PreferenceGroup(heading = "Built-in Models") {
                // TFLite models (Mediapipe)
                val tfliteModels = models.filter { it.isBuiltIn && it.modelType == ClassificationModelManager.ModelType.MEDIAPIPE_BERT }
                if (tfliteModels.isNotEmpty()) {
                    Text(
                        "TensorFlow Lite Models",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    tfliteModels.forEach { model ->
                        ModelCard(
                            model = model,
                            isSelected = selectedModel?.id == model.id,
                            onSelect = {
                                ClassificationModelManager.setSelectedModel(model.id)
                                selectedModel = ClassificationModelManager.getSelectedModel()
                                // Set AutoAgent model name to match selected classification model
                                // This also ensures the database is created for this model name
                                ClassificationModelManager.setAutoAgentModelName(model.name)
                                // Initialize AutoAgent services with the new model name
                                scope.launch(Dispatchers.IO) {
                                    // Ensure database is created
                                    val modelName = ClassificationModelManager.getAutoAgentModelName()
                                    com.qali.aterm.autogent.LearningDatabase.getInstance(modelName)
                                    com.qali.aterm.autogent.AutoAgentProvider.initialize(modelName)
                                    com.qali.aterm.autogent.AutoAgentLearningService.initialize()
                                    
                                    // Mark model as ready if file exists
                                    val filePath = ClassificationModelManager.getModelFilePath(model.id)
                                    if (filePath != null) {
                                        val file = File(filePath)
                                        if (file.exists() && file.length() > 0) {
                                            ClassificationModelManager.markModelReady(model.id, true)
                                        }
                                    }
                                }
                            },
                            onDownload = {
                                showDownloadDialog = model
                            }
                        )
                    }
                }
                
                // ONNX models (CodeBERT)
                val onnxModels = models.filter { it.isBuiltIn && it.modelType == ClassificationModelManager.ModelType.CODEBERT_ONNX }
                if (onnxModels.isNotEmpty()) {
                    Text(
                        "ONNX Models (CodeBERT)",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    onnxModels.forEach { model ->
                        ModelCard(
                            model = model,
                            isSelected = selectedModel?.id == model.id,
                            onSelect = {
                                ClassificationModelManager.setSelectedModel(model.id)
                                selectedModel = ClassificationModelManager.getSelectedModel()
                                // Set AutoAgent model name to match selected classification model
                                // This also ensures the database is created for this model name
                                ClassificationModelManager.setAutoAgentModelName(model.name)
                                // Initialize AutoAgent services with the new model name
                                scope.launch(Dispatchers.IO) {
                                    // Ensure database is created
                                    val modelName = ClassificationModelManager.getAutoAgentModelName()
                                    com.qali.aterm.autogent.LearningDatabase.getInstance(modelName)
                                    com.qali.aterm.autogent.AutoAgentProvider.initialize(modelName)
                                    com.qali.aterm.autogent.AutoAgentLearningService.initialize()
                                    
                                    // Mark model as ready if file exists
                                    val filePath = ClassificationModelManager.getModelFilePath(model.id)
                                    if (filePath != null) {
                                        val file = File(filePath)
                                        if (file.exists() && file.length() > 0) {
                                            ClassificationModelManager.markModelReady(model.id, true)
                                        }
                                    }
                                }
                            },
                            onDownload = if (model.downloadUrl != null) {
                                { showDownloadDialog = model }
                            } else null
                        )
                    }
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
                                // Set AutoAgent model name to match selected classification model
                                // This also ensures the database is created for this model name
                                ClassificationModelManager.setAutoAgentModelName(model.name)
                                // Initialize AutoAgent services with the new model name
                                scope.launch(Dispatchers.IO) {
                                    // Ensure database is created
                                    val modelName = ClassificationModelManager.getAutoAgentModelName()
                                    com.qali.aterm.autogent.LearningDatabase.getInstance(modelName)
                                    com.qali.aterm.autogent.AutoAgentProvider.initialize(modelName)
                                    com.qali.aterm.autogent.AutoAgentLearningService.initialize()
                                    
                                    // Mark model as ready if file exists
                                    val filePath = ClassificationModelManager.getModelFilePath(model.id)
                                    if (filePath != null) {
                                        val file = File(filePath)
                                        if (file.exists() && file.length() > 0) {
                                            ClassificationModelManager.markModelReady(model.id, true)
                                        }
                                    }
                                }
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
        
        // Reset AutoAgent Database section
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        
        PreferenceGroup(heading = "Database Management") {
            SettingsCard(
                title = { Text("Reset AutoAgent Database") },
                description = {
                    Column {
                        Text(
                            "Reset the AutoAgent learning database. This will delete all learned data but restore framework knowledge.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                startWidget = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = { showResetConfirmation = true }
            )
        }
    }
    
    // Add model dialog
    if (showAddModelDialog) {
        AddModelDialog(
            onDismiss = { showAddModelDialog = false },
            onAdd = { model ->
                scope.launch(Dispatchers.IO) {
                    if (ClassificationModelManager.addCustomModel(model)) {
                        // Set AutoAgent model name to match the new model
                        // This also ensures the database is created for this model name
                        ClassificationModelManager.setAutoAgentModelName(model.name)
                        
                        // Ensure database is created for this model name
                        val modelName = ClassificationModelManager.getAutoAgentModelName()
                        com.qali.aterm.autogent.LearningDatabase.getInstance(modelName)
                        com.qali.aterm.autogent.AutoAgentProvider.initialize(modelName)
                        com.qali.aterm.autogent.AutoAgentLearningService.initialize()
                        
                        // If model has a file path, verify it exists and mark as ready
                        if (model.filePath != null) {
                            val file = File(model.filePath)
                            if (file.exists() && file.length() > 0) {
                                ClassificationModelManager.markModelReady(model.id, true)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            models = ClassificationModelManager.getAvailableModels()
                            showAddModelDialog = false
                        }
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
    
    // Reset Database Confirmation Dialog
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset AutoAgent Database") },
            text = {
                Column {
                    Text("Are you sure you want to reset the AutoAgent learning database?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This will permanently delete all learned data including:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("• Code snippets", style = MaterialTheme.typography.bodySmall)
                    Text("• API usage patterns", style = MaterialTheme.typography.bodySmall)
                    Text("• Fix patches", style = MaterialTheme.typography.bodySmall)
                    Text("• Metadata transformations", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Note: Framework knowledge (HTML, CSS, JavaScript, Node.js, Python, Java, Kotlin, MVC) will be automatically restored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This action cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val modelName = ClassificationModelManager.getAutoAgentModelName()
                                val database = com.qali.aterm.autogent.LearningDatabase.getInstance(modelName)
                                val success = database.resetDatabase()
                                
                                // Re-initialize services with the reset database
                                com.qali.aterm.autogent.AutoAgentProvider.initialize(modelName)
                                com.qali.aterm.autogent.AutoAgentLearningService.initialize()
                                
                                withContext(Dispatchers.Main) {
                                    showResetConfirmation = false
                                }
                                
                                android.util.Log.i("AutoAgent", "Database reset ${if (success) "successful" else "failed"}")
                            } catch (e: Exception) {
                                android.util.Log.e("AutoAgent", "Error resetting database", e)
                                withContext(Dispatchers.Main) {
                                    showResetConfirmation = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Reset Database")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Reuse ModelCard, AddModelDialog, and DownloadModelDialog from ClassificationModelSettings
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
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    } else if (model.modelType == ClassificationModelManager.ModelType.CODEBERT_ONNX && model.id == "codebert_onnx") {
                        Text(
                            "⚠ No download URL - Add custom ONNX model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // Show model type badge
                    Surface(
                        color = when (model.modelType) {
                            ClassificationModelManager.ModelType.CODEBERT_ONNX -> MaterialTheme.colorScheme.tertiaryContainer
                            ClassificationModelManager.ModelType.MEDIAPIPE_BERT -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            when (model.modelType) {
                                ClassificationModelManager.ModelType.CODEBERT_ONNX -> "ONNX"
                                ClassificationModelManager.ModelType.MEDIAPIPE_BERT -> "TFLite"
                                else -> "Custom"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
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
                if (onDownload != null && !model.isDownloaded && model.downloadUrl != null) {
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
    val context = LocalContext.current
    var modelName by remember { mutableStateOf("") }
    var modelDescription by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf("") }
    var useFilePicker by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    
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
                    singleLine = true,
                    supportingText = {
                        Text(
                            "For ONNX models, use .onnx file URLs. For TFLite models, use .tflite file URLs.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
                        val modelId = modelName.lowercase().replace(" ", "_")
                        
                        // If model was added via file picker, copy file to model directory synchronously
                        var finalFilePath: String? = null
                        if (useFilePicker && selectedFile != null) {
                            try {
                                val modelDir = File(com.rk.libcommons.localDir(), "aterm/model").apply { mkdirs() }
                                val extension = when {
                                    selectedFile.toString().endsWith(".onnx", ignoreCase = true) -> ".onnx"
                                    selectedFile.toString().endsWith(".tflite", ignoreCase = true) -> ".tflite"
                                    selectedFile.toString().endsWith(".json", ignoreCase = true) -> ".json"
                                    selectedFile.toString().endsWith(".txt", ignoreCase = true) -> ".txt"
                                    else -> ".tflite"
                                }
                                val targetFile = File(modelDir, "${modelId}$extension")
                                
                                // Copy file from URI to model directory
                                context.contentResolver.openInputStream(selectedFile!!)?.use { input ->
                                    FileOutputStream(targetFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                
                                finalFilePath = targetFile.absolutePath
                                
                                // Mark model as ready if file was copied successfully
                                if (targetFile.exists() && targetFile.length() > 0) {
                                    scope.launch(Dispatchers.IO) {
                                        ClassificationModelManager.markModelReady(modelId, true)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("AutoAgentSettings", "Error copying model file", e)
                            }
                        }
                        
                        val model = ClassificationModelManager.ClassificationModel(
                            id = modelId,
                            name = modelName,
                            description = modelDescription,
                            modelType = ClassificationModelManager.ModelType.CUSTOM,
                            downloadUrl = if (!useFilePicker && downloadUrl.isNotBlank()) downloadUrl else null,
                            filePath = finalFilePath,
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
                    if (!isDownloading && model.downloadUrl != null && model.downloadUrl.isNotBlank()) {
                        isDownloading = true
                        error = null
                        scope.launch {
                            try {
                                if (model.downloadUrl == null) {
                                    error = "No download URL available"
                                    isDownloading = false
                                    return@launch
                                }
                                
                                // Get or create file path
                                val existingPath = ClassificationModelManager.getModelFilePath(model.id)
                                val filePath = existingPath ?: run {
                                    // Create default path if it doesn't exist yet
                                    val modelDir = File(com.rk.libcommons.localDir(), "aterm/model").apply { mkdirs() }
                                    val extension = when (model.modelType) {
                                        ClassificationModelManager.ModelType.CODEBERT_ONNX -> {
                                            when {
                                                model.id.contains("vocab") -> ".json"
                                                model.id.contains("merges") -> ".txt"
                                                else -> ".onnx"
                                            }
                                        }
                                        else -> ".tflite"
                                    }
                                    File(modelDir, "${model.id}$extension").absolutePath
                                }
                                
                                val outputFile = File(filePath)
                                outputFile.parentFile?.mkdirs()
                                
                                withContext(Dispatchers.IO) {
                                    downloadModelFile(
                                        url = model.downloadUrl,
                                        outputFile = outputFile,
                                        onProgress = { downloaded, total ->
                                            // Update progress on main thread using coroutine scope
                                            scope.launch(Dispatchers.Main) {
                                                downloadProgress = downloaded.toFloat() / total
                                            }
                                        }
                                    )
                                }
                                
                                // For built-in models, use markBuiltInDownloaded to persist
                                if (model.isBuiltIn) {
                                    ClassificationModelManager.markBuiltInDownloaded(model.id, filePath)
                                } else {
                                    ClassificationModelManager.updateModelDownloadStatus(
                                        model.id,
                                        filePath,
                                        true
                                    )
                                }
                                
                                // Mark model as ready (successfully downloaded)
                                // Note: Actual model loading/validation would happen here if needed
                                // For now, we mark it as ready if file exists and is not empty
                                val file = File(filePath)
                                if (file.exists() && file.length() > 0) {
                                    ClassificationModelManager.markModelReady(model.id, true)
                                }
                                
                                onDownloadComplete()
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
