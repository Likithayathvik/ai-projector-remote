package com.boat.cineagent.commands

object ErrorCode {
    const val INVALID_JSON = 1001
    const val UNKNOWN_COMMAND = 1002
    const val MISSING_PARAMETER = 1003
    const val APP_NOT_INSTALLED = 1004
    const val PERMISSION_DENIED = 1005
    const val COMMAND_FAILED = 1006
    /** Another phone is already connected; only one phone may control the projector. */
    const val DEVICE_BUSY = 1007
    const val UNSUPPORTED_COMMAND = 1008
    const val AUTH_FAILED = 1009
    const val NOT_AUTHENTICATED = 1010
    /** A newer tap pushed this command out of the phone's 3-command queue; it never ran. */
    const val COMMAND_DROPPED = 1011

    fun messageFor(code: Int): String = when (code) {
        INVALID_JSON -> "Invalid JSON"
        UNKNOWN_COMMAND -> "Unknown command"
        MISSING_PARAMETER -> "Missing parameter"
        APP_NOT_INSTALLED -> "Application not installed"
        PERMISSION_DENIED -> "Permission denied"
        COMMAND_FAILED -> "Command failed"
        DEVICE_BUSY -> "Projector is already connected to another phone"
        UNSUPPORTED_COMMAND -> "Unsupported command"
        AUTH_FAILED -> "Authentication failed"
        NOT_AUTHENTICATED -> "Not authenticated"
        COMMAND_DROPPED -> "Replaced by a newer command"
        else -> "Unknown error"
    }
}
