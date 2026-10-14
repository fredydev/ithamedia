package com.ithamedia.pdfsigner

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.RectF
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun today(): String = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())

// --- Fractionner -----------------------------------------------------------------------------

class SplitActivity : FormToolActivity() {
    override val defaultTool = Tool.SPLIT
    override val primaryLabel get() = getString(R.string.split_action)
    private lateinit var mode: Options.Choice
    private lateinit var every: Slider
    private lateinit var ranges: TextInputEditText

    override fun buildOptions(o: Options) {
        o.section(getString(R.string.split_how))
        mode = o.choice(listOf("Toutes les N pages", "Par plages", "Une page par fichier"))
        every = o.slider("Pages par fichier", 1f, maxOf(2, pageCount).toFloat(), 1f, minOf(2, maxOf(1, pageCount)).toFloat()) { it.roundToInt().toString() }
        ranges = o.text("Plages, ex. 1-3, 4-8, 9-", inputType = InputType.TYPE_CLASS_TEXT)
        o.note("Chaque partie devient un PDF distinct, rangé dans un nouveau dossier.")
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val n = pageCount
        val list: List<IntRange> = when (mode.index) {
            0 -> (0 until n step every.value.roundToInt().coerceAtLeast(1)).map { it until minOf(n, it + every.value.roundToInt()) }
            1 -> PageRanges.parse(ranges.text.toString(), n) ?: run {
                toast(R.string.invalid_ranges)
                return null
            }
            else -> (0 until n).map { it..it }
        }
        if (list.size < 2 && mode.index != 1) {
            toast(R.string.split_nothing)
            return null
        }
        return {
            val folder = outputFolder("${file.baseName()} (fractionné)")
            val files = PdfOps.split(file, list, folder, file.baseName())
            ToolOutput.Many(folder, files, resources.getQuantityString(R.plurals.files_count, files.size, files.size))
        }
    }
}

// --- Rogner ----------------------------------------------------------------------------------

class CropActivity : FormToolActivity() {
    override val defaultTool = Tool.CROP
    override val wantsPreview = true
    override val primaryLabel get() = getString(R.string.crop_action)
    private val sliders = arrayOfNulls<Slider>(4)

    override fun buildOptions(o: Options) {
        o.section(getString(R.string.margins))
        listOf("Gauche", "Haut", "Droite", "Bas").forEachIndexed { i, label ->
            sliders[i] = o.slider(label, 0f, 40f, 0.5f, 0f) { "${"%.1f".format(it)} %" }
        }
        o.note("Le contenu masqué n'est pas supprimé : il reste récupérable en annulant le rognage dans un autre logiciel.")
    }

    private fun margins() = sliders.map { (it?.value ?: 0f) / 100f }

    override fun drawPreview(canvas: Canvas, width: Int, height: Int) {
        val (l, t, r, b) = margins()
        val shade = Paint().apply { color = Color.argb(150, 27, 26, 23) }
        val rect = RectF(l * width, t * height, (1 - r) * width, (1 - b) * height)
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, shade)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shade)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, shade)
        canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = ContextCompat.getColor(this@CropActivity, R.color.accent)
            pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
        })
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val (l, t, r, b) = margins()
        if (l + r >= 0.9f || t + b >= 0.9f) {
            toast(R.string.crop_too_much)
            return null
        }
        return { ToolOutput.Single(produce(output("${file.baseName()}_rogné.pdf")) { PdfOps.crop(file, it, l, t, r, b) }) }
    }
}

// --- Texte en PDF / PDF vierge ---------------------------------------------------------------

class TextToPdfActivity : FormToolActivity() {
    override val defaultTool = Tool.TEXT
    override val needsInput = false
    override val primaryLabel get() = getString(R.string.create_pdf_long)
    private lateinit var name: TextInputEditText
    private lateinit var body: TextInputEditText
    private lateinit var font: Options.Choice
    private lateinit var size: Slider

