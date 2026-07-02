package fr.veridoc.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Viseur de scan : assombrit l'écran sauf une fenêtre claire (aux coins marqués)
 * qui indique où placer le document — bande MRZ (fenêtre large) ou recto de carte
 * (ratio ID-1) selon le mode.
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Largeur/hauteur de la fenêtre (ex. 4.0 pour une bande MRZ, 1.586 pour une carte ID-1). */
    var windowRatio = 4.0f
        set(value) { field = value; invalidate() }

    /** Largeur de la fenêtre en fraction de la largeur de l'écran. */
    var windowWidthFraction = 0.92f
        set(value) { field = value; invalidate() }

    /** Position verticale du centre de la fenêtre (fraction de la hauteur). */
    var windowVerticalBias = 0.42f
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val window = RectF()

    init {
        // Couche dédiée : nécessaire pour que PorterDuff.CLEAR « perce » réellement
        // le voile (sinon la fenêtre serait noire au lieu de transparente).
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val winW = w * windowWidthFraction
        val winH = winW / windowRatio
        val cx = w / 2f
        val cy = h * windowVerticalBias
        window.set(cx - winW / 2f, cy - winH / 2f, cx + winW / 2f, cy + winH / 2f)

        val radius = 16f * density
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
        canvas.drawRoundRect(window, radius, radius, clearPaint)

        // Coins de cadrage (traits en L sur les 4 angles).
        val len = minOf(28f * density, winH / 2.8f)
        val r = window
        // haut-gauche
        canvas.drawLine(r.left, r.top + len, r.left, r.top + radius / 2, cornerPaint)
        canvas.drawLine(r.left + radius / 2, r.top, r.left + len, r.top, cornerPaint)
        // haut-droit
        canvas.drawLine(r.right - len, r.top, r.right - radius / 2, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top + radius / 2, r.right, r.top + len, cornerPaint)
        // bas-gauche
        canvas.drawLine(r.left, r.bottom - len, r.left, r.bottom - radius / 2, cornerPaint)
        canvas.drawLine(r.left + radius / 2, r.bottom, r.left + len, r.bottom, cornerPaint)
        // bas-droit
        canvas.drawLine(r.right - len, r.bottom, r.right - radius / 2, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom - radius / 2, r.right, r.bottom - len, cornerPaint)
    }
}
