package com.qali.aterm.ui.screens.terminal

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doOnTextChanged
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.android.material.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.dpToPx
import com.rk.libcommons.localDir
import com.rk.libcommons.pendingCommand
import com.rk.resources.strings
import com.rk.settings.Settings
import com.qali.aterm.service.TabType
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.agent.AgentTabs
import com.qali.aterm.ui.screens.codeeditor.CodeEditorScreen
import com.qali.aterm.ui.screens.fileexplorer.FileExplorerScreen
import com.qali.aterm.ui.components.SettingsToggle
import com.qali.aterm.ui.routes.MainActivityRoutes
import com.qali.aterm.ui.screens.settings.SettingsCard
import com.qali.aterm.ui.screens.settings.WorkingMode
import com.qali.aterm.ui.screens.terminal.virtualkeys.VirtualKeysConstants
import com.qali.aterm.ui.screens.terminal.virtualkeys.VirtualKeysInfo
import com.qali.aterm.ui.screens.terminal.virtualkeys.VirtualKeysListener
import com.qali.aterm.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.qali.aterm.ui.theme.KarbonTheme
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.Properties

var terminalView = WeakReference<TerminalView?>(null)
var virtualKeysView = WeakReference<VirtualKeysView?>(null)


var darkText = mutableStateOf(Settings.blackTextColor)

/**
 * Sets up terminal colors for dark or light theme.
 * In dark theme, uses light colors for text visibility.
 * In light theme, uses dark colors for text visibility.
 */
fun setupTerminalColors(terminalView: TerminalView?, isDarkMode: Boolean) {
    terminalView?.mEmulator?.mColors?.mCurrentColors?.apply {
        val foregroundColor = if (isDarkMode) Color.WHITE else Color.BLACK
        val backgroundColor = if (isDarkMode) Color.BLACK else Color.WHITE
        
        // Set foreground, background, and cursor colors
        set(256, foregroundColor) // Foreground color (COLOR_INDEX_FOREGROUND)
        set(257, backgroundColor) // Background color (COLOR_INDEX_BACKGROUND)
        set(258, foregroundColor) // Cursor color (COLOR_INDEX_CURSOR)
        
        if (isDarkMode) {
            // Dark theme: Use light colors for ANSI palette
            // Standard colors (0-7)
            set(0, Color.BLACK)        // Black
            set(1, Color.rgb(187, 0, 0))      // Red (bright)
            set(2, Color.rgb(0, 187, 0))       // Green (bright)
            set(3, Color.rgb(187, 187, 0))     // Yellow (bright)
            set(4, Color.rgb(0, 0, 187))      // Blue (bright)
            set(5, Color.rgb(187, 0, 187))    // Magenta (bright)
            set(6, Color.rgb(0, 187, 187))    // Cyan (bright)
            set(7, Color.rgb(187, 187, 187)) // White (light gray)
            
            // Bright colors (8-15)
            set(8, Color.rgb(85, 85, 85))     // Bright Black (dark gray)
            set(9, Color.rgb(255, 85, 85))    // Bright Red
            set(10, Color.rgb(85, 255, 85))  // Bright Green
            set(11, Color.rgb(255, 255, 85)) // Bright Yellow
            set(12, Color.rgb(85, 85, 255))  // Bright Blue
            set(13, Color.rgb(255, 85, 255)) // Bright Magenta
            set(14, Color.rgb(85, 255, 255)) // Bright Cyan
            set(15, Color.WHITE)             // Bright White
        } else {
            // Light theme: Use dark colors for ANSI palette
            // Standard colors (0-7)
            set(0, Color.BLACK)        // Black
            set(1, Color.rgb(128, 0, 0))      // Red
            set(2, Color.rgb(0, 128, 0))       // Green
            set(3, Color.rgb(128, 128, 0))     // Yellow
            set(4, Color.rgb(0, 0, 128))      // Blue
            set(5, Color.rgb(128, 0, 128))    // Magenta
            set(6, Color.rgb(0, 128, 128))    // Cyan
            set(7, Color.rgb(192, 192, 192)) // White (light gray)
            
            // Bright colors (8-15)
            set(8, Color.rgb(128, 128, 128))   // Bright Black (gray)
            set(9, Color.rgb(255, 0, 0))      // Bright Red
            set(10, Color.rgb(0, 255, 0))     // Bright Green
            set(11, Color.rgb(255, 255, 0))   // Bright Yellow
            set(12, Color.rgb(0, 0, 255))     // Bright Blue
            set(13, Color.rgb(255, 0, 255))   // Bright Magenta
            set(14, Color.rgb(0, 255, 255))  // Bright Cyan
            set(15, Color.BLACK)              // Bright White (black for light theme)
        }
    }
    terminalView?.onScreenUpdated()
}
var bitmap = mutableStateOf<ImageBitmap?>(null)

