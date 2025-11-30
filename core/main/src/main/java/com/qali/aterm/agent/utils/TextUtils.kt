package com.qali.aterm.agent.utils

/**
 * Safely replaces text with literal strings, avoiding regex substitution issues.
 * Escapes $ characters to prevent template interpretation.
 */
fun safeLiteralReplace(str: String, oldString: String, newString: String): String {
    if (oldString.isEmpty() || !str.contains(oldString)) {
        return str
    }
    
    if (!newString.contains("$")) {
        return str.replace(oldString, newString, ignoreCase = false)
    }
    
    // Escape $ characters by doubling them
    val escapedNewString = newString.replace("$", "$$")
    return str.replace(oldString, escapedNewString, ignoreCase = false)
}

/**
 * Checks if data is likely binary by testing for the presence of a NULL byte.
 */
fun isBinary(data: ByteArray?, sampleSize: Int = 512): Boolean {
    if (data == null || data.isEmpty()) {
        return false
    }
    
    val sample = if (data.size > sampleSize) {
        data.sliceArray(0 until sampleSize)
    } else {
        data
    }
    
    for (byte in sample) {
        // The presence of a NULL byte (0x00) is a strong indicator of binary data
        if (byte == 0.toByte()) {
            return true
        }
    }
    
    return false
}
