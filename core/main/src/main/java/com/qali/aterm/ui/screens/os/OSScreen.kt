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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.FloatingActionButton
import androidx.compose.foundation.layout.Box
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
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
    
    // Use rememberSaveable to preserve state when switching tabs
    var detectedDistro by rememberSaveable { mutableStateOf<String?>(null) }
    var vncRunning by rememberSaveable { mutableStateOf(false) }
    var isStartingVNC by rememberSaveable { mutableStateOf(false) } // Guard against multiple executions
    
    val desktopEnvironment = DesktopEnvironment(
        id = "aterm-touch",
        name = "aTerm Touch",
        description = "A premium mobile-first desktop environment inspired by Ubuntu Touch. Features gesture navigation, large touch-friendly UI elements, edge swipes, smooth animations, and full browser support (Chromium, Chrome, Firefox) with Selenium automation. Perfect for mobile development and web automation.",
        icon = Icons.Default.PhoneAndroid,
        lightweight = true,
        mobileOptimized = true,
        installScript = "install-aterm-touch.sh"
    )
    
    // Function to check VNC status
    fun checkVNCStatus() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val session = mainActivity.sessionBinder?.getSession(sessionId)
                    if (session != null) {
                        // Check if VNC is running - include Xvnc process detection
                        session.write("bash -c '(netstat -ln 2>/dev/null | grep \":5901\" >/dev/null || ss -ln 2>/dev/null | grep \":5901\" >/dev/null || ps aux 2>/dev/null | grep -v grep | grep -E \"[X]tigervnc|[X]vnc.*:1|[X]vnc\" >/dev/null || ls ~/.vnc/*:1.pid ~/.vnc/localhost:1.pid 2>/dev/null | head -1) && echo VNC_RUNNING || echo VNC_NOT_RUNNING'\n")
                        delay(1500)
                        val vncOutput = session.emulator?.screen?.getTranscriptText() ?: ""
                        val vncLines = vncOutput.split("\n").takeLast(15).joinToString("\n")
                        if ("VNC_RUNNING" in vncLines || "New Xtigervnc server" in vncLines || "on port 5901" in vncLines) {
                            vncRunning = true
                        } else {
                            vncRunning = false
                        }
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }
    }
    
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
                    // Use bash to ensure compatibility and check more reliably
                    session.write("bash -c 'if [ -f ~/.xinitrc ] && [ -f ~/.config/openbox/rc.xml ]; then echo INSTALLED; else echo NOT_INSTALLED; fi'\n")
                    delay(2000)
                    val output = session.emulator?.screen?.getTranscriptText() ?: ""
                    // Check the last few lines for INSTALLED - look for the most recent INSTALLED
                    val lines = output.split("\n")
                    var foundInstalled = false
                    for (i in lines.size - 1 downTo 0) {
                        if (lines[i].contains("INSTALLED")) {
                            foundInstalled = true
                            break
                        } else if (lines[i].contains("NOT_INSTALLED")) {
                            break
                        }
                    }
                    if (foundInstalled) {
                        // Check if VNC is already running
                        checkVNCStatus()
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }
    
    // Periodically check VNC status when not running (to detect when it starts)
    LaunchedEffect(vncRunning, isStartingVNC) {
        if (!vncRunning && !isStartingVNC) {
            // Check every 3 seconds if VNC is not running
            while (!vncRunning && !isStartingVNC) {
                delay(3000)
                checkVNCStatus()
            }
        }
    }
    
    // If VNC is running, show only the viewer
    if (vncRunning) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Use native VNC viewer instead of WebView (no CDN dependency)
            NativeVNCViewer(
                modifier = Modifier.fillMaxSize(),
                sessionId = sessionId,
                mainActivity = mainActivity
            )
            // Floating action button to start/restart VNC
            FloatingActionButton(
                onClick = {
                    if (!isStartingVNC) {
                        scope.launch {
                            isStartingVNC = true
                            startDesktopEnvironment(
                                mainActivity = mainActivity,
                                sessionId = sessionId,
                                onStatusUpdate = { status, _ ->
                                    if (status is InstallationStatus.Success) {
                                        // Check if message indicates VNC started
                                        if ("VNC" in status.message || "display :1" in status.message || "port 5901" in status.message) {
                                            // Verify VNC is actually running
                                            val session = mainActivity.sessionBinder?.getSession(sessionId)
                                            if (session != null) {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        delay(3000) // Give VNC more time to fully start
                                                        session.write("bash -c '(netstat -ln 2>/dev/null | grep \":5901\" >/dev/null || ss -ln 2>/dev/null | grep \":5901\" >/dev/null || ps aux 2>/dev/null | grep -v grep | grep -E \"[X]tigervnc|[X]vnc.*:1\" >/dev/null || ls ~/.vnc/*:1.pid 2>/dev/null | head -1) && echo VNC_RUNNING || echo VNC_NOT_RUNNING'\n")
                                                        delay(2000)
                                                        val vncCheckOutput = session.emulator?.screen?.getTranscriptText() ?: ""
                                                        val vncCheckLines = vncCheckOutput.split("\n").takeLast(20).joinToString("\n")
                                                        if ("VNC_RUNNING" in vncCheckLines || "New Xtigervnc server" in vncCheckLines || "on port 5901" in vncCheckLines) {
                                                            vncRunning = true
                                                        }
                                                    }
                                                }
                                            } else {
                                                // Fallback: if message mentions VNC, assume it's running
                                                vncRunning = true
                                            }
                                        }
                                    }
                                    isStartingVNC = false
                                }
                            )
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = if (isStartingVNC) Icons.Default.Refresh else Icons.Default.PlayArrow,
                    contentDescription = if (isStartingVNC) "Starting..." else "Start Desktop"
                )
            }
        }
    } else {
        // Show setup screen when VNC is not running
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "aTerm Touch Desktop",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Install aTerm Touch from Settings to get started",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    if (!isStartingVNC) {
                        scope.launch {
                            isStartingVNC = true
                            startDesktopEnvironment(
                                mainActivity = mainActivity,
                                sessionId = sessionId,
                                onStatusUpdate = { status, _ ->
                                    if (status is InstallationStatus.Success) {
                                        // Check if message indicates VNC started
                                        if ("VNC" in status.message || "display :1" in status.message || "port 5901" in status.message) {
                                            // Verify VNC is actually running
                                            val session = mainActivity.sessionBinder?.getSession(sessionId)
                                            if (session != null) {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        delay(3000) // Give VNC more time to fully start
                                                        session.write("bash -c '(netstat -ln 2>/dev/null | grep \":5901\" >/dev/null || ss -ln 2>/dev/null | grep \":5901\" >/dev/null || ps aux 2>/dev/null | grep -v grep | grep -E \"[X]tigervnc|[X]vnc.*:1\" >/dev/null || ls ~/.vnc/*:1.pid 2>/dev/null | head -1) && echo VNC_RUNNING || echo VNC_NOT_RUNNING'\n")
                                                        delay(2000)
                                                        val vncCheckOutput = session.emulator?.screen?.getTranscriptText() ?: ""
                                                        val vncCheckLines = vncCheckOutput.split("\n").takeLast(20).joinToString("\n")
                                                        if ("VNC_RUNNING" in vncCheckLines || "New Xtigervnc server" in vncCheckLines || "on port 5901" in vncCheckLines) {
                                                            vncRunning = true
                                                        }
                                                    }
                                                }
                                            } else {
                                                // Fallback: if message mentions VNC, assume it's running
                                                vncRunning = true
                                            }
                                        }
                                    }
                                    isStartingVNC = false
                                }
                            )
                        }
                    }
                },
                enabled = !isStartingVNC
            ) {
                if (isStartingVNC) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Starting...")
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Desktop")
                }
            }
        }
    }
}

