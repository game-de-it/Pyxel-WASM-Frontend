package com.pwf.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URI
import org.json.JSONObject

/**
 * Owns the Pyodide + Pyxel runtime bundle and its over-the-air replacement.
 *
 * Reads resolve "downloaded bundle first, APK baseline second", so the app runs
 * even if an update has never succeeded. Writes stage into a scratch directory,
 * verify every file, and only then swap directories with a rename — a half
 * applied update is not a state this can end up in.
 */
class RuntimeStore(private val ctx: Context) {

    private val root = File(ctx.filesDir, "runtime")
    private val current = File(root, "current")
    private val previous = File(root, "previous")
    private val staging = File(root, "staging")
    private val pending = File(root, "pending.json")

    /** Alternate Pyxel runtimes installed after the fact, one dir per version. */
    private val extra = File(root, "rt")

    companion object {
        private const val TAG = "pwf.runtime"
        private const val BASELINE = "runtime"
        /** A bundle that has not confirmed a successful boot after this many
         *  attempts is assumed broken and rolled back. */
        private const val MAX_UNCONFIRMED_BOOTS = 1

        private val IMAGES = listOf(
            "pyxel_logo_76x32.png", "touch_to_start_114x14.png", "click_to_start_114x14.png",
            "gamepad_cross_98x98.png", "gamepad_button_98x98.png", "gamepad_menu_92x26.png",
            "pyxel_icon_64x64.ico",
        )

        /** Nice to have, but an old pyxel.js that lacks the anchor still runs. */
        private val OPTIONAL_PATCHES = listOf(
            """  const pyodideVersion = PYODIDE_URL.match(/v([\d.]+)\//)[1];""" to
                """  const pyodideVersion = PYODIDE_URL.match(/v([\d.]+)\//)?.[1] ?? "bundled";  // pwf""",
            "  _copyFileFromBase64(pyodide, params.name, params.base64);" to
                "  _copyFileFromBase64(pyodide, params.name, params.base64);\n" +
                "  await window.pwfPrepareFiles?.(pyodide, params);  // pwf",
        )
    }

    // ---- reading ----------------------------------------------------------

    /** Opens a file from the active bundle, falling back to the APK baseline. */
    fun open(rel: String): InputStream {
        val downloaded = File(current, rel)
        if (downloaded.isFile) return downloaded.inputStream()
        return ctx.assets.open("$BASELINE/$rel")
    }

    fun length(rel: String): Long {
        val downloaded = File(current, rel)
        if (downloaded.isFile) return downloaded.length()
        return try {
            ctx.assets.openFd("$BASELINE/$rel").use { it.length }
        } catch (e: Exception) {
            -1L
        }
    }

    fun exists(rel: String): Boolean {
        if (File(current, rel).isFile) return true
        return try {
            ctx.assets.open("$BASELINE/$rel").close(); true
        } catch (e: Exception) {
            false
        }
    }

    /** The descriptor of whatever bundle is currently active. */
    fun manifest(): JSONObject = try {
        JSONObject(open("bundle.json").reader().use { it.readText() })
    } catch (e: Exception) {
        Log.e(TAG, "bundle.json を読めません", e)
        JSONObject().put("bundle", 0)
    }

    fun bundleVersion(): Int = manifest().optInt("bundle", 0)

    fun state(): JSONObject {
        val m = manifest()
        return JSONObject()
            .put("bundle", m.optInt("bundle", 0))
            .put("pyxel", m.optString("pyxel", "?"))
            .put("pyodide", m.optString("pyodide", "?"))
            .put("abi", m.optString("abi", "?"))
            .put("platform", m.optString("platform", "?"))
            // The Pyxel layers a game may be pinned to. They share this
            // bundle's Pyodide, which is what makes them interchangeable.
            .put("variants", m.optJSONArray("variants") ?: org.json.JSONArray())
            .put("source", if (File(current, "bundle.json").isFile) "downloaded" else "baseline")
            .put("unconfirmed", pending.isFile)
            .put("canRollback", File(current, "bundle.json").isFile)
    }

    // ---- alternate Pyxel runtimes -----------------------------------------

