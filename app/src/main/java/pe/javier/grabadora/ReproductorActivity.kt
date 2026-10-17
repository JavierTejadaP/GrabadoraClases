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
        private const val REQ_BORRAR = 20
        private const val M_ABRIR = 1
        private const val M_BORRAR = 2
    }

    private lateinit var uri: Uri
    private var nombre = ""
    private var titulo = ""
    private var marcasUri: Uri? = null
    private val marcas = mutableListOf<Long>()
    private var mostrandoPausa = false

    private lateinit var tvActual: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvMarcasTitulo: TextView
    private lateinit var linea: LineaTiempo
    private lateinit var lvMarcas: ListView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnVel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refrescar()
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

        val curso = if (titulo.contains(" - ")) titulo.substringBefore(" - ") else null
        findViewById<TextView>(R.id.tvTitulo).text = titulo
        findViewById<TextView>(R.id.tvSubtitulo).text = intent.getStringExtra(EXTRA_SUBTITULO)
        findViewById<TextView>(R.id.tvIniciales).apply {
            text = Horario.iniciales(curso)
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

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)   // el audio sigue sonando
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

    private fun refrescar() {
        val activo = esta()
        val listo = activo && PlayerService.listo
        val sonando = activo && PlayerService.reproduciendo()
        if (sonando != mostrandoPausa) {
            mostrandoPausa = sonando
            btnPlay.setImageResource(if (sonando) R.drawable.ic_pause else R.drawable.ic_play)
        }
        btnVel.text = PlayerService.etiquetaVel(PlayerService.iVel)
        if (listo) {
            val dur = PlayerService.duracion
            if (linea.duracion != dur) {
                linea.duracion = dur
                tvTotal.text = "/ ${Horario.fmt(dur)}"
            }
            val t = if (linea.arrastrando) linea.posicion else PlayerService.posicion()
            if (!linea.arrastrando) linea.posicion = t
            tvActual.text = Horario.fmt(t)
        }
        PlayerService.error?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            PlayerService.error = null
        }
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
                v.findViewById<TextView>(R.id.tvNombre).text = "Marca ${position + 1}"
                v.findViewById<TextView>(R.id.tvTiempo).text = Horario.fmt(marcas[position])
                return v
            }
        }
        tvMarcasTitulo.text = if (marcas.isEmpty()) {
            "Sin marcas · toca Marcar para agregar una"
        } else {
            "Marcas (${marcas.size}) · toca para saltar, mantén para borrar"
        }
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
        Toast.makeText(this, "Marca agregada · ${Horario.fmt(t)}", Toast.LENGTH_SHORT).show()
    }

    private fun borrarMarca(pos: Int) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar marca ${pos + 1}?")
            .setMessage(Horario.fmt(marcas[pos]))
            .setPositiveButton("Borrar") { _, _ ->
                marcas.removeAt(pos)
                guardarMarcas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- compartir / menú ----------------

    private fun compartir() {
        val i = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .setType("audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Compartir"))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_ABRIR, 0, "Abrir con otra app")
        menu.add(0, M_BORRAR, 1, "Borrar grabación")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_ABRIR -> abrirCon()
            M_BORRAR -> confirmarBorrar()
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
            Toast.makeText(this, "No hay otra app para reproducir audio", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmarBorrar() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar esta grabación?")
            .setMessage("También se borran sus marcas. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                if (esta()) enviar(PlayerService.ACT_CERRAR)
                val fallidos = Biblioteca.borrarUris(this, listOfNotNull(uri, marcasUri))
                if (fallidos.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    try {
                        val pi = MediaStore.createDeleteRequest(contentResolver, fallidos)
                        startIntentSenderForResult(pi.intentSender, REQ_BORRAR, null, 0, 0, 0)
                    } catch (e: Exception) {
                        Toast.makeText(this, "No se pudo borrar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Borrado", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BORRAR) finish()
    }
}
