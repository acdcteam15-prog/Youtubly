package com.example.remoty

import android.content.Context
import com.zerotier.sockets.ZeroTierEventListener
import com.zerotier.sockets.ZeroTierNode
import com.zerotier.sockets.ZeroTierSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class ZtPhase { Stopped, Starting, Joining, NeedsAuth, Ready, Error }

data class ZtState(
    val phase: ZtPhase = ZtPhase.Stopped,
    val nodeId: String = "",     // this phone's ZeroTier address (10 hex characters), authorise it in ZeroTier Central
    val ip: String = "",         // the address the network gave this phone
    val message: String = "",
)

/**
 * ZeroTier running INSIDE this app (libzt): no ZeroTier app, no VPN slot, no root. The phone joins your network as its
 * own new device, which you authorise once in my.zerotier.com.
 */
object ZtManager {
    private val _state = MutableStateFlow(ZtState())
    val state: StateFlow<ZtState> = _state.asStateFlow()

    private var node: ZeroTierNode? = null
    private var joined: Long = 0L
    @Volatile private var accessDenied = false
    @Volatile private var notFound = false

    // Event codes from ZeroTierNative (ZTS_EVENT_*): 210 network not found, 213 network ok, 214 access denied.
    private val listener = object : ZeroTierEventListener {
        override fun onZeroTierEvent(id: Long, eventCode: Int) {
            when (eventCode) {
                214 -> accessDenied = true
                213 -> { accessDenied = false; notFound = false }
                210 -> notFound = true
            }
        }
    }

    /** Starts the node (once), joins [networkHex] and suspends until the network gave this phone an address. */
    suspend fun connect(ctx: Context, networkHex: String) = withContext(Dispatchers.IO) {
        val nwid = java.lang.Long.parseUnsignedLong(networkHex.trim(), 16)
        try {
            _state.value = ZtState(ZtPhase.Starting, message = "Starting the ZeroTier node...")
            val n = node ?: ZeroTierNode().also { fresh ->
                val dir = File(ctx.filesDir, "zt").apply { mkdirs() }
                fresh.initFromStorage(dir.absolutePath)   // keeps this phone's identity between launches
                fresh.initSetEventHandler(listener)
                val rc = fresh.start()
                if (rc != 0) throw IllegalStateException("ZeroTier could not start (code $rc)")
                node = fresh
            }
            var waited = 0
            while (!n.isOnline) {
                delay(250)
                waited += 250
                if (waited > 60_000) throw IllegalStateException("ZeroTier stayed offline for 60 s. Check the phone's internet connection.")
            }
            val nodeId = String.format("%010x", n.id)

            if (joined != 0L && joined != nwid) { n.leave(joined); joined = 0L }
            if (joined != nwid) {
                accessDenied = false; notFound = false
                n.join(nwid)
                joined = nwid
            }
            while (!n.isNetworkTransportReady(nwid)) {
                if (notFound) throw IllegalStateException("ZeroTier says network $networkHex does not exist. Check the Network ID.")
                _state.value = if (accessDenied)
                    ZtState(ZtPhase.NeedsAuth, nodeId, "", "Waiting for you to authorise this phone in ZeroTier Central.")
                else
                    ZtState(ZtPhase.Joining, nodeId, "", "Joining the network...")
                delay(500)
            }
            val ip = n.getIPv4Address(nwid)?.hostAddress ?: ""
            _state.value = ZtState(ZtPhase.Ready, nodeId, ip, "Connected to the ZeroTier network")
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = ZtState(ZtPhase.Stopped)
            throw e
        } catch (e: Exception) {
            _state.value = ZtState(ZtPhase.Error, _state.value.nodeId, "", e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Leaves the network (the node itself keeps running so reconnecting is quick). */
    fun disconnect() {
        try { if (joined != 0L) node?.leave(joined) } catch (_: Exception) {}
        joined = 0L
        _state.value = ZtState(ZtPhase.Stopped, _state.value.nodeId)
    }
}

/**
 * The app speaks plain HTTP to 127.0.0.1:<port>. This forwarder copies those bytes through a ZeroTier socket to the
 * laptop, so standard HTTP code (and the preview WebView) works over the embedded ZeroTier node unchanged.
 */
object Forwarder {
    private val ports = ConcurrentHashMap<String, Int>()

    @Synchronized
    fun localPortFor(remoteIp: String, remotePort: Int): Int =
        ports.getOrPut("$remoteIp:$remotePort") { start(remoteIp, remotePort) }

    private fun start(remoteIp: String, remotePort: Int): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "fwd-$remotePort") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { serve(client, remoteIp, remotePort) }
            }
        }
        return server.localPort
    }

    private fun serve(client: Socket, ip: String, port: Int) {
        val remote = try { ZeroTierSocket(ip, port) } catch (e: Exception) { closeQuietly(client); return }
        val up = thread(isDaemon = true) {
            copy(client.getInputStream(), remote.outputStream)
            closeAll(client, remote)
        }
        copy(remote.inputStream, client.getOutputStream())
        closeAll(client, remote)
        up.interrupt()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun closeAll(c: Socket, z: ZeroTierSocket) {
        closeQuietly(c)
        try { z.close() } catch (_: Exception) {}
    }

    private fun closeQuietly(s: Socket) { try { s.close() } catch (_: Exception) {} }
}
