package com.pwf.launcher

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP client. The platform one is enough here and costs no dependency. */
object Net {
    private const val TIMEOUT_MS = 30_000
    private const val MAX_BYTES = 64L * 1024 * 1024

    private fun open(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https" || parsed.host == "127.0.0.1" || parsed.host == "localhost") {
            "非 TLS の取得元は localhost のみ許可されています: $url"
        }
        val conn = parsed.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "pwf-launcher")
        return conn
    }

    fun get(url: String): ByteArray {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code — $url")
            return drain(conn.inputStream)
        } finally {
            conn.disconnect()
        }
    }

    fun getText(url: String): String = String(get(url), Charsets.UTF_8)

    /** Whether the resource is there; used to skip files an old tag never had. */
    fun exists(url: String): Boolean {
        val conn = open(url)
        return try {
            conn.requestMethod = "HEAD"
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams a download straight to disk.
     *
     * A Pyodide core archive is far too big to want in memory, and the caller
     * wants to say how far along it is while it arrives.
     */
    fun download(url: String, target: File, onBytes: (Long, Long) -> Unit = { _, _ -> }) {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code — $url")
            val total = conn.contentLengthLong
            target.parentFile?.mkdirs()
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onBytes(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun drain(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        stream.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw java.io.IOException("応答が大きすぎます")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
