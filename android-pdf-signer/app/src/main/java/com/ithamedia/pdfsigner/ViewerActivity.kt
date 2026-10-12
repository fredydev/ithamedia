package com.ithamedia.pdfsigner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Visionneuse PDF intégrée : pages les unes sous les autres, raccourcis vers les outils. */
class ViewerActivity : BaseToolActivity() {

    private var loader: PdfPageLoader? = null
    private lateinit var file: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_viewer, R.string.viewer)
        file = intent.paths().firstOrNull() ?: run {
            finish()
            return
        }
        toolbar.title = file.name
        toolbar.inflateMenu(R.menu.viewer)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> FileActions.share(this, listOf(file))
                R.id.action_sign -> FileActions.launchTool(this, Tool.SIGN, listOf(file))
                R.id.action_more -> FileActionsSheet.show(this, file, exporter) {
                    if (!file.exists()) finish()
                }
            }
            true
        }

        val width = resources.displayMetrics.widthPixels.coerceAtMost(1600)
        val pdf = try {
            PdfPageLoader(file, width)
        } catch (e: Exception) {
            showError(UserMessageException(getString(R.string.error_open, e.localizedMessage ?: "")))
            return
        }
        loader = pdf
        toolbar.subtitle = resources.getQuantityString(R.plurals.pages_count, pdf.pageCount, pdf.pageCount)
        findViewById<RecyclerView>(R.id.pages).apply {
            layoutManager = LinearLayoutManager(this@ViewerActivity)
            adapter = PagesAdapter(pdf)
        }
    }

    override fun onDestroy() {
        loader?.close()
        super.onDestroy()
    }

    private class PagesAdapter(private val pdf: PdfPageLoader) : RecyclerView.Adapter<PagesAdapter.Holder>() {
        class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
            var index = -1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_viewer_page, parent, false) as ImageView)

        override fun getItemCount() = pdf.pageCount

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.index = position
            val parentWidth = (holder.image.parent as? ViewGroup)?.width?.takeIf { it > 0 }
                ?: holder.image.resources.displayMetrics.widthPixels
            holder.image.layoutParams = holder.image.layoutParams.apply {
                height = (parentWidth / pdf.aspectRatios[position]).toInt()
            }
            holder.image.setImageBitmap(pdf.cached(position))
            pdf.load(position) { index, bitmap -> if (holder.index == index) holder.image.setImageBitmap(bitmap) }
        }
    }
}
