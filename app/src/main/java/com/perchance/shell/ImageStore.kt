package com.perchance.shell

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Private storage: context.filesDir/generated_images/<batch>/image_*.ext
 * Each "Download All" creates one batch folder. Files from older versions sit loose in the root.
 * Writes go to .tmp/ first, then are renamed into place, so a final file is never half-written.
 */
class ImageStore(context: Context) {

    enum class Result { SAVED, DUPLICATE, INVALID, NO_SPACE, IO }

    class Folder(val dir: File, val files: List<File>, val loose: Boolean) {
        val key: String get() = dir.absolutePath + if (loose) "#loose" else ""
        val time: Long get() = files.maxOfOrNull { it.lastModified() } ?: 0L
    }

    val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
    private val tmpDir = File(dir, ".tmp").apply { mkdirs() }
    private val hashes = HashMap<String, String>() // sha256 -> absolute path
    private var indexed = false
    private var batch: String? = null

    @Volatile var lastSaved: File? = null

    @Synchronized
    fun cleanTmp() {
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun beginBatch() {
        batch = stamp()
    }

    @Synchronized
    fun ensureIndexed() {
        if (indexed) return
        hashes.clear()
        list().forEach { f ->
            try { hashes[sha256(f.readBytes())] = f.absolutePath } catch (_: Exception) {}
        }
        indexed = true
    }

    private fun isImage(f: File) = f.isFile && f.name.startsWith("image_")

    @Synchronized
    fun folders(): List<Folder> {
        val out = ArrayList<Folder>()
        val loose = (dir.listFiles() ?: emptyArray()).filter { isImage(it) }.sortedByDescending { it.lastModified() }
        if (loose.isNotEmpty()) out.add(Folder(dir, loose, true))
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name != ".tmp" }.forEach { d ->
            val fs = (d.listFiles() ?: emptyArray()).filter { isImage(it) }.sortedBy { it.name }
            if (fs.isNotEmpty()) out.add(Folder(d, fs, false))
        }
        return out.sortedByDescending { it.time }
    }

    @Synchronized
    fun list(): List<File> = folders().flatMap { it.files }

    @Synchronized
    fun delete(file: File): Boolean {
        val inside = file.absolutePath.startsWith(dir.absolutePath + File.separator)
        val ok = inside && file.delete()
        if (ok) {
            hashes.values.remove(file.absolutePath)
            val p = file.parentFile
            if (p != null && p != dir && p != tmpDir && p.list()?.isEmpty() == true) p.delete()
        }
        return ok
    }

    @Synchronized
    fun save(bytes: ByteArray): Result {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return Result.INVALID
        val ext = sniff(bytes) ?: return Result.INVALID
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return Result.INVALID // corrupt

        val sha = sha256(bytes)
        if (hashes.containsKey(sha)) return Result.DUPLICATE
        if (dir.usableSpace < bytes.size + MIN_FREE) return Result.NO_SPACE

        val tmp = File(tmpDir, UUID.randomUUID().toString() + ".part")
        return try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            val b = batch ?: stamp().also { batch = it }
            val target = File(dir, b).apply { mkdirs() }
            val dest = nextFile(target, ext)
            if (!tmp.renameTo(dest)) { tmp.delete(); return Result.IO }
            hashes[sha] = dest.absolutePath
            lastSaved = dest
            Result.SAVED
        } catch (e: IOException) {
            tmp.delete()
            Result.IO
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun nextFile(target: File, ext: String): File {
        val s = stamp()
        var n = 1
        while (true) {
            val f = File(target, "image_${s}_%03d.$ext".format(n))
            if (!f.exists()) return f
            n++
        }
    }

    /** Format comes from the bytes, never from anything the page claims. */
    private fun sniff(b: ByteArray): String? {
        fun at(i: Int, vararg v: Int) = b.size > i + v.size && v.indices.all { (b[i + it].toInt() and 0xFF) == v[it] }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            at(0, 0x47, 0x49, 0x46, 0x38) -> "gif"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
            else -> null
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
        const val MIN_FREE = 20L * 1024 * 1024
    }
}
