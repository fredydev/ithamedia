package com.ithamedia.pdfsigner

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/** Word → PDF : conversion d'un .docx sur l'appareil, sans envoi en ligne. */
class WordToPdfActivity : BaseToolActivity() {

    private var source: File? = null
    private var hasInput = false

    private val pickDocx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (!hasInput) finish()
            return@registerForActivityResult
        }
        runTask(getString(R.string.loading), { Library.cacheCopy(this, uri) }) { setSource(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_word, R.string.tool_word)
        findViewById<View>(R.id.changeFile).setOnClickListener { pick() }
        findViewById<Button>(R.id.convertButton).setOnClickListener { convert() }

        val file = intent.paths().firstOrNull { it.kind == FileKind.WORD }
        when {
            file != null -> setSource(file)
            savedInstanceState == null -> pick()
            else -> finish()
        }
    }

    private fun pick() = pickDocx.launch(
        arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
        ),
    )

    private fun setSource(file: File) {
        hasInput = true
        source = file
        FileRow.bindIcon(findViewById(R.id.fileIcon), file)
        findViewById<TextView>(R.id.fileName).text = file.name
        findViewById<TextView>(R.id.fileMeta).text = formatSize(file.length())
        findViewById<Button>(R.id.convertButton).isEnabled = true
    }

    private fun convert() {
        val file = source ?: return
        runTask(getString(R.string.converting), {
            val output = Library.newOutput(this, "${file.baseName()}.pdf", file)
            try {
                DocxConverter.convert(file, output)
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            output
        }) { showResult(it) }
    }
}
