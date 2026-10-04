package com.perchance.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** Private gallery: date-grouped grid, multi-select delete, swipeable zoomable detail. No share/export. */
class LibraryActivity : AppCompatActivity() {

    private sealed class Item {
        class Header(val label: String) : Item()
        class Row(val files: List<File>) : Item()
    }

    private lateinit var store: ImageStore
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var back: TextView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var trash: TextView
    private lateinit var detail: FrameLayout
    private lateinit var zoom: ZoomImageView
    private lateinit var pos: TextView

    private var files: List<File> = emptyList()
    private var items: List<Item> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var detailIndex = -1
    private var loadToken = 0
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ImageStore(this)
        val root = FrameLayout(this).apply { setBackgroundColor(color(R.color.page_bg)) }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        back = iconButton("\u2190") { if (selected.isNotEmpty()) clearSelection() else finish() }
        title = TextView(this).apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color(R.color.text))
        }
        count = TextView(this).apply { textSize = 13f; setTextColor(color(R.color.text_muted)) }
        trash = pillText("Delete", R.color.danger) { confirmDeleteSelected() }.apply { visibility = View.GONE }
        page.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            addView(back)
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(count)
            addView(trash, LinearLayout.LayoutParams(-2, dp(44)))
        })

        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; dividerHeight = 0
            setPadding(dp(4), 0, dp(4), dp(8)); clipToPadding = false
            selector = ColorDrawable(0)
            adapter = Adapter()
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
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

        zoom = ZoomImageView(this).apply {
            onSwipe = { d ->
                val n = detailIndex + d
                if (n in files.indices) { detailIndex = n; showImage() }
            }
        }
        pos = TextView(this).apply { textSize = 13f; setTextColor(color(R.color.text_muted)); gravity = Gravity.CENTER }
        detail = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.page_bg)); visibility = View.GONE; isClickable = true
            addView(zoom, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(64); bottomMargin = dp(16) })
            addView(iconButton("\u2190") { closeDetail() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
            addView(pos, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
            addView(pillText("Delete", R.color.danger) { confirmDeleteCurrent() },
                FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(12), 0) })
        }
        root.addView(detail, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    detail.visibility == View.VISIBLE -> closeDetail()
                    selected.isNotEmpty() -> clearSelection()
                    else -> finish()
                }
            }
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    // ---------- data ----------

    private fun refresh() {
        files = store.list()
        selected.retainAll(files.map { it.absolutePath }.toSet())
        items = group(files)
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        updateHeader()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun group(fs: List<File>): List<Item> {
        val out = ArrayList<Item>()
        var key = ""
        var bucket = ArrayList<File>()
        fun flush() {
            bucket.chunked(3).forEach { out.add(Item.Row(it)) }
            bucket = ArrayList()
        }
        for (f in fs) {
            val k = dayLabel(f.lastModified())
            if (k != key) { flush(); out.add(Item.Header(k)); key = k }
            bucket.add(f)
        }
        flush()
        return out
    }

    private fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)

    private fun dayLabel(t: Long): String {
        val today = dayKey(Calendar.getInstance())
        val yesterday = dayKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) })
        val d = dayKey(Calendar.getInstance().apply { timeInMillis = t })
        return when (d) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(t))
        }
    }

    // ---------- header / selection ----------

    private fun updateHeader() {
        if (selected.isNotEmpty()) {
            back.text = "\u2715"; title.text = "${selected.size} selected"
            count.visibility = View.GONE; trash.visibility = View.VISIBLE
        } else {
            back.text = "\u2190"; title.text = "Private images"
            count.text = if (files.isEmpty()) "" else "${files.size}"
            count.visibility = View.VISIBLE; trash.visibility = View.GONE
        }
    }

    private fun toggle(f: File) {
        if (!selected.remove(f.absolutePath)) selected.add(f.absolutePath)
        updateHeader()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear(); updateHeader()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun confirmDeleteSelected() {
        val n = selected.size
        if (n == 0) return
        AlertDialog.Builder(this)
            .setTitle("Delete $n image${if (n == 1) "" else "s"}?")
            .setMessage("They will be removed from this app. This can\u2019t be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                files.filter { selected.contains(it.absolutePath) }.forEach {
                    store.delete(it); cache.remove(it.absolutePath)
                }
                selected.clear()
                refresh()
            }.show()
    }

    // ---------- detail ----------

    private fun openDetail(index: Int) {
        if (index !in files.indices) return
        detailIndex = index
        showImage()
        detail.alpha = 0f; detail.scaleX = 0.97f; detail.scaleY = 0.97f
        detail.visibility = View.VISIBLE
        detail.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    private fun showImage() {
        val f = files[detailIndex]
        pos.text = "${detailIndex + 1} / ${files.size}"
        zoom.setImageBitmap(null)
        val token = ++loadToken
        val m = resources.displayMetrics
        io.execute {
            val bmp = decodeSampled(f, m.widthPixels, m.heightPixels)
            main.post { if (token == loadToken) zoom.setImageBitmap(bmp) }
        }
    }

    private fun closeDetail() {
        loadToken++
        detail.visibility = View.GONE
        zoom.setImageBitmap(null)
        detailIndex = -1
    }

    private fun confirmDeleteCurrent() {
        val f = files.getOrNull(detailIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this image?")
            .setMessage("It will be removed from this app. This can\u2019t be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.delete(f); cache.remove(f.absolutePath)
                refresh()
                if (files.isEmpty()) closeDetail()
                else { detailIndex = minOf(detailIndex, files.size - 1); showImage() }
            }.show()
    }

    // ---------- views ----------

    private fun iconButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 22f; gravity = Gravity.CENTER; setTextColor(color(R.color.text))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun pillText(t: String, c: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(c)); gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        background = rounded(color(R.color.surface), 14)
        setOnClickListener { onClick() }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p] is Item.Header) 0 else 1
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val item = items[p]
            if (item is Item.Header) {
                val tv = (convert as? TextView) ?: TextView(this@LibraryActivity).apply {
                    textSize = 13f; setTextColor(color(R.color.text_muted))
                    setPadding(dp(6), dp(16), dp(6), dp(8))
                }
                tv.text = item.label
                return tv
            }
            val row = (convert as? RowView) ?: RowView(this@LibraryActivity)
            row.bind((item as Item.Row).files)
            return row
        }
    }

    private inner class RowView(c: Context) : LinearLayout(c) {
        private val cells = Array(3) { Cell(c) }
        init {
            orientation = HORIZONTAL
            cells.forEach {
                addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
        }
        fun bind(fs: List<File>) {
            cells.forEachIndexed { i, cell -> if (i < fs.size) cell.bind(fs[i]) else cell.visibility = View.INVISIBLE }
        }
    }

    private inner class Cell(c: Context) : FrameLayout(c) {
        private val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val mark = TextView(c).apply {
            text = "\u2713"; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.bg)); background = rounded(color(R.color.accent), 12); visibility = View.GONE
        }
        init {
            background = rounded(color(R.color.surface), 8); clipToOutline = true
            addView(iv, FrameLayout.LayoutParams(-1, -1))
            addView(mark, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        }
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)

        fun bind(f: File) {
            visibility = View.VISIBLE
            val path = f.absolutePath
            tag = path
            val sel = selected.contains(path)
            mark.visibility = if (sel) View.VISIBLE else View.GONE
            iv.alpha = if (sel) 0.55f else 1f
            val hit = cache.get(path)
            if (hit != null) iv.setImageBitmap(hit) else {
                iv.setImageBitmap(null)
                io.execute {
                    val bmp = decodeSampled(f, 256, 256) ?: return@execute
                    cache.put(path, bmp)
                    main.post { if (tag == path) iv.setImageBitmap(bmp) }
                }
            }
            setOnClickListener { if (selected.isNotEmpty()) toggle(f) else openDetail(files.indexOf(f)) }
            setOnLongClickListener { toggle(f); true }
        }
    }
}

/** Pinch to zoom, drag when zoomed, double-tap toggle, horizontal fling (when not zoomed) to change image. */
class ZoomImageView(c: Context) : ImageView(c) {
    var onSwipe: ((Int) -> Unit)? = null
    private val m = Matrix()
    private var rel = 1f

    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY); return true
        }
    })

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (rel > 1.01f) fit() else zoomBy(2.5f, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (rel > 1.01f && !scaler.isInProgress) { m.postTranslate(-dx, -dy); clamp() }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (rel <= 1.01f && abs(vx) > 900f && abs(vx) > abs(vy) * 1.5f) onSwipe?.invoke(if (vx < 0) 1 else -1)
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageDrawable(d: Drawable?) {
        super.setImageDrawable(d)
        if (d != null) fit()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (width == 0 || height == 0 || dw <= 0f || dh <= 0f) return
        val s = minOf(width / dw, height / dh)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        rel = 1f
        imageMatrix = m
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val target = (rel * f).coerceIn(1f, 5f)
        val real = target / rel
        m.postScale(real, real, fx, fy)
        rel = target
        clamp()
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = width / 2f - r.centerX()
        else if (r.left > 0f) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = height / 2f - r.centerY()
        else if (r.top > 0f) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }
}
