package com.ithamedia.pdfsigner

import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.math.roundToInt

/** Compresser : réduit le poids des images d'un PDF ; texte et vecteurs ne sont pas touchés. */
class CompressActivity : BaseToolActivity() {

    private var source: File? = null
    private var level = PdfTools.Level.BEST
    private lateinit var options: Map<PdfTools.Level, MaterialCardView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_compress, R.string.tool_compress)
        options = mapOf(
            PdfTools.Level.BEST to findViewById<MaterialCardView>(R.id.optionBest),
            PdfTools.Level.BALANCED to findViewById<MaterialCardView>(R.id.optionBalanced),
            PdfTools.Level.STRONG to findViewById<MaterialCardView>(R.id.optionStrong),
        )
        options.forEach { (lvl, card) -> card.setOnClickListener { select(lvl) } }
        select(PdfTools.Level.BEST)
        findViewById<View>(R.id.changeFile).setOnClickListener { pickAnotherPdf() }
        findViewById<Button>(R.id.compressButton).setOnClickListener { compress() }
        startWithPdfInput(savedInstanceState)
    }

    override fun onInputPdf(file: File) {
        source = file
        FileRow.bindIcon(findViewById(R.id.fileIcon), file)
        findViewById<TextView>(R.id.fileName).text = file.name
        val pages = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        }.getOrNull()
        val pageText = pages?.let { resources.getQuantityString(R.plurals.pages_count, it, it) + " · " } ?: ""
        findViewById<TextView>(R.id.fileMeta).text = pageText + formatSize(file.length())
        findViewById<Button>(R.id.compressButton).isEnabled = true
    }

    private fun select(selected: PdfTools.Level) {
        level = selected
        options.forEach { (lvl, card) -> card.isChecked = lvl == selected }
    }

    private fun compress() {
        val file = source ?: return
        val chosen = level
        runTask(getString(R.string.compressing), {
            val output = Library.newOutput(this, "${file.baseName()}_compressé.pdf", file)
            try {
                PdfTools.compress(file, output, chosen)
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            output
        }) { output ->
            val before = file.length()
            val after = output.length()
            if (after >= before * 0.97) {
                output.delete()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.compress_no_gain_title)
                    .setMessage(
                        if (chosen == PdfTools.Level.STRONG) R.string.compress_no_gain_body
                        else R.string.compress_no_gain_try_stronger,
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                val percent = ((1 - after.toDouble() / before) * 100).roundToInt()
                showResult(output, getString(R.string.compress_result, formatSize(before), formatSize(after), percent))
            }
        }
    }
}
