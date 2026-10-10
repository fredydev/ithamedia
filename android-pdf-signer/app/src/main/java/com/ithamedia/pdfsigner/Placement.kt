package com.ithamedia.pdfsigner

import android.graphics.Bitmap

/**
 * Signature posée sur une page. Les coordonnées sont des fractions (0..1) de la page
 * telle qu'elle est affichée (origine en haut à gauche), indépendantes du zoom.
 */
class Placement(
    val bitmap: Bitmap,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
)
