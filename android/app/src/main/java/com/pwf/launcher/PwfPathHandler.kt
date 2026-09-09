package com.pwf.launcher

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Serves every byte the WebView is allowed to see, from three places:
 *
 *  - `/web/…`             the shell and player pages, from the APK
 *  - `/runtime/<n>/…`     the active runtime bundle (downloaded or baseline)
 *  - `/apps/<id>.pyxapp`  a Pyxel app the native side already fetched
 *  - `/appshot/<id>.png`  a frame from the last time that app ran
 *  - `/pypkg/<abi>/<wheel>` a Python package a game imports, per interpreter
 *  - `/appdata/<id>/…`    the data folder that shipped alongside such an app
 *  - `/appsave/<id>/…`    what that app wrote the last time it ran
 *
 * Nothing else resolves, so the WebView never has a reason to reach the network.
 * The bundle number in the runtime path is what makes long-lived caching safe:
 * an update changes the URLs, so no stale wasm can survive a swap.
 */
class PwfPathHandler(
    private val ctx: Context,
    private val runtime: RuntimeStore,
    private val library: AppLibrary,
    private val packages: PyPackages,
) : WebViewAssetLoader.PathHandler {

    companion object {
        private const val TAG = "pwf.assets"

        private val MIME = mapOf(
            "html" to "text/html",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "wasm" to "application/wasm",
            "png" to "image/png",
            "ico" to "image/vnd.microsoft.icon",
            "svg" to "image/svg+xml",
            "py" to "text/plain",
            "txt" to "text/plain",
            "md" to "text/plain",
            "whl" to "application/octet-stream",
            "zip" to "application/octet-stream",
            "pyxapp" to "application/octet-stream",
        )

        private val TEXTUAL = setOf(
            "text/html", "text/javascript", "text/css", "application/json", "text/plain",
            "image/svg+xml",
        )

        private const val IMMUTABLE = "public, max-age=31536000, immutable"
        private const val NO_CACHE = "no-store"
    }

    override fun handle(path: String): WebResourceResponse? {
        val clean = path.trimStart('/').substringBefore('?').substringBefore('#')
        if (clean.contains("..")) return status(403, "Forbidden")

        return try {
            when {
                clean.isEmpty() || clean == "index.html" -> asset("web/index.html", IMMUTABLE_NO)
                clean.startsWith("web/") -> asset(clean, IMMUTABLE_NO)
                clean.startsWith("runtime/") -> runtimeFile(clean.removePrefix("runtime/"))
                clean.startsWith("apps/") -> appFile(clean.removePrefix("apps/"))
                clean.startsWith("appshot/") -> shotFile(clean.removePrefix("appshot/"))
                clean.startsWith("pypkg/") -> wheelFile(clean.removePrefix("pypkg/"))
                clean.startsWith("appdata/") ->
                    scopedFile(clean.removePrefix("appdata/")) { library.dataDir(it) }
                clean.startsWith("appsave/") ->
                    scopedFile(clean.removePrefix("appsave/")) { library.saveDir(it) }
                else -> status(404, "Not Found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "配信に失敗: $clean", e)
            status(404, "Not Found")
        }
    }

    private fun asset(rel: String, cache: String): WebResourceResponse =
        respond(rel, ctx.assets.open(rel), cache)

    /** `<bundle>/<rel>` — a mismatched bundle number means the page is stale. */
    private fun runtimeFile(versioned: String): WebResourceResponse {
        val slash = versioned.indexOf('/')
        if (slash <= 0) return status(404, "Not Found")
        val version = versioned.substring(0, slash).toIntOrNull() ?: return status(404, "Not Found")
        val rel = versioned.substring(slash + 1)
        if (version != runtime.bundleVersion()) {
            Log.i(TAG, "旧バンドル $version への要求を拒否 (現行 ${runtime.bundleVersion()})")
            return status(410, "Gone")
        }
        // `rt-<pyxel>/…` is a game pinned to another Pyxel. It resolves against
        // that runtime first and falls through to the bundle's own Pyodide,
        // which same-ABI versions share rather than duplicate.
        if (rel.startsWith("rt-")) {
            val cut = rel.indexOf('/')
            if (cut <= 0) return status(404, "Not Found")
            val found = runtime.openAlternate(rel.substring(3, cut), rel.substring(cut + 1))
                ?: return status(404, "Not Found")
            return respond(rel, found.first, IMMUTABLE, found.second)
        }
        return respond(rel, runtime.open(rel), IMMUTABLE, runtime.length(rel))
    }

    /** A Python wheel a game needs. Named by content, so it never changes. */
    private fun wheelFile(name: String): WebResourceResponse {
        val found = packages.open(name) ?: return status(404, "Not Found")
        return respond(name, found.first, IMMUTABLE, found.second)
    }

    /** A game's picture. It changes as the game is played, so it is not cached. */
    private fun shotFile(name: String): WebResourceResponse {
        val file = library.shotFile(name.removeSuffix(".png"))
        if (!file.isFile) return status(404, "Not Found")
        return respond(name, file.inputStream(), NO_CACHE, file.length())
    }

    private fun appFile(name: String): WebResourceResponse {
        val id = name.removeSuffix(".pyxapp")
        val file = library.file(id)
        if (!file.isFile) return status(404, "Not Found")
        return respond(name, file.inputStream(), IMMUTABLE, file.length())
    }

    /** `<id>/<rel>`, resolved against whichever per-app directory is asked for. */
    private fun scopedFile(scoped: String, root: (String) -> File): WebResourceResponse {
        val slash = scoped.indexOf('/')
        if (slash <= 0) return status(404, "Not Found")
        val dir = root(scoped.substring(0, slash))
        val rel = scoped.substring(slash + 1)

        // The listing carries sizes so the page can present a file's length
        // without having fetched it.
        if (rel == AppLibrary.DATA_INDEX) return listing(dir)

        val file = File(dir, rel)
        if (!file.isFile) return status(404, "Not Found")
        // Save data changes between launches, so it must not be cached.
        return respond(file.name, file.inputStream(), NO_CACHE, file.length())
    }

    private fun listing(dir: File): WebResourceResponse {
        val index = File(dir, AppLibrary.DATA_INDEX)
        val out = org.json.JSONArray()
        if (index.isFile) {
            val paths = org.json.JSONArray(index.readText())
            for (i in 0 until paths.length()) {
                val rel = paths.getString(i)
                val file = File(dir, rel)
                if (!file.isFile) continue
                out.put(org.json.JSONArray().put(rel).put(file.length()))
            }
        }
        val bytes = out.toString().toByteArray()
        return respond(".json", ByteArrayInputStream(bytes), NO_CACHE, bytes.size.toLong())
    }

    private fun respond(
        name: String,
        stream: InputStream,
        cache: String,
        length: Long = -1,
    ): WebResourceResponse {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MIME[ext] ?: "application/octet-stream"
        val encoding = if (mime in TEXTUAL) "utf-8" else null
        val headers = mutableMapOf(
            "Cache-Control" to cache,
            "X-Content-Type-Options" to "nosniff",
        )
        if (length >= 0) headers["Content-Length"] = length.toString()
        return WebResourceResponse(mime, encoding, 200, "OK", headers, stream)
    }

    private fun status(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", code, reason, emptyMap(),
            ByteArrayInputStream(reason.toByteArray()),
        )
}

/** The shell and player are versioned with the APK, so they revalidate instead. */
private const val IMMUTABLE_NO = "no-cache"
