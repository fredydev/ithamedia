package com.ithamedia.pdfsigner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Banc d'essai (debug) : compresse le PDF reçu aux trois niveaux, puis rend chaque page
 * de l'original et des résultats en PNG, avec un rapport des tailles, dans files/selftest.
 */
class DevCompressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        val input = File(intent.getStringExtra("path") ?: return finish())
        val out = File(filesDir, "selftest").apply {
            deleteRecursively()
            mkdirs()
        }
        thread {
            val report = StringBuilder()
            report.appendLine("original\t${input.length()}")
            render(input, out, "original")
            for (level in PdfTools.Level.entries) {
                val result = File(out, "${level.name}.pdf")
                try {
                    val stats = PdfTools.compress(input, result, level)
                    report.appendLine("${level.name}\t${result.length()}\timages=${stats.imagesOptimized}")
                    render(result, out, level.name)
                } catch (e: Throwable) {
                    report.appendLine("${level.name}\tERREUR\t$e")
                }
            }
            File(out, "report.txt").writeText(report.toString())
            File(out, "done").writeText("ok")
            runOnUiThread { finish() }
        }
    }

    private fun render(pdf: File, dir: File, prefix: String) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val w = 700
                        val h = (w.toFloat() * page.height / page.width).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(File(dir, "$prefix-p${i + 1}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                    }
                }
            }
        }
    }
}
