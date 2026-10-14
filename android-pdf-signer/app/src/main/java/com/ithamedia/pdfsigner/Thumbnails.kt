package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Miniature de la première page (PDF) ou de l'image, en mémoire et sur disque. */
object Thumbnails {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(file: File, width: Int) = "${file.absolutePath}|${file.lastModified()}|${file.length()}|$width"

    fun bind(view: ImageView, file: File, widthPx: Int) {
        val key = key(file, widthPx)
        view.tag = key
        memory.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val cacheDir = File(view.context.cacheDir, "thumbs").apply { mkdirs() }
        executor.execute {
            val bitmap = runCatching { load(file, widthPx, File(cacheDir, Integer.toHexString(key.hashCode()) + ".png")) }.getOrNull()
                ?: return@execute
            memory.put(key, bitmap)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    private fun load(file: File, width: Int, disk: File): Bitmap? {
        if (disk.exists()) BitmapFactory.decodeFile(disk.absolutePath)?.let { return it }
        val bitmap = when (file.kind) {
            FileKind.PDF -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            FileKind.IMAGE -> ImageUtils.decode(file, width * 2)
            else -> null
        } ?: return null
        runCatching { FileOutputStream(disk).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        return bitmap
    }

    /** Rendu d'une page en mémoire seulement (aperçus d'outils). */
    fun renderPage(file: File, index: Int, width: Int): Pair<Bitmap, Float>? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                    val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp to page.width.toFloat()
                }
            }
        }
    }.getOrNull()
}
