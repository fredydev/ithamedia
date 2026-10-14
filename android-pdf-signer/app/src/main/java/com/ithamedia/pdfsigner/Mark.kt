package com.ithamedia.pdfsigner

import android.graphics.PointF

enum class InkTool { PEN, HIGHLIGHTER, RECT, OVAL, LINE, ARROW, TEXT, NOTE, REDACT }

/**
 * Une annotation dessinée sur une page. Les points sont normalisés (0..1) sur la page affichée ;
 * [width] et [textSize] sont des fractions de la largeur de page.
 */
class Mark(
    val tool: InkTool,
    val color: Int,
    val width: Float,
    val points: MutableList<PointF> = mutableListOf(),
    var text: String? = null,
    var textSize: Float = 0.022f,
)
