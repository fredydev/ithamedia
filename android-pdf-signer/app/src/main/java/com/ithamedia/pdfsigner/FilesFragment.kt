package com.ithamedia.pdfsigner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Onglet « Mes fichiers » : navigation dans les dossiers, import, actions sur chaque fichier. */
class FilesFragment : Fragment(), Refreshable {

    private lateinit var currentDir: File
    private lateinit var pathText: TextView
    private lateinit var countText: TextView
    private lateinit var emptyView: View
    private val adapter = FileAdapter(
        onClick = { file -> if (file.isDirectory) navigate(file) else FileActions.open(requireContext(), file) },
        onMore = { file -> home?.let { FileActionsSheet.show(it, file, it.exporter) { refresh() } } },
    )

    private val home get() = activity as? HomeActivity

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            currentDir.parentFile?.let { navigate(it) }
        }
    }

    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val dir = currentDir
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                uris.count { runCatching { Library.importUri(requireContext(), it, dir) }.isSuccess }
            }
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(R.plurals.imported_count, ok, ok),
                Toast.LENGTH_SHORT,
            ).show()
            refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_files, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        currentDir = Library.root(requireContext())
        pathText = view.findViewById(R.id.pathText)
        countText = view.findViewById(R.id.countText)
        emptyView = view.findViewById(R.id.filesEmpty)
        view.findViewById<RecyclerView>(R.id.fileList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FilesFragment.adapter
        }
        view.findViewById<View>(R.id.addFab).setOnClickListener { showAddMenu() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateBackCallback()
    }

    fun openFolder(dir: File) {
        if (::currentDir.isInitialized && dir.isDirectory && Library.isInLibrary(requireContext(), dir)) navigate(dir)
    }

    fun openRoot() {
        if (::currentDir.isInitialized) navigate(Library.root(requireContext()))
    }

    private fun navigate(dir: File) {
        currentDir = dir
        refresh()
    }

    override fun refresh() {
        if (!::currentDir.isInitialized || context == null) return
        if (!currentDir.exists()) currentDir = Library.root(requireContext())
        val items = Library.list(currentDir)
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pathText.text = FileActionsSheet.folderLabel(requireContext(), Library.root(requireContext()), currentDir).uppercase(java.util.Locale.FRANCE)
        countText.text = resources.getQuantityString(R.plurals.items_count, items.size, items.size)
        updateBackCallback()
    }

    private fun updateBackCallback() {
        if (!::currentDir.isInitialized) return
        backCallback.isEnabled = !isHidden && currentDir != Library.root(requireContext())
    }

    private fun showAddMenu() {
        val activity = home ?: return
        val dialog = BottomSheetDialog(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.sheet_padding), 0, resources.getDimensionPixelSize(R.dimen.sheet_padding))
        }
        fun add(icon: Int, label: Int, action: () -> Unit) {
            val row = layoutInflater.inflate(R.layout.item_action, list, false)
            row.findViewById<ImageView>(R.id.actionIcon).setImageResource(icon)
            row.findViewById<TextView>(R.id.actionLabel).setText(label)
            row.setOnClickListener {
                dialog.dismiss()
                action()
            }
            list.addView(row)
        }
        add(R.drawable.ic_new_folder, R.string.new_folder) {
            promptText(activity, R.string.new_folder, R.string.folder_name_hint, "") { name ->
                val dir = File(currentDir, Library.sanitize(name))
                if (dir.exists()) Toast.makeText(activity, R.string.name_taken, Toast.LENGTH_SHORT).show()
                else if (dir.mkdirs()) refresh()
            }
        }
        add(R.drawable.ic_download, R.string.import_files) { importFiles.launch(arrayOf("*/*")) }
        add(R.drawable.ic_scan, R.string.tool_scan) { FileActions.launchTool(activity, Tool.SCAN) }
        dialog.setContentView(list)
        dialog.show()
    }
}

class FileAdapter(
    private val onClick: (File) -> Unit,
    private val onMore: (File) -> Unit,
) : RecyclerView.Adapter<FileAdapter.Holder>() {

    private var items: List<File> = emptyList()

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.fileIcon)
        val name: TextView = view.findViewById(R.id.fileName)
        val meta: TextView = view.findViewById(R.id.fileMeta)
        val more: View = view.findViewById(R.id.fileMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = items[position]
        FileRow.bindIcon(holder.icon, file)
        holder.name.text = file.name
        holder.meta.text = FileRow.meta(holder.itemView.context, file)
        holder.itemView.setOnClickListener { onClick(file) }
        holder.more.setOnClickListener { onMore(file) }
    }
}
