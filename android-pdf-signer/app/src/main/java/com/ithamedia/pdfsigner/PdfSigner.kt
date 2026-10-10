package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.OutputStream

/**
 * Appose les signatures (images) sur les pages du PDF d'origine.
 * Le contenu existant n'est pas rasterisé : texte et vecteurs restent intacts.
 */
object PdfSigner {

    fun sign(input: File, output: OutputStream, placementsByPage: Map<Int, List<Placement>>) {
        PDDocument.load(input).use { doc ->
            // Un PDF protégé uniquement par mot de passe propriétaire peut être modifié
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true

            val images = HashMap<Bitmap, PDImageXObject>()
            for ((pageIndex, placements) in placementsByPage) {
                if (placements.isEmpty() || pageIndex >= doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val box = page.cropBox
                val rotation = ((page.rotation % 360) + 360) % 360
                val rotated = rotation == 90 || rotation == 270
                val dispW = if (rotated) box.height else box.width
                val dispH = if (rotated) box.width else box.height

                // Convertit un point de la page affichée (origine en haut à gauche, en points)
                // vers l'espace utilisateur PDF, en tenant compte de /Rotate.
                fun toPdf(dx: Float, dy: Float): FloatArray {
                    val llx = box.lowerLeftX
                    val lly = box.lowerLeftY
                    return when (rotation) {
                        90 -> floatArrayOf(llx + dy, lly + dx)
                        180 -> floatArrayOf(llx + box.width - dx, lly + dy)
                        270 -> floatArrayOf(llx + box.width - dy, lly + box.height - dx)
                        else -> floatArrayOf(llx + dx, lly + box.height - dy)
                    }
                }

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (p in placements) {
                        val image = images.getOrPut(p.bitmap) { LosslessFactory.createFromImage(doc, p.bitmap) }
                        val left = p.x * dispW
                        val top = p.y * dispH
                        val right = left + p.w * dispW
                        val bottom = top + p.h * dispH

                        // L'image occupe le carré unité : (0,0) = bas-gauche, (1,0) = bas-droite, (0,1) = haut-gauche
                        val origin = toPdf(left, bottom)
                        val xAxis = toPdf(right, bottom)
                        val yAxis = toPdf(left, top)
                        cs.drawImage(
                            image,
                            Matrix(
                                xAxis[0] - origin[0], xAxis[1] - origin[1],
                                yAxis[0] - origin[0], yAxis[1] - origin[1],
                                origin[0], origin[1],
                            ),
                        )
                    }
                }
            }
            doc.save(output)
        }
    }
}
