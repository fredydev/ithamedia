package com.ithamedia.pdfsigner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Page PDF sur laquelle on dessine des annotations (voir [Mark]). */
class AnnotationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var pageBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var marks: MutableList<Mark> = mutableListOf()
        set(value) {
            field = value
            invalidate()
        }

    /** Outil actif ; null = gomme. */
    var tool: InkTool? = InkTool.PEN
    var color: Int = Color.BLACK
    var strokeFraction: Float = 0.004f

    var onMarkAdded: ((Mark) -> Unit)? = null
    var onMarkErased: ((Mark, Int) -> Unit)? = null
    /** Outils Texte et Note : demande le texte pour le point touché (normalisé). */
    var onTextRequested: ((InkTool, PointF) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val pageRect = RectF()
    private var current: Mark? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint().apply { color = Color.argb(30, 0, 0, 0) }

    private fun computeRect() {
        val bmp = pageBitmap ?: return
        val m = 12 * density
        val s = min((width - 2 * m) / bmp.width, (height - 2 * m) / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        pageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun sx(nx: Float) = pageRect.left + nx * pageRect.width()
    private fun sy(ny: Float) = pageRect.top + ny * pageRect.height()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = pageBitmap ?: return
        computeRect()
        canvas.drawRect(pageRect.left, pageRect.top + 2 * density, pageRect.right, pageRect.bottom + 2 * density, shadow)
        canvas.drawBitmap(bmp, null, pageRect, bitmapPaint)
        canvas.save()
        canvas.clipRect(pageRect)
        marks.forEach { draw(canvas, it) }
        current?.let { draw(canvas, it) }
        canvas.restore()
    }

    private fun draw(canvas: Canvas, mark: Mark) {
        val pts = mark.points
        if (pts.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mark.color
            style = Paint.Style.STROKE
            strokeWidth = max(1f, mark.width * pageRect.width())
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (mark.tool) {
            InkTool.PEN, InkTool.HIGHLIGHTER -> {
                if (mark.tool == InkTool.HIGHLIGHTER) paint.alpha = 100
                val path = Path()
                path.moveTo(sx(pts[0].x), sy(pts[0].y))
                if (pts.size == 1) path.lineTo(sx(pts[0].x) + 0.1f, sy(pts[0].y))
                for (i in 1 until pts.size) path.lineTo(sx(pts[i].x), sy(pts[i].y))
                canvas.drawPath(path, paint)
            }
            InkTool.RECT, InkTool.OVAL, InkTool.LINE, InkTool.ARROW, InkTool.REDACT -> {
                if (pts.size < 2) return
                val x0 = sx(pts[0].x)
                val y0 = sy(pts[0].y)
                val x1 = sx(pts[1].x)
                val y1 = sy(pts[1].y)
                val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
                when (mark.tool) {
                    InkTool.RECT -> canvas.drawRect(r, paint)
                    InkTool.OVAL -> canvas.drawOval(r, paint)
                    InkTool.REDACT -> {
                        canvas.drawRect(r, Paint().apply { color = Color.BLACK })
                        canvas.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = 1.5f * density
                            color = context.getColor(R.color.accent)
                        })
                    }
                    else -> {
                        canvas.drawLine(x0, y0, x1, y1, paint)
                        if (mark.tool == InkTool.ARROW) {
                            val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                            val len = max(10 * density, paint.strokeWidth * 5)
                            for (side in listOf(-1, 1)) {
                                val a = angle + Math.PI + side * Math.PI / 7
                                canvas.drawLine(x1, y1, x1 + (cos(a) * len).toFloat(), y1 + (sin(a) * len).toFloat(), paint)
                            }
                        }
                    }
                }
            }
            InkTool.TEXT -> {
                val text = mark.text ?: return
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mark.color
                    textSize = mark.textSize * pageRect.width()
                }
                text.split('\n').forEachIndexed { i, line ->
                    canvas.drawText(line, sx(pts[0].x), sy(pts[0].y) + tp.textSize * (0.9f + i * 1.2f), tp)
                }
            }
            InkTool.NOTE -> {
                val s = 16f / 595f * pageRect.width()
                val x = sx(pts[0].x)
                val y = sy(pts[0].y)
                canvas.drawRect(x, y, x + s, y + s, Paint().apply { color = Color.rgb(255, 212, 64) })
                canvas.drawRect(x, y, x + s, y + s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = Color.rgb(140, 107, 13)
                    strokeWidth = 1f * density
                })
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageBitmap == null || pageRect.width() <= 0) return false
        val nx = ((event.x - pageRect.left) / pageRect.width()).coerceIn(0f, 1f)
        val ny = ((event.y - pageRect.top) / pageRect.height()).coerceIn(0f, 1f)
        val active = tool
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                when (active) {
                    null -> erase(event.x, event.y)
                    InkTool.TEXT, InkTool.NOTE -> Unit
                    else -> {
                        val width = if (active == InkTool.HIGHLIGHTER) strokeFraction * 4 else strokeFraction
                        current = Mark(active, if (active == InkTool.REDACT) Color.BLACK else color, width, mutableListOf(PointF(nx, ny), PointF(nx, ny)))
                        if (active == InkTool.PEN || active == InkTool.HIGHLIGHTER) current?.points?.removeAt(1)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val mark = current ?: if (active == null) {
                    erase(event.x, event.y)
                    return true
                } else {
                    return true
                }
                if (mark.tool == InkTool.PEN || mark.tool == InkTool.HIGHLIGHTER) {
                    for (i in 0 until event.historySize) {
                        mark.points += PointF(
                            ((event.getHistoricalX(i) - pageRect.left) / pageRect.width()).coerceIn(0f, 1f),
                            ((event.getHistoricalY(i) - pageRect.top) / pageRect.height()).coerceIn(0f, 1f),
                        )
                    }
                    mark.points += PointF(nx, ny)
                } else {
                    mark.points[1] = PointF(nx, ny)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (active == InkTool.TEXT || active == InkTool.NOTE) {
                    onTextRequested?.invoke(active, PointF(nx, ny))
                }
                current?.let { mark ->
                    current = null
                    val tiny = mark.points.size >= 2 && mark.tool != InkTool.PEN && mark.tool != InkTool.HIGHLIGHTER &&
                        abs(mark.points[0].x - mark.points[1].x) < 0.01f && abs(mark.points[0].y - mark.points[1].y) < 0.01f
                    if (!tiny) {
                        marks += mark
                        onMarkAdded?.invoke(mark)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }

    fun addMark(mark: Mark) {
        marks += mark
        onMarkAdded?.invoke(mark)
        invalidate()
    }

    private fun erase(x: Float, y: Float) {
        val tolerance = 18 * density
        val index = marks.indexOfLast { bounds(it).apply { inset(-tolerance, -tolerance) }.contains(x, y) }
        if (index < 0) return
        val mark = marks.removeAt(index)
        onMarkErased?.invoke(mark, index)
        invalidate()
    }

    private fun bounds(mark: Mark): RectF {
        val xs = mark.points.map { sx(it.x) }
        val ys = mark.points.map { sy(it.y) }
        val r = RectF(xs.min(), ys.min(), xs.max(), ys.max())
        if (mark.tool == InkTool.TEXT) {
            val size = mark.textSize * pageRect.width()
            val lines = mark.text?.split('\n') ?: emptyList()
            r.right += (lines.maxOfOrNull { it.length } ?: 1) * size * 0.55f
            r.bottom += lines.size * size * 1.2f
        }
        if (mark.tool == InkTool.NOTE) {
            r.right += 16f / 595f * pageRect.width()
            r.bottom += 16f / 595f * pageRect.width()
        }
        return r
    }
}
