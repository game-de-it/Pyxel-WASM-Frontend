package com.pwf.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Python packages a game needs but the runtime does not carry.
 *
 * The bundled Pyodide is the core distribution: an interpreter and the standard
 * library, and nothing else. A game that imports pymunk or numpy fails on the
 * import, and there is no way for it to ask for what it needs. So the launcher
 * asks on its behalf: it reads the game's imports, works out which are neither
 * standard library nor pyxel, and fetches wheels for them.
 *
 * Wheels are fetched **here**, on the native side, and served back from the
 * app's own origin. The page never reaches out — that line is what keeps a
 * WebView holding a JavascriptInterface from ever loading an outside URL.
 */
class PyPackages(private val ctx: Context, private val runtime: RuntimeStore) {

    private val dir = File(ctx.filesDir, "pypkg").apply { mkdirs() }
    private val index = File(dir, "index.json")

    companion object {
        private const val TAG = "pwf.pypkg"
        private const val PYODIDE_CDN = "https://cdn.jsdelivr.net/pyodide"
        private const val PYPI = "https://pypi.org/pypi"

        /** Imported everywhere and provided by the runtime itself. */
        private val PROVIDED = setOf("pyxel", "js", "pyodide", "micropip", "__future__")
    }

    // ---- what is installed -------------------------------------------------

    fun list(): JSONArray = try {
        if (index.isFile) JSONArray(index.readText()) else JSONArray()
    } catch (e: Exception) {
        Log.e(TAG, "索引を読めません", e); JSONArray()
    }

    fun has(name: String): Boolean = entry(name) != null

    private fun entry(name: String): JSONObject? {
        val entries = list()
        for (i in 0 until entries.length()) {
            val it = entries.getJSONObject(i)
            if (it.getString("name").equals(name, ignoreCase = true)) return it
        }
        return null
    }

    /** A wheel by file name, for serving. Nothing outside this directory. */
    fun open(fileName: String): Pair<InputStream, Long>? {
        require(!fileName.contains("/") && !fileName.contains("..")) { "不正な名前" }
        val file = File(dir, fileName)
        if (!file.isFile) return null
        return file.inputStream() to file.length()
    }

    /** The wheels a set of packages needs, as paths the page can fetch. */
    fun urlsFor(names: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (name in names) {
            val found = entry(name) ?: continue
            for (dep in (found.optJSONArray("files") ?: JSONArray()).strings()) {
                out.add("/pypkg/$dep")
            }
        }
        return out.toList()
    }

    fun remove(name: String) {
        val entries = list()
        val kept = JSONArray()
        var dropped: JSONObject? = null
        for (i in 0 until entries.length()) {
            val it = entries.getJSONObject(i)
            if (it.getString("name").equals(name, ignoreCase = true)) dropped = it else kept.put(it)
        }
        index.writeText(kept.toString())

        // A wheel shared with another package stays; only what nobody else
        // names is deleted.
        val stillUsed = mutableSetOf<String>()
        for (i in 0 until kept.length()) {
            stillUsed += (kept.getJSONObject(i).optJSONArray("files") ?: JSONArray()).strings()
        }
        for (f in (dropped?.optJSONArray("files") ?: JSONArray()).strings()) {
            if (f !in stillUsed) File(dir, f).delete()
        }
    }

    // ---- what a game needs -------------------------------------------------

    /**
     * The modules a .pyxapp imports and cannot get from the runtime.
     *
     * Reading the source is the only way to know before running it, and running
     * it is what fails. Nothing here is executed: the imports are read as text.
     */
    fun missingFor(appFile: File): List<String> {
        val imported = LinkedHashSet<String>()
        val own = mutableSetOf<String>()
        ZipFile(appFile).use { zip ->
            val entries = zip.entries().toList()
            // The app's own modules: anything sitting beside its startup script.
            val root = entries.firstOrNull { it.name.endsWith("/.pyxapp_startup_script") }
                ?.name?.substringBeforeLast("/.pyxapp_startup_script") ?: ""
            for (e in entries) {
                if (e.isDirectory) continue
                val rel = e.name.removePrefix("$root/")
                val top = rel.substringBefore("/")
                if (top.endsWith(".py")) own += top.removeSuffix(".py")
                else if (!rel.contains(".")) own += top
                if (rel.contains("/")) own += top
                if (!e.name.endsWith(".py")) continue
                zip.getInputStream(e).bufferedReader().forEachLine { line ->
                    IMPORT.find(line)?.let { imported += it.groupValues[1].substringBefore(".") }
                }
            }
        }
        val stdlib = stdlibNames()
        return imported.filter {
            it.isNotEmpty() && it !in own && it !in stdlib && it !in PROVIDED && !has(it)
        }
    }

    private val IMPORT = Regex("""^\s*(?:from\s+([A-Za-z_][\w.]*)|import\s+([A-Za-z_][\w.]*))""")
        .let { re ->
            // One group either way, so callers can read groupValues[1].
            Regex("""^\s*(?:from|import)\s+([A-Za-z_][\w.]*)""")
        }

    /** Read out of the runtime's own stdlib archive rather than hardcoded. */
    private fun stdlibNames(): Set<String> {
        val names = mutableSetOf<String>()
        try {
            ZipInputStream(runtime.open("pyodide/python_stdlib.zip").buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val top = e.name.trimStart('/').substringBefore("/")
                    if (top.isNotEmpty()) names += top.removeSuffix(".py")
                    e = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "標準ライブラリの一覧を読めません", e)
        }
        return names
    }

    // ---- getting one -------------------------------------------------------

