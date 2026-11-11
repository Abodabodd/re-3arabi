package com.witanime

import android.annotation.SuppressLint
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Local `127.0.0.1` on-the-fly AES-128-CTR decrypting HTTP Range proxy for MEGA.nz video embeds.
 * Fully self-contained without external network helpers.
 */
object WitAnimeMegaServer {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class MegaKeySpec(
        val fileId: String,
        val rawKeyToken: String,
        val aesKey16: ByteArray,
        val nonce8: ByteArray
    )

    private data class MegaNodeEntry(
        val fileId: String,
        val dlBaseUrl: String,
        val fileSize: Long,
        val expiresAtMs: Long
    )

    private val nodeCache = ConcurrentHashMap<String, MegaNodeEntry>()
    private val keyRegistry = ConcurrentHashMap<String, MegaKeySpec>()

    @SuppressLint("CustomX509TrustManager")
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllSslSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        sslContext.socketFactory
    }

    private val streamHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(trustAllSslSocketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val authToken: String by lazy {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val workerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "WitAnime-MegaProxy").apply { isDaemon = true }
    }

    private val FILE_EMBED_REGEX =
        Regex("""mega\.(?:nz|co\.nz)/(?:embed|file)/([A-Za-z0-9_-]+)#([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val LEGACY_HASH_REGEX =
        Regex("""mega\.(?:nz|co\.nz)/#!([A-Za-z0-9_-]+)!([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

    fun parseMegaEmbedUrl(url: String): MegaKeySpec? {
        val m = FILE_EMBED_REGEX.find(url) ?: LEGACY_HASH_REGEX.find(url) ?: return null
        val fileId = m.groupValues[1].trim()
        val keyToken = m.groupValues[2].trim().trimEnd('/')
        if (fileId.isEmpty() || keyToken.isEmpty()) return null

        val stdB64 = keyToken.replace('-', '+').replace('_', '/').let { s ->
            val rem = s.length % 4
            if (rem == 0) s else s + "=".repeat(4 - rem)
        }
        val raw = runCatching { Base64.decode(stdB64, Base64.DEFAULT) }.getOrNull() ?: return null

        return when {
            raw.size >= 32 -> {
                val bb = ByteBuffer.wrap(raw, 0, 32).order(ByteOrder.BIG_ENDIAN)
                val k = IntArray(8) { bb.int }
                val aesKey = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                    .putInt(k[0] xor k[4])
                    .putInt(k[1] xor k[5])
                    .putInt(k[2] xor k[6])
                    .putInt(k[3] xor k[7])
                    .array()
                val nonce = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    .putInt(k[4])
                    .putInt(k[5])
                    .array()
                MegaKeySpec(fileId, keyToken, aesKey, nonce)
            }
            raw.size >= 16 -> {
                MegaKeySpec(fileId, keyToken, raw.copyOfRange(0, 16), ByteArray(8))
            }
            else -> null
        }
    }

    private fun resolveNodeBlocking(fileId: String, forceRefresh: Boolean = false): MegaNodeEntry? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            nodeCache[fileId]?.let { cached ->
                if (cached.expiresAtMs > now) return cached
            }
        }
        return try {
            val payload = """[{"a":"g","g":1,"ssl":2,"p":"$fileId"}]"""
            val req = Request.Builder()
                .url("https://g.api.mega.co.nz/cs?id=1")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://mega.nz")
                .header("Referer", "https://mega.nz/")
                .build()
            streamHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.trim() ?: return null
                if (!body.startsWith("[")) return null
                val arr = JSONArray(body)
                val obj = arr.optJSONObject(0) ?: return null
                val rawDl = obj.optString("g").trim()
                val size = obj.optLong("s", -1L)
                if (rawDl.isEmpty() || size <= 0L) return null
                val httpsDl = rawDl.replaceFirst("http://", "https://").trimEnd('/')
                val entry = MegaNodeEntry(
                    fileId = fileId,
                    dlBaseUrl = httpsDl,
                    fileSize = size,
                    expiresAtMs = now + 15 * 60_000L
                )
                nodeCache[fileId] = entry
                entry
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun ensureServerPort(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) {
            return existing.localPort
        }
        val created = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = created
        Thread({
            while (!created.isClosed) {
                try {
                    val client = created.accept()
                    workerPool.execute { handleClientSocket(client) }
                } catch (_: Exception) {
                    if (created.isClosed) break
                }
            }
        }, "WitAnime-MegaAccept").apply {
            isDaemon = true
            start()
        }
        return created.localPort
    }

    /**
     * Validates a MEGA embed/file URL against `g.api.mega.co.nz` and returns a local
     * `http://127.0.0.1:<port>/...` MP4 stream URL ready for ExoPlayer.
     */
    suspend fun registerMegaStream(embedUrl: String): String? = withContext(Dispatchers.IO) {
        val spec = parseMegaEmbedUrl(embedUrl) ?: return@withContext null
        val node = resolveNodeBlocking(spec.fileId) ?: return@withContext null
        if (node.fileSize <= 1024L) return@withContext null

        val slotId = "${spec.fileId}_${spec.rawKeyToken.take(12)}"
        keyRegistry[slotId] = spec
        val port = ensureServerPort()
        "http://127.0.0.1:$port/mega/$authToken/$slotId/video.mp4"
    }

    private fun handleClientSocket(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 30_000
                sock.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1].substringBefore('?')

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                val out = BufferedOutputStream(sock.getOutputStream(), 32 * 1024)
                if (method != "GET" && method != "HEAD") {
                    writeSimpleError(out, 405, "Method Not Allowed")
                    return
                }
                val segments = rawPath.trim('/').split('/')
                if (segments.size < 4 || segments[0] != "mega" || segments[1] != authToken) {
                    writeSimpleError(out, 403, "Forbidden")
                    return
                }
                val slotId = segments[2]
                val keySpec = keyRegistry[slotId]
                if (keySpec == null) {
                    writeSimpleError(out, 404, "Not Found")
                    return
                }

                var node = resolveNodeBlocking(keySpec.fileId, forceRefresh = false)
                if (node == null) {
                    writeSimpleError(out, 502, "Bad Gateway")
                    return
                }

                val totalSize = node.fileSize
                var hasRange = false
                var startByte = 0L
                var endByte = totalSize - 1L

                if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
                    val spec = rangeHeader.substringAfter('=').substringBefore(',').trim()
                    val dashIdx = spec.indexOf('-')
                    if (dashIdx >= 0) {
                        val left = spec.substring(0, dashIdx).trim()
                        val right = spec.substring(dashIdx + 1).trim()
                        if (left.isEmpty() && right.isNotEmpty()) {
                            val suffixLen = right.toLongOrNull() ?: 0L
                            if (suffixLen > 0L) {
                                startByte = (totalSize - suffixLen).coerceAtLeast(0L)
                                endByte = totalSize - 1L
                                hasRange = true
                            }
                        } else if (left.isNotEmpty()) {
                            val parsedStart = left.toLongOrNull()
                            if (parsedStart != null && parsedStart in 0 until totalSize) {
                                startByte = parsedStart
                                endByte = right.toLongOrNull()?.coerceAtMost(totalSize - 1L) ?: (totalSize - 1L)
                                if (endByte >= startByte) {
                                    hasRange = true
                                }
                            } else if (parsedStart != null && parsedStart >= totalSize) {
                                val hdr = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                                        "Content-Range: bytes */$totalSize\r\n" +
                                        "Connection: close\r\n\r\n"
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                out.flush()
                                return
                            }
                        }
                    }
                }

                val contentLen = endByte - startByte + 1L
                val statusLine = if (hasRange) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
                val headerSb = StringBuilder(256)
                headerSb.append(statusLine).append("\r\n")
                headerSb.append("Content-Type: video/mp4\r\n")
                headerSb.append("Accept-Ranges: bytes\r\n")
                headerSb.append("Content-Length: ").append(contentLen).append("\r\n")
                if (hasRange) {
                    headerSb.append("Content-Range: bytes ").append(startByte).append('-').append(endByte)
                        .append('/').append(totalSize).append("\r\n")
                }
                headerSb.append("Connection: close\r\n\r\n")

                if (method == "HEAD") {
                    out.write(headerSb.toString().toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }
                val alignedStart = (startByte / 16L) * 16L
                val skipBytes = (startByte - alignedStart).toInt()
                val blockIndex = alignedStart / 16L

                fun openUpstream(targetNode: MegaNodeEntry) = streamHttpClient.newCall(
                    Request.Builder()
                        .url("${targetNode.dlBaseUrl}/$alignedStart-$endByte")
                        .header("User-Agent", USER_AGENT)
                        .build()
                ).execute()

                var upstreamResp = openUpstream(node)
                if (!upstreamResp.isSuccessful) {
                    upstreamResp.close()
                    node = resolveNodeBlocking(keySpec.fileId, forceRefresh = true) ?: run {
                        writeSimpleError(out, 502, "Upstream Expired")
                        return
                    }
                    upstreamResp = openUpstream(node)
                }

                upstreamResp.use { resp ->
                    if (!resp.isSuccessful) {
                        writeSimpleError(out, 502, "Upstream Error")
                        return
                    }
                    val bodyStream = resp.body?.byteStream() ?: run {
                        writeSimpleError(out, 502, "Empty Upstream")
                        return
                    }

                    val iv16 = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .put(keySpec.nonce8)
                        .putLong(blockIndex)
                        .array()
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keySpec.aesKey16, "AES"),
                        IvParameterSpec(iv16)
                    )

                    out.write(headerSb.toString().toByteArray(Charsets.US_ASCII))

                    val inBuf = ByteArray(32 * 1024)
                    var toSkip = skipBytes
                    var remaining = contentLen

                    while (remaining > 0L) {
                        val n = bodyStream.read(inBuf)
                        if (n <= 0) break
                        val dec = cipher.update(inBuf, 0, n) ?: continue
                        var offset = 0
                        var avail = dec.size
                        if (toSkip > 0) {
                            if (avail <= toSkip) {
                                toSkip -= avail
                                continue
                            } else {
                                offset = toSkip
                                avail -= toSkip
                                toSkip = 0
                            }
                        }
                        val writeCount = minOf(avail.toLong(), remaining).toInt()
                        if (writeCount > 0) {
                            out.write(dec, offset, writeCount)
                            remaining -= writeCount.toLong()
                        }
                    }
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun writeSimpleError(out: BufferedOutputStream, code: Int, msg: String) {
        runCatching {
            val resp = "HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }
}