    override fun buildOptions(o: Options) {
        name = o.text(getString(R.string.pdf_name_title), "Note ${today().replace('/', '-')}")
        body = o.text(getString(R.string.text_body_hint), multiline = true)
        o.section(getString(R.string.typography))
        font = o.choice(listOf("Sans empattement", "Avec empattement"))
        size = o.slider("Taille du texte", 8f, 18f, 0.5f, 11f) { "${"%.1f".format(it)} pt" }
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val text = body.text.toString()
        if (text.isBlank()) {
            toast(R.string.text_empty)
            return null
        }
        val fileName = name.text.toString().ifBlank { "Texte" }
        val serif = font.index == 1
        val pt = size.value
        return { ToolOutput.Single(produce(output("$fileName.pdf")) { DocxConverter.textToPdf(text, it, pt, serif) }) }
    }
}

class BlankPdfActivity : FormToolActivity() {
    override val defaultTool = Tool.BLANK
    override val needsInput = false
    override val primaryLabel get() = getString(R.string.create_pdf_long)
    private lateinit var name: TextInputEditText
    private lateinit var format: Options.Choice
    private lateinit var orientation: Options.Choice
    private lateinit var count: Slider

    override fun buildOptions(o: Options) {
        name = o.text(getString(R.string.pdf_name_title), "Document vierge")
        o.section(getString(R.string.page_format))
        format = o.choice(listOf("A4", "A5", "A3", "Lettre US"))
        orientation = o.choice(listOf("Portrait", "Paysage"))
        count = o.slider("Nombre de pages", 1f, 50f, 1f, 1f) { it.roundToInt().toString() }
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val (w, h) = listOf(595f to 842f, 420f to 595f, 842f to 1191f, 612f to 792f)[format.index]
        val landscape = orientation.index == 1
        val n = count.value.roundToInt()
        val fileName = name.text.toString().ifBlank { "Document vierge" }
        return {
            ToolOutput.Single(produce(output("$fileName.pdf")) {
                PdfOps.blank(it, n, if (landscape) h else w, if (landscape) w else h)
            })
        }
    }
}

// --- Exporter --------------------------------------------------------------------------------

class ExportActivity : FormToolActivity() {
    override val defaultTool = Tool.TO_IMAGES
    override val primaryLabel get() = getString(R.string.export_action)
    private var format: Options.Choice? = null
    private var dpi: Options.Choice? = null
    private var range: TextInputEditText? = null
    private var separate: MaterialSwitch? = null

    override fun buildOptions(o: Options) {
        when (tool) {
            Tool.TO_IMAGES -> {
                o.section(getString(R.string.image_format))
                format = o.choice(listOf("PNG · sans perte", "JPEG · plus léger"))
                o.section(getString(R.string.resolution))
                dpi = o.choice(listOf("72 ppp · écran", "150 ppp · standard", "300 ppp · impression"), 1)
                o.section(getString(R.string.pages))
                range = o.text("Toutes, ou ex. 1-3, 7")
            }
            Tool.TO_TEXT -> {
                separate = o.switch("Séparer les pages", "Insère une ligne « — Page n — » entre les pages", true)
                o.note("Le texte est lu tel qu'il est enregistré dans le PDF. Pour un document scanné, utilisez d'abord « Reconnaître le texte ».")
            }
            Tool.TO_WORD -> o.note("Le texte de chaque page est regroupé en paragraphes dans un document .docx modifiable. La mise en page d'origine (colonnes, images, tableaux) n'est pas reconstituée.")
            else -> o.note("Chaque image intégrée au PDF est enregistrée dans sa qualité d'origine, dans un nouveau dossier.")
        }
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val base = file.baseName()
        if (tool == Tool.TO_IMAGES) {
            val png = format?.index != 1
            val ppp = listOf(72, 150, 300)[dpi?.index ?: 1]
            val text = range?.text?.toString().orEmpty()
            var selected: List<Int>? = null
            if (text.isNotBlank()) {
                val ranges = PageRanges.parse(text, pageCount)
                if (ranges == null) {
                    toast(R.string.invalid_ranges)
                    return null
                }
                selected = ranges.flatMap { it.toList() }.distinct().sorted()
            }
            val pages = selected
            return {
                val folder = outputFolder("$base (images)")
                val files = if (pages == null) {
                    PdfOps.renderToImages(file, folder, base, ppp, png) { i, n -> progress(i, n) }
                } else {
                    pages.flatMap { PdfOps.renderToImages(file, folder, base, ppp, png, it..it) }
                }
                ToolOutput.Many(folder, files, resources.getQuantityString(R.plurals.images_count, files.size, files.size))
            }
        }
        if (tool == Tool.TO_TEXT) {
            val sep = separate?.isChecked == true
            return {
                val pages = PdfOps.extractTextPages(file)
                val text = if (sep) pages.mapIndexed { i, t -> "— Page ${i + 1} —\n\n${t.trim()}" }.joinToString("\n\n")
                else pages.joinToString("\n") { it.trim() }
                if (text.isBlank()) {
                    ToolOutput.Info(getString(R.string.no_text_title), getString(R.string.no_text_body))
                } else {
                    ToolOutput.Single(produce(output("$base.txt")) { it.writeText(text) })
                }
            }
        }
        if (tool == Tool.TO_WORD) {
            return {
                val pages = PdfOps.extractTextPages(file)
                if (pages.all { it.isBlank() }) {
                    ToolOutput.Info(getString(R.string.no_text_title), getString(R.string.no_text_body))
                } else {
                    ToolOutput.Single(produce(output("$base.docx")) { PdfOps.writeDocx(pages.map { p -> PdfOps.paragraphs(p) }, it) })
                }
            }
        }
        return {
            val folder = outputFolder("$base (images extraites)")
            val files = PdfOps.extractImages(file, folder, base)
            if (files.isEmpty()) {
                folder.delete()
                ToolOutput.Info(getString(R.string.no_images_title), getString(R.string.no_images_body))
            } else {
                ToolOutput.Many(folder, files, resources.getQuantityString(R.plurals.images_count, files.size, files.size))
            }
        }
    }

