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
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.javier.grabadora.RecorderService.Estado
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        /** Abrir con una clase elegida y, si [EXTRA_GRABAR], empezar a grabar (aviso de clase, ajustes rápidos). */
        const val ACCION_GRABAR = "pe.javier.grabadora.GRABAR"
        const val EXTRA_CURSO_ID = "curso_id"
        const val EXTRA_TIPO = "tipo"
        const val EXTRA_GRABAR = "grabar"
    }

    /** Pedido que llegó por intent y se atiende en onResume, cuando ya está elegida la clase. */
    private class Pedido(val cursoId: Long?, val tipo: String?, val grabar: Boolean)

    private var pedido: Pedido? = null

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
    private var scope: CoroutineScope? = null
    private var seleccion = 0
    private var eleccionManual = false
    private var opcionElegida: Opcion? = null
    private var animPulso: ObjectAnimator? = null

    /** Solo mientras se graba: el reloj avanza cada segundo. */
    private val reloj = object : Runnable {
        override fun run() {
            tvTiempo.text = Horario.fmt(RecorderService.tiempoMs())
            handler.postDelayed(this, 1000)
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
            else Toast.makeText(this, R.string.no_cambiar_curso_grabando, Toast.LENGTH_SHORT).show()
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
        if (savedInstanceState == null) recibir(intent)
    }

    override fun onNewIntent(nuevo: Intent) {
        super.onNewIntent(nuevo)
        setIntent(nuevo)
        recibir(nuevo)
    }

    private fun recibir(i: Intent?) {
        if (i?.action != ACCION_GRABAR) return
        pedido = Pedido(
            i.getLongExtra(EXTRA_CURSO_ID, -1L).takeIf { it >= 0 },
            i.getStringExtra(EXTRA_TIPO),
            i.getBooleanExtra(EXTRA_GRABAR, false),
        )
    }

    /** Elige la clase del pedido y, si lo pide, empieza a grabar. */
    private fun atenderPedido() {
        val p = pedido ?: return
        pedido = null
        if (RecorderService.estado != Estado.DETENIDO) {
            if (p.grabar) abrirGrabando()
            return
        }
        if (p.cursoId != null) {
            val i = Horario.opciones.indexOfFirst { it.curso?.id == p.cursoId && it.tipo == p.tipo }
            if (i >= 0) {
                seleccion = i
                eleccionManual = true
                opcionElegida = Horario.opciones[i]
                mostrarCurso()
            }
        }
        if (p.grabar) iniciar()
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        scope = s
        s.launch { RecorderService.flujo.collect { pintar(it) } }
        s.launch {
            RecorderService.error.collect { e ->
                if (e != null) {
                    Toast.makeText(this@MainActivity, e, Toast.LENGTH_LONG).show()
                    RecorderService.error.value = null
                }
            }
        }
        s.launch {
            val n = withContext(Dispatchers.IO) { Biblioteca.cargar(applicationContext).size }
            tvResumen.text = if (n == 0) getString(R.string.sin_grabaciones)
            else resources.getQuantityString(R.plurals.grabaciones_guardadas, n, n)
        }
    }

    override fun onResume() {
        super.onResume()
        val patron = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        tvFecha.text = SimpleDateFormat(patron, Locale.getDefault()).format(Date())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        // El horario pudo cambiar en "Mi horario": se vuelve a ubicar la clase elegida
        if (eleccionManual) {
            val e = opcionElegida
            val i = Horario.opciones.indexOfFirst { it.curso?.id == e?.curso?.id && it.tipo == e?.tipo }
            if (i >= 0) seleccion = i else eleccionManual = false
        }
        if (RecorderService.estado == Estado.DETENIDO && !eleccionManual) {
            seleccion = Horario.indiceDe(Horario.detectar())
        }
        seleccion = seleccion.coerceIn(0, Horario.opciones.size - 1)
        mostrarCurso()
        atenderPedido()
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        handler.removeCallbacks(reloj)
        detenerPulso()
        super.onStop()
    }

    private fun abrirHorario() {
        startActivity(Intent(this, HorarioActivity::class.java))
    }

    // ---------------- curso ----------------

    private fun mostrarCurso() {
        val o = Horario.opciones.getOrElse(seleccion) { Horario.opciones[0] }
        val ahora = Horario.detectar()
        tvCursoLabel.setText(
            when {
                o.curso == null -> R.string.etiqueta_sin_curso
                ahora != null && ahora.curso.id == o.curso.id && ahora.tipo == o.tipo -> R.string.etiqueta_ahora_en_clase
                else -> R.string.etiqueta_curso_elegido
            }
        )
        tvCurso.text = o.curso?.nombre ?: getString(R.string.grabacion_libre)
        tvTipo.text = if (o.curso == null) getString(R.string.se_guarda_fecha_hora) else Horario.etiquetaTipo(this, o.tipo)
        tvIniciales.text = Horario.iniciales(o.curso?.nombre)
        tvIniciales.backgroundTintList = ColorStateList.valueOf(Horario.color(o.curso))
    }

    private fun elegirCurso() {
        val etiquetas = Horario.opciones.map { Horario.etiqueta(this, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.que_clase_grabar)
            .setSingleChoiceItems(etiquetas, seleccion) { d, w ->
                seleccion = w
                eleccionManual = true
                opcionElegida = Horario.opciones[w]
                mostrarCurso()
                d.dismiss()
            }
            .setNeutralButton(R.string.editar_horario) { _, _ -> abrirHorario() }
            .setNegativeButton(R.string.cancelar, null)
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
            Toast.makeText(this, R.string.sin_permiso_microfono, Toast.LENGTH_LONG).show()
        }
    }

    private var ultimoInicio = 0L

    private fun arrancar() {
        val ahora = android.os.SystemClock.elapsedRealtime()
        if (RecorderService.estado != Estado.DETENIDO || ahora - ultimoInicio < 2000) return   // doble toque
        ultimoInicio = ahora
        val o = Horario.opciones[seleccion]
        val i = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACT_START)
            .putExtra(RecorderService.EXTRA_NOMBRE, Horario.nombreArchivo(o.curso, o.tipo))
            .putExtra(RecorderService.EXTRA_TITULO, Horario.etiqueta(this, o))
            .putExtra(RecorderService.EXTRA_CURSO_ID, o.curso?.id ?: -1L)
            .putExtra(RecorderService.EXTRA_TIPO, o.tipo)
        startForegroundService(i)
        eleccionManual = false
        abrirGrabando()
    }

    private fun abrirGrabando() {
        startActivity(Intent(this, GrabandoActivity::class.java))
    }

    private fun enviar(accion: String) {
        startService(Intent(this, RecorderService::class.java).setAction(accion))
    }

    // ---------------- interfaz ----------------

    private fun pintar(inst: RecorderService.Instantanea) {
        val e = inst.estado
        val activo = e != Estado.DETENIDO

        when {
            e == Estado.GRABANDO && inst.advertencia != null -> {
                tvEstado.text = getString(R.string.advertencia_con_icono, inst.advertencia)
                tvEstado.setTextColor(getColor(R.color.marca))
            }
            e == Estado.GRABANDO -> {
                tvEstado.setText(R.string.estado_grabando)
                tvEstado.setTextColor(getColor(R.color.acento))
            }
            e == Estado.PAUSADO -> {
                tvEstado.setText(R.string.en_pausa)
                tvEstado.setTextColor(getColor(R.color.marca))
            }
            else -> {
                tvEstado.setText(R.string.listo_para_grabar)
                tvEstado.setTextColor(getColor(R.color.texto2))
            }
        }

        btnIniciar.contentDescription = getString(if (activo) R.string.ver_grabacion else R.string.grabar)
        btnPausar.setImageResource(if (e == Estado.PAUSADO) R.drawable.ic_play else R.drawable.ic_pause)
        tvPausar.setText(if (e == Estado.PAUSADO) R.string.reanudar else R.string.pausar)
        btnPausar.isEnabled = activo
        btnMarcar.isEnabled = activo
        grupoPausar.alpha = if (activo) 1f else 0.35f
        grupoMarcar.alpha = if (activo) 1f else 0.35f
        cardCurso.alpha = if (activo) 0.6f else 1f
        tvTiempo.alpha = if (e == Estado.PAUSADO) 0.6f else 1f

        tvTiempo.text = Horario.fmt(RecorderService.tiempoMs())
        handler.removeCallbacks(reloj)
        if (e == Estado.GRABANDO) handler.postDelayed(reloj, 1000)

        if (e == Estado.GRABANDO) iniciarPulso() else detenerPulso()
        if (!activo) mostrarCurso()

        tvMarcas.text = if (!activo || inst.numMarcas == 0) ""
        else resources.getQuantityString(R.plurals.marcas_con_icono, inst.numMarcas, inst.numMarcas)
        tvUltimo.text = inst.ultimoArchivo?.let { getString(R.string.ultima_grabacion, it.removeSuffix(".aac")) } ?: ""
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
                Toast.makeText(this, R.string.bateria_instrucciones, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_abrir_ajustes, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
