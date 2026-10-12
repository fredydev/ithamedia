package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Conversion Word (.docx) → PDF, entièrement sur l'appareil.
 *
 * Prend en charge : paragraphes et styles (titres…), gras/italique/souligné/barré, tailles,
 * couleurs, surlignage, exposant/indice, alignements, retraits, interlignes, listes à puces
 * et numérotées, sauts de page, tableaux, images. Le texte du PDF reste sélectionnable.
 * Non pris en charge : en-têtes/pieds de page, notes, zones de texte, formes, colonnes.
 */
object DocxConverter {

    fun convert(input: File, output: File) {
        val zip = try {
            ZipFile(input)
        } catch (_: Exception) {
            throw UserMessageException("Ce fichier n'est pas un document Word .docx valide (les anciens fichiers .doc ne sont pas pris en charge : enregistrez-le en .docx).")
        }
        zip.use {
            val document = Parser(it).parse()
            Renderer(document).render(output)
        }
    }

    // --- Modèle ------------------------------------------------------------------------------

    class RunProps(
        var bold: Boolean? = null,
        var italic: Boolean? = null,
        var underline: Boolean? = null,
        var strike: Boolean? = null,
        var size: Float? = null,
        var color: Int? = null,
        var highlight: Int? = null,
        var family: String? = null,
        var vertAlign: Int? = null,
        var caps: Boolean? = null,
    ) {
        fun inherit(parent: RunProps?): RunProps {
            if (parent == null) return this
            return RunProps(
                bold ?: parent.bold, italic ?: parent.italic, underline ?: parent.underline,
                strike ?: parent.strike, size ?: parent.size, color ?: parent.color,
                highlight ?: parent.highlight, family ?: parent.family, vertAlign ?: parent.vertAlign,
                caps ?: parent.caps,
            )
        }
    }

    class ParaProps(
        var styleId: String? = null,
        var align: String? = null,
        var spaceBefore: Float? = null,
        var spaceAfter: Float? = null,
        var lineMultiplier: Float? = null,
        var indLeft: Float? = null,
        var indFirst: Float? = null,
        var numId: String? = null,
        var ilvl: Int? = null,
        var pageBreakBefore: Boolean? = null,
    ) {
        fun inherit(parent: ParaProps?): ParaProps {
            if (parent == null) return this
            return ParaProps(
                styleId ?: parent.styleId, align ?: parent.align, spaceBefore ?: parent.spaceBefore,
                spaceAfter ?: parent.spaceAfter, lineMultiplier ?: parent.lineMultiplier,
                indLeft ?: parent.indLeft, indFirst ?: parent.indFirst, numId ?: parent.numId,
                ilvl ?: parent.ilvl, pageBreakBefore ?: parent.pageBreakBefore,
            )
        }
    }

    class Style(val para: ParaProps, val run: RunProps, val basedOn: String?, val name: String?)

    sealed class Inline {
        class Text(val text: String, val props: RunProps, val charStyle: String?) : Inline()
        class Image(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float) : Inline()
        object PageBreak : Inline()
    }

    sealed class Block {
        class Paragraph(val props: ParaProps, val inlines: List<Inline>) : Block()
        class Table(val gridCols: List<Float>, val rows: List<List<Cell>>) : Block()
    }

    class Cell(val span: Int, val fill: Int?, val paragraphs: List<Block.Paragraph>)

    class PageSetup(
        var width: Float = 595f,
        var height: Float = 842f,
        var top: Float = 72f,
        var bottom: Float = 72f,
        var left: Float = 72f,
        var right: Float = 72f,
    )

    class NumLevel(val format: String, val text: String, val indLeft: Float?, val hanging: Float?)

    class Document(
        val blocks: List<Block>,
        val page: PageSetup,
        val styles: Map<String, Style>,
        val defaultPara: ParaProps,
        val defaultRun: RunProps,
        val defaultParaStyle: String?,
        val numbering: Map<String, Map<Int, NumLevel>>,
    )

    // --- Lecture du .docx --------------------------------------------------------------------

