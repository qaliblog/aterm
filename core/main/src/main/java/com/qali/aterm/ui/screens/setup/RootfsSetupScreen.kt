package com.qali.aterm.ui.screens.setup

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
import androidx.navigation.NavHostController
import com.rk.libcommons.*
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.downloader.downloadRootfs
import com.qali.aterm.ui.screens.downloader.abiMap
import com.qali.aterm.ui.screens.terminal.Rootfs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RootfsType {
    ALPINE,
    UBUNTU,
    CUSTOM
}

@Composable
fun RootfsSetupScreen(
    mainActivity: MainActivity,
    navController: NavHostController,
    onSetupComplete: () -> Unit
) {
    var selectedType by remember { mutableStateOf<RootfsType?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Welcome to aTerm",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Choose a Linux distribution to install",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

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

        // Install button
        Button(
            onClick = {
                if (selectedType == null) {
                    errorMessage = "Please select a rootfs option"
                    return@Button
                }
                if (selectedType == RootfsType.CUSTOM && customUrl.isBlank()) {
                    errorMessage = "Please enter a custom URL"
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

                        val rootfsName = when (selectedType) {
                            RootfsType.ALPINE -> "alpine.tar.gz"
                            RootfsType.UBUNTU -> "ubuntu.tar.gz"
                            RootfsType.CUSTOM -> {
                                // Extract filename from URL or use custom_rootfs.tar.gz
                                customUrl.substringAfterLast("/").takeIf { it.endsWith(".tar.gz") || it.endsWith(".tar") }
                                    ?: "custom_rootfs.tar.gz"
                            }
                            null -> throw RuntimeException("No rootfs selected")
                        }

                        val currentType = selectedType
                        val rootfsUrl = when (currentType) {
                            RootfsType.ALPINE -> abiMap[abi]!!.alpine
                            RootfsType.UBUNTU -> abiMap[abi]!!.ubuntu
                            RootfsType.CUSTOM -> customUrl
                            null -> throw RuntimeException("No rootfs selected")
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
                        filesToDownload.add(rootfsName to rootfsUrl)

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

                        // Mark rootfs as installed
                        Rootfs.markRootfsInstalled(rootfsName)
                        
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
            modifier = Modifier.fillMaxWidth(),
            enabled = !isDownloading && selectedType != null
        ) {
            Text(if (isDownloading) "Installing..." else "Install")
        }
    }
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

