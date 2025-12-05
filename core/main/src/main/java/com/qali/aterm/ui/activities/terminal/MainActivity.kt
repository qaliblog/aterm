package com.qali.aterm.ui.activities.terminal

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.qali.aterm.service.SessionService
import com.qali.aterm.ui.navHosts.MainActivityNavHost
import com.qali.aterm.ui.screens.terminal.TerminalScreen
import com.qali.aterm.ui.screens.terminal.terminalView
import com.qali.aterm.ui.theme.KarbonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    var sessionBinder:SessionService.SessionBinder? = null
    var isBound = false
    
    // Double back press handling
    private var backPressTime: Long = 0
    private val backPressDelay = 2000L // 2 seconds to press back again
    private var backPressCallback: OnBackPressedCallback? = null
    
    // State for showing back press message overlay
    var showBackPressMessage = mutableStateOf(false)


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SessionService.SessionBinder
            sessionBinder = binder
            isBound = true

            lifecycleScope.launch(Dispatchers.Main){
                setContent {
                    KarbonTheme {
                        Surface {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val navController = rememberNavController()
                                MainActivityNavHost(navController = navController, mainActivity = this@MainActivity)
                                
                                // Transparent overlay for back press message
                                BackPressMessageOverlay(
                                    showMessage = showBackPressMessage.value,
                                    onDismiss = { showBackPressMessage.value = false }
                                )
                            }
                        }
                    }
                }
            }


        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            sessionBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize LocalLlamaModel with context
        com.qali.aterm.llm.LocalLlamaModel.init(this)
    }
    
    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, SessionService::class.java))
        }else{
            startService(Intent(this, SessionService::class.java))
        }
        Intent(this, SessionService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }


    private var denied = 1
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted && denied <= 2) {
                denied++
                requestPermission()
            }
        }

    fun requestPermission(){
        // Only request on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var isKeyboardVisible = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermission()

        if (intent.hasExtra("awake_intent")){
            moveTaskToBack(true)
        }
        
        // Setup back press handler - moves app to background instead of closing
        setupBackPressHandler()
    }
    
    /**
     * Setup back press handler that moves app to background
     * Single back press: Show transparent message to press again (doesn't move to background)
     * Sequential back press (within 2 seconds): Move to background (never closes app)
     * This ensures agent workflow continues uninterrupted in background service
     */
    private fun setupBackPressHandler() {
        backPressCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                
                // Check if this is a second back press within the delay window
                if (backPressTime + backPressDelay > currentTime) {
                    // Second back press - move to background
                    showBackPressMessage.value = false
                    moveTaskToBack(true)
                    android.util.Log.d("MainActivity", "Sequential back press - moving to background (agent continues)")
                } else {
                    // First back press - show message, don't move to background
                    backPressTime = currentTime
                    showBackPressMessage.value = true
                    android.util.Log.d("MainActivity", "First back press - showing message")
                    
                    // Auto-hide message after delay
                    lifecycleScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(backPressDelay)
                        if (showBackPressMessage.value) {
                            showBackPressMessage.value = false
                            backPressTime = 0 // Reset timer
                        }
                    }
                }
            }
        }
        
        // Register the callback with highest priority to intercept all back presses
        onBackPressedDispatcher.addCallback(this, backPressCallback!!)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Remove callback to prevent leaks
        backPressCallback?.remove()
        backPressCallback = null
    }

    var wasKeyboardOpen = false
    override fun onPause() {
        super.onPause()
        wasKeyboardOpen = isKeyboardVisible
    }

    override fun onResume() {
        super.onResume()

        val rootView = findViewById<View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            val isVisible = keypadHeight > screenHeight * 0.15

            isKeyboardVisible = isVisible
        }


        if (wasKeyboardOpen && !isKeyboardVisible){
            terminalView.get()?.let {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}

/**
 * Transparent overlay that shows back press message
 */
@Composable
fun BackPressMessageOverlay(
    showMessage: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = showMessage,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1000f) // Ensure it's on top
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent), // Fully transparent background
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = "Press back again to put app to background",
                modifier = Modifier
                    .padding(bottom = 100.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f), // Semi-transparent background for text
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = Color.White.copy(alpha = 0.9f), // Semi-transparent white text
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}