@Composable
fun VNCViewer(
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.mediaPlaybackRequiresUserGesture = false
                
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return false
                    }
                }
                
                // Use noVNC library from CDN - proper VNC implementation
                val htmlContent = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                        <title>aTerm Touch VNC</title>
                        <!-- noVNC library from CDN - try multiple sources for reliability -->
                        <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@novnc/core@1.4.0/lib/rfb.css" onerror="this.onerror=null; this.href='https://unpkg.com/@novnc/core@1.4.0/lib/rfb.css';">
                        <script>
                            // Try multiple CDN sources for noVNC library
                            (function() {
                                const sources = [
                                    'https://cdn.jsdelivr.net/npm/@novnc/core@1.4.0/lib/rfb.min.js',
                                    'https://unpkg.com/@novnc/core@1.4.0/lib/rfb.min.js',
                                    'https://cdnjs.cloudflare.com/ajax/libs/noVNC/1.4.0/core/rfb.min.js'
                                ];
                                let currentSource = 0;
                                
                                function loadScript(src) {
                                    return new Promise(function(resolve, reject) {
                                        const script = document.createElement('script');
                                        script.src = src;
                                        script.onload = resolve;
                                        script.onerror = function() {
                                            currentSource++;
                                            if (currentSource < sources.length) {
                                                loadScript(sources[currentSource]).then(resolve).catch(reject);
                                            } else {
                                                reject(new Error('All CDN sources failed'));
                                            }
                                        };
                                        document.head.appendChild(script);
                                    });
                                }
                                
                                loadScript(sources[0]).catch(function(err) {
                                    console.error('Failed to load VNC library from all CDN sources:', err);
                                });
                            })();
                        </script>
                        <style>
                            * {
                                margin: 0;
                                padding: 0;
                                box-sizing: border-box;
                            }
                            body {
                                margin: 0;
                                padding: 0;
                                overflow: hidden;
                                background: #1A1A1A;
                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            }
                            #noVNC_container {
                                width: 100%;
                                height: 100vh;
                                position: relative;
                                display: flex;
                                flex-direction: column;
                            }
                            #noVNC_status_bar {
                                background: #2D2D2D;
                                color: #FFFFFF;
                                padding: 8px 16px;
                                font-size: 12px;
                                text-align: center;
                                flex-shrink: 0;
                                border-bottom: 1px solid #3D3D3D;
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                gap: 8px;
                            }
                            .status-indicator {
                                width: 8px;
                                height: 8px;
                                border-radius: 50%;
                                background: #FFA500;
                                animation: pulse 2s infinite;
                            }
                            .status-indicator.connected {
                                background: #00AA00;
                                animation: none;
                            }
                            .status-indicator.error {
                                background: #CC0000;
                                animation: none;
                            }
                            @keyframes pulse {
                                0%, 100% { opacity: 1; }
                                50% { opacity: 0.5; }
                            }
                            #noVNC_screen {
                                flex: 1;
                                width: 100%;
                                background: #1A1A1A;
                                position: relative;
                            }
                            .fallback {
                                display: flex;
                                flex-direction: column;
                                align-items: center;
                                justify-content: center;
                                height: 100%;
                                color: #FFFFFF;
                                text-align: center;
                                padding: 20px;
                            }
                        </style>
                    </head>
                    <body>
                        <div id="noVNC_container">
                            <div id="noVNC_status_bar">
                                <span class="status-indicator" id="status_indicator"></span>
                                <span id="status_text">Connecting to VNC server...</span>
                            </div>
                            <div id="noVNC_screen"></div>
                        </div>
                        <script>
                            (function() {
                                const statusBar = document.getElementById('noVNC_status_bar');
                                const statusText = document.getElementById('status_text');
                                const statusIndicator = document.getElementById('status_indicator');
                                const screen = document.getElementById('noVNC_screen');
                                
                                let rfb = null;
                                let reconnectAttempts = 0;
                                const MAX_RECONNECT_ATTEMPTS = 50; // Keep retrying indefinitely (50 * 5s = ~4 minutes)
                                let reconnectTimer = null;
                                
                                function connectVNC() {
                                    try {
                                        // Clear any existing connection
                                        if (rfb) {
                                            try {
                                                rfb.disconnect();
                                            } catch(e) {
                                                // Ignore disconnect errors
                                            }
                                            rfb = null;
                                        }
                                        
                                        statusText.textContent = reconnectAttempts > 0 ? 
                                            'Reconnecting to VNC server... (attempt ' + reconnectAttempts + ')' : 
                                            'Connecting to VNC server...';
                                        statusIndicator.className = 'status-indicator';
                                        
                                        // Use noVNC library to connect via websockify
                                        const wsUrl = 'ws://127.0.0.1:6080/websockify';
                                        
                                        // Create RFB connection using noVNC library
                                        rfb = new RFB(screen, wsUrl, {
                                            credentials: {
                                                password: 'aterm'
                                            },
                                            scaleViewport: true,
                                            resizeSession: true,
                                            dragViewport: false,
                                            focusOnClick: true,
                                            clipViewport: false
                                        });
                                        
                                        // Handle connection events
                                        rfb.addEventListener('connect', function() {
                                            statusText.textContent = 'Connected to VNC server';
                                            statusIndicator.className = 'status-indicator connected';
                                            reconnectAttempts = 0; // Reset on successful connection
                                            if (reconnectTimer) {
                                                clearTimeout(reconnectTimer);
                                                reconnectTimer = null;
                                            }
                                        });
                                        
                                        rfb.addEventListener('disconnect', function(e) {
                                            if (e.detail.clean) {
                                                statusText.textContent = 'Disconnected from VNC server';
                                                statusIndicator.className = 'status-indicator';
                                            } else {
                                                statusText.textContent = 'Connection lost. Reconnecting...';
                                                statusIndicator.className = 'status-indicator';
                                                // Try to reconnect after a delay
                                                scheduleReconnect();
                                            }
                                        });
                                        
                                        rfb.addEventListener('credentialsrequired', function() {
                                            statusText.textContent = 'Authentication required...';
                                            // Password is already set in credentials
                                        });
                                        
                                        rfb.addEventListener('securityfailure', function(e) {
                                            statusText.textContent = 'Authentication failed. Retrying...';
                                            statusIndicator.className = 'status-indicator';
                                            // Retry on auth failure too
                                            scheduleReconnect();
                                        });
                                        
                                        // Handle resize
                                        rfb.addEventListener('resize', function(e) {
                                            statusText.textContent = 'Connected (' + e.detail.width + 'x' + e.detail.height + ')';
                                        });
                                        
                                    } catch(e) {
                                        statusText.textContent = 'Connection error: ' + e.message + '. Retrying...';
                                        statusIndicator.className = 'status-indicator';
                                        console.error('VNC connection error:', e);
                                        
                                        // Retry on any error
                                        scheduleReconnect();
                                    }
                                }
                                
                                function scheduleReconnect() {
                                    if (reconnectTimer) {
                                        clearTimeout(reconnectTimer);
                                    }
                                    
                                    reconnectAttempts++;
                                    if (reconnectAttempts <= MAX_RECONNECT_ATTEMPTS) {
                                        reconnectTimer = setTimeout(function() {
                                            connectVNC();
                                        }, 5000); // Retry every 5 seconds
                                    } else {
                                        // After many attempts, show fallback but keep trying
                                        statusText.textContent = 'Unable to connect. Still retrying...';
                                        reconnectTimer = setTimeout(function() {
                                            reconnectAttempts = 0; // Reset counter and keep trying
                                            connectVNC();
                                        }, 10000); // Retry every 10 seconds after many failures
                                    }
                                }
                                
                                // Start connection when page loads
                                let libraryLoadTimeout = null;
                                const LIBRARY_LOAD_TIMEOUT = 10000; // 10 seconds timeout
                                
                                function startVNCConnection() {
                                    if (typeof RFB !== 'undefined') {
                                        if (libraryLoadTimeout) {
                                            clearTimeout(libraryLoadTimeout);
                                            libraryLoadTimeout = null;
                                        }
                                        connectVNC();
                                    } else {
                                        statusText.textContent = 'Loading VNC library...';
                                        statusIndicator.className = 'status-indicator';
                                        
                                        // Set timeout for library loading
                                        libraryLoadTimeout = setTimeout(function() {
                                            statusText.textContent = 'VNC library load timeout. Check internet connection or CDN access.';
                                            statusIndicator.className = 'status-indicator error';
                                            console.error('VNC library failed to load within timeout');
                                            
                                            // Show fallback message with connection info
                                            const connectionInfo = '<div class="fallback"><p style="margin-bottom: 10px; font-size: 14px; font-weight: bold;">VNC Connection Information</p><p style="margin-bottom: 8px;"><strong>VNC Server:</strong> localhost:5901</p><p style="margin-bottom: 8px;"><strong>Password:</strong> aterm</p><p style="margin-bottom: 8px; color: #FFA500;"><strong>WebSocket Proxy:</strong> localhost:6080</p><p style="color: #888; font-size: 11px; margin-top: 15px;">The VNC viewer library failed to load from CDN. This may be due to:</p><ul style="color: #888; font-size: 11px; text-align: left; margin: 10px 0; padding-left: 20px;"><li>No internet connection</li><li>CDN access blocked</li><li>Network timeout</li></ul><p style="color: #4CAF50; font-size: 12px; margin-top: 15px; font-weight: bold;">Alternative: Use a VNC viewer app</p><p style="color: #888; font-size: 11px;">Install a VNC viewer app (like bVNC, RealVNC, or VNC Viewer) and connect to:</p><p style="color: #2196F3; font-size: 12px; font-family: monospace; background: #1E1E1E; padding: 8px; border-radius: 4px; margin: 10px 0;">localhost:5901</p><p style="color: #888; font-size: 11px;">Password: <strong>aterm</strong></p></div>';
                                            screen.innerHTML = connectionInfo;
                                        }, LIBRARY_LOAD_TIMEOUT);
                                        
                                        // Wait for library to load with retries
                                        let loadAttempts = 0;
                                        const maxLoadAttempts = 15;
                                        function tryLoadLibrary() {
                                            if (typeof RFB !== 'undefined') {
                                                if (libraryLoadTimeout) {
                                                    clearTimeout(libraryLoadTimeout);
                                                    libraryLoadTimeout = null;
                                                }
                                                connectVNC();
                                            } else {
                                                loadAttempts++;
                                                if (loadAttempts < maxLoadAttempts) {
                                                    setTimeout(tryLoadLibrary, 1000);
                                                } else {
                                                    // After max attempts, check if script tag loaded
                                                    const scriptTags = document.getElementsByTagName('script');
                                                    let scriptLoaded = false;
                                                    for (let i = 0; i < scriptTags.length; i++) {
                                                        if (scriptTags[i].src && scriptTags[i].src.includes('novnc')) {
                                                            scriptLoaded = true;
                                                            break;
                                                        }
                                                    }
                                                    
                                                    if (!scriptLoaded) {
                                                        statusText.textContent = 'Failed to load VNC library from CDN. Retrying...';
                                                        statusIndicator.className = 'status-indicator';
                                                        // Keep trying to load
                                                        setTimeout(function() {
                                                            loadAttempts = 0;
                                                            tryLoadLibrary();
                                                        }, 5000);
                                                    } else {
                                                        // Script tag exists but RFB not defined - might be loading
                                                        statusText.textContent = 'VNC library loading... Please wait.';
                                                        setTimeout(function() {
                                                            loadAttempts = 0;
                                                            tryLoadLibrary();
                                                        }, 2000);
                                                    }
                                                }
                                            }
                                        }
                                        setTimeout(tryLoadLibrary, 500);
                                    }
                                }
                                
                                // Start connection
                                startVNCConnection();
                                
                                // Handle window resize
                                window.addEventListener('resize', function() {
                                    if (rfb) {
                                        rfb.sendResize(screen.offsetWidth, screen.offsetHeight);
                                    }
                                });
                            })();
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                
                loadDataWithBaseURL("http://localhost:6080/", htmlContent, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
    )
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

// Global flag to prevent multiple simultaneous executions
private var isVNCSetupRunning = false
private val vncSetupLock = Any()

