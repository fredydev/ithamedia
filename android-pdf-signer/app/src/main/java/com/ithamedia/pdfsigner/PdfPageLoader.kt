package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Rendu asynchrone et mis en cache des pages d'un PDF (miniatures, visionneuse).
 * PdfRenderer n'étant pas thread-safe, tout le rendu passe par un seul fil dédié.
 */
class PdfPageLoader(file: File, private val targetWidth: Int) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    private val cache = object : LruCache<Int, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Rapport largeur/hauteur de chaque page, lu une fois à l'ouverture. */
    val aspectRatios: FloatArray = FloatArray(pageCount) { i ->
        renderer.openPage(i).use { it.width.toFloat() / it.height }
    }

    fun cached(index: Int): Bitmap? = cache.get(index)

    fun load(index: Int, onLoaded: (Int, Bitmap) -> Unit) {
        cache.get(index)?.let {
            onLoaded(index, it)
            return
        }
        executor.execute {
            if (closed) return@execute
            val bitmap = cache.get(index) ?: runCatching { render(index) }.getOrNull() ?: return@execute
            cache.put(index, bitmap)
            main.post { if (!closed) onLoaded(index, bitmap) }
        }
    }

    private fun render(index: Int): Bitmap = renderer.openPage(index).use { page ->
        val width = targetWidth.coerceAtLeast(1)
        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    fun close() {
        closed = true
        executor.execute {
            renderer.close()
            fd.close()
        }
        executor.shutdown()
    }

    private companion object {
        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).toInt()
    }
}
