package com.ithamedia.pdfsigner

import android.content.res.ColorStateList
import android.graphics.PointF
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Historique commun des écrans de dessin (annoter, caviarder). */
private sealed class MarkAction(val page: Int, val mark: Mark) {
    class Added(page: Int, mark: Mark) : MarkAction(page, mark)
    class Removed(page: Int, mark: Mark, val index: Int) : MarkAction(page, mark)
}

/** Base des écrans où l'on dessine sur les pages d'un PDF. */
abstract class DrawToolActivity : BaseToolActivity() {

    protected lateinit var canvasView: AnnotationView
    protected val marksByPage = HashMap<Int, MutableList<Mark>>()
    private val history = ArrayDeque<MarkAction>()
    protected var source: File? = null
    private var renderer: PageRenderer? = null
    protected var currentPage = 0
    private lateinit var indicator: TextView

    protected abstract val layoutRes: Int
    protected abstract val defaultTool: Tool

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(layoutRes, Tool.from(intent, defaultTool).title)
        canvasView = findViewById(R.id.annotationView)
        indicator = findViewById(R.id.pageIndicator)
        findViewById<View>(R.id.prevPage).setOnClickListener { showPage(currentPage - 1) }
        findViewById<View>(R.id.nextPage).setOnClickListener { showPage(currentPage + 1) }
        toolbar.inflateMenu(R.menu.draw)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_undo) undo()
            true
        }
        canvasView.onMarkAdded = { mark -> push(MarkAction.Added(currentPage, mark)) }
        canvasView.onMarkErased = { mark, index -> push(MarkAction.Removed(currentPage, mark, index)) }
        setupTools()
        startWithPdfInput(savedInstanceState)
    }

    protected abstract fun setupTools()

    override fun onDestroy() {
        renderer?.close()
        super.onDestroy()
    }

    override fun onInputPdf(file: File) {
        try {
            renderer?.close()
            renderer = PageRenderer(file)
            source = file
            toolbar.subtitle = file.name
            marksByPage.clear()
            history.clear()
            showPage(0)
        } catch (e: Exception) {
            showError(UserMessageException(getString(R.string.error_open, e.localizedMessage ?: "")))
        }
    }

    protected fun showPage(index: Int) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        currentPage = index
        canvasView.pageBitmap = r.render(index)
        canvasView.marks = marksByPage.getOrPut(index) { mutableListOf() }
        indicator.text = getString(R.string.page_indicator, index + 1, r.pageCount)
        findViewById<View>(R.id.prevPage).isEnabled = index > 0
        findViewById<View>(R.id.nextPage).isEnabled = index < r.pageCount - 1
    }

    protected val pageCount get() = renderer?.pageCount ?: 0

    private fun push(action: MarkAction) {
        history.addLast(action)
        toolbar.menu.findItem(R.id.action_undo)?.isEnabled = true
    }

    private fun undo() {
        val action = history.removeLastOrNull() ?: return
        val list = marksByPage.getOrPut(action.page) { mutableListOf() }
        when (action) {
            is MarkAction.Added -> list.remove(action.mark)
            is MarkAction.Removed -> list.add(action.index.coerceAtMost(list.size), action.mark)
        }
        if (action.page != currentPage) showPage(action.page) else canvasView.invalidate()
        toolbar.menu.findItem(R.id.action_undo)?.isEnabled = history.isNotEmpty()
    }

    protected fun hasMarks() = marksByPage.values.any { it.isNotEmpty() }

    /** Touches d'outils exclusives : met en évidence la touche active. */
    protected fun bindKeys(keys: Map<Int, InkTool?>, initial: Int) {
        val views = keys.keys.associateWith { findViewById<MaterialButton>(it) }
        fun select(id: Int) {
            canvasView.tool = keys[id]
            views.forEach { (vid, button) ->
                val on = vid == id
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.surface_sunk else android.R.color.transparent),
                )
                button.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, if (on) R.color.accent else R.color.ink))
            }
        }
        views.forEach { (id, button) -> button.setOnClickListener { select(id) } }
        select(initial)
    }
}

class AnnotateActivity : DrawToolActivity() {
    override val layoutRes = R.layout.activity_annotate
    override val defaultTool = Tool.ANNOTATE

    private val colors by lazy {
        listOf(R.color.ink_black, R.color.accent, R.color.stamp_blue, R.color.stamp_green, R.color.highlight_yellow)
            .map { ContextCompat.getColor(this, it) }
    }

