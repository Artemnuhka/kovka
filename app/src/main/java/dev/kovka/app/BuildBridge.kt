package dev.kovka.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal class BuildBridge(
    private val context: Context,
    id: String,
    private val onEvent: (String, String) -> Unit
) : Closeable {
    private val directory = BuildHistory.staging(context, id)
    private val token = UUID.randomUUID().toString()
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newFixedThreadPool(3)
    val endpoint = "http://127.0.0.1:${listener.localPort}/$token"

    fun start() {
        workers.execute {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                connections.add(socket)
                if (listener.isClosed || connections.size > 4) {
                    socket.close()
                    connections.remove(socket)
                    continue
                }
                val scheduled = runCatching {
                    workers.execute {
                        try {
                            socket.use { respond(it) }
                        } catch (_: Exception) {
                            // A failed or interrupted upload remains in staging and is discarded.
                        } finally {
                            connections.remove(socket)
                        }
                    }
                }
                if (scheduled.isFailure) {
                    socket.close()
                    connections.remove(socket)
                }
            }
        }
    }

    private fun respond(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = input.line().split(' ')
        val prefix = "/$token/"
        val route = request.getOrNull(1)?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        if (request.size != 3 || request[0] != "POST" || route !in listOf("log", "phase", "artifact")) {
            socket.reply(404)
            return
        }
        val headers = mutableMapOf<String, String>()
        repeat(32) {
            val header = input.line()
            if (header.isEmpty()) {
                val length = headers["content-length"]?.toLongOrNull()
                if (length == null || length !in 0..(512L * 1024 * 1024)) {
                    socket.reply(413)
                    return
                }
                val accepted = when (route) {
                    "artifact" -> receiveApk(input, length, headers["x-apk-name"].orEmpty())
                    else -> if (length > 32_768) false else {
                        val bytes = ByteArray(length.toInt())
                        java.io.DataInputStream(input).readFully(bytes)
                        onEvent(requireNotNull(route), bytes.decodeToString())
                        true
                    }
                }
                socket.reply(if (accepted) 200 else 422)
                return
            }
            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
        }
        socket.reply(400)
    }

    private fun receiveApk(input: BufferedInputStream, length: Long, name: String): Boolean {
        if (!name.matches(Regex("[A-Za-z0-9._-]{1,110}\\.apk")) || length == 0L) return false
        directory.mkdirs()
        val target = File(directory, name)
        if (target.exists()) return false
        val temporary = File.createTempFile("upload-", ".apk", directory)
        try {
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) throw EOFException("APK upload ended early")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val archive = context.packageManager.getPackageArchiveInfo(temporary.path, flags) ?: return false
            @Suppress("DEPRECATION")
            val signed = if (Build.VERSION.SDK_INT >= 28) !archive.signingInfo?.apkContentsSigners.isNullOrEmpty()
                else !archive.signatures.isNullOrEmpty()
            return signed && temporary.renameTo(target)
        } finally {
            temporary.delete()
        }
    }

    private fun BufferedInputStream.line(): String {
        val text = StringBuilder()
        while (text.length < 4096) {
            val byte = read()
            if (byte == -1) throw EOFException()
            if (byte == 10) return text.toString().trimEnd('\r')
            text.append(byte.toChar())
        }
        error("HTTP header too long")
    }

    private fun Socket.reply(code: Int) {
        getOutputStream().write("HTTP/1.1 $code Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    override fun close() {
        runCatching { listener.close() }
        connections.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
