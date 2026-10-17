package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Opérations PDF : réorganiser, fusionner, compresser, images → PDF. */
object PdfTools {

    // --- Réorganiser -------------------------------------------------------------------------

    /** Une page du résultat : son index dans le document source et la rotation ajoutée (degrés). */
    data class PageSpec(val sourceIndex: Int, val extraRotation: Int)

    fun reorder(source: File, output: File, pages: List<PageSpec>) {
        PDDocument.load(source).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val original = (0 until doc.numberOfPages).map { doc.getPage(it) }
            for (i in original.indices.reversed()) doc.removePage(i)
            for (spec in pages) {
                val page = original[spec.sourceIndex]
                page.rotation = ((page.rotation + spec.extraRotation) % 360 + 360) % 360
                doc.addPage(page)
            }
            doc.save(output)
        }
    }

    // --- Fusionner ---------------------------------------------------------------------------

    fun merge(sources: List<File>, output: File) {
        val merger = PDFMergerUtility()
        sources.forEach { merger.addSource(it) }
        FileOutputStream(output).use { out ->
            merger.destinationStream = out
            merger.mergeDocuments(MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
        }
    }

    // --- Compresser ------------------------------------------------------------------------

    /**
     * Niveau de compression. Seules les images sont recompressées : texte et dessins
     * vectoriels restent intacts, donc parfaitement nets.
     */
    enum class Level(val maxDpi: Int, val jpegQuality: Float, val minGain: Double) {
        /** Images ramenées à 200 ppp à leur taille réelle d'affichage, JPEG très haute qualité. */
        BEST(200, 0.86f, 0.25),
        BALANCED(150, 0.76f, 0.10),
        STRONG(100, 0.62f, 0.05),
    }

    data class CompressStats(val imagesOptimized: Int)

    private val PIECE_INFO: COSName = COSName.getPDFName("PieceInfo")
    private val THUMB: COSName = COSName.getPDFName("Thumb")

    fun compress(source: File, output: File, level: Level): CompressStats {
        var optimized = 0
        PDDocument.load(source).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            // Taille réelle d'affichage de chaque image, lue dans le contenu des pages
            val displayed = ImageSizes().apply { for (page in doc.pages) runCatching { processPage(page) } }.sizes
            val visited: MutableSet<COSBase> = Collections.newSetFromMap(IdentityHashMap())
            val replacements = IdentityHashMap<COSBase, PDImageXObject>()

            fun process(resources: PDResources?, page: PDPage) {
                if (resources == null) return
                for (name in resources.xObjectNames.toList()) {
                    val xObject = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                    val key = xObject.cosObject
                    when (xObject) {
                        is PDImageXObject -> {
                            val done = replacements[key]
                            if (done != null) {
                                resources.put(name, done)
                                continue
                            }
                            if (!visited.add(key)) continue
                            val smaller = recompress(doc, xObject, page, displayed[key], level) ?: continue
                            replacements[key] = smaller
                            resources.put(name, smaller)
                            optimized++
                        }
                        is PDFormXObject -> if (visited.add(key)) process(xObject.resources, page)
                    }
                }
            }

            for (page in doc.pages) {
                process(page.resources, page)
                // Données privées d'Illustrator/Photoshop et vignettes : souvent très lourdes, jamais affichées
                page.cosObject.removeItem(PIECE_INFO)
                page.cosObject.removeItem(THUMB)
                compressContents(doc, page)
            }
            doc.documentCatalog.cosObject.removeItem(PIECE_INFO)
            doc.save(output)
        }
        return CompressStats(optimized)
    }

    /** Recompresse en Flate les flux de contenu de page enregistrés sans compression. */
    private fun compressContents(doc: PDDocument, page: PDPage) {
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
        val streams = when (contents) {
            is com.tom_roush.pdfbox.cos.COSStream -> listOf(contents)
            is com.tom_roush.pdfbox.cos.COSArray -> (0 until contents.size()).mapNotNull { contents.getObject(it) as? com.tom_roush.pdfbox.cos.COSStream }
            else -> emptyList()
        }
        if (streams.isEmpty() || streams.all { it.filters != null }) return
        runCatching {
            val merged = page.contents.use { it.readBytes() }
            val stream = com.tom_roush.pdfbox.pdmodel.common.PDStream(doc, merged.inputStream(), COSName.FLATE_DECODE)
            page.setContents(stream)
        }
    }

    /** Espaces de couleur que le décodeur gère sans approximation (pas de tons directs ni CMJN). */
    private fun safeColorSpace(image: PDImageXObject): Boolean {
        val cs = runCatching { image.colorSpace }.getOrNull() ?: return false
        return when (cs) {
            is com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB,
            is com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray -> true
            is com.tom_roush.pdfbox.pdmodel.graphics.color.PDICCBased -> cs.numberOfComponents == 1 || cs.numberOfComponents == 3
            is com.tom_roush.pdfbox.pdmodel.graphics.color.PDIndexed -> {
                val base = cs.baseColorSpace
                base is com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB ||
                    base is com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray ||
                    (base is com.tom_roush.pdfbox.pdmodel.graphics.color.PDICCBased && base.numberOfComponents in setOf(1, 3))
            }
            else -> false
        }
    }

    private fun recompress(doc: PDDocument, image: PDImageXObject, page: PDPage, shownPts: FloatArray?, level: Level): PDImageXObject? {
        val stream = image.cosObject
        // Transparence, masques, tons directs, CMJN, inversions : laissés tels quels pour ne rien dégrader
        if (image.isStencil || stream.containsKey(COSName.SMASK) || stream.containsKey(COSName.MASK)) return null
        if (stream.containsKey(COSName.DECODE) || !safeColorSpace(image)) return null
        val indexed = runCatching { image.colorSpace }.getOrNull() is com.tom_roush.pdfbox.pdmodel.graphics.color.PDIndexed
        if (image.bitsPerComponent !in setOf(8, 16) && !indexed) return null
        if (image.suffix == "jpx" || image.suffix == "jb2") return null
        val originalSize = stream.length
        if (originalSize < 16 * 1024) return null

        // Pixels utiles : taille d'affichage réelle si connue, sinon page entière (prudent)
        val target = if (shownPts != null) {
            maxOf(shownPts[0] / 72f * level.maxDpi / image.width, shownPts[1] / 72f * level.maxDpi / image.height)
        } else {
            val box = page.mediaBox
            (max(box.width, box.height) / 72f * level.maxDpi) / max(image.width, image.height)
        }
        val scale = target.coerceAtMost(1f)
        val needsDownscale = scale < 0.87f
        if (!needsDownscale && image.suffix == "jpg" && level == Level.BEST) return null

        return try {
            val decoded = image.image ?: return null
            val opaque = flattenOnWhite(decoded)
            if (opaque !== decoded) decoded.recycle()
            if (looksBroken(opaque)) {
                opaque.recycle()
                return null
            }
            var bitmap = opaque
            if (needsDownscale) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            val candidate = JPEGFactory.createFromImage(doc, bitmap, level.jpegQuality)
            bitmap.recycle()
            // On ne garde la nouvelle version que si le gain est réel
            if (candidate.cosObject.length < originalSize * (1 - level.minGain)) candidate else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Pose l'image sur un fond blanc opaque (le JPEG ne connaît pas la transparence, qui sortirait
     * en noir). Gère aussi les images en niveaux de gris stockées en ALPHA_8.
     */
    private fun flattenOnWhite(src: Bitmap): Bitmap {
        if (src.config == Bitmap.Config.ALPHA_8) {
            val w = src.width
            val h = src.height
            val buffer = java.nio.ByteBuffer.allocate(src.rowBytes * h)
            src.copyPixelsToBuffer(buffer)
            val bytes = buffer.array()
            val pixels = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val v = bytes[y * src.rowBytes + x].toInt() and 0xFF
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
        if (!src.hasAlpha() && src.config == Bitmap.Config.ARGB_8888) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Image décodée entièrement noire : signe d'un décodage raté, on garde l'original. */
    private fun looksBroken(bitmap: Bitmap): Boolean {
        val step = max(1, max(bitmap.width, bitmap.height) / 64)
        var total = 0
        var black = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                total++
                if ((c shr 16 and 0xFF) < 12 && (c shr 8 and 0xFF) < 12 && (c and 0xFF) < 12) black++
                x += step
            }
            y += step
        }
        return total > 0 && black > total * 0.97
    }

    /**
     * Parcourt le contenu des pages pour connaître la taille à laquelle chaque image est
     * réellement affichée (en points), y compris dans les objets de formulaire imbriqués.
     */
    private class ImageSizes : com.tom_roush.pdfbox.contentstream.PDFStreamEngine() {
        val sizes = IdentityHashMap<COSBase, FloatArray>()

        init {
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Concatenate())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Save())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Restore())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.DrawObject())
        }

        override fun processOperator(operator: com.tom_roush.pdfbox.contentstream.operator.Operator?, operands: MutableList<COSBase>?) {
            if (operator?.name == "Do") {
                val name = operands?.firstOrNull() as? COSName
                val xObject = name?.let { runCatching { resources?.getXObject(it) }.getOrNull() }
                if (xObject is PDImageXObject) {
                    val m = graphicsState.currentTransformationMatrix
                    val w = kotlin.math.hypot(m.scaleX.toDouble(), m.shearY.toDouble()).toFloat()
                    val h = kotlin.math.hypot(m.shearX.toDouble(), m.scaleY.toDouble()).toFloat()
                    val prev = sizes[xObject.cosObject]
                    sizes[xObject.cosObject] = if (prev == null) floatArrayOf(w, h) else floatArrayOf(max(prev[0], w), max(prev[1], h))
                }
            }
            super.processOperator(operator, operands)
        }
    }

    // --- Images → PDF ------------------------------------------------------------------------

    data class ImagePage(val file: File, val rotation: Int)

    enum class PageFormat { A4, FIT_IMAGE }

    private const val A4_SHORT = 595f
    private const val A4_LONG = 842f
    private const val MAX_IMAGE_SIDE = 2480 // A4 à 300 ppp

    fun imagesToPdf(images: List<ImagePage>, output: File, format: PageFormat, quality: Float = 0.85f) {
        PDDocument().use { doc ->
            for (item in images) {
                val bitmap = ImageUtils.decode(item.file, MAX_IMAGE_SIDE, item.rotation) ?: continue
                val image = JPEGFactory.createFromImage(doc, bitmap, quality)
                val landscape = bitmap.width > bitmap.height
                val (pageW, pageH) = when (format) {
                    PageFormat.A4 -> if (landscape) A4_LONG to A4_SHORT else A4_SHORT to A4_LONG
                    PageFormat.FIT_IMAGE -> {
                        val scale = A4_LONG / max(bitmap.width, bitmap.height)
                        bitmap.width * scale to bitmap.height * scale
                    }
                }
                val scale = min(pageW / bitmap.width, pageH / bitmap.height)
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                bitmap.recycle()

                val page = PDPage(PDRectangle(pageW, pageH))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, (pageW - drawW) / 2f, (pageH - drawH) / 2f, drawW, drawH)
                }
            }
            if (doc.numberOfPages == 0) throw UserMessageException("Aucune image lisible")
            doc.save(output)
        }
    }
}

object ImageUtils {

    /** Décode une image en respectant son orientation EXIF, réduite à [maxSide] pixels au plus. */
    fun decode(file: File, maxSide: Int, extraRotation: Int = 0): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val rotation = (exifRotation(file) + extraRotation) % 360
        val longSide = max(decoded.width, decoded.height)
        val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
        if (rotation == 0 && scale == 1f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
