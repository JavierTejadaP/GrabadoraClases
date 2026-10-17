package pe.javier.grabadora

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import pe.javier.grabadora.RecorderService.Estado
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val es = Locale.forLanguageTag("es-PE")

    private lateinit var tvFecha: TextView
    private lateinit var cardCurso: View
    private lateinit var tvIniciales: TextView
    private lateinit var tvCursoLabel: TextView
    private lateinit var tvCurso: TextView
    private lateinit var tvTipo: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvTiempo: TextView
    private lateinit var tvMarcas: TextView
    private lateinit var pulso: View
    private lateinit var btnIniciar: ImageButton
    private lateinit var btnPausar: ImageButton
    private lateinit var tvPausar: TextView
    private lateinit var btnMarcar: ImageButton
    private lateinit var grupoPausar: View
    private lateinit var grupoMarcar: View
    private lateinit var tvResumen: TextView
    private lateinit var tvUltimo: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var seleccion = 0
    private var eleccionManual = false
    private var opcionElegida: Opcion? = null
    private var ultimoEstado: Estado? = null
    private var animPulso: ObjectAnimator? = null

    private val tick = object : Runnable {
        override fun run() {
            refrescar()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFecha = findViewById(R.id.tvFecha)
        cardCurso = findViewById(R.id.cardCurso)
        tvIniciales = findViewById(R.id.tvIniciales)
        tvCursoLabel = findViewById(R.id.tvCursoLabel)
        tvCurso = findViewById(R.id.tvCurso)
        tvTipo = findViewById(R.id.tvTipo)
        tvEstado = findViewById(R.id.tvEstado)
        tvTiempo = findViewById(R.id.tvTiempo)
        tvMarcas = findViewById(R.id.tvMarcas)
        pulso = findViewById(R.id.pulso)
        btnIniciar = findViewById(R.id.btnIniciar)
        btnPausar = findViewById(R.id.btnPausar)
        tvPausar = findViewById(R.id.tvPausar)
        btnMarcar = findViewById(R.id.btnMarcar)
        grupoPausar = findViewById(R.id.grupoPausar)
        grupoMarcar = findViewById(R.id.grupoMarcar)
        tvResumen = findViewById(R.id.tvResumen)
        tvUltimo = findViewById(R.id.tvUltimo)

        cardCurso.setOnClickListener {
            if (RecorderService.estado == Estado.DETENIDO) elegirCurso()
            else Toast.makeText(this, "No se puede cambiar el curso mientras grabas", Toast.LENGTH_SHORT).show()
        }
        btnIniciar.setOnClickListener {
            if (RecorderService.estado == Estado.DETENIDO) iniciar() else abrirGrabando()
        }
        btnPausar.setOnClickListener {
            enviar(if (RecorderService.estado == Estado.GRABANDO) RecorderService.ACT_PAUSE else RecorderService.ACT_RESUME)
        }
        btnMarcar.setOnClickListener { enviar(RecorderService.ACT_MARK) }
        findViewById<View>(R.id.cardGrabaciones).setOnClickListener {
            startActivity(Intent(this, CursosActivity::class.java))
        }
        findViewById<View>(R.id.btnBateria).setOnClickListener { configurarBateria() }
        findViewById<View>(R.id.btnHorario).setOnClickListener { abrirHorario() }
    }

    private fun abrirHorario() {
        startActivity(Intent(this, HorarioActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        tvFecha.text = SimpleDateFormat("EEEE d 'de' MMMM", es).format(Date()).replaceFirstChar { it.titlecase(es) }
        // El horario pudo cambiar en "Mi horario": se vuelve a ubicar la clase elegida
        if (eleccionManual) {
            val i = opcionElegida?.let { Horario.opciones.indexOf(it) } ?: -1
            if (i >= 0) seleccion = i else eleccionManual = false
        }
        if (RecorderService.estado == Estado.DETENIDO && !eleccionManual) {
            seleccion = Horario.indiceDe(Horario.detectar())
        }
        seleccion = seleccion.coerceIn(0, Horario.opciones.size - 1)
        mostrarCurso()
        val n = Biblioteca.cargar(this).size
        tvResumen.text = when (n) {
            0 -> "Aún no tienes grabaciones"
            1 -> "1 grabación guardada"
            else -> "$n grabaciones guardadas"
        }
        ultimoEstado = null
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        animPulso?.cancel()
        animPulso = null
    }

    // ---------------- curso ----------------

    private fun mostrarCurso() {
        val o = Horario.opciones[seleccion]
        val ahora = Horario.detectar()
        tvCursoLabel.text = when {
            o.curso == null -> "SIN CURSO"
            ahora?.curso == o.curso && ahora.tipo == o.tipo -> "AHORA EN CLASE"
            else -> "CURSO ELEGIDO"
        }
        tvCurso.text = o.curso ?: "Grabación libre"
        tvTipo.text = o.tipo ?: "Se guarda con fecha y hora"
        tvIniciales.text = Horario.iniciales(o.curso)
        tvIniciales.backgroundTintList = ColorStateList.valueOf(Horario.color(o.curso))
    }

    private fun elegirCurso() {
        val etiquetas = Horario.opciones.map { it.etiqueta }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("¿Qué clase vas a grabar?")
            .setSingleChoiceItems(etiquetas, seleccion) { d, w ->
                seleccion = w
                eleccionManual = true
                opcionElegida = Horario.opciones[w]
                mostrarCurso()
                d.dismiss()
            }
            .setNeutralButton("Editar horario") { _, _ -> abrirHorario() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- grabación ----------------

    private fun iniciar() {
        val faltan = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) faltan.add(Manifest.permission.POST_NOTIFICATIONS)
        val pedir = faltan.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (pedir.isNotEmpty()) {
            requestPermissions(pedir.toTypedArray(), 1)
            return
        }
        arrancar()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            arrancar()
        } else {
            Toast.makeText(this, "Sin permiso de micrófono no se puede grabar", Toast.LENGTH_LONG).show()
        }
    }

    private var ultimoInicio = 0L

    private fun arrancar() {
        val ahora = android.os.SystemClock.elapsedRealtime()
        if (arrancando() || ahora - ultimoInicio < 2000) return   // doble toque
        ultimoInicio = ahora
        val o = Horario.opciones[seleccion]
        val i = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACT_START)
            .putExtra(RecorderService.EXTRA_NOMBRE, Horario.nombreArchivo(o))
            .putExtra(RecorderService.EXTRA_TITULO, o.etiqueta)
        startForegroundService(i)
        eleccionManual = false
        abrirGrabando()
    }

    private fun abrirGrabando() {
        startActivity(Intent(this, GrabandoActivity::class.java))
    }

    private fun arrancando() = RecorderService.estado != Estado.DETENIDO

    private fun enviar(accion: String) {
        startService(Intent(this, RecorderService::class.java).setAction(accion))
    }

    // ---------------- interfaz ----------------

    private fun refrescar() {
        val e = RecorderService.estado
        val activo = e != Estado.DETENIDO
        tvTiempo.text = Horario.fmt(RecorderService.tiempoMs())

        if (e != ultimoEstado) {
            ultimoEstado = e
            when (e) {
                Estado.GRABANDO -> {
                    tvEstado.text = "● Grabando"
                    tvEstado.setTextColor(getColor(R.color.acento))
                }
                Estado.PAUSADO -> {
                    tvEstado.text = "En pausa"
                    tvEstado.setTextColor(getColor(R.color.marca))
                }
                Estado.DETENIDO -> {
                    tvEstado.text = "Listo para grabar"
                    tvEstado.setTextColor(getColor(R.color.texto2))
                }
            }
            btnIniciar.setImageResource(R.drawable.ic_mic)
            btnIniciar.contentDescription = if (activo) "Ver grabación" else "Grabar"
            btnPausar.setImageResource(if (e == Estado.PAUSADO) R.drawable.ic_play else R.drawable.ic_pause)
            tvPausar.text = if (e == Estado.PAUSADO) "Reanudar" else "Pausar"
            btnPausar.isEnabled = activo
            btnMarcar.isEnabled = activo
            grupoPausar.alpha = if (activo) 1f else 0.35f
            grupoMarcar.alpha = if (activo) 1f else 0.35f
            cardCurso.alpha = if (activo) 0.6f else 1f
            tvTiempo.alpha = if (e == Estado.PAUSADO) 0.6f else 1f
            if (e == Estado.GRABANDO) iniciarPulso() else detenerPulso()
            if (!activo) mostrarCurso()
        }

        val aviso = RecorderService.advertencia
        if (e == Estado.GRABANDO) {
            tvEstado.text = if (aviso != null) "⚠ $aviso" else "● Grabando"
            tvEstado.setTextColor(getColor(if (aviso != null) R.color.marca else R.color.acento))
        }

        val n = RecorderService.numMarcas()
        tvMarcas.text = when {
            !activo || n == 0 -> ""
            n == 1 -> "⚑ 1 marca"
            else -> "⚑ $n marcas"
        }
        tvUltimo.text = RecorderService.ultimoArchivo?.let { "Última: ${it.removeSuffix(".aac")}" } ?: ""

        RecorderService.error?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            RecorderService.error = null
        }
    }

    private fun iniciarPulso() {
        if (animPulso != null) return
        animPulso = ObjectAnimator.ofPropertyValuesHolder(
            pulso,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.45f, 0f),
        ).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun detenerPulso() {
        animPulso?.cancel()
        animPulso = null
        pulso.alpha = 0f
    }

    // ---------------- batería (Xiaomi) ----------------

    @SuppressLint("BatteryLife")
    private fun configurarBateria() {
        val pm = getSystemService(PowerManager::class.java)
        try {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } else {
                Toast.makeText(
                    this,
                    "Activa \"Autoencendido\" y en \"Ahorro de batería\" elige \"Sin restricciones\"",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir ajustes: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
