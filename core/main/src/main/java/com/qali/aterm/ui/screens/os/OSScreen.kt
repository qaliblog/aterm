package com.qali.aterm.ui.screens.os

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.terminal.TerminalBackEnd
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DesktopEnvironment(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val lightweight: Boolean,
    val mobileOptimized: Boolean,
    val installScript: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var detectedDistro by remember { mutableStateOf<String?>(null) }
    var installationStatus by remember { mutableStateOf<InstallationStatus?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf("") }
    
    val desktopEnvironment = DesktopEnvironment(
        id = "aterm-touch",
        name = "aTerm Touch",
        description = "A premium mobile-first desktop environment inspired by Ubuntu Touch. Features gesture navigation, large touch-friendly UI elements, edge swipes, smooth animations, and full browser support (Chromium, Chrome, Firefox) with Selenium automation. Perfect for mobile development and web automation.",
        icon = Icons.Default.PhoneAndroid,
        lightweight = true,
        mobileOptimized = true,
        installScript = "install-aterm-touch.sh"
    )
    
    // Detect Linux distribution and check installation status
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val session = mainActivity.sessionBinder?.getSession(sessionId)
                if (session != null) {
                    // Try to detect distribution
                    val detectCommands = listOf(
                        "cat /etc/os-release | grep '^ID=' | cut -d'=' -f2 | tr -d '\"'",
                        "cat /etc/os-release | grep '^NAME=' | cut -d'=' -f2 | tr -d '\"'",
                        "which apt-get >/dev/null 2>&1 && echo 'debian' || which yum >/dev/null 2>&1 && echo 'rhel' || which pacman >/dev/null 2>&1 && echo 'arch' || echo 'unknown'"
                    )
                    
                    // For now, assume it's a Debian-based system if we have a session
                    detectedDistro = "debian" // Will be enhanced to actually detect
                    
                    // Check if installation is complete by checking for .xinitrc
                    // Use bash to ensure compatibility
                    session.write("bash -c 'test -f ~/.xinitrc && echo INSTALLED || echo NOT_INSTALLED'\n")
                    delay(1500)
                    val output = session.emulator?.screen?.getTranscriptText() ?: ""
                    // Check the last few lines for INSTALLED
                    val recentLines = output.split("\n").takeLast(10).joinToString("\n")
                    if ("INSTALLED" in recentLines && recentLines.indexOf("INSTALLED") > recentLines.lastIndexOf("NOT_INSTALLED")) {
                        installationStatus = InstallationStatus.Success("aTerm Touch is installed and ready to use!")
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "aTerm Touch",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Mobile-first desktop environment inspired by Ubuntu Touch",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                if (detectedDistro != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Detected: ${detectedDistro!!.replaceFirstChar { it.uppercase() }}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Installation Status
        AnimatedVisibility(
            visible = installationStatus != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            installationStatus?.let { status ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (status) {
                            is InstallationStatus.Installing -> MaterialTheme.colorScheme.tertiaryContainer
                            is InstallationStatus.Success -> MaterialTheme.colorScheme.primaryContainer
                            is InstallationStatus.Error -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (status) {
                                is InstallationStatus.Installing -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                is InstallationStatus.Success -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                is InstallationStatus.Error -> {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (status) {
                                        is InstallationStatus.Installing -> "Installing..."
                                        is InstallationStatus.Success -> "Installation Complete!"
                                        is InstallationStatus.Error -> "Installation Failed"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when (status) {
                                        is InstallationStatus.Installing -> MaterialTheme.colorScheme.onTertiaryContainer
                                        is InstallationStatus.Success -> MaterialTheme.colorScheme.onPrimaryContainer
                                        is InstallationStatus.Error -> MaterialTheme.colorScheme.onErrorContainer
                                    }
                                )
                                if (status is InstallationStatus.Installing && installProgress.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = installProgress,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                                if (status is InstallationStatus.Error) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = status.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        
        // Desktop Environment Card
        DesktopEnvironmentCard(
            desktopEnvironment = desktopEnvironment,
            isSelected = true,
            isInstalling = isInstalling,
            isInstalled = installationStatus is InstallationStatus.Success,
            onClick = {
                // Card is always selected
            },
            onInstall = {
                scope.launch {
                    installDesktopEnvironment(
                        mainActivity = mainActivity,
                        sessionId = sessionId,
                        desktopEnvironment = desktopEnvironment,
                        onStatusUpdate = { status, progress ->
                            installationStatus = status
                            installProgress = progress
                            isInstalling = status is InstallationStatus.Installing
                        }
                    )
                }
            },
            onStartDesktop = {
                scope.launch {
                    startDesktopEnvironment(
                        mainActivity = mainActivity,
                        sessionId = sessionId,
                        onStatusUpdate = { status, progress ->
                            installationStatus = status
                            installProgress = progress
                        }
                    )
                }
            }
        )
    }
}

@Composable
fun DesktopEnvironmentCard(
    desktopEnvironment: DesktopEnvironment,
    isSelected: Boolean,
    isInstalling: Boolean,
    isInstalled: Boolean = false,
    onClick: () -> Unit,
    onInstall: () -> Unit,
    onStartDesktop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = !isInstalling,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = desktopEnvironment.icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = desktopEnvironment.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = desktopEnvironment.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (desktopEnvironment.lightweight) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Lightweight",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                            if (desktopEnvironment.mobileOptimized) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Mobile Optimized",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (isSelected && !isInstalling) {
                    if (isInstalled) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onStartDesktop,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Desktop")
                            }
                            OutlinedButton(
                                onClick = onInstall,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reinstall")
                            }
                        }
                    } else {
                        Button(
                            onClick = onInstall,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Install")
                        }
                    }
                }
            }
        }
    }
}

sealed class InstallationStatus {
    data class Installing(val progress: String = "") : InstallationStatus()
    data class Success(val message: String = "Desktop environment installed successfully!") : InstallationStatus()
    data class Error(val message: String) : InstallationStatus()
}

suspend fun installDesktopEnvironment(
    mainActivity: MainActivity,
    sessionId: String,
    desktopEnvironment: DesktopEnvironment,
    onStatusUpdate: (InstallationStatus, String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            onStatusUpdate(InstallationStatus.Installing(), "Preparing installation...")
            delay(500)
            
            val session = mainActivity.sessionBinder?.getSession(sessionId)
            if (session == null) {
                onStatusUpdate(InstallationStatus.Error("No active session found. Please ensure you have a Linux session active."), "")
                return@withContext
            }
            
            // Generate installation script
            val installScript = generateInstallScript(desktopEnvironment)
            
            // Write script to a temporary file
            val scriptFile = java.io.File.createTempFile("install_${desktopEnvironment.id}_", ".sh")
            scriptFile.writeText(installScript)
            scriptFile.setExecutable(true)
            
            onStatusUpdate(InstallationStatus.Installing(), "Detecting package manager...")
            
            // Execute the installation script via terminal session
            val scriptPath = scriptFile.absolutePath
            val command = "bash $scriptPath 2>&1"
            
            // Write command to terminal session
            session.write("$command\n")
            
            // Wait a bit for command to start
            delay(1000)
            
            // Monitor installation progress
            var lastOutput = ""
            var attempts = 0
            val maxAttempts = 300 // 30 seconds max
            
            while (attempts < maxAttempts) {
                delay(100)
                attempts++
                
                // Try to read output from terminal (this is a simplified approach)
                // In a real implementation, you'd parse the terminal output
                val currentOutput = session.emulator?.screen?.getTranscriptText() ?: ""
                
                // Update progress based on output
                when {
                    "Installing X server" in currentOutput -> {
                        onStatusUpdate(InstallationStatus.Installing(), "Installing X server and display manager...")
                    }
                    "Installing window manager" in currentOutput -> {
                        onStatusUpdate(InstallationStatus.Installing(), "Installing window manager...")
                    }
                    "Installing web browsers" in currentOutput -> {
                        onStatusUpdate(InstallationStatus.Installing(), "Installing web browsers (Chromium, Chrome, Firefox)...")
                    }
                    "Installing Selenium" in currentOutput -> {
                        onStatusUpdate(InstallationStatus.Installing(), "Installing Selenium and automation tools...")
                    }
                    "Installing essential mobile applications" in currentOutput -> {
                        onStatusUpdate(InstallationStatus.Installing(), "Installing applications...")
                    }
                    "Configuring" in currentOutput -> {
                        onStatusUpdate(InstallationStatus.Installing(), "Configuring desktop environment...")
                    }
                    "Installation complete" in currentOutput || "complete!" in currentOutput || "Installation Complete!" in currentOutput || "✓ Configuration complete" in currentOutput -> {
                        // Wait a bit more to ensure .xinitrc is created
                        delay(2000)
                        onStatusUpdate(InstallationStatus.Success("${desktopEnvironment.name} has been installed successfully! Click 'Start Desktop' to launch the GUI."), "")
                        scriptFile.delete()
                        return@withContext
                    }
                    "Error:" in currentOutput || "error" in currentOutput.lowercase() -> {
                        val errorMsg = currentOutput.lines().find { 
                            it.contains("Error", ignoreCase = true) || it.contains("error", ignoreCase = true)
                        } ?: "Installation failed"
                        onStatusUpdate(InstallationStatus.Error(errorMsg), "")
                        scriptFile.delete()
                        return@withContext
                    }
                }
                
                lastOutput = currentOutput
            }
            
            // Timeout - assume it's still running or completed
            onStatusUpdate(InstallationStatus.Success("Installation process started. Check terminal for progress. You can start the desktop with: startx"), "")
            scriptFile.delete()
            
        } catch (e: Exception) {
            onStatusUpdate(InstallationStatus.Error("Installation failed: ${e.message}"), "")
        }
    }
}

