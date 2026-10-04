package com.perchance.shell.storage

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class PrivateStorageManager(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "generated_images").also { it.mkdirs() }
    }

    private val counter = AtomicInteger(0)

    fun save(bytes: ByteArray, mime: String): File? {
        if (bytes.size > 25 * 1024 * 1024) return null // 25 MB cap

        // Validate it's an image
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            else -> "jpg"
        }

        val sha = sha256(bytes)
        // simple dedupe by name containing hash prefix
        val existing = dir.listFiles()?.find { it.name.contains(sha.take(12)) }
        if (existing != null) return existing

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val n = counter.incrementAndGet().toString().padStart(3, '0')
        val name = "image_${ts}_${n}_${sha.take(8)}.$ext"
        val tmp = File(dir, "$name.tmp")
        val final = File(dir, name)

        try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(final)) {
                tmp.copyTo(final, overwrite = true)
                tmp.delete()
            }
            return final
        } catch (_: Exception) {
            tmp.delete()
            return null
        }
    }

    fun listImages(): List<File> {
        return dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun delete(path: String): Boolean {
        return File(path).takeIf { it.exists() && it.canonicalPath.startsWith(dir.canonicalPath) }?.delete() ?: false
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
