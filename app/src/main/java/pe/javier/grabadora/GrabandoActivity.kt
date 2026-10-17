package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import pe.javier.grabadora.RecorderService.Estado
import java.util.Locale

/** Pantalla mientras se graba: tiempo, onda en vivo, pausa, terminar y marcar. */
class GrabandoActivity : Activity() {

    private lateinit var tvTiempo: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvAviso: TextView
    private lateinit var tvMarcas: TextView
    private lateinit var onda: Onda
    private lateinit var btnPausar: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var vioGrabando = false
    private val abiertaEn = android.os.SystemClock.elapsedRealtime()

    private val tick = object : Runnable {
        override fun run() {
            refrescar()
            handler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grabando)

        tvTiempo = findViewById(R.id.tvTiempo)
        tvSub = findViewById(R.id.tvSub)
        tvAviso = findViewById(R.id.tvAviso)
        tvMarcas = findViewById(R.id.tvMarcas)
        onda = findViewById(R.id.onda)
        btnPausar = findViewById(R.id.btnPausar)

        onda.reloj = { RecorderService.tiempoMs() }
        onda.nivel = { b -> RecorderService.nivel(b) }
        onda.marcas = { RecorderService.marcasLista() }

        findViewById<ImageButton>(R.id.btnVolver).setOnClickListener { finish() }
        btnPausar.setOnClickListener {
            enviar(if (RecorderService.estado == Estado.GRABANDO) RecorderService.ACT_PAUSE else RecorderService.ACT_RESUME)
        }
        findViewById<ImageButton>(R.id.btnMarcar).setOnClickListener { enviar(RecorderService.ACT_MARK) }
        findViewById<ImageButton>(R.id.btnTerminar).setOnClickListener { confirmarTerminar() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        onda.invalidate()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun enviar(accion: String) {
        startService(Intent(this, RecorderService::class.java).setAction(accion))
    }

    private fun confirmarTerminar() {
        AlertDialog.Builder(this)
            .setTitle("¿Terminar la grabación?")
            .setMessage("Se guardará lo grabado (${Horario.fmt(RecorderService.tiempoMs())}).")
            .setPositiveButton("Terminar") { _, _ -> enviar(RecorderService.ACT_STOP) }
            .setNegativeButton("Seguir grabando", null)
            .show()
    }

    private fun refrescar() {
        val e = RecorderService.estado
        if (e != Estado.DETENIDO) vioGrabando = true
        if (e == Estado.DETENIDO) {
            // Terminó (desde aquí, la notificación o por un error)
            if (vioGrabando) {
                RecorderService.ultimoArchivo?.let {
                    Toast.makeText(this, "Guardado: ${it.removeSuffix(".aac")}", Toast.LENGTH_LONG).show()
                }
                finish()
            } else if (android.os.SystemClock.elapsedRealtime() - abiertaEn > 4000) {
                // No llegó a arrancar (permiso, micrófono ocupado…)
                RecorderService.error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                RecorderService.error = null
                finish()
            }
            return
        }

        tvTiempo.text = formatear(RecorderService.tiempoMs())
        tvTiempo.alpha = if (e == Estado.PAUSADO) 0.5f else 1f
        tvSub.text = RecorderService.titulo.ifBlank { "Grabación libre" }
        onda.activo = e == Estado.GRABANDO
        btnPausar.setImageResource(if (e == Estado.PAUSADO) R.drawable.ic_play else R.drawable.ic_pause)
        btnPausar.contentDescription = if (e == Estado.PAUSADO) "Reanudar" else "Pausar"

        tvAviso.text = when {
            e == Estado.PAUSADO -> "En pausa"
            RecorderService.advertencia != null -> "⚠ ${RecorderService.advertencia}"
            else -> ""
        }
        val n = RecorderService.numMarcas()
        tvMarcas.text = when (n) {
            0 -> ""
            1 -> "⚑ 1 marca"
            else -> "⚑ $n marcas"
        }

        RecorderService.error?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            RecorderService.error = null
        }
    }

    /** 00:04.26 · o 1:02:03.45 si pasa la hora. */
    private fun formatear(ms: Long): String {
        val cs = (ms / 10) % 100
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d.%02d", s / 3600, s % 3600 / 60, s % 60, cs)
        else String.format(Locale.US, "%02d:%02d.%02d", s / 60, s % 60, cs)
    }
}