    /**
     * Fetches a package and everything it needs.
     *
     * Two places are asked, in order: the Pyodide distribution that matches the
     * bundled interpreter, then PyPI for a wheel built against the same ABI.
     * Anything else cannot load, so it is not offered.
     */
    fun install(name: String, onProgress: (String, Int) -> Unit): JSONObject {
        val wanted = ArrayDeque(listOf(name))
        val seen = mutableSetOf<String>()
        val files = JSONArray()
        var version = ""

        while (wanted.isNotEmpty()) {
            val current = wanted.removeFirst()
            val key = current.lowercase()
            if (!seen.add(key)) continue
            if (key != name.lowercase() && has(current)) continue   // already here

            onProgress(current, files.length())
            val got = fetchOne(current)
                ?: throw IllegalArgumentException("$current に合う wheel が見つかりません")
            files.put(got.file)
            if (key == name.lowercase()) version = got.version
            wanted.addAll(got.depends)
        }

        val entry = JSONObject()
            .put("name", name)
            .put("version", version)
            .put("files", files)
            .put("installedAt", System.currentTimeMillis())

        val entries = list()
        val kept = JSONArray().put(entry)
        for (i in 0 until entries.length()) {
            val it = entries.getJSONObject(i)
            if (!it.getString("name").equals(name, ignoreCase = true)) kept.put(it)
        }
        index.writeText(kept.toString())
        Log.i(TAG, "$name $version を導入 (${files.length()} wheel)")
        return entry
    }

    private data class Fetched(val file: String, val version: String, val depends: List<String>)

    private fun fetchOne(name: String): Fetched? {
        fromPyodide(name)?.let { return it }
        return fromPypi(name)
    }

    /** The distribution that shipped with this interpreter, so it always fits. */
    private fun fromPyodide(name: String): Fetched? {
        val lock = lock() ?: return null
        val packages = lock.optJSONObject("packages") ?: return null
        val key = packages.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }
            ?: return null
        val info = packages.getJSONObject(key)
        val fileName = info.getString("file_name")
        val pyodide = runtime.manifest().optString("pyodide")
        download("$PYODIDE_CDN/v$pyodide/full/$fileName", fileName)
        val depends = (info.optJSONArray("depends") ?: JSONArray()).let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        return Fetched(fileName, info.optString("version"), depends)
    }

    /** Anything else, if it publishes a wheel this interpreter can load. */
    private fun fromPypi(name: String): Fetched? {
        val body = try {
            JSONObject(Net.getText("$PYPI/${Uri.encode(name)}/json"))
        } catch (e: Exception) {
            Log.w(TAG, "PyPI から $name を引けません", e)
            return null
        }
        val releases = body.optJSONObject("releases") ?: return null
        val versions = releases.keys().asSequence().toList().sortedWith(VERSION).reversed()
        for (version in versions) {
            val files = releases.optJSONArray(version) ?: continue
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val fileName = file.optString("filename")
                if (!accepts(fileName)) continue
                download(file.getString("url"), fileName)
                return Fetched(fileName, version, requiresOf(File(dir, fileName)))
            }
        }
        return null
    }

    /** Only a wheel this interpreter can actually load is worth downloading. */
    private fun accepts(fileName: String): Boolean {
        if (!fileName.endsWith(".whl")) return false
        if (fileName.endsWith("-py3-none-any.whl")) return true
        val platform = runtime.manifest().optString("platform")   // emscripten_5_0_3
        val abi = runtime.manifest().optString("abi")             // 2026_0
        val cp = cpTag() ?: return false
        return fileName.endsWith("-$cp-$cp-pyemscripten_${abi}_wasm32.whl") ||
            fileName.endsWith("-$cp-abi3-${platform}_wasm32.whl")
    }

    /** cp314 and the like, taken from the interpreter rather than assumed. */
    private fun cpTag(): String? {
        val python = lock()?.optJSONObject("info")?.optString("python") ?: return null
        val parts = python.split(".")
        if (parts.size < 2) return null
        return "cp${parts[0]}${parts[1]}"
    }

    private fun lock(): JSONObject? = try {
        JSONObject(runtime.open("pyodide/pyodide-lock.json").reader().use { it.readText() })
    } catch (e: Exception) {
        Log.w(TAG, "pyodide-lock.json を読めません", e); null
    }

    /** What a wheel says it needs, ignoring anything behind an extra. */
    private fun requiresOf(wheel: File): List<String> {
        val out = mutableListOf<String>()
        try {
            ZipFile(wheel).use { zip ->
                val metadata = zip.entries().asSequence()
                    .firstOrNull { it.name.endsWith(".dist-info/METADATA") } ?: return emptyList()
                zip.getInputStream(metadata).bufferedReader().forEachLine { line ->
                    if (!line.startsWith("Requires-Dist:")) return@forEachLine
                    val body = line.removePrefix("Requires-Dist:").trim()
                    if (body.contains("extra ==")) return@forEachLine
                    val name = body.split(Regex("[\\s;\\[<>=!~(]"))[0].trim()
                    if (name.isNotEmpty()) out += name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "METADATA を読めません: ${wheel.name}", e)
        }
        return out
    }

    private fun download(url: String, fileName: String) {
        val target = File(dir, fileName)
        if (target.isFile && target.length() > 0) return       // already fetched
        val staging = File(dir, "$fileName.part")
        Net.download(url, staging)
        staging.renameTo(target)
    }

    /** Newest first, comparing numbers as numbers. */
    private val VERSION = Comparator<String> { a, b ->
        val x = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0)
            if (d != 0) return@Comparator d
        }
        // A plain release beats a pre-release of the same numbers.
        b.length.compareTo(a.length)
    }
}

/** JSONArray is not iterable in Kotlin, and every use here wants strings. */
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

private object Uri {
    fun encode(s: String): String = android.net.Uri.encode(s)
}
