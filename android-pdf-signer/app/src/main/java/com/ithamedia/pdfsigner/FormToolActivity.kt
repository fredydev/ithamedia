package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ce que produit un outil. */
sealed class ToolOutput {
    class Single(val file: File, val summary: String? = null) : ToolOutput()
    class Many(val folder: File, val files: List<File>, val summary: String? = null) : ToolOutput()
    class Info(val title: String, val message: String) : ToolOutput()
}

/**
 * Écran d'outil « formulaire » : un fichier en entrée, des réglages, un aperçu facultatif
 * et un bouton d'action. Les sous-classes décrivent les réglages et le traitement.
 */
abstract class FormToolActivity : BaseToolActivity() {

    protected abstract val defaultTool: Tool
    protected lateinit var tool: Tool
    protected open val needsInput: Boolean = true
    protected open val wantsPreview: Boolean = false
    protected open val primaryLabel: String get() = tool.title
    protected open val workingLabel: String get() = getString(R.string.working)

    protected var source: File? = null
    protected var pageCount = 0
    protected lateinit var options: Options
    private lateinit var optionsHost: LinearLayout
    private lateinit var primary: Button
    private lateinit var preview: ImageView
    private var previewBase: Bitmap? = null
    /** Largeur en points de la page d'aperçu, pour mettre les tailles à l'échelle. */
    protected var previewPageWidthPts = 595f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tool = Tool.from(intent, defaultTool)
        setupScreen(R.layout.activity_form_tool, tool.title)
        findViewById<TextView>(R.id.intro).text = tool.subtitle
        optionsHost = findViewById(R.id.options)
        preview = findViewById(R.id.preview)
        primary = findViewById(R.id.primaryButton)
        primary.text = primaryLabel
        primary.setOnClickListener { submit() }
        if (needsInput) {
            findViewById<View>(R.id.changeFile).setOnClickListener { pickAnotherPdf() }
            primary.isEnabled = false
            startWithPdfInput(savedInstanceState)
        } else {
            findViewById<View>(R.id.fileCard).visibility = View.GONE
            buildForm()
        }
    }

    override fun onInputPdf(file: File) {
        source = file
        findViewById<TextView>(R.id.fileName).text = file.name
        FileRow.bindIcon(findViewById(R.id.fileThumb), file)
        pageCount = PdfOps.pageCount(file) ?: 0
        val pages = if (pageCount > 0) resources.getQuantityString(R.plurals.pages_count, pageCount, pageCount) + " · " else ""
        findViewById<TextView>(R.id.fileMeta).text = pages + formatSize(file.length())
        buildForm()
        primary.isEnabled = true
        if (wantsPreview) loadPreview(file)
        onSourceReady(file)
    }

    protected open fun onSourceReady(file: File) {}

    protected fun setPrimaryVisible(visible: Boolean) {
        (primary.parent as View).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun buildForm() {
        optionsHost.removeAllViews()
        options = Options(optionsHost) { refreshPreview() }
        buildOptions(options)
    }

    protected abstract fun buildOptions(o: Options)

    /**
     * Lit les réglages (fil principal) et renvoie le traitement à exécuter en arrière-plan,
     * ou null si un réglage est invalide (la sous-classe a déjà prévenu l'utilisateur).
     */
    protected abstract fun job(source: File?): (() -> ToolOutput)?

    private fun submit() {
        if (needsInput && source == null) return
        val work = job(source) ?: return
        runTask(workingLabel, { work() }) { deliver(it) }
    }

    // --- Aperçu ------------------------------------------------------------------------------

    protected open fun drawPreview(canvas: Canvas, width: Int, height: Int) {}

    private fun loadPreview(file: File) {
        val width = (resources.displayMetrics.widthPixels * 0.8f).toInt().coerceIn(400, 1100)
        lifecycleScope.launch {
            val rendered = withContext(Dispatchers.IO) { Thumbnails.renderPage(file, 0, width) } ?: return@launch
            previewBase = rendered.first
            previewPageWidthPts = rendered.second
            refreshPreview()
        }
    }

    protected fun refreshPreview() {
        val base = previewBase ?: return
        val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
        runCatching { drawPreview(Canvas(bmp), bmp.width, bmp.height) }
        preview.setImageBitmap(bmp)
        preview.visibility = View.VISIBLE
        findViewById<View>(R.id.previewLabel).visibility = View.VISIBLE
    }

    /** Nouveau fichier de sortie à côté de la source (ou à la racine de Mes fichiers). */
    protected fun output(name: String): File = Library.newOutput(this, name, source)

    protected fun outputFolder(name: String): File {
        val parent = source?.parentFile?.takeIf { Library.isInLibrary(this, it) } ?: Library.root(this)
        return Library.uniqueFile(parent, Library.sanitize(name)).apply { mkdirs() }
    }

    /** Exécute [block] en supprimant la sortie en cas d'échec. */
    protected inline fun produce(file: File, block: (File) -> Unit): File {
        try {
            block(file)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }
}