private val file = application!!.filesDir.child("font.ttf")
private var font = (if (file.exists() && file.canRead()){
    Typeface.createFromFile(file)
}else{
    Typeface.MONOSPACE
})

suspend fun setFont(typeface: Typeface) = withContext(Dispatchers.Main){
    font = typeface
    terminalView.get()?.apply {
        setTypeface(typeface)
        onScreenUpdated()
    }
}

inline fun getViewColor(): Int{
    return if (darkText.value){
        Color.BLACK
    }else{
        Color.WHITE
    }
}

inline fun getComposeColor():androidx.compose.ui.graphics.Color{
    return if (darkText.value){
        androidx.compose.ui.graphics.Color.Black
    }else{
        androidx.compose.ui.graphics.Color.White
    }
}

var showToolbar = mutableStateOf(Settings.toolbar)
var showVirtualKeys = mutableStateOf(Settings.virtualKeys)
var showHorizontalToolbar = mutableStateOf(Settings.toolbar)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    mainActivityActivity: MainActivity,
    navController: NavController
) {
    val context = LocalContext.current
    val isDarkMode = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Track current active tab (per session)
    val currentMainSessionId = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first ?: "main"
    var selectedTabIndex by rememberSaveable(currentMainSessionId) { mutableStateOf(0) }
    
    val tabs = listOf(
        TabType.TERMINAL,
        TabType.FILE_EXPLORER,
        TabType.TEXT_EDITOR,
        TabType.AGENT
    )
    
    // Reset tab to Terminal when switching sessions
    LaunchedEffect(currentMainSessionId) {
        selectedTabIndex = 0
    }


    // Update terminal text color based on theme
    LaunchedEffect(isDarkMode) {
        // In dark mode, we want white text (darkText = false)
        // In light mode, we want black text (darkText = true)
        darkText.value = !isDarkMode
        
        // Force update terminal colors immediately
        scope.launch(Dispatchers.Main) {
            setupTerminalColors(terminalView.get(), isDarkMode)
            virtualKeysView.get()?.apply {
                buttonTextColor = if (isDarkMode) Color.WHITE else Color.BLACK
                reload(
                    VirtualKeysInfo(
                        VIRTUAL_KEYS,
                        "",
                        VirtualKeysConstants.CONTROL_CHARS_ALIASES
                    )
                )
            }
        }
    }
    
    LaunchedEffect(Unit){
        withContext(Dispatchers.IO){
            if (context.filesDir.child("background").exists().not()){
                darkText.value = !isDarkMode
            }else if (bitmap.value == null){
                val fullBitmap = BitmapFactory.decodeFile(context.filesDir.child("background").absolutePath)?.asImageBitmap()
                if (fullBitmap != null) bitmap.value = fullBitmap
            }
        }


        scope.launch(Dispatchers.Main){
            virtualKeysView.get()?.apply {
                virtualKeysViewClient =
                    terminalView.get()?.mTermSession?.let {
                        VirtualKeysListener(
                            it
                        )
                    }

                buttonTextColor = getViewColor()


                reload(
                    VirtualKeysInfo(
                        VIRTUAL_KEYS,
                        "",
                        VirtualKeysConstants.CONTROL_CHARS_ALIASES
                    )
                )
            }

            terminalView.get()?.apply {
                onScreenUpdated()
                // Set up terminal colors based on current theme
                // Use isDarkMode variable instead of calling isSystemInDarkTheme() in coroutine
                setupTerminalColors(this, isDarkMode)
            }
        }


    }
    
    // Handle tab changes - switch session when tab changes
    LaunchedEffect(selectedTabIndex, currentMainSessionId) {
        // Hide keyboard when switching tabs
        keyboardController?.hide()
        
        terminalView.get()?.let { view ->
            val mainSessionId = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first ?: "main"
            val currentTabType = tabs[selectedTabIndex]
            val sessionIdForTab = mainActivityActivity.sessionBinder?.getService()
                ?.getSessionIdForTab(mainSessionId, currentTabType)
            
            sessionIdForTab?.let { sessionId ->
                val session = mainActivityActivity.sessionBinder?.getSession(sessionId)
                if (session != null && view.mTermSession != session) {
                    val client = TerminalBackEnd(view, mainActivityActivity)
                    session.updateTerminalSessionClient(client)
                    view.attachSession(session)
                    view.setTerminalViewClient(client)
                    view.onScreenUpdated()
                }
            }
        }
    }

    Box {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp
        val drawerWidth = (screenWidthDp * 0.84).dp
        var showAddDialog by remember { mutableStateOf(false) }

        BackHandler(enabled = drawerState.isOpen) {
            scope.launch {
                drawerState.close()
            }
        }

        if (drawerState.isClosed){
            SetStatusBarTextColor(isDarkIcons = darkText.value)
        }else{
            SetStatusBarTextColor(isDarkIcons = !isDarkMode)
        }

        if (showAddDialog){
            BasicAlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                }
            ) {

                fun createSession(workingMode:Int){
                    fun generateUniqueString(existingStrings: List<String>): String {
                        var index = 1
                        var newString: String

                        do {
                            newString = "main$index"
                            index++
                        } while (newString in existingStrings)

                        return newString
                    }

                    val sessionId = generateUniqueString(mainActivityActivity.sessionBinder!!.getService().getVisibleSessions())

                    terminalView.get()
                        ?.let {
                            val client = TerminalBackEnd(it, mainActivityActivity)
                            // Create session with 3 hidden sessions (file explorer, text editor, agent)
                            mainActivityActivity.sessionBinder!!.createSessionWithHidden(
                                sessionId,
                                client,
                                mainActivityActivity, workingMode = workingMode
                            )
                        }


                    changeSession(mainActivityActivity, session_id = sessionId)
                }


                PreferenceGroup {
                    val installedRootfs = remember { Rootfs.getInstalledRootfsList() }

                    SettingsCard(
                        title = { Text("Android") },
                        description = {Text("aTerm Android shell")},
                        onClick = {
                            createSession(workingMode = WorkingMode.ANDROID)
                            showAddDialog = false
                        })

                    // Show all installed rootfs dynamically
                    installedRootfs.forEach { rootfsName ->
                        val displayName = Rootfs.getRootfsDisplayName(rootfsName)
                        val workingMode = Rootfs.getRootfsWorkingMode(rootfsName)
                        
                        if (workingMode != null) {
                            SettingsCard(
                                title = { Text(displayName) },
                                description = { Text("Linux rootfs: $rootfsName") },
                                onClick = {
                                    createSession(workingMode = workingMode)
                                    showAddDialog = false
                                })
                        } else {
                            // For custom rootfs without known working mode, try to infer or use ALPINE as default
                            SettingsCard(
                                title = { Text(displayName) },
                                description = { Text("Custom rootfs: $rootfsName") },
                                onClick = {
                                    createSession(workingMode = WorkingMode.ALPINE)
                                    showAddDialog = false
                                })
                        }
                    }
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen || !(showToolbar.value && (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || showHorizontalToolbar.value)),
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Session",
                                style = MaterialTheme.typography.titleLarge
                            )

                            Row {
                                val keyboardController = LocalSoftwareKeyboardController.current
                                IconButton(onClick = {
                                    navController.navigate(MainActivityRoutes.Settings.route)
                                    keyboardController?.hide()
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Settings,
                                        contentDescription = null
                                    )
                                }

                                IconButton(onClick = {
                                    showAddDialog = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null
                                    )
                                }

                            }


                        }

                        mainActivityActivity.sessionBinder?.getService()?.getVisibleSessions()?.let { sessionList ->
                            LazyColumn {
                                items(sessionList) { session_id: String ->
                                    SelectableCard(
                                        selected = session_id == mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first,
                                        onSelect = {
                                            changeSession(
                                                mainActivityActivity,
                                                session_id
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = session_id,
                                                style = MaterialTheme.typography.bodyLarge
                                            )

                                            if (session_id != mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first) {
                                                Spacer(modifier = Modifier.weight(1f))

                                                IconButton(
                                                    onClick = {
                                                        println(session_id)
                                                        mainActivityActivity.sessionBinder?.terminateSession(
                                                            session_id
                                                        )
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    
                                                    Icon(
                                                        imageVector = Icons.Outlined.Delete,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                        }
                                    }
                                }
                            }
                        }

                    }
                }

            },
            content = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BackgroundImage()
                        // Use theme colors directly for proper dark mode support
                        val textColor = MaterialTheme.colorScheme.onSurface
                        Column {

                            fun getNameOfWorkingMode(workingMode:Int?):String{
                                return when(workingMode){
                                    0 -> "ALPINE".lowercase()
                                    1 -> "ANDROID".lowercase()
                                    2 -> "UBUNTU".lowercase()
                                    null -> "null"
                                    else -> "unknown"
                                }
                            }


                            if (showToolbar.value && (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || showHorizontalToolbar.value)){
                                TopAppBar(
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                                    ),
                                    title = {
                                        Column {
                                            Text(text = "aTerm", color = textColor)
                                            Text(
                                                style = MaterialTheme.typography.bodySmall,
                                                text = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first + " (${getNameOfWorkingMode(mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.second)})",
                                                color = textColor
                                            )
                                        }
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = {
                                            scope.launch { drawerState.open() }
                                        }) {
                                            Icon(Icons.Default.Menu, null, tint = textColor)
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            showAddDialog = true
                                        }) {
                                            Icon(Icons.Default.Add, null, tint = textColor)
                                        }
                                    }
                                )
                            }

                            val density = LocalDensity.current
                            Column(modifier = Modifier.imePadding().navigationBarsPadding().padding(top = if (showToolbar.value){0.dp}else{
                                with(density){
                                    TopAppBarDefaults.windowInsets.getTop(density).toDp()
                                }
                            })) {
                                // Tab Row
                                val currentMainSession = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first ?: "main"
                                TabRow(
                                    selectedTabIndex = selectedTabIndex,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    tabs.forEachIndexed { index, tabType ->
                                        Tab(
                                            selected = selectedTabIndex == index,
                                            onClick = { 
                                                // Hide keyboard when switching tabs
                                                keyboardController?.hide()
                                                selectedTabIndex = index
                                            },
                                            text = {
                                                Text(
                                                    when (tabType) {
                                                        TabType.TERMINAL -> "Terminal"
                                                        TabType.FILE_EXPLORER -> "File Explorer"
                                                        TabType.TEXT_EDITOR -> "Text Editor"
                                                        TabType.AGENT -> "Agent"
                                                    }
                                                )
                                            }
                                        )
                                    }
                                }
                                
                                // Show appropriate screen based on selected tab
                                when (tabs[selectedTabIndex]) {
                                    TabType.TERMINAL -> {
                                        AndroidView(
                                    factory = { context ->
                                        TerminalView(context, null).apply {
                                            terminalView = WeakReference(this)
                                            setTextSize(
                                                dpToPx(
                                                    Settings.terminal_font_size.toFloat(),
                                                    context
                                                )
                                            )
                                            val client = TerminalBackEnd(this, mainActivityActivity)
                                            
                                            // Get the current main session ID
                                            val mainSessionId = if (pendingCommand != null) {
                                                pendingCommand!!.id
                                            } else {
                                                mainActivityActivity.sessionBinder!!.getService().currentSession.value.first
                                            }
                                            
                                            // Get the session ID for the currently selected tab
                                            val currentTabType = tabs[selectedTabIndex]
                                            val sessionIdForTab = mainActivityActivity.sessionBinder!!.getService()
                                                .getSessionIdForTab(mainSessionId, currentTabType)

                                            val session = if (pendingCommand != null) {
                                                mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(
                                                    pendingCommand!!.id, pendingCommand!!.workingMode)
                                                // Create session with hidden if needed
                                                mainActivityActivity.sessionBinder!!.getSession(mainSessionId)
                                                    ?: mainActivityActivity.sessionBinder!!.createSessionWithHidden(
                                                        mainSessionId,
                                                        client,
                                                        mainActivityActivity, workingMode = Settings.working_Mode
                                                    )
                                                // Get the session for the current tab
                                                // Ensure agent session exists - createSessionWithHidden should have created it, but double-check
                                                var tabSession = mainActivityActivity.sessionBinder!!.getSession(sessionIdForTab)
                                                if (tabSession == null && sessionIdForTab.endsWith("_agent")) {
                                                    // Agent session doesn't exist, create it with matching working mode
                                                    val mainSessionWorkingMode = mainActivityActivity.sessionBinder!!.getSessionWorkingMode(mainSessionId) 
                                                        ?: Settings.working_Mode
                                                    
                                                    val agentClient = object : TerminalSessionClient {
                                                        override fun onTextChanged(changedSession: TerminalSession) {}
                                                        override fun onTitleChanged(changedSession: TerminalSession) {}
                                                        override fun onSessionFinished(finishedSession: TerminalSession) {}
                                                        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
                                                        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
                                                        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
                                                        override fun onBell(session: TerminalSession) {}
                                                        override fun onColorsChanged(session: TerminalSession) {}
                                                        override fun onTerminalCursorStateChange(state: Boolean) {}
                                                        override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
                                                        override fun logError(tag: String?, message: String?) {}
                                                        override fun logWarn(tag: String?, message: String?) {}
                                                        override fun logInfo(tag: String?, message: String?) {}
                                                        override fun logDebug(tag: String?, message: String?) {}
                                                        override fun logVerbose(tag: String?, message: String?) {}
                                                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                                                        override fun logStackTrace(tag: String?, e: Exception?) {}
                                                    }
                                                    // Use the same working mode as the main session to ensure matching distros
                                                    tabSession = mainActivityActivity.sessionBinder!!.createSession(sessionIdForTab, agentClient, mainActivityActivity, mainSessionWorkingMode)
                                                }
                                                tabSession ?: mainActivityActivity.sessionBinder!!.getSession(mainSessionId)
                                            } else {
                                                // Ensure main session exists
                                                val mainSession = mainActivityActivity.sessionBinder!!.getSession(mainSessionId)
                                                    ?: mainActivityActivity.sessionBinder!!.createSessionWithHidden(
                                                        mainSessionId,
                                                        client,
                                                        mainActivityActivity,workingMode = Settings.working_Mode
                                                    )
                                                // Get the session for the current tab
                                                // Ensure agent session exists if needed
                                                var tabSession = mainActivityActivity.sessionBinder!!.getSession(sessionIdForTab)
                                                if (tabSession == null && sessionIdForTab.endsWith("_agent")) {
                                                    // Agent session doesn't exist, create it with matching working mode
                                                    val mainSessionWorkingMode = mainActivityActivity.sessionBinder!!.getSessionWorkingMode(mainSessionId) 
                                                        ?: Settings.working_Mode
                                                    
                                                    val agentClient = object : TerminalSessionClient {
                                                        override fun onTextChanged(changedSession: TerminalSession) {}
                                                        override fun onTitleChanged(changedSession: TerminalSession) {}
                                                        override fun onSessionFinished(finishedSession: TerminalSession) {}
                                                        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
                                                        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
                                                        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
                                                        override fun onBell(session: TerminalSession) {}
                                                        override fun onColorsChanged(session: TerminalSession) {}
                                                        override fun onTerminalCursorStateChange(state: Boolean) {}
                                                        override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
                                                        override fun logError(tag: String?, message: String?) {}
                                                        override fun logWarn(tag: String?, message: String?) {}
                                                        override fun logInfo(tag: String?, message: String?) {}
                                                        override fun logDebug(tag: String?, message: String?) {}
                                                        override fun logVerbose(tag: String?, message: String?) {}
                                                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                                                        override fun logStackTrace(tag: String?, e: Exception?) {}
                                                    }
                                                    // Use the same working mode as the main session to ensure matching distros
                                                    tabSession = mainActivityActivity.sessionBinder!!.createSession(sessionIdForTab, agentClient, mainActivityActivity, mainSessionWorkingMode)
                                                }
                                                tabSession ?: mainSession
                                            }

                                            session?.updateTerminalSessionClient(client)
                                            session?.let { attachSession(it) }
                                            setTerminalViewClient(client)
                                            setTypeface(font)

                                            post {
                                                keepScreenOn = true
                                                requestFocus()
                                                isFocusableInTouchMode = true

                                                // Set terminal colors based on current theme
                                                darkText.value = !isDarkMode
                                                setupTerminalColors(this, isDarkMode)

                                                val colorsFile = localDir().child("colors.properties")
                                                if (colorsFile.exists() && colorsFile.isFile){
                                                    val props = Properties()
                                                    FileInputStream(colorsFile).use { input ->
                                                        props.load(input)
                                                    }
                                                    TerminalColors.COLOR_SCHEME.updateWith(props)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    update = { terminalView ->
                                        terminalView.onScreenUpdated()
                                        // Update terminal colors based on current theme
                                        darkText.value = !isDarkMode
                                        setupTerminalColors(terminalView, isDarkMode)
                                    },
                                )
                                    }
                                    
                                    TabType.FILE_EXPLORER -> {
                                        FileExplorerScreen(
                                            mainActivity = mainActivityActivity,
                                            sessionId = currentMainSession
                                        )
                                    }
                                    
                                    TabType.TEXT_EDITOR -> {
                                        CodeEditorScreen(
                                            mainActivity = mainActivityActivity,
                                            sessionId = currentMainSession
                                        )
                                    }
                                    
                                    TabType.AGENT -> {
                                        AgentTabs(
                                            mainActivity = mainActivityActivity,
                                            sessionId = currentMainSession
                                        )
                                    }
                                }

                                if (showVirtualKeys.value && tabs[selectedTabIndex] == TabType.TERMINAL){
                                    val pagerState = rememberPagerState(pageCount = { 2 })
                                    val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(75.dp)
                                    ) { page ->
                                        when (page) {
                                            0 -> {
                                                terminalView.get()?.requestFocus()
                                                //terminalView.get()?.requestFocusFromTouch()
                                                AndroidView(
                                                    factory = { context ->
                                                        VirtualKeysView(context, null).apply {
                                                            virtualKeysView = WeakReference(this)
                                                            virtualKeysViewClient =
                                                                terminalView.get()?.mTermSession?.let {
                                                                    VirtualKeysListener(
                                                                        it
                                                                    )
                                                                }

                                                            buttonTextColor = onSurfaceColor!!

                                                            reload(
                                                                VirtualKeysInfo(
                                                                    VIRTUAL_KEYS,
                                                                    "",
                                                                    VirtualKeysConstants.CONTROL_CHARS_ALIASES
                                                                )
                                                            )
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(75.dp)
                                                )
                                            }

                                            1 -> {
                                                var text by rememberSaveable { mutableStateOf("") }

                                                AndroidView(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(75.dp),
                                                    factory = { ctx ->
                                                        EditText(ctx).apply {
                                                            maxLines = 1
                                                            isSingleLine = true
                                                            imeOptions = EditorInfo.IME_ACTION_DONE

                                                            // Listen for text changes to update Compose state
                                                            doOnTextChanged { textInput, _, _, _ ->
                                                                text = textInput.toString()
                                                            }

                                                            setOnEditorActionListener { v, actionId, event ->
                                                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                                                    if (text.isEmpty()) {
                                                                        // Dispatch enter key events if text is empty
                                                                        val eventDown = KeyEvent(
                                                                            KeyEvent.ACTION_DOWN,
                                                                            KeyEvent.KEYCODE_ENTER
                                                                        )
                                                                        val eventUp = KeyEvent(
                                                                            KeyEvent.ACTION_UP,
                                                                            KeyEvent.KEYCODE_ENTER
                                                                        )
                                                                        terminalView.get()
                                                                            ?.dispatchKeyEvent(eventDown)
                                                                        terminalView.get()
                                                                            ?.dispatchKeyEvent(eventUp)
                                                                    } else {
                                                                        terminalView.get()?.currentSession?.write(
                                                                            text
                                                                        )
                                                                        setText("")
                                                                    }
                                                                    true
                                                                } else {
                                                                    false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    update = { editText ->
                                                        // Keep EditText's text in sync with Compose state, avoid infinite loop by only updating if different
                                                        if (editText.text.toString() != text) {
                                                            editText.setText(text)
                                                            editText.setSelection(text.length)
                                                        }
                                                    }
                                                )
                                            }
                                        }

                                    }
                                }else{
                                    virtualKeysView = WeakReference(null)
                                }

                            }
                        }



                }

            })
    }
}

@Composable
fun BackgroundImage() {
    bitmap.value?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(-1f)
        )
    }
}

