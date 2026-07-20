package com.projector.airemote.adb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Wire-format framing for the ADB host<->device protocol (adb/protocol.txt in AOSP).
 * Each message is a 24-byte header followed by an optional data payload.
 */
object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257

    const val A_VERSION = 0x01000000
    const val MAX_PAYLOAD = 256 * 1024

    const val HEADER_LENGTH = 24

    class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray = ByteArray(0)
    )

    fun generateMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val checksum = checksum(data)
        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(checksum)
        header.putInt(command.inv())
        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(data)
        return out.toByteArray()
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0L
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return (sum and 0xFFFFFFFFL).toInt()
    }

    /** Appends a single trailing NUL byte, matching adb's C-string wire format. */
    private fun nulTerminated(s: String): ByteArray {
        val strBytes = s.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(strBytes.size + 1)
        System.arraycopy(strBytes, 0, out, 0, strBytes.size)
        out[strBytes.size] = 0
        return out
    }

    fun connectPayload(): ByteArray = nulTerminated("host::features=cmd,shell_v2")

    fun cString(s: String): ByteArray = nulTerminated(s)

    fun readHeader(bytes: ByteArray): Message {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        buf.int // data checksum, unused on read
        buf.int // magic, unused on read
        return Message(command, arg0, arg1, ByteArray(dataLength))
    }
}
