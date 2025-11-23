package com.qali.aterm.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.*
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
import androidx.navigation.NavController
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.libcommons.*
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.downloader.downloadRootfs
import com.qali.aterm.ui.screens.downloader.abiMap
import com.qali.aterm.ui.screens.setup.RootfsType
import com.qali.aterm.ui.screens.setup.FilePickerDialog
import com.qali.aterm.ui.screens.setup.getInitialStoragePath
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
    var selectedDistro by remember { mutableStateOf<com.qali.aterm.ui.screens.setup.DistroType?>(null) }
    var customInitScript by remember { mutableStateOf("") }
    var showInitScriptInfo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
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
                                if (checkStoragePermission()) {
                                    showFilePicker = true
                                } else {
                                    requestStoragePermission()
                                    // showFilePicker will be set to true after permission is granted
                                    showFilePicker = true
                                }
                            },
                            label = { Text("📁 Pick File") }
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

                    // Distro type selector
                    if (selectedType != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Category,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Distribution Type",
                                        style = MaterialTheme.typography.labelLarge,
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
                                    com.qali.aterm.ui.screens.setup.DistroType.values().forEach { distro ->
                                        FilterChip(
                                            selected = selectedDistro == distro,
                                            onClick = { 
                                                selectedDistro = distro
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

                    // Custom init script input
                    if (selectedType != null && selectedDistro == com.qali.aterm.ui.screens.setup.DistroType.CUSTOM) {
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
                        if (selectedType != null && currentDistro != null && currentDistro != com.qali.aterm.ui.screens.setup.DistroType.CUSTOM && currentDistro.hasPredefinedInit) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Custom Init Script (Optional):",
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
                }
            },
                    confirmButton = {
                Button(
                    onClick = {
                        if (selectedType == null) {
                            errorMessage = "Please select a rootfs type"
                            return@Button
                        }
                        if (selectedDistro == null) {
                            errorMessage = "Please select a distribution type"
                            return@Button
                        }
                        if (selectedDistro == com.qali.aterm.ui.screens.setup.DistroType.CUSTOM && customInitScript.isBlank()) {
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
                                
                                // Store distro type
                                if (selectedDistro != null) {
                                    Rootfs.setRootfsDistroType(finalRootfsName, selectedDistro!!)
                                }
                                
                                // Store custom init script if provided
                                if (customInitScript.isNotBlank()) {
                                    Rootfs.setRootfsInitScript(finalRootfsName, customInitScript)
                                } else if (selectedDistro != null && selectedDistro != com.qali.aterm.ui.screens.setup.DistroType.CUSTOM) {
                                    Rootfs.clearRootfsInitScript(finalRootfsName)
                                }
                                
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
                    enabled = !isDownloading && selectedType != null && selectedDistro != null && 
                             (selectedType != RootfsType.FILE_PICKER || selectedFile != null) &&
                             !(selectedDistro == com.qali.aterm.ui.screens.setup.DistroType.CUSTOM && customInitScript.isBlank())
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
    
    // File picker dialog - shows on top of install dialog
    if (showFilePicker && checkStoragePermission()) {
        FilePickerDialog(
            context = context,
            initialPath = getInitialStoragePath(context),
            onDismiss = { 
                showFilePicker = false
            },
            onFileSelected = { file ->
                if (file.name.endsWith(".tar.gz", ignoreCase = true) || file.name.endsWith(".tar", ignoreCase = true)) {
                    selectedFile = file
                    if (rootfsName.isBlank()) {
                        rootfsName = file.name.substringBeforeLast(".")
                    }
                    showFilePicker = false
                    errorMessage = null // Clear any previous errors
                } else {
                    errorMessage = "Please select a .tar.gz or .tar file"
                    showFilePicker = false
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
}