    private fun progress(i: Int, n: Int) = runOnUiThread { setBusy(getString(R.string.page_progress, i, n)) }
}

// --- OCR -------------------------------------------------------------------------------------

class OcrActivity : FormToolActivity() {
    override val defaultTool = Tool.OCR
    override val primaryLabel get() = getString(R.string.ocr_action)
    private lateinit var skip: MaterialSwitch

    override fun buildOptions(o: Options) {
        skip = o.switch("Ignorer les pages déjà lisibles", "Pages qui contiennent déjà du texte sélectionnable", true)
        o.note("La reconnaissance fonctionne sur le téléphone, sans connexion, pour les langues en alphabet latin (français, anglais, espagnol…). Le texte reconnu est ajouté de façon invisible sous chaque page : l'apparence ne change pas, mais vous pouvez chercher, sélectionner et copier.")
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val skipText = skip.isChecked
        return {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                var count = 0
                val out = produce(output("${file.baseName()}_ocr.pdf")) { target ->
                    count = PdfOps.addTextLayer(file, target, skipText, { bmp ->
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                        result.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            PdfOps.OcrLine(
                                line.text,
                                RectF(box.left.toFloat() / bmp.width, box.top.toFloat() / bmp.height, box.right.toFloat() / bmp.width, box.bottom.toFloat() / bmp.height),
                            )
                        }
                    }) { i, n -> runOnUiThread { setBusy(getString(R.string.ocr_progress, i, n)) } }
                }
                if (count == 0) {
                    out.delete()
                    ToolOutput.Info(getString(R.string.ocr_nothing_title), getString(R.string.ocr_nothing_body))
                } else {
                    ToolOutput.Single(out, resources.getQuantityString(R.plurals.ocr_pages, count, count))
                }
            } finally {
                recognizer.close()
            }
        }
    }
}

// --- Filigrane, en-tête et pied de page, numérotation, Bates -------------------------------

class StampTextActivity : FormToolActivity() {
    override val defaultTool = Tool.WATERMARK
    override val wantsPreview = true
    override val primaryLabel get() = getString(R.string.apply_action)

    private var text1: TextInputEditText? = null
    private var text2: TextInputEditText? = null
    private var pos1: Options.Choice? = null
    private var pos2: Options.Choice? = null
    private var format: Options.Choice? = null
    private var angle: Options.Choice? = null
    private var color: Options.Choice? = null
    private var size: Slider? = null
    private var opacity: Slider? = null
    private var digits: Slider? = null
    private var start: TextInputEditText? = null
    private var skipFirst: MaterialSwitch? = null
    private var bold: MaterialSwitch? = null
    private var range: TextInputEditText? = null

