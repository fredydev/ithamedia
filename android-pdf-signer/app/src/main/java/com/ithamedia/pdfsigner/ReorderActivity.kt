package com.ithamedia.pdfsigner

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import java.io.File

/** Réorganiser : déplacer, pivoter et supprimer les pages d'un PDF. */
class ReorderActivity : BaseToolActivity() {

    private var source: File? = null
    private var loader: PdfPageLoader? = null
    private lateinit var grid: RecyclerView
    private lateinit var adapter: PageGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_reorder, R.string.tool_reorder)
        grid = findViewById(R.id.pageGrid)
        grid.layoutManager = GridLayoutManager(this, gridColumns())
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        startWithPdfInput(savedInstanceState)
    }

    override fun onDestroy() {
        loader?.close()
        super.onDestroy()
    }

    override fun onInputPdf(file: File) {
        val pdf = try {
            PdfPageLoader(file, resources.displayMetrics.widthPixels / gridColumns())
        } catch (e: Exception) {
            showError(UserMessageException(getString(R.string.error_open, e.localizedMessage ?: "")))
            return
        }
        loader?.close()
        loader = pdf
        source = file
        toolbar.subtitle = file.name
        val items = MutableList(pdf.pageCount) { GridPage(it) }
        adapter = PageGridAdapter(
            items,
            loadThumb = { page, done -> pdf.load(page.key as Int) { _, bmp -> done(bmp) } },
            onDelete = ::deletePage,
            onChanged = ::updateCount,
        )
        adapter.attachTo(grid)
        updateCount()
    }

    private fun deletePage(position: Int) {
        val items = adapter.items
        if (items.size <= 1) {
            toast(R.string.reorder_keep_one)
            return
        }
        val removed = items.removeAt(position)
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, items.size - position)
        updateCount()
        Snackbar.make(grid, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                val at = position.coerceAtMost(items.size)
                items.add(at, removed)
                adapter.notifyItemInserted(at)
                adapter.notifyItemRangeChanged(at, items.size - at)
                updateCount()
            }
            .show()
    }

    private fun updateCount() {
        val count = adapter.items.size
        findViewById<View>(R.id.reorderHint).visibility = View.VISIBLE
        toolbar.subtitle = resources.getQuantityString(R.plurals.pages_count, count, count) +
            (source?.let { " · ${it.name}" } ?: "")
    }

    private fun save() {
        val file = source ?: return
        val specs = adapter.items.map { PdfTools.PageSpec(it.key as Int, it.rotation) }
        runTask(getString(R.string.saving), {
            val output = Library.newOutput(this, "${file.baseName()}_réorganisé.pdf", file)
            try {
                PdfTools.reorder(file, output, specs)
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            output
        }) { showResult(it) }
    }

    private fun gridColumns(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 120).toInt().coerceIn(2, 6)
    }
}
