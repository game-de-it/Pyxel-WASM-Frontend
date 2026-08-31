package com.pwf.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity() {

    lateinit var runtime: RuntimeStore
        private set
    lateinit var library: AppLibrary
        private set

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val worker = Executors.newSingleThreadExecutor()
    private var playing = false

    companion object {
        private const val TAG = "pwf"
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val START_URL = "$ORIGIN/web/index.html"
        private const val PREFS = "pwf"
        private const val KEY_UPDATE_URL = "updateUrl"
        private const val REQ_PICK = 1001
        private const val REQ_PICK_TREE = 1002
        private const val REQ_PICK_UPDATE = 1003
        private const val SHOT_WIDTH = 384
        private const val REQ_PICK_BULK = 1004
        private const val REQ_PICK_SAVE_DIR = 1005
        private const val REQ_PICK_SAVE_ZIP = 1006
        private const val KEY_SAVE_DIR = "save_dir"
        private const val WEB_STORAGE = "_pwf_webstorage.json"
        private const val MAX_DATA_BYTES = 512L * 1024 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = RuntimeStore(this)
        library = AppLibrary(this)
        library.migrateRuntimePins()
        // Decide the fate of an unconfirmed bundle before anything loads it.
        runtime.beforeLoad()

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", PwfPathHandler(this, runtime, library))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(0xFF141622.toInt())
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Nothing outside the virtual origin is ever needed.
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    // Refuse to navigate anywhere but our own origin.
                    val allowed = request.url.toString().startsWith(ORIGIN)
                    if (!allowed) Log.w(TAG, "外部への遷移を拒否: ${request.url}")
                    return !allowed
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.e(
                        TAG,
                        "renderer gone: didCrash=${detail.didCrash()} " +
                            "priorityAtExit=${detail.rendererPriorityAtExit()}",
                    )
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.i("pwf.web", "${msg.message()} @${msg.lineNumber()}")
                    return true
                }
            }
            addJavascriptInterface(HostBridge(this@MainActivity), "pwfHost")
        }

        // A WebView normally puts ~2.17 device pixels in a CSS pixel here, which
        // makes Pyxel's whole-number scaling land on fractional device pixels and
        // a game pixel come out 4 columns wide in places and 5 in others. At 100%
        // the two units are the same, so what Pyxel draws is what the screen
        // shows. The page scales its own furniture back up.
        webView.setInitialScale(100)

        WebView.setWebContentsDebuggingEnabled(true)
        setContentView(webView)
        webView.loadUrl(START_URL)
    }

    /**
     * Picks up a freshly applied runtime bundle. Reads resolve lazily, so a
     * reload is enough — no process restart, which would also take the WebView's
     * renderer down with it.
     */
    fun reloadForRuntime() {
        runtime.beforeLoad()
        webView.clearCache(false)
        webView.loadUrl(START_URL)
    }

    // ---- host -> page -----------------------------------------------------

    fun emit(type: String, payload: JSONObject) {
        runOnUiThread {
            val js = "window.pwfEvent && window.pwfEvent(" +
                "${JSONObject.quote(type)},${JSONObject.quote(payload.toString())})"
            webView.evaluateJavascript(js, null)
        }
    }

    // ---- settings ---------------------------------------------------------

    fun updateUrl(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UPDATE_URL, "") ?: ""

    fun setUpdateUrl(url: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_UPDATE_URL, url).apply()
    }

    /**
     * Every Pyxel the launcher knows how to install, with what is already here.
     *
     * Surveyed at build time by `tools/make-catalog.py`, so the list is there
     * without a network; installing one is what needs to reach out.
     */
    fun pyxelCatalog(): JSONObject {
        val catalog = JSONObject(assets.open("pyxel-catalog.json").reader().use { it.readText() })
        return catalog
            .put("installed", runtime.installedRuntimes())
            .put("bundled", runtime.state())
    }

    /** Versions a game may be pinned to without downloading anything first. */
    fun availableRuntimes(): Set<String> {
        val out = mutableSetOf<String>()
        val state = runtime.state()
        out += state.optString("pyxel")
        state.optJSONArray("variants")?.let { variants ->
            for (i in 0 until variants.length()) out += variants.getJSONObject(i).optString("pyxel")
        }
        runtime.installedRuntimes().let { installed ->
            for (i in 0 until installed.length()) out += installed.getJSONObject(i).optString("pyxel")
        }
        return out
    }

    fun catalogEntry(version: String): JSONObject? {
        val versions = pyxelCatalog().optJSONArray("versions") ?: return null
        for (i in 0 until versions.length()) {
            val entry = versions.getJSONObject(i)
            if (entry.optString("pyxel") == version) return entry
        }
        return null
    }

    /**
     * A picture of the game as it is on screen right now.
     *
     * Read from the window rather than from the canvas: the page draws through
     * WebGL, whose buffer is empty again by the time any script could read it,
     * and forcing `preserveDrawingBuffer` to work around that would cost frame
     * rate on a device where frame rate is the whole problem.
     */
    fun captureShot(appId: String) {
        if (!playing) return
        val source = window.decorView
        if (source.width <= 0 || source.height <= 0) return
        val bitmap = android.graphics.Bitmap.createBitmap(
            source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            android.view.PixelCopy.request(window, bitmap, { result ->
                if (result != android.view.PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    return@request
                }
                worker.execute { storeShot(appId, bitmap) }
            }, android.os.Handler(mainLooper))
        } catch (e: Exception) {
            Log.w(TAG, "画面を取得できません", e)
            bitmap.recycle()
        }
    }

    private fun storeShot(appId: String, bitmap: android.graphics.Bitmap) {
        try {
            val width = SHOT_WIDTH
            val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
            val small = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            small.recycle()
            library.putShot(appId, out.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "画面を保存できません", e)
        } finally {
            bitmap.recycle()
        }
    }

    // ---- picking a local .pyxapp ------------------------------------------

    private var updateTarget: String? = null
    private var saveTarget: String? = null

    /** Everything directly inside a picked folder, without walking into it. */
    private fun topLevel(treeUri: Uri): List<Quad> {
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, root)
        val found = mutableListOf<Quad>()
        contentResolver.query(children, null, null, null, null)?.use { c ->
            val id = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val size = c.getColumnIndex(OpenableColumns.SIZE)
            val stamp = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val document = if (id >= 0) c.getString(id) else continue
                found += Quad(
                    if (name >= 0) c.getString(name) ?: "" else "",
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, document),
                    if (size >= 0 && !c.isNull(size)) c.getLong(size) else 0L,
                    if (stamp >= 0 && !c.isNull(stamp)) c.getLong(stamp) else 0L,
                )
            }
        }
        return found
    }

    data class Quad(val name: String, val uri: Uri, val size: Long, val stamp: Long)

    /**
     * Adds every .pyxapp sitting in one folder.
     *
     * A folder of games is how these arrive, and adding them one at a time
     * through the picker is the tedious part. Assets folders are deliberately
     * not guessed at here: what they are called is up to whoever built the
     * game, so they stay on the per-game flow that asks.
     */
    private fun importFolder(treeUri: Uri) {
        val files = topLevel(treeUri).filter { it.name.endsWith(".pyxapp", ignoreCase = true) }
        if (files.isEmpty()) {
            emit("error", JSONObject().put("op", "bulk")
                .put("message", "このフォルダに .pyxapp がありません"))
            return
        }

        // Match on the document id, not the URI. The same file picked one at a
        // time and reached through a folder has two different URIs but one id,
        // and matching on the string would add a second copy of every game.
        val known = HashMap<String, Triple<String, Long, Long>>()
        val entries = library.list()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val origin = entry.optString("origin")
            val id = documentIdOf(origin) ?: continue
            known[id] = Triple(
                entry.getString("id"),
                entry.optLong("originSize", -1L),
                entry.optLong("originStamp", -1L),
            )
        }

        var added = 0
        var updated = 0
        var same = 0
        for ((index, file) in files.withIndex()) {
            emit("bulk-progress", JSONObject()
                .put("name", file.name).put("done", index).put("total", files.size))
            try {
                val existing = documentIdOf(file.uri.toString())?.let { known[it] }
                if (existing != null && existing.second == file.size && existing.third == file.stamp) {
                    same++
                    continue                      // already here, and unchanged
                }
                val bytes = contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    ?: continue
                val entry =
                    if (existing != null) library.replace(existing.first, file.name, bytes)
                        .also { updated++ }
                    else library.add(file.name, bytes, "端末").also { added++ }
                library.setOrigin(entry.getString("id"), file.uri.toString(), file.size, file.stamp)
            } catch (e: Exception) {
                Log.w(TAG, "取り込めません: ${file.name}", e)
            }
        }
        emit("bulk-done", JSONObject()
            .put("added", added).put("updated", updated).put("same", same))
    }

    /** The stable identity of a document, the same from a pick or from a tree. */
    private fun documentIdOf(uri: String): String? = try {
        val parsed = Uri.parse(uri)
        if (DocumentsContract.isDocumentUri(this, parsed)) DocumentsContract.getDocumentId(parsed)
        else null
    } catch (e: Exception) {
        null
    }

    fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(intent, REQ_PICK_BULK)
        } catch (e: Exception) {
            emit("error", JSONObject().put("op", "bulk").put("message", "フォルダ選択を開けません"))
        }
    }

    // ---- save data in and out ---------------------------------------------

    private fun saveDirUri(): Uri? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SAVE_DIR, null)?.let(Uri::parse)

    /**
     * Writes a game's saves out as a zip, into a folder chosen once.
     *
     * Saves live in the app's own storage, which goes away with the app; this
     * is the only way to keep them. Pointing it at the folder the .pyxapp files
     * are in puts the backup where it will be looked for.
     */
    /** Web storage travels with the archive; see [writeSave]. */
    private var saveExtra: String = ""

    fun exportSave(appId: String, webStorage: String) {
        saveExtra = webStorage
        val tree = saveDirUri()
        if (tree == null) {
            saveTarget = appId
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_SAVE_DIR)
            } catch (e: Exception) {
                emit("error", JSONObject().put("op", "save").put("message", "フォルダ選択を開けません"))
            }
            return
        }
        worker.execute { writeSave(appId, tree) }
    }

    private fun writeSave(appId: String, tree: Uri) {
        try {
            val dir = library.saveDir(appId)
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            // Not every game writes files. Several keep their progress in web
            // storage instead, and a backup that skipped those would protect
            // nothing for them, so it rides along in the same archive.
            val extra = saveExtra
            require(files.isNotEmpty() || extra.length > 2) { "保存されたセーブがありません" }

            val title = library.list().let { entries ->
                (0 until entries.length()).map { entries.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == appId }
                    ?.optString("title").orEmpty()
            }.ifEmpty { appId }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            val name = "$title-save-$stamp.zip"

            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree),
            )
            val target = DocumentsContract.createDocument(
                contentResolver, parent, "application/zip", name,
            ) ?: throw java.io.IOException("書き出し先を作れません")

            contentResolver.openOutputStream(target)?.use { out ->
                java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
                    for (file in files) {
                        val rel = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(java.util.zip.ZipEntry(rel))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    if (extra.length > 2) {
                        zip.putNextEntry(java.util.zip.ZipEntry(WEB_STORAGE))
                        zip.write(extra.toByteArray())
                        zip.closeEntry()
                    }
                }
            } ?: throw java.io.IOException("書き出せません")
            emit("save-exported", JSONObject().put("name", name).put("files", files.size))
        } catch (e: Exception) {
            Log.e(TAG, "セーブの書き出しに失敗", e)
            emit("error", JSONObject().put("op", "save")
                .put("message", e.message ?: e.toString()))
        }
    }

    fun importSave(appId: String) {
        saveTarget = appId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        try {
            startActivityForResult(intent, REQ_PICK_SAVE_ZIP)
        } catch (e: Exception) {
            emit("error", JSONObject().put("op", "save").put("message", "ファイル選択を開けません"))
        }
    }

    /**
     * Points an entry that is already in the library at a file.
     *
     * Used for entries added before their source was remembered, or whose file
     * has since moved; picking it once is also what starts the tracking that
     * makes later overwrites arrive on their own.
     */
    fun pickPyxappFor(appId: String) {
        updateTarget = appId
        pickPyxapp(REQ_PICK_UPDATE)
    }

    fun pickPyxapp(request: Int = REQ_PICK) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        try {
            startActivityForResult(intent, request)
        } catch (e: Exception) {
            emit("error", JSONObject().put("op", "pick").put("message", "ファイル選択を開けません"))
        }
    }

    private var assetsTarget: String? = null

    /**
     * Picks the data folder for one app already in the library.
     *
     * A directory of Pyxel games holds many .pyxapp files and one assets folder
     * each, so the pairing cannot be inferred from the folder — the app says
     * which folder is its own.
     */
    fun pickAssetsFolder(appId: String) {
        assetsTarget = appId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(intent, REQ_PICK_TREE)
        } catch (e: Exception) {
            emit("error", JSONObject().put("op", "pick").put("message", "フォルダ選択を開けません"))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == REQ_PICK_BULK) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(TAG, "この場所は追跡できません", e)
            }
            worker.execute { importFolder(uri) }
            return
        }
        if (requestCode == REQ_PICK_SAVE_DIR) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(TAG, "この場所には書き込めないかもしれません", e)
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_SAVE_DIR, uri.toString()).apply()
            val target = saveTarget.also { saveTarget = null } ?: return
            worker.execute { writeSave(target, uri) }
            return
        }
        if (requestCode == REQ_PICK_SAVE_ZIP) {
            val target = saveTarget.also { saveTarget = null } ?: return
            worker.execute {
                try {
                    var web = ""
                    val count = contentResolver.openInputStream(uri)?.use { input ->
                        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                            library.restoreSave(target, zip) { name, bytes ->
                                if (name == WEB_STORAGE) web = String(bytes)
                            }
                        }
                    } ?: 0
                    // The page owns web storage, so hand it back for it to write.
                    emit("save-imported", JSONObject().put("files", count).put("web", web))
                } catch (e: Exception) {
                    Log.e(TAG, "セーブの読み込みに失敗", e)
                    emit("error", JSONObject().put("op", "save")
                        .put("message", e.message ?: e.toString()))
                }
            }
            return
        }
        if (requestCode == REQ_PICK_TREE) {
            val target = assetsTarget ?: return
            assetsTarget = null
            worker.execute { importAssetsFolder(target, uri) }
            return
        }
        if (requestCode != REQ_PICK && requestCode != REQ_PICK_UPDATE) return
        val target = updateTarget.also { updateTarget = null }
            .takeIf { requestCode == REQ_PICK_UPDATE }

        // Hold the grant past this activity, so the same file can be read again
        // later to notice it has been rebuilt.
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "この場所は追跡できません", e)
        }

        worker.execute {
            try {
                var name = "picked.pyxapp"
                var size = 0L
                var stamp = 0L
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val z = c.getColumnIndex(OpenableColumns.SIZE)
                        val m = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (z >= 0 && !c.isNull(z)) size = c.getLong(z)
                        if (m >= 0 && !c.isNull(m)) stamp = c.getLong(m)
                    }
                }
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("読み込めません")
                val entry =
                    if (target != null) library.replace(target, name, bytes)
                    else library.add(name, bytes, "端末")
                val id = entry.getString("id")
                library.setOrigin(id, uri.toString(), size, stamp)
                emit(if (target != null) "library-updated" else "library-added", entry)
            } catch (e: Exception) {
                Log.e(TAG, "取り込みに失敗", e)
                emit(
                    "error",
                    JSONObject().put("op", "pick").put("message", e.message ?: e.toString()),
                )
            }
        }
    }

    /** Every file under a picked tree, as relative path to document uri. */
    private fun walkTree(treeUri: Uri): List<Pair<String, Uri>> {
        val found = mutableListOf<Pair<String, Uri>>()

        fun walk(documentId: String, prefix: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (name.startsWith(".")) continue
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, rel)
                    } else {
                        found += rel to DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    }
                }
            }
        }

        walk(DocumentsContract.getTreeDocumentId(treeUri), "")
        return found
    }

    /** The picked folder's own name, which the game expects to see. */
    private fun treeDisplayName(treeUri: Uri): String? {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        return contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun importAssetsFolder(appId: String, treeUri: Uri) {
        try {
            val name = treeDisplayName(treeUri)
                ?: throw IllegalArgumentException("フォルダ名を取得できません")
            val files = walkTree(treeUri)
            if (files.isEmpty()) throw IllegalArgumentException("フォルダが空です")

            // Games read the folder by name — `pfs_assets/bgm/...` — so the
            // picked folder becomes the top level rather than disappearing.
            val dataDir = library.dataDir(appId)
            val paths = mutableListOf<String>()
            var total = 0L
            for ((rel, uri) in files) {
                val stored = "$name/$rel"
                val target = File(dataDir, stored)
                target.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { total += input.copyTo(it) }
                } ?: continue
                if (total > MAX_DATA_BYTES) {
                    throw IllegalStateException("データフォルダが大きすぎます")
                }
                paths += stored
            }
            library.addData(appId, paths)

            Log.i(TAG, "$name を取り込みました: ${paths.size} ファイル / ${total / 1024} KB")
            emit(
                "assets-added",
                JSONObject().put("id", appId).put("name", name).put("files", paths.size),
            )
        } catch (e: Exception) {
            Log.e(TAG, "assets の取り込みに失敗", e)
            emit(
                "error",
                JSONObject().put("op", "assets").put("message", e.message ?: e.toString()),
            )
        }
    }

    // ---- playing ----------------------------------------------------------

    fun setPlaying(value: Boolean) {
        playing = value
        if (value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hideSystemBars()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            showSystemBars()
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // The page closes a sheet, a dialog or the player if one is open, and
        // says so; only when it has nothing to dismiss does back leave the app.
        webView.evaluateJavascript("window.pwfBack ? window.pwfBack() : false") { handled ->
            if (handled != "true") finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // onPause() alone stops this WebView's rendering and script. pauseTimers()
        // is process-wide and starves SDL2's audio callback mid-buffer, which the
        // device then plays back as a held tone; the page suspends its own audio
        // context on the visibility change instead.
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (playing) hideSystemBars()
    }
}
