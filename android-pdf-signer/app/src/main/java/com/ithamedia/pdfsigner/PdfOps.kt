package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Moteur des outils PDF (PdfBox-Android + PdfRenderer). Toutes les fonctions sont bloquantes. */
object PdfOps {

    // --- Ouverture ---------------------------------------------------------------------------

    fun open(file: File, password: String? = null): PDDocument {
        val doc = try {
            if (password != null) PDDocument.load(file, password) else PDDocument.load(file)
        } catch (_: InvalidPasswordException) {
            throw UserMessageException(
                if (password == null) "Ce PDF est protégé par un mot de passe. Retirez-le d'abord avec « Retirer le mot de passe »."
                else "Mot de passe incorrect.",
            )
        }
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    fun pageCount(file: File): Int? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }.getOrNull()

    fun isEncrypted(file: File): Boolean = runCatching {
        PDDocument.load(file).use { false }
    }.getOrElse { it is InvalidPasswordException }

    private fun norm(deg: Int) = ((deg % 360) + 360) % 360

    // --- Assembler (organiser, extraire, insérer, fractionner) ------------------------------

    sealed class OrgPage {
        data class Source(val source: Int, val index: Int, val rotation: Int = 0) : OrgPage()
        data class Blank(val width: Float = 595f, val height: Float = 842f) : OrgPage()
    }

    /** Construit un nouveau PDF à partir de pages de [sources] (et de pages blanches). */
    fun assemble(sources: List<File>, pages: List<OrgPage>, output: File) {
        val docs = sources.map { open(it) }
        try {
            assembleDocs(docs, pages, output)
        } finally {
            docs.forEach { runCatching { it.close() } }
        }
    }

    private fun assembleDocs(docs: List<PDDocument>, pages: List<OrgPage>, output: File) {
        PDDocument().use { out ->
            for (p in pages) {
                when (p) {
                    is OrgPage.Blank -> out.addPage(PDPage(PDRectangle(p.width, p.height)))
                    is OrgPage.Source -> out.addPage(copyPage(docs[p.source].getPage(p.index), p.rotation))
                }
            }
            // Un seul document source : on garde son formulaire interactif
            if (docs.size == 1) docs[0].documentCatalog.acroForm?.let { out.documentCatalog.acroForm = it }
            out.save(output)
        }
    }

    /** Copie indépendante d'une page (attributs hérités résolus), pour pouvoir la dupliquer. */
    private fun copyPage(source: PDPage, extraRotation: Int): PDPage {
        val dict = COSDictionary(source.cosObject)
        dict.removeItem(COSName.PARENT)
        val page = PDPage(dict)
        page.mediaBox = source.mediaBox
        page.cropBox = source.cropBox
        source.resources?.let { page.resources = it }
        page.rotation = norm(source.rotation + extraRotation)
        return page
    }

    fun split(source: File, ranges: List<IntRange>, outDir: File, baseName: String): List<File> {
        outDir.mkdirs()
        val doc = open(source)
        try {
            return ranges.mapIndexed { i, range ->
                val name = if (range.first == range.last) "$baseName - page ${range.first + 1}.pdf"
                else "$baseName - ${i + 1} (p. ${range.first + 1}-${range.last + 1}).pdf"
                val file = Library.uniqueFile(outDir, Library.sanitize(name))
                assembleDocs(listOf(doc), range.map { OrgPage.Source(0, it) }, file)
                file
            }
        } finally {
            doc.close()
        }
    }

    // --- Rogner ------------------------------------------------------------------------------

    /** Marges en fractions de la page affichée (gauche, haut, droite, bas). */
    fun crop(source: File, output: File, left: Float, top: Float, right: Float, bottom: Float) {
        open(source).use { doc ->
            for (page in doc.pages) {
                val m = PageMapper(page)
                val a = m.toPdf(left * m.width, top * m.height)
                val b = m.toPdf((1 - right) * m.width, (1 - bottom) * m.height)
                page.cropBox = PDRectangle(min(a[0], b[0]), min(a[1], b[1]), abs(a[0] - b[0]), abs(a[1] - b[1]))
            }
            doc.save(output)
        }
    }

