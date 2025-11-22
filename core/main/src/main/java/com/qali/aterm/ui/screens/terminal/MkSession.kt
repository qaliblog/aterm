package com.qali.aterm.ui.screens.terminal

import android.os.Environment
import com.rk.libcommons.alpineDir
import com.rk.libcommons.alpineHomeDir
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.createFileIfNot
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
import com.rk.libcommons.localLibDir
import com.rk.libcommons.pendingCommand
import com.rk.settings.Settings
import com.qali.aterm.App
import com.qali.aterm.App.Companion.getTempDir
import com.qali.aterm.BuildConfig
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.settings.WorkingMode
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.io.FileOutputStream

object MkSession {
    fun createSession(
        activity: MainActivity, sessionClient: TerminalSessionClient, session_id: String,workingMode:Int
    ): TerminalSession {
        with(activity) {
            val envVariables = mapOf(
                "ANDROID_ART_ROOT" to System.getenv("ANDROID_ART_ROOT"),
                "ANDROID_DATA" to System.getenv("ANDROID_DATA"),
                "ANDROID_I18N_ROOT" to System.getenv("ANDROID_I18N_ROOT"),
                "ANDROID_ROOT" to System.getenv("ANDROID_ROOT"),
                "ANDROID_RUNTIME_ROOT" to System.getenv("ANDROID_RUNTIME_ROOT"),
                "ANDROID_TZDATA_ROOT" to System.getenv("ANDROID_TZDATA_ROOT"),
                "BOOTCLASSPATH" to System.getenv("BOOTCLASSPATH"),
                "DEX2OATBOOTCLASSPATH" to System.getenv("DEX2OATBOOTCLASSPATH"),
                "EXTERNAL_STORAGE" to System.getenv("EXTERNAL_STORAGE")
            )

            val workingDir = pendingCommand?.workingDir ?: alpineHomeDir().path

            val initFileName = when (workingMode) {
                WorkingMode.UBUNTU -> "init-host-ubuntu.sh"
                else -> "init-host.sh"
            }
            val initFile: File = localBinDir().child(initFileName.replace(".sh", ""))

            if (initFile.exists().not()){
                initFile.createFileIfNot()
                initFile.writeText(assets.open(initFileName).bufferedReader().use { it.readText() })
            }


            // Get rootfs filename for current working mode
            val rootfsFileName = com.qali.aterm.ui.screens.terminal.Rootfs.getRootfsFileName(workingMode)
            
            // Check if there's a custom init script stored for this rootfs
            val customInitScript = com.qali.aterm.ui.screens.terminal.Rootfs.getRootfsInitScript(rootfsFileName)
            val distroType = com.qali.aterm.ui.screens.terminal.Rootfs.getRootfsDistroType(rootfsFileName)
            
            // Determine which init script to use
            val initScriptContent = if (customInitScript != null && customInitScript.isNotBlank()) {
                // Use custom init script if provided
                customInitScript
            } else {
                // Use predefined init script based on distro type or working mode
                val initScriptName = when (distroType) {
                    com.qali.aterm.ui.screens.setup.DistroType.UBUNTU -> "init-ubuntu.sh"
                    com.qali.aterm.ui.screens.setup.DistroType.DEBIAN -> "init-debian.sh"
                    com.qali.aterm.ui.screens.setup.DistroType.KALI -> "init-kali.sh"
                    com.qali.aterm.ui.screens.setup.DistroType.ARCH -> "init-arch.sh"
                    com.qali.aterm.ui.screens.setup.DistroType.ALPINE -> "init.sh"
                    com.qali.aterm.ui.screens.setup.DistroType.CUSTOM -> {
                        // For custom distro without init script, fall back to Alpine
                        "init.sh"
                    }
                    null -> {
                        // Fallback to working mode if distro type not set
                        when (workingMode) {
                            WorkingMode.UBUNTU -> "init-ubuntu.sh"
                            else -> "init.sh"
                        }
                    }
                }
                // Read from assets
                try {
                    assets.open(initScriptName).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    // If asset doesn't exist, fall back to Alpine init
                    android.util.Log.w("MkSession", "Init script $initScriptName not found in assets, using Alpine init")
                    assets.open("init.sh").bufferedReader().use { it.readText() }
                }
            }
            
            // Write the init script
            localBinDir().child("init").apply {
                createFileIfNot()
                writeText(initScriptContent)
            }


            val env = mutableListOf(
                "PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}",
                "HOME=/sdcard",
                "PUBLIC_HOME=${getExternalFilesDir(null)?.absolutePath}",
                "COLORTERM=truecolor",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "BIN=${localBinDir()}",
                "DEBUG=${BuildConfig.DEBUG}",
                "PREFIX=${filesDir.parentFile!!.path}",
                "LD_LIBRARY_PATH=${localLibDir().absolutePath}",
                "LINKER=${if(File("/system/bin/linker64").exists()){"/system/bin/linker64"}else{"/system/bin/linker"}}",
                "NATIVE_LIB_DIR=${applicationInfo.nativeLibraryDir}",
                "PKG=${packageName}",
                "RISH_APPLICATION_ID=${packageName}",
                "PKG_PATH=${applicationInfo.sourceDir}",
                "PROOT_TMP_DIR=${getTempDir().child(session_id).also { if (it.exists().not()){it.mkdirs()} }}",
                "TMPDIR=${getTempDir().absolutePath}"
            )

            if (File(applicationInfo.nativeLibraryDir).child("libproot-loader32.so").exists()){
                env.add("PROOT_LOADER32=${applicationInfo.nativeLibraryDir}/libproot-loader32.so")
            }

            if (File(applicationInfo.nativeLibraryDir).child("libproot-loader.so").exists()){
                env.add("PROOT_LOADER=${applicationInfo.nativeLibraryDir}/libproot-loader.so")
            }




            env.addAll(envVariables.map { "${it.key}=${it.value}" })

            localDir().child("stat").apply {
                if (exists().not()){
                    writeText(stat)
                }
            }

            localDir().child("vmstat").apply {
                if (exists().not()){
                    writeText(vmstat)
                }
            }

            pendingCommand?.env?.let {
                env.addAll(it)
            }

            val args: Array<String>

            val shell = if (pendingCommand == null) {
                args = when (workingMode) {
                    WorkingMode.ALPINE, WorkingMode.UBUNTU -> arrayOf("-c",initFile.absolutePath)
                    else -> arrayOf()
                }
                "/system/bin/sh"
            } else{
                args = pendingCommand!!.args
                pendingCommand!!.shell
            }

            pendingCommand = null
            return TerminalSession(
                shell,
                workingDir,
                args,
                env.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

    }
}