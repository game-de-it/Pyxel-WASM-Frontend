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

    /**
     * What a particular Pyxel version's interpreter can load.
     *
     * A wheel is built against one interpreter, and a game pinned to an older
     * Pyxel runs on an older Pyodide with a different Python and a different
     * ABI. Everything here is therefore asked in terms of a runtime rather than
     * of "the" runtime.
     */
    data class Target(
        val runtime: String,     // "" for the bundle's own Pyxel
        val cp: String,          // cp314
        val abi: String,         // 2026_0
        val platform: String,    // emscripten_5_0_3
        val pyodide: String,     // 314.0.4, for that distribution's own packages
        val lock: JSONObject,
    )

    /** The interpreter behind a game's chosen Pyxel, or null if it is unknown. */
    fun targetFor(runtimeVersion: String): Target? {
        val version = runtimeVersion.trim()
        val lock = lockFor(version) ?: return null
        val info = lock.optJSONObject("info") ?: return null
        val python = info.optString("python").split(".")
        if (python.size < 2) return null

        // A version with its own interpreter says so in its descriptor;
        // anything else is riding on the bundle's.
        var pyodide = runtime.manifest().optString("pyodide")
        val installed = runtime.installedRuntimes()
        for (i in 0 until installed.length()) {
            val it = installed.getJSONObject(i)
            if (it.optString("pyxel") == version && !it.optBoolean("shared", false)) {
                pyodide = it.optString("pyodide")
            }
        }

        Log.i(TAG, "target($version) = cp${python[0]}${python[1]} / " +
            "${info.optString("abi_version")} / ${info.optString("platform")} / $pyodide")
        return Target(
            runtime = version,
            cp = "cp${python[0]}${python[1]}",
            abi = info.optString("abi_version"),
            platform = info.optString("platform"),
            pyodide = pyodide,
            lock = lock,
        )
    }

    /**
     * Wheels used to sit in one flat directory, from when there was only ever
     * one interpreter to build them for. They were all built for the bundle's
     * own ABI, so that is where they belong now.
     */
    fun migrate() {
        val entries = list()
        var moved = 0
        val bundled = targetFor("") ?: return
        val abi = bundled.abi
        if (abi.isEmpty()) return
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.optString("abi").isNotEmpty()) {
                // Moved by an earlier run, before the interpreter was recorded.
                if (entry.optString("python").isEmpty() && entry.optString("abi") == abi) {
                    entry.put("python", bundled.cp)
                    moved++
                }
                continue
            }
            entry.put("abi", abi).put("python", bundled.cp)
            for (name in (entry.optJSONArray("files") ?: JSONArray()).strings()) {
                val old = File(dir, name)
                if (!old.isFile) continue
                val into = File(dir, abi).apply { mkdirs() }
                if (old.renameTo(File(into, name))) moved++
            }
        }
        if (moved > 0) {
            index.writeText(entries.toString())
            Log.i(TAG, "既存の wheel $moved 件を abi $abi に揃えました")
        }
    }

    private fun lockFor(version: String): JSONObject? = try {
        val text = if (version.isEmpty()) {
            runtime.open("pyodide/pyodide-lock.json").reader().use { it.readText() }
        } else {
            // Falls through to the bundle's own when the version shares it.
            runtime.openAlternate(version, "pyodide/pyodide-lock.json")
                ?.first?.reader()?.use { it.readText() }
        }
        text?.let { JSONObject(it) }
    } catch (e: Exception) {
        Log.w(TAG, "pyodide-lock.json を読めません ($version)", e); null
    }

    companion object {
        private const val TAG = "pwf.pypkg"
        private const val PYODIDE_CDN = "https://cdn.jsdelivr.net/pyodide"
        private const val PYPI = "https://pypi.org/pypi"

        /** Imported everywhere and provided by the runtime itself. */
        private val PROVIDED = setOf("pyxel", "js", "pyodide", "micropip", "__future__")

        /**
         * Standard library modules compiled into the interpreter.
         *
         * python_stdlib.zip holds only what ships as .py files, so `import math`
         * looks like a missing package to a reader of the source — and PyPI has
         * an unrelated project called `math`. Fetching that would be worse than
         * useless, so these names are excluded by name.
         *
         * Derived from `sys.stdlib_module_names` minus the archive's entries;
         * a few are for platforms this interpreter does not have, which costs
         * nothing since none of them may ever be downloaded.
         */
        private val BUILTIN = (
            "_abc _aix_support _ast _asyncio _bisect _blake2 _bz2 _codecs _codecs_cn " +
            "_codecs_hk _codecs_iso2022 _codecs_jp _codecs_kr _codecs_tw _collections " +
            "_contextvars _crypt _csv _ctypes _curses _curses_panel _datetime _dbm " +
            "_decimal _elementtree _functools _gdbm _hashlib _heapq _imp _io _json " +
            "_locale _lsprof _lzma _md5 _msi _multibytecodec _multiprocessing _opcode " +
            "_operator _overlapped _pickle _posixshmem _posixsubprocess _queue _random " +
            "_scproxy _sha1 _sha2 _sha3 _signal _socket _sqlite3 _sre _stat _statistics " +
            "_string _struct _symtable _thread _tkinter _tokenize _tracemalloc _typing " +
            "_uuid _warnings _weakref _winapi _zoneinfo array atexit audioop binascii " +
            "builtins cmath crypt curses dbm errno faulthandler fcntl gc grp itertools " +
            "marshal math mmap msvcrt nis nt ossaudiodev posix pwd pyexpat readline " +
            "resource select spwd sys syslog termios time tkinter unicodedata winreg " +
            "winsound zlib "
            ).trim().split(" ").toSet()
    }

    // ---- what is installed -------------------------------------------------

    fun list(): JSONArray = try {
        if (index.isFile) JSONArray(index.readText()) else JSONArray()
    } catch (e: Exception) {
        Log.e(TAG, "索引を読めません", e); JSONArray()
    }

    fun has(name: String, abi: String): Boolean = entry(name, abi) != null

    private fun entry(name: String, abi: String): JSONObject? {
        val entries = list()
        for (i in 0 until entries.length()) {
            val it = entries.getJSONObject(i)
            if (it.getString("name").equals(name, ignoreCase = true) &&
                it.optString("abi") == abi
            ) return it
        }
        return null
    }

    /** A wheel, for serving: `<abi>/<file>`. Nothing outside this directory. */
    fun open(path: String): Pair<InputStream, Long>? {
        require(!path.contains("..")) { "不正な名前" }
        val parts = path.split("/")
        require(parts.size == 2) { "不正な名前" }
        val file = File(File(dir, parts[0]), parts[1])
        if (!file.isFile) return null
        return file.inputStream() to file.length()
    }

    /**
     * The wheels a set of packages needs, for one interpreter.
     *
     * Only what was built for that ABI is offered. A game whose Pyxel version
     * changed simply finds nothing here, and the scan asks for it again — the
     * wheels it had cannot load on the interpreter it now runs on.
     */
    fun urlsFor(names: List<String>, abi: String): List<String> {
        val out = LinkedHashSet<String>()
        for (name in names) {
            val found = entry(name, abi) ?: continue
            for (dep in (found.optJSONArray("files") ?: JSONArray()).strings()) {
                out.add("/pypkg/$abi/$dep")
            }
        }
        return out.toList()
    }

    fun remove(name: String, abi: String) {
        val entries = list()
        val kept = JSONArray()
        var dropped: JSONObject? = null
        for (i in 0 until entries.length()) {
            val it = entries.getJSONObject(i)
            if (it.getString("name").equals(name, ignoreCase = true) &&
                it.optString("abi") == abi
            ) dropped = it else kept.put(it)
        }
        index.writeText(kept.toString())

        // A wheel shared with another package stays; only what nobody else
        // names is deleted.
        val stillUsed = mutableSetOf<String>()
        for (i in 0 until kept.length()) {
            if (kept.getJSONObject(i).optString("abi") != abi) continue
            stillUsed += (kept.getJSONObject(i).optJSONArray("files") ?: JSONArray()).strings()
        }
        for (f in (dropped?.optJSONArray("files") ?: JSONArray()).strings()) {
            if (f !in stillUsed) File(File(dir, abi), f).delete()
        }
    }

    // ---- what a game needs -------------------------------------------------

    /**
     * The modules a .pyxapp imports and cannot get from the runtime.
     *
     * Reading the source is the only way to know before running it, and running
     * it is what fails. Nothing here is executed: the imports are read as text.
     */
    fun missingFor(appFile: File, target: Target): List<String> {
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
        val stdlib = stdlibNames(target)
        return imported.filter {
            it.isNotEmpty() && it !in own && it !in stdlib && it !in BUILTIN &&
                it !in PROVIDED && !has(it, target.abi)
        }
    }

    private val IMPORT = Regex("""^\s*(?:from\s+([A-Za-z_][\w.]*)|import\s+([A-Za-z_][\w.]*))""")
        .let { re ->
            // One group either way, so callers can read groupValues[1].
            Regex("""^\s*(?:from|import)\s+([A-Za-z_][\w.]*)""")
        }

    /** Read out of that interpreter's own stdlib archive rather than hardcoded. */
    private fun stdlibNames(target: Target): Set<String> {
        val names = mutableSetOf<String>()
        try {
            ZipInputStream(stdlib(target).buffered()).use { zip ->
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

    private fun stdlib(target: Target): InputStream {
        val rel = "pyodide/python_stdlib.zip"
        if (target.runtime.isEmpty()) return runtime.open(rel)
        return runtime.openAlternate(target.runtime, rel)?.first ?: runtime.open(rel)
    }

    // ---- getting one -------------------------------------------------------

    /**
     * Fetches a package and everything it needs.
     *
     * Two places are asked, in order: the Pyodide distribution that matches the
     * game's interpreter, then PyPI for a wheel built against the same ABI.
     * Anything else cannot load, so it is not offered.
     */
    fun install(name: String, target: Target, onProgress: (String, Int) -> Unit): JSONObject {
        val wanted = ArrayDeque(listOf(name))
        val seen = mutableSetOf<String>()
        val files = JSONArray()
        var version = ""

        while (wanted.isNotEmpty()) {
            val current = wanted.removeFirst()
            val key = current.lowercase()
            if (!seen.add(key)) continue
            if (key != name.lowercase() && has(current, target.abi)) continue   // already here

            onProgress(current, files.length())
            val got = fetchOne(current, target)
                ?: throw IllegalArgumentException("$current に合う wheel が見つかりません")
            files.put(got.file)
            if (key == name.lowercase()) version = got.version
            wanted.addAll(got.depends)
        }

        val entry = JSONObject()
            .put("name", name)
            .put("version", version)
            .put("abi", target.abi)
            .put("python", target.cp)
            .put("files", files)
            .put("installedAt", System.currentTimeMillis())

        // Only the same package for the same ABI is superseded: the cp313 build
        // and the cp314 build of one package are two installs, not one.
        val entries = list()
        val kept = JSONArray().put(entry)
        for (i in 0 until entries.length()) {
            val it = entries.getJSONObject(i)
            if (!it.getString("name").equals(name, ignoreCase = true) ||
                it.optString("abi") != target.abi
            ) kept.put(it)
        }
        index.writeText(kept.toString())
        Log.i(TAG, "$name $version を導入 (${files.length()} wheel, abi ${target.abi})")
        return entry
    }

    private data class Fetched(val file: String, val version: String, val depends: List<String>)

    private fun fetchOne(name: String, target: Target): Fetched? {
        fromPyodide(name, target)?.let { return it }
        return fromPypi(name, target)
    }

    /** The distribution that shipped with this interpreter, so it always fits. */
    private fun fromPyodide(name: String, target: Target): Fetched? {
        val packages = target.lock.optJSONObject("packages") ?: return null
        val key = packages.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }
            ?: return null
        val info = packages.getJSONObject(key)
        val fileName = info.getString("file_name")
        download("$PYODIDE_CDN/v${target.pyodide}/full/$fileName", fileName, target.abi)
        val depends = (info.optJSONArray("depends") ?: JSONArray()).let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        return Fetched(fileName, info.optString("version"), depends)
    }

    /** Anything else, if it publishes a wheel this interpreter can load. */
    private fun fromPypi(name: String, target: Target): Fetched? {
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
                if (!accepts(fileName, target)) continue
                download(file.getString("url"), fileName, target.abi)
                return Fetched(fileName, version, requiresOf(File(File(dir, target.abi), fileName)))
            }
        }
        return null
    }

    /** Only a wheel this interpreter can actually load is worth downloading. */
    private fun accepts(fileName: String, target: Target): Boolean {
        if (!fileName.endsWith(".whl")) return false
        if (fileName.endsWith("-py3-none-any.whl")) return true
        return fileName.endsWith("-${target.cp}-${target.cp}-pyemscripten_${target.abi}_wasm32.whl") ||
            fileName.endsWith("-${target.cp}-abi3-${target.platform}_wasm32.whl")
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

    private fun download(url: String, fileName: String, abi: String) {
        val into = File(dir, abi).apply { mkdirs() }
        val target = File(into, fileName)
        if (target.isFile && target.length() > 0) return       // already fetched
        val staging = File(into, "$fileName.part")
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