    override fun setupTools() {
        bindKeys(
            mapOf(
                R.id.keyPen to InkTool.PEN,
                R.id.keyHighlighter to InkTool.HIGHLIGHTER,
                R.id.keyRect to InkTool.RECT,
                R.id.keyOval to InkTool.OVAL,
                R.id.keyLine to InkTool.LINE,
                R.id.keyArrow to InkTool.ARROW,
                R.id.keyText to InkTool.TEXT,
                R.id.keyNote to InkTool.NOTE,
                R.id.keyEraser to null,
            ),
            R.id.keyPen,
        )
        val swatches = findViewById<LinearLayout>(R.id.swatches)
        val dp = resources.displayMetrics.density
        fun selectColor(i: Int) {
            canvasView.color = colors[i]
            for (k in 0 until swatches.childCount) {
                swatches.getChildAt(k).alpha = if (k == i) 1f else 0.35f
                swatches.getChildAt(k).scaleX = if (k == i) 1.15f else 1f
                swatches.getChildAt(k).scaleY = if (k == i) 1.15f else 1f
            }
        }
        colors.forEachIndexed { i, c ->
            val v = View(this).apply {
                setBackgroundResource(R.drawable.bg_circle)
                background.mutate().setTint(c)
                contentDescription = getString(R.string.color)
                setOnClickListener { selectColor(i) }
            }
            swatches.addView(v, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
        }
        selectColor(0)
        val widths = listOf(0.0025f, 0.004f, 0.008f)
        val widthButton = findViewById<Button>(R.id.widthButton)
        var w = 1
        fun applyWidth() {
            canvasView.strokeFraction = widths[w]
            widthButton.text = getString(listOf(R.string.width_thin, R.string.width_medium, R.string.width_thick)[w])
        }
        widthButton.setOnClickListener {
            w = (w + 1) % widths.size
            applyWidth()
        }
        applyWidth()
        canvasView.onTextRequested = { tool, point -> askText(tool, point) }
        findViewById<View>(R.id.saveButton).setOnClickListener { save() }
    }

    private fun askText(tool: InkTool, point: PointF) {
        val title = if (tool == InkTool.NOTE) R.string.note_title else R.string.text_dialog_title
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.isSingleLine = false
        input.maxLines = 6
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                canvasView.addMark(Mark(tool, canvasView.color, canvasView.strokeFraction, mutableListOf(point), text))
            }
            .show()
    }

    private fun save() {
        val file = source ?: return
        if (!hasMarks()) {
            toast(R.string.no_annotations)
            return
        }
        val snapshot = marksByPage.mapValues { it.value.toList() }
        runTask(getString(R.string.saving), {
            val out = Library.newOutput(this, "${file.baseName()}_annoté.pdf", file)
            try {
                PdfOps.flattenMarks(file, out, snapshot)
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
            out
        }) { showResult(it) }
    }
}

class RedactActivity : DrawToolActivity() {
    override val layoutRes = R.layout.activity_redact
    override val defaultTool = Tool.REDACT

    override fun setupTools() {
        bindKeys(mapOf(R.id.keyRedact to InkTool.REDACT, R.id.keyEraser to null), R.id.keyRedact)
        findViewById<View>(R.id.searchButton).setOnClickListener { searchAndMark() }
        findViewById<View>(R.id.saveButton).setOnClickListener { confirmSave() }
    }

    private fun searchAndMark() {
        val file = source ?: return
        promptText(this, R.string.find_and_redact, R.string.search_hint, "") { query ->
            runTask(getString(R.string.searching), { PdfOps.findText(file, query) }) { hits ->
                var count = 0
                hits.forEach { (page, rects) ->
                    val list = marksByPage.getOrPut(page) { mutableListOf() }
                    rects.forEach { r ->
                        list += Mark(InkTool.REDACT, android.graphics.Color.BLACK, 0f, mutableListOf(PointF(r.left, r.top), PointF(r.right, r.bottom)))
                        count++
                    }
                }
                canvasView.invalidate()
                toast(if (count == 0) getString(R.string.no_match) else resources.getQuantityString(R.plurals.matches_marked, count, count))
                hits.keys.minOrNull()?.let { if (it != currentPage) showPage(it) }
            }
        }
    }

    private fun confirmSave() {
        if (!hasMarks()) {
            toast(R.string.no_redactions)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.redact_confirm_title)
            .setMessage(R.string.redact_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.redact_action) { _, _ -> save() }
            .show()
    }

    private fun save() {
        val file = source ?: return
        val boxes = marksByPage.mapValues { (_, list) ->
            list.filter { it.points.size >= 2 }.map {
                android.graphics.RectF(
                    minOf(it.points[0].x, it.points[1].x), minOf(it.points[0].y, it.points[1].y),
                    maxOf(it.points[0].x, it.points[1].x), maxOf(it.points[0].y, it.points[1].y),
                )
            }
        }
        runTask(getString(R.string.redacting), {
            val out = Library.newOutput(this, "${file.baseName()}_caviardé.pdf", file)
            try {
                PdfOps.redact(file, out, boxes)
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
            out
        }) { showResult(it, getString(R.string.redact_done)) }
    }
}
