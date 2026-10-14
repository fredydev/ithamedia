package com.ithamedia.pdfsigner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Onglet « Atelier » : salutation, actions rapides, documents à reprendre et catalogue
 * complet des outils, filtrable.
 */
class ToolsFragment : Fragment(), Refreshable {

    private lateinit var catalog: LinearLayout
    private lateinit var recentList: RecyclerView
    private lateinit var recentEmpty: View
    private lateinit var noResult: View
    private val recentAdapter = RecentAdapter()

    private val home get() = activity as? HomeActivity

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Library.importUri(requireContext(), uri, Library.root(requireContext())) }.getOrNull()
            } ?: return@launch
            val activity = home ?: return@launch
            if (file.kind == FileKind.PDF) FileActions.open(activity, file)
            else FileActionsSheet.show(activity, file, activity.exporter) { refresh() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_tools, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        view.findViewById<TextView>(R.id.greeting).setText(
            when (hour) {
                in 5..11 -> R.string.greeting_morning
                in 12..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            },
        )
        view.findViewById<TextView>(R.id.today).text =
            SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date()).uppercase(Locale.FRANCE)

        view.findViewById<View>(R.id.quickScan).setOnClickListener { FileActions.launchTool(requireContext(), Tool.SCAN) }
        view.findViewById<View>(R.id.quickOpen).setOnClickListener {
            openDocument.launch(arrayOf("application/pdf", "image/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        }
        view.findViewById<View>(R.id.quickNew).setOnClickListener { showCreateSheet() }

        recentList = view.findViewById(R.id.recentList)
        recentList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        recentList.adapter = recentAdapter
        recentEmpty = view.findViewById(R.id.recentEmpty)
        view.findViewById<View>(R.id.seeAll).setOnClickListener { home?.showFiles() }

        catalog = view.findViewById(R.id.catalog)
        noResult = view.findViewById(R.id.noResult)
        val search = view.findViewById<EditText>(R.id.toolSearch)
        search.doAfterTextChanged { buildCatalog(it?.toString().orEmpty()) }
        view.findViewById<TextView>(R.id.toolCount).text = getString(R.string.tool_count, Tool.entries.size)
        buildCatalog("")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (!::recentList.isInitialized) return
        val ctx = context ?: return
        val recent = Library.recent(ctx, 12)
        recentAdapter.submit(recent)
        recentEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        recentList.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildCatalog(query: String) {
        catalog.removeAllViews()
        val inflater = layoutInflater
        var shown = 0
        for (category in Category.entries) {
            val tools = Tool.entries.filter { it.category == category && it.matches(query) }
            if (tools.isEmpty()) continue
            val header = inflater.inflate(R.layout.item_category_header, catalog, false)
            header.findViewById<TextView>(R.id.categoryNumber).text = category.number
            header.findViewById<TextView>(R.id.categoryTitle).text = category.title
            catalog.addView(header)
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_hairline_box)
                clipToOutline = true
            }
            tools.forEachIndexed { i, tool ->
                if (i > 0) box.addView(View(requireContext()).apply { setBackgroundResource(R.drawable.divider_hairline) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = (56 * resources.displayMetrics.density).toInt() })
                val row = inflater.inflate(R.layout.item_tool_row, box, false)
                row.findViewById<ImageView>(R.id.toolIcon).setImageResource(tool.icon)
                row.findViewById<TextView>(R.id.toolTitle).text = tool.title
                row.findViewById<TextView>(R.id.toolSubtitle).text = tool.subtitle
                row.setOnClickListener { FileActions.launchTool(requireContext(), tool) }
                box.addView(row)
                shown++
            }
            catalog.addView(box)
        }
        noResult.visibility = if (shown == 0) View.VISIBLE else View.GONE
    }

    private fun showCreateSheet() {
        val activity = home ?: return
        val dialog = BottomSheetDialog(activity)
        val view = layoutInflater.inflate(R.layout.sheet_file_actions, null)
        view.findViewById<View>(R.id.quickRow).visibility = View.GONE
        view.findViewById<ImageView>(R.id.sheetIcon).visibility = View.GONE
        view.findViewById<TextView>(R.id.sheetTitle).setText(R.string.create_new)
        view.findViewById<TextView>(R.id.sheetMeta).setText(R.string.create_new_sub)
        val container = view.findViewById<LinearLayout>(R.id.sheetActions)
        for (tool in Tool.entries.filter { it.category == Category.CREATE && !it.onPdf }) {
            val row = layoutInflater.inflate(R.layout.item_action, container, false)
            row.findViewById<ImageView>(R.id.actionIcon).setImageResource(tool.icon)
            row.findViewById<TextView>(R.id.actionLabel).text = tool.title
            row.findViewById<TextView>(R.id.actionSub).apply {
                text = tool.subtitle
                visibility = View.VISIBLE
            }
            row.setOnClickListener {
                dialog.dismiss()
                FileActions.launchTool(activity, tool)
            }
            container.addView(row)
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.Holder>() {
        private var items: List<File> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<File>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.recentThumb)
            val name: TextView = view.findViewById(R.id.recentName)
            val meta: TextView = view.findViewById(R.id.recentMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = items[position]
            val activity = home ?: return
            FileRow.bindIcon(holder.thumb, file)
            holder.thumb.scaleType = ImageView.ScaleType.FIT_START
            holder.name.text = file.baseName()
            holder.meta.text = SimpleDateFormat("d MMM", Locale.FRANCE).format(Date(file.lastModified())) + " · " + file.extension.uppercase(Locale.ROOT)
            holder.itemView.setOnClickListener { FileActions.open(activity, file) }
            holder.itemView.setOnLongClickListener {
                FileActionsSheet.show(activity, file, activity.exporter) { refresh() }
                true
            }
        }
    }
}