@Composable
fun SetStatusBarTextColor(isDarkIcons: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return

    SideEffect {
        WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = isDarkIcons
    }
}



@Composable
fun SelectableCard(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "containerColor"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 8.dp else 2.dp
        ),
        enabled = enabled,
        onClick = onSelect
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            content()
        }
    }
}


fun changeSession(mainActivityActivity: MainActivity, session_id: String) {
    terminalView.get()?.apply {
        val client = TerminalBackEnd(this, mainActivityActivity)
        // Always use createSessionWithHidden to ensure agent session is created
        val session = mainActivityActivity.sessionBinder!!.getSession(session_id)
            ?: mainActivityActivity.sessionBinder!!.createSessionWithHidden(
                session_id,
                client,
                mainActivityActivity,workingMode = Settings.working_Mode
            )
        
        // Ensure agent session exists even if main session already existed
        // Use the same working mode as the main session to ensure matching distros
        val agentSessionId = "${session_id}_agent"
        if (mainActivityActivity.sessionBinder!!.getSession(agentSessionId) == null) {
            val mainSessionWorkingMode = mainActivityActivity.sessionBinder!!.getSessionWorkingMode(session_id) 
                ?: Settings.working_Mode
            
            val agentClient = object : TerminalSessionClient {
                override fun onTextChanged(changedSession: TerminalSession) {}
                override fun onTitleChanged(changedSession: TerminalSession) {}
                override fun onSessionFinished(finishedSession: TerminalSession) {}
                override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
                override fun onPasteTextFromClipboard(session: TerminalSession?) {}
                override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
                override fun onBell(session: TerminalSession) {}
                override fun onColorsChanged(session: TerminalSession) {}
                override fun onTerminalCursorStateChange(state: Boolean) {}
                override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
                override fun logError(tag: String?, message: String?) {}
                override fun logWarn(tag: String?, message: String?) {}
                override fun logInfo(tag: String?, message: String?) {}
                override fun logDebug(tag: String?, message: String?) {}
                override fun logVerbose(tag: String?, message: String?) {}
                override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                override fun logStackTrace(tag: String?, e: Exception?) {}
            }
            // Use the same working mode as the main session to ensure matching distros
            mainActivityActivity.sessionBinder!!.createSession(agentSessionId, agentClient, mainActivityActivity, mainSessionWorkingMode)
        }
        
        session.updateTerminalSessionClient(client)
        attachSession(session)
        setTerminalViewClient(client)
        post {
            val typedValue = TypedValue()

            context.theme.resolveAttribute(
                R.attr.colorOnSurface,
                typedValue,
                true
            )
            keepScreenOn = true
            requestFocus()
            isFocusableInTouchMode = true

            mEmulator?.mColors?.mCurrentColors?.apply {
                set(256, typedValue.data)
                set(258, typedValue.data)
            }
        }
        virtualKeysView.get()?.apply {
            virtualKeysViewClient =
                terminalView.get()?.mTermSession?.let { VirtualKeysListener(it) }
        }

    }
    mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(session_id,mainActivityActivity.sessionBinder!!.getService().sessionList[session_id]!!)

}


const val VIRTUAL_KEYS =
    ("[" + "\n  [" + "\n    \"ESC\"," + "\n    {" + "\n      \"key\": \"/\"," + "\n      \"popup\": \"\\\\\"" + "\n    }," + "\n    {" + "\n      \"key\": \"-\"," + "\n      \"popup\": \"|\"" + "\n    }," + "\n    \"HOME\"," + "\n    \"UP\"," + "\n    \"END\"," + "\n    \"PGUP\"" + "\n  ]," + "\n  [" + "\n    \"TAB\"," + "\n    \"CTRL\"," + "\n    \"ALT\"," + "\n    \"LEFT\"," + "\n    \"DOWN\"," + "\n    \"RIGHT\"," + "\n    \"PGDN\"" + "\n  ]" + "\n]")