    private class Parser(private val zip: ZipFile) {
        private val relationships = HashMap<String, String>()
        private val styles = HashMap<String, Style>()
        private var defaultPara = ParaProps()
        private var defaultRun = RunProps()
        private var defaultParaStyle: String? = null
        private val abstractNums = HashMap<String, Map<Int, NumLevel>>()
        private val numToAbstract = HashMap<String, String>()
        private val page = PageSetup()

        fun parse(): Document {
            readRelationships()
            open("word/styles.xml")?.let { readStyles(it) }
            open("word/numbering.xml")?.let { readNumbering(it) }
            val parser = open("word/document.xml")
                ?: throw UserMessageException("Document Word illisible (contenu introuvable).")
            val blocks = mutableListOf<Block>()
            parser.forEachChild { name ->
                when (name) {
                    "p" -> blocks += readParagraph(parser)
                    "tbl" -> blocks += readTable(parser)
                    "sectPr" -> readSection(parser)
                }
            }
            val numbering = numToAbstract.mapNotNull { (num, abs) -> abstractNums[abs]?.let { num to it } }.toMap()
            return Document(blocks, page, styles, defaultPara, defaultRun, defaultParaStyle, numbering)
        }

        private fun open(path: String): XmlPullParser? {
            val entry = zip.getEntry(path) ?: return null
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(zip.getInputStream(entry), null)
            // Se place sur l'élément racine
            while (parser.next() != XmlPullParser.START_TAG) {
                if (parser.eventType == XmlPullParser.END_DOCUMENT) return null
            }
            return parser
        }

        private fun readRelationships() {
            val parser = open("word/_rels/document.xml.rels") ?: return
            parser.forEachChild { name ->
                if (name == "Relationship") {
                    val id = parser.attr("Id") ?: return@forEachChild
                    val target = parser.attr("Target") ?: return@forEachChild
                    relationships[id] = target
                }
            }
        }

        private fun readStyles(parser: XmlPullParser) {
            parser.forEachChild { name ->
                when (name) {
                    "docDefaults" -> parser.forEachChild { inner ->
                        when (inner) {
                            "rPr" -> defaultRun = readRunProps(parser)
                            "pPr" -> defaultPara = readParaProps(parser)
                        }
                    }
                    "style" -> {
                        val id = parser.attr("styleId") ?: return@forEachChild
                        val isDefault = parser.attr("default").let { it == "1" || it == "true" }
                        val type = parser.attr("type")
                        var para = ParaProps()
                        var run = RunProps()
                        var basedOn: String? = null
                        var styleName: String? = null
                        parser.forEachChild { inner ->
                            when (inner) {
                                "pPr" -> para = readParaProps(parser)
                                "rPr" -> run = readRunProps(parser)
                                "basedOn" -> basedOn = parser.attr("val")
                                "name" -> styleName = parser.attr("val")
                                "tblStylePr" -> parser.skip()
                            }
                        }
                        styles[id] = Style(para, run, basedOn, styleName)
                        if (isDefault && type == "paragraph") defaultParaStyle = id
                    }
                }
            }
        }

        private fun readNumbering(parser: XmlPullParser) {
            parser.forEachChild { name ->
                when (name) {
                    "abstractNum" -> {
                        val id = parser.attr("abstractNumId") ?: return@forEachChild
                        val levels = HashMap<Int, NumLevel>()
                        parser.forEachChild { inner ->
                            if (inner != "lvl") return@forEachChild
                            val ilvl = parser.attr("ilvl")?.toIntOrNull() ?: 0
                            var format = "bullet"
                            var text = "•"
                            var left: Float? = null
                            var hanging: Float? = null
                            parser.forEachChild { prop ->
                                when (prop) {
                                    "numFmt" -> format = parser.attr("val") ?: format
                                    "lvlText" -> text = parser.attr("val") ?: text
                                    "ind" -> {
                                        left = twips(parser.attr("left") ?: parser.attr("start"))
                                        hanging = twips(parser.attr("hanging"))
                                    }
                                }
                            }
                            levels[ilvl] = NumLevel(format, text, left, hanging)
                        }
                        abstractNums[id] = levels
                    }
                    "num" -> {
                        val numId = parser.attr("numId") ?: return@forEachChild
                        parser.forEachChild { inner ->
                            if (inner == "abstractNumId") parser.attr("val")?.let { numToAbstract[numId] = it }
                        }
                    }
                }
            }
        }

