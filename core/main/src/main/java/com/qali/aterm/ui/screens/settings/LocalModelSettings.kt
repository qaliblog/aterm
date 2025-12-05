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
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.settings.Preference
import com.qali.aterm.llm.LocalLlamaModel
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Settings for local model file selection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSettings() {
    val context = LocalContext.current
    var localModelPath by remember { 
        mutableStateOf(Preference.getString("localModelPath", ""))
    }
    var showTestDialog by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    
    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // Get actual file path from URI (handles SAF URIs)
                val path = getRealPathFromURI(context, it)
                if (path != null) {
                    localModelPath = path
                    Preference.setString("localModelPath", path)
                    
                    // Try to load the model
                    val loaded = LocalLlamaModel.loadModel(path)
                    if (!loaded) {
                        testResult = "Failed to load model. Please check if the file is valid and accessible."
                        showTestDialog = true
                    } else {
                        testResult = "Model loaded successfully!"
                        showTestDialog = true
                    }
                } else {
                    testResult = "Failed to get file path from URI. Please try selecting the file again."
                    showTestDialog = true
                }
            } catch (e: Exception) {
                testResult = "Error: ${e.message}"
                showTestDialog = true
            }
        }
    }
    
    /**
     * Get real file path from URI (handles SAF and file:// URIs)
     */
    fun getRealPathFromURI(context: Context, uri: Uri): String? {
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
        
        // If still no result, try to copy to cache directory
        if (result == null) {
            result = copyUriToCache(context, uri)
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
     * Copy URI content to cache directory and return path
     */
    private fun copyUriToCache(context: Context, uri: Uri): String? {
        return try {
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
            
            // Copy to cache directory
            val cacheDir = File(context.cacheDir, "models")
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            cacheFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
    
    PreferenceGroup(heading = "Local Model Settings") {
        SettingsCard(
            title = { Text("Local Model File") },
            description = { 
                Text(
                    if (localModelPath.isNotBlank()) {
                        localModelPath
                    } else {
                        "No model file selected"
                    }
                )
            },
            endWidget = {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Select file",
                    modifier = Modifier.padding(end = 8.dp)
                )
            },
            onClick = {
                filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            }
        )
        
        if (localModelPath.isNotBlank()) {
            SettingsCard(
                title = { Text("Test Model") },
                description = { 
                    Text(
                        if (LocalLlamaModel.isLoaded()) {
                            "Model is loaded and ready"
                        } else {
                            "Click to load and test the model"
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
                    val loaded = LocalLlamaModel.loadModel(localModelPath)
                    if (loaded) {
                        // Test generation
                        try {
                            val testResponse = LocalLlamaModel.generate("Hello")
                            testResult = "Test successful! Response: $testResponse"
                        } catch (e: Exception) {
                            testResult = "Test failed: ${e.message}"
                        }
                    } else {
                        testResult = "Failed to load model"
                    }
                    showTestDialog = true
                }
            )
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
}
