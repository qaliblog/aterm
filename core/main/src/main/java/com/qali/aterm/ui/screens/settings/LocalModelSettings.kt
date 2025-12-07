package com.qali.aterm.ui.screens.settings

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.settings.Preference
import com.qali.aterm.llm.LocalLlamaModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Get real file path from URI (handles SAF and file:// URIs)
 */
private fun getRealPathFromURI(context: Context, uri: Uri): String? {
    // Check if it's a file:// URI
    if (uri.scheme == "file") {
        return uri.path
    }
    
    // For content:// URIs, try to get the actual path
    var result: String? = null
    
    // Try DocumentsContract for Android 4.4+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, uri)) {
        val docId = DocumentsContract.getDocumentId(uri)
        
        // Handle external storage
        if ("com.android.externalstorage.documents" == uri.authority) {
            val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val type = split[0]
            if ("primary".equals(type, ignoreCase = true)) {
                result = Environment.getExternalStorageDirectory().toString() + "/" + split[1]
            }
        }
        // Handle downloads
        else if ("com.android.providers.downloads.documents" == uri.authority) {
            val id = DocumentsContract.getDocumentId(uri)
            val contentUri = ContentUris.withAppendedId(
                Uri.parse("content://downloads/public_downloads"),
                java.lang.Long.valueOf(id)
            )
            result = getDataColumn(context, contentUri, null, null)
        }
        // Handle media
        else if ("com.android.providers.media.documents" == uri.authority) {
            val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val type = split[0]
            val contentUri = when (type) {
                "image" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> null
            }
            val selection = "_id=?"
            val selectionArgs = arrayOf(split[1])
            result = contentUri?.let { getDataColumn(context, it, selection, selectionArgs) }
        }
    }
    
    return result
}

/**
 * Get data column from content URI
 */
private fun getDataColumn(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
    var cursor: Cursor? = null
    val column = "_data"
    val projection = arrayOf(column)
    
    try {
        cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        if (cursor != null && cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndexOrThrow(column)
            return cursor.getString(columnIndex)
        }
    } catch (e: Exception) {
        // Ignore
    } finally {
        cursor?.close()
    }
    return null
}

/**
 * Copy URI content to app folder
 */
private fun copyUriToAppFolder(context: Context, uri: Uri): String? {
    return try {
        // Ensure LocalLlamaModel is initialized
        if (!LocalLlamaModel.isInitialized()) {
            LocalLlamaModel.init(context)
        }
        
        // Get file name from URI
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        
        if (fileName == null) {
            fileName = "model.gguf"
        }
        
        // Get app models directory
        val modelsDir = LocalLlamaModel.getModelsDirectory() ?: return null
        val targetFile = File(modelsDir, fileName)
        
        // Check if already exists and is up-to-date (we can't check URI modification time easily, so always copy)
        // Copy directly to app folder
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        
        android.util.Log.d("LocalModelSettings", "Model copied to app folder: ${targetFile.absolutePath}")
        targetFile.absolutePath
    } catch (e: Exception) {
        android.util.Log.e("LocalModelSettings", "Failed to copy to app folder: ${e.message}", e)
        null
    }
}

/**
 * Format file size in human-readable format
 */
private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.2f MB", mb)
        kb >= 1.0 -> String.format("%.2f KB", kb)
        else -> "$bytes bytes"
    }
}

