package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil

/** Transforme un texte (date, lieu…) en image transparente, posée ensuite comme une signature. */
object TextStamp {

    // Grande taille de rendu pour rester net une fois redimensionné ou imprimé
    private const val TEXT_SIZE_PX = 72f

    fun render(text: String, color: Int): Bitmap {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = TEXT_SIZE_PX
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val lines = text.lines()
        val width = ceil(lines.maxOf { paint.measureText(it) }).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        val pad = (TEXT_SIZE_PX * 0.15f).toInt()
        val bitmap = Bitmap.createBitmap(width + 2 * pad, layout.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(pad.toFloat(), pad.toFloat())
        layout.draw(canvas)
        return bitmap
    }
}