    private val palette by lazy {
        listOf(
            ContextCompat.getColor(this, R.color.ink_black),
            ContextCompat.getColor(this, R.color.stamp_red),
            Color.rgb(128, 128, 128),
            ContextCompat.getColor(this, R.color.stamp_blue),
        )
    }
    private val sixPositions = listOf("Haut gauche", "Haut centre", "Haut droite", "Bas gauche", "Bas centre", "Bas droite")
    private val sixValues = listOf(
        PdfOps.Position.TOP_LEFT, PdfOps.Position.TOP_CENTER, PdfOps.Position.TOP_RIGHT,
        PdfOps.Position.BOTTOM_LEFT, PdfOps.Position.BOTTOM_CENTER, PdfOps.Position.BOTTOM_RIGHT,
    )

    override fun buildOptions(o: Options) {
        when (tool) {
            Tool.WATERMARK -> {
                text1 = o.text(getString(R.string.watermark_text), "CONFIDENTIEL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
                o.section(getString(R.string.appearance))
                angle = o.choice(listOf("Diagonale", "Horizontal", "Vertical"))
                color = o.choice(listOf("Encre", "Vermillon", "Gris", "Bleu"), 2)
                size = o.slider("Taille", 24f, 160f, 2f, 72f) { "${it.roundToInt()} pt" }
                opacity = o.slider("Opacité", 5f, 70f, 1f, 18f) { "${it.roundToInt()} %" }
                bold = o.switch("Caractères gras", checked = true)
                o.section(getString(R.string.pages))
                range = o.text("Toutes, ou ex. 1-3, 7")
            }
            Tool.HEADER_FOOTER -> {
                o.section(getString(R.string.header))
                text1 = o.text(getString(R.string.text_hint), "")
                pos1 = o.choice(listOf("Gauche", "Centre", "Droite"), 1)
                o.section(getString(R.string.footer))
                text2 = o.text(getString(R.string.text_hint), "{fichier} · {date}")
                pos2 = o.choice(listOf("Gauche", "Centre", "Droite"))
                o.section(getString(R.string.appearance))
                size = o.slider("Taille", 6f, 18f, 0.5f, 9f) { "${"%.1f".format(it)} pt" }
                color = o.choice(listOf("Encre", "Vermillon", "Gris", "Bleu"), 2)
                o.note("Variables : {page}, {total}, {date}, {fichier}")
            }
            Tool.PAGE_NUMBERS -> {
                o.section(getString(R.string.number_format))
                format = o.choice(listOf("1", "Page 1", "Page 1 sur 12", "1 / 12", "– 1 –"), 2)
                o.section(getString(R.string.position))
                pos1 = o.choice(sixPositions, 4)
                size = o.slider("Taille", 7f, 16f, 0.5f, 9.5f) { "${"%.1f".format(it)} pt" }
                start = o.text("Premier numéro", "1", InputType.TYPE_CLASS_NUMBER)
                skipFirst = o.switch("Ne pas numéroter la première page", "Utile pour une page de garde")
            }
            else -> {
                text1 = o.text("Préfixe", "DOC-", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
                start = o.text("Premier numéro", "1", InputType.TYPE_CLASS_NUMBER)
                digits = o.slider("Chiffres", 3f, 9f, 1f, 6f) { it.roundToInt().toString() }
                text2 = o.text("Suffixe (facultatif)", "")
                o.section(getString(R.string.position))
                pos1 = o.choice(sixPositions, 5)
                size = o.slider("Taille", 7f, 14f, 0.5f, 9f) { "${"%.1f".format(it)} pt" }
            }
        }
    }

    private fun fill(template: String, page: Int, total: Int, name: String) = template
        .replace("{page}", page.toString()).replace("{total}", total.toString())
        .replace("{date}", today()).replace("{fichier}", name)

    /** Réglages → textes à apposer. [total] est le nombre de pages du document. */
    private fun specs(total: Int): List<PdfOps.TextStampSpec>? {
        val name = source?.baseName().orEmpty()
        val col = palette[color?.index ?: 0]
        val pt = size?.value ?: 10f
        return when (tool) {
            Tool.WATERMARK -> {
                val text = text1?.text?.toString().orEmpty()
                val rangeText = range?.text?.toString().orEmpty()
                val pages = if (rangeText.isBlank()) null else PageRanges.pages(rangeText, total) ?: return null
                val deg = listOf(45f, 0f, 90f)[angle?.index ?: 0]
                listOf(
                    PdfOps.TextStampSpec(
                        { _, _ -> text }, PdfOps.Position.CENTER, pt, col, (opacity?.value ?: 18f) / 100f, deg,
                        bold = bold?.isChecked == true,
                        pages = null,
                    ).let { spec -> if (pages == null) spec else filtered(spec, pages) },
                )
            }
            Tool.HEADER_FOOTER -> {
                val top = listOf(PdfOps.Position.TOP_LEFT, PdfOps.Position.TOP_CENTER, PdfOps.Position.TOP_RIGHT)
                val bottom = listOf(PdfOps.Position.BOTTOM_LEFT, PdfOps.Position.BOTTOM_CENTER, PdfOps.Position.BOTTOM_RIGHT)
                val h = text1?.text?.toString().orEmpty()
                val f = text2?.text?.toString().orEmpty()
                listOfNotNull(
                    h.takeIf { it.isNotBlank() }?.let { t -> PdfOps.TextStampSpec({ p, n -> fill(t, p, n, name) }, top[pos1?.index ?: 1], pt, col) },
                    f.takeIf { it.isNotBlank() }?.let { t -> PdfOps.TextStampSpec({ p, n -> fill(t, p, n, name) }, bottom[pos2?.index ?: 0], pt, col) },
                )
            }
            Tool.PAGE_NUMBERS -> {
                val first = start?.text?.toString()?.toIntOrNull() ?: 1
                val skip = skipFirst?.isChecked == true
                val fmt = format?.index ?: 2
                val pages = if (skip) 1 until total else null
                listOf(
                    PdfOps.TextStampSpec({ p, n ->
                        val num = p - 1 + first - (if (skip) 1 else 0)
                        val last = n - 1 + first - (if (skip) 1 else 0)
                        when (fmt) {
                            0 -> "$num"
                            1 -> "Page $num"
                            2 -> "Page $num sur $last"
                            3 -> "$num / $last"
                            else -> "– $num –"
                        }
                    }, sixValues[pos1?.index ?: 4], pt, palette[0], pages = pages),
                )
            }
            else -> {
                val prefix = text1?.text?.toString().orEmpty()
                val suffix = text2?.text?.toString().orEmpty()
                val first = start?.text?.toString()?.toLongOrNull() ?: 1L
                val width = digits?.value?.roundToInt() ?: 6
                listOf(
                    PdfOps.TextStampSpec({ p, _ -> prefix + (first + p - 1).toString().padStart(width, '0') + suffix }, sixValues[pos1?.index ?: 5], pt, palette[0], bold = true),
                )
            }
        }
    }

    /** Filigrane limité à certaines pages (non contiguës). */
    private fun filtered(spec: PdfOps.TextStampSpec, pages: Set<Int>) = PdfOps.TextStampSpec(
        { p, n -> if (p - 1 in pages) spec.text(p, n) else "" },
        spec.position, spec.sizePt, spec.color, spec.opacity, spec.angle, spec.marginPt, spec.bold, null,
    )

    override fun drawPreview(canvas: Canvas, width: Int, height: Int) {
        val list = specs(maxOf(1, pageCount)) ?: return
        val scale = width / previewPageWidthPts
        val pageH = height / scale
        for (spec in list) {
            if (spec.pages != null && 0 !in spec.pages) continue
            val text = spec.text(1, maxOf(1, pageCount))
            if (text.isBlank()) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.color
                alpha = (spec.opacity * 255).roundToInt()
                textSize = spec.sizePt * scale
                typeface = Typeface.create(Typeface.SANS_SERIF, if (spec.bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val w = paint.measureText(text) / scale
            val (sx, sy) = PdfOps.anchor(spec, previewPageWidthPts, pageH, w, spec.sizePt * 0.72f)
            canvas.save()
            canvas.rotate(-spec.angle, sx * scale, sy * scale)
            canvas.drawText(text, sx * scale, sy * scale, paint)
            canvas.restore()
        }
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val list = specs(pageCount) ?: run {
            toast(R.string.invalid_ranges)
            return null
        }
        if (list.isEmpty()) {
            toast(R.string.text_empty)
            return null
        }
        val suffix = when (tool) {
            Tool.WATERMARK -> "filigrane"
            Tool.HEADER_FOOTER -> "en-tête"
            Tool.PAGE_NUMBERS -> "numéroté"
            else -> "bates"
        }
        return { ToolOutput.Single(produce(output("${file.baseName()}_$suffix.pdf")) { PdfOps.stampText(file, it, list) }) }
    }
}

// --- Protéger / déverrouiller / nettoyer -----------------------------------------------------

class ProtectActivity : FormToolActivity() {
    override val defaultTool = Tool.PROTECT
    override val primaryLabel get() = getString(R.string.protect_action)
    private lateinit var password: TextInputEditText
    private lateinit var confirm: TextInputEditText
    private lateinit var print: MaterialSwitch
    private lateinit var copy: MaterialSwitch
    private lateinit var modify: MaterialSwitch
    private lateinit var owner: TextInputEditText

    override fun buildOptions(o: Options) {
        password = o.text(getString(R.string.password), password = true)
        confirm = o.text(getString(R.string.password_confirm), password = true)
        o.section(getString(R.string.permissions))
        print = o.switch("Autoriser l'impression", checked = true)
        copy = o.switch("Autoriser la copie du texte", checked = true)
        modify = o.switch("Autoriser les modifications", checked = false)
        o.section(getString(R.string.advanced))
        owner = o.text(getString(R.string.owner_password), password = true)
        o.note("Sans mot de passe propriétaire, un mot de passe aléatoire est créé : les autorisations ci-dessus ne pourront plus être changées. Conservez votre mot de passe : il ne peut pas être récupéré.")
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val pwd = password.text.toString()
        if (pwd.length < 4) {
            toast(R.string.password_too_short)
            return null
        }
        if (pwd != confirm.text.toString()) {
            toast(R.string.password_mismatch)
            return null
        }
        val p = print.isChecked
        val c = copy.isChecked
        val m = modify.isChecked
        val ownerPwd = owner.text.toString()
        return {
            ToolOutput.Single(
                produce(output("${file.baseName()}_protégé.pdf")) { PdfOps.protect(file, it, pwd, ownerPwd, p, c, m) },
                getString(R.string.protect_done),
            )
        }
    }
}

class UnlockActivity : FormToolActivity() {
    override val defaultTool = Tool.UNLOCK
    override val primaryLabel get() = getString(R.string.unlock_action)
    private lateinit var password: TextInputEditText

    override fun buildOptions(o: Options) {
        password = o.text(getString(R.string.password), password = true)
        o.note("Si le PDF s'ouvre sans mot de passe mais limite l'impression ou la copie, laissez le champ vide.")
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val pwd = password.text.toString()
        return { ToolOutput.Single(produce(output("${file.baseName()}_déverrouillé.pdf")) { PdfOps.unlock(file, it, pwd) }) }
    }
}

class SanitizeActivity : FormToolActivity() {
    override val defaultTool = Tool.SANITIZE
    override val primaryLabel get() = getString(R.string.sanitize_action)
    private lateinit var switches: List<MaterialSwitch>

    override fun buildOptions(o: Options) {
        o.section(getString(R.string.to_remove))
        switches = listOf(
            o.switch("Métadonnées", "Auteur, logiciel, dates, propriétés XMP", true),
            o.switch("Scripts et actions automatiques", "JavaScript, actions à l'ouverture", true),
            o.switch("Pièces jointes", "Fichiers intégrés au PDF", true),
            o.switch("Commentaires et annotations", "Notes, surlignages, dessins"),
            o.switch("Signets", "Table des matières du panneau latéral"),
            o.switch("Aplatir le formulaire", "Les champs deviennent du contenu fixe"),
        )
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val s = switches.map { it.isChecked }
        if (s.none { it }) {
            toast(R.string.nothing_selected)
            return null
        }
        return {
            var removed = emptyList<String>()
            val out = produce(output("${file.baseName()}_nettoyé.pdf")) {
                removed = PdfOps.sanitize(file, it, PdfOps.SanitizeOptions(s[0], s[1], s[2], s[3], s[4], s[5]))
            }
            ToolOutput.Single(out, getString(R.string.sanitize_done, removed.joinToString(", ")))
        }
    }
}

// --- Propriétés ------------------------------------------------------------------------------

class PropertiesActivity : FormToolActivity() {
    override val defaultTool = Tool.PROPERTIES
    override val primaryLabel get() = getString(R.string.save_properties)
    private var info: PdfOps.DocInfo? = null
    private var fields: List<TextInputEditText> = emptyList()

    override fun onSourceReady(file: File) {
        runTask(getString(R.string.loading), { PdfOps.readInfo(file) }) {
            info = it
            rebuild()
        }
    }

    override fun buildOptions(o: Options) {
        val i = info ?: return
        o.section(getString(R.string.description))
        fields = listOf(
            o.text("Titre", i.title.orEmpty()),
            o.text("Auteur", i.author.orEmpty()),
            o.text("Sujet", i.subject.orEmpty()),
            o.text("Mots-clés", i.keywords.orEmpty()),
        )
        o.section(getString(R.string.document))
        o.info("Pages", i.pages.toString())
        o.info("Format", i.pageSize)
        o.info("Taille", source?.let { formatSize(it.length()) } ?: "—")
        o.info("Version PDF", "%.1f".format(Locale.ROOT, i.version))
        o.info("Créé le", i.created?.let { formatDate(it) } ?: "—")
        o.info("Modifié le", i.modified?.let { formatDate(it) } ?: "—")
        o.info("Application", i.creator ?: "—")
        o.info("Producteur", i.producer ?: "—")
        o.info("Sécurité", if (i.encrypted) "Chiffré" else "Aucune")
        o.info("Formulaire", if (i.formFields > 0) "${i.formFields} champs" else "Aucun")
        o.info("Signets", if (i.hasOutline) "Oui" else "Non")
        o.section(getString(R.string.fonts))
        if (i.fonts.isEmpty()) o.note("Aucune police (document image ou scanné).")
        i.fonts.forEach { (name, embedded) -> o.info(name, if (embedded) "Intégrée" else "Non intégrée") }
    }

    private fun rebuild() {
        val host = findViewById<android.widget.LinearLayout>(R.id.options)
        host.removeAllViews()
        options = Options(host) {}
        buildOptions(options)
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        if (fields.size < 4) return null
        val v = fields.map { it.text.toString().trim() }
        return { ToolOutput.Single(produce(output("${file.baseName()}_propriétés.pdf")) { PdfOps.writeInfo(file, it, v[0], v[1], v[2], v[3]) }) }
    }
}

// --- Compresser ------------------------------------------------------------------------------

class CompressActivity : FormToolActivity() {
    override val defaultTool = Tool.COMPRESS
    override val primaryLabel get() = getString(R.string.compress_action)
    private lateinit var level: Options.Cards

    override fun buildOptions(o: Options) {
        o.section(getString(R.string.compress_level))
        level = o.cards(
            listOf(
                getString(R.string.compress_best) to getString(R.string.compress_best_sub),
                getString(R.string.compress_balanced) to getString(R.string.compress_balanced_sub),
                getString(R.string.compress_strong) to getString(R.string.compress_strong_sub),
            ),
        )
        o.note(getString(R.string.compress_note))
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        val chosen = PdfTools.Level.entries[level.index]
        return {
            val out = produce(output("${file.baseName()}_compressé.pdf")) { PdfTools.compress(file, it, chosen) }
            val before = file.length()
            val after = out.length()
            if (after >= before * 0.97) {
                out.delete()
                ToolOutput.Info(
                    getString(R.string.compress_no_gain_title),
                    getString(if (chosen == PdfTools.Level.STRONG) R.string.compress_no_gain_body else R.string.compress_no_gain_try_stronger),
                )
            } else {
                val percent = ((1 - after.toDouble() / before) * 100).roundToInt()
                ToolOutput.Single(out, getString(R.string.compress_result, formatSize(before), formatSize(after), percent))
            }
        }
    }
}

// --- Word en PDF -----------------------------------------------------------------------------

class WordToPdfActivity : FormToolActivity() {
    override val defaultTool = Tool.WORD
    override val primaryLabel get() = getString(R.string.convert_action)
    override val inputTypes = arrayOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword",
    )

    override fun buildOptions(o: Options) {
        o.section(getString(R.string.word_supported_title))
        o.note(getString(R.string.word_supported_body))
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        return { ToolOutput.Single(produce(output("${file.baseName()}.pdf")) { DocxConverter.convert(file, it) }) }
    }
}
