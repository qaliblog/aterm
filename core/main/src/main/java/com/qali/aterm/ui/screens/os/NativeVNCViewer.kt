package com.qali.aterm.ui.screens.os

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qali.aterm.ui.activities.terminal.MainActivity
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RFB protocol state
 */
class RFBState {
    var protocolVersion: String = ""
    var securityType: Int = 0
    var framebufferWidth: Int = 0
    var framebufferHeight: Int = 0
    var pixelFormat: PixelFormat? = null
    var name: String = ""
    var authenticated: Boolean = false
    var initialized: Boolean = false
    
    companion object {
        fun disconnected(): RFBState = RFBState()
    }
}

/**
 * Pixel format information
 */
data class PixelFormat(
    var bitsPerPixel: Int = 32,
    var depth: Int = 24,
    var bigEndian: Boolean = false,
    var trueColor: Boolean = true,
    var redMax: Int = 255,
    var greenMax: Int = 255,
    var blueMax: Int = 255,
    var redShift: Int = 16,
    var greenShift: Int = 8,
    var blueShift: Int = 0
)

/**
 * Native VNC Viewer using Jetpack Compose Canvas
 * Connects via WebSocket to websockify (localhost:6080)
 * Implements RFB protocol for VNC communication
 */
@Composable
fun NativeVNCViewer(
    modifier: Modifier = Modifier,
    sessionId: String,
    mainActivity: MainActivity
) {
    val context = LocalContext.current
    var connectionStatus by remember { mutableStateOf("Connecting...") }
    var isConnected by remember { mutableStateOf(false) }
    var vncBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    
    val scope = rememberCoroutineScope()
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }
    var rfbState by remember { mutableStateOf<RFBState>(RFBState.disconnected()) }
    var messageBuffer by remember { mutableStateOf(ByteArray(0)) }
    
    // Helper functions defined first
    fun encryptPassword(password: String, challenge: ByteArray): ByteArray {
        // Simple VNC password encryption (DES)
        // For now, use a simplified version
        val key = ByteArray(8)
        password.toByteArray().copyInto(key, 0, 0, minOf(8, password.length))
        // In production, use proper DES encryption
        return challenge // Simplified - should be DES encrypted
    }
    
    fun requestInitialization(ws: WebSocket, state: RFBState) {
        // Already handled in security result
    }
    
    fun requestFramebufferUpdate(ws: WebSocket, state: RFBState) {
        scope.launch(Dispatchers.IO) {
            val message = ByteArray(10)
            message[0] = 3 // FramebufferUpdateRequest
            message[1] = 0 // incremental = false
            // x, y, width, height (all 0 = full screen)
            ByteBuffer.wrap(message, 2, 8).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(0) // x
                putShort(0) // y
                putShort(state.framebufferWidth.toShort()) // width
                putShort(state.framebufferHeight.toShort()) // height
            }
            ws.send(ByteString.of(*message))
        }
    }
    
    fun updateFramebuffer(x: Int, y: Int, w: Int, h: Int, data: ByteArray, state: RFBState) {
        scope.launch(Dispatchers.Main) {
            val pixelFormat = state.pixelFormat ?: return@launch
            val bitmap = vncBitmap ?: Bitmap.createBitmap(
                state.framebufferWidth,
                state.framebufferHeight,
                Bitmap.Config.ARGB_8888
            ).also { vncBitmap = it }
            
            // Convert pixel data to bitmap
            val pixels = IntArray(w * h)
            val bytesPerPixel = pixelFormat.bitsPerPixel / 8
            var dataIndex = 0
            
            for (i in 0 until h) {
                for (j in 0 until w) {
                    if (dataIndex + bytesPerPixel <= data.size) {
                        val pixel = when (bytesPerPixel) {
                            4 -> {
                                val value = ByteBuffer.wrap(data, dataIndex, 4)
                                    .order(if (pixelFormat.bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                                    .int
                                val r = (((value shr pixelFormat.redShift) and pixelFormat.redMax.toLong()) * 255 / pixelFormat.redMax).toInt()
                                val g = (((value shr pixelFormat.greenShift) and pixelFormat.greenMax.toLong()) * 255 / pixelFormat.greenMax).toInt()
                                val b = (((value shr pixelFormat.blueShift) and pixelFormat.blueMax.toLong()) * 255 / pixelFormat.blueMax).toInt()
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            else -> 0xFF000000.toInt() // Default to black
                        }
                        pixels[i * w + j] = pixel
                        dataIndex += bytesPerPixel
                    }
                }
            }
            
            bitmap.setPixels(pixels, 0, w, x, y, w, h)
        }
    }
    
    fun sendPointerEvent(ws: WebSocket, x: Int, y: Int, buttonMask: Int) {
        scope.launch(Dispatchers.IO) {
            val message = ByteArray(6)
            message[0] = 5 // PointerEvent
            message[1] = buttonMask.toByte()
            ByteBuffer.wrap(message, 2, 4).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(x.toShort())
                putShort(y.toShort())
            }
            ws.send(ByteString.of(*message))
        }
    }
    
    fun sendKeyEvent(ws: WebSocket, key: Int, down: Boolean) {
        scope.launch(Dispatchers.IO) {
            val message = ByteArray(8)
            message[0] = 4 // KeyEvent
            message[1] = if (down) 1 else 0
            message[2] = 0 // padding
            message[3] = 0 // padding
            ByteBuffer.wrap(message, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(key)
            ws.send(ByteString.of(*message))
        }
    }
    
    // Handle RFB protocol messages - returns remaining unprocessed data
    fun handleRFBMessage(data: ByteArray, ws: WebSocket): ByteArray {
        val state = rfbState
        if (!state.initialized && state.framebufferWidth == 0) {
            // Initial handshake - expect "RFB 003.008\n"
            if (data.size >= 12) {
                val message = String(data, 0, minOf(12, data.size))
                if (message.startsWith("RFB")) {
                    val newState = RFBState()
                    newState.protocolVersion = message.trim()
                    rfbState = newState
                    // Send security handshake
                    scope.launch(Dispatchers.IO) {
                        // Request VNC authentication
                        val securityTypes = byteArrayOf(0x02) // VNC Authentication
                        ws.send(ByteString.of(*securityTypes))
                    }
                    return data.drop(12).toByteArray()
                }
            }
            return data
        }
        
        // Handle security type response
        if (state.securityType == 0 && data.size >= 1) {
            val newState = RFBState()
            newState.protocolVersion = state.protocolVersion
            newState.securityType = (data[0].toInt() and 0xFF)
            newState.authenticated = state.authenticated
            newState.initialized = state.initialized
            newState.framebufferWidth = state.framebufferWidth
            newState.framebufferHeight = state.framebufferHeight
            newState.pixelFormat = state.pixelFormat
            newState.name = state.name
            rfbState = newState
            if (newState.securityType == 0x02) {
                // VNC Authentication - receive challenge
                if (data.size >= 17) {
                    val challenge = data.sliceArray(1..16)
                    // Send password response
                    val password = "aterm"
                    val response = encryptPassword(password, challenge)
                    scope.launch(Dispatchers.IO) {
                        ws.send(ByteString.of(*response))
                    }
                    return data.drop(17).toByteArray()
                }
            } else if (newState.securityType == 0x01) {
                // None - no authentication needed
                requestInitialization(ws, newState)
                return data.drop(1).toByteArray()
            }
            return data
        }
        
        // Handle security result
        if (!state.authenticated && data.size >= 4 && state.framebufferWidth == 0) {
            val result = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            if (result == 0) {
                // Success - request initialization
                val newState = RFBState()
                newState.protocolVersion = state.protocolVersion
                newState.securityType = state.securityType
                newState.authenticated = true
                newState.initialized = state.initialized
                newState.framebufferWidth = state.framebufferWidth
                newState.framebufferHeight = state.framebufferHeight
                newState.pixelFormat = state.pixelFormat
                newState.name = state.name
                rfbState = newState
                requestInitialization(ws, newState)
            } else {
                connectionStatus = "Authentication failed"
            }
            return data.drop(4).toByteArray()
        }
        
        // Handle server initialization
        if (!state.initialized && state.framebufferWidth == 0 && data.size >= 24) {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val newState = RFBState()
            newState.protocolVersion = state.protocolVersion
            newState.securityType = state.securityType
            newState.authenticated = state.authenticated
            newState.framebufferWidth = (buffer.short.toInt() and 0xFFFF)
            newState.framebufferHeight = (buffer.short.toInt() and 0xFFFF)
            
            // Read pixel format
            val pixelFormat = PixelFormat()
            pixelFormat.bitsPerPixel = (buffer.get().toInt() and 0xFF)
            pixelFormat.depth = (buffer.get().toInt() and 0xFF)
            pixelFormat.bigEndian = buffer.get() != 0.toByte()
            pixelFormat.trueColor = buffer.get() != 0.toByte()
            pixelFormat.redMax = (buffer.short.toInt() and 0xFFFF)
            pixelFormat.greenMax = (buffer.short.toInt() and 0xFFFF)
            pixelFormat.blueMax = (buffer.short.toInt() and 0xFFFF)
            pixelFormat.redShift = (buffer.get().toInt() and 0xFF)
            pixelFormat.greenShift = (buffer.get().toInt() and 0xFF)
            pixelFormat.blueShift = (buffer.get().toInt() and 0xFF)
            buffer.position(buffer.position() + 3) // padding
            
            newState.pixelFormat = pixelFormat
            
            val nameLength = buffer.int
            val totalSize = 24 + nameLength
            if (data.size >= totalSize) {
                if (nameLength > 0) {
                    val nameBytes = ByteArray(nameLength)
                    buffer.get(nameBytes)
                    newState.name = String(nameBytes)
                }
                
                newState.initialized = true
                rfbState = newState
                
                connectionStatus = "Connected (${newState.framebufferWidth}x${newState.framebufferHeight})"
                
                // Send client initialization
                scope.launch(Dispatchers.IO) {
                    ws.send(ByteString.of(0x01)) // Shared flag
                }
                
                // Request framebuffer update
                requestFramebufferUpdate(ws, newState)
                return data.drop(totalSize).toByteArray()
            }
            return data // Need more data
        }
        
        // Handle framebuffer update
        if (state.initialized && data.size >= 4 && data[0] == 0x00.toByte()) {
            // FramebufferUpdate message
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buffer.position(2) // Skip message type and padding
            val numRectangles = (buffer.short.toInt() and 0xFFFF)
            
            var pos = 4
            for (i in 0 until numRectangles) {
                if (pos + 12 <= data.size) {
                    val x = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    val y = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    val w = ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
                    val h = ((data[pos + 6].toInt() and 0xFF) shl 8) or (data[pos + 7].toInt() and 0xFF)
                    val encoding = ByteBuffer.wrap(data, pos + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                    
                    pos += 12
                    
                    if (encoding == 0) { // Raw encoding
                        val pixelSize = state.pixelFormat?.bitsPerPixel ?: 32 / 8
                        val rectSize = w * h * pixelSize
                        if (pos + rectSize <= data.size) {
                            val rectData = data.sliceArray(pos until pos + rectSize)
                            updateFramebuffer(x, y, w, h, rectData, state)
                            pos += rectSize
                        }
                    }
                }
            }
            
            // Request next update
            requestFramebufferUpdate(ws, state)
            return data.drop(pos).toByteArray()
        }
        
        // Return unprocessed data
        return data
    }
    
    // Connect to VNC via websockify
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                connectionStatus = "Connecting to VNC server..."
                val client = OkHttpClient.Builder()
                    .build()
                
                val request = Request.Builder()
                    .url("ws://127.0.0.1:6080/websockify")
                    .build()
                
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectionStatus = "Connected. Initializing VNC..."
                        isConnected = true
                        
                        // Send RFB protocol version
                        scope.launch(Dispatchers.IO) {
                            webSocket.send("RFB 003.008\n")
                        }
                    }
                    
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Handle text messages (unlikely in VNC)
                    }
                    
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        scope.launch(Dispatchers.Main) {
                            // Append to buffer and process
                            val newData = bytes.toByteArray()
                            val combined = messageBuffer + newData
                            messageBuffer = handleRFBMessage(combined, webSocket)
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connectionStatus = "Connection failed: ${t.message}"
                        isConnected = false
                        rfbState = RFBState.disconnected()
                        
                        // Retry connection
                        scope.launch {
                            delay(5000)
                            // Reconnect logic would go here
                        }
                    }
                    
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        connectionStatus = "Disconnecting..."
                        isConnected = false
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connectionStatus = "Disconnected"
                        isConnected = false
                        rfbState = RFBState.disconnected()
                    }
                })
                
                webSocket = ws
            } catch (e: Exception) {
                connectionStatus = "Error: ${e.message}"
                isConnected = false
            }
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            webSocket?.close(1000, "User closed")
        }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // Status bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.TopCenter),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isConnected) Color(0xFF00AA00) else Color(0xFFFFA500),
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // VNC Canvas - Use AndroidView with ImageView for better performance
        AndroidView(
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.MATRIX
                    setBackgroundColor(0xFF1A1A1A.toInt())
                }
            },
            update = { imageView ->
                vncBitmap?.let { bitmap ->
                    imageView.setImageBitmap(bitmap)
                    // Apply scale and translation
                    val matrix = android.graphics.Matrix()
                    matrix.postScale(scale, scale)
                    matrix.postTranslate(offsetX, offsetY)
                    imageView.imageMatrix = matrix
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp)
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        webSocket?.let { ws ->
                            val x = ((tapOffset.x - offsetX) / scale).toInt().coerceAtLeast(0)
                            val y = ((tapOffset.y - offsetY) / scale).toInt().coerceAtLeast(0)
                            sendPointerEvent(ws, x, y, 1) // Left button down
                            scope.launch {
                                delay(50)
                                sendPointerEvent(ws, x, y, 0) // Left button up
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
        )
    }
}