    // --- Texte sur les pages (filigrane, en-tête/pied, numéros, Bates) ----------------------

    enum class Position { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

    class TextStampSpec(
        val text: (pageNumber: Int, total: Int) -> String,
        val position: Position,
        val sizePt: Float,
        val color: Int,
        val opacity: Float = 1f,
        val angle: Float = 0f,
        val marginPt: Float = 28f,
        val bold: Boolean = false,
        val pages: IntRange? = null,
    )

    fun stampText(source: File, output: File, specs: List<TextStampSpec>) {
        open(source).use { doc ->
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { index, page ->
                val applicable = specs.filter { it.pages == null || index in it.pages }
                if (applicable.isEmpty()) return@forEachIndexed
                val m = PageMapper(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (spec in applicable) {
                        val font: PDFont = if (spec.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
                        val text = winAnsi(font, spec.text(index + 1, total))
                        if (text.isBlank()) continue
                        val w = font.getStringWidth(text) / 1000f * spec.sizePt
                        val capH = spec.sizePt * 0.72f
                        val (sx, sy) = anchor(spec, m.width, m.height, w, capH)
                        cs.saveGraphicsState()
                        if (spec.opacity < 1f) {
                            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = spec.opacity })
                        }
                        cs.beginText()
                        cs.setFont(font, spec.sizePt)
                        cs.setNonStrokingColor(Color.red(spec.color) / 255f, Color.green(spec.color) / 255f, Color.blue(spec.color) / 255f)
                        val p = m.toPdf(sx, sy)
                        cs.setTextMatrix(Matrix.getRotateInstance(m.textAngle(spec.angle, sx, sy), p[0], p[1]))
                        cs.showText(text)
                        cs.endText()
                        cs.restoreGraphicsState()
                    }
                }
            }
            doc.save(output)
        }
    }

    /** Point de départ de la ligne de base, dans la page affichée. */
    fun anchor(spec: TextStampSpec, pageW: Float, pageH: Float, textW: Float, capH: Float): Pair<Float, Float> {
        val mg = spec.marginPt
        return when (spec.position) {
            Position.CENTER -> {
                val rad = Math.toRadians(spec.angle.toDouble())
                val dx = cos(rad).toFloat()
                val dy = -sin(rad).toFloat()
                // départ = centre − demi-largeur le long de la ligne + demi-hauteur vers le bas
                (pageW / 2 - dx * textW / 2 + sin(rad).toFloat() * capH / 2) to
                    (pageH / 2 - dy * textW / 2 + cos(rad).toFloat() * capH / 2)
            }
            Position.TOP_LEFT -> mg to mg + capH
            Position.TOP_CENTER -> (pageW - textW) / 2 to mg + capH
            Position.TOP_RIGHT -> pageW - mg - textW to mg + capH
            Position.BOTTOM_LEFT -> mg to pageH - mg
            Position.BOTTOM_CENTER -> (pageW - textW) / 2 to pageH - mg
            Position.BOTTOM_RIGHT -> pageW - mg - textW to pageH - mg
        }
    }

    /** Remplace les caractères absents de l'encodage WinAnsi des polices standard. */
    fun winAnsi(font: PDFont, s: String): String = buildString {
        for (ch in s.replace('\n', ' ').replace('\t', ' ')) {
            val c = ch.toString()
            append(
                try {
                    font.encode(c)
                    c
                } catch (_: Exception) {
                    when (ch) {
                        '’', '‘' -> "'"
                        '“', '”', '«', '»' -> "\""
                        '–', '—' -> "-"
                        else -> "?"
                    }
                },
            )
        }
    }

    // --- Annotations aplaties (stylo, surligneur, formes, texte, notes) ---------------------

