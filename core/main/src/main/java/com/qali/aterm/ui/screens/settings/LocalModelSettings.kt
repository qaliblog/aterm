package com.qali.aterm.ui.screens.settings

import android.net.Uri
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
            // Get file path from URI
            val path = it.path
            if (path != null) {
                localModelPath = path
                Preference.setString("localModelPath", path)
                
                // Try to load the model
                val loaded = LocalLlamaModel.loadModel(path)
                if (!loaded) {
                    testResult = "Failed to load model. Please check if the file is valid."
                    showTestDialog = true
                } else {
                    testResult = "Model loaded successfully!"
                    showTestDialog = true
                }
            }
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