    /**
     * What a game can be pinned to, beyond the bundle's own Pyxel.
     *
     * A version whose Pyodide matches the bundle's shares that interpreter and
     * costs only a wheel; anything else brings its own, which is why the
     * descriptor records which of the two it is.
     */
    fun installedRuntimes(): org.json.JSONArray {
        val out = org.json.JSONArray()
        val dirs = extra.listFiles { f: File -> f.isDirectory } ?: return out
        for (dir in dirs.sortedBy { it.name }) {
            val descriptor = File(dir, "layer.json")
            if (!descriptor.isFile) continue
            try {
                out.put(JSONObject(descriptor.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "layer.json を読めません: ${dir.name}", e)
            }
        }
        return out
    }

    /**
     * A file of one alternate runtime, addressed as `rt-<version>/<rel>`.
     *
     * Three places answer, in this order: what was installed for that version;
     * the bundle's own Pyodide, which a same-ABI layer deliberately shares; and
     * a layer the APK itself carries.
     */
    fun openAlternate(version: String, rel: String): Pair<InputStream, Long>? {
        require(version.matches(Regex("[0-9]+(\\.[0-9]+){1,2}"))) { "不正なバージョン" }
        val own = File(File(extra, version), rel)
        if (own.isFile) return own.inputStream() to own.length()
        if (rel.startsWith("pyodide/") && exists(rel)) return open(rel) to length(rel)
        if (rel.startsWith("pyxel/")) {
            val mapped = "pyxel-$version/" + rel.removePrefix("pyxel/")
            if (exists(mapped)) return open(mapped) to length(mapped)
        }
        return null
    }

    fun removeRuntime(version: String) {
        require(version.matches(Regex("[0-9]+(\\.[0-9]+){1,2}"))) { "不正なバージョン" }
        File(extra, version).deleteRecursively()
    }

    /**
     * Installs one Pyxel version from the upstream wasm build.
     *
     * The wheel and its pyxel.js come from the tag; the Pyodide it was built
     * against is only fetched when the bundle's own interpreter is a different
     * release, since a wheel is tied to one ABI and nothing else.
     */
    fun installRuntime(entry: JSONObject, onProgress: (String, Int) -> Unit): JSONObject {
        val version = entry.getString("pyxel")
        require(version.matches(Regex("[0-9]+(\\.[0-9]+){1,2}"))) { "不正なバージョン" }
        val wheel = entry.getString("wheel")
        require(wheel.matches(Regex("pyxel-[0-9A-Za-z._-]+\\.whl"))) { "不正な wheel 名" }
        val pyodideVersion = entry.getString("pyodide")
        // What decides whether the bundled interpreter can carry this wheel is
        // the ABI it was built against, not the Pyodide release it shipped
        // beside: 314.0.0 and 314.0.4 are both emscripten_5_0_3.
        val platform = manifest().optString("platform")
        val shared = platform.isNotEmpty() && entry.optString("abi").endsWith(platform)

        val staging = File(extra, "$version.new")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val base = "https://raw.githubusercontent.com/kitao/pyxel/v$version/wasm"
            val pyxelDir = File(staging, "pyxel").apply { mkdirs() }

            onProgress("pyxel $version", 5)
            for (name in listOf("pyxel.js", "pyxel.css", "import_hook.py", wheel)) {
                Net.download("$base/$name", File(pyxelDir, name))
            }
            // Old tags carry different decorations, so a missing one is not an
            // error -- it just means that build never had it.
            val images = File(pyxelDir, "images").apply { mkdirs() }
            for (name in IMAGES) {
                try {
                    Net.download("$base/images/$name", File(images, name))
                } catch (e: Exception) {
                    Log.i(TAG, "この版には無い画像: $name")
                }
            }
            onProgress("pyxel $version", 40)
            patchPyxelJs(File(pyxelDir, "pyxel.js"))

            if (!shared) {
                onProgress("pyodide $pyodideVersion", 45)
                val archive = File(staging, "core.tar.bz2")
                Net.download(
                    "https://github.com/pyodide/pyodide/releases/download/" +
                        "$pyodideVersion/pyodide-core-$pyodideVersion.tar.bz2",
                    archive,
                ) { done, total ->
                    if (total > 0) onProgress("pyodide $pyodideVersion", 45 + (done * 45 / total).toInt())
                }
                onProgress("展開中", 92)
                unpackPyodide(archive, File(staging, "pyodide"))
                archive.delete()
            }

            val descriptor = JSONObject()
                .put("pyxel", version)
                .put("pyodide", pyodideVersion)
                .put("abi", entry.optString("abi"))
                .put("shared", shared)
                .put("installedAt", System.currentTimeMillis())
            File(staging, "layer.json").writeText(descriptor.toString())

            val live = File(extra, version)
            live.deleteRecursively()
            if (!staging.renameTo(live)) throw java.io.IOException("設置に失敗しました")
            onProgress("完了", 100)
            Log.i(TAG, "pyxel $version を導入 (pyodide $pyodideVersion, 共有=$shared)")
            return descriptor
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** Extracts the core distribution, which is only published as tar.bz2. */
    private fun unpackPyodide(archive: File, target: File) {
        target.mkdirs()
        archive.inputStream().buffered().use { raw ->
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(raw).use { bz ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        if (e.isDirectory) continue
                        val rel = e.name.substringAfter("pyodide/", "")
                        if (rel.isEmpty() || rel.contains("..")) continue
                        val out = File(target, rel)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    /**
     * The same four edits `tools/patch-pyxel-js.py` makes at build time, made
     * here because a runtime installed on the device never passes through it.
     * Only the first two are fatal: without them the page would reach for a CDN.
     */
    private fun patchPyxelJs(file: File) {
        var s = file.readText()
        val bundled = """const PYODIDE_URL = "../pyodide/pyodide.js";  // pwf: bundled offline runtime"""
        if (!s.contains(bundled)) {
            val cdn = Regex("""const PYODIDE_URL = "https://cdn\.jsdelivr\.net/pyodide/v[\d.]+/full/pyodide\.js";""")
            require(cdn.containsMatchIn(s)) { "pyxel.js の PYODIDE_URL が見つかりません" }
            s = cdn.replace(s, java.util.regex.Matcher.quoteReplacement(bundled))
        }
        val anchored = "  await _loadScript(_scriptDir + PYODIDE_URL);  // pwf: resolve against vendor dir"
        if (!s.contains(anchored)) {
            require(s.contains("  await _loadScript(PYODIDE_URL);")) { "pyxel.js の _loadScript が見つかりません" }
            s = s.replace("  await _loadScript(PYODIDE_URL);", anchored)
        }
        for ((old, new) in OPTIONAL_PATCHES) {
            if (!s.contains(new) && s.contains(old)) s = s.replace(old, new)
        }
        file.writeText(s)
    }

    // ---- boot barrier -----------------------------------------------------

    /**
     * Called before the WebView loads the shell — at process start and again on
     * every reload. A bundle that was applied but never confirmed a good boot
     * gets rolled back here, so a runtime that cannot start costs one reload
     * rather than a broken install.
     */
    fun beforeLoad() {
        dropDownloadedIfOlderThanBaseline()
        if (!pending.isFile) return
        val attempts = try {
            JSONObject(pending.readText()).optInt("attempts", 0)
        } catch (e: Exception) {
            MAX_UNCONFIRMED_BOOTS
        }
        if (attempts >= MAX_UNCONFIRMED_BOOTS) {
            Log.w(TAG, "起動確認が取れないバンドルを差し戻します (attempts=$attempts)")
            rollback()
        } else {
            pending.writeText(JSONObject().put("attempts", attempts + 1).toString())
        }
    }

    /**
     * An app update can ship a newer runtime than whatever was downloaded
     * earlier. Reads prefer the downloaded bundle, so a stale one has to be
     * dropped or the new APK would never take effect.
     */
    private fun dropDownloadedIfOlderThanBaseline() {
        if (!File(current, "bundle.json").isFile) return
        val downloaded = try {
            JSONObject(File(current, "bundle.json").readText()).optInt("bundle", 0)
        } catch (e: Exception) {
            0
        }
        val baseline = try {
            JSONObject(ctx.assets.open("$BASELINE/bundle.json").reader().use { it.readText() })
                .optInt("bundle", 0)
        } catch (e: Exception) {
            0
        }
        if (baseline > downloaded) {
            Log.i(TAG, "同梱バンドル $baseline が DL 済み $downloaded より新しいので差し替えます")
            current.deleteRecursively()
            previous.deleteRecursively()
            pending.delete()
        }
    }

    /** The runtime reached a working Pyodide; the active bundle is good. */
    fun confirmBoot() {
        if (pending.isFile) {
            pending.delete()
            Log.i(TAG, "バンドル ${bundleVersion()} の起動を確認しました")
        }
    }

    fun rollback(): Boolean {
        pending.delete()
        if (!File(previous, "bundle.json").isFile) {
            // Nothing to go back to but the baseline; dropping current is enough.
            current.deleteRecursively()
            return true
        }
        current.deleteRecursively()
        return previous.renameTo(current)
    }

    // ---- updating ---------------------------------------------------------

    /** Fetches a manifest and reports whether it supersedes the active bundle. */
    fun check(manifestUrl: String): JSONObject {
        val remote = JSONObject(Net.getText(manifestUrl))
        val active = manifest()
        val remoteBundle = remote.optInt("bundle", -1)
        require(remoteBundle > 0) { "manifest に bundle 番号がありません" }
        require(remote.optJSONObject("files") != null) { "manifest に files がありません" }

        return JSONObject()
            .put("available", remoteBundle > active.optInt("bundle", 0))
            .put("remote", remoteBundle)
            .put("active", active.optInt("bundle", 0))
            .put("pyxel", remote.optString("pyxel", "?"))
            .put("pyodide", remote.optString("pyodide", "?"))
            .put("files", remote.getJSONObject("files").length())
            .put("bytes", totalBytes(remote))
    }

    /**
     * Downloads, verifies and installs the bundle described by [manifestUrl].
     *
     * Files whose hash already exists locally are copied instead of fetched, so a
     * Pyxel-only update moves the wheel and nothing else.
     */
    fun apply(manifestUrl: String, onProgress: (String, Int, Int) -> Unit): JSONObject {
        val manifestText = Net.getText(manifestUrl)
        val manifest = JSONObject(manifestText)
        val files = manifest.getJSONObject("files")
        val base = URI(manifestUrl).resolve(manifest.optString("baseUrl", "."))

        staging.deleteRecursively()
        require(staging.mkdirs()) { "作業ディレクトリを作れません" }

        val names = files.keys().asSequence().toList().sorted()
        var fetched = 0
        var reused = 0
        var bytes = 0L

        names.forEachIndexed { index, rel ->
            require(!rel.contains("..") && !rel.startsWith("/")) { "不正なパス: $rel" }
            val entry = files.getJSONObject(rel)
            val want = entry.getString("sha256")
            val target = File(staging, rel)
            target.parentFile?.mkdirs()

            onProgress(rel, index + 1, names.size)

            if (copyLocalIfMatches(rel, want, target)) {
                reused++
            } else {
                val url = base.resolve(rel).toString()
                val data = Net.get(url)
                val got = Digest.sha256(data)
                if (got != want) {
                    staging.deleteRecursively()
                    throw IllegalStateException("ハッシュ不一致: $rel")
                }
                target.writeBytes(data)
                fetched++
            }
            bytes += target.length()
        }

        // The manifest is the bundle descriptor; store it as the new bundle.json.
        File(staging, "bundle.json").writeText(manifestText)

        previous.deleteRecursively()
        if (File(current, "bundle.json").isFile && !current.renameTo(previous)) {
            staging.deleteRecursively()
            throw IllegalStateException("旧バンドルを退避できません")
        }
        current.deleteRecursively()
        if (!staging.renameTo(current)) {
            previous.renameTo(current)
            throw IllegalStateException("新バンドルを配置できません")
        }
        pending.writeText(JSONObject().put("attempts", 0).toString())

        Log.i(TAG, "バンドル ${manifest.optInt("bundle")} を適用 (取得 $fetched / 流用 $reused)")
        return JSONObject()
            .put("bundle", manifest.optInt("bundle"))
            .put("fetched", fetched)
            .put("reused", reused)
            .put("bytes", bytes)
    }

    /** Reuses a byte-identical file already on the device instead of downloading. */
    private fun copyLocalIfMatches(rel: String, want: String, target: File): Boolean {
        if (!exists(rel)) return false
        return try {
            if (Digest.sha256(open(rel)) != want) return false
            open(rel).use { input -> target.outputStream().use { input.copyTo(it) } }
            true
        } catch (e: FileNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "ローカル流用に失敗: $rel", e)
            false
        }
    }

    private fun totalBytes(manifest: JSONObject): Long {
        val files = manifest.optJSONObject("files") ?: return 0
        var sum = 0L
        for (key in files.keys()) sum += files.getJSONObject(key).optLong("size", 0)
        return sum
    }
}
