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

    // --- Compresser --------------------------------------------------------------------------

    /**
     * Niveau de compression. Seules les images sont recompressées : texte et dessins
     * vectoriels restent intacts, donc parfaitement nets.
     */
    enum class Level(val maxDpi: Int, val jpegQuality: Float, val recompressJpeg: Boolean) {
        /** Ne touche qu'aux images surdimensionnées, ré-encodées en haute qualité. */
        BEST(220, 0.88f, false),
        BALANCED(150, 0.78f, true),
        STRONG(110, 0.65f, true),
    }

    data class CompressStats(val imagesOptimized: Int)

    fun compress(source: File, output: File, level: Level): CompressStats {
        var optimized = 0
        PDDocument.load(source).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
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
                            val smaller = recompress(doc, xObject, page, level) ?: continue
                            replacements[key] = smaller
                            resources.put(name, smaller)
                            optimized++
                        }
                        is PDFormXObject -> if (visited.add(key)) process(xObject.resources, page)
                    }
                }
            }

            for (page in doc.pages) process(page.resources, page)
            doc.save(output)
        }
        return CompressStats(optimized)
    }

    private fun recompress(doc: PDDocument, image: PDImageXObject, page: PDPage, level: Level): PDImageXObject? {
        val stream = image.cosObject
        // Transparence, masques et CMJN : laissés tels quels pour ne rien dégrader
        if (image.isStencil || stream.containsKey(COSName.SMASK) || stream.containsKey(COSName.MASK)) return null
        if (image.bitsPerComponent == 1) return null
        val components = runCatching { image.colorSpace.numberOfComponents }.getOrDefault(0)
        if (components !in setOf(1, 3)) return null
        val originalSize = stream.length
        if (originalSize < 24 * 1024) return null

        // Hypothèse prudente : l'image peut occuper toute la page
        val box = page.mediaBox
        val pageInches = max(box.width, box.height) / 72f
        val maxPixels = (pageInches * level.maxDpi).roundToInt()
        val longSide = max(image.width, image.height)
        val needsDownscale = longSide > maxPixels * 1.1f
        if (!needsDownscale && image.suffix == "jpg" && !level.recompressJpeg) return null

        return try {
            var bitmap = image.image ?: return null
            if (needsDownscale) {
                val scale = maxPixels.toFloat() / longSide
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
            if (candidate.cosObject.length < originalSize * 0.9) candidate else null
        } catch (_: Throwable) {
            null
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
