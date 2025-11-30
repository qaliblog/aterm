package com.qali.aterm.agent

import java.io.File
import com.rk.libcommons.getRootfsDir

/**
 * Service for detecting system information (OS, architecture, package manager, etc.)
 * Used to provide context to AI for generating system-specific commands
 */
object SystemInfoService {
    
    data class SystemInfo(
        val os: String,
        val osVersion: String?,
        val architecture: String,
        val packageManager: String,
        val packageManagerCommands: Map<String, String>,
        val shell: String,
        val pathSeparator: String,
        val lineSeparator: String
    )
    
    /**
     * Detect system information by checking various indicators
     * @param workspaceRoot Optional workspace root path to help detect the correct OS/distro
     */
    fun detectSystemInfo(workspaceRoot: String? = null): SystemInfo {
        val os = detectOS(workspaceRoot)
        val osVersion = detectOSVersion(workspaceRoot)
        val architecture = detectArchitecture()
        val packageManager = detectPackageManager(os)
        val packageManagerCommands = getPackageManagerCommands(packageManager)
        val shell = detectShell()
        val pathSeparator = if (os == "Windows") "\\" else "/"
        val lineSeparator = System.getProperty("line.separator") ?: "\n"
        
        return SystemInfo(
            os = os,
            osVersion = osVersion,
            architecture = architecture,
            packageManager = packageManager,
            packageManagerCommands = packageManagerCommands,
            shell = shell,
            pathSeparator = pathSeparator,
            lineSeparator = lineSeparator
        )
    }
    
    private fun detectOS(workspaceRoot: String? = null): String {
        val osName = System.getProperty("os.name", "").lowercase()
        
        return when {
            osName.contains("linux") -> {
                // First, check workspace root path to determine distro (most reliable)
                val rootfsPath = workspaceRoot ?: getRootfsDir().absolutePath
                when {
                    // Check workspace root path first - this is the most reliable indicator
                    rootfsPath.contains("/ubuntu", ignoreCase = true) -> {
                        // Verify it's actually Ubuntu by checking for Ubuntu-specific files
                        val osReleaseFile = File(rootfsPath, "etc/os-release")
                        if (osReleaseFile.exists()) {
                            try {
                                val osRelease = osReleaseFile.readText()
                                if (osRelease.contains("Ubuntu", ignoreCase = true)) {
                                    "Ubuntu"
                                } else if (osRelease.contains("Debian", ignoreCase = true)) {
                                    "Debian"
                                } else {
                                    "Debian/Ubuntu"
                                }
                            } catch (e: Exception) {
                                "Debian/Ubuntu"
                            }
                        } else {
                            "Debian/Ubuntu"
                        }
                    }
                    rootfsPath.contains("/alpine", ignoreCase = true) -> "Alpine Linux"
                    // Then check system files
                    File("/etc/alpine-release").exists() -> "Alpine Linux"
                    File("/etc/debian_version").exists() -> "Debian/Ubuntu"
                    File("/etc/os-release").exists() -> {
                        try {
                            val osRelease = File("/etc/os-release").readText()
                            when {
                                osRelease.contains("Ubuntu", ignoreCase = true) -> "Ubuntu"
                                osRelease.contains("Debian", ignoreCase = true) -> "Debian"
                                osRelease.contains("Alpine", ignoreCase = true) -> "Alpine Linux"
                                else -> "Linux"
                            }
                        } catch (e: Exception) {
                            "Linux"
                        }
                    }
                    File("/etc/redhat-release").exists() -> "RedHat/CentOS"
                    File("/etc/arch-release").exists() -> "Arch Linux"
                    // Fallback: check common paths
                    File("/data/user/0/com.qali.aterm/local/alpine").exists() -> "Alpine Linux"
                    File("/data/user/0/com.qali.aterm/local/ubuntu").exists() -> "Debian/Ubuntu"
                    System.getProperty("user.dir", "").contains("ubuntu", ignoreCase = true) -> "Debian/Ubuntu"
                    System.getProperty("user.dir", "").contains("alpine", ignoreCase = true) -> "Alpine Linux"
                    System.getenv("PATH")?.contains("ubuntu", ignoreCase = true) == true -> "Debian/Ubuntu"
                    System.getenv("PATH")?.contains("alpine", ignoreCase = true) == true -> "Alpine Linux"
                    else -> "Linux"
                }
            }
            osName.contains("windows") -> "Windows"
            osName.contains("mac") || osName.contains("darwin") -> "macOS"
            osName.contains("freebsd") -> "FreeBSD"
            osName.contains("openbsd") -> "OpenBSD"
            osName.contains("netbsd") -> "NetBSD"
            else -> "Unknown"
        }
    }
    
    private fun detectOSVersion(workspaceRoot: String? = null): String? {
        return try {
            val rootfsPath = workspaceRoot ?: getRootfsDir().absolutePath
            when {
                File(rootfsPath, "etc/alpine-release").exists() -> {
                    File(rootfsPath, "etc/alpine-release").readText().trim()
                }
                File("/etc/alpine-release").exists() -> {
                    File("/etc/alpine-release").readText().trim()
                }
                File(rootfsPath, "etc/os-release").exists() -> {
                    val osRelease = File(rootfsPath, "etc/os-release").readText()
                    val versionId = Regex("VERSION_ID=\"?([^\"]+)\"?").find(osRelease)
                    versionId?.groupValues?.get(1)
                }
                File("/etc/os-release").exists() -> {
                    val osRelease = File("/etc/os-release").readText()
                    val versionId = Regex("VERSION_ID=\"?([^\"]+)\"?").find(osRelease)
                    versionId?.groupValues?.get(1)
                }
                else -> System.getProperty("os.version")
            }
        } catch (e: Exception) {
            System.getProperty("os.version")
        }
    }
    
