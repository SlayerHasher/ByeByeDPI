package io.github.romanvht.byedpi.utility

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

class SiteCheckUtils(
    private val proxyIp: String,
    private val proxyPort: Int
) {

    companion object {
        private const val TAG = "SiteChecker"
        private const val MAX_BODY_BYTES = 64L * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000

        // Per-site failure counts shared between strategy runs within one test session.
        // A site that failed every request for several previous strategies is very
        // unlikely to work now, so its checks are shortened (early exit), which
        // substantially speeds up the whole test. Cleared at the start of each run.
        private val failureCache = ConcurrentHashMap<String, Int>()
        private val cacheMutex = Mutex()

        fun resetCache() {
            failureCache.clear()
        }

        private suspend fun recordResult(site: String, successes: Int, requestsCount: Int) {
            cacheMutex.withLock {
                val previous = failureCache[site] ?: 0
                val failures = requestsCount - successes
                failureCache[site] = if (failures == 0) 0 else minOf(previous + failures, requestsCount)
            }
        }
    }

    /**
     * Checks all [sites] concurrently. Requests for each site also run concurrently;
     * a site finishes as soon as its success count reaches the required threshold or
     * the remaining requests can no longer reach it (early exit). Sites that failed
     * every request in previous strategies are skipped entirely thanks to the shared
     * failure cache, which substantially speeds up testing of many strategies.
     */
    suspend fun checkSitesAsync(
        sites: List<String>,
        requestsCount: Int,
        requestTimeout: Long,
        concurrentRequests: Int = 20,
        fullLog: Boolean,
        onSiteChecked: (suspend (String, Int, Int) -> Unit)? = null
    ): List<Pair<String, Int>> {
        val semaphore = Semaphore(concurrentRequests)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                sites.map { site ->
                    async {
                        val cachedFailures = failureCache[site] ?: 0
                        val successCount = if (cachedFailures >= requestsCount) {
                            Log.i(TAG, "Cached failure for $site ($cachedFailures/$requestsCount)")
                            0
                        } else {
                            checkSiteAccess(site, requestsCount, requestTimeout, semaphore, requestsCount - cachedFailures)
                        }
                        ensureActive()
                        recordResult(site, successCount, requestsCount)
                        if (fullLog) {
                            onSiteChecked?.invoke(site, successCount, requestsCount)
                        }
                        site to successCount
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun checkSiteAccess(
        site: String,
        requestsCount: Int,
        timeout: Long,
        semaphore: Semaphore,
        requiredSuccesses: Int,
    ): Int = coroutineScope {
        val formattedUrl = if (site.startsWith("http://") || site.startsWith("https://")) site
        else "https://$site"

        val url = try {
            URL(formattedUrl)
        } catch (_: Exception) {
            Log.e(TAG, "Invalid URL: $formattedUrl")
            return@coroutineScope 0
        }

        val successCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val jobs = List(requestsCount) { index ->
            async(Dispatchers.IO) {
                // Early exit: the site outcome is already decided by completed and
                // not-yet-started requests, so this one cannot influence the result.
                val successes = successCounter.get()
                if (successes >= requiredSuccesses || successes + (requestsCount - index) < requiredSuccesses) {
                    return@async false
                }
                semaphore.withPermit {
                    withTimeoutOrNull(timeout * 1000) {
                        runCatching { performRequest(url) }.getOrElse { e ->
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Error accessing $site: ${e.message}")
                            false
                        }
                    } ?: run {
                        Log.w(TAG, "Timeout for $site")
                        false
                    }
                }.also { ok -> if (ok) successCounter.incrementAndGet() }
            }
        }

        // Wait until the outcome is decided; the outer budget guarantees we never
        // hang forever even if a socket gets stuck in an uninterruptible read.
        val budgetMs = timeout * 1000 * ((requestsCount + 1) / 2) + 15_000L
        withTimeoutOrNull(budgetMs) {
            while (currentCoroutineContext().isActive) {
                val successes = successCounter.get()
                val pending = jobs.count { !it.isCompleted }
                if (successes >= requiredSuccesses || successes + pending < requiredSuccesses) break
                delay(50)
            }
        }
        jobs.forEach { it.cancel() }
        val results = jobs.map { runCatching { it.await() }.getOrDefault(false) }

        results.count { it }
    }

    private fun performRequest(url: URL): Boolean =
        if (url.protocol.equals("https", ignoreCase = true)) {
            // Android's HttpURLConnection does not tunnel HTTPS through SOCKS proxies
            // (it sends an absolute-form GET instead of CONNECT, which DPI blocks and
            // the socket layer mishandles), so we do the SOCKS5 handshake manually and
            // layer TLS on top of the tunneled TCP socket.
            socksConnectThenHttpsGet(url)
        } else {
            plainHttpGet(url)
        }

    private fun plainHttpGet(url: URL): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyIp, proxyPort))
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Connection", "close")

            val responseCode = connection.responseCode
            val declaredLength = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }

            var actualLength = 0L
            try {
                val inputStream = if (responseCode in 200..399) connection.inputStream else connection.errorStream
                if (inputStream != null) actualLength = drainLimited(inputStream)
            } catch (_: IOException) {
                // Stream reading failed
            }

            val ok = responseCode in 200..399 && (declaredLength <= 0L || actualLength >= declaredLength)
            logResult(url.host, responseCode, declaredLength, actualLength, ok)
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing ${url.host}: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun socksConnectThenHttpsGet(url: URL): Boolean {
        val host = url.host
        val port = if (url.port != -1) url.port else 443
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(proxyIp, proxyPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socksHandshake(socket, host, port)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket(socket, host, port, true) as SSLSocket
            socket = null // ownership transferred to sslSocket
            try {
                sslSocket.soTimeout = CONNECT_TIMEOUT_MS
                applyHostnameVerification(sslSocket, host)
                sslSocket.startHandshake()
                httpsGetOverTls(sslSocket, url)
            } finally {
                runCatching { sslSocket.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error for $host: ${e.message}")
            false
        } finally {
            socket?.let { runCatching { it.close() } }
        }
    }

    private fun socksHandshake(socket: Socket, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        // Greeting: version 5, one method (no auth)
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        if (readByte(input) != 0x05) throw IOException("Not a SOCKS5 proxy")
        when (val method = readByte(input)) {
            0x00 -> {}
            0xFF -> throw IOException("SOCKS5 proxy has no acceptable auth method")
            else -> throw IOException("SOCKS5 auth method unsupported: $method")
        }

        // CONNECT request with domain name (ATYP 0x03) so the proxy resolves remotely
        val address = host.toByteArray(Charsets.US_ASCII)
        if (address.isEmpty() || address.size > 255) throw IOException("Invalid host length")
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, address.size.toByte()))
        out.write(address)
        out.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()

        if (readByte(input) != 0x05) throw IOException("Invalid SOCKS5 reply")
        val status = readByte(input)
        if (status != 0x00) throw IOException("SOCKS5 CONNECT failed, status=$status")
        readByte(input) // reserved
        // Consume bound address
        when (val atyp = readByte(input)) {
            0x01 -> skip(input, 4 + 2) // IPv4 + port
            0x03 -> { // Domain
                val len = readByte(input)
                skip(input, len + 2)
            }
            0x04 -> skip(input, 16 + 2) // IPv6 + port
            else -> throw IOException("Unknown SOCKS5 ATYP: $atyp")
        }
    }

    private fun httpsGetOverTls(socket: Socket, url: URL): Boolean {
        val path = url.file.ifEmpty { "/" }
        val query = url.query?.let { "?$it" }.orEmpty()
        val request = "GET $path$query HTTP/1.1\r\n" +
            "Host: ${url.host}\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept: */*\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }

        val input = socket.getInputStream()
        val headers = readHttpHeaders(input)
        val responseCode = headers.substringBefore("\r\n")
            .split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)").find(headers)
            ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val chunked = Regex("(?im)^Transfer-Encoding:.*chunked").containsMatchIn(headers)

        var actualLength = 0L
        try {
            actualLength = if (chunked) drainChunked(input) else drainLimited(input)
        } catch (_: IOException) {
            // Stream reading failed / truncated body
        }

        val ok = responseCode in 200..399 && (contentLength <= 0L || actualLength >= contentLength)
        logResult(url.host, responseCode, contentLength, actualLength, ok)
        return ok
    }

    private fun readHttpHeaders(input: InputStream): String {
        val buffer = ByteArrayOutputStream(1024)
        var state = 0 // 0 = other, 1 = saw CR, 2 = saw CRLF, 3 = saw CRLFCR
        while (buffer.size() < 64 * 1024) {
            val b = input.read()
            if (b == -1) break
            buffer.write(b)
            state = when {
                b == '\r'.code -> if (state == 2) 3 else 1
                b == '\n'.code && state == 3 -> return buffer.toString("US-ASCII") // blank line: end of headers
                b == '\n'.code && state == 1 -> 2
                else -> 0
            }
        }
        return buffer.toString("US-ASCII")
    }

    private fun drainChunked(input: InputStream): Long {
        var total = 0L
        while (total < MAX_BODY_BYTES) {
            val sizeLine = readLineAscii(input) ?: break
            val size = sizeLine.substringBefore(";").trim().toIntOrNull(16) ?: break
            if (size == 0) break
            var remaining = size
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) throw IOException("Truncated chunked body")
                remaining -= read
                total += read
            }
            readLineAscii(input) // CRLF terminating the chunk data
        }
        return total
    }

    private fun readLineAscii(input: InputStream): String? {
        val builder = StringBuilder(32)
        while (builder.length < 1024) {
            val b = input.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) return builder.toString()
            if (b != '\r'.code) builder.append(b.toChar())
        }
        return builder.toString()
    }

    private suspend fun drainLimited(input: InputStream): Long = coroutineScope {
        val ctx = coroutineContext
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            var total = 0L
            while (total < MAX_BODY_BYTES) {
                ctx.ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size, (MAX_BODY_BYTES - total).toInt()))
                if (read == -1) break
                total += read
            }
            total
        }
    }

    private fun applyHostnameVerification(socket: SSLSocket, host: String) {
        val params = socket.sslParameters
        params.peerHost = host
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
            // Framework performs full hostname verification during the handshake
            params.endpointIdentificationAlgorithm = "HTTPS"
        }
    }

    private fun logResult(host: String, code: Int, declared: Long, actual: Long, ok: Boolean) {
        if (ok) {
            Log.i(TAG, "Response for $host: $code, Declared: $declared, Actual: $actual")
        } else {
            Log.w(TAG, "Block detected for $host: $code, Declared: $declared, Actual: $actual")
        }
    }

    private fun skip(input: InputStream, count: Int) {
        var left = count
        while (left > 0) {
            val skipped = runCatching { input.skip(left.toLong()) }.getOrDefault(0L)
            if (skipped > 0) {
                left -= skipped.toInt()
            } else if (readByte(input) == -1) {
                throw IOException("Proxy closed connection")
            } else {
                left--
            }
        }
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b == -1) throw IOException("Proxy closed connection")
        return b
    }
}
