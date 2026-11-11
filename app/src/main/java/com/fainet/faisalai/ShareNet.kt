package com.fainet.faisalai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** One file to send. [key] identifies it in the selection. */
class Item(val key: String, val name: String, val uri: Uri, val size: Long, val id: Long = 0L, val pkg: String = "")

/** The phone on the other end. */
class Peer(val ip: String, val port: Int, val name: String)

/** All file bytes that moved (sent or received); the screen turns this into a speed. */
object Meter {
    val bytes = AtomicLong()
}

/**
 * Both phones run this small server, so each can send to the other.
 * GET /hello?port=P&name=N  - the other phone introduces itself
 * PUT /up?name=&id=&off=&total=  - a file, or a part of a big file (several parts travel in parallel)
 */
class ShareServer(
    private val ctx: Context,
    private val deviceName: String,
    private val onHello: (Peer) -> Unit,
    private val onReceived: (File) -> Unit
) {
    companion object {
        const val PORT = 8080
        const val CHUNK = 1024 * 1024
        const val BUFFER = 4 * 1024 * 1024

        /** This phone's address on Wi-Fi or on its own hotspot, or null when there is no local network. */
        fun localIp(): String? {
            try {
                var fallback: String? = null
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in Collections.list(ni.inetAddresses)) {
                        if (a is Inet4Address && a.isSiteLocalAddress) {
                            val n = ni.name.lowercase()
                            if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return a.hostAddress
                            if (fallback == null) fallback = a.hostAddress
                        }
                    }
                }
                return fallback
            } catch (e: Exception) {
                return null
            }
        }
    }

    private class Incoming(val dest: File, val total: Long) { val got = AtomicLong() }

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private var socket: ServerSocket? = null

    fun start(): Boolean {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                receiveBufferSize = BUFFER
                bind(InetSocketAddress(PORT), 64)
            }
        } catch (e: Exception) {
            return false
        }
        socket = s
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    try { handle(client) } catch (e: Exception) { /* the other phone dropped the connection */ }
                    finally { try { client.close() } catch (e: Exception) { } }
                }
            }
        }
        return true
    }

    fun stop() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
    }

    private fun readLine(ins: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    private fun arg(query: String, key: String): String? =
        query.split("&").firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.let { URLDecoder.decode(it, "UTF-8") }

    private fun handle(client: Socket) {
        client.soTimeout = 60000
        client.tcpNoDelay = true
        client.sendBufferSize = BUFFER
        val ins = BufferedInputStream(client.getInputStream(), CHUNK)
        val out = client.getOutputStream()
        val request = readLine(ins).split(" ")
        if (request.size < 2) return
        var length = 0L
        while (true) {
            val header = readLine(ins)
            if (header.isEmpty()) break
            val i = header.indexOf(':')
            if (i > 0 && header.substring(0, i).equals("Content-Length", ignoreCase = true)) {
                length = header.substring(i + 1).trim().toLongOrNull() ?: 0L
            }
        }
        val path = request[1].substringBefore('?')
        val query = request[1].substringAfter('?', "")

        when {
            request[0] == "GET" && path == "/hello" -> {
                val ip = client.inetAddress.hostAddress ?: ""
                val port = arg(query, "port")?.toIntOrNull() ?: PORT
                onHello(Peer(ip, port, arg(query, "name") ?: ip))
                respond(out, "200 OK", deviceName)
            }
            request[0] == "PUT" && path == "/up" -> {
                val name = File(arg(query, "name") ?: "file").name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "file" }
                val id = arg(query, "id") ?: name
                val off = arg(query, "off")?.toLongOrNull() ?: 0L
                val total = arg(query, "total")?.toLongOrNull() ?: length
                val inc = synchronized(incoming) {
                    incoming.getOrPut(id) {
                        val dest = unique(File(Storage.dir(ctx, "Received"), name))
                        RandomAccessFile(dest, "rw").use { it.setLength(total) }
                        Incoming(dest, total)
                    }
                }
                RandomAccessFile(inc.dest, "rw").use { raf ->
                    raf.seek(off)
                    val buf = ByteArray(CHUNK)
                    var left = length
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) throw IOException("cut")
                        raf.write(buf, 0, n)
                        Meter.bytes.addAndGet(n.toLong())
                        left -= n
                    }
                }
                respond(out, "200 OK", "ok")
                if (inc.got.addAndGet(length) >= inc.total) {
                    incoming.remove(id)
                    onReceived(inc.dest)
                }
            }
            else -> respond(out, "404 Not Found", "Not found")
        }
        out.flush()
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var i = 1
        while (true) {
            val c = File(f.parentFile, "$base ($i)$ext")
            if (!c.exists()) return c
            i++
        }
    }

    private fun respond(out: OutputStream, status: String, body: String) {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
    }
}

