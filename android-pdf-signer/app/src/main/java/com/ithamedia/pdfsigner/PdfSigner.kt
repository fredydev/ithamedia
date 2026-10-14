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
                val mapper = PageMapper(page)
                val dispW = mapper.width
                val dispH = mapper.height
                fun toPdf(dx: Float, dy: Float) = mapper.toPdf(dx, dy)

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