        private fun readSection(parser: XmlPullParser) {
            parser.forEachChild { name ->
                when (name) {
                    "pgSz" -> {
                        twips(parser.attr("w"))?.let { page.width = it }
                        twips(parser.attr("h"))?.let { page.height = it }
                    }
                    "pgMar" -> {
                        twips(parser.attr("top"))?.let { page.top = max(18f, it) }
                        twips(parser.attr("bottom"))?.let { page.bottom = max(18f, it) }
                        twips(parser.attr("left"))?.let { page.left = it }
                        twips(parser.attr("right"))?.let { page.right = it }
                    }
                }
            }
        }

        private fun readParaProps(parser: XmlPullParser): ParaProps {
            val props = ParaProps()
            parser.forEachChild { name ->
                when (name) {
                    "pStyle" -> props.styleId = parser.attr("val")
                    "jc" -> props.align = parser.attr("val")
                    "spacing" -> {
                        twips(parser.attr("before"))?.let { props.spaceBefore = it }
                        twips(parser.attr("after"))?.let { props.spaceAfter = it }
                        val line = parser.attr("line")?.toFloatOrNull()
                        val rule = parser.attr("lineRule")
                        if (line != null && (rule == null || rule == "auto")) props.lineMultiplier = line / 240f
                    }
                    "ind" -> {
                        (twips(parser.attr("left")) ?: twips(parser.attr("start")))?.let { props.indLeft = it }
                        twips(parser.attr("firstLine"))?.let { props.indFirst = it }
                        twips(parser.attr("hanging"))?.let { props.indFirst = -it }
                    }
                    "numPr" -> parser.forEachChild { inner ->
                        when (inner) {
                            "numId" -> props.numId = parser.attr("val")
                            "ilvl" -> props.ilvl = parser.attr("val")?.toIntOrNull()
                        }
                    }
                    "pageBreakBefore" -> props.pageBreakBefore = parser.boolVal()
                    "rPr", "tabs", "pBdr", "shd", "sectPr", "framePr" -> parser.skip()
                }
            }
            return props
        }

        private fun readRunProps(parser: XmlPullParser): RunProps {
            val props = RunProps()
            parser.forEachChild { name ->
                when (name) {
                    "b" -> props.bold = parser.boolVal()
                    "i" -> props.italic = parser.boolVal()
                    "u" -> props.underline = parser.attr("val").let { it != null && it != "none" }
                    "strike", "dstrike" -> props.strike = parser.boolVal()
                    "caps", "smallCaps" -> props.caps = parser.boolVal()
                    "sz" -> parser.attr("val")?.toFloatOrNull()?.let { props.size = it / 2f }
                    "color" -> props.color = parseColor(parser.attr("val"))
                    "highlight" -> props.highlight = highlightColor(parser.attr("val"))
                    "shd" -> parseColor(parser.attr("fill"))?.let { props.highlight = it }
                    "vertAlign" -> props.vertAlign = when (parser.attr("val")) {
                        "superscript" -> 1
                        "subscript" -> -1
                        else -> 0
                    }
                    "rFonts" -> props.family = fontFamily(parser.attr("ascii") ?: parser.attr("hAnsi"))
                }
            }
            return props
        }

        private fun readParagraph(parser: XmlPullParser): Block.Paragraph {
            var props = ParaProps()
            val inlines = mutableListOf<Inline>()
            parser.forEachChild { name ->
                when (name) {
                    "pPr" -> props = readParaProps(parser)
                    "r" -> readRun(parser, inlines)
                    "del", "moveFrom" -> parser.skip()
                }
            }
            return Block.Paragraph(props, inlines)
        }

