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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Signer : signature manuscrite et textes (date, lieu…) posés sur les pages. */
class SignActivity : BaseToolActivity() {

    private companion object {
        const val KEY_PLACE = "last_place"
        const val KEY_BLUE = "ink_blue"
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

    private lateinit var tool: Tool
    private var startActionDone = false
    private val savedSignatureFile by lazy { File(filesDir, "signature.png") }
    private val savedInitialsFile by lazy { File(filesDir, "initials.png") }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        runTask(getString(R.string.loading), {
            val copy = Library.cacheCopy(this, uri)
            ImageUtils.decode(copy, 1600).also { copy.delete() }
        }) { bmp ->
            if (bmp == null) toast(R.string.image_unreadable) else placeSignature(bmp, 0.3f)
        }
    }
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tool = Tool.from(intent, Tool.SIGN)
        setupScreen(R.layout.activity_sign, tool.title)

        pageView = findViewById(R.id.pageView)
        pageIndicator = findViewById(R.id.pageIndicator)
        prevPage = findViewById(R.id.prevPage)
        nextPage = findViewById(R.id.nextPage)

        findViewById<View>(R.id.toolSignature).setOnClickListener { chooseSignature(initials = false) }
        findViewById<View>(R.id.toolInitials).setOnClickListener { chooseSignature(initials = true) }
        findViewById<View>(R.id.toolText).setOnClickListener { addText() }
        findViewById<View>(R.id.toolDate).setOnClickListener { placeText(today()) }
        findViewById<View>(R.id.toolCheck).setOnClickListener { placeGlyph(Glyphs.Kind.CHECK) }
        findViewById<View>(R.id.toolCross).setOnClickListener { placeGlyph(Glyphs.Kind.CROSS) }
        findViewById<View>(R.id.toolDot).setOnClickListener { placeGlyph(Glyphs.Kind.DOT) }
        findViewById<View>(R.id.toolLine).setOnClickListener { placeGlyph(Glyphs.Kind.LINE) }
        findViewById<View>(R.id.toolStamp).setOnClickListener { showStamps() }
        findViewById<View>(R.id.toolImage).setOnClickListener { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<View>(R.id.inkToggle).setOnClickListener { toggleInk() }
        updateInkSwatch()
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
            if (!startActionDone) {
                startActionDone = true
                when (tool) {
                    Tool.STAMP -> showStamps()
                    Tool.ADD_IMAGE -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    else -> Unit
                }
            }
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

    private fun chooseSignature(initials: Boolean) {
        val saved = loadSaved(initials)
        if (saved == null) {
            drawSignature(initials)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (initials) R.string.initials else R.string.choose_signature)
            .setItems(
                arrayOf(
                    getString(if (initials) R.string.use_saved_initials else R.string.use_saved_signature),
                    getString(if (initials) R.string.draw_new_initials else R.string.draw_new_signature),
                ),
            ) { _, which ->
                if (which == 0) placeSignature(saved, if (initials) 0.12f else 0.35f) else drawSignature(initials)
            }
            .show()
    }

    private fun drawSignature(initials: Boolean) {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_signature, null)
        val pad = content.findViewById<SignaturePadView>(R.id.signaturePad)
        val remember = content.findViewById<MaterialCheckBox>(R.id.rememberSignature)
        val group = content.findViewById<MaterialButtonToggleGroup>(R.id.colorGroup)
        group.check(if (inkColor() == ContextCompat.getColor(this, R.color.ink_blue)) R.id.colorBlue else R.id.colorBlack)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val blue = checkedId == R.id.colorBlue
            prefs.edit().putBoolean(KEY_BLUE, blue).apply()
            pad.inkColor = inkColor()
            updateInkSwatch()
        }
        pad.inkColor = inkColor()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (initials) R.string.draw_initials else R.string.draw_signature)
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
                if (remember.isChecked) save(bitmap, if (initials) savedInitialsFile else savedSignatureFile)
                dialog.dismiss()
                placeSignature(bitmap, if (initials) 0.12f else 0.35f)
            }
        }
        dialog.show()
    }

    private fun placeSignature(signature: Bitmap, widthFraction: Float = 0.35f) {
        pageView.addPlacement(signature, widthFraction)
        toast(getString(R.string.hint_place))
    }

    private fun loadSaved(initials: Boolean): Bitmap? {
        val file = if (initials) savedInitialsFile else savedSignatureFile
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun save(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
            // La mémorisation est facultative
        }
    }

    // --- Encre, signes, tampons --------------------------------------------------------------

    private fun inkColor(): Int =
        ContextCompat.getColor(this, if (prefs.getBoolean(KEY_BLUE, false)) R.color.ink_blue else R.color.ink_black)

    private fun toggleInk() {
        prefs.edit().putBoolean(KEY_BLUE, !prefs.getBoolean(KEY_BLUE, false)).apply()
        updateInkSwatch()
        toast(if (prefs.getBoolean(KEY_BLUE, false)) R.string.ink_blue_on else R.string.ink_black_on)
    }

    private fun updateInkSwatch() {
        findViewById<View>(R.id.inkSwatch)?.background?.mutate()?.setTint(inkColor())
    }

    /** Taille d'affichage ≈ [points] sur la page. */
    private fun fraction(bitmapWidth: Int, points: Float, pxPerPoint: Float) =
        if (pageWidthPts > 0) bitmapWidth / pxPerPoint * points / pageWidthPts else 0.2f

    private fun placeGlyph(kind: Glyphs.Kind) {
        val bmp = Glyphs.render(kind, inkColor())
        val pts = if (kind == Glyphs.Kind.LINE) 90f else 14f
        pageView.addPlacement(bmp, if (pageWidthPts > 0) pts / pageWidthPts else 0.04f)
    }

    private fun placeText(text: String) {
        val bitmap = TextStamp.render(text, inkColor())
        pageView.addPlacement(bitmap, fraction(bitmap.width, 12f, 72f))
    }

    private fun showStamps() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_stamps, null)
        val grid = view.findViewById<android.widget.GridLayout>(R.id.stampGrid)
        val withDate = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.stampDate)
        val dp = resources.displayMetrics.density
        fun fill() {
            grid.removeAllViews()
            StampRenderer.presets.forEach { preset ->
                val bmp = StampRenderer.render(preset.label, ContextCompat.getColor(this, preset.colorRes), withDate.isChecked)
                val image = android.widget.ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                    setBackgroundResource(R.drawable.bg_hairline_box)
                    contentDescription = preset.label
                    setOnClickListener {
                        sheet.dismiss()
                        pageView.addPlacement(bmp, if (pageWidthPts > 0) 150f / pageWidthPts else 0.3f)
                        toast(getString(R.string.hint_place))
                    }
                }
                grid.addView(image, android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                ).apply {
                    width = 0
                    height = (64 * dp).toInt()
                    setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                })
            }
        }
        withDate.setOnCheckedChangeListener { _, _ -> fill() }
        fill()
        view.findViewById<View>(R.id.stampCustom).setOnClickListener {
            sheet.dismiss()
            promptText(this, R.string.custom_stamp, R.string.text_hint, "") { label ->
                val bmp = StampRenderer.render(label.uppercase(Locale.FRANCE), ContextCompat.getColor(this, R.color.stamp_red), withDate.isChecked)
                pageView.addPlacement(bmp, if (pageWidthPts > 0) 150f / pageWidthPts else 0.3f)
            }
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

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
