package com.ithamedia.pdfsigner

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import java.io.File

/**
 * Visionneuse : défilement continu, recherche dans le texte, signets, aller à la page,
 * mode lecture de nuit, et accès à tous les outils pour ce document.
 */
class ViewerActivity : BaseToolActivity() {

    private var loader: PdfPageLoader? = null
    private lateinit var file: File
    private lateinit var list: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var pageChip: TextView
    private var adapter: PagesAdapter? = null
    private var night = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_viewer, R.string.viewer)
        file = intent.paths().firstOrNull() ?: run {
            finish()
            return
        }
        toolbar.title = file.baseName()
        toolbar.inflateMenu(R.menu.viewer)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> openSearch()
                R.id.action_outline -> openOutline()
                R.id.action_night -> toggleNight()
                R.id.action_tools -> ToolsSheet.show(this, file)
                R.id.action_share -> FileActions.share(this, listOf(file))
                R.id.action_more -> FileActionsSheet.show(this, file, exporter) { if (!file.exists()) finish() }
            }
            true
        }
        findViewById<View>(R.id.toolsFab).setOnClickListener { ToolsSheet.show(this, file) }
        pageChip = findViewById(R.id.pageChip)
        pageChip.setOnClickListener { goToPage() }

        val width = resources.displayMetrics.widthPixels.coerceAtMost(1600)
        val pdf = try {
            PdfPageLoader(file, width)
        } catch (e: Exception) {
            if (PdfOps.isEncrypted(file)) {
                showError(UserMessageException(getString(R.string.viewer_locked)))
            } else {
                showError(UserMessageException(getString(R.string.error_open, e.localizedMessage ?: "")))
            }
            return
        }
        loader = pdf
        toolbar.subtitle = resources.getQuantityString(R.plurals.pages_count, pdf.pageCount, pdf.pageCount) + " · " + formatSize(file.length())
        list = findViewById(R.id.pages)
        layoutManager = LinearLayoutManager(this)
        list.layoutManager = layoutManager
        adapter = PagesAdapter(pdf)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateChip()
        })
        list.post { updateChip() }
    }

    override fun onDestroy() {
        loader?.close()
        super.onDestroy()
    }

    private fun updateChip() {
        val total = loader?.pageCount ?: return
        val first = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        pageChip.text = getString(R.string.page_of, first + 1, total)
    }

    private fun scrollTo(page: Int) {
        layoutManager.scrollToPositionWithOffset(page, 0)
        list.post { updateChip() }
    }

    private fun goToPage() {
        val total = loader?.pageCount ?: return
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<TextInputEditText>(R.id.input)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputLayout).hint = getString(R.string.page_number_hint, total)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { scrollTo((it - 1).coerceIn(0, total - 1)) }
            }
            .show()
    }

    private fun toggleNight() {
        night = !night
        adapter?.night = night
        @Suppress("NotifyDataSetChanged")
        adapter?.notifyDataSetChanged()
        findViewById<View>(R.id.viewerRoot).setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, if (night) R.color.night_bg else R.color.page_background),
        )
        toast(if (night) R.string.night_on else R.string.night_off)
    }

    private fun openSearch() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_search, null)
        val input = view.findViewById<TextInputEditText>(R.id.searchInput)
        val results = view.findViewById<LinearLayout>(R.id.searchResults)
        val status = view.findViewById<TextView>(R.id.searchStatus)
        fun run() {
            val query = input.text.toString().trim()
            if (query.isEmpty()) return
            status.setText(R.string.searching)
            results.removeAllViews()
            runTask(getString(R.string.searching), { PdfOps.search(file, query) }) { hits ->
                status.text = if (hits.isEmpty()) getString(R.string.no_match)
                else resources.getQuantityString(R.plurals.matches_count, hits.size, hits.size)
                for (hit in hits) {
                    val row = layoutInflater.inflate(R.layout.item_action, results, false)
                    row.findViewById<ImageView>(R.id.actionIcon).visibility = View.GONE
                    row.findViewById<TextView>(R.id.actionLabel).text = getString(R.string.page_label, hit.page + 1)
                    row.findViewById<TextView>(R.id.actionSub).apply {
                        text = hit.snippet
                        visibility = View.VISIBLE
                    }
                    row.setOnClickListener {
                        sheet.dismiss()
                        scrollTo(hit.page)
                    }
                    results.addView(row)
                }
            }
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                run()
                true
            } else {
                false
            }
        }
        view.findViewById<View>(R.id.searchGo).setOnClickListener { run() }
        sheet.setContentView(view)
        sheet.show()
        input.requestFocus()
    }

    private fun openOutline() {
        runTask(getString(R.string.loading), { PdfOps.outline(file) }) { items ->
            if (items.isEmpty()) {
                toast(R.string.no_outline)
                return@runTask
            }
            val sheet = BottomSheetDialog(this)
            val view = LayoutInflater.from(this).inflate(R.layout.sheet_file_actions, null)
            FileRow.bindIcon(view.findViewById(R.id.sheetIcon), file)
            view.findViewById<TextView>(R.id.sheetTitle).setText(R.string.outline)
            view.findViewById<TextView>(R.id.sheetMeta).text = resources.getQuantityString(R.plurals.bookmarks_count, items.size, items.size)
            view.findViewById<View>(R.id.quickRow).visibility = View.GONE
            val container = view.findViewById<LinearLayout>(R.id.sheetActions)
            val dp = resources.displayMetrics.density
            for (item in items) {
                val row = layoutInflater.inflate(R.layout.item_action, container, false)
                row.findViewById<ImageView>(R.id.actionIcon).setImageResource(R.drawable.ic_bookmark)
                row.setPadding(((24 + item.depth * 18) * dp).toInt(), row.paddingTop, row.paddingRight, row.paddingBottom)
                row.findViewById<TextView>(R.id.actionLabel).text = item.title
                item.page?.let { p ->
                    row.findViewById<TextView>(R.id.actionSub).apply {
                        text = getString(R.string.page_label, p + 1)
                        visibility = View.VISIBLE
                    }
                    row.setOnClickListener {
                        sheet.dismiss()
                        scrollTo(p)
                    }
                }
                container.addView(row)
            }
            sheet.setContentView(view)
            sheet.show()
        }
    }

    private class PagesAdapter(private val pdf: PdfPageLoader) : RecyclerView.Adapter<PagesAdapter.Holder>() {
        var night = false
        private val invert = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.87f, 0f, 0f, 0f, 235f,
                    0f, -0.87f, 0f, 0f, 232f,
                    0f, 0f, -0.87f, 0f, 224f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
            var index = -1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_viewer_page, parent, false) as ImageView)

        override fun getItemCount() = pdf.pageCount

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.index = position
            val parentWidth = (holder.image.parent as? ViewGroup)?.let { it.width - it.paddingLeft - it.paddingRight }?.takeIf { it > 0 }
                ?: holder.image.resources.displayMetrics.widthPixels
            holder.image.layoutParams = holder.image.layoutParams.apply {
                height = (parentWidth / pdf.aspectRatios[position]).toInt()
            }
            holder.image.colorFilter = if (night) invert else null
            holder.image.setImageBitmap(pdf.cached(position))
            pdf.load(position) { index, bitmap -> if (holder.index == index) holder.image.setImageBitmap(bitmap) }
        }
    }
}