    fun flattenMarks(source: File, output: File, marks: Map<Int, List<Mark>>) {
        open(source).use { doc ->
            for ((index, list) in marks) {
                if (list.isEmpty() || index >= doc.numberOfPages) continue
                val page = doc.getPage(index)
                val m = PageMapper(page)
                val notes = mutableListOf<PDAnnotationText>()
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (mark in list) drawMark(cs, m, mark)?.let { notes += it }
                }
                if (notes.isNotEmpty()) page.annotations = page.annotations.toMutableList().apply { addAll(notes) }
            }
            doc.save(output)
        }
    }

    private fun drawMark(cs: PDPageContentStream, m: PageMapper, mark: Mark): PDAnnotationText? {
        val r = Color.red(mark.color) / 255f
        val g = Color.green(mark.color) / 255f
        val b = Color.blue(mark.color) / 255f
        val pts = mark.points
        if (pts.isEmpty()) return null
        fun p(i: Int) = m.fromNormalized(pts[i].x, pts[i].y)
        fun disp(i: Int) = pts[i].x * m.width to pts[i].y * m.height
        cs.saveGraphicsState()
        try {
            cs.setStrokingColor(r, g, b)
            cs.setNonStrokingColor(r, g, b)
            cs.setLineWidth(max(0.3f, mark.width * m.width))
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            when (mark.tool) {
                InkTool.PEN, InkTool.HIGHLIGHTER -> {
                    if (mark.tool == InkTool.HIGHLIGHTER) {
                        cs.setGraphicsStateParameters(
                            PDExtendedGraphicsState().apply {
                                strokingAlphaConstant = 0.38f
                                blendMode = BlendMode.MULTIPLY
                            },
                        )
                    }
                    val first = p(0)
                    cs.moveTo(first[0], first[1])
                    if (pts.size == 1) cs.lineTo(first[0] + 0.01f, first[1])
                    for (i in 1 until pts.size) {
                        val q = p(i)
                        cs.lineTo(q[0], q[1])
                    }
                    cs.stroke()
                }
                InkTool.RECT, InkTool.OVAL, InkTool.LINE, InkTool.ARROW -> {
                    if (pts.size < 2) return null
                    val (x0, y0) = disp(0)
                    val (x1, y1) = disp(1)
                    when (mark.tool) {
                        InkTool.RECT -> {
                            val c = listOf(m.toPdf(x0, y0), m.toPdf(x1, y0), m.toPdf(x1, y1), m.toPdf(x0, y1))
                            cs.moveTo(c[0][0], c[0][1])
                            for (k in 1..3) cs.lineTo(c[k][0], c[k][1])
                            cs.closePath()
                            cs.stroke()
                        }
                        InkTool.OVAL -> {
                            val cx = (x0 + x1) / 2
                            val cy = (y0 + y1) / 2
                            val rx = abs(x1 - x0) / 2
                            val ry = abs(y1 - y0) / 2
                            val k = 0.5523f
                            fun t(x: Float, y: Float) = m.toPdf(x, y)
                            val s = t(cx + rx, cy)
                            cs.moveTo(s[0], s[1])
                            curve(cs, t(cx + rx, cy + ry * k), t(cx + rx * k, cy + ry), t(cx, cy + ry))
                            curve(cs, t(cx - rx * k, cy + ry), t(cx - rx, cy + ry * k), t(cx - rx, cy))
                            curve(cs, t(cx - rx, cy - ry * k), t(cx - rx * k, cy - ry), t(cx, cy - ry))
                            curve(cs, t(cx + rx * k, cy - ry), t(cx + rx, cy - ry * k), t(cx + rx, cy))
                            cs.closePath()
                            cs.stroke()
                        }
                        else -> {
                            val a = m.toPdf(x0, y0)
                            val e = m.toPdf(x1, y1)
                            cs.moveTo(a[0], a[1])
                            cs.lineTo(e[0], e[1])
                            if (mark.tool == InkTool.ARROW) {
                                val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                                val len = max(10f, mark.width * m.width * 5)
                                for (side in listOf(-1, 1)) {
                                    val ang = angle + Math.PI + side * Math.PI / 7
                                    val h = m.toPdf(x1 + (cos(ang) * len).toFloat(), y1 + (sin(ang) * len).toFloat())
                                    cs.moveTo(e[0], e[1])
                                    cs.lineTo(h[0], h[1])
                                }
                            }
                            cs.stroke()
                        }
                    }
                }
                InkTool.TEXT -> {
                    val text = mark.text ?: return null
                    val font = PDType1Font.HELVETICA
                    val size = max(4f, mark.textSize * m.width)
                    val (x0, y0) = disp(0)
                    text.split('\n').forEachIndexed { i, line ->
                        val baseline = y0 + size * (0.9f + i * 1.2f)
                        val pt = m.toPdf(x0, baseline)
                        cs.beginText()
                        cs.setFont(font, size)
                        cs.setTextMatrix(Matrix.getRotateInstance(m.textAngle(0f, x0, baseline), pt[0], pt[1]))
                        cs.showText(winAnsi(font, line))
                        cs.endText()
                    }
                }
                InkTool.NOTE -> {
                    val (x0, y0) = disp(0)
                    val size = 16f
                    val corners = listOf(m.toPdf(x0, y0), m.toPdf(x0 + size, y0), m.toPdf(x0 + size, y0 + size), m.toPdf(x0, y0 + size))
                    cs.setNonStrokingColor(1f, 0.83f, 0.25f)
                    cs.setStrokingColor(0.55f, 0.42f, 0.05f)
                    cs.setLineWidth(0.8f)
                    cs.moveTo(corners[0][0], corners[0][1])
                    for (k in 1..3) cs.lineTo(corners[k][0], corners[k][1])
                    cs.closePath()
                    cs.fillAndStroke()
                    val xs = corners.map { it[0] }
                    val ys = corners.map { it[1] }
                    return PDAnnotationText().apply {
                        rectangle = PDRectangle(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
                        contents = mark.text ?: ""
                        isNoRotate = true
                    }
                }
                InkTool.REDACT -> Unit
            }
        } finally {
            cs.restoreGraphicsState()
        }
        return null
    }

    private fun curve(cs: PDPageContentStream, a: FloatArray, b: FloatArray, c: FloatArray) =
        cs.curveTo(a[0], a[1], b[0], b[1], c[0], c[1])

    // --- Caviardage --------------------------------------------------------------------------

    /**
     * Les pages contenant des zones à caviarder sont converties en image avec les zones
     * noircies : le texte et les images dessous sont réellement supprimés du fichier.
     */
    fun redact(source: File, output: File, boxes: Map<Int, List<RectF>>, dpi: Int = 200) {
        val doc = open(source)
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    PDDocument().use { out ->
                        val black = Paint().apply { color = Color.BLACK }
                        for (i in 0 until doc.numberOfPages) {
                            val marks = boxes[i].orEmpty()
                            if (marks.isEmpty()) {
                                out.addPage(copyPage(doc.getPage(i), 0))
                                continue
                            }
                            renderer.openPage(i).use { rp ->
                                val scale = dpi / 72f
                                val bmp = Bitmap.createBitmap(
                                    (rp.width * scale).toInt().coerceAtLeast(1),
                                    (rp.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888,
                                )
                                bmp.eraseColor(Color.WHITE)
                                rp.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val canvas = Canvas(bmp)
                                for (r in marks) {
                                    canvas.drawRect(r.left * bmp.width, r.top * bmp.height, r.right * bmp.width, r.bottom * bmp.height, black)
                                }
                                val page = PDPage(PDRectangle(rp.width.toFloat(), rp.height.toFloat()))
                                out.addPage(page)
                                val image = JPEGFactory.createFromImage(out, bmp, 0.9f)
                                bmp.recycle()
                                PDPageContentStream(out, page).use { it.drawImage(image, 0f, 0f, rp.width.toFloat(), rp.height.toFloat()) }
                            }
                        }
                        out.save(output)
                    }
                }
            }
        } finally {
            doc.close()
        }
    }

    /** Positions (normalisées, page affichée) de chaque occurrence de [query]. */
    fun findText(file: File, query: String): Map<Int, List<RectF>> {
        if (query.isBlank()) return emptyMap()
        open(file).use { doc ->
            val finder = Finder(query.trim())
            finder.getText(doc)
            return finder.hits
        }
    }

    private class Finder(private val query: String) : PDFTextStripper() {
        val hits = HashMap<Int, MutableList<RectF>>()

        init {
            sortByPosition = true
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            val positions = textPositions ?: return
            val line = text ?: return
            if (positions.isEmpty()) return
            val m = PageMapper(currentPage)
            var from = 0
            while (true) {
                val idx = line.indexOf(query, from, ignoreCase = true)
                if (idx < 0) break
                val scale = positions.size.toFloat() / line.length
                val a = (idx * scale).toInt().coerceIn(0, positions.size - 1)
                val b = ((idx + query.length) * scale - 1).toInt().coerceIn(a, positions.size - 1)
                var l = Float.MAX_VALUE
                var t = Float.MAX_VALUE
                var r = 0f
                var btm = 0f
                for (k in a..b) {
                    val tp = positions[k]
                    l = min(l, tp.xDirAdj)
                    r = max(r, tp.xDirAdj + tp.widthDirAdj)
                    t = min(t, tp.yDirAdj - tp.heightDir)
                    btm = max(btm, tp.yDirAdj + tp.heightDir * 0.3f)
                }
                val pad = 1.5f
                hits.getOrPut(currentPageNo - 1) { mutableListOf() } += RectF(
                    ((l - pad) / m.width).coerceIn(0f, 1f),
                    ((t - pad) / m.height).coerceIn(0f, 1f),
                    ((r + pad) / m.width).coerceIn(0f, 1f),
                    ((btm + pad) / m.height).coerceIn(0f, 1f),
                )
                from = idx + query.length
            }
        }
    }

    // --- Recherche de texte (visionneuse) ----------------------------------------------------

    data class SearchHit(val page: Int, val snippet: String)

    fun search(file: File, query: String, limit: Int = 200): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        open(file).use { doc ->
            val stripper = PDFTextStripper()
            for (i in 1..doc.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                val text = stripper.getText(doc).replace(Regex("\\s+"), " ")
                var from = 0
                while (hits.size < limit) {
                    val idx = text.indexOf(q, from, ignoreCase = true)
                    if (idx < 0) break
                    val start = max(0, idx - 40)
                    val end = min(text.length, idx + q.length + 40)
                    hits += SearchHit(i - 1, (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else ""))
                    from = idx + q.length
                }
            }
        }
        return hits
    }

    data class OutlineItem(val title: String, val page: Int?, val depth: Int)

    fun outline(file: File): List<OutlineItem> = runCatching {
        open(file).use { doc ->
            val items = mutableListOf<OutlineItem>()
            val root = doc.documentCatalog.documentOutline ?: return@use items
            fun walk(node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode, depth: Int) {
                var child = node.firstChild
                var guard = 0
                while (child != null && guard++ < 2000) {
                    val page = runCatching { child.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull()
                    items += OutlineItem(child.title ?: "—", page?.takeIf { it >= 0 }, depth)
                    if (depth < 6) walk(child, depth + 1)
                    child = child.nextSibling
                }
            }
            walk(root, 0)
            items
        }
    }.getOrDefault(emptyList())

    // --- Conversion --------------------------------------------------------------------------

    fun renderToImages(file: File, outDir: File, baseName: String, dpi: Int, png: Boolean, pages: IntRange? = null, progress: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        outDir.mkdirs()
        val result = mutableListOf<File>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val range = pages ?: 0 until renderer.pageCount
                for (i in range) {
                    if (i !in 0 until renderer.pageCount) continue
                    progress(i + 1, renderer.pageCount)
                    renderer.openPage(i).use { page ->
                        var scale = dpi / 72f
                        val longSide = max(page.width, page.height) * scale
                        if (longSide > 5000) scale *= 5000 / longSide
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val ext = if (png) "png" else "jpg"
                        val out = Library.uniqueFile(outDir, "$baseName - p${"%03d".format(i + 1)}.$ext")
                        FileOutputStream(out).use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                        bmp.recycle()
                        result += out
                    }
                }
            }
        }
        return result
    }

    fun extractTextPages(file: File): List<String> = open(file).use { doc ->
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        (1..doc.numberOfPages).map { i ->
            stripper.startPage = i
            stripper.endPage = i
            stripper.getText(doc)
        }
    }

    /** Regroupe les lignes extraites en paragraphes. */
    fun paragraphs(pageText: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (raw in pageText.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (current.isNotEmpty()) result += current.toString()
                current.clear()
                continue
            }
            val bullet = line.startsWith("•") || line.startsWith("- ") || Regex("^\\d+[.)] ").containsMatchIn(line)
            if (current.isNotEmpty() && bullet) {
                result += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) {
                if (current.endsWith("-")) current.setLength(current.length - 1) else current.append(' ')
            }
            current.append(line)
            if (line.endsWith('.') || line.endsWith(':') || line.endsWith('!') || line.endsWith('?')) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    fun writeDocx(pages: List<List<String>>, output: File) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .filter { it == '\t' || it >= ' ' }
        val body = StringBuilder()
        pages.forEachIndexed { i, paras ->
            if (i > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            for (p in paras) body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(esc(p)).append("</w:t></w:r></w:p>")
        }
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$w"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$w"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/document.xml", document)
            put("word/styles.xml", styles)
            put("word/_rels/document.xml.rels", docRels)
        }
    }

    /** Extrait les images intégrées (JPEG d'origine quand c'est possible, sinon PNG). */
    fun extractImages(file: File, outDir: File, baseName: String): List<File> {
        outDir.mkdirs()
        val result = mutableListOf<File>()
        open(file).use { doc ->
            val seen: MutableSet<COSBase> = Collections.newSetFromMap(IdentityHashMap())
            fun walk(resources: PDResources?, pageNo: Int) {
                if (resources == null) return
                for (name in resources.xObjectNames) {
                    val x = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                    if (!seen.add(x.cosObject)) continue
                    when (x) {
                        is PDImageXObject -> runCatching {
                            if (x.width < 16 || x.height < 16) return@runCatching
                            val n = result.size + 1
                            if (x.suffix == "jpg") {
                                val out = Library.uniqueFile(outDir, "$baseName - image ${"%03d".format(n)} (p. $pageNo).jpg")
                                x.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { input ->
                                    FileOutputStream(out).use { input.copyTo(it) }
                                }
                                result += out
                            } else {
                                val bmp = x.image ?: return@runCatching
                                val out = Library.uniqueFile(outDir, "$baseName - image ${"%03d".format(n)} (p. $pageNo).png")
                                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                bmp.recycle()
                                result += out
                            }
                        }
                        is PDFormXObject -> walk(x.resources, pageNo)
                    }
                }
            }
            doc.pages.forEachIndexed { i, page -> walk(page.resources, i + 1) }
        }
        return result
    }

    // --- OCR ---------------------------------------------------------------------------------

    /** Une ligne reconnue, en coordonnées normalisées de la page affichée. */
    class OcrLine(val text: String, val box: RectF)

    /**
     * Ajoute sous chaque page une couche de texte invisible, alignée sur le texte reconnu :
     * le PDF devient cherchable et le texte peut être sélectionné et copié.
     */
    fun addTextLayer(source: File, output: File, skipPagesWithText: Boolean, recognize: (Bitmap) -> List<OcrLine>, progress: (Int, Int) -> Unit): Int {
        var recognized = 0
        open(source).use { doc ->
            val stripper = PDFTextStripper()
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val font = PDType1Font.HELVETICA
                    for (i in 0 until doc.numberOfPages) {
                        progress(i + 1, doc.numberOfPages)
                        if (skipPagesWithText) {
                            stripper.startPage = i + 1
                            stripper.endPage = i + 1
                            if (stripper.getText(doc).trim().length > 25) continue
                        }
                        val lines = renderer.openPage(i).use { rp ->
                            val scale = min(3f, 2400f / max(rp.width, rp.height))
                            val bmp = Bitmap.createBitmap((rp.width * scale).toInt(), (rp.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            rp.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            try {
                                recognize(bmp)
                            } finally {
                                bmp.recycle()
                            }
                        }
                        if (lines.isEmpty()) continue
                        recognized++
                        val page = doc.getPage(i)
                        val m = PageMapper(page)
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.beginText()
                            cs.setRenderingMode(RenderingMode.NEITHER)
                            for (line in lines) {
                                val text = winAnsi(font, line.text).trim()
                                if (text.isEmpty()) continue
                                val h = (line.box.bottom - line.box.top) * m.height
                                val size = max(1f, h * 0.82f)
                                val textW = font.getStringWidth(text) / 1000f * size
                                val targetW = (line.box.right - line.box.left) * m.width
                                if (textW <= 0f || targetW <= 0f) continue
                                cs.setFont(font, size)
                                cs.setHorizontalScaling((targetW / textW * 100f).coerceIn(10f, 1000f))
                                val x = line.box.left * m.width
                                val baseline = line.box.bottom * m.height - h * 0.18f
                                val pt = m.toPdf(x, baseline)
                                cs.setTextMatrix(Matrix.getRotateInstance(m.textAngle(0f, x, baseline), pt[0], pt[1]))
                                cs.showText(text)
                            }
                            cs.endText()
                        }
                    }
                }
            }
            doc.save(output)
        }
        return recognized
    }

    // --- Sécurité ----------------------------------------------------------------------------

    fun protect(source: File, output: File, userPassword: String, ownerPassword: String?, allowPrint: Boolean, allowCopy: Boolean, allowModify: Boolean) {
        open(source).use { doc ->
            val permissions = AccessPermission().apply {
                setCanPrint(allowPrint)
                setCanExtractContent(allowCopy)
                setCanModify(allowModify)
                setCanModifyAnnotations(allowModify)
                setCanFillInForm(true)
            }
            val owner = ownerPassword?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
            val policy = StandardProtectionPolicy(owner, userPassword, permissions)
            policy.encryptionKeyLength = 256
            doc.protect(policy)
            doc.save(output)
        }
    }

    fun unlock(source: File, output: File, password: String) {
        open(source, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(output)
        }
    }

    class SanitizeOptions(
        val metadata: Boolean,
        val scripts: Boolean,
        val attachments: Boolean,
        val comments: Boolean,
        val bookmarks: Boolean,
        val flattenForms: Boolean,
    )

    fun sanitize(source: File, output: File, options: SanitizeOptions): List<String> {
        val done = mutableListOf<String>()
        open(source).use { doc ->
            val catalog = doc.documentCatalog
            if (options.metadata) {
                doc.documentInformation = PDDocumentInformation()
                catalog.cosObject.removeItem(COSName.METADATA)
                done += "métadonnées"
            }
            if (options.scripts) {
                catalog.cosObject.removeItem(COSName.OPEN_ACTION)
                catalog.cosObject.removeItem(COSName.AA)
                catalog.names?.cosObject?.removeItem(COSName.JAVA_SCRIPT)
                for (page in doc.pages) {
                    page.cosObject.removeItem(COSName.AA)
                    for (annot in page.annotations) {
                        annot.cosObject.removeItem(COSName.AA)
                        val action = annot.cosObject.getDictionaryObject(COSName.A) as? COSDictionary
                        if (action?.getNameAsString(COSName.S) == "JavaScript") annot.cosObject.removeItem(COSName.A)
                    }
                }
                done += "scripts"
            }
            if (options.attachments) {
                catalog.names?.cosObject?.removeItem(COSName.EMBEDDED_FILES)
                for (page in doc.pages) {
                    page.annotations = page.annotations.filter { it.subtype != "FileAttachment" }
                }
                done += "pièces jointes"
            }
            if (options.comments) {
                for (page in doc.pages) {
                    page.annotations = page.annotations.filter { it is PDAnnotationLink || it is PDAnnotationWidget }
                }
                done += "commentaires"
            }
            if (options.bookmarks) {
                catalog.documentOutline = null
                done += "signets"
            }
            if (options.flattenForms) {
                catalog.acroForm?.let {
                    it.flatten()
                    done += "formulaire aplati"
                }
            }
            doc.save(output)
        }
        return done
    }

    // --- Propriétés --------------------------------------------------------------------------

    class DocInfo(
        val title: String?,
        val author: String?,
        val subject: String?,
        val keywords: String?,
        val creator: String?,
        val producer: String?,
        val created: Long?,
        val modified: Long?,
        val version: Float,
        val pages: Int,
        val pageSize: String,
        val encrypted: Boolean,
        val fonts: List<Pair<String, Boolean>>,
        val formFields: Int,
        val hasOutline: Boolean,
    )

    fun readInfo(file: File): DocInfo {
        val encrypted = isEncrypted(file)
        open(file).use { doc ->
            val info = doc.documentInformation
            val fonts = linkedMapOf<String, Boolean>()
            for (page in doc.pages) {
                val res = page.resources ?: continue
                for (name in res.fontNames) {
                    val font = runCatching { res.getFont(name) }.getOrNull() ?: continue
                    val fontName = (font.name ?: name.name).substringAfter('+')
                    fonts[fontName] = (fonts[fontName] ?: false) || font.isEmbedded
                }
                if (fonts.size > 60) break
            }
            val first = doc.getPage(0)
            val m = PageMapper(first)
            val size = "%.0f × %.0f mm".format(m.width * 25.4f / 72f, m.height * 25.4f / 72f) + paperName(m.width, m.height)
            return DocInfo(
                info.title, info.author, info.subject, info.keywords, info.creator, info.producer,
                info.creationDate?.timeInMillis, info.modificationDate?.timeInMillis,
                doc.version, doc.numberOfPages, size, encrypted,
                fonts.entries.map { it.key to it.value },
                doc.documentCatalog.acroForm?.fieldTree?.count() ?: 0,
                doc.documentCatalog.documentOutline != null,
            )
        }
    }

    private fun paperName(w: Float, h: Float): String {
        val a = min(w, h)
        val b = max(w, h)
        fun near(x: Float, y: Float) = abs(a - x) < 4 && abs(b - y) < 4
        return when {
            near(595f, 842f) -> " · A4"
            near(420f, 595f) -> " · A5"
            near(842f, 1191f) -> " · A3"
            near(612f, 792f) -> " · Lettre US"
            near(612f, 1008f) -> " · Legal US"
            else -> ""
        }
    }

    fun writeInfo(source: File, output: File, title: String, author: String, subject: String, keywords: String) {
        open(source).use { doc ->
            val info = doc.documentInformation
            info.title = title.ifBlank { null }
            info.author = author.ifBlank { null }
            info.subject = subject.ifBlank { null }
            info.keywords = keywords.ifBlank { null }
            info.modificationDate = java.util.Calendar.getInstance()
            doc.documentInformation = info
            doc.save(output)
        }
    }

    // --- Créer -------------------------------------------------------------------------------

    fun blank(output: File, count: Int, width: Float, height: Float) {
        PDDocument().use { doc ->
            repeat(count.coerceIn(1, 500)) { doc.addPage(PDPage(PDRectangle(width, height))) }
            doc.save(output)
        }
    }

    // --- Comparer ----------------------------------------------------------------------------

    /**
     * Compare deux rendus de page de même taille. Renvoie la part de pixels différents et une
     * image de la seconde page, estompée, où les différences ressortent en vermillon.
     */
    fun diff(a: Bitmap, b: Bitmap, highlight: Int): Pair<Float, Bitmap> {
        val w = a.width
        val h = a.height
        val bb = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        bb.getPixels(pb, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        var changed = 0
        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val d = abs(Color.red(ca) - Color.red(cb)) + abs(Color.green(ca) - Color.green(cb)) + abs(Color.blue(ca) - Color.blue(cb))
            if (d > 90) {
                changed++
                out[i] = highlight
            } else {
                // Estompe le contenu identique
                val l = (Color.red(cb) * 0.3 + Color.green(cb) * 0.59 + Color.blue(cb) * 0.11).toInt()
                val v = 255 - (255 - l) / 3
                out[i] = Color.rgb(v, v, v)
            }
        }
        val result = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (bb !== b) bb.recycle()
        return changed.toFloat() / (w * h) to result
    }

    fun distance(x0: Float, y0: Float, x1: Float, y1: Float) = hypot(x1 - x0, y1 - y0)
}
