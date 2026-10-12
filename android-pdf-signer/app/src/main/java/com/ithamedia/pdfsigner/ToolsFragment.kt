package com.ithamedia.pdfsigner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/** Onglet « Outils » : grande carte Scanner, grille d'outils et fichiers récents. */
class ToolsFragment : Fragment(), Refreshable {

    private lateinit var recentList: LinearLayout
    private lateinit var recentEmpty: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_tools, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.heroScan).setOnClickListener {
            FileActions.launchTool(requireContext(), Tool.SCAN)
        }

        val grid = view.findViewById<GridLayout>(R.id.toolsGrid)
        val gap = resources.getDimensionPixelSize(R.dimen.grid_gap)
        Tool.entries.filter { it != Tool.SCAN }.forEach { tool ->
            val card = layoutInflater.inflate(R.layout.item_tool, grid, false)
            tintedIcon(card.findViewById(R.id.toolIcon), tool.icon, ContextCompat.getColor(requireContext(), tool.color))
            card.findViewById<TextView>(R.id.toolTitle).setText(tool.title)
            card.findViewById<TextView>(R.id.toolSubtitle).setText(tool.subtitle)
            card.setOnClickListener { FileActions.launchTool(requireContext(), tool) }
            card.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            }
            grid.addView(card)
        }

        recentList = view.findViewById(R.id.recentList)
        recentEmpty = view.findViewById(R.id.recentEmpty)
        view.findViewById<View>(R.id.seeAll).setOnClickListener { (activity as? HomeActivity)?.showFiles() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (!::recentList.isInitialized) return
        val home = activity as? HomeActivity ?: return
        recentList.removeAllViews()
        val recent = Library.recent(home, 5)
        recentEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        recent.forEach { file ->
            val row = layoutInflater.inflate(R.layout.item_file, recentList, false)
            FileRow.bindIcon(row.findViewById<ImageView>(R.id.fileIcon), file)
            row.findViewById<TextView>(R.id.fileName).text = file.name
            row.findViewById<TextView>(R.id.fileMeta).text = FileRow.meta(home, file)
            row.setOnClickListener { FileActions.open(home, file) }
            row.findViewById<View>(R.id.fileMore).setOnClickListener {
                FileActionsSheet.show(home, file, home.exporter) { refresh() }
            }
            recentList.addView(row)
        }
    }
}
