package com.qali.aterm.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.rk.resources.drawables
import com.rk.resources.strings
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.settings.Settings
import com.qali.aterm.ui.screens.terminal.MkSession
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import okhttp3.internal.wait

enum class TabType {
    TERMINAL,
    FILE_EXPLORER,
    TEXT_EDITOR,
    AGENT
}

class SessionService : Service() {
    private val sessions = hashMapOf<String, TerminalSession>()
    val sessionList = mutableStateMapOf<String,Int>()
    var currentSession = mutableStateOf(Pair("main",com.rk.settings.Settings.working_Mode))
    // Track hidden sessions (file explorer, text editor, agent)
    private val hiddenSessions = mutableSetOf<String>()
    // Track which hidden sessions belong to which main session
    private val sessionGroups = mutableMapOf<String, List<String>>()

    inner class SessionBinder : Binder() {
        fun getService():SessionService{
            return this@SessionService
        }
        fun terminateAllSessions(){
            sessions.values.forEach{
                it.finishIfRunning()
            }
            sessions.clear()
            sessionList.clear()
            hiddenSessions.clear()
            sessionGroups.clear()
            updateNotification()
        }
        fun createSession(id: String, client: TerminalSessionClient, activity: MainActivity,workingMode:Int): TerminalSession {
            return MkSession.createSession(activity, client, id, workingMode = workingMode).also {
                sessions[id] = it
                sessionList[id] = workingMode
                updateNotification()
            }
        }
        
        fun createSessionWithHidden(id: String, client: TerminalSessionClient, activity: MainActivity, workingMode: Int): TerminalSession {
            // Create the main visible session
            val mainSession = createSession(id, client, activity, workingMode)
            
            // Only create agent hidden session - file explorer and text editor don't use terminal sessions
            // They just use the sessionId for identification but don't need actual terminal sessions
            val hiddenSessionIds = listOf(
                "${id}_agent"
            )
            
            // Create hidden session with a dummy client (it won't be displayed)
            hiddenSessionIds.forEach { hiddenId ->
                val hiddenClient = object : TerminalSessionClient {
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
                
                val hiddenSession = MkSession.createSession(activity, hiddenClient, hiddenId, workingMode = workingMode)
                sessions[hiddenId] = hiddenSession
                // Don't add hidden sessions to sessionList - they should not appear in UI
                hiddenSessions.add(hiddenId)
            }
            
            // Track the relationship between main session and hidden sessions
            sessionGroups[id] = hiddenSessionIds
            
            return mainSession
        }
        
        fun isHiddenSession(id: String): Boolean {
            return hiddenSessions.contains(id)
        }
        
        fun getVisibleSessions(): List<String> {
            return this@SessionService.getVisibleSessions()
        }
        
        fun getSessionIdForTab(mainSessionId: String, tabType: TabType): String {
            return this@SessionService.getSessionIdForTab(mainSessionId, tabType)
        }
        
        fun getMainSessionIdFromTabId(tabSessionId: String): String? {
            return this@SessionService.getMainSessionIdFromTabId(tabSessionId)
        }
        
        fun getSession(id: String): TerminalSession? {
            return sessions[id]
        }
        fun terminateSession(id: String) {
            runCatching {
                // Terminate the main session
                sessions[id]?.apply {
                    if (emulator != null){
                        sessions[id]?.finishIfRunning()
                    }
                }

                sessions.remove(id)
                sessionList.remove(id)
                
                // Also terminate associated hidden sessions
                sessionGroups[id]?.forEach { hiddenId ->
                    sessions[hiddenId]?.apply {
                        if (emulator != null) {
                            finishIfRunning()
                        }
                    }
                    sessions.remove(hiddenId)
                    hiddenSessions.remove(hiddenId)
                }
                sessionGroups.remove(id)
                
                if (sessions.isEmpty()) {
                    stopSelf()
                } else {
                    updateNotification()
                }
            }.onFailure { it.printStackTrace() }

        }
    }

    private val binder = SessionBinder()
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        sessions.forEach { s -> s.value.finishIfRunning() }
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_EXIT" -> {
                sessions.forEach { s -> s.value.finishIfRunning() }
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exitIntent = Intent(this, SessionService::class.java).apply {
            action = "ACTION_EXIT"
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 1, exitIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("aTerm")
            .setContentText(getNotificationContentText())
            .setSmallIcon(drawables.terminal)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    null,
                    "EXIT",
                    exitPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private val CHANNEL_ID = "session_service_channel"

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Session Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for Terminal Service"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val notification = createNotification()
        notificationManager.notify(1, notification)
    }

    private fun getNotificationContentText(): String {
        val count = sessions.size
        if (count == 1){
            return "1 session running"
        }
        return "$count sessions running"
    }
    
    fun isHiddenSession(id: String): Boolean {
        return hiddenSessions.contains(id)
    }
    
    fun getVisibleSessions(): List<String> {
        return sessionList.keys.filter { !isHiddenSession(it) }
    }
    
    fun getSessionIdForTab(mainSessionId: String, tabType: TabType): String {
        return when (tabType) {
            TabType.TERMINAL -> mainSessionId
            TabType.FILE_EXPLORER -> mainSessionId // File explorer doesn't need separate terminal session
            TabType.TEXT_EDITOR -> mainSessionId // Text editor doesn't need separate terminal session
            TabType.AGENT -> "${mainSessionId}_agent" // Only agent needs a separate terminal session
        }
    }
    
    fun getMainSessionIdFromTabId(tabSessionId: String): String? {
        return when {
            tabSessionId.endsWith("_agent") -> tabSessionId.removeSuffix("_agent")
            else -> if (!isHiddenSession(tabSessionId)) tabSessionId else null
        }
    }
}
