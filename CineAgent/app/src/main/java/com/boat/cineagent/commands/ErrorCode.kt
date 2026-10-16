package com.boat.cineagent.commands

object ErrorCode {
    const val INVALID_JSON = 1001
    const val UNKNOWN_COMMAND = 1002
    const val MISSING_PARAMETER = 1003
    const val APP_NOT_INSTALLED = 1004
    const val PERMISSION_DENIED = 1005
    const val COMMAND_FAILED = 1006
    const val DEVICE_BUSY = 1007
    const val UNSUPPORTED_COMMAND = 1008

    fun messageFor(code: Int): String = when (code) {
        INVALID_JSON -> "Invalid JSON"
        UNKNOWN_COMMAND -> "Unknown command"
        MISSING_PARAMETER -> "Missing parameter"
        APP_NOT_INSTALLED -> "Application not installed"
        PERMISSION_DENIED -> "Permission denied"
        COMMAND_FAILED -> "Command failed"
        DEVICE_BUSY -> "Device busy"
        UNSUPPORTED_COMMAND -> "Unsupported command"
        else -> "Unknown error"
    }
}