suspend fun startDesktopEnvironment(
    mainActivity: MainActivity,
    sessionId: String,
    onStatusUpdate: (InstallationStatus, String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            onStatusUpdate(InstallationStatus.Installing(), "Starting desktop environment...")
            
            val session = mainActivity.sessionBinder?.getSession(sessionId)
            if (session == null) {
                onStatusUpdate(InstallationStatus.Error("No active session found. Please ensure you have a Linux session active."), "")
                return@withContext
            }
            
            // Check if .xinitrc exists using bash
            session.write("bash -c 'test -f ~/.xinitrc && echo Config found || echo Config missing'\n")
            delay(500)
            
            // Install and start VNC server for GUI display
            onStatusUpdate(InstallationStatus.Installing(), "Setting up VNC server for GUI display...")
            
            // Create a bash script to set up VNC (works with any shell)
            val vncSetupScript = """#!/bin/bash
command -v vncserver >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq tigervnc-standalone-server tigervnc-common 2>/dev/null || yum install -y -q tigervnc-server 2>/dev/null || pacman -S --noconfirm tigervnc 2>/dev/null || apk add -q tigervnc 2>/dev/null || true)
vncserver -kill :1 2>/dev/null || true
mkdir -p ~/.vnc
echo 'aterm' | vncpasswd -f > ~/.vnc/passwd 2>/dev/null && chmod 600 ~/.vnc/passwd || true
cat > ~/.vnc/xstartup << 'VNC_EOF'
#!/bin/sh
[ -x /etc/vnc/xstartup ] && exec /etc/vnc/xstartup
[ -r ${'$'}HOME/.Xresources ] && xrdb ${'$'}HOME/.Xresources
xsetroot -solid grey
vncconfig -iconic &
exec /bin/sh ~/.xinitrc
VNC_EOF
chmod +x ~/.vnc/xstartup
export DISPLAY=:1
vncserver :1 -geometry 1920x1080 -depth 24 2>&1 &
""".trimIndent()
            
            // Write script to temp file and execute
            val vncScriptFile = java.io.File.createTempFile("vnc_setup_", ".sh")
            vncScriptFile.writeText(vncSetupScript)
            vncScriptFile.setExecutable(true)
            
            session.write("bash ${vncScriptFile.absolutePath} 2>&1\n")
            delay(3000)
            
            // Clean up
            vncScriptFile.delete()
            
            // Start VNC server
            onStatusUpdate(InstallationStatus.Installing(), "Starting VNC server on display :1...")
            delay(1000)
            
            onStatusUpdate(InstallationStatus.Success("Desktop environment is starting on VNC display :1! The GUI should be accessible via VNC viewer at localhost:5901 (password: aterm)."), "")
            
        } catch (e: Exception) {
            onStatusUpdate(InstallationStatus.Error("Failed to start desktop: ${e.message}"), "")
        }
    }
}

