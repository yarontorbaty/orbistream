package com.orbistream.bondix

import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.nio.ByteBuffer

/**
 * Socks5UdpRelay creates a local UDP listener that forwards traffic 
 * through a SOCKS5 proxy using UDP ASSOCIATE.
 * 
 * This allows native code (like GStreamer's srtsink) to benefit from 
 * Bondix bonding by connecting to the local relay instead of directly
 * to the remote server.
 * 
 * Flow:
 * 1. GStreamer connects to local relay (127.0.0.1:localPort)
 * 2. Relay forwards UDP through SOCKS5 proxy (UDP ASSOCIATE)
 * 3. Traffic goes through Bondix bonded tunnel
 * 4. Arrives at destination (SRT server)
 */
class Socks5UdpRelay(
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 28007,
    private val targetHost: String,
    private val targetPort: Int,
    private val localPort: Int = 0 // 0 = auto-assign
) {
    companion object {
        private const val TAG = "Socks5UdpRelay"
        
        // SOCKS5 constants
        private const val SOCKS_VERSION: Byte = 0x05
        private const val AUTH_NONE: Byte = 0x00
        private const val CMD_UDP_ASSOCIATE: Byte = 0x03
        private const val ATYP_IPV4: Byte = 0x01
        private const val ATYP_DOMAIN: Byte = 0x03
        private const val ATYP_IPV6: Byte = 0x04
        
        // Retry settings for waiting for Bondix tunnel to connect
        // Total wait time: 20 retries * 1000ms = 20 seconds max
        private const val MAX_RETRIES = 20
        private const val RETRY_DELAY_MS = 1000L
    }

    private var controlSocket: Socket? = null
    private var relaySocket: DatagramSocket? = null
    private var localSocket: DatagramSocket? = null
    
    private var relayAddress: InetSocketAddress? = null
    private var running = false
    private var relayJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Get the local port this relay is listening on.
     * Call after start() to get the assigned port.
     */
    fun getLocalPort(): Int = localSocket?.localPort ?: 0

    /**
     * Start the UDP relay.
     * 
     * Will retry connecting to the SOCKS5 proxy multiple times to allow
     * the Bondix tunnel to establish its connection first.
     * 
     * @return true if relay started successfully
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting SOCKS5 UDP relay to $targetHost:$targetPort via $socksHost:$socksPort")
            
            // Step 1: Establish SOCKS5 control connection (with retries)
            var connected = false
            for (attempt in 1..MAX_RETRIES) {
                Log.d(TAG, "SOCKS5 connection attempt $attempt/$MAX_RETRIES")
                
                if (establishSocksConnection()) {
                    connected = true
                    Log.i(TAG, "SOCKS5 connection established on attempt $attempt")
                    break
                }
                
                if (attempt < MAX_RETRIES) {
                    Log.d(TAG, "Waiting ${RETRY_DELAY_MS}ms before retry...")
                    delay(RETRY_DELAY_MS)
                }
            }
            
            if (!connected) {
                Log.e(TAG, "Failed to establish SOCKS5 connection after $MAX_RETRIES attempts")
                return@withContext false
            }
            
            // Step 2: Request UDP ASSOCIATE
            if (!requestUdpAssociate()) {
                Log.e(TAG, "Failed to request UDP ASSOCIATE")
                return@withContext false
            }
            
            // Step 3: Create local listening socket
            localSocket = DatagramSocket(localPort, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "Local relay listening on port ${localSocket!!.localPort}")
            
            // Step 4: Create socket for talking to SOCKS5 relay
            relaySocket = DatagramSocket()
            
            running = true
            
            // Step 5: Start relay loops
            relayJob = scope.launch {
                launch { forwardToRemote() }
                launch { forwardToLocal() }
            }
            
            Log.i(TAG, "SOCKS5 UDP relay started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start relay: ${e.message}")
            stop()
            false
        }
    }

    /**
     * Stop the relay.
     */
    fun stop() {
        Log.i(TAG, "Stopping SOCKS5 UDP relay")
        running = false
        relayJob?.cancel()
        
        try { localSocket?.close() } catch (_: Exception) {}
        try { relaySocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        
        localSocket = null
        relaySocket = null
        controlSocket = null
        relayAddress = null
    }

    private fun establishSocksConnection(): Boolean {
        // Close any previous failed connection
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        
        try {
            controlSocket = Socket(socksHost, socksPort)
            controlSocket!!.soTimeout = 10000
            
            val output = DataOutputStream(controlSocket!!.getOutputStream())
            val input = DataInputStream(controlSocket!!.getInputStream())
            
            // Send greeting: version + 1 auth method (no auth)
            output.write(byteArrayOf(SOCKS_VERSION, 0x01, AUTH_NONE))
            output.flush()
            
            // Read response
            val version = input.readByte()
            val method = input.readByte()
            
            if (version != SOCKS_VERSION || method != AUTH_NONE) {
                Log.e(TAG, "SOCKS5 auth failed: version=$version, method=$method")
                try { controlSocket?.close() } catch (_: Exception) {}
                controlSocket = null
                return false
            }
            
            Log.d(TAG, "SOCKS5 connection established")
            return true
        } catch (e: java.net.ConnectException) {
            // Connection refused - proxy not ready yet (expected during retries)
            Log.d(TAG, "SOCKS5 proxy not ready: ${e.message}")
            try { controlSocket?.close() } catch (_: Exception) {}
            controlSocket = null
            return false
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS5 connection error: ${e.message}")
            try { controlSocket?.close() } catch (_: Exception) {}
            controlSocket = null
            return false
        }
    }

    private fun requestUdpAssociate(): Boolean {
        try {
            val socket = controlSocket ?: return false
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            
            // Send UDP ASSOCIATE request
            // We bind to 0.0.0.0:0 as we'll send from any address
            val request = ByteBuffer.allocate(10).apply {
                put(SOCKS_VERSION)      // Version
                put(CMD_UDP_ASSOCIATE)  // Command
                put(0x00)               // Reserved
                put(ATYP_IPV4)          // Address type
                put(byteArrayOf(0, 0, 0, 0)) // Bind address (0.0.0.0)
                putShort(0)             // Bind port (0)
            }
            
            output.write(request.array())
            output.flush()
            
            // Read response
            val version = input.readByte()
            val reply = input.readByte()
            input.readByte() // Reserved
            val addrType = input.readByte()
            
            if (version != SOCKS_VERSION || reply != 0x00.toByte()) {
                Log.e(TAG, "UDP ASSOCIATE failed: reply=$reply")
                return false
            }
            
            // Parse relay address
            val relayHost: InetAddress
            when (addrType) {
                ATYP_IPV4 -> {
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    relayHost = InetAddress.getByAddress(addr)
                }
                ATYP_IPV6 -> {
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    relayHost = InetAddress.getByAddress(addr)
                }
                ATYP_DOMAIN -> {
                    val len = input.readByte().toInt() and 0xFF
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    relayHost = InetAddress.getByName(String(domain))
                }
                else -> {
                    Log.e(TAG, "Unknown address type: $addrType")
                    return false
                }
            }
            
            val relayPort = input.readShort().toInt() and 0xFFFF
            
            // If relay host is 0.0.0.0, use the SOCKS server address
            val actualHost = if (relayHost.hostAddress == "0.0.0.0") {
                InetAddress.getByName(socksHost)
            } else {
                relayHost
            }
            
            relayAddress = InetSocketAddress(actualHost, relayPort)
            Log.i(TAG, "UDP relay address: ${relayAddress}")
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "UDP ASSOCIATE error: ${e.message}")
            return false
        }
    }

    /**
     * Forward packets from local socket to SOCKS5 relay (encapsulated).
     */
    private suspend fun forwardToRemote() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(65536)
        val local = localSocket ?: return@withContext
        val relay = relaySocket ?: return@withContext
        val relayAddr = relayAddress ?: return@withContext
        
        // Resolve target address once
        val targetAddr = InetAddress.getByName(targetHost)
        
        Log.d(TAG, "Starting forward-to-remote loop")
        
        while (running && !local.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                local.receive(packet)
                
                // Encapsulate for SOCKS5 UDP
                val encapsulated = encapsulateUdpPacket(
                    packet.data, 
                    packet.length, 
                    targetAddr, 
                    targetPort
                )
                
                val outPacket = DatagramPacket(
                    encapsulated, 
                    encapsulated.size, 
                    relayAddr
                )
                relay.send(outPacket)
                
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "Forward-to-remote socket error: ${e.message}")
                break
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Forward-to-remote error: ${e.message}")
            }
        }
        
        Log.d(TAG, "Forward-to-remote loop ended")
    }

    /**
     * Forward packets from SOCKS5 relay to local socket (decapsulated).
     */
    private suspend fun forwardToLocal() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(65536)
        val local = localSocket ?: return@withContext
        val relay = relaySocket ?: return@withContext
        
        // Remember the client address from local socket
        var clientAddress: SocketAddress? = null
        
        Log.d(TAG, "Starting forward-to-local loop")
        
        while (running && !relay.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                relay.receive(packet)
                
                // Decapsulate SOCKS5 UDP header
                val (data, dataLen) = decapsulateUdpPacket(packet.data, packet.length)
                
                if (data != null && clientAddress != null) {
                    val outPacket = DatagramPacket(data, dataLen, clientAddress)
                    local.send(outPacket)
                }
                
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "Forward-to-local socket error: ${e.message}")
                break
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Forward-to-local error: ${e.message}")
            }
        }
        
        Log.d(TAG, "Forward-to-local loop ended")
    }

    /**
     * Encapsulate data for SOCKS5 UDP relay.
     * 
     * Format:
     * +----+------+------+----------+----------+----------+
     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
     * +----+------+------+----------+----------+----------+
     * | 2  |  1   |  1   | Variable |    2     | Variable |
     * +----+------+------+----------+----------+----------+
     */
    private fun encapsulateUdpPacket(
        data: ByteArray, 
        length: Int, 
        destAddr: InetAddress, 
        destPort: Int
    ): ByteArray {
        val addrBytes = destAddr.address
        val isIpv4 = addrBytes.size == 4
        
        val headerSize = 4 + addrBytes.size + 2 // RSV(2) + FRAG(1) + ATYP(1) + ADDR + PORT(2)
        val result = ByteArray(headerSize + length)
        
        var offset = 0
        
        // RSV (2 bytes)
        result[offset++] = 0x00
        result[offset++] = 0x00
        
        // FRAG
        result[offset++] = 0x00
        
        // ATYP
        result[offset++] = if (isIpv4) ATYP_IPV4 else ATYP_IPV6
        
        // DST.ADDR
        System.arraycopy(addrBytes, 0, result, offset, addrBytes.size)
        offset += addrBytes.size
        
        // DST.PORT (big endian)
        result[offset++] = ((destPort shr 8) and 0xFF).toByte()
        result[offset++] = (destPort and 0xFF).toByte()
        
        // DATA
        System.arraycopy(data, 0, result, offset, length)
        
        return result
    }

    /**
     * Decapsulate SOCKS5 UDP packet to get original data.
     * 
     * @return Pair of (data, length) or (null, 0) on error
     */
    private fun decapsulateUdpPacket(data: ByteArray, length: Int): Pair<ByteArray?, Int> {
        if (length < 10) return Pair(null, 0)
        
        var offset = 2 // Skip RSV
        offset++ // Skip FRAG
        
        val addrType = data[offset++]
        
        // Skip address
        when (addrType) {
            ATYP_IPV4 -> offset += 4
            ATYP_IPV6 -> offset += 16
            ATYP_DOMAIN -> {
                val domainLen = data[offset++].toInt() and 0xFF
                offset += domainLen
            }
            else -> return Pair(null, 0)
        }
        
        offset += 2 // Skip port
        
        val dataLen = length - offset
        if (dataLen <= 0) return Pair(null, 0)
        
        val result = ByteArray(dataLen)
        System.arraycopy(data, offset, result, 0, dataLen)
        
        return Pair(result, dataLen)
    }
}