    private fun detectArchitecture(): String {
        val arch = System.getProperty("os.arch", "").lowercase()
        
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("arm") -> "arm"
            arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
            arch.contains("x86") || arch.contains("i386") || arch.contains("i686") -> "x86"
            arch.contains("ppc") -> "ppc"
            arch.contains("mips") -> "mips"
            else -> arch.ifEmpty { "unknown" }
        }
    }
    
    private fun detectPackageManager(os: String): String {
        return when {
            os.contains("Alpine", ignoreCase = true) -> "apk"
            os.contains("Debian") || os.contains("Ubuntu") -> "apt"
            os.contains("RedHat") || os.contains("CentOS") || os.contains("Fedora") -> {
                // Check for dnf (newer) or yum (older)
                try {
                    val process = Runtime.getRuntime().exec("which dnf")
                    process.waitFor()
                    if (process.exitValue() == 0) "dnf" else "yum"
                } catch (e: Exception) {
                    "yum"
                }
            }
            os.contains("Arch") -> "pacman"
            os.contains("macOS") -> "brew"
            os == "Windows" -> "choco" // Chocolatey
            else -> {
                // Try to detect by checking which package manager exists
                // Prioritize apk for Alpine environments
                val packageManagers = listOf("apk", "apt", "yum", "dnf", "pacman", "brew")
                for (pm in packageManagers) {
                    try {
                        val process = Runtime.getRuntime().exec("which $pm")
                        process.waitFor()
                        if (process.exitValue() == 0) {
                            return pm
                        }
                    } catch (e: Exception) {
                        // Continue checking
                    }
                }
                
                // Last resort: check for Alpine indicators
                if (File("/etc/alpine-release").exists() || 
                    System.getProperty("user.dir", "").contains("alpine", ignoreCase = true) ||
                    System.getenv("PATH")?.contains("alpine", ignoreCase = true) == true) {
                    return "apk"
                }
                
                "unknown"
            }
        }
    }
    
    private fun getPackageManagerCommands(packageManager: String): Map<String, String> {
        return when (packageManager) {
            "apk" -> mapOf(
                "install" to "apk add",
                "update" to "apk update",
                "upgrade" to "apk upgrade",
                "remove" to "apk del",
                "search" to "apk search",
                "info" to "apk info"
            )
            "apt" -> mapOf(
                "install" to "apt install",
                "update" to "apt update",
                "upgrade" to "apt upgrade",
                "remove" to "apt remove",
                "search" to "apt search",
                "info" to "apt show"
            )
            "yum" -> mapOf(
                "install" to "yum install",
                "update" to "yum update",
                "upgrade" to "yum upgrade",
                "remove" to "yum remove",
                "search" to "yum search",
                "info" to "yum info"
            )
            "dnf" -> mapOf(
                "install" to "dnf install",
                "update" to "dnf update",
                "upgrade" to "dnf upgrade",
                "remove" to "dnf remove",
                "search" to "dnf search",
                "info" to "dnf info"
            )
            "pacman" -> mapOf(
                "install" to "pacman -S",
                "update" to "pacman -Sy",
                "upgrade" to "pacman -Syu",
                "remove" to "pacman -R",
                "search" to "pacman -Ss",
                "info" to "pacman -Si"
            )
            "brew" -> mapOf(
                "install" to "brew install",
                "update" to "brew update",
                "upgrade" to "brew upgrade",
                "remove" to "brew uninstall",
                "search" to "brew search",
                "info" to "brew info"
            )
            "choco" -> mapOf(
                "install" to "choco install",
                "update" to "choco upgrade",
                "upgrade" to "choco upgrade all",
                "remove" to "choco uninstall",
                "search" to "choco search",
                "info" to "choco info"
            )
            else -> mapOf(
                "install" to "install",
                "update" to "update",
                "upgrade" to "upgrade",
                "remove" to "remove",
                "search" to "search",
                "info" to "info"
            )
        }
    }
    
    private fun detectShell(): String {
        val shellEnv = System.getenv("SHELL")
        if (shellEnv != null && shellEnv.isNotEmpty()) {
            return File(shellEnv).name
        }
        
        // Fallback detection
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            osName.contains("windows") -> "cmd.exe"
            else -> "sh" // Default to sh
        }
    }
    
    /**
     * Generate a system context string for AI prompts
     * @param workspaceRoot Optional workspace root path to help detect the correct OS/distro
     */
    fun generateSystemContext(workspaceRoot: String? = null): String {
        val info = detectSystemInfo(workspaceRoot)
        
        return buildString {
            appendLine("## System Information")
            appendLine("- **OS:** ${info.os}")
            if (info.osVersion != null) {
                appendLine("- **OS Version:** ${info.osVersion}")
            }
            appendLine("- **Architecture:** ${info.architecture}")
            appendLine("- **Package Manager:** ${info.packageManager}")
            appendLine("- **Shell:** ${info.shell}")
            appendLine()
            appendLine("### Package Manager Commands")
            info.packageManagerCommands.forEach { (action, command) ->
                appendLine("- **$action:** `$command`")
            }
            appendLine()
            appendLine("**IMPORTANT:** When generating commands, you MUST use the correct package manager commands for this system.")
            appendLine("For example, on ${info.os}, use `${info.packageManagerCommands["install"]}` instead of generic commands like `apt install`.")
            appendLine("Always use system-specific commands that match the detected package manager.")
        }
    }
}
