package com.ithamedia.pdfsigner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Affiche une page PDF rendue en bitmap et les signatures posées dessus.
 * Glisser une signature la déplace, tirer la poignée du coin la redimensionne,
 * un appui long la supprime.
 */
class PdfPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pageBitmap: Bitmap? = null
        set(value) {
            field = value
            selected = null
            invalidate()
        }

    var placements: MutableList<Placement> = mutableListOf()
        set(value) {
            field = value
            selected = null
            invalidate()
        }

    /** Appelé quand une signature est supprimée par appui long. */
    var onPlacementRemoved: ((Placement) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val margin = 12 * density
    private val handleRadius = 11 * density
    private val handleTouchRadius = 28 * density
    private val minSignatureWidth = 40 * density

    private val pageRect = RectF()
    private val tmpRect = RectF()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 0, 0)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.rgb(30, 79, 216)
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 79, 216)
    }

    private var selected: Placement? = null
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Mode { NONE, DRAG, RESIZE }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onLongPress(e: MotionEvent) {
            val hit = hitTest(e.x, e.y) ?: return
            placements.remove(hit)
            if (selected === hit) selected = null
            mode = Mode.NONE
            invalidate()
            onPlacementRemoved?.invoke(hit)
        }
    })

    /** Ajoute une signature centrée sur la page affichée, large de 35 % de la page. */
    fun addPlacement(signature: Bitmap) {
        if (pageBitmap == null) return
        computePageRect()
        val w = 0.35f
        val h = normalizedHeight(signature, w)
        val p = Placement(signature, (1f - w) / 2f, ((1f - h) / 2f).coerceAtLeast(0f), w, h)
        placements += p
        selected = p
        invalidate()
    }

    private fun normalizedHeight(signature: Bitmap, normalizedWidth: Float): Float {
        val aspect = signature.width.toFloat() / signature.height
        // Conserve le ratio de l'image à l'écran (la page affichée garde son propre ratio)
        return normalizedWidth * pageRect.width() / (pageRect.height() * aspect)
    }

    private fun computePageRect() {
        val bmp = pageBitmap ?: return
        val availW = width - 2 * margin
        val availH = height - 2 * margin
        if (availW <= 0 || availH <= 0) return
        val scale = min(availW / bmp.width, availH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        pageRect.set(left, top, left + w, top + h)
    }

    private fun screenRect(p: Placement, out: RectF): RectF {
        out.set(
            pageRect.left + p.x * pageRect.width(),
            pageRect.top + p.y * pageRect.height(),
            pageRect.left + (p.x + p.w) * pageRect.width(),
            pageRect.top + (p.y + p.h) * pageRect.height(),
        )
        return out
    }

    private fun hitTest(x: Float, y: Float): Placement? =
        placements.lastOrNull { screenRect(it, tmpRect).contains(x, y) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computePageRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = pageBitmap ?: return
        computePageRect()

        tmpRect.set(pageRect)
        tmpRect.offset(0f, 2 * density)
        canvas.drawRect(tmpRect, shadowPaint)
        canvas.drawBitmap(bmp, null, pageRect, bitmapPaint)

        for (p in placements) {
            val r = screenRect(p, tmpRect)
            canvas.drawBitmap(p.bitmap, null, r, bitmapPaint)
            if (p === selected) {
                canvas.drawRect(r, framePaint)
                canvas.drawCircle(r.right, r.bottom, handleRadius, handlePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageBitmap == null) return false
        gestures.onTouchEvent(event)
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sel = selected
                mode = if (sel != null && screenRect(sel, tmpRect).let {
                        hypot(x - it.right, y - it.bottom) <= handleTouchRadius
                    }) {
                    Mode.RESIZE
                } else {
                    selected = hitTest(x, y)
                    if (selected != null) Mode.DRAG else Mode.NONE
                }
                if (mode != Mode.NONE) parent?.requestDisallowInterceptTouchEvent(true)
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val p = selected ?: return true
                when (mode) {
                    Mode.DRAG -> {
                        p.x = (p.x + (x - lastX) / pageRect.width()).coerceIn(0f, 1f - p.w)
                        p.y = (p.y + (y - lastY) / pageRect.height()).coerceIn(0f, 1f - p.h)
                    }
                    Mode.RESIZE -> {
                        val left = pageRect.left + p.x * pageRect.width()
                        val maxW = (1f - p.x) * pageRect.width()
                        val newWpx = (x - left).coerceIn(minSignatureWidth, maxW)
                        var nw = newWpx / pageRect.width()
                        var nh = normalizedHeight(p.bitmap, nw)
                        if (p.y + nh > 1f) {
                            nh = 1f - p.y
                            nw = nw * nh / normalizedHeight(p.bitmap, nw)
                        }
                        p.w = nw
                        p.h = nh
                    }
                    Mode.NONE -> Unit
                }
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }
}