/** Sending side. Files move over up to 4 connections at once; big files are cut into 4 parts that travel in parallel. */
object ShareClient {
    private const val STREAMS = 4
    private const val BIG = 48L * 1024 * 1024
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Introduces this phone to the other one. Returns the other phone's name, or null when it cannot be reached. */
    fun hello(ip: String, port: Int, myName: String): String? = try {
        val (code, text) = Net.request("GET", "http://$ip:$port/hello?port=${ShareServer.PORT}&name=${enc(myName)}", emptyMap(), null)
        if (code == 200) text.trim() else null
    } catch (e: Exception) {
        null
    }

    /** Size of a file, or -1 when unknown. */
    fun sizeOf(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) { }
        return -1L
    }

    /** Sends the items; done(sent, failed) runs on the main thread. */
    fun send(ctx: Context, peer: Peer, items: List<Item>, done: (Int, Int) -> Unit) {
        Net.pool.execute {
            val pool = Executors.newFixedThreadPool(STREAMS)
            val ok = AtomicInteger()
            val bad = AtomicInteger()
            val latch = CountDownLatch(items.size)
            for (item in items) {
                val size = if (item.size >= 0) item.size else sizeOf(ctx, item.uri)
                if (size < 0) { bad.incrementAndGet(); latch.countDown(); continue }
                val id = System.nanoTime().toString() + "-" + item.key.hashCode()
                val parts = if (size >= BIG) STREAMS else 1
                val partLen = (size + parts - 1) / parts
                val partsLeft = AtomicInteger(parts)
                val failed = java.util.concurrent.atomic.AtomicBoolean(false)
                for (p in 0 until parts) {
                    val off = p * partLen
                    val len = minOf(partLen, size - off)
                    pool.execute {
                        try {
                            if (!failed.get()) {
                                try { range(ctx, peer, item, id, off, len, size) } catch (e: IOException) { range(ctx, peer, item, id, off, len, size) }
                            }
                        } catch (e: Exception) {
                            failed.set(true)
                        }
                        if (partsLeft.decrementAndGet() == 0) {
                            if (failed.get()) bad.incrementAndGet() else ok.incrementAndGet()
                            latch.countDown()
                        }
                    }
                }
                if (size == 0L) { /* an empty file is a single empty part; handled by parts = 1 and len = 0 */ }
            }
            latch.await()
            pool.shutdown()
            Net.main.post { done(ok.get(), bad.get()) }
        }
    }

    private fun range(ctx: Context, peer: Peer, item: Item, id: String, off: Long, len: Long, total: Long) {
        val sc = SocketChannel.open()
        try {
            sc.socket().sendBufferSize = ShareServer.BUFFER
            sc.socket().tcpNoDelay = true
            sc.socket().connect(InetSocketAddress(peer.ip, peer.port), 8000)
            sc.socket().soTimeout = 60000
            val head = "PUT /up?name=${enc(item.name)}&id=${enc(id)}&off=$off&total=$total HTTP/1.1\r\n" +
                "Host: ${peer.ip}\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
            sc.write(ByteBuffer.wrap(head.toByteArray()))
            var pos = off
            val end = off + len
            var zeroCopy = true
            try {
                ctx.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                    val ch = FileInputStream(pfd.fileDescriptor).channel
                    while (pos < end) {
                        val n = ch.transferTo(pos, minOf(end - pos, 8L * 1024 * 1024), sc)
                        if (n <= 0) throw IOException("stalled")
                        pos += n
                        Meter.bytes.addAndGet(n)
                    }
                } ?: run { zeroCopy = false }
            } catch (e: IOException) {
                if (pos != off) throw e
                zeroCopy = false
            }
            if (!zeroCopy && pos < end) {
                // Providers that cannot be read by position: read the stream and skip to the part.
                (ctx.contentResolver.openInputStream(item.uri) ?: throw IOException("unreadable")).use { ins ->
                    var skip = off
                    while (skip > 0) { val s = ins.skip(skip); if (s <= 0) throw IOException("skip"); skip -= s }
                    val buf = ByteArray(ShareServer.CHUNK)
                    var left = len
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) throw IOException("short")
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        while (bb.hasRemaining()) sc.write(bb)
                        Meter.bytes.addAndGet(n.toLong())
                        left -= n
                    }
                }
            }
            val reply = ByteBuffer.allocate(64)
            sc.read(reply)
            if (!String(reply.array(), 0, reply.position()).contains(" 200")) throw IOException("rejected")
        } finally {
            try { sc.close() } catch (e: Exception) { }
        }
    }
}
