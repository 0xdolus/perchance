package com.perchance.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.HorizontalScrollView
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

/**
 * Private library. Level 1: folders (one per Download All). Level 2: image grid with multi-select.
 * Level 3: full-bleed viewer (tap toggles chrome, filmstrip, swipe, pinch/double-tap zoom).
 * No share, export or "open with".
 */
class LibraryActivity : AppCompatActivity() {

    private sealed class Item {
        class FolderItem(val folder: ImageStore.Folder) : Item()
        class Row(val files: List<File>) : Item()
    }

    private lateinit var store: ImageStore
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var back: TextView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var trash: TextView
    private lateinit var viewer: FrameLayout
    private lateinit var zoom: ZoomImageView
    private lateinit var pos: TextView
    private lateinit var info: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var strip: LinearLayout

    private var folders: List<ImageStore.Folder> = emptyList()
    private var openKey: String? = null
    private var files: List<File> = emptyList()
    private var items: List<Item> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var index = -1
    private var token = 0
    private var chrome = true
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

        back = iconButton("\u2190") { goBack() }
        title = TextView(this).apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color(R.color.text)); maxLines = 1
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
            setPadding(dp(8), 0, dp(8), dp(8)); clipToPadding = false
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

        buildViewer()
        root.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun goBack() {
        when {
            viewer.visibility == View.VISIBLE -> closeViewer()
            selected.isNotEmpty() -> clearSelection()
            openKey != null -> { openKey = null; refresh() }
            else -> finish()
        }
    }

    // ---------- data ----------

    private fun refresh() {
        folders = store.folders()
        val open = folders.firstOrNull { it.key == openKey }
        if (open == null) openKey = null
        files = open?.files ?: emptyList()
        items = if (open == null) folders.map { Item.FolderItem(it) } else files.chunked(3).map { Item.Row(it) }
        selected.retainAll(files.map { it.absolutePath }.toSet())
        empty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (folders.isEmpty()) View.GONE else View.VISIBLE
        updateHeader()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
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

    private fun folderTitle(f: ImageStore.Folder): String =
        if (f.loose) "Earlier"
        else dayLabel(f.time) + " \u00B7 " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(f.time))

    private fun size(b: Long): String =
        if (b < 1024 * 1024) "${maxOf(1L, b / 1024)} KB" else "%.1f MB".format(b / 1048576.0)

    // ---------- header / selection ----------

    private fun updateHeader() {
        val open = folders.firstOrNull { it.key == openKey }
        if (selected.isNotEmpty()) {
            back.text = "\u2715"; title.text = "${selected.size} selected"
            count.visibility = View.GONE; trash.visibility = View.VISIBLE
        } else {
            back.text = "\u2190"
            title.text = if (open == null) "Gallery" else folderTitle(open)
            count.text = if (open == null) "${folders.sumOf { it.files.size }}" else "${files.size}"
            count.visibility = if (folders.isEmpty()) View.GONE else View.VISIBLE
            trash.visibility = View.GONE
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

    private fun confirm(titleText: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setMessage("This will remove it from this app. It can\u2019t be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> onYes() }
            .show()
    }

    private fun deleteFiles(fs: List<File>) {
        fs.forEach { store.delete(it); cache.remove(it.absolutePath) }
    }

    private fun confirmDeleteSelected() {
        val n = selected.size
        if (n == 0) return
        confirm("Delete $n image${if (n == 1) "" else "s"}?") {
            deleteFiles(files.filter { selected.contains(it.absolutePath) })
            selected.clear()
            refresh()
        }
    }

    private fun confirmDeleteFolder(f: ImageStore.Folder) {
        confirm("Delete folder with ${f.files.size} image${if (f.files.size == 1) "" else "s"}?") {
            deleteFiles(f.files)
            refresh()
        }
    }

    // ---------- viewer ----------

    private fun buildViewer() {
        zoom = ZoomImageView(this).apply {
            onSwipe = { d ->
                val n = index + d
                if (n in files.indices) { index = n; showImage() }
            }
            onTap = { setChrome(!chrome) }
        }
        pos = TextView(this).apply { textSize = 13f; setTextColor(color(R.color.text)); gravity = Gravity.CENTER }
        info = TextView(this).apply { textSize = 12f; setTextColor(color(R.color.text_muted)); maxLines = 2 }
        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        stripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, FrameLayout.LayoutParams(-2, -1))
        }

        topBar = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0))
            addView(iconButton("\u2190") { closeViewer() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(8) })
            addView(pos, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0))
            setPadding(0, dp(24), 0, dp(12))
            addView(stripScroll, LinearLayout.LayoutParams(-1, dp(60)))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), 0)
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                addView(pillText("Delete", R.color.danger) { confirmDeleteCurrent() }, LinearLayout.LayoutParams(-2, dp(44)))
            })
        }
        viewer = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE; isClickable = true
            addView(zoom, FrameLayout.LayoutParams(-1, -1))
            addView(topBar, FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
    }

    private fun setChrome(show: Boolean) {
        chrome = show
        listOf(topBar, bottomBar).forEach { v ->
            if (show) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(150).start() }
            else v.animate().alpha(0f).setDuration(150).withEndAction { if (!chrome) v.visibility = View.GONE }.start()
        }
    }

    private fun openViewer(i: Int) {
        if (i !in files.indices) return
        index = i
        chrome = true
        topBar.alpha = 1f; bottomBar.alpha = 1f; topBar.visibility = View.VISIBLE; bottomBar.visibility = View.VISIBLE
        buildStrip()
        showImage()
        viewer.alpha = 0f
        viewer.visibility = View.VISIBLE
        viewer.animate().alpha(1f).setDuration(160).start()
    }

    private fun buildStrip() {
        strip.removeAllViews()
        files.forEachIndexed { i, f ->
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(color(R.color.surface), 8); clipToOutline = true
                setOnClickListener { index = i; showImage() }
            }
            strip.addView(iv, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(6); gravity = Gravity.CENTER_VERTICAL })
            loadThumb(iv, f)
        }
    }

    private fun showImage() {
        val f = files.getOrNull(index) ?: return
        pos.text = "${index + 1} / ${files.size}"
        for (i in 0 until strip.childCount) {
            val v = strip.getChildAt(i)
            val cur = i == index
            v.alpha = if (cur) 1f else 0.45f
            v.scaleX = if (cur) 1.1f else 1f; v.scaleY = v.scaleX
        }
        strip.getChildAt(index)?.let { c ->
            stripScroll.post { stripScroll.smoothScrollTo(c.left - (stripScroll.width - c.width) / 2, 0) }
        }
        info.text = f.name
        zoom.setImageBitmap(null)
        val t = ++token
        val m = resources.displayMetrics
        io.execute {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val meta = f.name + "\n" + o.outWidth + " \u00D7 " + o.outHeight + "  \u00B7  " + size(f.length())
            val bmp = decodeSampled(f, m.widthPixels, m.heightPixels)
            main.post { if (t == token) { info.text = meta; zoom.setImageBitmap(bmp) } }
        }
    }

    private fun closeViewer() {
        token++
        viewer.visibility = View.GONE
        zoom.setImageBitmap(null)
        index = -1
    }

    private fun confirmDeleteCurrent() {
        val f = files.getOrNull(index) ?: return
        confirm("Delete this image?") {
            deleteFiles(listOf(f))
            refresh()
            if (files.isEmpty()) closeViewer()
            else { index = minOf(index, files.size - 1); buildStrip(); showImage() }
        }
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

    private fun loadThumb(iv: ImageView, f: File) {
        val path = f.absolutePath
        iv.tag = path
        val hit = cache.get(path)
        if (hit != null) { iv.setImageBitmap(hit); return }
        iv.setImageBitmap(null)
        io.execute {
            val b = decodeSampled(f, 256, 256) ?: return@execute
            cache.put(path, b)
            main.post { if (iv.tag == path) iv.setImageBitmap(b) }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p] is Item.FolderItem) 0 else 1
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val item = items[p]
            if (item is Item.FolderItem) {
                val v = (convert as? FolderView) ?: FolderView(this@LibraryActivity)
                v.bind(item.folder)
                return v
            }
            val row = (convert as? RowView) ?: RowView(this@LibraryActivity)
            row.bind((item as Item.Row).files)
            return row
        }
    }

    private inner class FolderView(c: Context) : FrameLayout(c) {
        private val cover = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(color(R.color.border), 12); clipToOutline = true
        }
        private val name = TextView(c).apply {
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color(R.color.text)); maxLines = 1
        }
        private val sub = TextView(c).apply { textSize = 13f; setTextColor(color(R.color.text_muted)) }
        private val card = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(16), dp(12))
            background = rounded(color(R.color.surface), 18)
            addView(cover, LinearLayout.LayoutParams(dp(68), dp(68)))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                addView(name); addView(sub)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(c).apply { text = "\u203A"; textSize = 26f; setTextColor(color(R.color.text_muted)) })
        }

        init {
            setPadding(0, dp(4), 0, dp(4))
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }

        fun bind(f: ImageStore.Folder) {
            name.text = folderTitle(f)
            sub.text = "${f.files.size} image${if (f.files.size == 1) "" else "s"}"
            loadThumb(cover, f.files.first())
            card.setOnClickListener { openKey = f.key; refresh(); list.setSelection(0) }
            card.setOnLongClickListener { confirmDeleteFolder(f); true }
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
            val sel = selected.contains(f.absolutePath)
            mark.visibility = if (sel) View.VISIBLE else View.GONE
            iv.alpha = if (sel) 0.55f else 1f
            loadThumb(iv, f)
            setOnClickListener { if (selected.isNotEmpty()) toggle(f) else openViewer(files.indexOf(f)) }
            setOnLongClickListener { toggle(f); true }
        }
    }
}

/** Pinch to zoom, drag when zoomed, double-tap toggle, single tap callback, horizontal fling to change image. */
class ZoomImageView(c: Context) : ImageView(c) {
    var onSwipe: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    private val m = Matrix()
    private var rel = 1f

    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY); return true
        }
    })

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
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
