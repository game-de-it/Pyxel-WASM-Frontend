package com.pwf.launcher

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-device collection of .pyxapp files, however they arrived.
 *
 * Metadata comes out of the archive's ZIP comment, which is where `pyxel
 * package` writes title/author/license, so the list can be labelled without
 * starting the interpreter.
 */
class AppLibrary(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "apps").apply { mkdirs() }
    private val dataRoot = File(ctx.filesDir, "appdata").apply { mkdirs() }
    private val saveRoot = File(ctx.filesDir, "appsave").apply { mkdirs() }
    private val shotRoot = File(ctx.filesDir, "appshot").apply { mkdirs() }
    private val index = File(dir, "index.json")

    companion object {
        private const val TAG = "pwf.library"
        private const val STARTUP_MARKER = ".pyxapp_startup_script"
        /** Listing the player reads to materialise the data folder up front. */
        const val DATA_INDEX = ".pwf-index.json"
    }

    fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{16}"))) { "不正な id" }
        return File(dir, "$id.pyxapp")
    }

    /**
     * Where an app's side-by-side data folder lives.
     *
     * Games distributed as "a .pyxapp plus a folder" read that folder from the
     * working directory, so those files have to exist before the app starts.
     */
    fun dataDir(id: String): File {
        require(id.matches(Regex("[0-9a-f]{16}"))) { "不正な id" }
        return File(dataRoot, id)
    }

    /** A frame from the last time this app ran, used as its picture. */
    fun shotFile(id: String): File {
        require(id.matches(Regex("[0-9a-f]{16}"))) { "不正な id" }
        return File(shotRoot, "$id.png")
    }

    fun putShot(id: String, bytes: ByteArray) {
        shotFile(id).writeBytes(bytes)
        setField(id, "shot", System.currentTimeMillis().toString())
    }

    /** Throws the picture away, so the next run takes a fresh one. */
    fun clearShot(id: String) {
        shotFile(id).delete()
        setField(id, "shot", "")
    }

    /** Whether this app wants the touch gamepad: "auto", "on" or "off". */
    fun setPad(id: String, mode: String) {
        require(mode in setOf("auto", "on", "off")) { "不正な指定: $mode" }
        setField(id, "pad", mode)
    }

    /**
     * Who plays this app's PCM sounds: "pyxel" or "browser".
     *
     * Pyxel decodes them on the main thread, where it also generates audio, so
     * a game that loads tracks while playing hears its own loading. Handing
     * them to the page moves the decode off that thread.
     */
    fun setAudioMode(id: String, mode: String) {
        require(mode in setOf("pyxel", "browser")) { "不正な指定: $mode" }
        setField(id, "audio", mode)
    }

    /**
     * Python packages to load before this app runs, comma separated.
     *
     * Per app rather than global: loading every package every time would slow
     * every launch for the sake of one game.
     */
    fun setPackages(id: String, names: String) {
        require(names.matches(Regex("[A-Za-z0-9_.,+-]*"))) { "不正な指定: $names" }
        setField(id, "packages", names)
    }

    /** What a tap on the card does: "restart" or "continue". */
    fun setResume(id: String, mode: String) {
        require(mode in setOf("restart", "continue")) { "不正な指定: $mode" }
        setField(id, "resume", mode)
    }

    /**
     * Which Pyxel layer this app runs on; empty means the bundle's default.
     *
     * Some games only behave on a particular Pyxel — audio timing being the
     * usual reason — so the choice belongs to the game, not to the device.
     */
    fun setRuntime(id: String, version: String) {
        require(version.isEmpty() || version.matches(Regex("[0-9]+(\\.[0-9]+){1,2}"))) {
            "不正な指定: $version"
        }
        setField(id, "runtime", version)
    }

    /**
     * The Pyxel pin used to name a directory; it names a version now.
     *
     * Only entries written by an earlier build carry the old shape, and left
     * alone they would ask for a runtime path that cannot resolve.
     */
    fun migrateRuntimePins() {
        val entries = list()
        var changed = false
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val old = entry.optString("runtime")
            if (old.isEmpty() || !old.startsWith("pyxel")) continue
            entry.put("runtime", old.removePrefix("pyxel").removePrefix("-"))
            changed = true
        }
        if (changed) index.writeText(entries.toString())
    }

    private fun setField(id: String, key: String, value: String) {
        val entries = list()
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("id") == id) entry.put(key, value)
            kept.put(entry)
        }
        index.writeText(kept.toString())
    }

    fun dataPaths(id: String): List<String> {
        val listing = File(dataDir(id), DATA_INDEX)
        if (!listing.isFile) return emptyList()
        return try {
            val array = JSONArray(listing.readText())
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Folds another folder into an app's data, keeping what was already there.
     *
     * A game's assets arrive one folder at a time — a directory of .pyxapp files
     * has one assets folder per game — so this adds rather than replaces.
     */
    fun addData(id: String, paths: List<String>) {
        val listing = LinkedHashSet(dataPaths(id))
        listing.addAll(paths)
        File(dataDir(id).apply { mkdirs() }, DATA_INDEX)
            .writeText(JSONArray(listing.toList()).toString())
        setDataCount(id, listing.size)
    }

    fun clearData(id: String) {
        dataDir(id).deleteRecursively()
        setDataCount(id, 0)
    }

    private fun setDataCount(id: String, count: Int) {
        val entries = list()
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("id") == id) entry.put("data", count)
            kept.put(entry)
        }
        index.writeText(kept.toString())
    }

    /**
     * Where a game's own writes are kept between launches.
     *
     * Pyxel runs on an in-memory filesystem that dies with the page, so save
     * files only survive if they are copied out and put back.
     */
    fun saveDir(id: String): File {
        require(id.matches(Regex("[0-9a-f]{16}"))) { "不正な id" }
        return File(saveRoot, id)
    }

    /** Starts a fresh snapshot; the previous one stays until [endSave]. */
    fun beginSave(id: String) {
        val staging = File(saveDir(id).path + ".new")
        staging.deleteRecursively()
        staging.mkdirs()
    }

    fun putSave(id: String, rel: String, bytes: ByteArray) {
        require(!rel.contains("..") && !rel.startsWith("/")) { "不正なパス: $rel" }
        val target = File(File(saveDir(id).path + ".new"), rel)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    /** Swaps the snapshot in whole, so a half-written one is never read back. */
    fun endSave(id: String): Int {
        val staging = File(saveDir(id).path + ".new")
        if (!staging.isDirectory) return 0
        val paths = staging.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(staging).path.replace(File.separatorChar, '/') }
            .filter { it != DATA_INDEX }
            .toList()
        File(staging, DATA_INDEX).writeText(JSONArray(paths).toString())

        val live = saveDir(id)
        live.deleteRecursively()
        if (!staging.renameTo(live)) {
            Log.w(TAG, "セーブの差し替えに失敗")
            staging.deleteRecursively()
            return 0
        }
        return paths.size
    }

    fun list(): JSONArray = try {
        if (index.isFile) JSONArray(index.readText()) else JSONArray()
    } catch (e: Exception) {
        Log.e(TAG, "索引を読めません", e); JSONArray()
    }

    /**
     * Folds edits of the source files back into the library.
     *
     * A game that gets a new build is usually overwritten in the folder it was
     * picked from, and having to add it again would be busywork that also
     * stranded its saves on the old entry. Entries picked from a file remember
     * which document they came from; when that document's size or timestamp
     * moves, the archive is read again into the same entry.
     */
    fun syncOrigins(): Int {
        var changed = 0
        val entries = list()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val origin = entry.optString("origin")
            if (!origin.startsWith("content://")) continue
            val uri = Uri.parse(origin)
            val stat = stat(uri) ?: continue
            if (stat.first == entry.optLong("originSize", -1L) &&
                stat.second == entry.optLong("originStamp", -1L)
            ) continue
            val id = entry.getString("id")
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: continue
                replace(id, entry.optString("name", "$id.pyxapp"), bytes)
                setOrigin(id, origin, stat.first, stat.second)
                Log.i(TAG, "更新を取り込み: ${entry.optString("title")}")
                changed++
            } catch (e: Exception) {
                // The file may be half-written, or no longer a pyxapp at all.
                // Either way the entry keeps the build it already has.
                Log.w(TAG, "更新の取り込みに失敗: $id", e)
            }
        }
        return changed
    }

    /** Size and last-modified of a document, or null if it is out of reach. */
    private fun stat(uri: Uri): Pair<Long, Long>? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else {
                val size = c.getColumnIndex(OpenableColumns.SIZE)
                val stamp = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (size < 0 || c.isNull(size)) null
                else Pair(
                    c.getLong(size),
                    if (stamp >= 0 && !c.isNull(stamp)) c.getLong(stamp) else 0L,
                )
            }
        }
    } catch (e: Exception) {
        null // the persisted grant can be revoked, or the file simply deleted
    }

    /** The entry that came from this document, if the library already has it. */
    fun findByOrigin(uri: String): String? {
        val entries = list()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.optString("origin") == uri) return entry.getString("id")
        }
        return null
    }

    /**
     * Puts a whole save set back, replacing what is there.
     *
     * The listing is rebuilt from what the archive actually held, so a restore
     * from another device cannot leave a stale index behind.
     */
    fun restoreSave(
        id: String,
        zip: java.util.zip.ZipInputStream,
        onExtra: (String, ByteArray) -> Unit = { _, _ -> },
    ): Int {
        val staging = File(saveDir(id).path + ".new")
        staging.deleteRecursively()
        staging.mkdirs()
        var entry = zip.nextEntry
        while (entry != null) {
            val rel = entry.name.replace('\\', '/')
            if (!entry.isDirectory && rel.startsWith("_pwf_")) {
                onExtra(rel, zip.readBytes())     // not a game file; the page's
            } else if (!entry.isDirectory && !rel.contains("..") && !rel.startsWith("/")) {
                val target = File(staging, rel)
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return endSave(id)
    }

    /** Ties an entry to the document it was picked from, for [syncOrigins]. */
    fun setOrigin(id: String, uri: String, size: Long, stamp: Long) {
        val entries = list()
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("id") == id) {
                entry.put("origin", uri).put("originSize", size).put("originStamp", stamp)
            }
            kept.put(entry)
        }
        index.writeText(kept.toString())
    }

    /**
     * Puts a new build into an entry that already exists.
     *
     * The id is the entry's key, not a running checksum of what it holds: a
     * game with a new build is the same game, and its saves, data folder and
     * per-app settings belong to it rather than to the bytes. `rev` moves so a
     * warm interpreter still holding the old code is not mistaken for the game
     * being ready to resume.
     */
    fun replace(id: String, displayName: String, bytes: ByteArray): JSONObject {
        val target = file(id)
        val staging = File(target.path + ".new")
        staging.writeBytes(bytes)
        val meta = try {
            readMetadata(staging)
        } catch (e: Exception) {
            staging.delete()
            throw IllegalArgumentException("Pyxel アプリとして読めません: ${e.message}")
        }
        target.delete()
        if (!staging.renameTo(target)) {
            staging.delete()
            throw java.io.IOException("差し替えに失敗しました")
        }

        val entries = list()
        val kept = JSONArray()
        var updated = JSONObject()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("id") == id) {
                entry.put("name", displayName)
                    .put("title", meta.optString("title", displayName.removeSuffix(".pyxapp")))
                    .put("author", meta.optString("author", entry.optString("author")))
                    .put("desc", meta.optString("desc", entry.optString("desc")))
                    .put("license", meta.optString("license", entry.optString("license")))
                    .put("site", meta.optString("site", entry.optString("site")))
                    .put("version", meta.optString("version", ""))
                    .put("size", bytes.size)
                    .put("rev", entry.optInt("rev", 0) + 1)
                    .put("updatedAt", System.currentTimeMillis())
                // Taken from what is on disk rather than from the entry, so a
                // build swap is also a chance to put right anything stale.
                val data = dataPaths(id).size
                if (data > 0) entry.put("data", data) else entry.remove("data")
                val shot = shotFile(id)
                if (shot.isFile) entry.put("shot", shot.lastModified().toString())
                else entry.remove("shot")
                updated = entry
            }
            kept.put(entry)
        }
        index.writeText(kept.toString())
        return updated
    }

    /**
     * Puts an archive into the library, keyed by what it contains.
     *
     * Adding the same file twice is not an error and not a duplicate: the id is
     * the content, so the second add lands on the entry that is already there.
     * When it does, everything that belongs to the app rather than to the bytes
     * -- its data folder, its pad and resume choices, its picture -- is kept.
     * Losing those to a re-add is how a library quietly forgets what it knew.
     */
    fun add(displayName: String, bytes: ByteArray, source: String): JSONObject {
        val id = Digest.sha256(bytes).substring(0, 16)
        val target = File(dir, "$id.pyxapp")
        target.writeBytes(bytes)

        val meta = try {
            readMetadata(target)
        } catch (e: Exception) {
            target.delete()
            throw IllegalArgumentException("Pyxel アプリとして読めません: ${e.message}")
        }

        val entries = list()
        var entry = JSONObject()
        for (i in 0 until entries.length()) {
            val old = entries.getJSONObject(i)
            if (old.getString("id") == id) entry = old
        }

        entry.put("id", id)
            .put("name", displayName)
            .put("title", meta.optString("title", displayName.removeSuffix(".pyxapp")))
            .put("author", meta.optString("author", ""))
            .put("desc", meta.optString("desc", ""))
            .put("license", meta.optString("license", ""))
            .put("site", meta.optString("site", ""))
            .put("version", meta.optString("version", ""))
            .put("size", bytes.size)
            .put("source", source)
        if (!entry.has("addedAt")) entry.put("addedAt", System.currentTimeMillis())

        // Take these from what is actually on disk rather than from whatever
        // the entry happened to be carrying: a repair as much as a refresh.
        val data = dataPaths(id).size
        if (data > 0) entry.put("data", data) else entry.remove("data")
        val shot = shotFile(id)
        if (shot.isFile) entry.put("shot", shot.lastModified().toString()) else entry.remove("shot")

        val kept = JSONArray()
        kept.put(entry)
        for (i in 0 until entries.length()) {
            val old = entries.getJSONObject(i)
            if (old.getString("id") != id) kept.put(old)
        }
        index.writeText(kept.toString())
        return entry
    }

    fun remove(id: String) {
        file(id).delete()
        shotFile(id).delete()
        dataDir(id).deleteRecursively()
        saveDir(id).deleteRecursively()
        val entries = list()
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val old = entries.getJSONObject(i)
            if (old.getString("id") != id) kept.put(old)
        }
        index.writeText(kept.toString())
    }

    /** Reads the ZIP comment `pyxel package` writes, and proves it is a pyxapp. */
    private fun readMetadata(file: File): JSONObject {
        ZipFile(file).use { zip ->
            val hasStartup = zip.entries().asSequence().any { it.name.endsWith("/$STARTUP_MARKER") }
            require(hasStartup) { "$STARTUP_MARKER が見つかりません" }

            val meta = JSONObject()
            val comment = zip.comment ?: return meta
            for (line in comment.lines()) {
                if (line.startsWith("-") || !line.contains(":")) continue
                val key = line.substringBefore(":").trim().lowercase()
                val value = line.substringAfter(":").trim()
                if (key.isNotEmpty() && value.isNotEmpty()) meta.put(key, value)
            }
            return meta
        }
    }
}
