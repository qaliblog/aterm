package com.qali.aterm.agent.utils

import java.io.File

/**
 * Make a path relative to a base directory
 */
fun makeRelative(path: String, baseDir: String): String {
    val pathFile = File(path)
    val baseFile = File(baseDir)
    return try {
        baseFile.toPath().relativize(pathFile.toPath()).toString()
    } catch (e: Exception) {
        path
    }
}

/**
 * Shorten a path for display
 */
fun shortenPath(path: String, maxLength: Int = 50): String {
    if (path.length <= maxLength) return path
    
    val parts = path.split(File.separator)
    if (parts.size <= 2) return path
    
    // Show first and last parts
    val first = parts.first()
    val last = parts.last()
    val remaining = maxLength - first.length - last.length - 3 // 3 for "..."
    
    return if (remaining > 0) {
        "$first...${last.take(remaining)}"
    } else {
        "...$last"
    }
}

/**
 * Resolve path relative to base directory
 */
fun resolvePath(baseDir: String, relativePath: String): String {
    return File(baseDir, relativePath).absolutePath
}
