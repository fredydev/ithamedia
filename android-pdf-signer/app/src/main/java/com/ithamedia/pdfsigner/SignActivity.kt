package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/** Signer : signature manuscrite et textes (date, lieu…) posés sur les pages. */
class SignActivity : BaseToolActivity() {

    private companion object {
        const val KEY_PLACE = "last_place"
    }

    private lateinit var pageView: PdfPageView
    private lateinit var pageIndicator: TextView
    private lateinit var prevPage: Button
    private lateinit var nextPage: Button

    private var pdfFile: File? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentPage = 0
    private var pageWidthPts = 0
    private val placementsByPage = HashMap<Int, MutableList<Placement>>()

    private val savedSignatureFile by lazy { File(filesDir, "signature.png") }
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_sign, R.string.tool_sign)

        pageView = findViewById(R.id.pageView)
        pageIndicator = findViewById(R.id.pageIndicator)
        prevPage = findViewById(R.id.prevPage)
        nextPage = findViewById(R.id.nextPage)

        findViewById<Button>(R.id.signButton).setOnClickListener { chooseSignature() }
        findViewById<Button>(R.id.textButton).setOnClickListener { addText() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        prevPage.setOnClickListener { showPage(currentPage - 1) }
        nextPage.setOnClickListener { showPage(currentPage + 1) }
        pageView.onPlacementRemoved = { toast(R.string.signature_removed) }

        startWithPdfInput(savedInstanceState)
    }

    override fun onDestroy() {
        closeRenderer()
        super.onDestroy()
    }

    override fun onInputPdf(file: File) {
        try {
            closeRenderer()
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
            fileDescriptor = fd
            pdfFile = file
            placementsByPage.clear()
            toolbar.subtitle = file.name
            showPage(0)
        } catch (e: Exception) {
            showError(UserMessageException(getString(R.string.error_open, e.localizedMessage ?: e.javaClass.simpleName)))
        }
    }

    private fun closeRenderer() {
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
    }

    private fun showPage(index: Int) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        currentPage = index

        val page = r.openPage(index)
        pageWidthPts = page.width
        // Rendu à ~2x la taille en points, plafonné pour éviter les dépassements mémoire
        val scale = min(2f, 2400f / max(page.width, page.height))
        val bitmap = Bitmap.createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        pageView.pageBitmap = bitmap
        pageView.placements = placementsByPage.getOrPut(index) { mutableListOf() }

        pageIndicator.text = getString(R.string.page_indicator, index + 1, r.pageCount)
        prevPage.isEnabled = index > 0
        nextPage.isEnabled = index < r.pageCount - 1
    }

    // --- Signature ---------------------------------------------------------------------------

    private fun chooseSignature() {
        val saved = loadSavedSignature()
        if (saved == null) {
            drawSignature()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_signature)
            .setItems(
                arrayOf(getString(R.string.use_saved_signature), getString(R.string.draw_new_signature)),
            ) { _, which ->
                if (which == 0) placeSignature(saved) else drawSignature()
            }
            .show()
    }

    private fun drawSignature() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_signature, null)
        val pad = content.findViewById<SignaturePadView>(R.id.signaturePad)
        val remember = content.findViewById<MaterialCheckBox>(R.id.rememberSignature)
        content.findViewById<MaterialButtonToggleGroup>(R.id.colorGroup)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val color = if (checkedId == R.id.colorBlue) R.color.ink_blue else R.color.ink_black
                pad.inkColor = ContextCompat.getColor(this, color)
            }
        pad.inkColor = ContextCompat.getColor(this, R.color.ink_black)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.draw_signature)
            .setView(content)
            .setPositiveButton(R.string.validate, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear, null)
            .create()

        dialog.setOnShowListener {
            // Remplace les écouteurs par défaut pour que "Effacer" ne ferme pas la boîte de dialogue
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val bitmap = pad.exportBitmap()
                if (bitmap == null) {
                    toast(getString(R.string.signature_empty))
                    return@setOnClickListener
                }
                if (remember.isChecked) saveSignature(bitmap)
                dialog.dismiss()
                placeSignature(bitmap)
            }
        }
        dialog.show()
    }

    private fun placeSignature(signature: Bitmap) {
        pageView.addPlacement(signature)
        toast(getString(R.string.hint_place))
    }

    private fun loadSavedSignature(): Bitmap? =
        if (savedSignatureFile.exists()) BitmapFactory.decodeFile(savedSignatureFile.absolutePath) else null

    private fun saveSignature(bitmap: Bitmap) {
        try {
            FileOutputStream(savedSignatureFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
            // La mémorisation est facultative
        }
    }

    // --- Texte (date, lieu…) ------------------------------------------------------------------

    private fun addText() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_text, null)
        val textInput = content.findViewById<TextInputEditText>(R.id.textInput)
        val placeLayout = content.findViewById<View>(R.id.placeLayout)
        val placeInput = content.findViewById<TextInputEditText>(R.id.placeInput)
        val colorGroup = content.findViewById<MaterialButtonToggleGroup>(R.id.colorGroup)
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

        fun placeDateText() = getString(R.string.place_date_format, placeInput.text.toString().trim(), today)
        fun setText(value: String) {
            textInput.setText(value)
            textInput.setSelection(value.length)
        }

        content.findViewById<View>(R.id.presetDate).setOnClickListener {
            placeLayout.visibility = View.GONE
            setText(today)
        }
        content.findViewById<View>(R.id.presetReadApproved).setOnClickListener {
            placeLayout.visibility = View.GONE
            setText(getString(R.string.read_approved_text))
        }
        content.findViewById<View>(R.id.presetPlaceDate).setOnClickListener {
            placeLayout.visibility = View.VISIBLE
            if (placeInput.text.isNullOrEmpty()) placeInput.setText(prefs.getString(KEY_PLACE, ""))
            setText(placeDateText())
            if (placeInput.text.isNullOrEmpty()) placeInput.requestFocus()
        }
        // Met à jour « Fait à …, le … » pendant la saisie de la ville
        placeInput.doAfterTextChanged {
            if (placeLayout.visibility == View.VISIBLE) setText(placeDateText())
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_dialog_title)
            .setView(content)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = textInput.text.toString().trim()
                if (text.isEmpty()) {
                    toast(getString(R.string.text_empty))
                    return@setOnClickListener
                }
                if (placeLayout.visibility == View.VISIBLE) {
                    prefs.edit().putString(KEY_PLACE, placeInput.text.toString().trim()).apply()
                }
                val color = if (colorGroup.checkedButtonId == R.id.colorBlue) R.color.ink_blue else R.color.ink_black
                val bitmap = TextStamp.render(text, ContextCompat.getColor(this, color))
                dialog.dismiss()
                // Taille initiale ≈ corps 12 pt sur la page (le rendu est à 72 px par corps de police)
                val widthFraction = if (pageWidthPts > 0) bitmap.width * (12f / 72f) / pageWidthPts else 0.35f
                pageView.addPlacement(bitmap, widthFraction)
                toast(getString(R.string.hint_place))
            }
        }
        dialog.show()
    }

    // --- Enregistrement ----------------------------------------------------------------------

    private fun save() {
        val source = pdfFile ?: return
        if (placementsByPage.values.all { it.isEmpty() }) {
            toast(R.string.no_signature_placed)
            return
        }
        val snapshot = placementsByPage.mapValues { (_, list) -> list.toList() }
        runTask(getString(R.string.saving), {
            val output = Library.newOutput(this, "${source.baseName()}_signé.pdf", source)
            try {
                FileOutputStream(output).use { PdfSigner.sign(source, it, snapshot) }
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            output
        }) { output -> showResult(output) }
    }
}
