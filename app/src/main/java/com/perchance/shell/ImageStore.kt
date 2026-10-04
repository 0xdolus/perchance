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
 * Private storage: context.filesDir/generated_images/. No MediaStore, no public dirs.
 * Writes go to .tmp/ first, then are renamed into place, so a final file is never half-written.
 */
class ImageStore(context: Context) {

    enum class Result { SAVED, DUPLICATE, INVALID, NO_SPACE, IO }

    val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
    private val tmpDir = File(dir, ".tmp").apply { mkdirs() }
    private val hashes = HashMap<String, String>() // sha256 -> file name
    private var indexed = false

    @Synchronized
    fun cleanTmp() {
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun ensureIndexed() {
        if (indexed) return
        hashes.clear()
        list().forEach { f ->
            try { hashes[sha256(f.readBytes())] = f.name } catch (_: Exception) {}
        }
        indexed = true
    }

    @Synchronized
    fun list(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("image_") } ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    @Synchronized
    fun delete(file: File): Boolean {
        val ok = file.parentFile == dir && file.delete()
        if (ok) hashes.values.remove(file.name)
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
            val dest = nextFile(ext)
            if (!tmp.renameTo(dest)) { tmp.delete(); return Result.IO }
            hashes[sha] = dest.name
            Result.SAVED
        } catch (e: IOException) {
            tmp.delete()
            Result.IO
        }
    }

    private fun nextFile(ext: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var n = 1
        while (true) {
            val f = File(dir, "image_${stamp}_%03d.$ext".format(n))
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
