package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pantalla del reproductor. El audio lo reproduce PlayerService, así que
 * sigue sonando con la pantalla apagada o fuera de la app.
 */
class ReproductorActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NOMBRE = "nombre"
        const val EXTRA_TITULO = "titulo"
        const val EXTRA_SUBTITULO = "subtitulo"
        const val EXTRA_MARCAS = "marcas"
        const val EXTRA_BYTES = "bytes"
        const val EXTRA_CURSO_ID = "curso_id"
        const val EXTRA_TEMA = "tema"
        private const val REQ_BORRAR = 20
        private const val M_ABRIR = 1
        private const val M_BORRAR = 2
        private const val M_TEMA = 3
    }

    private lateinit var uri: Uri
    private var nombre = ""
    private var titulo = ""
    private var tema: String? = null
    private var marcasUri: Uri? = null
    private val marcas = mutableListOf<Long>()

    private lateinit var tvActual: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvMarcasTitulo: TextView
    private lateinit var linea: LineaTiempo
    private lateinit var lvMarcas: ListView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnVel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    /** Solo mientras suena: avanza la barra y el tiempo. */
    private val progreso = object : Runnable {
        override fun run() {
            pintarPosicion()
            handler.postDelayed(this, 200)
        }
    }

    /** ¿El servicio tiene cargada ESTA grabación? */
    private fun esta(): Boolean = PlayerService.uriActual == uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: run { finish(); return })
        nombre = intent.getStringExtra(EXTRA_NOMBRE) ?: ""
        titulo = intent.getStringExtra(EXTRA_TITULO) ?: ""
        marcasUri = intent.getStringExtra(EXTRA_MARCAS)?.let { Uri.parse(it) }
            ?: Biblioteca.buscarMarcas(this, nombre)

        title = ""
        actionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(R.layout.activity_reproductor)

        val curso = Horario.curso(intent.getLongExtra(EXTRA_CURSO_ID, -1L))
        tema = intent.getStringExtra(EXTRA_TEMA)
        pintarTitulo()
        findViewById<TextView>(R.id.tvIniciales).apply {
            text = Horario.iniciales(curso?.nombre)
            backgroundTintList = ColorStateList.valueOf(Horario.color(curso))
        }

        tvActual = findViewById(R.id.tvActual)
        tvTotal = findViewById(R.id.tvTotal)
        tvMarcasTitulo = findViewById(R.id.tvMarcasTitulo)
        linea = findViewById(R.id.linea)
        lvMarcas = findViewById(R.id.lvMarcas)
        btnPlay = findViewById(R.id.btnPlay)
        btnVel = findViewById(R.id.btnVel)

        linea.alBuscar = { ms -> buscar(ms) }
        btnPlay.setOnClickListener {
            if (!esta()) abrirEnServicio() else enviar(PlayerService.ACT_TOGGLE)
        }
        findViewById<View>(R.id.btnAtras).setOnClickListener {
            if (esta()) enviar(PlayerService.ACT_SALTAR) { putExtra(PlayerService.EXTRA_DELTA, -PlayerService.SALTO) }
        }
        findViewById<View>(R.id.btnAdelante).setOnClickListener {
            if (esta()) enviar(PlayerService.ACT_SALTAR) { putExtra(PlayerService.EXTRA_DELTA, PlayerService.SALTO) }
        }
        findViewById<View>(R.id.btnMarcar).setOnClickListener { marcar() }
        findViewById<View>(R.id.btnCompartir).setOnClickListener { compartir() }
        btnVel.setOnClickListener {
            val sig = (PlayerService.iVel + 1) % PlayerService.VELOCIDADES.size
            enviar(PlayerService.ACT_VEL) { putExtra(PlayerService.EXTRA_VEL, sig) }
        }

        lvMarcas.setOnItemClickListener { _, _, pos, _ ->
            buscar(marcas[pos])
            if (esta() && !PlayerService.reproduciendo()) enviar(PlayerService.ACT_PLAY)
        }
        lvMarcas.setOnItemLongClickListener { _, _, pos, _ ->
            borrarMarca(pos)
            true
        }

        cargarMarcas()
        abrirEnServicio()
    }

    override fun onNewIntent(nuevo: Intent) {
        super.onNewIntent(nuevo)
        if (nuevo.getStringExtra(EXTRA_URI) != uri.toString()) {
            setIntent(nuevo)
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        scope = s
        s.launch { PlayerService.flujo.collect { pintar(it) } }
        s.launch {
            PlayerService.error.collect { e ->
                if (e != null) {
                    Toast.makeText(this@ReproductorActivity, e, Toast.LENGTH_LONG).show()
                    PlayerService.error.value = null
                }
            }
        }
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        handler.removeCallbacks(progreso)   // el audio sigue sonando
        super.onStop()
    }

    /** Con tema: el tema arriba y "Curso - Tipo · fecha" debajo. */
    private fun pintarTitulo() {
        val sub = intent.getStringExtra(EXTRA_SUBTITULO) ?: ""
        val t = tema
        findViewById<TextView>(R.id.tvTitulo).text = t ?: titulo
        findViewById<TextView>(R.id.tvSubtitulo).text = if (t == null) sub else getString(R.string.titulo_con_tiempo, titulo, sub)
    }

    // ---------------- servicio ----------------

    private fun abrirEnServicio() {
        val i = Intent(this, PlayerService::class.java)
            .setAction(PlayerService.ACT_ABRIR)
            .putExtras(intent.extras ?: Bundle())
            .putExtra(EXTRA_MARCAS, marcasUri?.toString())
        startForegroundService(i)
    }

    private fun enviar(accion: String, extras: Intent.() -> Unit = {}) {
        startService(Intent(this, PlayerService::class.java).setAction(accion).apply(extras))
    }

    private fun pintar(inst: PlayerService.Instantanea) {
        val activo = inst.uri == uri
        val sonando = activo && inst.sonando
        btnPlay.setImageResource(if (sonando) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (sonando) R.string.pausar else R.string.reproducir)
        btnVel.text = PlayerService.etiquetaVel(inst.iVel)
        if (activo && inst.listo && linea.duracion != inst.duracion) {
            linea.duracion = inst.duracion
            tvTotal.text = getString(R.string.duracion_total, Horario.fmt(inst.duracion))
        }
        pintarPosicion()
        handler.removeCallbacks(progreso)
        if (sonando) handler.postDelayed(progreso, 200)
    }

    private fun pintarPosicion() {
        if (!esta() || !PlayerService.listo) return
        val t = if (linea.arrastrando) linea.posicion else PlayerService.posicion()
        if (!linea.arrastrando) linea.posicion = t
        tvActual.text = Horario.fmt(t)
    }

    private fun buscar(ms: Long) {
        if (!esta() || !PlayerService.listo) return
        val t = ms.coerceIn(0L, PlayerService.duracion)
        enviar(PlayerService.ACT_SEEK) { putExtra(PlayerService.EXTRA_MS, t) }
        linea.posicion = t
        tvActual.text = Horario.fmt(t)
    }

    // ---------------- marcas ----------------

    private fun cargarMarcas() {
        marcasUri?.let { marcas.addAll(Biblioteca.parsearMarcas(Biblioteca.leerTexto(contentResolver, it))) }
        marcas.sort()
        refrescarMarcas()
    }

    private fun refrescarMarcas() {
        linea.marcas = marcas.toList()
        lvMarcas.adapter = object : BaseAdapter() {
            override fun getCount() = marcas.size
            override fun getItem(position: Int) = marcas[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_marca, parent, false)
                v.findViewById<TextView>(R.id.tvNombre).text = getString(R.string.marca_numero, position + 1)
                v.findViewById<TextView>(R.id.tvTiempo).text = Horario.fmt(marcas[position])
                return v
            }
        }
        tvMarcasTitulo.text = if (marcas.isEmpty()) getString(R.string.sin_marcas_ayuda)
        else resources.getQuantityString(R.plurals.marcas_ayuda, marcas.size, marcas.size)
    }

    private fun guardarMarcas() {
        marcasUri = Biblioteca.guardarMarcas(this, nombre, marcasUri, titulo, marcas)
        refrescarMarcas()
    }

    private fun marcar() {
        if (!esta() || !PlayerService.listo) return
        val t = PlayerService.posicion()
        marcas.add(t)
        marcas.sort()
        guardarMarcas()
        Toast.makeText(this, getString(R.string.marca_agregada, Horario.fmt(t)), Toast.LENGTH_SHORT).show()
    }

    private fun borrarMarca(pos: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.borrar_marca_titulo, pos + 1))
            .setMessage(Horario.fmt(marcas[pos]))
            .setPositiveButton(R.string.borrar) { _, _ ->
                marcas.removeAt(pos)
                guardarMarcas()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    // ---------------- compartir / menú ----------------

    private fun compartir() = Envios.compartir(this, listOf(uri), listOf(nombre))

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_ABRIR, 0, R.string.abrir_con_otra_app)
        menu.add(0, M_TEMA, 1, R.string.tema_menu)
        menu.add(0, M_BORRAR, 2, R.string.borrar_grabacion)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_ABRIR -> abrirCon()
            M_BORRAR -> confirmarBorrar()
            M_TEMA -> VistaGrabacion.pedirTema(this, tema, R.string.tema_menu, R.string.cancelar) { nuevo ->
                Biblioteca.ponerTema(this, nombre, nuevo)
                tema = nuevo
                intent.putExtra(EXTRA_TEMA, nuevo)   // para que la notificación lo lleve si se vuelve a abrir
                pintarTitulo()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun abrirCon() {
        if (esta()) enviar(PlayerService.ACT_PAUSE)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.sin_otra_app_audio, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmarBorrar() {
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.borrar_grabaciones, 1, 1))
            .setMessage(R.string.borrar_mensaje)
            .setPositiveButton(R.string.borrar) { _, _ ->
                if (esta()) enviar(PlayerService.ACT_CERRAR)
                val fallidos = Biblioteca.borrarUris(this, listOfNotNull(uri, marcasUri))
                if (fallidos.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    try {
                        val pi = MediaStore.createDeleteRequest(contentResolver, fallidos)
                        startIntentSenderForResult(pi.intentSender, REQ_BORRAR, null, 0, 0, 0)
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.error_borrar, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, R.string.borrado, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BORRAR) finish()
    }
}
