package com.qali.aterm.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
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
                 (permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                  permissions[Manifest.permission.READ_MEDIA_VIDEO] == true ||
                  permissions[Manifest.permission.READ_MEDIA_AUDIO] == true))
        
        if (hasStoragePermission) {
            // Permission granted, show file picker if FILE_PICKER is selected
            if (selectedType == RootfsType.FILE_PICKER) {
                showFilePicker = true
            }
        } else {
            // Permission denied
            if (selectedType == RootfsType.FILE_PICKER) {
                errorMessage = "Storage permission is required to pick files"
                selectedType = null // Reset selection
                showFilePicker = false
            }
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
            title = { 
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Install Rootfs")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Step 1: Rootfs source selection
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "1. Select Source",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                item {
                                    FilterChip(
                                        selected = selectedType == RootfsType.ALPINE,
                                        onClick = { 
                                            if (!Rootfs.isRootfsInstalled("alpine.tar.gz")) {
                                                selectedType = RootfsType.ALPINE
                                                errorMessage = null
                                            }
                                        },
                                        label = { Text("Alpine") },
                                        enabled = !Rootfs.isRootfsInstalled("alpine.tar.gz"),
                                        leadingIcon = if (selectedType == RootfsType.ALPINE) {
                                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                        } else null
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = selectedType == RootfsType.UBUNTU,
                                        onClick = { 
                                            if (!Rootfs.isRootfsInstalled("ubuntu.tar.gz")) {
                                                selectedType = RootfsType.UBUNTU
                                                errorMessage = null
                                            }
                                        },
                                        label = { Text("Ubuntu") },
                                        enabled = !Rootfs.isRootfsInstalled("ubuntu.tar.gz"),
                                        leadingIcon = if (selectedType == RootfsType.UBUNTU) {
                                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                        } else null
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = selectedType == RootfsType.CUSTOM,
                                        onClick = { 
                                            selectedType = RootfsType.CUSTOM
                                            errorMessage = null
                                        },
                                        label = { Text("Custom URL") },
                                        leadingIcon = if (selectedType == RootfsType.CUSTOM) {
                                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                        } else null
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = selectedType == RootfsType.FILE_PICKER,
                                        onClick = { 
                                            selectedType = RootfsType.FILE_PICKER
                                            errorMessage = null
                                            if (checkStoragePermission()) {
                                                showFilePicker = true
                                            } else {
                                                requestStoragePermission()
                                            }
                                        },
                                        label = { Text("📁 Local File") },
                                        leadingIcon = if (selectedType == RootfsType.FILE_PICKER) {
                                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                        } else null
                                    )
                                }
                            }
                            
                            // Show disabled message for installed rootfs
                            if (Rootfs.isRootfsInstalled("alpine.tar.gz") || Rootfs.isRootfsInstalled("ubuntu.tar.gz")) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Already installed rootfs are disabled",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Custom URL input or File picker result
                    if (selectedType == RootfsType.CUSTOM) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "2. Enter URL",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = { 
                                        customUrl = it
                                        errorMessage = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Rootfs URL") },
                                    placeholder = { Text("https://example.com/rootfs.tar.gz") },
                                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                    singleLine = true,
                                    isError = customUrl.isNotBlank() && !customUrl.startsWith("http")
                                )
                                if (customUrl.isNotBlank() && !customUrl.startsWith("http")) {
                                    Text(
                                        text = "⚠ URL must start with http:// or https://",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // File picker result
                    if (selectedType == RootfsType.FILE_PICKER && selectedFile != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Selected File",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = selectedFile!!.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = selectedFile!!.absolutePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        maxLines = 2
                                    )
                                }
                                IconButton(
                                    onClick = { 
                                        selectedFile = null
                                        if (selectedType == RootfsType.FILE_PICKER) {
                                            selectedType = null
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear selection",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // Step 2: Distro type selector
                    if (selectedType != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
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
                                        text = "2. Select Distribution",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    com.qali.aterm.ui.screens.setup.DistroType.values().forEach { distro ->
                                        item {
                                            FilterChip(
                                                selected = selectedDistro == distro,
                                                onClick = { 
                                                    selectedDistro = distro
                                                    errorMessage = null
                                                    if (distro.hasPredefinedInit) {
                                                        customInitScript = ""
                                                    }
                                                },
                                                label = { 
                                                    Text(
                                                        text = distro.displayName,
                                                        maxLines = 1
                                                    ) 
                                                },
                                                leadingIcon = if (selectedDistro == distro) {
                                                    { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                                                } else null
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Step 3: Custom init script input (if needed)
                    if (selectedType != null && selectedDistro == com.qali.aterm.ui.screens.setup.DistroType.CUSTOM) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "3. Init Script (Required)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { showInitScriptInfo = true },
                                        modifier = Modifier.size(32.dp)
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
                                    onValueChange = { 
                                        customInitScript = it
                                        errorMessage = null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 180.dp, max = 350.dp),
                                    label = { Text("Init Script") },
                                    placeholder = { Text("#!/bin/sh\nset -e\nexport PATH=...") },
                                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                    minLines = 8,
                                    maxLines = 18,
                                    isError = customInitScript.isBlank()
                                )
                                if (customInitScript.isBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Custom distro requires an init script",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val currentDistro = selectedDistro
                        if (selectedType != null && currentDistro != null && currentDistro != com.qali.aterm.ui.screens.setup.DistroType.CUSTOM && currentDistro.hasPredefinedInit) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "3. Custom Init Script (Optional)",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { showInitScriptInfo = true },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Info,
                                                contentDescription = "Init script help",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = customInitScript,
                                        onValueChange = { customInitScript = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp, max = 250.dp),
                                        label = { Text("Custom Init Script") },
                                        placeholder = { Text("Leave empty to use default ${currentDistro.displayName} init script") },
                                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                        minLines = 5,
                                        maxLines = 12
                                    )
                                }
                            }
                        }
                    }

                    // Step 4: Name input (for all types except predefined)
                    if (selectedType != null && selectedType != RootfsType.ALPINE && selectedType != RootfsType.UBUNTU) {
                        OutlinedTextField(
                            value = rootfsName,
                            onValueChange = { 
                                rootfsName = it
                                errorMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Display Name (Optional)") },
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
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = downloadText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Error message
                    errorMessage?.let { error ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
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
                                
                                // Determine working mode based on rootfs type and distro
                                val targetWorkingMode = when (selectedType) {
                                    RootfsType.ALPINE -> com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE
                                    RootfsType.UBUNTU -> com.qali.aterm.ui.screens.settings.WorkingMode.UBUNTU
                                    RootfsType.FILE_PICKER, RootfsType.CUSTOM -> {
                                        // For custom rootfs, determine working mode based on distro type
                                        when (selectedDistro) {
                                            com.qali.aterm.ui.screens.setup.DistroType.UBUNTU -> com.qali.aterm.ui.screens.settings.WorkingMode.UBUNTU
                                            com.qali.aterm.ui.screens.setup.DistroType.DEBIAN, 
                                            com.qali.aterm.ui.screens.setup.DistroType.KALI, 
                                            com.qali.aterm.ui.screens.setup.DistroType.ARCH, 
                                            com.qali.aterm.ui.screens.setup.DistroType.ALPINE, 
                                            com.qali.aterm.ui.screens.setup.DistroType.CUSTOM -> {
                                                // Use ALPINE working mode for these (they use similar init scripts)
                                                com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE
                                            }
                                            null -> com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE
                                        }
                                    }
                                    null -> com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE
                                }
                                
                                // Store mapping between working mode and rootfs file
                                Rootfs.setRootfsFileForWorkingMode(targetWorkingMode, finalRootfsName)
                                
                                // Set as default working mode if this is a custom rootfs
                                if (selectedType == RootfsType.FILE_PICKER || selectedType == RootfsType.CUSTOM) {
                                    com.rk.settings.Settings.working_Mode = targetWorkingMode
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
    if (showFilePicker) {
        if (checkStoragePermission()) {
            FilePickerDialog(
                context = context,
                initialPath = getInitialStoragePath(context),
                onDismiss = { 
                    showFilePicker = false
                    // Reset selection if user cancels without selecting
                    if (selectedFile == null && selectedType == RootfsType.FILE_PICKER) {
                        selectedType = null
                    }
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
        } else {
            // Permission not granted, show error and close picker
            showFilePicker = false
            errorMessage = "Storage permission is required to pick files"
            selectedType = null // Reset selection
        }
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

