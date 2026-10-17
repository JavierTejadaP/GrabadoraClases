package pe.javier.grabadora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Barra de progreso con las marcas dibujadas. Se puede arrastrar para buscar. */
class LineaTiempo @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    var duracion = 0L
        set(v) { field = v; invalidate() }
    var posicion = 0L
        set(v) { field = v; invalidate() }
    var marcas: List<Long> = emptyList()
        set(v) { field = v; invalidate() }
    var arrastrando = false
        private set
    var alBuscar: ((Long) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val margen = 14 * d

    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55888888
        strokeWidth = 6 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val pProgreso = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD32F2F.toInt()
        strokeWidth = 6 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val pMarca = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF57C00.toInt()
        strokeWidth = 3 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val pCabezal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt() }

    private fun x(ms: Long): Float {
        val w = width - 2 * margen
        if (duracion <= 0 || w <= 0) return margen
        return margen + w * (ms.coerceIn(0, duracion).toFloat() / duracion)
    }

    private fun ms(x: Float): Long {
        val w = width - 2 * margen
        if (duracion <= 0 || w <= 0) return 0
        return (((x - margen) / w).coerceIn(0f, 1f) * duracion).toLong()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cy = height / 2f
        c.drawLine(margen, cy, width - margen, cy, pFondo)
        c.drawLine(margen, cy, x(posicion), cy, pProgreso)
        for (m in marcas) {
            val mx = x(m)
            c.drawLine(mx, cy - height * 0.35f, mx, cy + height * 0.35f, pMarca)
        }
        c.drawCircle(x(posicion), cy, 9 * d, pCabezal)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (duracion <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                arrastrando = true
                posicion = ms(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (arrastrando) posicion = ms(e.x)
            MotionEvent.ACTION_UP -> {
                posicion = ms(e.x)
                arrastrando = false
                alBuscar?.invoke(posicion)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> arrastrando = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
