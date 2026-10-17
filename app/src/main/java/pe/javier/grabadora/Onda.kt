package pe.javier.grabadora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * Onda de grabación estilo grabadora: la línea roja queda fija al centro,
 * lo grabado se desplaza a la izquierda y abajo hay una regla de segundos.
 * Se redibuja sola a 60 fps mientras está visible.
 */
class Onda @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    /** Tiempo actual de la grabación (ms). */
    var reloj: () -> Long = { 0L }
    /** Nivel 0..1 del tramo de 100 ms número b. */
    var nivel: (Long) -> Float = { 0f }
    var marcas: () -> List<Long> = { emptyList() }
    var activo = true

    private val d = resources.displayMetrics.density
    private val pxSeg = 60 * d          // 1 segundo = 60 dp
    private val alturaRegla = 28 * d

    private val pPanel = Paint().apply { color = ctx.getColor(R.color.superficie) }
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val pFuturo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = 1.5f * d
    }
    private val pMarca = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.marca)
        strokeWidth = 2 * d
    }
    private val pCabezal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.acento)
        strokeWidth = 2 * d
    }
    private val pTick = Paint().apply {
        color = 0x66FFFFFF
        strokeWidth = d
    }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.texto2)
        textSize = 12 * resources.displayMetrics.scaledDensity
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val t = reloj()
        val alto = height - alturaRegla
        val cx = width / 2f
        val cy = alto / 2f

        c.drawRect(0f, 0f, width.toFloat(), alto, pPanel)

        // Futuro: puntitos a la derecha
        var xf = cx + 6 * d
        while (xf < width) {
            c.drawLine(xf, cy - 3 * d, xf, cy + 3 * d, pFuturo)
            xf += 6 * d
        }

        // Pasado: una barra cada 100 ms
        pBarra.color = if (activo) 0xFFFFFFFF.toInt() else 0xFF9AA0AA.toInt()
        var b = t / 100
        while (b >= 0) {
            val x = cx - (t - b * 100) / 1000f * pxSeg
            if (x < 0) break
            val h = max(2 * d, nivel(b) * alto * 0.8f) / 2
            c.drawLine(x, cy - h, x, cy + h, pBarra)
            b--
        }

        // Marcas
        for (m in marcas()) {
            val x = cx - (t - m) / 1000f * pxSeg
            if (x in 0f..cx) c.drawLine(x, 8 * d, x, alto - 8 * d, pMarca)
        }

        // Línea roja fija al centro
        c.drawLine(cx, 0f, cx, alto, pCabezal)
        c.drawCircle(cx, 3 * d, 4 * d, pCabezal)
        c.drawCircle(cx, alto - 3 * d, 4 * d, pCabezal)

        // Regla de segundos
        val primero = floor((t - cx / pxSeg * 1000) / 1000.0).toLong().coerceAtLeast(0)
        var s = primero
        while (true) {
            val x = cx + (s * 1000 - t) / 1000f * pxSeg
            if (x > width) break
            if (x >= 0) {
                c.drawLine(x, alto, x, alto + 6 * d, pTick)
                c.drawText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60), x + 3 * d, alto + 20 * d, pTexto)
            }
            s++
        }

        if (isShown) postInvalidateOnAnimation()
    }
}
