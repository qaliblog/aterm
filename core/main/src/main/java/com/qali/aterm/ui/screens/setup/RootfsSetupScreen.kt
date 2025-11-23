package com.qali.aterm.ui.screens.setup

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.rk.libcommons.*
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.downloader.downloadRootfs
import com.qali.aterm.ui.screens.downloader.abiMap
import com.qali.aterm.ui.screens.terminal.Rootfs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.os.Environment
import android.content.Context

enum class RootfsType {
    ALPINE,
    UBUNTU,
    CUSTOM,
    FILE_PICKER
}

@Composable
fun RootfsSetupScreen(
    mainActivity: MainActivity,
    navController: NavHostController,
    onSetupComplete: () -> Unit
) {
    var selectedType by remember { mutableStateOf<RootfsType?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var rootfsName by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var showFilePicker by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedDistro by remember { mutableStateOf<DistroType?>(null) }
    var customInitScript by remember { mutableStateOf("") }
    var showInitScriptInfo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    
    // Storage permission launcher
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasStoragePermission = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
                 permissions[Manifest.permission.READ_MEDIA_IMAGES] == true)
        
        if (hasStoragePermission && showFilePicker) {
            // Permission granted, file picker will show files
        } else if (!hasStoragePermission && showFilePicker) {
            errorMessage = "Storage permission is required to pick files"
            showFilePicker = false
        }
    }
    
    // Check storage permission
    fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses granular media permissions
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED ||
            // Fallback to READ_EXTERNAL_STORAGE for broader access
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    // Request storage permission
    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermissionLauncher.launch(arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ))
        } else {
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header with improved styling
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Welcome to aTerm",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Choose a Linux distribution to install",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // Rootfs selection cards
        RootfsOptionCard(
            title = "Alpine Linux",
            description = "Lightweight, security-oriented Linux distribution",
            icon = Icons.Default.Star,
            isSelected = selectedType == RootfsType.ALPINE,
            onClick = { selectedType = RootfsType.ALPINE }
        )

        RootfsOptionCard(
            title = "Ubuntu",
            description = "Popular Debian-based Linux distribution",
            icon = Icons.Default.Star,
            isSelected = selectedType == RootfsType.UBUNTU,
            onClick = { selectedType = RootfsType.UBUNTU }
        )

        RootfsOptionCard(
            title = "Custom Rootfs",
            description = "Enter a custom rootfs URL",
            icon = Icons.Default.Edit,
            isSelected = selectedType == RootfsType.CUSTOM,
            onClick = { selectedType = RootfsType.CUSTOM }
        )

        RootfsOptionCard(
            title = "📁 Pick File",
            description = "Select a rootfs file from your device",
            icon = Icons.Default.FolderOpen,
            isSelected = selectedType == RootfsType.FILE_PICKER,
            onClick = { 
                selectedType = RootfsType.FILE_PICKER
                if (checkStoragePermission()) {
                    showFilePicker = true
                } else {
                    requestStoragePermission()
                    // showFilePicker will be set to true after permission is granted
                    showFilePicker = true
                }
            }
        )

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

        // Distro type selector (shown for all types)
        if (selectedType != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Distribution Type",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DistroType.values().forEach { distro ->
                            FilterChip(
                                selected = selectedDistro == distro,
                                onClick = { 
                                    selectedDistro = distro
                                    // Clear custom init script if selecting a predefined distro
                                    if (distro.hasPredefinedInit) {
                                        customInitScript = ""
                                    }
                                },
                                label = { 
                                    Text(
                                        text = distro.displayName,
                                        maxLines = 1,
                                        softWrap = false
                                    ) 
                                }
                            )
                        }
                    }
                }
            }
        }

        // Custom init script input (required for CUSTOM distro, optional for others)
        if (selectedType != null && selectedDistro == DistroType.CUSTOM) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Custom Init Script (Required):",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showInitScriptInfo = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Init script help",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            OutlinedTextField(
                value = customInitScript,
                onValueChange = { customInitScript = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp),
                label = { Text("Init Script") },
                placeholder = { Text("#!/bin/sh\nset -e\nexport PATH=...") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                minLines = 10,
                maxLines = 20
            )
            if (customInitScript.isBlank()) {
                Text(
                    text = "⚠ Custom distro requires an init script",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }
        } else {
            val currentDistro = selectedDistro
            if (selectedType != null && currentDistro != null && currentDistro != DistroType.CUSTOM && currentDistro.hasPredefinedInit) {
                // Optional custom init script override for predefined distros
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Custom Init Script (Optional - overrides default):",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showInitScriptInfo = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Init script help",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                OutlinedTextField(
                    value = customInitScript,
                    onValueChange = { customInitScript = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 300.dp),
                    label = { Text("Custom Init Script (optional)") },
                    placeholder = { Text("Leave empty to use default ${currentDistro.displayName} init script") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    minLines = 6,
                    maxLines = 15
                )
            }
        }

        // Name input (for all types except predefined)
        if (selectedType != null && selectedType != RootfsType.ALPINE && selectedType != RootfsType.UBUNTU) {
            Spacer(modifier = Modifier.height(8.dp))
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

        Spacer(modifier = Modifier.weight(1f))

        // Install button - with bottom padding to escape navigation menu
        Button(
            onClick = {
                if (selectedType == null) {
                    errorMessage = "Please select a rootfs option"
                    return@Button
                }
                if (selectedDistro == null) {
                    errorMessage = "Please select a distribution type"
                    return@Button
                }
                if (selectedDistro == DistroType.CUSTOM && customInitScript.isBlank()) {
                    errorMessage = "Custom distro requires an init script"
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
                                        // Extract filename from URL or use custom_rootfs.tar.gz
                                        customUrl.substringAfterLast("/").takeIf { it.endsWith(".tar.gz") || it.endsWith(".tar") }
                                            ?: "custom_rootfs.tar.gz"
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
                        
                        // Store distro type
                        if (selectedDistro != null) {
                            Rootfs.setRootfsDistroType(finalRootfsName, selectedDistro!!)
                        }
                        
                        // Store custom init script if provided
                        if (customInitScript.isNotBlank()) {
                            Rootfs.setRootfsInitScript(finalRootfsName, customInitScript)
                        } else if (selectedDistro != null && selectedDistro != DistroType.CUSTOM) {
                            // Clear any existing custom init script for predefined distros
                            Rootfs.clearRootfsInitScript(finalRootfsName)
                        }
                        
                        // Set as default working mode if none is set
                        if (com.rk.settings.Settings.working_Mode == com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE && selectedType != RootfsType.ALPINE) {
                            when (selectedType) {
                                RootfsType.UBUNTU -> com.rk.settings.Settings.working_Mode = com.qali.aterm.ui.screens.settings.WorkingMode.UBUNTU
                                else -> {}
                            }
                        }

                        downloadText = "Installation complete!"
                        // Update rootfs state
                        Rootfs.isDownloaded.value = Rootfs.isFilesDownloaded()
                        // Small delay to show completion message
                        kotlinx.coroutines.delay(500)
                        onSetupComplete()
                    } catch (e: Exception) {
                        errorMessage = "Installation failed: ${e.message}"
                        isDownloading = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp), // Add padding to escape navigation menu
            enabled = !isDownloading && selectedType != null && selectedDistro != null && 
                     (selectedType != RootfsType.FILE_PICKER || selectedFile != null) &&
                     !(selectedDistro == DistroType.CUSTOM && customInitScript.isBlank())
        ) {
            Text(if (isDownloading) "Installing..." else "Install")
        }
        
        // Init script info dialog
        if (showInitScriptInfo) {
            AlertDialog(
                onDismissRequest = { showInitScriptInfo = false },
                title = { Text("Custom Init Script Guide") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "The init script runs when the rootfs starts. It should:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "1. Set up environment variables (PATH, HOME, etc.)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "2. Configure DNS (nameserver 8.8.8.8)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "3. Install essential packages if needed",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "4. Set up shell prompt and configuration",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "5. Start the shell or execute commands",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Example structure:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "#!/bin/sh\nset -e\nexport PATH=...\nexport HOME=/root\necho 'nameserver 8.8.8.8' > /etc/resolv.conf\n# Install packages\n# Configure shell\n/bin/bash",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInitScriptInfo = false }) {
                        Text("Got it")
                    }
                }
            )
        }
        
        // File picker dialog
        if (showFilePicker && checkStoragePermission()) {
            FilePickerDialog(
                context = context,
                initialPath = getInitialStoragePath(context),
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
        } else if (showFilePicker && !checkStoragePermission()) {
            // Show permission request dialog
            AlertDialog(
                onDismissRequest = { showFilePicker = false },
                title = { Text("Storage Permission Required") },
                text = {
                    Text("Storage permission is required to pick files from your device. Please grant the permission.")
                },
                confirmButton = {
                    Button(onClick = { requestStoragePermission() }) {
                        Text("Grant Permission")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFilePicker = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

fun getInitialStoragePath(context: Context): String {
    // Prioritize /sdcard and /storage/emulated/0 (actual SD card mounting points)
    val sdcardPaths = listOf(
        "/sdcard",
        "/storage/emulated/0",
        "/storage/sdcard0",
        "/mnt/sdcard"
    )
    
    for (path in sdcardPaths) {
        val dir = File(path)
        if (dir.exists() && dir.isDirectory && dir.canRead()) {
            return path
        }
    }
    
    // Try to get external storage directory
    val externalStorage = Environment.getExternalStorageDirectory()
    if (externalStorage != null && externalStorage.exists() && externalStorage.canRead()) {
        return externalStorage.absolutePath
    }
    
    // Try to get external files directory
    val externalFilesDir = context.getExternalFilesDir(null)
    if (externalFilesDir != null && externalFilesDir.exists()) {
        val parent = externalFilesDir.parentFile
        if (parent != null && parent.exists() && parent.canRead()) {
            return parent.absolutePath
        }
    }
    
    // Fallback to /storage if it exists
    val storageDir = File("/storage")
    if (storageDir.exists() && storageDir.isDirectory && storageDir.canRead()) {
        return "/storage"
    }
    
    // Last resort fallback
    return "/sdcard"
}

@Composable
fun FilePickerDialog(
    context: Context,
    initialPath: String,
    onDismiss: () -> Unit,
    onFileSelected: (File) -> Unit
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(currentPath) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val dir = File(currentPath)
                if (dir.exists() && dir.isDirectory && dir.canRead()) {
                    files = dir.listFiles()?.filter { file ->
                        // Show directories and .tar/.tar.gz files
                        file.isDirectory || file.name.endsWith(".tar.gz", ignoreCase = true) || 
                        file.name.endsWith(".tar", ignoreCase = true)
                    }?.toList() ?: emptyList()
                } else {
                    files = emptyList()
                    errorMessage = "Cannot access directory: $currentPath"
                }
            } catch (e: Exception) {
                files = emptyList()
                errorMessage = "Error reading directory: ${e.message}"
            }
        }
        isLoading = false
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select Rootfs File")
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                // Storage shortcuts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val storagePaths = listOf(
                        "📁 SD Card" to "/sdcard",
                        "📁 Storage" to "/storage/emulated/0",
                        "📁 Downloads" to File("/sdcard", "Download").absolutePath,
                        "📁 Root" to "/storage"
                    )
                    
                    storagePaths.forEach { (label, path) ->
                        val dir = File(path)
                        if (dir.exists() && dir.isDirectory && dir.canRead()) {
                            FilterChip(
                                selected = currentPath == path,
                                onClick = { currentPath = path },
                                label = { Text(label, maxLines = 1) }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                
                // Path bar with navigation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            File(currentPath).parentFile?.let {
                                if (it.exists() && it.canRead()) {
                                    currentPath = it.absolutePath
                                }
                            }
                        },
                        enabled = File(currentPath).parentFile != null
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Go up")
                    }
                    Text(
                        text = currentPath,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                
                // File list
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (errorMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                } else if (files.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No files found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    ) {
                        items(files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) { file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                onClick = {
                                    if (file.isDirectory) {
                                        currentPath = file.absolutePath
                                    } else if (file.name.endsWith(".tar.gz", ignoreCase = true) || 
                                              file.name.endsWith(".tar", ignoreCase = true)) {
                                        onFileSelected(file)
                                    }
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (file.isDirectory) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Archive,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = if (file.isDirectory) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        if (!file.isDirectory) {
                                            Text(
                                                text = "${(file.length() / 1024 / 1024)} MB",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RootfsOptionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(32.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

