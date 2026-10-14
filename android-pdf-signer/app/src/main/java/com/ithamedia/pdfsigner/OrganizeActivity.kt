package com.ithamedia.pdfsigner

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Organiser les pages comme des cartes sur une table : sélection multiple, pivoter, dupliquer,
 * supprimer, insérer (pages blanches ou d'un autre PDF), extraire, avec annulation.
 * Sert aussi aux outils « Extraire des pages » et « Insérer des pages ».
 */
class OrganizeActivity : BaseToolActivity() {

    private data class PageKey(val source: Int, val index: Int)
    private object BlankKey

    private lateinit var tool: Tool
    private val sources = mutableListOf<File>()
    private val loaders = mutableListOf<PdfPageLoader>()
    private val pages = mutableListOf<GridPage>()
    private val selection = LinkedHashSet<GridPage>()
    private val history = ArrayDeque<List<Pair<Any, Int>>>()
    private lateinit var grid: RecyclerView
    private lateinit var adapter: PageGridAdapter
    private lateinit var idleBar: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var primary: Button
    private val blankThumb by lazy { blankBitmap() }

    private val pickInsert = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runTask(getString(R.string.loading), { Library.cacheCopy(this, uri) }) { insertFrom(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tool = Tool.from(intent, Tool.ORGANIZE)
        setupScreen(R.layout.activity_organize, tool.title)
        grid = findViewById(R.id.pageGrid)
        idleBar = findViewById(R.id.idleBar)
        selectionBar = findViewById(R.id.selectionBar)
        selectionCount = findViewById(R.id.selectionCount)
        primary = findViewById(R.id.primaryButton)
        grid.layoutManager = GridLayoutManager(this, gridColumns())
        adapter = PageGridAdapter(
            pages,
            loadThumb = ::loadThumb,
            onDelete = {},
            selection = selection,
            onSelectionChanged = ::updateUi,
            beforeMove = ::remember,
            onChanged = ::updateUi,
        )
        adapter.attachTo(grid)

        toolbar.inflateMenu(R.menu.organize)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_undo -> undo()
                R.id.action_select_all -> selectAll()
            }
            true
        }
        findViewById<View>(R.id.insertBlank).setOnClickListener { insertBlank() }
        findViewById<View>(R.id.insertPdf).setOnClickListener { pickInsert.launch(arrayOf("application/pdf")) }
        findViewById<View>(R.id.actionRotate).setOnClickListener { rotateSelection() }
        findViewById<View>(R.id.actionDuplicate).setOnClickListener { duplicateSelection() }
        findViewById<View>(R.id.actionDelete).setOnClickListener { deleteSelection() }
        findViewById<View>(R.id.actionExtract).setOnClickListener { extractSelection() }
        findViewById<View>(R.id.clearSelection).setOnClickListener { clearSelection() }
        primary.setOnClickListener { if (tool == Tool.EXTRACT) extractSelection() else save() }
        primary.setText(if (tool == Tool.EXTRACT) R.string.extract_action else R.string.save_pdf)
        findViewById<TextView>(R.id.organizeHint).setText(
            when (tool) {
                Tool.EXTRACT -> R.string.extract_hint
                Tool.INSERT -> R.string.insert_hint
                else -> R.string.organize_hint
            },
        )
        startWithPdfInput(savedInstanceState)
    }

    override fun onDestroy() {
        loaders.forEach { it.close() }
        super.onDestroy()
    }

    override fun onInputPdf(file: File) {
        val loader = openLoader(file) ?: return
        sources += file
        loaders += loader
        toolbar.subtitle = file.name
        repeat(loader.pageCount) { pages += GridPage(PageKey(0, it)) }
        refreshGrid()
    }

    private fun openLoader(file: File): PdfPageLoader? = try {
        PdfPageLoader(file, resources.displayMetrics.widthPixels / gridColumns())
    } catch (e: Exception) {
        showError(UserMessageException(getString(R.string.error_open, e.localizedMessage ?: "")))
        null
    }

    private fun loadThumb(page: GridPage, done: (Bitmap) -> Unit) {
        when (val key = page.key) {
            is PageKey -> loaders[key.source].load(key.index) { _, bmp -> done(bmp) }
            else -> done(blankThumb)
        }
    }

    // --- Historique --------------------------------------------------------------------------

    private fun remember() {
        history.addLast(pages.map { it.key to it.rotation })
        if (history.size > 50) history.removeFirst()
        toolbar.menu.findItem(R.id.action_undo)?.isEnabled = true
    }

    private fun undo() {
        val snapshot = history.removeLastOrNull() ?: return
        pages.clear()
        snapshot.forEach { (key, rotation) -> pages += GridPage(key, rotation) }
        selection.clear()
        refreshGrid()
    }

    // --- Actions -----------------------------------------------------------------------------

    private fun selectedPositions() = pages.indices.filter { pages[it] in selection }

    private fun insertionIndex(): Int = selectedPositions().maxOrNull()?.plus(1) ?: pages.size

    private fun insertBlank() {
        remember()
        val at = insertionIndex()
        pages.add(at, GridPage(BlankKey))
        refreshGrid()
        grid.scrollToPosition(at)
    }

    private fun insertFrom(file: File) {
        val loader = openLoader(file) ?: return
        remember()
        val sourceIndex = sources.size
        sources += file
        loaders += loader
        val at = insertionIndex()
        pages.addAll(at, List(loader.pageCount) { GridPage(PageKey(sourceIndex, it)) })
        refreshGrid()
        grid.scrollToPosition(at)
        toast(resources.getQuantityString(R.plurals.pages_inserted, loader.pageCount, loader.pageCount))
    }

    private fun rotateSelection() {
        remember()
        selection.forEach { it.rotation = (it.rotation + 90) % 360 }
        refreshGrid()
    }

    private fun duplicateSelection() {
        remember()
        val positions = selectedPositions()
        var offset = 0
        for (pos in positions) {
            val original = pages[pos + offset]
            pages.add(pos + offset + 1, GridPage(original.key, original.rotation))
            offset++
        }
        refreshGrid()
    }

    private fun deleteSelection() {
        if (selection.size >= pages.size) {
            toast(R.string.reorder_keep_one)
            return
        }
        remember()
        pages.removeAll(selection)
        selection.clear()
        refreshGrid()
    }

    private fun selectAll() {
        selection.clear()
        selection.addAll(pages)
        refreshGrid()
    }

    private fun clearSelection() {
        selection.clear()
        refreshGrid()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshGrid() {
        adapter.notifyDataSetChanged()
        updateUi()
    }

    private fun updateUi() {
        val hasSelection = selection.isNotEmpty()
        selectionBar.visibility = if (hasSelection) View.VISIBLE else View.GONE
        idleBar.visibility = if (hasSelection && tool != Tool.EXTRACT) View.GONE else View.VISIBLE
        findViewById<View>(R.id.insertRow).visibility = if (tool == Tool.EXTRACT) View.GONE else View.VISIBLE
        selectionCount.text = resources.getQuantityString(R.plurals.selected_count, selection.size, selection.size)
        primary.isEnabled = if (tool == Tool.EXTRACT) hasSelection else pages.isNotEmpty()
        toolbar.menu.findItem(R.id.action_undo)?.isEnabled = history.isNotEmpty()
        toolbar.subtitle = resources.getQuantityString(R.plurals.pages_count, pages.size, pages.size) +
            (sources.firstOrNull()?.let { " · ${it.name}" } ?: "")
    }

    // --- Enregistrer -------------------------------------------------------------------------

    private fun specs(list: List<GridPage>) = list.map { page ->
        when (val key = page.key) {
            is PageKey -> PdfOps.OrgPage.Source(key.source, key.index, page.rotation)
            else -> PdfOps.OrgPage.Blank()
        }
    }

    private fun save() {
        val first = sources.firstOrNull() ?: return
        val specs = specs(pages)
        val suffix = if (tool == Tool.INSERT) "complété" else "organisé"
        val files = sources.toList()
        runTask(getString(R.string.saving), {
            val output = Library.newOutput(this, "${first.baseName()}_$suffix.pdf", first)
            try {
                PdfOps.assemble(files, specs, output)
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            output
        }) { showResult(it) }
    }

    private fun extractSelection() {
        val first = sources.firstOrNull() ?: return
        val chosen = pages.filter { it in selection }
        if (chosen.isEmpty()) {
            toast(R.string.select_pages_first)
            return
        }
        val specs = specs(chosen)
        val files = sources.toList()
        val name = "${first.baseName()}_extrait.pdf"
        runTask(getString(R.string.saving), {
            val output = Library.newOutput(this, name, first)
            try {
                PdfOps.assemble(files, specs, output)
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            output
        }) { output ->
            if (tool == Tool.EXTRACT) {
                showResult(output, resources.getQuantityString(R.plurals.pages_count, chosen.size, chosen.size) + " · " + formatSize(output.length()))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.extract_done_title)
                    .setMessage(getString(R.string.extract_done_body, output.name))
                    .setPositiveButton(R.string.action_open) { _, _ -> FileActions.open(this, output) }
                    .setNegativeButton(R.string.continue_editing, null)
                    .show()
            }
        }
    }

    private fun blankBitmap(): Bitmap {
        val w = resources.displayMetrics.widthPixels / gridColumns()
        val h = (w * 1.414f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = ContextCompat.getColor(this@OrganizeActivity, R.color.hairline_strong)
            pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        }
        c.drawRect(w * 0.12f, h * 0.1f, w * 0.88f, h * 0.9f, paint)
        return bmp
    }

    private fun gridColumns(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 118).toInt().coerceIn(2, 6)
    }
}
