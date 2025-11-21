package com.qali.aterm.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.libcommons.*
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.downloader.downloadRootfs
import com.qali.aterm.ui.screens.downloader.abiMap
import com.qali.aterm.ui.screens.setup.RootfsType
import com.qali.aterm.ui.screens.setup.FilePickerDialog
import com.qali.aterm.ui.screens.terminal.Rootfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RootfsSettings(
    mainActivity: MainActivity,
    navController: NavController
) {
    val installedRootfs = remember { mutableStateOf(Rootfs.getInstalledRootfsList()) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<RootfsType?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var rootfsName by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var showFilePicker by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        installedRootfs.value = Rootfs.getInstalledRootfsList()
    }

    PreferenceGroup(heading = "Rootfs Management") {
        // Show installed rootfs
        if (installedRootfs.value.isNotEmpty()) {
            Text(
                text = "Installed Rootfs:",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            installedRootfs.value.forEach { rootfsName ->
                val displayName = Rootfs.getRootfsDisplayName(rootfsName)
                SettingsCard(
                    title = { Text(displayName) },
                    description = { 
                        Text(
                            if (rootfsName == Rootfs.getRootfsFileName(com.rk.settings.Settings.working_Mode)) {
                                "Currently active - $rootfsName"
                            } else {
                                "Installed - $rootfsName"
                            }
                        )
                    },
                    startWidget = {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    },
                    onClick = {}
                )
            }
        }

        // Install new rootfs button
        SettingsCard(
            title = { Text("Install Additional Rootfs") },
            description = { Text("Install Alpine, Ubuntu, or a custom rootfs") },
            startWidget = {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp)
                )
            },
            onClick = { showInstallDialog = true }
        )
    }

    // Install dialog
    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { 
                if (!isDownloading) {
                    showInstallDialog = false
                    selectedType = null
                    customUrl = ""
                    errorMessage = null
                }
            },
            title = { Text("Install Rootfs") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Rootfs type selection
                    Text(
                        text = "Select rootfs type:",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == RootfsType.ALPINE,
                            onClick = { 
                                if (!Rootfs.isRootfsInstalled("alpine.tar.gz")) {
                                    selectedType = RootfsType.ALPINE
                                }
                            },
                            label = { Text("Alpine") },
                            enabled = !Rootfs.isRootfsInstalled("alpine.tar.gz")
                        )
                        FilterChip(
                            selected = selectedType == RootfsType.UBUNTU,
                            onClick = { 
                                if (!Rootfs.isRootfsInstalled("ubuntu.tar.gz")) {
                                    selectedType = RootfsType.UBUNTU
                                }
                            },
                            label = { Text("Ubuntu") },
                            enabled = !Rootfs.isRootfsInstalled("ubuntu.tar.gz")
                        )
                        FilterChip(
                            selected = selectedType == RootfsType.CUSTOM,
                            onClick = { selectedType = RootfsType.CUSTOM },
                            label = { Text("Custom") }
                        )
                        FilterChip(
                            selected = selectedType == RootfsType.FILE_PICKER,
                            onClick = { 
                                selectedType = RootfsType.FILE_PICKER
                                showFilePicker = true
                            },
                            label = { Text("Pick File") }
                        )
                    }

                    // Custom URL input
                    if (selectedType == RootfsType.CUSTOM) {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Rootfs URL") },
                            placeholder = { Text("https://example.com/rootfs.tar.gz") },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                            singleLine = true
                        )
                    }

                    // File picker result
                    if (selectedType == RootfsType.FILE_PICKER && selectedFile != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Selected: ${selectedFile!!.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = selectedFile!!.absolutePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Name input (for all types except predefined)
                    if (selectedType != null && selectedType != RootfsType.ALPINE && selectedType != RootfsType.UBUNTU) {
                        OutlinedTextField(
                            value = rootfsName,
                            onValueChange = { rootfsName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Rootfs Name (optional)") },
                            placeholder = { Text("e.g., My Custom Rootfs") },
                            leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                            singleLine = true
                        )
                    }

                    // Download progress
                    if (isDownloading) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = downloadText,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Error message
                    errorMessage?.let { error ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            },
                    confirmButton = {
                Button(
                    onClick = {
                        if (selectedType == null) {
                            errorMessage = "Please select a rootfs type"
                            return@Button
                        }
                        if (selectedType == RootfsType.CUSTOM && customUrl.isBlank()) {
                            errorMessage = "Please enter a custom URL"
                            return@Button
                        }
                        if (selectedType == RootfsType.FILE_PICKER && selectedFile == null) {
                            errorMessage = "Please select a rootfs file"
                            return@Button
                        }

                        errorMessage = null
                        isDownloading = true
                        downloadProgress = 0f

                        scope.launch {
                            try {
                                val abi = Build.SUPPORTED_ABIS.firstOrNull {
                                    it in abiMap
                                } ?: throw RuntimeException("Unsupported CPU")

                                val finalRootfsName: String
                                val displayName: String?
                                
                                when (selectedType) {
                                    RootfsType.FILE_PICKER -> {
                                        // Copy file from selected location
                                        val sourceFile = selectedFile!!
                                        finalRootfsName = sourceFile.name
                                        displayName = if (rootfsName.isNotBlank()) rootfsName else null
                                        
                                        downloadText = "Copying ${sourceFile.name}..."
                                        
                                        // Download proot and libtalloc if needed
                                        val abiUrls = abiMap[abi]!!
                                        val filesToDownload = mutableListOf<Pair<String, String>>()
                                        
                                        if (!Rootfs.reTerminal.child("proot").exists()) {
                                            filesToDownload.add("proot" to abiUrls.proot)
                                        }
                                        if (!Rootfs.reTerminal.child("libtalloc.so.2").exists()) {
                                            filesToDownload.add("libtalloc.so.2" to abiUrls.talloc)
                                        }
                                        
                                        var completed = 0
                                        val total = filesToDownload.size + 1
                                        
                                        // Download dependencies first
                                        filesToDownload.forEach { (name, url) ->
                                            downloadText = "Downloading $name..."
                                            val outputFile = Rootfs.reTerminal.child(name)
                                            outputFile.parentFile?.mkdirs()
                                            
                                            withContext(Dispatchers.IO) {
                                                downloadRootfs(
                                                    url = url,
                                                    outputFile = outputFile,
                                                    onProgress = { downloaded, totalBytes ->
                                                        val fileProgress = downloaded.toFloat() / totalBytes
                                                        downloadProgress = ((completed + fileProgress) / total).coerceIn(0f, 1f)
                                                    }
                                                )
                                            }
                                            completed++
                                            downloadProgress = (completed.toFloat() / total).coerceIn(0f, 1f)
                                        }
                                        
                                        // Copy the selected file
                                        downloadText = "Copying ${sourceFile.name}..."
                                        val outputFile = Rootfs.reTerminal.child(finalRootfsName)
                                        outputFile.parentFile?.mkdirs()
                                        
                                        withContext(Dispatchers.IO) {
                                            sourceFile.copyTo(outputFile, overwrite = true)
                                        }
                                        completed++
                                        downloadProgress = (completed.toFloat() / total).coerceIn(0f, 1f)
                                    }
                                    else -> {
                                        finalRootfsName = when (selectedType) {
                                            RootfsType.ALPINE -> "alpine.tar.gz"
                                            RootfsType.UBUNTU -> "ubuntu.tar.gz"
                                            RootfsType.CUSTOM -> {
                                                customUrl.substringAfterLast("/").takeIf { 
                                                    it.endsWith(".tar.gz") || it.endsWith(".tar") 
                                                } ?: "custom_rootfs.tar.gz"
                                            }
                                            else -> throw RuntimeException("No rootfs selected")
                                        }
                                        displayName = if (rootfsName.isNotBlank()) rootfsName else null

                                        val currentType = selectedType
                                        val rootfsUrl = when (currentType) {
                                            RootfsType.ALPINE -> abiMap[abi]!!.alpine
                                            RootfsType.UBUNTU -> abiMap[abi]!!.ubuntu
                                            RootfsType.CUSTOM -> customUrl
                                            else -> throw RuntimeException("No rootfs selected")
                                        }

                                        downloadText = "Downloading ${currentType?.name?.lowercase() ?: "rootfs"} rootfs..."
                                        
                                        // Download proot and libtalloc if needed
                                        val abiUrls = abiMap[abi]!!
                                        val filesToDownload = mutableListOf<Pair<String, String>>()
                                        
                                        if (!Rootfs.reTerminal.child("proot").exists()) {
                                            filesToDownload.add("proot" to abiUrls.proot)
                                        }
                                        if (!Rootfs.reTerminal.child("libtalloc.so.2").exists()) {
                                            filesToDownload.add("libtalloc.so.2" to abiUrls.talloc)
                                        }
                                        filesToDownload.add(finalRootfsName to rootfsUrl)

                                        var completed = 0
                                        val total = filesToDownload.size

                                        filesToDownload.forEach { (name, url) ->
                                            downloadText = "Downloading $name..."
                                            val outputFile = Rootfs.reTerminal.child(name)
                                            outputFile.parentFile?.mkdirs()
                                            
                                            withContext(Dispatchers.IO) {
                                                downloadRootfs(
                                                    url = url,
                                                    outputFile = outputFile,
                                                    onProgress = { downloaded, totalBytes ->
                                                        val fileProgress = downloaded.toFloat() / totalBytes
                                                        downloadProgress = ((completed + fileProgress) / total).coerceIn(0f, 1f)
                                                    }
                                                )
                                            }
                                            completed++
                                            downloadProgress = (completed.toFloat() / total).coerceIn(0f, 1f)
                                        }
                                    }
                                }

                                // Mark rootfs as installed with display name
                                Rootfs.markRootfsInstalled(finalRootfsName, displayName)
                                installedRootfs.value = Rootfs.getInstalledRootfsList()
                                Rootfs.isDownloaded.value = Rootfs.isFilesDownloaded()

                                downloadText = "Installation complete!"
                                isDownloading = false
                                showInstallDialog = false
                                toast("Rootfs installed successfully!")
                            } catch (e: Exception) {
                                errorMessage = "Installation failed: ${e.message}"
                                isDownloading = false
                            }
                        }
                    },
                    enabled = !isDownloading && selectedType != null && (selectedType != RootfsType.FILE_PICKER || selectedFile != null)
                ) {
                    Text(if (isDownloading) "Installing..." else "Install")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        if (!isDownloading) {
                            showInstallDialog = false
                        }
                    },
                    enabled = !isDownloading
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // File picker dialog
    if (showFilePicker) {
        FilePickerDialog(
            initialPath = android.os.Environment.getExternalStorageDirectory().absolutePath,
            onDismiss = { showFilePicker = false },
            onFileSelected = { file ->
                if (file.name.endsWith(".tar.gz") || file.name.endsWith(".tar")) {
                    selectedFile = file
                    if (rootfsName.isBlank()) {
                        rootfsName = file.name.substringBeforeLast(".")
                    }
                    showFilePicker = false
                } else {
                    errorMessage = "Please select a .tar.gz or .tar file"
                }
            }
        )
    }
}

