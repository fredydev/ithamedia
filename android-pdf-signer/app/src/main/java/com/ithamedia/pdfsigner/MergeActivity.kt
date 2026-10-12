package com.ithamedia.pdfsigner

import android.annotation.SuppressLint
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fusionner : plusieurs PDF, dans l'ordre choisi, en un seul document. */
class MergeActivity : BaseToolActivity() {

    private class Item(val file: File, var pages: Int? = null)

    private val items = mutableListOf<Item>()
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var mergeButton: Button
    private lateinit var adapter: MergeAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private val pickPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            if (items.isEmpty()) finish()
            return@registerForActivityResult
        }
        runTask(getString(R.string.loading), {
            uris.mapNotNull { runCatching { Library.cacheCopy(this, it) }.getOrNull() }
        }) { files -> add(files) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_merge, R.string.tool_merge)
        list = findViewById(R.id.mergeList)
        emptyView = findViewById(R.id.mergeEmpty)
        mergeButton = findViewById(R.id.mergeButton)

        adapter = MergeAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
        ) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                items.add(b, items.removeAt(a))
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            @SuppressLint("NotifyDataSetChanged")
            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                rv.post { adapter.notifyDataSetChanged() }
            }
        })
        touchHelper.attachToRecyclerView(list)

        findViewById<Button>(R.id.addButton).setOnClickListener { pickPdfs.launch(arrayOf("application/pdf")) }
        mergeButton.setOnClickListener { askNameAndMerge() }

        add(intent.paths().filter { it.kind == FileKind.PDF })
        if (savedInstanceState == null && items.size < 2) pickPdfs.launch(arrayOf("application/pdf"))
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun add(files: List<File>) {
        val newItems = files.map { Item(it) }
        items += newItems
        adapter.notifyDataSetChanged()
        refresh()
        // Nombre de pages lu en arrière-plan
        lifecycleScope.launch {
            for (item in newItems) {
                item.pages = withContext(Dispatchers.IO) { pageCount(item.file) }
                val index = items.indexOf(item)
                if (index >= 0) adapter.notifyItemChanged(index)
                refresh()
            }
        }
    }

    private fun pageCount(file: File): Int? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrNull()

    private fun refresh() {
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        mergeButton.isEnabled = items.size >= 2
        val total = items.sumOf { it.pages ?: 0 }
        toolbar.subtitle = if (items.isEmpty()) null else
            resources.getQuantityString(R.plurals.files_count, items.size, items.size) +
                if (total > 0) " · " + resources.getQuantityString(R.plurals.pages_count, total, total) else ""
    }

    private fun askNameAndMerge() {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH'h'mm", Locale.FRANCE).format(Date())
        promptText(this, R.string.pdf_name_title, R.string.name_hint, getString(R.string.merge_default_name, stamp)) { name ->
            val sources = items.map { it.file }
            runTask(getString(R.string.merging), {
                val output = Library.newOutput(this, "$name.pdf")
                try {
                    PdfTools.merge(sources, output)
                } catch (e: Throwable) {
                    output.delete()
                    throw e
                }
                output
            }) { showResult(it) }
        }
    }

    private inner class MergeAdapter : RecyclerView.Adapter<MergeAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val handle: View = view.findViewById(R.id.dragHandle)
            val icon: ImageView = view.findViewById(R.id.fileIcon)
            val order: TextView = view.findViewById(R.id.mergeOrder)
            val name: TextView = view.findViewById(R.id.fileName)
            val meta: TextView = view.findViewById(R.id.fileMeta)
            val remove: View = view.findViewById(R.id.removeButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_merge, parent, false))

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            FileRow.bindIcon(holder.icon, item.file)
            holder.order.text = (position + 1).toString()
            holder.name.text = item.file.name
            val pages = item.pages?.let { resources.getQuantityString(R.plurals.pages_count, it, it) + " · " } ?: ""
            holder.meta.text = pages + formatSize(item.file.length())
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
            holder.remove.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                items.removeAt(pos)
                notifyItemRemoved(pos)
                notifyItemRangeChanged(pos, items.size - pos)
                refresh()
            }
        }
    }
}
