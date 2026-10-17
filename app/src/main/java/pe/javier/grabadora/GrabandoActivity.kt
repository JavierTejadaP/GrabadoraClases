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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var scope: CoroutineScope? = null
    private var vioGrabando = false

    /** Solo mientras se graba: centésimas en el reloj. */
    private val reloj = object : Runnable {
        override fun run() {
            tvTiempo.text = formatear(RecorderService.tiempoMs())
            handler.postDelayed(this, 33)
        }
    }

    /** Si a los 4 s no arrancó (permiso, micrófono ocupado…), se cierra la pantalla. */
    private val noArranco = Runnable {
        if (!vioGrabando && RecorderService.estado == Estado.DETENIDO) finish()
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
        handler.postDelayed(noArranco, 4000)
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        scope = s
        s.launch { RecorderService.flujo.collect { pintar(it) } }
        s.launch {
            RecorderService.error.collect { e ->
                if (e != null) {
                    Toast.makeText(this@GrabandoActivity, e, Toast.LENGTH_LONG).show()
                    RecorderService.error.value = null
                }
            }
        }
        onda.invalidate()
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        handler.removeCallbacks(reloj)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun enviar(accion: String) {
        startService(Intent(this, RecorderService::class.java).setAction(accion))
    }

    private fun confirmarTerminar() {
        AlertDialog.Builder(this)
            .setTitle(R.string.terminar_titulo)
            .setMessage(getString(R.string.terminar_mensaje, Horario.fmt(RecorderService.tiempoMs())))
            .setPositiveButton(R.string.terminar) { _, _ -> enviar(RecorderService.ACT_STOP) }
            .setNegativeButton(R.string.seguir_grabando, null)
            .show()
    }

    private fun pintar(inst: RecorderService.Instantanea) {
        val e = inst.estado
        if (e == Estado.DETENIDO) {
            // Terminó (desde aquí, la notificación o por un error)
            if (vioGrabando) {
                inst.ultimoArchivo?.let {
                    Toast.makeText(this, getString(R.string.guardado, it.removeSuffix(".aac")), Toast.LENGTH_LONG).show()
                }
                finish()
            }
            return
        }
        vioGrabando = true

        tvTiempo.text = formatear(RecorderService.tiempoMs())
        tvTiempo.alpha = if (e == Estado.PAUSADO) 0.5f else 1f
        handler.removeCallbacks(reloj)
        if (e == Estado.GRABANDO) handler.post(reloj)

        tvSub.text = inst.titulo.ifBlank { getString(R.string.grabacion_libre) }
        onda.activo = e == Estado.GRABANDO
        btnPausar.setImageResource(if (e == Estado.PAUSADO) R.drawable.ic_play else R.drawable.ic_pause)
        btnPausar.contentDescription = getString(if (e == Estado.PAUSADO) R.string.reanudar else R.string.pausar)

        tvAviso.text = when {
            e == Estado.PAUSADO -> getString(R.string.en_pausa)
            inst.advertencia != null -> getString(R.string.advertencia_con_icono, inst.advertencia)
            else -> ""
        }
        tvMarcas.text = if (inst.numMarcas == 0) ""
        else resources.getQuantityString(R.plurals.marcas_con_icono, inst.numMarcas, inst.numMarcas)
    }

    /** 00:04.26 · o 1:02:03.45 si pasa la hora. */
    private fun formatear(ms: Long): String {
        val cs = (ms / 10) % 100
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d.%02d", s / 3600, s % 3600 / 60, s % 60, cs)
        else String.format(Locale.US, "%02d:%02d.%02d", s / 60, s % 60, cs)
    }
}
