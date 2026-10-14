package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Rendu d'une page à la fois pour les écrans d'édition (fil principal, pages courtes). */
class PageRenderer(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    val pageCount get() = renderer.pageCount
    var lastWidthPts = 0
        private set

    fun render(index: Int, maxSide: Float = 2400f): Bitmap = renderer.openPage(index).use { page ->
        lastWidthPts = page.width
        val scale = min(2f, maxSide / max(page.width, page.height))
        Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}
