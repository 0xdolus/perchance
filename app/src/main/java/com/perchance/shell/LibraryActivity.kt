package com.perchance.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/** Private gallery: grid + detail. No share, export or "open with". */
class LibraryActivity : AppCompatActivity() {

    private lateinit var store: ImageStore
    private lateinit var grid: GridView
    private lateinit var empty: LinearLayout
    private lateinit var detail: FrameLayout
    private lateinit var detailImage: ImageView
    private lateinit var count: TextView

    private var files: List<File> = emptyList()
    private var current: File? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ImageStore(this)
        val root = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
            addView(iconButton("\u2190") { finish() })
            addView(TextView(context).apply {
                text = "Private images"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            count = TextView(context).apply { textSize = 13f; setTextColor(color(R.color.text_muted)) }
            addView(count)
        }
        page.addView(header)

        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4); verticalSpacing = dp(4)
            setPadding(dp(4), 0, dp(4), dp(4)); clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ -> openDetail(files[pos]) }
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(80))
            addView(TextView(context).apply {
                text = "No saved images yet"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text)); gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Images downloaded from this app will appear here."; textSize = 14f
                setTextColor(color(R.color.text_muted)); gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        detailImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        detail = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.bg)); visibility = View.GONE; isClickable = true
            addView(detailImage, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(64); bottomMargin = dp(16) })
            addView(iconButton("\u2190") { closeDetail() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
            addView(TextView(context).apply {
                text = "Delete"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.danger)); gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = rounded(color(R.color.surface), 14)
                setOnClickListener { confirmDelete() }
            }, FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(12), 0) })
        }
        root.addView(detail, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detail.visibility == View.VISIBLE) closeDetail() else finish()
            }
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        files = store.list()
        count.text = if (files.isEmpty()) "" else "${files.size}"
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun iconButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 22f; gravity = Gravity.CENTER; setTextColor(color(R.color.text))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun openDetail(f: File) {
        current = f
        detailImage.setImageBitmap(null)
        val m = resources.displayMetrics
        io.execute {
            val bmp = decode(f, m.widthPixels, m.heightPixels)
            main.post { if (current == f) detailImage.setImageBitmap(bmp) }
        }
        detail.alpha = 0f; detail.scaleX = 0.97f; detail.scaleY = 0.97f
        detail.visibility = View.VISIBLE
        detail.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    private fun closeDetail() {
        detail.visibility = View.GONE
        detailImage.setImageBitmap(null)
        current = null
    }

    private fun confirmDelete() {
        val f = current ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this image?")
            .setMessage("It will be removed from this app. This can\u2019t be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.delete(f)
                cache.remove(f.absolutePath)
                closeDetail()
                refresh()
            }.show()
    }

    private fun decode(f: File, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private inner class Square(c: Context) : ImageView(c) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(p: Int) = files[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val iv = (convert as? ImageView) ?: Square(this@LibraryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(color(R.color.surface), 8)
                clipToOutline = true
            }
            val f = files[p]
            iv.tag = f.absolutePath
            val hit = cache.get(f.absolutePath)
            if (hit != null) iv.setImageBitmap(hit) else {
                iv.setImageBitmap(null)
                io.execute {
                    val bmp = decode(f, 256, 256) ?: return@execute
                    cache.put(f.absolutePath, bmp)
                    main.post { if (iv.tag == f.absolutePath) iv.setImageBitmap(bmp) }
                }
            }
            return iv
        }
    }
}
