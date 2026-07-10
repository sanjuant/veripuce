package fr.veripuce.app

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

    /**
     * Dessine une pièce d'identité fantôme dont la fenêtre est la bande MRZ (bas du
     * document) : silhouette de carte + photo et lignes schématiques au-dessus.
     * Pour le mode « bande MRZ » uniquement.
     */
    var showCardPlaceholder = false
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
    private val ghostStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xAAFFFFFF.toInt()
    }
    private val ghostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FFFFFF
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

        var winW = w * windowWidthFraction
        var winH = winW / windowRatio
        // En paysage, une fenêtre au ratio carte déborderait verticalement :
        // plafonner la hauteur et recalculer la largeur en conséquence.
        val maxH = h * 0.68f
        if (winH > maxH) {
            winH = maxH
            winW = winH * windowRatio
        }
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

        // Pièce d'identité fantôme : la fenêtre est sa bande MRZ, le reste du document
        // (photo + lignes) est esquissé au-dessus pour guider le placement.
        if (showCardPlaceholder) {
            val pad = 6f * density
            val cardBottom = r.bottom + pad
            var cardH = (r.width() + 2 * pad) / 1.586f // ratio carte ID-1
            val minTop = 8f * density
            if (cardBottom - cardH < minTop) cardH = cardBottom - minTop
            val card = RectF(r.left - pad, cardBottom - cardH, r.right + pad, cardBottom)
            canvas.drawRoundRect(card, radius, radius, ghostStrokePaint)

            val topArea = RectF(card.left, card.top, card.right, r.top - pad)
            if (topArea.height() > 48f * density) {
                val m = 16f * density
                val photo = RectF(
                    topArea.left + m,
                    topArea.top + m,
                    topArea.left + m + topArea.width() * 0.20f,
                    topArea.bottom - m,
                )
                canvas.drawRoundRect(photo, 5f * density, 5f * density, ghostFillPaint)

                val lineH = 5f * density
                val lx = photo.right + m
                var ly = topArea.top + m + 4f * density
                for (frac in listOf(0.85f, 0.6f, 0.45f)) {
                    if (ly + lineH > topArea.bottom - m) break
                    canvas.drawRoundRect(
                        RectF(lx, ly, lx + (card.right - m - lx) * frac, ly + lineH),
                        lineH / 2f, lineH / 2f, ghostFillPaint,
                    )
                    ly += lineH + 12f * density
                }
            }
        }
    }
}