/**
 * Settings for local model file selection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedModel by remember { 
        mutableStateOf(Preference.getString("selectedLocalModel", ""))
    }
    var appModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showTestDialog by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isCopying by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    
    // Refresh app models list
    fun refreshModels() {
        appModels = LocalLlamaModel.listAppModels()
    }
    
    // Ensure LocalLlamaModel is initialized and load models on first composition
    LaunchedEffect(Unit) {
        if (!LocalLlamaModel.isInitialized()) {
            try {
                LocalLlamaModel.init(context)
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("LocalModelSettings", "Failed to initialize native library: ${e.message}", e)
                testResult = "Error: Native library not available. The app may need to be reinstalled."
                showTestDialog = true
            }
        }
        refreshModels()
    }
    
    // File picker launcher for adding models
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
                scope.launch {
                    isCopying = true
                    try {
                        // Ensure library is initialized
                        if (!LocalLlamaModel.isInitialized()) {
                            try {
                                LocalLlamaModel.init(context)
                            } catch (e: UnsatisfiedLinkError) {
                                testResult = "Error: Native library not available. The app may need to be reinstalled."
                                showTestDialog = true
                                isCopying = false
                                return@launch
                            }
                        }
                        
                        // Copy to app folder
                        val appFolderPath = copyUriToAppFolder(context, it)
                        if (appFolderPath != null) {
                            val fileName = File(appFolderPath).name
                            // Auto-select the newly added model
                            selectedModel = fileName
                            Preference.setString("selectedLocalModel", fileName)
                            refreshModels()
                            
                            // Try to load the model
                            val loaded = LocalLlamaModel.loadModel(appFolderPath)
                            if (loaded) {
                                testResult = "Model added and loaded successfully!"
                            } else {
                                testResult = "Model added but failed to load. Please try selecting it manually."
                            }
                            showTestDialog = true
                        } else {
                            testResult = "Failed to copy model to app folder. Please try again."
                            showTestDialog = true
                        }
                    } catch (e: Exception) {
                        testResult = "Error: ${e.message}"
                        showTestDialog = true
                    } finally {
                        isCopying = false
                    }
                }
        }
    }
    
    PreferenceGroup(heading = "Local Model Settings") {
        // Add Model button
        SettingsCard(
            title = { Text("Add Model") },
            description = { 
                Text(
                    if (isCopying) {
                        "Copying model to app folder..."
                    } else {
                        "Select a .gguf model file to add to app folder for faster loading"
                    }
                )
            },
            endWidget = {
                if (isCopying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add model",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            },
            onClick = {
                if (!isCopying) {
                    filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                }
            }
        )
        
        // Model Selection
        if (appModels.isNotEmpty()) {
            PreferenceGroup(heading = "Available Models") {
                appModels.forEach { modelPath ->
                    val modelFile = File(modelPath)
                    val fileName = modelFile.name
                    val isSelected = selectedModel == fileName
                    val fileSize = if (modelFile.exists()) modelFile.length() else 0L
                    
                    SettingsCard(
                        title = { 
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        },
                        description = { 
                            Column {
                                Text(
                                    text = formatFileSize(fileSize),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (isSelected && LocalLlamaModel.isLoaded()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "✓ Loaded and ready",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        endWidget = {
                            Row {
                                IconButton(
                                    onClick = { showDeleteDialog = fileName }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        onClick = {
                            if (!isSelected) {
                                selectedModel = fileName
                                Preference.setString("selectedLocalModel", fileName)
                                
                                // Load the selected model
                                scope.launch {
                                    isTesting = true
                                    try {
                                        // Ensure library is initialized
                                        if (!LocalLlamaModel.isInitialized()) {
                                            try {
                                                LocalLlamaModel.init(context)
                                            } catch (e: UnsatisfiedLinkError) {
                                                testResult = "Error: Native library not available. The app may need to be reinstalled."
                                                showTestDialog = true
                                                isTesting = false
                                                return@launch
                                            }
                                        }
                                        
                                        val loaded = LocalLlamaModel.loadModel(modelPath)
                                        if (loaded) {
                                            testResult = "Model selected and loaded successfully!"
                                        } else {
                                            testResult = "Failed to load model. Please check if the file is valid."
                                        }
                                    } catch (e: Exception) {
                                        testResult = "Error loading model: ${e.message}"
                                    } finally {
                                        isTesting = false
                                        showTestDialog = true
                                    }
                                }
                            }
                        }
                    )
                }
            }
        } else {
            SettingsCard(
                title = { Text("No Models") },
                description = { 
                    Text("Add a model using the 'Add Model' button above")
                },
                onClick = { }
            )
        }
        
        // Test Model button (if model is selected)
        if (selectedModel.isNotBlank() && appModels.isNotEmpty()) {
            val selectedModelPath = appModels.find { File(it).name == selectedModel }
            if (selectedModelPath != null) {
                SettingsCard(
                    title = { Text("Test Model") },
                    description = { 
                        Text(
                            when {
                                isTesting -> "Testing model..."
                                LocalLlamaModel.isLoaded() -> "Model is loaded and ready"
                                else -> "Click to load and test the model"
                            }
                        )
                    },
                    endWidget = {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Test",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    },
                    onClick = {
                        scope.launch {
                            isTesting = true
                            testResult = null
                            
                            try {
                                // Ensure library is initialized
                                if (!LocalLlamaModel.isInitialized()) {
                                    try {
                                        LocalLlamaModel.init(context)
                                    } catch (e: UnsatisfiedLinkError) {
                                        testResult = "Error: Native library not available. The app may need to be reinstalled."
                                        showTestDialog = true
                                        isTesting = false
                                        return@launch
                                    }
                                }
                                
                                val loaded = LocalLlamaModel.loadModel(selectedModelPath)
                                if (loaded) {
                                    // Test generation (runs on background thread)
                                    try {
                                        val testResponse = LocalLlamaModel.generate("Hello")
                                        testResult = "Test successful! Response: ${testResponse.take(100)}..."
                                    } catch (e: Exception) {
                                        testResult = "Test failed: ${e.message}"
                                    }
                                } else {
                                    testResult = "Failed to load model"
                                }
                            } catch (e: Exception) {
                                testResult = "Error: ${e.message}"
                            } finally {
                                isTesting = false
                                showTestDialog = true
                            }
                        }
                    }
                )
            }
        }
    }
    
    // Test result dialog
    if (showTestDialog) {
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text("Model Test Result") },
            text = { 
                Text(testResult ?: "No result")
            },
            confirmButton = {
                TextButton(onClick = { showTestDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Model") },
            text = { 
                Text("Are you sure you want to delete '${showDeleteDialog}'? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fileName = showDeleteDialog
                        if (fileName != null) {
                            val deleted = LocalLlamaModel.deleteAppModel(fileName)
                            if (deleted) {
                                // If deleted model was selected, clear selection
                                if (selectedModel == fileName) {
                                    selectedModel = ""
                                    Preference.setString("selectedLocalModel", "")
                                    LocalLlamaModel.unloadModel()
                                }
                                refreshModels()
                            }
                            showDeleteDialog = null
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