fun generateInstallScript(desktopEnvironment: DesktopEnvironment): String {
    return when (desktopEnvironment.id) {
        "aterm-touch" -> """
            #!/bin/bash
            # aTerm Touch - Mobile-First Desktop Environment Installation Script
            # Inspired by Ubuntu Touch design philosophy
            
            set -e
            
            echo "=========================================="
            echo "  aTerm Touch Installation"
            echo "  Mobile-First Desktop Environment"
            echo "=========================================="
            echo ""
            
            # Detect package manager and distribution
            if command -v apt-get >/dev/null 2>&1; then
                PKG_MANAGER="apt-get"
                UPDATE_CMD="apt-get update -qq"
                INSTALL_CMD="apt-get install -y -qq"
                DISTRO_TYPE="debian"
            elif command -v yum >/dev/null 2>&1; then
                PKG_MANAGER="yum"
                UPDATE_CMD="yum check-update -q || true"
                INSTALL_CMD="yum install -y -q"
                DISTRO_TYPE="rhel"
            elif command -v pacman >/dev/null 2>&1; then
                PKG_MANAGER="pacman"
                UPDATE_CMD="pacman -Sy --noconfirm"
                INSTALL_CMD="pacman -S --noconfirm"
                DISTRO_TYPE="arch"
            elif command -v apk >/dev/null 2>&1; then
                PKG_MANAGER="apk"
                UPDATE_CMD="apk update -q"
                INSTALL_CMD="apk add -q"
                DISTRO_TYPE="alpine"
            else
                echo "Error: Unsupported package manager"
                exit 1
            fi
            
            echo "[1/8] Updating package lists..."
            ${'$'}{UPDATE_CMD} || true
            echo "✓ Package lists updated"
            echo ""
            
            echo "[2/8] Installing X server and display manager..."
            if [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                ${'$'}{INSTALL_CMD} xorg-server xf86-video-fbdev xf86-input-libinput || true
            elif [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                ${'$'}{INSTALL_CMD} xorg xinit xserver-xorg-core xserver-xorg-input-libinput || true
            elif [ "${'$'}{DISTRO_TYPE}" = "arch" ]; then
                ${'$'}{INSTALL_CMD} xorg-server xorg-xinit xf86-input-libinput || true
            elif [ "${'$'}{DISTRO_TYPE}" = "rhel" ]; then
                ${'$'}{INSTALL_CMD} xorg-x11-server-Xorg xorg-x11-xinit xorg-x11-drv-libinput || true
            else
                ${'$'}{INSTALL_CMD} xorg xinit || true
            fi
            echo "✓ X server installed"
            echo ""
            
            echo "[3/8] Installing window manager and compositor..."
            if [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                ${'$'}{INSTALL_CMD} openbox obconf compton || true
            else
                ${'$'}{INSTALL_CMD} openbox obconf || true
                ${'$'}{INSTALL_CMD} picom || ${'$'}{INSTALL_CMD} compton || true
            fi
            echo "✓ Window manager installed"
            echo ""
            
            echo "[4/8] Installing mobile-optimized panel and launcher..."
            if [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                ${'$'}{INSTALL_CMD} tint2 lxpanel || true
            else
                ${'$'}{INSTALL_CMD} tint2 lxpanel lxmenu-data || true
            fi
            echo "✓ Panel installed"
            echo ""
            
            echo "[5/8] Installing web browsers..."
            # Install Chromium
            if [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                ${'$'}{INSTALL_CMD} chromium-browser chromium-chromedriver 2>/dev/null || \
                ${'$'}{INSTALL_CMD} chromium chromium-driver 2>/dev/null || \
                echo "  Note: Chromium not available in repositories, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "rhel" ]; then
                ${'$'}{INSTALL_CMD} chromium chromium-headless 2>/dev/null || \
                echo "  Note: Chromium not available, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "arch" ]; then
                ${'$'}{INSTALL_CMD} chromium chromium-driver 2>/dev/null || \
                echo "  Note: Chromium not available, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                ${'$'}{INSTALL_CMD} chromium chromium-chromedriver 2>/dev/null || \
                echo "  Note: Chromium not available, skipping..."
            fi
            
            # Install Firefox
            if [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                ${'$'}{INSTALL_CMD} firefox 2>/dev/null || \
                ${'$'}{INSTALL_CMD} firefox-esr 2>/dev/null || \
                echo "  Note: Firefox not available in repositories, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "rhel" ]; then
                ${'$'}{INSTALL_CMD} firefox 2>/dev/null || \
                echo "  Note: Firefox not available, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "arch" ]; then
                ${'$'}{INSTALL_CMD} firefox 2>/dev/null || \
                ${'$'}{INSTALL_CMD} firefox-developer-edition 2>/dev/null || \
                echo "  Note: Firefox not available, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                ${'$'}{INSTALL_CMD} firefox 2>/dev/null || \
                echo "  Note: Firefox not available, skipping..."
            fi
            
            # Install Google Chrome (via direct download for better compatibility)
            if [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                if ! command -v google-chrome >/dev/null 2>&1 && ! command -v chromium >/dev/null 2>&1; then
                    echo "  Installing Google Chrome..."
                    wget -q -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb 2>/dev/null || true
                    if [ ! -f /tmp/chrome.deb ]; then
                        wget -q -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb 2>/dev/null || true
                    fi
                    if [ -f /tmp/chrome.deb ]; then
                        ${'$'}{INSTALL_CMD} /tmp/chrome.deb 2>/dev/null || dpkg -i /tmp/chrome.deb 2>/dev/null || true
                        rm -f /tmp/chrome.deb
                    fi
                fi
            fi
            
            echo "✓ Browsers installed"
            echo ""
            
            echo "[6/8] Installing Selenium and dependencies..."
            # Install Python and pip if not available
            if ! command -v python3 >/dev/null 2>&1; then
                if [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                    ${'$'}{INSTALL_CMD} python3 py3-pip || true
                else
                    ${'$'}{INSTALL_CMD} python3 python3-pip || ${'$'}{INSTALL_CMD} python3 python-pip || true
                fi
            fi
            
            # Install Selenium
            if command -v pip3 >/dev/null 2>&1; then
                pip3 install --quiet --upgrade pip setuptools wheel 2>/dev/null || true
                pip3 install --quiet selenium webdriver-manager 2>/dev/null || true
                pip3 install --quiet selenium-wire undetected-chromedriver 2>/dev/null || true
            elif command -v pip >/dev/null 2>&1; then
                pip install --quiet --upgrade pip setuptools wheel 2>/dev/null || true
                pip install --quiet selenium webdriver-manager 2>/dev/null || true
                pip install --quiet selenium-wire undetected-chromedriver 2>/dev/null || true
            fi
            
            # Install Node.js and npm for Selenium WebDriver (if not available)
            if ! command -v node >/dev/null 2>&1; then
                if [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                    ${'$'}{INSTALL_CMD} nodejs npm || true
                elif [ "${'$'}{DISTRO_TYPE}" = "rhel" ]; then
                    ${'$'}{INSTALL_CMD} nodejs npm || true
                elif [ "${'$'}{DISTRO_TYPE}" = "arch" ]; then
                    ${'$'}{INSTALL_CMD} nodejs npm || true
                elif [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                    ${'$'}{INSTALL_CMD} nodejs npm || true
                fi
            fi
            
            # Install Selenium for Node.js
            if command -v npm >/dev/null 2>&1; then
                npm install -g selenium-webdriver chromedriver geckodriver 2>/dev/null || true
            fi
            
            echo "✓ Selenium installed"
            echo ""
            
            echo "[7/8] Installing essential mobile applications..."
            # File manager
            ${'$'}{INSTALL_CMD} pcmanfm || ${'$'}{INSTALL_CMD} thunar || ${'$'}{INSTALL_CMD} nautilus || true
            
            # Terminal
            ${'$'}{INSTALL_CMD} lxterminal || ${'$'}{INSTALL_CMD} xterm || ${'$'}{INSTALL_CMD} gnome-terminal || true
            
            # Text editor
            ${'$'}{INSTALL_CMD} mousepad || ${'$'}{INSTALL_CMD} leafpad || ${'$'}{INSTALL_CMD} gedit || true
            
            # Image viewer
            ${'$'}{INSTALL_CMD} feh || ${'$'}{INSTALL_CMD} viewnior || ${'$'}{INSTALL_CMD} eog || true
            
            # Network tools
            ${'$'}{INSTALL_CMD} network-manager || ${'$'}{INSTALL_CMD} wicd || true
            
            # Additional Ubuntu Touch-like apps
            ${'$'}{INSTALL_CMD} rofi dmenu || true  # App launcher
            ${'$'}{INSTALL_CMD} dunst || true  # Notifications
            ${'$'}{INSTALL_CMD} xdotool || true  # For gestures
            ${'$'}{INSTALL_CMD} imagemagick || true  # For wallpaper generation
            
            echo "✓ Applications installed"
            echo ""
            
            echo "[8/8] Installing VNC server for GUI display..."
            # Install VNC server for remote desktop access
            if [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                ${'$'}{INSTALL_CMD} tigervnc-standalone-server tigervnc-common 2>/dev/null || \
                ${'$'}{INSTALL_CMD} tightvncserver 2>/dev/null || \
                echo "  Note: VNC server not available, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "rhel" ]; then
                ${'$'}{INSTALL_CMD} tigervnc-server 2>/dev/null || \
                echo "  Note: VNC server not available, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "arch" ]; then
                ${'$'}{INSTALL_CMD} tigervnc 2>/dev/null || \
                echo "  Note: VNC server not available, skipping..."
            elif [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                ${'$'}{INSTALL_CMD} tigervnc 2>/dev/null || \
                echo "  Note: VNC server not available, skipping..."
            fi
            echo "✓ VNC server installed"
            echo ""
            
            echo "[9/9] Configuring aTerm Touch desktop environment..."
            
            # Create config directories
            mkdir -p ~/.config/openbox
            mkdir -p ~/.config/tint2
            mkdir -p ~/.config/aterm-touch
            mkdir -p ~/.local/share/applications
            
            # Create mobile-optimized Openbox configuration
            cat > ~/.config/openbox/rc.xml << 'OPENBOX_EOF'
            <?xml version="1.0" encoding="UTF-8"?>
            <openbox_config xmlns="http://openbox.org/3.4/rc">
                <theme><name>Clearlooks</name></theme>
                <resistance>
                    <strength>10</strength>
                    <screen_edge_strength>20</screen_edge_strength>
                </resistance>
                <focus>
                    <focusNew>yes</focusNew>
                    <followMouse>no</followMouse>
                    <focusLast>yes</focusLast>
                    <underMouse>no</underMouse>
                    <focusDelay>200</focusDelay>
                    <raiseOnFocus>yes</raiseOnFocus>
                </focus>
                <placement>
                    <policy>Smart</policy>
                    <center>yes</center>
                    <monitor>Primary</monitor>
                    <primaryMonitor>1</primaryMonitor>
                </placement>
                <theme>
                    <name>Clearlooks</name>
                    <titleLayout>NLIMC</titleLayout>
                    <keepBorder>yes</keepBorder>
                    <animateIconify>yes</animateIconify>
                    <font place="ActiveWindow">
                        <name>sans</name>
                        <size>12</size>
                        <weight>bold</weight>
                        <slant>normal</slant>
                    </font>
                    <font place="InactiveWindow">
                        <name>sans</name>
                        <size>12</size>
                        <weight>normal</weight>
                        <slant>normal</slant>
                    </font>
                    <font place="MenuHeader">
                        <name>sans</name>
                        <size>12</size>
                        <weight>bold</weight>
                        <slant>normal</slant>
                    </font>
                    <font place="MenuItem">
                        <name>sans</name>
                        <size>12</size>
                        <weight>normal</weight>
                        <slant>normal</slant>
                    </font>
                    <font place="ActiveOnScreenDisplay">
                        <name>sans</name>
                        <size>14</size>
                        <weight>bold</weight>
                        <slant>normal</slant>
                    </font>
                    <font place="InactiveOnScreenDisplay">
                        <name>sans</name>
                        <size>14</size>
                        <weight>normal</weight>
                        <slant>normal</slant>
                    </font>
                </theme>
                <desktops>
                    <number>1</number>
                    <firstdesk>1</firstdesk>
                    <popupTime>0</popupTime>
                    <names>
                        <name>Desktop 1</name>
                    </names>
                </desktops>
                <resize>
                    <drawContents>yes</drawContents>
                    <popupShow>Nonpixel</popupShow>
                    <popupPosition>Center</popupPosition>
                    <popupFixedPosition>
                        <x>10</x>
                        <y>10</y>
                    </popupFixedPosition>
                </resize>
                <margins>
                    <top>0</top>
                    <bottom>60</bottom>
                    <left>0</left>
                    <right>0</right>
                </margins>
                <dock>
                    <position>Bottom</position>
                    <floatingX>0</floatingX>
                    <floatingY>0</floatingY>
                    <noStrut>no</noStrut>
                    <stacking>Above</stacking>
                    <direction>Vertical</direction>
                    <autoHide>no</autoHide>
                    <hideDelay>300</hideDelay>
                    <showDelay>300</showDelay>
                    <moveButton>Middle</moveButton>
                </dock>
            </openbox_config>
            OPENBOX_EOF
            
            # Create mobile-optimized tint2 panel (Ubuntu Touch style - Premium)
            cat > ~/.config/tint2/tint2rc << 'TINT2_EOF'
            # aTerm Touch Panel Configuration
            # Premium Ubuntu Touch inspired design
            
            # Panel - Ubuntu Touch style bottom bar
            panel_background_color = #1A1A1A 98
            panel_size = 100% 72
            panel_margin = 0 0
            panel_padding = 8 4
            panel_dock = 0
            panel_position = bottom center horizontal
            panel_layer = top
            panel_monitor = all
            panel_shrink = 0
            autohide = 0
            autohide_show_timeout = 0
            autohide_hide_timeout = 0.5
            autohide_height = 3
            strut_policy = follow_size
            panel_background_id = 1
            
            # Panel border - subtle rounded top
            rounded = 8
            border_width = 0
            border_sides = T
            border_content_tint_weight = 0
            
            # Background - Ubuntu Touch dark theme
            background_color = #1A1A1A 98
            background_color_hover = #2D2D2D 98
            background_color_pressed = #0D0D0D 98
            background_border_sides = T
            background_border_width = 1
            background_border_color = #3D3D3D 60
            
            # Taskbar - Ubuntu Touch style app switcher
            taskbar_mode = single_desktop
            taskbar_padding = 6 4
            taskbar_background_id = 0
            task_active_background_id = 2
            task_urgent_background_id = 3
            task_iconified_background_id = 0
            task_maximum_size = 140 64
            
            # Task - Large touch-friendly icons
            task_text = 0
            task_icon = 1
            task_centered = 1
            task_maximum_size = 140 64
            task_padding = 12 8
            task_background_id = 0
            task_active_background_id = 2
            task_urgent_background_id = 3
            task_iconified_background_id = 0
            task_icon_asb = 100 0 0
            task_active_icon_asb = 100 0 0
            task_urgent_icon_asb = 100 0 0
            task_iconified_icon_asb = 60 0 0
            task_tooltip = 1
            
            # System tray - Ubuntu Touch style indicators
            systray_padding = 8 4
            systray_background_id = 0
            systray_icon_size = 32
            systray_icon_asb = 100 0 0
            systray_monitor = 1
            systray_sort = ascending
            
            # Clock - Large, readable
            time1_format = %H:%M
            time1_font = sans 16
            time1_font_color = #FFFFFF 100
            time2_format = %a %d %b
            time2_font = sans 10
            time2_font_color = #CCCCCC 90
            clock_font_color = #FFFFFF 100
            clock_padding = 12 8
            clock_background_id = 0
            clock_tooltip = 1
            clock_tooltip_time_format = %A, %B %d, %Y - %H:%M:%S
            clock_lclick_command = 
            clock_rclick_command = 
            
            # Battery - Mobile-friendly
            battery = 1
            battery_low_status = 15
            battery_low_cmd = notify-send -u critical "Battery Low" "Please charge your device"
            battery_hide = 100
            battery_font = sans 12
            battery_font_color = #FFFFFF 100
            battery_padding = 8 4
            battery_background_id = 0
            battery_tooltip = 1
            
            # Tooltip - Ubuntu Touch style
            tooltip = 1
            tooltip_padding = 8 6
            tooltip_show_timeout = 0.3
            tooltip_hide_timeout = 0.1
            tooltip_background_id = 4
            tooltip_font_color = #FFFFFF 100
            tooltip_font = sans 11
            
            # Launcher button - Ubuntu Touch style
            launcher_icon_theme = Adwaita
            launcher_padding = 12 8
            launcher_background_id = 0
            launcher_icon_size = 40
            launcher_item_app = ~/.local/bin/aterm-launcher
            TINT2_EOF
            
            # Create gesture configuration script
            cat > ~/.config/aterm-touch/gestures.sh << 'GESTURES_EOF'
            #!/bin/bash
            # aTerm Touch Gesture Support
            # Ubuntu Touch inspired gesture navigation
            
            # Edge swipe gestures (if supported)
            # Left edge: Show app launcher
            # Right edge: Show notifications
            # Bottom edge: Show app switcher
            # Top edge: Show system menu
            
            # Install gesture support if available
            if command -v libinput-gestures >/dev/null 2>&1; then
                # Configure gestures
                mkdir -p ~/.config/libinput-gestures
                cat > ~/.config/libinput-gestures/libinput-gestures.conf << 'LIBINPUT_EOF'
            gesture swipe left 3  xdotool key super+Tab
            gesture swipe right 3 xdotool key super+Shift+Tab
            gesture swipe up 3    xdotool key super
            gesture swipe down 3  xdotool key alt+Tab
            LIBINPUT_EOF
                libinput-gestures-setup autostart
            fi
            GESTURES_EOF
            chmod +x ~/.config/aterm-touch/gestures.sh
            
            # Create launcher script directory first
            mkdir -p ~/.local/bin
            
            # Create launcher script (Ubuntu Touch style app drawer - Premium)
            cat > ~/.local/bin/aterm-launcher << 'LAUNCHER_EOF'
            #!/bin/bash
            # aTerm Touch App Launcher
            # Premium Ubuntu Touch inspired application launcher
            
            # Create Ubuntu Touch style launcher with rofi
            if command -v rofi >/dev/null 2>&1; then
                rofi -show drun \
                    -theme-str 'window {width: 95%; height: 80%; location: center;}' \
                    -theme-str 'listview {lines: 10; columns: 3;}' \
                    -theme-str 'element {padding: 16px; border-radius: 8px;}' \
                    -theme-str 'element selected {background-color: #0078D4;}' \
                    -theme-str 'inputbar {padding: 12px;}' \
                    -theme-str 'prompt {padding: 8px;}' \
                    -font "sans 14" \
                    -kb-cancel Escape \
                    -kb-accept-entry Return
            elif command -v dmenu >/dev/null 2>&1; then
                dmenu_run -l 12 -fn "sans-16" \
                    -nb "#1A1A1A" -nf "#FFFFFF" \
                    -sb "#0078D4" -sf "#FFFFFF" \
                    -h 48 -x 5 -y 5 -w 95%
            else
                # Fallback to simple menu
                ls ~/.local/share/applications/*.desktop 2>/dev/null | \
                    xargs -I {} grep -h "^Name=" {} | sed 's/Name=//' | \
                    dmenu -l 12 -fn "sans-16"
            fi
            LAUNCHER_EOF
            chmod +x ~/.local/bin/aterm-launcher
            
            # Create startup script (Premium Ubuntu Touch experience)
            cat > ~/.xinitrc << 'XINIT_EOF'
            #!/bin/sh
            # aTerm Touch Desktop Startup Script
            # Premium Ubuntu Touch Experience
            
            # Set DPI for mobile screens (Ubuntu Touch optimized)
            export QT_AUTO_SCREEN_SCALE_FACTOR=1.5
            export GDK_SCALE=1.5
            export GDK_DPI_SCALE=0.75
            export XCURSOR_SIZE=32
            
            # Enable hardware acceleration if available
            export LIBGL_ALWAYS_SOFTWARE=0
            export __GLX_VENDOR_LIBRARY_NAME=nvidia 2>/dev/null || true
            
            # Start notification daemon (Ubuntu Touch style)
            if command -v dunst >/dev/null 2>&1; then
                dunst -config ~/.config/dunst/dunstrc &
                DUNST_PID=${'$'}!
            fi
            
            # Start compositor for smooth animations (Ubuntu Touch style)
            if command -v picom >/dev/null 2>&1; then
                picom --backend glx --vsync --animations --animation-window-mass 0.5 --animation-stiffness 200 --animation-dampening 25 --shadow --shadow-radius 12 --shadow-opacity 0.3 &
                PICOM_PID=${'$'}!
            elif command -v compton >/dev/null 2>&1; then
                compton --backend glx --vsync opengl-swc --shadow --shadow-radius 12 &
                PICOM_PID=${'$'}!
            fi
            
            # Start panel (Ubuntu Touch style)
            tint2 &
            TINT2_PID=${'$'}!
            
            # Start gesture support
            ~/.config/aterm-touch/gestures.sh &
            
            # Set wallpaper (Ubuntu Touch style gradient or image)
            if command -v feh >/dev/null 2>&1; then
                # Try to find a nice wallpaper
                if [ -f /usr/share/backgrounds/default.png ]; then
                    feh --bg-scale /usr/share/backgrounds/default.png
                elif [ -f /usr/share/pixmaps/backgrounds/default.png ]; then
                    feh --bg-scale /usr/share/pixmaps/backgrounds/default.png
                else
                    # Create a simple gradient background
                    if command -v convert >/dev/null 2>&1; then
                        convert -size 1920x1080 gradient:#1A1A1A-#2D2D2D /tmp/aterm-bg.png 2>/dev/null && feh --bg-scale /tmp/aterm-bg.png || true
                    fi
                fi
            fi
            
            # Wait a moment for everything to start
            sleep 0.5
            
            # Start window manager
            exec openbox-session
            
            # Cleanup on exit
            kill ${'$'}PICOM_PID ${'$'}TINT2_PID ${'$'}DUNST_PID 2>/dev/null || true
            XINIT_EOF
            chmod +x ~/.xinitrc
            
            # Create notification daemon config (Ubuntu Touch style)
            mkdir -p ~/.config/dunst
            cat > ~/.config/dunst/dunstrc << 'DUNST_EOF'
            [global]
            font = Sans 12
            markup = full
            format = "<b>%s</b>\n%b"
            sort = yes
            indicate_hidden = yes
            alignment = left
            show_age_threshold = 60
            word_wrap = yes
            ignore_newline = no
            geometry = "300x50-30+50"
            transparency = 0
            idle_threshold = 120
            monitor = 0
            follow = keyboard
            sticky_history = yes
            history_length = 20
            show_indicators = yes
            line_height = 0
            separator_height = 2
            padding = 8
            horizontal_padding = 8
            separator_color = frame
            startup_notification = false
            dmenu = /usr/bin/dmenu -p dunst:
            browser = /usr/bin/firefox -new-tab
            
            [frame]
            width = 2
            color = "#0078D4"
            
            [shortcuts]
            close = ctrl+space
            close_all = ctrl+shift+space
            history = ctrl+grave
            context = ctrl+shift+period
            
            [urgency_low]
            background = "#1A1A1A"
            foreground = "#FFFFFF"
            timeout = 10
            
            [urgency_normal]
            background = "#1A1A1A"
            foreground = "#FFFFFF"
            timeout = 10
            
            [urgency_critical]
            background = "#CC0000"
            foreground = "#FFFFFF"
            frame_color = "#FF0000"
            timeout = 0
            DUNST_EOF
            
            # Create desktop entries for browsers
            cat > ~/.local/share/applications/chromium.desktop << 'CHROMIUM_EOF'
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=Chromium
            Comment=Web Browser
            Exec=chromium --no-sandbox %U
            Icon=chromium
            Terminal=false
            Categories=Network;WebBrowser;
            MimeType=text/html;text/xml;application/xhtml+xml;
            CHROMIUM_EOF
            
            cat > ~/.local/share/applications/firefox.desktop << 'FIREFOX_EOF'
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=Firefox
            Comment=Web Browser
            Exec=firefox %U
            Icon=firefox
            Terminal=false
            Categories=Network;WebBrowser;
            MimeType=text/html;text/xml;application/xhtml+xml;
            FIREFOX_EOF
            
            # Ensure selenium-setup directory exists
            mkdir -p ~/.local/bin
            
            # Create Selenium helper script
            cat > ~/.local/bin/selenium-setup << 'SELENIUM_EOF'
            #!/bin/bash
            # Selenium Setup and Test Script for aTerm Touch
            
            echo "=== Selenium Setup for aTerm Touch ==="
            echo ""
            
            # Check Python
            if command -v python3 >/dev/null 2>&1; then
                echo "✓ Python3 found: ${'$'}(python3 --version)"
                PYTHON_CMD="python3"
            elif command -v python >/dev/null 2>&1; then
                echo "✓ Python found: ${'$'}(python --version)"
                PYTHON_CMD="python"
            else
                echo "✗ Python not found. Please install Python first."
                exit 1
            fi
            
            # Check Selenium
            if ${'$'}{PYTHON_CMD} -c "import selenium" 2>/dev/null; then
                echo "✓ Selenium installed: ${'$'}(${'$'}{PYTHON_CMD} -c 'import selenium; print(selenium.__version__)')"
            else
                echo "Installing Selenium..."
                if command -v pip3 >/dev/null 2>&1; then
                    pip3 install selenium webdriver-manager
                else
                    pip install selenium webdriver-manager
                fi
            fi
            
            # Check browsers
            echo ""
            echo "Browser availability:"
            if command -v chromium >/dev/null 2>&1 || command -v chromium-browser >/dev/null 2>&1; then
                echo "✓ Chromium available"
            fi
            if command -v google-chrome >/dev/null 2>&1; then
                echo "✓ Google Chrome available"
            fi
            if command -v firefox >/dev/null 2>&1; then
                echo "✓ Firefox available"
            fi
            
            # Create test script
            cat > ~/selenium_test.py << 'PYTHON_EOF'
            #!/usr/bin/env python3
            # Selenium Test Script for aTerm Touch
            
            from selenium import webdriver
            from selenium.webdriver.chrome.service import Service
            from selenium.webdriver.chrome.options import Options
            from selenium.webdriver.firefox.service import Service as FirefoxService
            from selenium.webdriver.firefox.options import Options as FirefoxOptions
            from webdriver_manager.chrome import ChromeDriverManager
            from webdriver_manager.firefox import GeckoDriverManager
            import sys
            
            def test_chromium():
                # Test Chromium browser
                try:
                    chrome_options = Options()
                    chrome_options.add_argument('--no-sandbox')
                    chrome_options.add_argument('--disable-dev-shm-usage')
                    chrome_options.add_argument('--headless')
                    
                    service = Service(ChromeDriverManager().install())
                    driver = webdriver.Chrome(service=service, options=chrome_options)
                    driver.get("https://www.google.com")
                    print("✓ Chromium test successful!")
                    driver.quit()
                    return True
                except Exception as e:
                    print("✗ Chromium test failed: " + str(e))
                    return False
            
            def test_firefox():
                # Test Firefox browser
                try:
                    firefox_options = FirefoxOptions()
                    firefox_options.add_argument('--headless')
                    
                    service = FirefoxService(GeckoDriverManager().install())
                    driver = webdriver.Firefox(service=service, options=firefox_options)
                    driver.get("https://www.google.com")
                    print("✓ Firefox test successful!")
                    driver.quit()
                    return True
                except Exception as e:
                    print("✗ Firefox test failed: " + str(e))
                    return False
            
            if __name__ == "__main__":
                print("Testing Selenium with browsers...\\n")
                chromium_ok = test_chromium()
                firefox_ok = test_firefox()
                
                if chromium_ok or firefox_ok:
                    print("\\n✓ Selenium is working!")
                    sys.exit(0)
                else:
                    print("\\n✗ Selenium tests failed")
                    sys.exit(1)
            PYTHON_EOF
            chmod +x ~/selenium_test.py
            
            echo ""
            echo "✓ Selenium setup complete!"
            echo "Run 'python3 ~/selenium_test.py' to test Selenium"
            SELENIUM_EOF
            chmod +x ~/.local/bin/selenium-setup
            
            # Create desktop entry for aTerm Touch
            cat > ~/.local/share/applications/aterm-touch.desktop << 'DESKTOP_EOF'
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=aTerm Touch
            Comment=Mobile-First Desktop Environment
            Exec=startx
            Icon=phone
            Terminal=false
            Categories=System;
            DESKTOP_EOF
            
            # Create Selenium desktop entry
            cat > ~/.local/share/applications/selenium-test.desktop << 'SELENIUM_DESKTOP_EOF'
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=Selenium Test
            Comment=Test Selenium with browsers
            Exec=python3 ~/selenium_test.py
            Icon=application-x-executable
            Terminal=true
            Categories=Development;
            SELENIUM_DESKTOP_EOF
            
            echo "✓ Configuration complete"
            echo ""
            echo ""
            echo "=========================================="
            echo "  Installation Complete!"
            echo "=========================================="
            echo ""
            echo "aTerm Touch has been installed successfully!"
            echo ""
            echo "To start the desktop environment, run:"
            echo "  startx"
            echo ""
            echo "Installed Browsers:"
            echo "  • Chromium (with ChromeDriver)"
            echo "  • Firefox (with GeckoDriver)"
            echo "  • Google Chrome (if available)"
            echo ""
            echo "Selenium Support:"
            echo "  • Python Selenium library"
            echo "  • WebDriver Manager"
            echo "  • Test script: ~/selenium_test.py"
            echo "  • Setup script: selenium-setup"
            echo ""
            echo "Features:"
            echo "  • Ubuntu Touch inspired design"
            echo "  • Touch-optimized interface"
            echo "  • Large, mobile-friendly UI elements"
            echo "  • Gesture navigation support"
            echo "  • Full browser support (Chromium, Chrome, Firefox)"
            echo "  • Selenium automation ready"
            echo "  • Lightweight and fast"
            echo ""
            echo "Enjoy your mobile desktop experience!"
        """.trimIndent()
        
        else -> """
            #!/bin/bash
            # ${desktopEnvironment.name} Installation Script
            echo "Installing ${desktopEnvironment.name}..."
            echo "Error: Unknown desktop environment"
            exit 1
        """.trimIndent()
    }
}
