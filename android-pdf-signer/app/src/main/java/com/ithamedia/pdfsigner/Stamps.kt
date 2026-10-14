package com.ithamedia.pdfsigner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Petits signes de remplissage : coche, croix, point, trait. */
object Glyphs {
    enum class Kind { CHECK, CROSS, DOT, LINE }

    fun render(kind: Kind, color: Int): Bitmap {
        val w = if (kind == Kind.LINE) 480 else 160
        val h = if (kind == Kind.LINE) 40 else 160
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 18f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (kind) {
            Kind.CHECK -> c.drawPath(Path().apply {
                moveTo(28f, 86f)
                lineTo(64f, 124f)
                lineTo(134f, 38f)
            }, paint)
            Kind.CROSS -> {
                c.drawLine(36f, 36f, 124f, 124f, paint)
                c.drawLine(124f, 36f, 36f, 124f, paint)
            }
            Kind.DOT -> c.drawCircle(80f, 80f, 40f, paint.apply { style = Paint.Style.FILL })
            Kind.LINE -> c.drawLine(14f, 20f, w - 14f, 20f, paint.apply { strokeWidth = 8f })
        }
        return bmp
    }
}

/** Tampons encreurs : cadre double, capitales espacées, date facultative. */
object StampRenderer {

    class Preset(val label: String, val colorRes: Int)

    val presets = listOf(
        Preset("APPROUVÉ", R.color.stamp_green),
        Preset("PAYÉ", R.color.stamp_green),
        Preset("REÇU", R.color.stamp_blue),
        Preset("VU", R.color.stamp_blue),
        Preset("CONFIDENTIEL", R.color.stamp_red),
        Preset("URGENT", R.color.stamp_red),
        Preset("REFUSÉ", R.color.stamp_red),
        Preset("ANNULÉ", R.color.stamp_red),
        Preset("BROUILLON", R.color.ink_black),
        Preset("COPIE", R.color.ink_black),
        Preset("FINAL", R.color.ink_black),
        Preset("À SIGNER", R.color.stamp_red),
    )

    fun render(label: String, color: Int, withDate: Boolean): Bitmap {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 64f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.14f
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 26f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
        }
        val date = SimpleDateFormat("dd MMM yyyy", Locale.FRANCE).format(Date()).uppercase(Locale.FRANCE)
        val textW = text.measureText(label)
        val dateW = if (withDate) small.measureText(date) else 0f
        val padX = 40f
        val w = (maxOf(textW, dateW) + padX * 2).toInt()
        val h = if (withDate) 150 else 112
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
        }
        frame.strokeWidth = 6f
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 16f, 16f, frame)
        frame.strokeWidth = 2f
        c.drawRoundRect(RectF(14f, 14f, w - 14f, h - 14f), 10f, 10f, frame)
        val baseline = if (withDate) 82f else 78f
        c.drawText(label, (w - textW) / 2f, baseline, text)
        if (withDate) c.drawText(date, (w - dateW) / 2f, 122f, small)
        // Légère transparence : rendu « encre » plutôt qu'aplat
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply { alpha = 225 })
        bmp.recycle()
        return out
    }
}