        private fun readRun(parser: XmlPullParser, out: MutableList<Inline>) {
            var props = RunProps()
            var charStyle: String? = null
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    out += Inline.Text(text.toString(), props, charStyle)
                    text.clear()
                }
            }
            parser.forEachChild { name ->
                when (name) {
                    "rPr" -> {
                        // rStyle se trouve dans rPr : lu à part pour garder le style de caractère
                        val depth = parser.depth
                        val p = RunProps()
                        while (true) {
                            val ev = parser.next()
                            if (ev == XmlPullParser.END_DOCUMENT) break
                            if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
                            if (ev != XmlPullParser.START_TAG) continue
                            when (parser.localName()) {
                                "rStyle" -> charStyle = parser.attr("val")
                                "b" -> p.bold = parser.boolVal()
                                "i" -> p.italic = parser.boolVal()
                                "u" -> p.underline = parser.attr("val").let { it != null && it != "none" }
                                "strike", "dstrike" -> p.strike = parser.boolVal()
                                "caps", "smallCaps" -> p.caps = parser.boolVal()
                                "sz" -> parser.attr("val")?.toFloatOrNull()?.let { p.size = it / 2f }
                                "color" -> p.color = parseColor(parser.attr("val"))
                                "highlight" -> p.highlight = highlightColor(parser.attr("val"))
                                "shd" -> parseColor(parser.attr("fill"))?.let { p.highlight = it }
                                "vertAlign" -> p.vertAlign = when (parser.attr("val")) {
                                    "superscript" -> 1
                                    "subscript" -> -1
                                    else -> 0
                                }
                                "rFonts" -> p.family = fontFamily(parser.attr("ascii") ?: parser.attr("hAnsi"))
                            }
                        }
                        props = p
                    }
                    "t" -> text.append(parser.nextText())
                    "tab", "ptab" -> text.append('\t')
                    "br" -> if (parser.attr("type") == "page") {
                        flush()
                        out += Inline.PageBreak
                    } else {
                        text.append('\n')
                    }
                    "cr" -> text.append('\n')
                    "noBreakHyphen" -> text.append('-')
                    "sym" -> text.append(' ')
                    "drawing" -> {
                        flush()
                        readDrawing(parser)?.let { out += it }
                    }
                    "pict" -> {
                        flush()
                        readPict(parser)?.let { out += it }
                    }
                    // Versions de repli des contenus « AlternateContent » : déjà lues via Choice
                    "Fallback", "instrText", "delText", "footnoteReference", "endnoteReference" -> parser.skip()
                }
            }
            flush()
        }

        private fun readDrawing(parser: XmlPullParser): Inline.Image? {
            var cx = 0f
            var cy = 0f
            var embed: String? = null
            parser.forEachChild { name ->
                when (name) {
                    "extent" -> if (cx == 0f) {
                        cx = parser.attr("cx")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 0f
                        cy = parser.attr("cy")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 0f
                    }
                    "blip" -> if (embed == null) embed = parser.attr("embed")
                    "txbxContent" -> parser.skip()
                }
            }
            return image(embed, cx, cy)
        }

        private fun readPict(parser: XmlPullParser): Inline.Image? {
            var id: String? = null
            parser.forEachChild { name ->
                when (name) {
                    "imagedata" -> if (id == null) id = parser.attr("id")
                    "textbox" -> parser.skip()
                }
            }
            return image(id, 0f, 0f)
        }

        private fun image(relId: String?, widthPt: Float, heightPt: Float): Inline.Image? {
            val target = relationships[relId ?: return null] ?: return null
            val path = normalizePath("word/$target")
            val entry = zip.getEntry(path) ?: return null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null // EMF/WMF… non décodables
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val w = if (widthPt > 0) widthPt else bounds.outWidth * 0.75f
            val h = if (heightPt > 0) heightPt else bounds.outHeight * 0.75f
            return Inline.Image(bitmap, w, h)
        }

        private fun readTable(parser: XmlPullParser): Block.Table {
            val grid = mutableListOf<Float>()
            val rows = mutableListOf<List<Cell>>()
            parser.forEachChild { name ->
                when (name) {
                    "tblGrid" -> parser.forEachChild { inner ->
                        if (inner == "gridCol") grid += twips(parser.attr("w")) ?: 0f
                    }
                    "tr" -> {
                        val cells = mutableListOf<Cell>()
                        parser.forEachChild { inner ->
                            when (inner) {
                                "tc" -> cells += readCell(parser)
                                "trPr", "tblPrEx" -> parser.skip()
                            }
                        }
                        rows += cells
                    }
                    "tblPr" -> parser.skip()
                }
            }
            return Block.Table(grid, rows)
        }

        private fun readCell(parser: XmlPullParser): Cell {
            var span = 1
            var fill: Int? = null
            val paragraphs = mutableListOf<Block.Paragraph>()
            parser.forEachChild { name ->
                when (name) {
                    "tcPr" -> parser.forEachChild { inner ->
                        when (inner) {
                            "gridSpan" -> span = parser.attr("val")?.toIntOrNull() ?: 1
                            "shd" -> fill = parseColor(parser.attr("fill"))
                        }
                    }
                    "p" -> paragraphs += readParagraph(parser)
                    // Tableau imbriqué : son contenu est aplati dans la cellule
                    "tbl" -> readTable(parser).rows.flatten().forEach { paragraphs += it.paragraphs }
                }
            }
            return Cell(span, fill, paragraphs)
        }

        private fun normalizePath(path: String): String {
            val parts = ArrayDeque<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(part)
                }
            }
            return parts.joinToString("/")
        }
    }

    // --- Mise en page ------------------------------------------------------------------------

    private class Renderer(private val doc: Document) {
        private val pdf = PdfDocument()
        private val setup = doc.page
        private val contentWidth = setup.width - setup.left - setup.right
        private var page: PdfDocument.Page? = null
        private var y = 0f
        private var pageNumber = 0
        private val counters = HashMap<String, IntArray>()
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = Color.rgb(120, 120, 120)
        }
        private val fillPaint = Paint()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun render(output: File) {
            try {
                for (block in doc.blocks) {
                    when (block) {
                        is Block.Paragraph -> paragraph(block)
                        is Block.Table -> table(block)
                    }
                }
                ensurePage()
                page?.let { pdf.finishPage(it) }
                FileOutputStream(output).use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
        }

        private val canvas: Canvas get() = ensurePage().canvas
        private val bottomLimit get() = setup.height - setup.bottom
        private val atTop get() = y <= setup.top + 0.5f

        private fun ensurePage(): PdfDocument.Page =
            page ?: pdf.startPage(PdfDocument.PageInfo.Builder(setup.width.toInt(), setup.height.toInt(), ++pageNumber).create())
                .also {
                    page = it
                    y = setup.top
                }

        private fun newPage() {
            page?.let { pdf.finishPage(it) }
            page = null
            ensurePage()
        }

        // Propriétés effectives : paragraphe → style (et ses parents) → style par défaut → docDefaults
        private fun styleChain(id: String?): List<Style> {
            val chain = mutableListOf<Style>()
            var current = id
            var guard = 0
            while (current != null && guard++ < 20) {
                val style = doc.styles[current] ?: break
                chain += style
                current = style.basedOn
            }
            return chain
        }

        private fun effectivePara(own: ParaProps): ParaProps {
            var props = own
            for (s in styleChain(own.styleId ?: doc.defaultParaStyle)) props = props.inherit(s.para)
            return props.inherit(doc.defaultPara)
        }

        private fun effectiveRun(own: RunProps, charStyle: String?, paraStyle: String?): RunProps {
            var props = own
            for (s in styleChain(charStyle)) props = props.inherit(s.run)
            for (s in styleChain(paraStyle ?: doc.defaultParaStyle)) props = props.inherit(s.run)
            props = props.inherit(doc.defaultRun)
            if (props.size == null) props.size = headingSize(paraStyle) ?: 11f
            return props
        }

        /** Repli pour les documents sans styles.xml détaillé. */
        private fun headingSize(styleId: String?): Float? {
            val name = (doc.styles[styleId ?: return null]?.name ?: styleId).lowercase(Locale.ROOT)
            return when {
                name == "title" || name == "titre" -> 26f
                name.endsWith("1") && (name.contains("heading") || name.contains("titre")) -> 20f
                name.endsWith("2") && (name.contains("heading") || name.contains("titre")) -> 16f
                name.endsWith("3") && (name.contains("heading") || name.contains("titre")) -> 14f
                else -> null
            }
        }

        private fun paragraph(block: Block.Paragraph, width: Float = contentWidth) {
            val props = effectivePara(block.props)
            if (props.pageBreakBefore == true && !atTop) newPage()

            var segment = mutableListOf<Inline.Text>()
            var first = true
            fun flushText() {
                if (segment.isEmpty() && !first) return
                val layout = buildLayout(segment, props, block.props.styleId ?: doc.defaultParaStyle, width, first)
                drawLayout(layout, props, first)
                segment = mutableListOf()
                first = false
            }
            for (inline in block.inlines) {
                when (inline) {
                    is Inline.Text -> segment += inline
                    is Inline.Image -> {
                        if (segment.isNotEmpty()) flushText()
                        drawImage(inline, props, width)
                        first = false
                    }
                    Inline.PageBreak -> {
                        if (segment.isNotEmpty()) flushText()
                        newPage()
                        first = false
                    }
                }
            }
            if (segment.isNotEmpty() || first) flushText()
            y += props.spaceAfter ?: 0f
        }

        private fun listLabel(props: ParaProps): String? {
            val numId = props.numId ?: return null
            if (numId == "0") return null
            val levels = doc.numbering[numId] ?: return "•"
            val ilvl = (props.ilvl ?: 0).coerceIn(0, 8)
            val counts = counters.getOrPut(numId) { IntArray(9) }
            counts[ilvl]++
            for (i in ilvl + 1 until 9) counts[i] = 0
            val level = levels[ilvl] ?: return "•"
            if (level.format == "bullet") return bulletChar(level.text)
            if (level.format == "none") return null
            var label = level.text
            for (i in 0..ilvl) {
                val fmt = levels[i]?.format ?: "decimal"
                label = label.replace("%${i + 1}", formatNumber(max(1, counts[i]), fmt))
            }
            return label
        }

        private fun buildLayout(
            texts: List<Inline.Text>,
            props: ParaProps,
            paraStyle: String?,
            width: Float,
            firstSegment: Boolean,
        ): StaticLayout {
            val sb = SpannableStringBuilder()
            var baseSize = 11f
            val label = if (firstSegment) listLabel(props) else null
            val level = props.numId?.let { doc.numbering[it]?.get(props.ilvl ?: 0) }
            var left = props.indLeft ?: level?.indLeft ?: if (label != null) 18f * ((props.ilvl ?: 0) + 1) else 0f
            var firstLine = props.indFirst ?: level?.hanging?.let { -it } ?: if (label != null) -14f else 0f
            left = left.coerceIn(0f, width * 0.6f)
            firstLine = firstLine.coerceIn(-left, width * 0.5f)

            val firstRun = texts.firstOrNull()?.let { effectiveRun(it.props, it.charStyle, paraStyle) }
                ?: effectiveRun(RunProps(), null, paraStyle)
            if (label != null) {
                val start = sb.length
                sb.append(label).append('\t')
                applyRun(sb, start, sb.length, firstRun.inherit(null).also { it.underline = false; it.strike = false })
            }
            for (text in texts) {
                val run = effectiveRun(text.props, text.charStyle, paraStyle)
                val start = sb.length
                sb.append(if (run.caps == true) text.text.uppercase(Locale.getDefault()) else text.text)
                applyRun(sb, start, sb.length, run)
                baseSize = max(baseSize, run.size ?: 11f)
            }
            if (sb.isEmpty()) {
                // Paragraphe vide : garde la hauteur d'une ligne
                sb.append(' ')
                sb.setSpan(AbsoluteSizeSpan(firstRun.size?.toInt() ?: 11), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (left > 0f || firstLine != 0f) {
                sb.setSpan(
                    LeadingMarginSpan.Standard((left + firstLine).toInt(), left.toInt()),
                    0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                )
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = firstRun.size ?: 11f
                color = Color.BLACK
            }
            val alignment = when (props.align) {
                "center" -> Layout.Alignment.ALIGN_CENTER
                "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val builder = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, max(1, width.toInt()))
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(0f, (props.lineMultiplier ?: 1f).coerceIn(0.8f, 3f))
            if (props.align == "both" || props.align == "distribute") {
                builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            }
            return builder.build()
        }

        private fun applyRun(sb: SpannableStringBuilder, start: Int, end: Int, run: RunProps) {
            if (start >= end) return
            fun span(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val style = when {
                run.bold == true && run.italic == true -> Typeface.BOLD_ITALIC
                run.bold == true -> Typeface.BOLD
                run.italic == true -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) span(StyleSpan(style))
            if (run.underline == true) span(UnderlineSpan())
            if (run.strike == true) span(StrikethroughSpan())
            run.size?.let { span(AbsoluteSizeSpan(it.toInt().coerceAtLeast(1))) }
            run.color?.let { span(ForegroundColorSpan(it)) }
            run.highlight?.let { span(BackgroundColorSpan(it)) }
            run.family?.let { span(TypefaceSpan(it)) }
            when (run.vertAlign) {
                1 -> {
                    span(SuperscriptSpan())
                    span(RelativeSizeSpan(0.7f))
                }
                -1 -> {
                    span(SubscriptSpan())
                    span(RelativeSizeSpan(0.7f))
                }
            }
        }

        /** Dessine un paragraphe, en le coupant entre deux pages si nécessaire. */
        private fun drawLayout(layout: StaticLayout, props: ParaProps, applyBefore: Boolean, x: Float = setup.left) {
            if (applyBefore && !atTop) y += props.spaceBefore ?: 0f
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && y + (layout.getLineBottom(end) - top) <= bottomLimit) end++
                if (end == line) {
                    if (!atTop) {
                        newPage()
                        continue
                    }
                    end = line + 1 // ligne plus haute que la page : dessinée quand même
                }
                val bottom = layout.getLineBottom(end - 1)
                val c = canvas
                c.save()
                c.translate(x, y - top)
                c.clipRect(0f, top.toFloat(), layout.width.toFloat(), bottom.toFloat())
                layout.draw(c)
                c.restore()
                y += bottom - top
                line = end
                if (line < layout.lineCount) newPage()
            }
        }

        private fun drawImage(image: Inline.Image, props: ParaProps, width: Float, x0: Float = setup.left) {
            var w = image.widthPt
            var h = image.heightPt
            val maxH = setup.height - setup.top - setup.bottom
            val scale = min(1f, min(width / w, maxH / h))
            w *= scale
            h *= scale
            if (y + h > bottomLimit && !atTop) newPage()
            val x = when (props.align) {
                "center" -> x0 + (width - w) / 2f
                "right", "end" -> x0 + width - w
                else -> x0
            }
            canvas.drawBitmap(image.bitmap, null, RectF(x, y, x + w, y + h), bitmapPaint)
            y += h
        }

        private fun table(table: Block.Table) {
            val columns = max(table.gridCols.size, table.rows.maxOfOrNull { row -> row.sumOf { it.span } } ?: 1)
            val grid = if (table.gridCols.size == columns && table.gridCols.sum() > 0f) {
                val total = table.gridCols.sum()
                val factor = if (total > contentWidth) contentWidth / total else 1f
                table.gridCols.map { it * factor }
            } else {
                List(columns) { contentWidth / columns }
            }
            val pad = 4f
            for (row in table.rows) {
                var col = 0
                val cells = row.map { cell ->
                    val x = grid.take(col).sum()
                    val w = grid.drop(col).take(cell.span).sum().coerceAtLeast(20f)
                    col += cell.span
                    val layouts = cell.paragraphs.map { p ->
                        val props = effectivePara(p.props)
                        val texts = p.inlines.filterIsInstance<Inline.Text>()
                        props to buildLayout(texts, props, p.props.styleId ?: doc.defaultParaStyle, w - 2 * pad, true)
                    }
                    Triple(cell, x to w, layouts)
                }
                val rowHeight = cells.maxOfOrNull { (_, _, layouts) ->
                    layouts.sumOf { (props, l) -> (l.height + (props.spaceAfter ?: 0f).coerceAtMost(6f)).toDouble() }.toFloat()
                }?.plus(2 * pad) ?: 0f
                if (y + rowHeight > bottomLimit && !atTop) newPage()
                val c = canvas
                for ((cell, geometry, layouts) in cells) {
                    val (x, w) = geometry
                    val rect = RectF(setup.left + x, y, setup.left + x + w, y + rowHeight)
                    cell.fill?.let {
                        fillPaint.color = it
                        c.drawRect(rect, fillPaint)
                    }
                    c.drawRect(rect, borderPaint)
                    var cy = y + pad
                    for ((props, layout) in layouts) {
                        c.save()
                        c.translate(rect.left + pad, cy)
                        c.clipRect(0f, 0f, layout.width.toFloat(), min(layout.height.toFloat(), rect.bottom - cy))
                        layout.draw(c)
                        c.restore()
                        cy += layout.height + (props.spaceAfter ?: 0f).coerceAtMost(6f)
                    }
                }
                y += rowHeight
            }
            y += 6f
        }
    }

    // --- Utilitaires -------------------------------------------------------------------------

    private const val EMU_PER_PT = 12700f

    private fun twips(value: String?): Float? = value?.toFloatOrNull()?.div(20f)

    private fun parseColor(value: String?): Int? {
        if (value == null || value == "auto" || value.length != 6) return null
        return value.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }

    private fun highlightColor(value: String?): Int? = when (value) {
        "yellow" -> Color.rgb(255, 255, 0)
        "green" -> Color.rgb(0, 255, 0)
        "cyan" -> Color.rgb(0, 255, 255)
        "magenta" -> Color.rgb(255, 0, 255)
        "blue" -> Color.rgb(0, 0, 255)
        "red" -> Color.rgb(255, 0, 0)
        "lightGray" -> Color.rgb(211, 211, 211)
        "darkGray" -> Color.rgb(169, 169, 169)
        else -> null
    }

    private fun fontFamily(name: String?): String? {
        val n = name?.lowercase(Locale.ROOT) ?: return null
        return when {
            listOf("courier", "consolas", "mono").any { n.contains(it) } -> "monospace"
            listOf("times", "cambria", "georgia", "garamond", "serif", "book").any { n.contains(it) } &&
                !n.contains("sans") -> "serif"
            else -> "sans-serif"
        }
    }

    private fun bulletChar(text: String): String {
        val c = text.firstOrNull() ?: return "•"
        return when {
            c == 'o' -> "◦"
            c == '§' || c == '' -> "▪"
            c == '' || c == '' -> "➢"
            c == '' -> "✓"
            c == '-' || c == '–' -> "–"
            c.code in 0xF000..0xF0FF -> "•" // police Symbol/Wingdings
            else -> text
        }
    }

    private fun formatNumber(n: Int, format: String): String = when (format) {
        "lowerLetter" -> letters(n).lowercase(Locale.ROOT)
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase(Locale.ROOT)
        "upperRoman" -> roman(n)
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            x--
            sb.insert(0, 'A' + x % 26)
            x /= 26
        }
        return sb.toString()
    }

    private fun roman(n: Int): String {
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in values.indices) while (x >= values[i]) {
            sb.append(symbols[i])
            x -= values[i]
        }
        return sb.toString()
    }

    // --- Aides XmlPullParser (espaces de noms désactivés : « w:p » → « p ») -----------------

    private fun XmlPullParser.localName(): String = name.substringAfter(':')

    private fun XmlPullParser.attr(local: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i).substringAfter(':') == local) return getAttributeValue(i)
        }
        return null
    }

    private fun XmlPullParser.boolVal(): Boolean = when (attr("val")) {
        null, "1", "true", "on" -> true
        else -> false
    }

    /**
     * Parcourt les descendants de l'élément courant. Les éléments non consommés par [block]
     * sont traversés (leurs enfants sont visités), ce qui gère les conteneurs comme
     * w:hyperlink, w:ins, w:sdt ou w:smartTag.
     */
    private inline fun XmlPullParser.forEachChild(block: (String) -> Unit) {
        val depth = this.depth
        while (true) {
            val event = next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && this.depth == depth) return
            if (event == XmlPullParser.START_TAG) block(localName())
        }
    }

    private fun XmlPullParser.skip() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
