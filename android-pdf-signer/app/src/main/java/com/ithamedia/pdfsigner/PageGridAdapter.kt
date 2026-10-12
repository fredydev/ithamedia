package com.ithamedia.pdfsigner

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/** Une vignette de la grille : [key] identifie la page source, [rotation] est ajoutée par l'utilisateur. */
class GridPage(val key: Any, var rotation: Int = 0)

/**
 * Grille de pages réorganisable : appui long pour déplacer, boutons pivoter et supprimer.
 */
class PageGridAdapter(
    val items: MutableList<GridPage>,
    private val loadThumb: (GridPage, (Bitmap) -> Unit) -> Unit,
    private val onDelete: (position: Int) -> Unit,
    private val onChanged: () -> Unit = {},
) : RecyclerView.Adapter<PageGridAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.pageThumb)
        val number: TextView = view.findViewById(R.id.pageNumber)
        val rotate: View = view.findViewById(R.id.pageRotate)
        val delete: View = view.findViewById(R.id.pageDelete)
        var bound: GridPage? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bound = item
        holder.number.text = (position + 1).toString()
        holder.thumb.setImageDrawable(null)
        loadThumb(item) { bitmap ->
            if (holder.bound === item) holder.thumb.setImageBitmap(rotated(bitmap, item.rotation))
        }
        holder.rotate.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            items[pos].rotation = (items[pos].rotation + 90) % 360
            notifyItemChanged(pos)
            onChanged()
        }
        holder.delete.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onDelete(pos)
        }
    }

    /** Glisser-déposer après un appui long, dans toutes les directions. */
    fun attachTo(recyclerView: RecyclerView) {
        recyclerView.adapter = this
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
        ) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
                items.add(b, items.removeAt(a))
                notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.06f)?.scaleY(1.06f)?.setDuration(120)?.start()
                }
            }

            @SuppressLint("NotifyDataSetChanged")
            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                // Renumérote les pages après le déplacement
                rv.post { notifyDataSetChanged() }
                onChanged()
            }
        }).attachToRecyclerView(recyclerView)
    }

    private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

/** Miniatures d'images décodées en arrière-plan et mises en cache. */
class ImageThumbLoader(private val maxSide: Int) {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(file: File, onLoaded: (Bitmap) -> Unit) {
        cache.get(file.path)?.let {
            onLoaded(it)
            return
        }
        executor.execute {
            val bitmap = ImageUtils.decode(file, maxSide) ?: return@execute
            cache.put(file.path, bitmap)
            main.post { onLoaded(bitmap) }
        }
    }

    fun shutdown() = executor.shutdownNow()
}
