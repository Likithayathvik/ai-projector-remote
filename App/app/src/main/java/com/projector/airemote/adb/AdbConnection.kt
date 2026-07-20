package com.projector.airemote.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

sealed class AdbResult {
    data class Success(val output: String) : AdbResult()
    data class Failure(val reason: String) : AdbResult()
}

/**
 * A single ADB-over-TCP connection to one device (e.g. an AOSP projector with
 * `adb tcpip 5555` / network debugging already enabled). Opens a fresh logical
 * stream per shell command, matching how `adb shell <cmd>` behaves.
 */
class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream
) {
    private val localIdCounter = AtomicInteger(1)

    /**
     * Serializes shell() calls end-to-end. The connection multiplexes logical streams over
     * one TCP socket by local/remote stream id, but this client's readMessage() doesn't
     * demultiplex by id - it just consumes whatever frame is next. Without this lock, two
     * concurrent shell() calls (e.g. the connection-health heartbeat and a user-triggered
     * command) can steal each other's response frames, so one call returns the other's output.
     */
    private val shellMutex = Mutex()

    val isConnected: Boolean get() = !socket.isClosed && socket.isConnected

    suspend fun shell(command: String): AdbResult = withContext(Dispatchers.IO) {
        shellMutex.withLock {
            val localId = localIdCounter.incrementAndGet()
            var remoteId = 0
            try {
                withTimeout(5000) {
                    writeMessage(AdbProtocol.generateMessage(AdbProtocol.A_OPEN, localId, 0, AdbProtocol.cString("shell:$command")))

                    val resultBuilder = StringBuilder()
                    while (true) {
                        val msg = readMessage()
                        // A frame not addressed to our stream id is either a stray leftover from
                        // a previous call that timed out before it could be cleanly closed, or
                        // (pre-mutex) a concurrent call's traffic. Either way it must never be
                        // treated as our own response - discard it and keep reading.
                        if (msg.command != AdbProtocol.A_CNXN && msg.arg1 != localId) {
                            continue
                        }
                        when (msg.command) {
                            AdbProtocol.A_OKAY -> {
                                if (remoteId == 0) remoteId = msg.arg0
                            }
                            AdbProtocol.A_WRTE -> {
                                remoteId = msg.arg0
                                resultBuilder.append(String(msg.data, StandardCharsets.UTF_8))
                                writeMessage(AdbProtocol.generateMessage(AdbProtocol.A_OKAY, localId, remoteId))
                            }
                            AdbProtocol.A_CLSE -> {
                                writeMessage(AdbProtocol.generateMessage(AdbProtocol.A_CLSE, localId, remoteId))
                                return@withTimeout AdbResult.Success(resultBuilder.toString())
                            }
                            else -> { /* ignore unrelated frames */ }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    AdbResult.Success(resultBuilder.toString())
                }
            } catch (e: Exception) {
                // Best-effort: tell the device to tear down this stream now rather than leaving
                // it dangling to send frames later that a subsequent call could misattribute to
                // itself (the exact bug the arg1 check above guards against, but this avoids
                // generating the stray frames in the first place).
                try {
                    writeMessage(AdbProtocol.generateMessage(AdbProtocol.A_CLSE, localId, remoteId))
                } catch (_: Exception) {
                }
                AdbResult.Failure(e.message ?: "adb shell command failed")
            }
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun writeMessage(bytes: ByteArray) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun readMessage(): AdbProtocol.Message {
        val headerBytes = ByteArray(AdbProtocol.HEADER_LENGTH)
        input.readFully(headerBytes)
        val header = AdbProtocol.readHeader(headerBytes)
        if (header.data.isNotEmpty()) {
            input.readFully(header.data)
        }
        return header
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3000

        /** Bounds the CNXN handshake. */
        private const val HANDSHAKE_TIMEOUT_MS = 8000L

        suspend fun connect(
            host: String,
            port: Int = 5555
        ): Result<AdbConnection> =
            withContext(Dispatchers.IO) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    output.write(
                        AdbProtocol.generateMessage(
                            AdbProtocol.A_CNXN,
                            AdbProtocol.A_VERSION,
                            AdbProtocol.MAX_PAYLOAD,
                            AdbProtocol.connectPayload()
                        )
                    )
                    output.flush()

                    fun readMessage(): AdbProtocol.Message {
                        val headerBytes = ByteArray(AdbProtocol.HEADER_LENGTH)
                        input.readFully(headerBytes)
                        val header = AdbProtocol.readHeader(headerBytes)
                        if (header.data.isNotEmpty()) input.readFully(header.data)
                        return header
                    }

                    withTimeout(HANDSHAKE_TIMEOUT_MS) {
                        val reply = readMessage()
                        if (reply.command == AdbProtocol.A_CNXN) {
                            Result.success(AdbConnection(socket, input, output))
                        } else {
                            socket.close()
                            Result.failure(Exception("Unexpected response 0x${reply.command.toString(16)} during handshake"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
    }
}
