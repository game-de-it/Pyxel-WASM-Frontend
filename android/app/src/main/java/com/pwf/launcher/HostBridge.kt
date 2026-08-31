package com.pwf.launcher

import android.util.Log
import android.webkit.JavascriptInterface
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * The only surface the page can call into.
 *
 * Everything here is either a small JSON control message or a request to start
 * background work; bulk data always travels the other way, as bytes served
 * through [PwfPathHandler]. That keeps large payloads off this boundary and
 * keeps the WebView from ever needing an outside URL.
 */
class HostBridge(private val activity: MainActivity) {

    private val worker = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "pwf.bridge"
    }

    /**
     * The library, after folding in any source file that has been rebuilt.
     *
     * The check is a stat per tracked entry; only a file that actually moved is
     * read again, so the common case costs nothing worth measuring.
     */
    @JavascriptInterface
    fun library(): String {
        activity.library.syncOrigins()
        return activity.library.list().toString()
    }

    @JavascriptInterface
    fun runtime(): String = activity.runtime.state().toString()

    /** Device pixels per dp — how much the page must scale its own UI back up. */
    @JavascriptInterface
    fun density(): String = activity.resources.displayMetrics.density.toString()

    @JavascriptInterface
    fun updateUrl(): String = activity.updateUrl()

    @JavascriptInterface
    fun setUpdateUrl(url: String) = activity.setUpdateUrl(url.trim())

    @JavascriptInterface
    fun pickFile() = activity.runOnUiThread { activity.pickPyxapp() }

    @JavascriptInterface
    fun pickUpdate(id: String) = activity.runOnUiThread { activity.pickPyxappFor(id) }

    @JavascriptInterface
    fun pickFolder() = activity.runOnUiThread { activity.pickFolder() }

    @JavascriptInterface
    fun exportSave(id: String, webStorage: String) =
        activity.runOnUiThread { activity.exportSave(id, webStorage) }

    @JavascriptInterface
    fun importSave(id: String) = activity.runOnUiThread { activity.importSave(id) }

    @JavascriptInterface
    fun pickAssets(id: String) = activity.runOnUiThread { activity.pickAssetsFolder(id) }

    @JavascriptInterface
    fun clearData(id: String) = background("clearData") {
        activity.library.clearData(id)
        activity.emit("library-changed", JSONObject().put("id", id))
    }

    @JavascriptInterface
    fun addUrl(url: String) = background("add") {
        val trimmed = url.trim()
        val name = trimmed.substringAfterLast('/').substringBefore('?').ifEmpty { "remote.pyxapp" }
        val entry = activity.library.add(name, Net.get(trimmed), trimmed)
        activity.emit("library-added", entry)
    }

    /** Takes a picture of the running game, kept as its face in the library. */
    @JavascriptInterface
    fun captureShot(id: String) = activity.runOnUiThread { activity.captureShot(id) }

    @JavascriptInterface
    fun clearShot(id: String) = background("clearShot") {
        activity.library.clearShot(id)
        activity.emit("library-changed", JSONObject().put("id", id))
    }

    @JavascriptInterface
    fun setPad(id: String, mode: String) = background("setPad") {
        activity.library.setPad(id, mode)
        activity.emit("library-changed", JSONObject().put("id", id))
    }

    /** Which Pyxel versions can be pinned right now, and what else exists. */
    @JavascriptInterface
    fun catalog(): String = activity.pyxelCatalog().toString()

    @JavascriptInterface
    fun setRuntime(id: String, version: String) = background("setRuntime") {
        require(version.isEmpty() || version in activity.availableRuntimes()) {
            "その版はまだ入っていません: $version"
        }
        activity.library.setRuntime(id, version)
        activity.emit("library-changed", JSONObject().put("id", id))
    }

    /** Fetches one Pyxel from upstream so a game can be pinned to it. */
    @JavascriptInterface
    fun installRuntime(version: String) = background("installRuntime") {
        val entry = activity.catalogEntry(version)
            ?: throw IllegalArgumentException("一覧にない版です: $version")
        val installed = activity.runtime.installRuntime(entry) { label, percent ->
            activity.emit(
                "runtime-progress",
                JSONObject().put("version", version).put("label", label).put("percent", percent),
            )
        }
        activity.emit("runtime-installed", installed)
    }

    @JavascriptInterface
    fun removeRuntime(version: String) = background("removeRuntime") {
        activity.runtime.removeRuntime(version)
        activity.emit("runtime-installed", JSONObject().put("pyxel", version).put("removed", true))
    }

    @JavascriptInterface
    fun setAudioMode(id: String, mode: String) = background("setAudioMode") {
        activity.library.setAudioMode(id, mode)
        activity.emit("library-changed", JSONObject().put("id", id))
    }

    @JavascriptInterface
    fun setResume(id: String, mode: String) = background("setResume") {
        activity.library.setResume(id, mode)
        activity.emit("library-changed", JSONObject().put("id", id))
    }

    @JavascriptInterface
    fun remove(id: String) = background("remove") {
        activity.library.remove(id)
        activity.emit("library-changed", JSONObject().put("id", id))
    }

    @JavascriptInterface
    fun checkUpdate() = background("check") {
        val url = activity.updateUrl()
        require(url.isNotEmpty()) { "更新元 URL が未設定です" }
        activity.emit("update-checked", activity.runtime.check(url))
    }

    @JavascriptInterface
    fun applyUpdate() = background("apply") {
        val url = activity.updateUrl()
        require(url.isNotEmpty()) { "更新元 URL が未設定です" }
        val result = activity.runtime.apply(url) { file, done, total ->
            activity.emit(
                "update-progress",
                JSONObject().put("file", file).put("done", done).put("total", total),
            )
        }
        activity.emit("update-applied", result)
    }

    @JavascriptInterface
    fun rollback() = background("rollback") {
        activity.runtime.rollback()
        activity.emit("update-rolled-back", activity.runtime.state())
    }

    /** Pyodide came up on the active bundle, so it is not a candidate for rollback. */
    @JavascriptInterface
    fun bootOk() = background("bootOk") { activity.runtime.confirmBoot() }

    @JavascriptInterface
    fun beginSave(id: String) = background("beginSave") { activity.library.beginSave(id) }

    /** One file per call: the bridge is a Binder hop and dislikes big payloads. */
    @JavascriptInterface
    fun putSave(id: String, path: String, base64: String) = background("putSave") {
        activity.library.putSave(id, path, android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
    }

    @JavascriptInterface
    fun endSave(id: String) = background("endSave") {
        val count = activity.library.endSave(id)
        Log.i(TAG, "セーブを保存: $count ファイル")
    }

    @JavascriptInterface
    fun reloadRuntime() = activity.runOnUiThread { activity.reloadForRuntime() }

    @JavascriptInterface
    fun setPlaying(playing: Boolean) = activity.runOnUiThread { activity.setPlaying(playing) }

    @JavascriptInterface
    fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun background(what: String, body: () -> Unit) {
        worker.execute {
            try {
                body()
            } catch (e: Exception) {
                Log.e(TAG, "$what に失敗", e)
                activity.emit(
                    "error",
                    JSONObject().put("op", what).put("message", e.message ?: e.toString()),
                )
            }
        }
    }
}
