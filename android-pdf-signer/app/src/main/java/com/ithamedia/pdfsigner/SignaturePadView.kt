package com.ithamedia.pdfsigner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Zone de dessin à main levée pour la signature. */
class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    private val strokeWidthPx = 3f * resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = Color.LTGRAY
    }

    var inkColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val baseline = height * 0.75f
        canvas.drawLine(width * 0.08f, baseline, width * 0.92f, baseline, guidePaint)
        strokes.forEach { canvas.drawPath(it, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val path = Path().apply {
                    moveTo(x, y)
                    // Un simple point doit rester visible
                    lineTo(x + 0.1f, y + 0.1f)
                }
                strokes += path
                current = path
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val path = current ?: return true
                for (i in 0 until event.historySize) {
                    addPoint(path, event.getHistoricalX(i), event.getHistoricalY(i))
                }
                addPoint(path, x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.lineTo(x, y)
                current = null
            }
        }
        invalidate()
        return true
    }

    private fun addPoint(path: Path, x: Float, y: Float) {
        if (abs(x - lastX) < 1f && abs(y - lastY) < 1f) return
        // Courbe lissée passant par les milieux des segments
        path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
        lastX = x
        lastY = y
    }

    /**
     * Exporte la signature sur fond transparent, recadrée au plus près du tracé.
     * [scale] augmente la résolution pour un rendu net une fois imprimé.
     */
    fun exportBitmap(scale: Float = 2f): Bitmap? {
        if (strokes.isEmpty()) return null
        val bounds = RectF()
        val tmp = RectF()
        strokes.forEachIndexed { index, path ->
            path.computeBounds(tmp, true)
            if (index == 0) bounds.set(tmp) else bounds.union(tmp)
        }
        val pad = strokeWidthPx * 2
        val left = floor(bounds.left - pad)
        val top = floor(bounds.top - pad)
        val w = ceil((bounds.width() + 2 * pad) * scale).toInt().coerceAtLeast(1)
        val h = ceil((bounds.height() + 2 * pad) * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-left, -top)
        strokes.forEach { canvas.drawPath(it, paint) }
        return bitmap
    }
}
