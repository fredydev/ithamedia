package com.ithamedia.pdfsigner

import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlin.math.atan2

/**
 * Passage des coordonnées « page affichée » (origine en haut à gauche, en points, page
 * tournée selon /Rotate) à l'espace utilisateur PDF.
 */
class PageMapper(page: PDPage) {
    private val box = page.cropBox
    val rotation = ((page.rotation % 360) + 360) % 360
    private val rotated = rotation == 90 || rotation == 270
    val width: Float = if (rotated) box.height else box.width
    val height: Float = if (rotated) box.width else box.height

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

    /** Coordonnées normalisées (0..1) de la page affichée → PDF. */
    fun fromNormalized(nx: Float, ny: Float): FloatArray = toPdf(nx * width, ny * height)

    /** Angle PDF (radians) d'un texte qui doit apparaître incliné de [degrees] à l'écran. */
    fun textAngle(degrees: Float, atX: Float, atY: Float): Double {
        val rad = Math.toRadians(degrees.toDouble())
        val p0 = toPdf(atX, atY)
        val p1 = toPdf(atX + Math.cos(rad).toFloat(), atY - Math.sin(rad).toFloat())
        return atan2((p1[1] - p0[1]).toDouble(), (p1[0] - p0[0]).toDouble())
    }
}
