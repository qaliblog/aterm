package com.qali.aterm.ui.screens.setup

/**
 * Enum representing different Linux distributions
 */
enum class DistroType(val displayName: String, val hasPredefinedInit: Boolean) {
    ALPINE("Alpine Linux", true),
    UBUNTU("Ubuntu", true),
    DEBIAN("Debian", true),
    KALI("Kali Linux", true),
    ARCH("Arch Linux", true),
    CUSTOM("Custom", false);
    
    companion object {
        fun fromString(name: String): DistroType? {
            return values().find { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
        }
    }
}
