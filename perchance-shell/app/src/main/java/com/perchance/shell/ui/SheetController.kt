package com.perchance.shell.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.perchance.shell.R
import java.io.File

/**
 * Minimal programmatic UI for sheets and full-screen destinations.
 * Uses system icons / plain views only.
 */
class SheetController(
    private val context: Context,
    private val sheetContainer: FrameLayout,
    private val fullContainer: FrameLayout
) {

    fun showBar(onDownload: () -> Unit, onLibrary: () -> Unit) {
        sheetContainer.removeAllViews()
        sheetContainer.visibility = View.VISIBLE

        val sheet = makeSheet()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
        }

        val dl = makeButton(context.getString(R.string.download_all), true) { onDownload() }
        val lib = makeButton("▦", false) { onLibrary() }.apply {
            layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { marginStart = 16 }
        }

        row.addView(dl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        row.addView(lib)
        sheet.addView(row)
        sheetContainer.addView(sheet)
    }

    fun showScanning() {
        sheetContainer.removeAllViews()
        sheetContainer.visibility = View.VISIBLE
        val sheet = makeSheet()
        sheet.addView(TextView(context).apply {
            text = context.getString(R.string.scanning)
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 16f
        })
        sheetContainer.addView(sheet)
    }

    fun showProgress(done: Int, total: Int) {
        sheetContainer.removeAllViews()
        sheetContainer.visibility = View.VISIBLE
        val sheet = makeSheet()
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        col.addView(TextView(context).apply {
            text = context.getString(R.string.downloading)
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        col.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total.coerceAtLeast(1)
            progress = done
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        })
        col.addView(TextView(context).apply {
            text = "$done of $total"
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 0)
        })
        sheet.addView(col)
        sheetContainer.addView(sheet)
    }

    fun showDone(count: Int, onOpenLibrary: () -> Unit) {
        sheetContainer.removeAllViews()
        sheetContainer.visibility = View.VISIBLE
        val sheet = makeSheet()
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(TextView(context).apply {
            text = "✓"
            textSize = 36f
            setTextColor(Color.parseColor("#2FBF71"))
            gravity = Gravity.CENTER
        })
        col.addView(TextView(context).apply {
            text = context.getString(R.string.images_saved)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 4)
        })
        col.addView(TextView(context).apply {
            text = "$count images\n${context.getString(R.string.saved_privately)}"
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        })
        col.addView(makeButton(context.getString(R.string.open_library), true) {
            hideAllSheets()
            onOpenLibrary()
        })
        col.addView(makeButton(context.getString(R.string.done), false) {
            hideAllSheets()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        })
        sheet.addView(col)
        sheetContainer.addView(sheet)
    }

    fun showFailed(saved: Int, failed: Int, onRetry: () -> Unit) {
        sheetContainer.removeAllViews()
        sheetContainer.visibility = View.VISIBLE
        val sheet = makeSheet()
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        col.addView(TextView(context).apply {
            text = "$failed images couldn’t be saved"
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        col.addView(TextView(context).apply {
            text = "$saved saved."
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 20)
        })
        col.addView(makeButton("Retry failed", true) { onRetry() })
        col.addView(makeButton("Keep saved images", false) {
            hideAllSheets()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        })
        sheet.addView(col)
        sheetContainer.addView(sheet)
    }

    fun showNetworkError(onRetry: () -> Unit) {
        sheetContainer.removeAllViews()
        sheetContainer.visibility = View.VISIBLE
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E0E12"))
            setPadding(48, 48, 48, 48)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        col.addView(TextView(context).apply {
            text = "⚠"
            textSize = 40f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(context).apply {
            text = context.getString(R.string.network_error)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        })
        col.addView(TextView(context).apply {
            text = context.getString(R.string.network_error_msg)
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })
        col.addView(makeButton(context.getString(R.string.retry), true) {
            hideAllSheets()
            onRetry()
        })
        sheetContainer.addView(col)
    }

    fun showLibrary(files: List<File>, onClose: () -> Unit, onImageClick: (String) -> Unit) {
        fullContainer.removeAllViews()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E12"))
            setPadding(16, 48, 16, 16)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Private images"
            setTextColor(Color.WHITE)
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(makeButton("✕", false) { onClose() }.apply {
            layoutParams = LinearLayout.LayoutParams(100, 100)
        })
        root.addView(header)

        if (files.isEmpty()) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.empty_library)
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 80, 0, 0)
                textSize = 16f
            })
        } else {
            val scroll = ScrollView(context)
            val grid = GridLayout(context).apply {
                columnCount = 3
                setPadding(0, 16, 0, 0)
            }
            files.forEach { file ->
                val iv = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.DKGRAY)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(4, 4, 4, 4)
                    }
                    setOnClickListener { onImageClick(file.absolutePath) }
                    // load thumbnail
                    try {
                        val bm = BitmapFactory.decodeFile(file.absolutePath)
                        setImageBitmap(bm)
                    } catch (_: Exception) {}
                }
                grid.addView(iv)
            }
            scroll.addView(grid)
            root.addView(scroll)
        }
        fullContainer.addView(root)
    }

    fun showDetail(path: String, onDelete: () -> Unit) {
        fullContainer.removeAllViews()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E12"))
            setPadding(16, 48, 16, 16)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(makeButton("←", false) {
            // back handled by activity
        }.apply { layoutParams = LinearLayout.LayoutParams(100, 100) })
        header.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(makeButton(context.getString(R.string.delete), false) {
            AlertDialog.Builder(context)
                .setTitle(R.string.delete_confirm)
                .setMessage(R.string.delete_message)
                .setPositiveButton(R.string.delete) { _, _ -> onDelete() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        root.addView(header)

        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            try {
                setImageBitmap(BitmapFactory.decodeFile(path))
            } catch (_: Exception) {}
        }
        root.addView(iv)
        root.addView(TextView(context).apply {
            text = File(path).name
            setTextColor(Color.GRAY)
            setPadding(0, 12, 0, 0)
            textSize = 12f
        })
        fullContainer.addView(root)
    }

    fun showCancelDownloadDialog(onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.cancel_download)
            .setPositiveButton(R.string.cancel) { _, _ -> onConfirm() }
            .setNegativeButton("Continue", null)
            .show()
    }

    fun hideAllSheets() {
        sheetContainer.visibility = View.GONE
        sheetContainer.removeAllViews()
    }

    private fun makeSheet(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B1B22"))
            elevation = 16f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                setMargins(16, 0, 16, 16)
            }
        }
    }

    private fun makeButton(text: String, primary: Boolean, onClick: () -> Unit): Button {
        return Button(context).apply {
            this.text = text
            setOnClickListener { onClick() }
            if (primary) {
                setBackgroundColor(Color.parseColor("#7C6CFF"))
                setTextColor(Color.WHITE)
            } else {
                setBackgroundColor(Color.parseColor("#2A2A33"))
                setTextColor(Color.WHITE)
            }
            isAllCaps = false
        }
    }
}
