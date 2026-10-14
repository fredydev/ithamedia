package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.roundToInt

/** Comparer deux versions d'un PDF : rendu page à page, différences surlignées en vermillon. */
class CompareActivity : BaseToolActivity() {

    private var first: File? = null
    private var second: File? = null
    private var picking = 0

    private class PageResult(val index: Int, val changed: Float, val overlay: Bitmap?, val note: String?)

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runTask(getString(R.string.loading), { Library.cacheCopy(this, uri) }) { set(picking, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_compare, Tool.COMPARE.title)
        findViewById<TextView>(R.id.intro).text = Tool.COMPARE.subtitle
        bindCard(R.id.cardA, 0)
        bindCard(R.id.cardB, 1)
        findViewById<Button>(R.id.compareButton).setOnClickListener { compare() }
        intent.paths().getOrNull(0)?.let { set(0, it) }
        intent.paths().getOrNull(1)?.let { set(1, it) }
        update()
    }

    private fun bindCard(id: Int, slot: Int) {
        val card = findViewById<View>(id)
        card.findViewById<TextView>(R.id.fileName).setText(if (slot == 0) R.string.compare_original else R.string.compare_revised)
        card.findViewById<TextView>(R.id.fileMeta).setText(R.string.tap_to_choose)
        card.findViewById<Button>(R.id.changeFile).setText(R.string.choose)
        val launch = View.OnClickListener {
            picking = slot
            pick.launch(arrayOf("application/pdf"))
        }
        card.setOnClickListener(launch)
        card.findViewById<View>(R.id.changeFile).setOnClickListener(launch)
    }

    private fun set(slot: Int, file: File) {
        if (slot == 0) first = file else second = file
        val card = findViewById<View>(if (slot == 0) R.id.cardA else R.id.cardB)
        card.findViewById<TextView>(R.id.fileName).text = file.name
        val pages = PdfOps.pageCount(file)
        card.findViewById<TextView>(R.id.fileMeta).text =
            (if (slot == 0) "A · " else "B · ") + (pages?.let { resources.getQuantityString(R.plurals.pages_count, it, it) + " · " } ?: "") + formatSize(file.length())
        FileRow.bindIcon(card.findViewById(R.id.fileThumb), file)
        card.findViewById<Button>(R.id.changeFile).setText(R.string.change)
        update()
    }

    private fun update() {
        findViewById<Button>(R.id.compareButton).isEnabled = first != null && second != null
    }

    private fun compare() {
        val a = first ?: return
        val b = second ?: return
        val width = (resources.displayMetrics.widthPixels * 0.9f).toInt().coerceIn(400, 900)
        val highlight = ContextCompat.getColor(this, R.color.accent)
        runTask(getString(R.string.comparing), {
            val results = mutableListOf<PageResult>()
            open(a).use { ra ->
                open(b).use { rb ->
                    val n = maxOf(ra.pageCount, rb.pageCount)
                    var kept = 0
                    for (i in 0 until n) {
                        if (i >= ra.pageCount) {
                            results += PageResult(i, 1f, null, getString(R.string.page_added))
                            continue
                        }
                        if (i >= rb.pageCount) {
                            results += PageResult(i, 1f, null, getString(R.string.page_removed))
                            continue
                        }
                        val pa = render(ra, i, width)
                        val pb = render(rb, i, width)
                        val (fraction, overlay) = PdfOps.diff(pa, pb, highlight)
                        pa.recycle()
                        pb.recycle()
                        if (fraction > 0.0004f && kept < 40) {
                            kept++
                            results += PageResult(i, fraction, overlay, null)
                        } else {
                            overlay.recycle()
                            results += PageResult(i, fraction, null, null)
                        }
                    }
                }
            }
            results
        }) { show(it) }
    }

    private class Pdf(file: File) : AutoCloseable {
        val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val pageCount get() = renderer.pageCount
        override fun close() {
            renderer.close()
            fd.close()
        }
    }

    private fun open(file: File) = Pdf(file)

    private fun render(pdf: Pdf, index: Int, width: Int): Bitmap = pdf.renderer.openPage(index).use { page ->
        val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    private fun show(results: List<PageResult>) {
        val container = findViewById<LinearLayout>(R.id.results)
        container.removeAllViews()
        val changed = results.filter { it.note != null || it.changed > 0.0004f }
        findViewById<TextView>(R.id.summary).apply {
            visibility = View.VISIBLE
            text = if (changed.isEmpty()) getString(R.string.compare_identical, results.size)
            else getString(R.string.compare_summary, changed.size, results.size)
        }
        val dp = resources.displayMetrics.density
        for (r in changed) {
            container.addView(TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_Atelier_Label)
                text = r.note?.let { "Page ${r.index + 1} · $it" }
                    ?: "Page ${r.index + 1} · ${"%.1f".format(r.changed * 100)} % modifié"
                setPadding(0, (22 * dp).toInt(), 0, (8 * dp).toInt())
            })
            r.overlay?.let { bmp ->
                container.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    setBackgroundResource(R.drawable.bg_thumb)
                    setPadding(1, 1, 1, 1)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        if (changed.size > 40) {
            container.addView(TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_Atelier_Body)
                text = getString(R.string.compare_truncated)
            })
        }
        findViewById<View>(R.id.scroll).post {
            findViewById<androidx.core.widget.NestedScrollView>(R.id.scroll).smoothScrollTo(0, findViewById<View>(R.id.summary).top - (16 * dp).roundToInt())
        }
    }
}