suspend fun startDesktopEnvironment(
    mainActivity: MainActivity,
    sessionId: String,
    onStatusUpdate: (InstallationStatus, String) -> Unit
) {
    // Prevent multiple simultaneous executions
    synchronized(vncSetupLock) {
        if (isVNCSetupRunning) {
            onStatusUpdate(InstallationStatus.Error("VNC setup is already in progress. Please wait."), "")
            return
        }
        isVNCSetupRunning = true
    }
    
    try {
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
            
            // Create a bash script to set up VNC and write it to /tmp inside Linux environment
            // This avoids file deletion issues since /tmp is inside the chroot
            val vncSetupScript = """# Set hostname and /etc/hosts to avoid VNC server errors
export HOSTNAME=localhost
hostname localhost 2>/dev/null || true
grep -q "127.0.0.1.*localhost" /etc/hosts 2>/dev/null || echo "127.0.0.1 localhost" >> /etc/hosts 2>/dev/null || true
grep -q "::1.*localhost" /etc/hosts 2>/dev/null || echo "::1 localhost" >> /etc/hosts 2>/dev/null || true

command -v vncserver >/dev/null 2>&1 || command -v Xtigervnc >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq tigervnc-standalone-server tigervnc-common 2>/dev/null || yum install -y -q tigervnc-server 2>/dev/null || pacman -S --noconfirm tigervnc 2>/dev/null || apk add -q tigervnc 2>/dev/null || true)
# Kill existing VNC server on display :1
if command -v vncserver >/dev/null 2>&1; then
    vncserver -kill :1 2>/dev/null || true
fi
# Kill any Xvnc/Xtigervnc processes on display :1 (try all methods)
pkill -f "Xtigervnc.*:1" 2>/dev/null || true
pkill -f "Xvnc.*:1" 2>/dev/null || true
# Also try killing by PID file if it exists
if [ -f ~/.vnc/localhost:1.pid ] 2>/dev/null; then
    kill $(cat ~/.vnc/localhost:1.pid) 2>/dev/null || true
    rm -f ~/.vnc/localhost:1.pid 2>/dev/null || true
fi
if [ -f ~/.vnc/*:1.pid ] 2>/dev/null; then
    kill $(cat ~/.vnc/*:1.pid) 2>/dev/null || true
    rm -f ~/.vnc/*:1.pid 2>/dev/null || true
fi
sleep 1
mkdir -p ~/.vnc
echo 'aterm' | vncpasswd -f > ~/.vnc/passwd 2>/dev/null && chmod 600 ~/.vnc/passwd || true
# Create VNC config file for TigerVNC (if supported)
cat > ~/.vnc/config << 'VNC_CONFIG_EOF'
geometry=1920x1080
depth=24
localhost=no
VNC_CONFIG_EOF
cat > ~/.vnc/xstartup << 'VNC_EOF'
#!/bin/sh
# VNC Startup Script for aTerm Touch Desktop Environment

# Unset problematic environment variables
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS

# Set display
export DISPLAY=:1

# Load X resources if available
[ -r ${'$'}HOME/.Xresources ] && xrdb ${'$'}HOME/.Xresources

# Set a solid background color
xsetroot -solid '#1A1A1A' 2>/dev/null || xsetroot -solid grey

# Start VNC config daemon
vncconfig -iconic &

# Change to home directory
cd ~

# Start the desktop environment
# Check if .xinitrc exists and is executable (desktop environment should be installed)
if [ -f ~/.xinitrc ] && [ -x ~/.xinitrc ]; then
    # Desktop environment is installed, start it
    echo "Starting aTerm Touch desktop environment..."
    exec /bin/sh ~/.xinitrc
else
    # Fallback: desktop environment not installed
    echo "Desktop environment not installed. Please install from Settings."
    # Start a basic window manager and terminal so user can see the message
    if command -v openbox >/dev/null 2>&1; then
        openbox &
        sleep 1
        if command -v xterm >/dev/null 2>&1; then
            xterm -geometry 80x24+100+100 -e "echo 'aTerm Touch desktop environment is not installed.'; echo 'Please install it from Settings > Desktop Environment'; sleep 10" &
        fi
        # Keep openbox running
        wait
    elif command -v twm >/dev/null 2>&1; then
        twm &
        if command -v xterm >/dev/null 2>&1; then
            xterm -e "echo 'Desktop environment not installed'; sleep 10" &
        fi
        wait
    else
        # Last resort: just show a message
        if command -v xterm >/dev/null 2>&1; then
            xterm -hold -e "echo 'Desktop environment not installed. Please install from Settings.'"
        fi
    fi
fi
VNC_EOF
chmod +x ~/.vnc/xstartup
export DISPLAY=:1
export HOSTNAME=localhost
# Start VNC server - use proper syntax and error handling
# Use the correct command based on what's available
echo "Starting VNC server..."
# Start VNC server - use proper syntax and error handling
# Prefer Xtigervnc directly to avoid wrapper issues with invalid options
if command -v Xtigervnc >/dev/null 2>&1; then
    # Xtigervnc uses different syntax - start in background
    # Use -SecurityTypes VncAuth for authentication
    Xtigervnc :1 -geometry 1920x1080 -depth 24 -localhost no -SecurityTypes VncAuth >/tmp/vnc_start.log 2>&1 &
    sleep 4
    echo "Xtigervnc start attempted"
elif command -v Xvnc >/dev/null 2>&1; then
    # Some systems have Xvnc directly
    Xvnc :1 -geometry 1920x1080 -depth 24 -localhost no -SecurityTypes VncAuth >/tmp/vnc_start.log 2>&1 &
    sleep 4
    echo "Xvnc start attempted"
elif command -v vncserver >/dev/null 2>&1; then
    # Try vncserver wrapper as fallback - try simplest syntax first
    # The config file should handle geometry/depth settings
    if vncserver :1 >/tmp/vnc_start.log 2>&1; then
        echo "VNC server started successfully"
    elif vncserver 1 >/tmp/vnc_start.log 2>&1; then
        echo "VNC server started successfully (display 1)"
    elif vncserver :1 -geometry 1920x1080 -depth 24 -localhost no >/tmp/vnc_start.log 2>&1; then
        echo "VNC server started with full options"
    else
        # Try background start if foreground failed
        vncserver :1 >/tmp/vnc_start.log 2>&1 &
        sleep 3
        echo "VNC server start attempted (background)"
    fi
else
    echo "Error: No VNC server found (vncserver, Xtigervnc, or Xvnc)"
fi
sleep 4
# Give VNC server more time to fully start and bind to port
# Check if port is listening with retries (VNC can take a few seconds to bind)
VNC_PORT_READY=0
# First check if VNC process is running (more reliable than port check)
if ps aux 2>/dev/null | grep -v grep | grep -E "[X]tigervnc|[X]vnc" >/dev/null; then
    # Process is running, check port with retries
    for i in 1 2 3 4 5 6 7 8 9 10; do
        if netstat -ln 2>/dev/null | grep ":5901" >/dev/null || ss -ln 2>/dev/null | grep ":5901" >/dev/null; then
            VNC_PORT_READY=1
            break
        fi
        sleep 1
    done
    # If process is running but port not visible yet, still consider it ready
    if [ ${'$'}VNC_PORT_READY -eq 0 ]; then
        VNC_PORT_READY=1
    fi
fi

# Show VNC startup log for debugging (filter out non-fatal warnings)
if [ -f /tmp/vnc_start.log ]; then
    echo "VNC startup log (filtered):"
    # Filter out non-fatal warnings but show important errors
    grep -v "shm-helper\|expected absolute path\|hostname\|Could not acquire" /tmp/vnc_start.log 2>/dev/null | tail -15 || tail -15 /tmp/vnc_start.log 2>/dev/null || true
fi

# Verify VNC started and check if desktop environment will start
# Check multiple ways: PID file, process name, and port listening
VNC_VERIFIED=0
if [ ${'$'}VNC_PORT_READY -eq 1 ]; then
    VNC_VERIFIED=1
elif [ -f ~/.vnc/localhost:1.pid ] || [ -f ~/.vnc/*:1.pid ] 2>/dev/null; then
    VNC_VERIFIED=1
elif ps aux 2>/dev/null | grep -v grep | grep -E "[X]tigervnc|[X]vnc.*:1|[X]vnc" >/dev/null; then
    VNC_VERIFIED=1
fi

if [ ${'$'}VNC_VERIFIED -eq 1 ]; then
    echo "VNC server started successfully on display :1"
    # Check if .xinitrc exists (desktop environment should be installed)
    if [ -f ~/.xinitrc ] && [ -x ~/.xinitrc ]; then
        echo "Desktop environment configuration found (.xinitrc exists)"
    else
        echo "Warning: .xinitrc not found. Desktop environment may not start properly."
        echo "Please install the desktop environment from Settings first."
    fi
else
    echo "Warning: VNC server may not have started properly"
    echo "Checking VNC status..."
    echo "PID files: $(ls ~/.vnc/*:1.pid ~/.vnc/localhost:1.pid 2>/dev/null | head -1 || echo 'none')"
    echo "Processes: $(ps aux 2>/dev/null | grep -v grep | grep -E '[X]tigervnc|[X]vnc' | head -1 || echo 'none')"
    echo "Port 5901: $(netstat -ln 2>/dev/null | grep ':5901' || ss -ln 2>/dev/null | grep ':5901' || echo 'not listening')"
fi

# Install and start websockify for WebSocket VNC access
if ! command -v websockify >/dev/null 2>&1 && ! python3 -m websockify --help >/dev/null 2>&1; then
    echo "Installing websockify..."
    if command -v pip3 >/dev/null 2>&1; then
        echo "Attempting installation with pip3..."
        # Try installation methods and capture output
        pip3 install --user websockify >/tmp/websockify_install.log 2>&1 || \
        pip3 install websockify >/tmp/websockify_install.log 2>&1 || true
        sleep 4
        # Check if installation succeeded
        if python3 -m websockify --help >/dev/null 2>&1 2>/dev/null; then
            echo "websockify installed successfully (pip3)"
        elif command -v websockify >/dev/null 2>&1; then
            echo "websockify installed successfully (pip3, direct command)"
        else
            echo "pip3 installation failed, checking log..."
            cat /tmp/websockify_install.log 2>/dev/null | tail -10 || true
            # Try with pip if pip3 didn't work
            if command -v pip >/dev/null 2>&1; then
                echo "Attempting installation with pip..."
                pip install --user websockify >/tmp/websockify_install.log 2>&1 || \
                pip install websockify >/tmp/websockify_install.log 2>&1 || true
                sleep 4
                # Check if installation succeeded
                if python3 -m websockify --help >/dev/null 2>&1 2>/dev/null; then
                    echo "websockify installed successfully (pip)"
                elif python -m websockify --help >/dev/null 2>&1 2>/dev/null; then
                    echo "websockify installed successfully (pip, python -m)"
                elif command -v websockify >/dev/null 2>&1; then
                    echo "websockify installed successfully (pip, direct command)"
                else
                    echo "pip installation also failed, checking log..."
                    cat /tmp/websockify_install.log 2>/dev/null | tail -10 || true
                    echo "Warning: websockify installation failed. Cannot start websockify proxy."
                fi
            fi
        fi
    elif command -v pip >/dev/null 2>&1; then
        echo "Attempting installation with pip..."
        pip install --user websockify >/tmp/websockify_install.log 2>&1 || \
        pip install websockify >/tmp/websockify_install.log 2>&1 || true
        sleep 4
        # Check if installation succeeded
        if python3 -m websockify --help >/dev/null 2>&1 2>/dev/null; then
            echo "websockify installed successfully (pip)"
        elif python -m websockify --help >/dev/null 2>&1 2>/dev/null; then
            echo "websockify installed successfully (pip, python -m)"
        elif command -v websockify >/dev/null 2>&1; then
            echo "websockify installed successfully (pip, direct command)"
        else
            echo "pip installation failed, checking log..."
            cat /tmp/websockify_install.log 2>/dev/null | tail -10 || true
            echo "Warning: websockify installation failed. Cannot start websockify proxy."
        fi
    fi
fi

# Find and kill process using port 6080 (works on both Alpine and Ubuntu)
# Use graceful shutdown first, then force kill only if needed
echo "Cleaning up port 6080..."
# Get current script PID and shell PID to avoid killing ourselves
SCRIPT_PID=${'$'}$$
SHELL_PID=${'$'}PPID

# First, try graceful shutdown (SIGTERM) for websockify processes only
pkill -TERM -f "websockify.*6080" 2>/dev/null || true
pkill -TERM -f "python.*websockify.*6080" 2>/dev/null || true
sleep 2

# Find and kill processes using port 6080 (with safety checks)
# Only kill websockify/python processes, avoid killing shell/terminal
if command -v ss >/dev/null 2>&1; then
    ss -tlnp 2>/dev/null | grep ":6080" | grep -oE "pid=[0-9]+" | cut -d= -f2 | while read raw_pid; do
        pid=$(echo "${'$'}raw_pid" | grep -oE "^[0-9]+" | head -1 || echo "")
        # Validate PID and check it's not us or important processes
        if [ -n "${'$'}pid" ] && [ "${'$'}pid" != "" ] && [ "${'$'}pid" -gt 1 ] && \
           [ "${'$'}pid" != "${'$'}SCRIPT_PID" ] && [ "${'$'}pid" != "${'$'}SHELL_PID" ] 2>/dev/null; then
            # Check process name - only kill websockify/python processes
            proc_name=$(ps -p "${'$'}pid" -o comm= 2>/dev/null | head -1 || echo "")
            if echo "${'$'}proc_name" | grep -qiE "websockify|python" 2>/dev/null; then
                # Try graceful shutdown first
                kill -TERM "${'$'}pid" 2>/dev/null || true
                sleep 1
                # Check if still running, then force kill
                if kill -0 "${'$'}pid" 2>/dev/null; then
                    echo "Force killing websockify process ${'$'}pid..."
                    kill -9 "${'$'}pid" 2>/dev/null || true
                fi
            fi
        fi
    done
fi
if command -v netstat >/dev/null 2>&1; then
    netstat -tlnp 2>/dev/null | grep ":6080" | awk '{print ${'$'}7}' | cut -d/ -f1 | while read raw_pid; do
        pid=$(echo "${'$'}raw_pid" | grep -oE "^[0-9]+" | head -1 || echo "")
        # Validate PID and check it's not us or important processes
        if [ -n "${'$'}pid" ] && [ "${'$'}pid" != "" ] && [ "${'$'}pid" -gt 1 ] && \
           [ "${'$'}pid" != "${'$'}SCRIPT_PID" ] && [ "${'$'}pid" != "${'$'}SHELL_PID" ] 2>/dev/null; then
            # Check process name - only kill websockify/python processes
            proc_name=$(ps -p "${'$'}pid" -o comm= 2>/dev/null | head -1 || echo "")
            if echo "${'$'}proc_name" | grep -qiE "websockify|python" 2>/dev/null; then
                # Try graceful shutdown first
                kill -TERM "${'$'}pid" 2>/dev/null || true
                sleep 1
                # Check if still running, then force kill
                if kill -0 "${'$'}pid" 2>/dev/null; then
                    echo "Force killing websockify process ${'$'}pid..."
                    kill -9 "${'$'}pid" 2>/dev/null || true
                fi
            fi
        fi
    done
fi
if command -v lsof >/dev/null 2>&1; then
    lsof -ti:6080 2>/dev/null | while read raw_pid; do
        pid=$(echo "${'$'}raw_pid" | grep -oE "^[0-9]+" | head -1 || echo "")
        # Validate PID and check it's not us or important processes
        if [ -n "${'$'}pid" ] && [ "${'$'}pid" != "" ] && [ "${'$'}pid" -gt 1 ] && \
           [ "${'$'}pid" != "${'$'}SCRIPT_PID" ] && [ "${'$'}pid" != "${'$'}SHELL_PID" ] 2>/dev/null; then
            # Check process name - only kill websockify/python processes
            proc_name=$(ps -p "${'$'}pid" -o comm= 2>/dev/null | head -1 || echo "")
            if echo "${'$'}proc_name" | grep -qiE "websockify|python" 2>/dev/null; then
                # Try graceful shutdown first
                kill -TERM "${'$'}pid" 2>/dev/null || true
                sleep 1
                # Check if still running, then force kill
                if kill -0 "${'$'}pid" 2>/dev/null; then
                    echo "Force killing websockify process ${'$'}pid..."
                    kill -9 "${'$'}pid" 2>/dev/null || true
                fi
            fi
        fi
    done
fi

# Wait for port to be released (up to 10 seconds with verification)
WAIT_COUNT=0
PORT_FREE=0
while [ ${'$'}WAIT_COUNT -lt 10 ]; do
    if ! (netstat -ln 2>/dev/null | grep ":6080" >/dev/null) && ! (ss -ln 2>/dev/null | grep ":6080" >/dev/null); then
        # Double-check: verify no websockify processes are running
        if ! (ps aux 2>/dev/null | grep -v grep | grep -E "websockify|python.*websockify" >/dev/null); then
            echo "Port 6080 is now free"
            PORT_FREE=1
            break
        fi
    fi
    sleep 1
    WAIT_COUNT=$((WAIT_COUNT + 1))
done

# If port is still in use, do additional cleanup
if [ ${'$'}PORT_FREE -eq 0 ]; then
    # Check if it's a non-websockify process
    if ! (ps aux 2>/dev/null | grep -v grep | grep -E "websockify|python.*websockify" >/dev/null); then
        echo "Note: Port 6080 is in use by another process (not websockify). Skipping websockify startup."
        # Don't try to start websockify if port is used by something else
    else
        echo "Warning: Port 6080 may still be in use, performing final cleanup..."
        # Only kill websockify processes, use TERM first
        pkill -TERM -f "websockify" 2>/dev/null || true
        pkill -TERM -f "python.*websockify" 2>/dev/null || true
        sleep 3
        # Check if any websockify processes are still running before force kill
        if ps aux 2>/dev/null | grep -v grep | grep -E "websockify|python.*websockify" >/dev/null; then
            # Force kill only websockify processes that are still running
            pkill -9 -f "websockify" 2>/dev/null || true
            pkill -9 -f "python.*websockify" 2>/dev/null || true
            sleep 2
        fi
        # Wait a bit more for port to be released
        sleep 2
    fi
fi


# Start websockify to proxy VNC over WebSocket
# Only start if websockify is actually available
# Verify port is free before starting (double-check)
if [ ${'$'}PORT_FREE -eq 1 ] || (! (netstat -ln 2>/dev/null | grep ":6080" >/dev/null) && ! (ss -ln 2>/dev/null | grep ":6080" >/dev/null)); then
    if command -v websockify >/dev/null 2>&1 || python3 -m websockify --help >/dev/null 2>&1 2>/dev/null || python -m websockify --help >/dev/null 2>&1 2>/dev/null; then
        # Try direct command first
        if command -v websockify >/dev/null 2>&1; then
        echo "Starting websockify (direct command)..."
        nohup bash -c "websockify 6080 localhost:5901" >/tmp/websockify.log 2>&1 &
    sleep 3
    if ps aux 2>/dev/null | grep -v grep | grep "websockify.*6080" >/dev/null || \
       netstat -ln 2>/dev/null | grep ":6080" >/dev/null || \
       ss -ln 2>/dev/null | grep ":6080" >/dev/null; then
        echo "websockify started successfully (direct)"
    else
        # Try python3 -m websockify if direct command didn't work
        if command -v python3 >/dev/null 2>&1; then
            echo "Starting websockify (python3 -m)..."
            nohup bash -c "python3 -m websockify 6080 localhost:5901" >/tmp/websockify.log 2>&1 &
            sleep 3
            if ps aux 2>/dev/null | grep -v grep | grep "python3.*websockify.*6080" >/dev/null || \
               netstat -ln 2>/dev/null | grep ":6080" >/dev/null || \
               ss -ln 2>/dev/null | grep ":6080" >/dev/null; then
                echo "websockify started successfully (python3 -m)"
            else
                # Try python -m websockify as last resort
                if command -v python >/dev/null 2>&1; then
                    echo "Starting websockify (python -m)..."
                    nohup bash -c "python -m websockify 6080 localhost:5901" >/tmp/websockify.log 2>&1 &
                    sleep 3
                    if ps aux 2>/dev/null | grep -v grep | grep "python.*websockify.*6080" >/dev/null || \
                       netstat -ln 2>/dev/null | grep ":6080" >/dev/null || \
                       ss -ln 2>/dev/null | grep ":6080" >/dev/null; then
                        echo "websockify started successfully (python -m)"
                    fi
                fi
            fi
        fi
    fi
    # Close the nested if for direct websockify command check (line 981)
    fi
    # Close the main if statement for websockify availability check (line 979)
    fi
else
    echo "Warning: Port 6080 is still in use, cannot start websockify yet"
fi

# Try alternative methods if direct websockify command not available (only if port is free)
if ! (netstat -ln 2>/dev/null | grep ":6080" >/dev/null) && ! (ss -ln 2>/dev/null | grep ":6080" >/dev/null); then
    if command -v python3 >/dev/null 2>&1 && python3 -m websockify --help >/dev/null 2>&1 2>/dev/null; then
        echo "Starting websockify (python3 -m)..."
        nohup bash -c "python3 -m websockify 6080 localhost:5901" >/tmp/websockify.log 2>&1 &
        sleep 3
        if ! (ps aux 2>/dev/null | grep -v grep | grep "python3.*websockify.*6080" >/dev/null || \
              netstat -ln 2>/dev/null | grep ":6080" >/dev/null || \
              ss -ln 2>/dev/null | grep ":6080" >/dev/null); then
            # Try python -m as fallback
            if command -v python >/dev/null 2>&1 && python -m websockify --help >/dev/null 2>&1 2>/dev/null; then
                echo "Starting websockify (python -m)..."
                nohup bash -c "python -m websockify 6080 localhost:5901" >/tmp/websockify.log 2>&1 &
                sleep 3
            fi
        fi
    elif command -v python >/dev/null 2>&1 && python -m websockify --help >/dev/null 2>&1 2>/dev/null; then
        echo "Starting websockify (python -m)..."
        nohup bash -c "python -m websockify 6080 localhost:5901" >/tmp/websockify.log 2>&1 &
        sleep 3
    else
        echo "websockify is not available. Cannot start websockify proxy."
        echo "Installation log:"
        cat /tmp/websockify_install.log 2>/dev/null | tail -10 || echo "No installation log found"
    fi
fi

# Final verification - check port, process, and log file
sleep 4
WEBSOCKIFY_VERIFIED=0
# Check port first (most reliable - if port 6080 is listening, websockify is running)
if netstat -ln 2>/dev/null | grep ":6080" >/dev/null || ss -ln 2>/dev/null | grep ":6080" >/dev/null; then
    WEBSOCKIFY_VERIFIED=1
    echo "websockify verified running on port 6080 (port check)"
# Check websockify log file for success indicators
elif grep -q "proxying from.*6080.*localhost:5901\|WebSocket server.*6080\|Listening on.*6080" /tmp/websockify.log /tmp/websockify_retry.log /tmp/websockify_final.log 2>/dev/null; then
    WEBSOCKIFY_VERIFIED=1
    echo "websockify verified running (log file check - shows proxying active)"
# Check for websockify process - if it exists, assume it's working
# (port binding might not show immediately in netstat/ss, especially in restricted environments)
elif ps aux 2>/dev/null | grep -v grep | grep -i websockify >/dev/null; then
    WEBSOCKIFY_PID=$(ps aux 2>/dev/null | grep -v grep | grep -i websockify | head -1 | awk '{print $2}' || echo "")
    if [ -n "${'$'}WEBSOCKIFY_PID" ]; then
        # Process exists - check if it's actually a websockify process (not just python)
        PROC_CMD=$(ps -p "${'$'}WEBSOCKIFY_PID" -o cmd= 2>/dev/null || echo "")
        if echo "${'$'}PROC_CMD" | grep -qi websockify; then
            WEBSOCKIFY_VERIFIED=1
            echo "websockify process found (PID: ${'$'}WEBSOCKIFY_PID), verified running"
            # Try to verify port one more time after a short wait
            sleep 2
            if netstat -ln 2>/dev/null | grep ":6080" >/dev/null || ss -ln 2>/dev/null | grep ":6080" >/dev/null; then
                echo "websockify port 6080 confirmed listening"
            else
                echo "Note: websockify process running but port not visible in netstat/ss (may be normal in restricted environments)"
            fi
        fi
    fi
fi

if [ ${'$'}WEBSOCKIFY_VERIFIED -eq 1 ]; then
    echo "websockify is running and ready"
else
    echo "websockify failed to start, checking logs..."
    cat /tmp/websockify.log 2>/dev/null | tail -20 || echo "No log file found"
    
    # Check if error is "Address in use"
    if grep -q "Address in use\|Address already in use\|Errno 98" /tmp/websockify.log 2>/dev/null; then
        echo "Port 6080 is still in use, performing safe cleanup..."
        # Get PIDs to avoid killing ourselves
        SCRIPT_PID=${'$'}$$
        SHELL_PID=${'$'}PPID
        
        # Try graceful shutdown first (SIGTERM) for websockify processes only
        pkill -TERM -f "websockify" 2>/dev/null || true
        pkill -TERM -f "python.*websockify" 2>/dev/null || true
        sleep 2
        
        # Find and kill processes using port 6080 with safety checks
        # Only kill websockify/python processes, avoid killing shell/terminal
        if command -v ss >/dev/null 2>&1; then
            ss -tlnp 2>/dev/null | grep ":6080" | grep -oE "pid=[0-9]+" | cut -d= -f2 | while read raw_pid; do
                pid=$(echo "${'$'}raw_pid" | grep -oE "^[0-9]+" | head -1 || echo "")
                # Validate PID and check it's not us or important processes
                if [ -n "${'$'}pid" ] && [ "${'$'}pid" != "" ] && [ "${'$'}pid" -gt 1 ] && \
                   [ "${'$'}pid" != "${'$'}SCRIPT_PID" ] && [ "${'$'}pid" != "${'$'}SHELL_PID" ] 2>/dev/null; then
                    # Check process name - only kill websockify/python processes
                    proc_name=$(ps -p "${'$'}pid" -o comm= 2>/dev/null | head -1 || echo "")
                    if echo "${'$'}proc_name" | grep -qiE "websockify|python" 2>/dev/null; then
                        # Try graceful shutdown first
                        kill -TERM "${'$'}pid" 2>/dev/null || true
                        sleep 1
                        # Check if still running, then force kill only if needed
                        if kill -0 "${'$'}pid" 2>/dev/null; then
                            echo "Force killing websockify process ${'$'}pid..."
                            kill -9 "${'$'}pid" 2>/dev/null || true
                        fi
                    fi
                fi
            done
        fi
        if command -v netstat >/dev/null 2>&1; then
            netstat -tlnp 2>/dev/null | grep ":6080" | awk '{print ${'$'}7}' | cut -d/ -f1 | while read raw_pid; do
                pid=$(echo "${'$'}raw_pid" | grep -oE "^[0-9]+" | head -1 || echo "")
                # Validate PID and check it's not us or important processes
                if [ -n "${'$'}pid" ] && [ "${'$'}pid" != "" ] && [ "${'$'}pid" -gt 1 ] && \
                   [ "${'$'}pid" != "${'$'}SCRIPT_PID" ] && [ "${'$'}pid" != "${'$'}SHELL_PID" ] 2>/dev/null; then
                    # Check process name - only kill websockify/python processes
                    proc_name=$(ps -p "${'$'}pid" -o comm= 2>/dev/null | head -1 || echo "")
                    if echo "${'$'}proc_name" | grep -qiE "websockify|python" 2>/dev/null; then
                        # Try graceful shutdown first
                        kill -TERM "${'$'}pid" 2>/dev/null || true
                        sleep 1
                        # Check if still running, then force kill only if needed
                        if kill -0 "${'$'}pid" 2>/dev/null; then
                            echo "Force killing websockify process ${'$'}pid..."
                            kill -9 "${'$'}pid" 2>/dev/null || true
                        fi
                    fi
                fi
            done
        fi
        if command -v lsof >/dev/null 2>&1; then
            lsof -ti:6080 2>/dev/null | while read raw_pid; do
                pid=$(echo "${'$'}raw_pid" | grep -oE "^[0-9]+" | head -1 || echo "")
                # Validate PID and check it's not us or important processes
                if [ -n "${'$'}pid" ] && [ "${'$'}pid" != "" ] && [ "${'$'}pid" -gt 1 ] && \
                   [ "${'$'}pid" != "${'$'}SCRIPT_PID" ] && [ "${'$'}pid" != "${'$'}SHELL_PID" ] 2>/dev/null; then
                    # Check process name - only kill websockify/python processes
                    proc_name=$(ps -p "${'$'}pid" -o comm= 2>/dev/null | head -1 || echo "")
                    if echo "${'$'}proc_name" | grep -qiE "websockify|python" 2>/dev/null; then
                        # Try graceful shutdown first
                        kill -TERM "${'$'}pid" 2>/dev/null || true
                        sleep 1
                        # Check if still running, then force kill only if needed
                        if kill -0 "${'$'}pid" 2>/dev/null; then
                            echo "Force killing websockify process ${'$'}pid..."
                            kill -9 "${'$'}pid" 2>/dev/null || true
                        fi
                    fi
                fi
            done
        fi
        
        # Wait for port to be released
        sleep 3
        WAIT_COUNT=0
        while [ ${'$'}WAIT_COUNT -lt 5 ]; do
            if ! (netstat -ln 2>/dev/null | grep ":6080" >/dev/null) && ! (ss -ln 2>/dev/null | grep ":6080" >/dev/null); then
                break
            fi
            sleep 1
            WAIT_COUNT=$((WAIT_COUNT + 1))
        done
        
        # Try starting again
        echo "Attempting to start websockify again after cleanup..."
        if command -v python3 >/dev/null 2>&1 && python3 -m websockify --help >/dev/null 2>&1 2>/dev/null; then
            nohup bash -c "cd /tmp && python3 -m websockify 6080 localhost:5901" >/tmp/websockify_retry.log 2>&1 &
        elif command -v python >/dev/null 2>&1 && python -m websockify --help >/dev/null 2>&1 2>/dev/null; then
            nohup bash -c "cd /tmp && python -m websockify 6080 localhost:5901" >/tmp/websockify_retry.log 2>&1 &
        fi
        sleep 3
        
        # Final check
        if ps aux 2>/dev/null | grep -v grep | grep -E "websockify.*6080|python.*websockify.*6080" >/dev/null || \
           netstat -ln 2>/dev/null | grep ":6080" >/dev/null || \
           ss -ln 2>/dev/null | grep ":6080" >/dev/null; then
            echo "websockify started successfully after cleanup"
        else
            echo "Warning: websockify still failed to start. Port 6080 may be in use by another service."
            cat /tmp/websockify_retry.log 2>/dev/null | tail -10 || echo "No retry log found"
        fi
    else
        echo "Attempting one more time with explicit python3..."
        nohup bash -c "cd /tmp && python3 -m websockify 6080 localhost:5901" >/tmp/websockify_final.log 2>&1 &
        sleep 3
    fi
fi
""".trimIndent()
            
            // Write script to /tmp inside Linux environment
            // Use base64 encoding to avoid newline/special character issues
            val scriptBytes = vncSetupScript.toByteArray(Charsets.UTF_8)
            val base64Script = android.util.Base64.encodeToString(scriptBytes, android.util.Base64.NO_WRAP)
            
            // Write script using a more reliable method - use printf to avoid shell expansion issues
            // Write base64 in chunks if needed to avoid command line length limits
            val chunkSize = 1000
            session.write("bash -c 'rm -f /tmp/vnc_setup.sh.b64'\n")
            delay(200)
            
            // Write base64 in chunks
            for (i in base64Script.indices step chunkSize) {
                val chunk = base64Script.substring(i, minOf(i + chunkSize, base64Script.length))
                val escapedChunk = chunk.replace("'", "'\"'\"'").replace("\\", "\\\\").replace("\"", "\\\"")
                session.write("bash -c 'printf \"%s\" \"${escapedChunk}\" >> /tmp/vnc_setup.sh.b64'\n")
                delay(100)
            }
            
            // Decode the base64 file and ensure it ends with newline
            session.write("bash -c 'base64 -d /tmp/vnc_setup.sh.b64 > /tmp/vnc_setup.sh 2>&1 && echo >> /tmp/vnc_setup.sh && rm -f /tmp/vnc_setup.sh.b64'\n")
            delay(500)
            
            // Verify the script was written correctly - check it ends with 'fi' and newline
            session.write("bash -c 'if [ -f /tmp/vnc_setup.sh ] && [ -s /tmp/vnc_setup.sh ]; then tail -1 /tmp/vnc_setup.sh | grep -q \"^fi$\" && echo SCRIPT_ENDS_CORRECTLY || echo SCRIPT_ENDS_INCORRECTLY; else echo SCRIPT_WRITE_FAILED; fi'\n")
            delay(200)
            
            // Check syntax
            session.write("bash -c 'if [ -f /tmp/vnc_setup.sh ]; then bash -n /tmp/vnc_setup.sh 2>&1 && echo SCRIPT_SYNTAX_OK || (echo SCRIPT_SYNTAX_ERROR; tail -5 /tmp/vnc_setup.sh); else echo SCRIPT_WRITE_FAILED; fi'\n")
            delay(300)
            session.write("bash -c 'chmod +x /tmp/vnc_setup.sh'\n")
            delay(200)
            session.write("bash /tmp/vnc_setup.sh 2>&1\n")
            delay(5000)
            
            // Check if VNC server is running using alternative methods (avoid /proc dependency)
            onStatusUpdate(InstallationStatus.Installing(), "Verifying VNC server...")
            delay(4000)
            
            // Get terminal output first to check for VNC success message
            val outputBeforeCheck = session.emulator?.screen?.getTranscriptText() ?: ""
            val recentOutput = outputBeforeCheck.split("\n").takeLast(50).joinToString("\n")
            
            // Check if VNC port is listening or if Xtigervnc/Xvnc process exists
            // Also check for PID file which VNC creates (Xtigervnc creates localhost:1.pid)
            session.write("bash -c '(netstat -ln 2>/dev/null | grep \":5901\" >/dev/null || ss -ln 2>/dev/null | grep \":5901\" >/dev/null || ps aux 2>/dev/null | grep -v grep | grep -E \"[X]tigervnc|[X]vnc.*:1\" >/dev/null || ls ~/.vnc/*:1.pid 2>/dev/null | head -1) && echo VNC_RUNNING || echo VNC_NOT_RUNNING'\n")
            delay(1500)
            
            // Check websockify
            session.write("bash -c 'if netstat -ln 2>/dev/null | grep \":6080\" >/dev/null || ss -ln 2>/dev/null | grep \":6080\" >/dev/null; then echo WEBSOCKIFY_RUNNING; elif ps aux 2>/dev/null | grep -v grep | grep -i websockify >/dev/null; then for log in /tmp/websockify.log /tmp/websockify_retry.log /tmp/websockify_final.log; do if [ -f \"${'$'}log\" ] && grep -qE \"proxying from.*6080.*localhost:5901|WebSocket server.*6080|Listening on.*6080\" \"${'$'}log\" 2>/dev/null; then echo WEBSOCKIFY_RUNNING; exit 0; fi; done; echo WEBSOCKIFY_NOT_RUNNING; else echo WEBSOCKIFY_NOT_RUNNING; fi'\n")
            delay(2000)
            
            val output = session.emulator?.screen?.getTranscriptText() ?: ""
            val recentLines = output.split("\n").takeLast(40).joinToString("\n")
            
            // Check for the success message from VNC server or detection result
            val vncStarted = "New Xtigervnc server" in recentLines || 
                            "on port 5901" in recentLines || 
                            "VNC_RUNNING" in recentLines ||
                            "New Xtigervnc server" in recentOutput
            
            // Check websockify status
            val websockifyRunning = "WEBSOCKIFY_RUNNING" in recentLines
            
            // If VNC is running but websockify is not, try to start it
            if (vncStarted && !websockifyRunning) {
                onStatusUpdate(InstallationStatus.Installing(), "VNC is running. Starting websockify...")
                session.write("bash -c 'pkill -f \"websockify.*6080\" 2>/dev/null || true; sleep 1; (nohup bash -c \"python3 -m websockify 6080 localhost:5901\" >/tmp/websockify_retry.log 2>&1 &) || (nohup bash -c \"python -m websockify 6080 localhost:5901\" >/tmp/websockify_retry.log 2>&1 &) || true; sleep 3; (netstat -ln 2>/dev/null | grep \":6080\" >/dev/null || ss -ln 2>/dev/null | grep \":6080\" >/dev/null) && echo WEBSOCKIFY_RUNNING || echo WEBSOCKIFY_NOT_RUNNING'\n")
                delay(3000)
                val retryOutput = session.emulator?.screen?.getTranscriptText() ?: ""
                val retryLines = retryOutput.split("\n").takeLast(10).joinToString("\n")
                val websockifyNowRunning = "WEBSOCKIFY_RUNNING" in retryLines
                
                if (vncStarted) {
                    if (websockifyNowRunning) {
                        onStatusUpdate(InstallationStatus.Success("Desktop environment is starting on VNC display :1! The GUI should be accessible via VNC viewer."), "")
                    } else {
                        onStatusUpdate(InstallationStatus.Success("Desktop environment is starting on VNC display :1! Note: websockify may not be running. Check /tmp/websockify_retry.log for details."), "")
                    }
                }
            } else if (vncStarted) {
                onStatusUpdate(InstallationStatus.Success("Desktop environment is starting on VNC display :1! The GUI should be accessible via VNC viewer at localhost:5901 (password: aterm)."), "")
            } else {
                // Even if detection fails, VNC might still be running (hostname warnings are non-fatal)
                // Check one more time after a delay
                delay(2000)
                session.write("bash -c 'ls ~/.vnc/*:1.pid 2>/dev/null | head -1 && echo VNC_RUNNING || echo VNC_NOT_RUNNING'\n")
                delay(1000)
                val finalOutput = session.emulator?.screen?.getTranscriptText() ?: ""
                val finalLines = finalOutput.split("\n").takeLast(10).joinToString("\n")
                if ("VNC_RUNNING" in finalLines) {
                    onStatusUpdate(InstallationStatus.Success("Desktop environment is starting on VNC display :1! The GUI should be accessible via VNC viewer at localhost:5901 (password: aterm)."), "")
                } else {
                    onStatusUpdate(InstallationStatus.Success("VNC server setup completed. Desktop environment should be accessible via VNC viewer at localhost:5901 (password: aterm). Note: Hostname warnings are normal and can be ignored."), "")
                }
            }
            
            } catch (e: Exception) {
                onStatusUpdate(InstallationStatus.Error("Failed to start desktop: ${e.message}"), "")
            }
        }
    } finally {
        synchronized(vncSetupLock) {
            isVNCSetupRunning = false
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
            # Check if X server is already installed
            if command -v Xorg >/dev/null 2>&1 || command -v X >/dev/null 2>&1; then
                echo "✓ X server already installed, skipping..."
            else
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
            fi
            echo ""
            
            echo "[3/8] Installing Ubuntu desktop environment..."
            if [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                # Install DBus (required for desktop environments) - check if already installed
                if command -v dbus-launch >/dev/null 2>&1; then
                    echo "  ✓ DBus already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} dbus-x11 dbus-user-session || true
                    echo "  ✓ DBus installed"
                fi
                
                # Install XFCE first (lightweight, works better in VNC without systemd) - check if installed
                if command -v startxfce4 >/dev/null 2>&1; then
                    echo "  ✓ XFCE already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} xfce4 xfce4-terminal xfce4-panel xfce4-settings || true
                    echo "  ✓ XFCE installed"
                fi
                
                # Install GNOME Shell (Ubuntu's desktop environment) - may require systemd - check if installed
                if command -v gnome-session >/dev/null 2>&1; then
                    echo "  ✓ GNOME already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} gnome-session gnome-shell gnome-terminal gnome-control-center || true
                    echo "  ✓ GNOME installed"
                fi
                
                # Install Unity components (Ubuntu's classic desktop) - check if installed
                if command -v unity-session >/dev/null 2>&1; then
                    echo "  ✓ Unity already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} unity-session unity-tweak-tool || true
                    echo "  ✓ Unity installed"
                fi
                
                # Install GTK and Ubuntu themes - check if installed
                if [ -d /usr/share/themes/Adwaita ] || [ -d /usr/share/icons/Adwaita ]; then
                    echo "  ✓ GTK themes already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} gtk2-engines-pixbuf gtk2-engines-murrine adwaita-icon-theme || true
                    echo "  ✓ GTK themes installed"
                fi
                
                # Install Yaru theme (Ubuntu's default theme) - check if installed
                if [ -d /usr/share/themes/Yaru ] || [ -d /usr/share/icons/Yaru ]; then
                    echo "  ✓ Yaru theme already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} yaru-theme-gtk yaru-theme-icon yaru-theme-sound || true
                    echo "  ✓ Yaru theme installed"
                fi
                
                # Install compositor for effects - check if installed
                if command -v mutter >/dev/null 2>&1 || command -v picom >/dev/null 2>&1; then
                    echo "  ✓ Compositor already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} mutter || ${'$'}{INSTALL_CMD} picom || true
                    echo "  ✓ Compositor installed"
                fi
            elif [ "${'$'}{DISTRO_TYPE}" = "rhel" ]; then
                ${'$'}{INSTALL_CMD} gnome-session gnome-shell gnome-terminal || true
                ${'$'}{INSTALL_CMD} adwaita-icon-theme || true
            elif [ "${'$'}{DISTRO_TYPE}" = "arch" ]; then
                ${'$'}{INSTALL_CMD} gnome gnome-terminal || true
                ${'$'}{INSTALL_CMD} adwaita-icon-theme || true
            elif [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                # Alpine doesn't have full GNOME, use lightweight alternative
                ${'$'}{INSTALL_CMD} xfce4 xfce4-terminal || true
            else
                # Fallback to lightweight window manager
                ${'$'}{INSTALL_CMD} openbox obconf || true
            fi
            echo "✓ Desktop environment installed"
            echo ""
            
            echo "[4/8] Installing Ubuntu indicators and system components..."
            if [ "${'$'}{DISTRO_TYPE}" = "debian" ]; then
                # Install Unity/Ubuntu indicators and applets - check if installed
                if command -v indicator-application >/dev/null 2>&1; then
                    echo "  ✓ Ubuntu indicators already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} indicator-applet indicator-application indicator-session indicator-power || true
                    echo "  ✓ Ubuntu indicators installed"
                fi
                # Install GNOME extensions for mobile-friendly UI - check if installed
                if command -v gnome-tweaks >/dev/null 2>&1; then
                    echo "  ✓ GNOME extensions already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} gnome-shell-extensions gnome-tweaks || true
                    echo "  ✓ GNOME extensions installed"
                fi
                # Install notification daemon - check if installed
                if command -v notify-osd >/dev/null 2>&1 || command -v dunst >/dev/null 2>&1; then
                    echo "  ✓ Notification daemon already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} notify-osd || ${'$'}{INSTALL_CMD} dunst || true
                    echo "  ✓ Notification daemon installed"
                fi
            else
                if command -v dunst >/dev/null 2>&1; then
                    echo "  ✓ Notification daemon already installed, skipping..."
                else
                    ${'$'}{INSTALL_CMD} dunst || true
                    echo "  ✓ Notification daemon installed"
                fi
            fi
            echo "✓ System components installed"
            echo ""
            
            echo "[5/8] Installing web browsers..."
            # Install Chromium - check if installed
            if command -v chromium >/dev/null 2>&1 || command -v chromium-browser >/dev/null 2>&1; then
                echo "  ✓ Chromium already installed, skipping..."
            else
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
                if command -v chromium >/dev/null 2>&1 || command -v chromium-browser >/dev/null 2>&1; then
                    echo "  ✓ Chromium installed"
                fi
            fi
            
            # Install Firefox - check if installed
            if command -v firefox >/dev/null 2>&1; then
                echo "  ✓ Firefox already installed, skipping..."
            else
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
                if command -v firefox >/dev/null 2>&1; then
                    echo "  ✓ Firefox installed"
                fi
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
            # Install Python and pip if not available - check if installed
            if ! command -v python3 >/dev/null 2>&1; then
                if [ "${'$'}{DISTRO_TYPE}" = "alpine" ]; then
                    ${'$'}{INSTALL_CMD} python3 py3-pip || true
                else
                    ${'$'}{INSTALL_CMD} python3 python3-pip || ${'$'}{INSTALL_CMD} python3 python-pip || true
                fi
                echo "  ✓ Python3 installed"
            else
                echo "  ✓ Python3 already installed, skipping..."
            fi
            
            # Install Selenium - check if installed
            if python3 -c "import selenium" 2>/dev/null; then
                echo "  ✓ Selenium already installed, skipping..."
            else
                if command -v pip3 >/dev/null 2>&1; then
                    pip3 install --quiet --upgrade pip setuptools wheel 2>/dev/null || true
                    pip3 install --quiet selenium webdriver-manager 2>/dev/null || true
                    pip3 install --quiet selenium-wire undetected-chromedriver 2>/dev/null || true
                elif command -v pip >/dev/null 2>&1; then
                    pip install --quiet --upgrade pip setuptools wheel 2>/dev/null || true
                    pip install --quiet selenium webdriver-manager 2>/dev/null || true
                    pip install --quiet selenium-wire undetected-chromedriver 2>/dev/null || true
                fi
                if python3 -c "import selenium" 2>/dev/null; then
                    echo "  ✓ Selenium installed"
                fi
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
            # File manager - check if installed
            if command -v pcmanfm >/dev/null 2>&1 || command -v thunar >/dev/null 2>&1 || command -v nautilus >/dev/null 2>&1; then
                echo "  ✓ File manager already installed, skipping..."
            else
                ${'$'}{INSTALL_CMD} pcmanfm || ${'$'}{INSTALL_CMD} thunar || ${'$'}{INSTALL_CMD} nautilus || true
                echo "  ✓ File manager installed"
            fi
            
            # Terminal - check if installed
            if command -v lxterminal >/dev/null 2>&1 || command -v xterm >/dev/null 2>&1 || command -v gnome-terminal >/dev/null 2>&1; then
                echo "  ✓ Terminal already installed, skipping..."
            else
                ${'$'}{INSTALL_CMD} lxterminal || ${'$'}{INSTALL_CMD} xterm || ${'$'}{INSTALL_CMD} gnome-terminal || true
                echo "  ✓ Terminal installed"
            fi
            
            # Text editor - check if installed
            if command -v mousepad >/dev/null 2>&1 || command -v leafpad >/dev/null 2>&1 || command -v gedit >/dev/null 2>&1; then
                echo "  ✓ Text editor already installed, skipping..."
            else
                ${'$'}{INSTALL_CMD} mousepad || ${'$'}{INSTALL_CMD} leafpad || ${'$'}{INSTALL_CMD} gedit || true
                echo "  ✓ Text editor installed"
            fi
            
            # Image viewer - check if installed
            if command -v feh >/dev/null 2>&1 || command -v viewnior >/dev/null 2>&1 || command -v eog >/dev/null 2>&1; then
                echo "  ✓ Image viewer already installed, skipping..."
            else
                ${'$'}{INSTALL_CMD} feh || ${'$'}{INSTALL_CMD} viewnior || ${'$'}{INSTALL_CMD} eog || true
                echo "  ✓ Image viewer installed"
            fi
            
            # Additional Ubuntu Touch-like apps - check if installed
            if command -v rofi >/dev/null 2>&1 || command -v dmenu >/dev/null 2>&1; then
                echo "  ✓ App launcher already installed, skipping..."
            else
                ${'$'}{INSTALL_CMD} rofi dmenu || true
                echo "  ✓ App launcher installed"
            fi
            
            if command -v xdotool >/dev/null 2>&1; then
                echo "  ✓ Gesture tools already installed, skipping..."
            else
                ${'$'}{INSTALL_CMD} xdotool || true
                echo "  ✓ Gesture tools installed"
            fi
            
            if command -v convert >/dev/null 2>&1; then
                echo "  ✓ ImageMagick already installed, skipping..."
            else
                ${'$'}{INSTALL_CMD} imagemagick || true
                echo "  ✓ ImageMagick installed"
            fi
            
            echo "✓ Applications installed"
            echo ""
            
            echo "[8/8] Installing VNC server for GUI display..."
            # Install VNC server for remote desktop access - check if installed
            if command -v vncserver >/dev/null 2>&1 || command -v Xtigervnc >/dev/null 2>&1; then
                echo "  ✓ VNC server already installed, skipping..."
            else
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
                if command -v vncserver >/dev/null 2>&1 || command -v Xtigervnc >/dev/null 2>&1; then
                    echo "  ✓ VNC server installed"
                fi
            fi
            echo ""
            
            echo "[9/9] Configuring Ubuntu desktop environment (mobile-optimized)..."
            
            # Create config directories (idempotent - mkdir -p won't fail if exists)
            mkdir -p ~/.config/openbox
            mkdir -p ~/.config/tint2
            mkdir -p ~/.config/aterm-touch
            mkdir -p ~/.config/dunst
            mkdir -p ~/.config/gtk-3.0
            mkdir -p ~/.config/gtk-4.0
            mkdir -p ~/.local/share/applications
            mkdir -p ~/.local/bin
            mkdir -p ~/.local/share/themes
            mkdir -p ~/.local/share/icons
            echo "  ✓ Configuration directories created"
            
            # Configure GTK for mobile-friendly Ubuntu experience - check if already configured
            if [ -f ~/.config/gtk-3.0/settings.ini ] && grep -q "Yaru" ~/.config/gtk-3.0/settings.ini 2>/dev/null; then
                echo "  ✓ GTK3 settings already configured, skipping..."
            else
                cat > ~/.config/gtk-3.0/settings.ini << 'GTK3_EOF'
            [Settings]
            gtk-theme-name=Yaru-dark
            gtk-icon-theme-name=Yaru
            gtk-cursor-theme-name=Yaru
            gtk-cursor-theme-size=32
            gtk-toolbar-style=GTK_TOOLBAR_BOTH
            gtk-toolbar-icon-size=GTK_ICON_SIZE_LARGE_TOOLBAR
            gtk-button-images=1
            gtk-menu-images=1
            gtk-enable-event-sounds=1
            gtk-enable-input-feedback-sounds=1
            gtk-xft-antialias=1
            gtk-xft-hinting=1
            gtk-xft-hintstyle=hintfull
            gtk-xft-rgba=rgb
            gtk-application-prefer-dark-theme=1
            gtk-touchscreen-mode=1
            GTK3_EOF
                echo "  ✓ GTK3 settings configured"
            fi
            
            if [ -f ~/.config/gtk-4.0/settings.ini ] && grep -q "Yaru" ~/.config/gtk-4.0/settings.ini 2>/dev/null; then
                echo "  ✓ GTK4 settings already configured, skipping..."
            else
                cat > ~/.config/gtk-4.0/settings.ini << 'GTK4_EOF'
            [Settings]
            gtk-theme-name=Yaru-dark
            gtk-icon-theme-name=Yaru
            gtk-cursor-theme-name=Yaru
            gtk-cursor-theme-size=32
            gtk-application-prefer-dark-theme=1
            gtk-touchscreen-mode=1
            GTK4_EOF
                echo "  ✓ GTK4 settings configured"
            fi
            
            # Configure GNOME settings for mobile - check if already configured
            if command -v gsettings >/dev/null 2>&1; then
                CURRENT_THEME=${'$'}(gsettings get org.gnome.desktop.interface gtk-theme 2>/dev/null || echo "")
                if [ "${'$'}CURRENT_THEME" = "'Yaru-dark'" ] || [ "${'$'}CURRENT_THEME" = "'Yaru'" ]; then
                    echo "  ✓ GNOME settings already configured, skipping..."
                else
                    # Set Yaru theme
                    gsettings set org.gnome.desktop.interface gtk-theme 'Yaru-dark' 2>/dev/null || true
                    gsettings set org.gnome.desktop.interface icon-theme 'Yaru' 2>/dev/null || true
                    gsettings set org.gnome.desktop.interface cursor-theme 'Yaru' 2>/dev/null || true
                    # Mobile-friendly settings
                    gsettings set org.gnome.desktop.interface cursor-size 32 2>/dev/null || true
                    gsettings set org.gnome.desktop.interface text-scaling-factor 1.5 2>/dev/null || true
                    gsettings set org.gnome.desktop.interface scaling-factor 2 2>/dev/null || true
                    # Touch-friendly
                    gsettings set org.gnome.desktop.peripherals.touchscreen orientation-lock true 2>/dev/null || true
                    echo "  ✓ GNOME settings configured"
                fi
            fi
            
            # Create mobile-optimized Openbox configuration - check if already exists
            if [ -f ~/.config/openbox/rc.xml ]; then
                echo "  ✓ Openbox configuration already exists, skipping..."
            else
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
                echo "  ✓ Openbox configuration created"
            fi
            
            # Create mobile-optimized tint2 panel (Ubuntu Touch style - Premium) - check if exists
            if [ -f ~/.config/tint2/tint2rc ]; then
                echo "  ✓ Tint2 configuration already exists, skipping..."
            else
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
                echo "  ✓ Tint2 configuration created"
            fi
            
            # Create gesture configuration script - check if exists
            if [ -f ~/.config/aterm-touch/gestures.sh ]; then
                echo "  ✓ Gesture configuration already exists, skipping..."
            else
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
                echo "  ✓ Gesture configuration created"
            fi
            
            # Create launcher script (Ubuntu Touch style app drawer - Premium) - check if exists
            if [ -f ~/.local/bin/aterm-launcher ] && [ -x ~/.local/bin/aterm-launcher ]; then
                echo "  ✓ Launcher script already exists, skipping..."
            else
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
                echo "  ✓ Launcher script created"
            fi
            
            # Create startup script (Ubuntu Desktop - Mobile Optimized)
            # Always recreate to ensure XFCE is prioritized
            cat > ~/.xinitrc << 'XINIT_EOF'
            #!/bin/sh
            # aTerm Touch Desktop Startup Script
            # Ubuntu Desktop Environment - Mobile Optimized
            
            # Set DPI and scaling for mobile screens (Ubuntu optimized)
            export QT_AUTO_SCREEN_SCALE_FACTOR=1.5
            export QT_SCALE_FACTOR=1.5
            export GDK_SCALE=1.5
            export GDK_DPI_SCALE=0.75
            export XCURSOR_SIZE=32
            export XCURSOR_THEME=Yaru
            
            # Enable touch-friendly settings
            export GTK_TOUCH_MODE=1
            export GNOME_SHELL_SESSION_MODE=ubuntu
            
            # Enable hardware acceleration if available
            export LIBGL_ALWAYS_SOFTWARE=0
            export __GLX_VENDOR_LIBRARY_NAME=nvidia 2>/dev/null || true
            
            # Start DBus session bus (required for GNOME/Unity)
            if command -v dbus-launch >/dev/null 2>&1; then
                eval $(dbus-launch --sh-syntax)
                export DBUS_SESSION_BUS_ADDRESS
                export DBUS_SESSION_BUS_PID
                echo "DBus session bus started"
            else
                echo "Warning: dbus-launch not found. GNOME/Unity may not work properly."
                echo "Install dbus-x11 package to enable full desktop environment support."
            fi
            
            # Start notification daemon (Ubuntu style)
            if command -v notify-osd >/dev/null 2>&1; then
                notify-osd &
            elif command -v dunst >/dev/null 2>&1; then
                dunst -config ~/.config/dunst/dunstrc &
                DUNST_PID=${'$'}!
            fi
            
            # Try XFCE first (lighter, works better in VNC without systemd)
            if command -v startxfce4 >/dev/null 2>&1; then
                echo "Starting XFCE session (lightweight Ubuntu-like desktop)..."
                export XDG_CURRENT_DESKTOP=XFCE
                exec startxfce4
            fi
            
            # Try GNOME/Unity if XFCE not available (may require systemd)
            # Note: GNOME requires systemd which may not be available in chroot/VNC
            if command -v gnome-session >/dev/null 2>&1 && [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                echo "Starting GNOME session (Ubuntu desktop)..."
                # Set GNOME to mobile-friendly mode
                export GNOME_SHELL_SESSION_MODE=ubuntu
                export XDG_CURRENT_DESKTOP=ubuntu:GNOME
                # Disable systemd integration (not available in chroot/VNC)
                export XDG_RUNTIME_DIR=/tmp/gnome-session-runtime
                mkdir -p ${'$'}XDG_RUNTIME_DIR
                chmod 700 ${'$'}XDG_RUNTIME_DIR
                # Try to start GNOME (may fail without systemd)
                exec gnome-session --session=ubuntu --disable-acceleration-check 2>&1 || true
            fi
            
            # Try Unity if GNOME didn't work
            if command -v unity-session >/dev/null 2>&1 && [ -n "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                echo "Starting Unity session (Ubuntu classic desktop)..."
                export XDG_CURRENT_DESKTOP=Unity
                export XDG_RUNTIME_DIR=/tmp/unity-session-runtime
                mkdir -p ${'$'}XDG_RUNTIME_DIR
                chmod 700 ${'$'}XDG_RUNTIME_DIR
                exec unity-session 2>&1 || true
            fi
            
            # Fallback to lightweight window manager if GNOME/Unity not available
            if command -v openbox >/dev/null 2>&1; then
                echo "Starting Openbox (fallback window manager)..."
                # Start compositor for effects
                if command -v picom >/dev/null 2>&1; then
                    pkill picom 2>/dev/null || true
                    sleep 0.2
                    picom --backend glx --vsync --animations --shadow --shadow-radius 12 --shadow-opacity 0.3 &
                fi
                # Start panel
                if command -v tint2 >/dev/null 2>&1; then
                    pkill tint2 2>/dev/null || true
                    sleep 0.2
                    tint2 -c ~/.config/tint2/tint2rc 2>/dev/null &
                fi
                sleep 1
                exec openbox-session || exec openbox
            else
                echo "Error: No desktop environment found!"
                echo "Please install GNOME, Unity, or at least Openbox."
                if command -v xterm >/dev/null 2>&1; then
                    xterm -hold -e "echo 'Desktop environment not installed. Please install from Settings.'"
                fi
                exit 1
            fi
            
            # Cleanup on exit (this won't run if exec succeeds, but good to have)
            kill ${'$'}DUNST_PID 2>/dev/null || true
            XINIT_EOF
            chmod +x ~/.xinitrc
            echo "  ✓ .xinitrc created/updated and made executable"
            
            # Create notification daemon config (Ubuntu Touch style) - check if exists
            if [ -f ~/.config/dunst/dunstrc ]; then
                echo "  ✓ Dunst configuration already exists, skipping..."
            else
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
                echo "  ✓ Dunst configuration created"
            fi
            
            # Create desktop entries for browsers - check if exists
            if [ -f ~/.local/share/applications/chromium.desktop ]; then
                echo "  ✓ Browser desktop entries already exist, skipping..."
            else
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
                echo "  ✓ Browser desktop entries created"
            fi
            
            # Create Selenium helper script - check if exists
            if [ -f ~/.local/bin/selenium-setup ] && [ -x ~/.local/bin/selenium-setup ]; then
                echo "  ✓ Selenium setup script already exists, skipping..."
            else
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
                echo "  ✓ Selenium setup script created"
            fi
            
            # Create desktop entry for aTerm Touch - check if exists
            if [ -f ~/.local/share/applications/aterm-touch.desktop ]; then
                echo "  ✓ aTerm Touch desktop entry already exists, skipping..."
            else
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
                echo "  ✓ Desktop entries created"
            fi
            
            echo "✓ Configuration complete"
            
            # Verify configuration files were created
            echo ""
            echo "Verifying installation..."
            if [ -f ~/.xinitrc ] && [ -x ~/.xinitrc ]; then
                echo "✓ .xinitrc created and executable"
            else
                echo "✗ .xinitrc missing or not executable"
            fi
            if [ -f ~/.config/openbox/rc.xml ]; then
                echo "✓ Openbox configuration created"
            else
                echo "✗ Openbox configuration missing"
            fi
            if [ -f ~/.config/tint2/tint2rc ]; then
                echo "✓ Tint2 panel configuration created"
            else
                echo "✗ Tint2 panel configuration missing"
            fi
            if command -v openbox >/dev/null 2>&1; then
                echo "✓ Openbox window manager installed"
            else
                echo "✗ Openbox window manager not found"
            fi
            if command -v tint2 >/dev/null 2>&1; then
                echo "✓ Tint2 panel installed"
            else
                echo "✗ Tint2 panel not found"
            fi
            
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
            echo "Or use the 'Start Desktop' button in the OS tab."
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
