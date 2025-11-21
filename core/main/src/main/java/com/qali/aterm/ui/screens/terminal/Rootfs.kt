package com.qali.aterm.ui.screens.terminal

import android.os.Environment
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.qali.aterm.App
import java.io.File

object Rootfs {
    val reTerminal = application!!.filesDir

    init {
        if (reTerminal.exists().not()){
            reTerminal.mkdirs()
        }
    }

    var isDownloaded = mutableStateOf(isFilesDownloaded())
    
    fun isFilesDownloaded(): Boolean {
        // Check if at least one rootfs is installed
        val installedRootfs = getInstalledRootfs()
        return reTerminal.exists() && 
               reTerminal.child("proot").exists() && 
               reTerminal.child("libtalloc.so.2").exists() && 
               installedRootfs.isNotEmpty()
    }
    
    fun getInstalledRootfs(): List<String> {
        if (!reTerminal.exists()) return emptyList()
        return reTerminal.listFiles()?.filter { file ->
            file.isFile && (file.name.endsWith(".tar.gz") || file.name.endsWith(".tar"))
        }?.map { it.name } ?: emptyList()
    }
    
    fun isRootfsInstalled(rootfsName: String): Boolean {
        return reTerminal.child(rootfsName).exists()
    }
    
    fun markRootfsInstalled(rootfsName: String, displayName: String? = null) {
        // Add to installed rootfs list in settings
        val installed = getInstalledRootfsList()
        if (!installed.contains(rootfsName)) {
            val updated = installed + rootfsName
            com.rk.settings.Preference.setString("installed_rootfs", updated.joinToString(","))
        }
        
        // Store display name if provided
        if (displayName != null && displayName.isNotBlank()) {
            setRootfsDisplayName(rootfsName, displayName)
        }
    }
    
    fun getInstalledRootfsList(): List<String> {
        val stored = com.rk.settings.Preference.getString("installed_rootfs", "")
        return if (stored.isBlank()) {
            // Fallback: check files on disk
            getInstalledRootfs()
        } else {
            stored.split(",").filter { it.isNotBlank() }
        }
    }
    
    fun getRootfsDisplayName(rootfsName: String): String {
        val nameKey = "rootfs_name_$rootfsName"
        val storedName = com.rk.settings.Preference.getString(nameKey, "")
        return if (storedName.isNotBlank()) {
            storedName
        } else {
            // Default names for known rootfs
            when (rootfsName) {
                "alpine.tar.gz" -> "Alpine"
                "ubuntu.tar.gz" -> "Ubuntu"
                else -> rootfsName.substringBeforeLast(".").replace("_", " ").split(" ").joinToString(" ") { 
                    it.replaceFirstChar { char -> char.uppercaseChar() }
                }
            }
        }
    }
    
    fun setRootfsDisplayName(rootfsName: String, displayName: String) {
        val nameKey = "rootfs_name_$rootfsName"
        com.rk.settings.Preference.setString(nameKey, displayName)
    }
    
    fun getRootfsWorkingMode(rootfsName: String): Int? {
        return when (rootfsName) {
            "alpine.tar.gz" -> com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE
            "ubuntu.tar.gz" -> com.qali.aterm.ui.screens.settings.WorkingMode.UBUNTU
            else -> {
                // Try to infer from name or return null for custom rootfs
                val name = rootfsName.lowercase()
                when {
                    name.contains("alpine") -> com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE
                    name.contains("ubuntu") -> com.qali.aterm.ui.screens.settings.WorkingMode.UBUNTU
                    else -> null
                }
            }
        }
    }
    
    fun getRootfsFileName(workingMode: Int): String {
        return when (workingMode) {
            com.qali.aterm.ui.screens.settings.WorkingMode.UBUNTU -> "ubuntu.tar.gz"
            com.qali.aterm.ui.screens.settings.WorkingMode.ALPINE -> "alpine.tar.gz"
            else -> {
                // Try to find any installed rootfs
                val installed = getInstalledRootfs()
                installed.firstOrNull() ?: "alpine.tar.gz"
            }
        }
    }
}