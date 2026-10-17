package pe.javier.grabadora

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lista de cursos con cuántas grabaciones tiene cada uno, con buscador arriba
 * (por tema, curso o fecha). Mantener presionado un curso: renombrarlo o cambiarle el color.
 */
class CursosActivity : Activity() {

    companion object {
        private const val REQ_CARPETA = 30
    }

    private class Entrada(val curso: Curso?, val cantidad: Int, val ultima: Long?)

    private val fmtCorta = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), Locale.getDefault())
    private lateinit var vista: VistaGrabacion
    private lateinit var etBuscar: EditText
    private lateinit var tvSinResultados: TextView
    private lateinit var lista: ListView
    private lateinit var tvRecuperar: TextView
    private var todas: List<Grabacion> = emptyList()
    private var entradas: List<Entrada> = emptyList()
    private var resultados: List<Grabacion> = emptyList()
    private var scope: CoroutineScope? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buscando() = etBuscar.text.isNotBlank()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        vista = VistaGrabacion(this)

        etBuscar = EditText(this).apply {
            setSingleLine()
            setHint(R.string.buscar_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            background = getDrawable(R.drawable.bg_chip)
            setTextColor(getColor(R.color.texto))
            setHintTextColor(getColor(R.color.texto2))
            textSize = 15f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = pintarLista()
            })
        }
        tvSinResultados = TextView(this).apply {
            setText(R.string.sin_resultados)
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(getColor(R.color.texto2))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        lista = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = getDrawable(android.R.color.transparent)
            clipToPadding = false
            setPadding(0, 8, 0, 24)
        }
        val pie = FrameLayout(this)
        tvRecuperar = TextView(this).apply {
            setText(R.string.recuperar_grabaciones)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.marca))
            setPadding(48, 40, 48, 40)
            visibility = View.GONE
            setOnClickListener { elegirCarpeta() }
        }
        pie.addView(tvRecuperar)
        lista.addFooterView(pie, null, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(etBuscar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(8), dp(16), dp(4))
            })
            addView(tvSinResultados)
            addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        lista.setOnItemClickListener { _, _, pos, _ ->
            if (buscando()) {
                resultados.getOrNull(pos)?.let { vista.abrir(this, it) }
                return@setOnItemClickListener
            }
            val e = entradas.getOrNull(pos) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, GrabacionesActivity::class.java)
                    .putExtra(GrabacionesActivity.EXTRA_CURSO_ID, e.curso?.id ?: -1L)
            )
        }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            if (buscando()) return@setOnItemLongClickListener false
            val curso = entradas.getOrNull(pos)?.curso ?: return@setOnItemLongClickListener false
            DialogoCurso.editar(this, curso) { cargar() }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        scope = MainScope()
        cargar()
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    private fun cargar() {
        val s = scope ?: return
        s.launch {
            val nuevas = withContext(Dispatchers.IO) { Biblioteca.cargar(applicationContext) }
            mostrar(nuevas)
        }
    }

    private fun mostrar(nuevas: List<Grabacion>) {
        todas = nuevas
        // Cursos del horario y los que tienen grabaciones, aunque ya no estén en el horario
        val ids = (Horario.bloques.map { it.curso.id } + todas.mapNotNull { it.curso?.id }).toSet()
        val cursos = Horario.cursos.filter { it.id in ids }
        fun entrada(c: Curso?): Entrada {
            val de = todas.filter { it.curso?.id == c?.id }
            return Entrada(c, de.size, de.maxOfOrNull { it.fechaMs })
        }
        entradas = cursos.map { entrada(it) } + entrada(null)
        pintarLista()
    }

    /** Cursos, o resultados de búsqueda si hay algo escrito. */
    private fun pintarLista() {
        if (buscando()) {
            val consulta = etBuscar.text.toString()
            resultados = todas.filter { Biblioteca.coincide(vista.textosBusqueda(it), consulta) }
            tvSinResultados.visibility = if (resultados.isEmpty()) View.VISIBLE else View.GONE
            tvRecuperar.visibility = View.GONE
            lista.adapter = object : BaseAdapter() {
                override fun getCount() = resultados.size
                override fun getItem(position: Int) = resultados[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_grabacion, parent, false)
                    vista.pintar(v, resultados[position], conCurso = true)
                    return v
                }
            }
            return
        }

        tvSinResultados.visibility = View.GONE
        tvRecuperar.visibility = if (Biblioteca.carpetaConAcceso(this) == null) View.VISIBLE else View.GONE
        lista.adapter = object : BaseAdapter() {
            override fun getCount() = entradas.size
            override fun getItem(position: Int) = entradas[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_curso, parent, false)
                val e = entradas[position]
                val ini = v.findViewById<TextView>(R.id.tvIniciales)
                ini.text = Horario.iniciales(e.curso?.nombre)
                ini.backgroundTintList = ColorStateList.valueOf(Horario.color(e.curso))
                v.findViewById<TextView>(R.id.tvNombre).text = e.curso?.nombre ?: getString(R.string.grabaciones_libres)
                val cantidad = if (e.cantidad == 0) getString(R.string.sin_grabaciones_curso)
                else resources.getQuantityString(R.plurals.n_grabaciones, e.cantidad, e.cantidad)
                v.findViewById<TextView>(R.id.tvDetalle).text =
                    e.ultima?.let { getString(R.string.cantidad_y_ultima, cantidad, fmtCorta.format(Date(it))) } ?: cantidad
                v.alpha = if (e.cantidad == 0) 0.55f else 1f
                return v
            }
        }
    }

    private fun elegirCarpeta() {
        try {
            startActivityForResult(Biblioteca.intentElegirCarpeta(), REQ_CARPETA)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_selector_carpetas, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode != REQ_CARPETA || resultCode != RESULT_OK || tree == null) return
        val ctx = applicationContext
        MainScope().launch {
            val antes = withContext(Dispatchers.IO) { Biblioteca.cargar(ctx).size }
            try {
                Biblioteca.guardarCarpeta(ctx, tree)
            } catch (e: SecurityException) {
                Toast.makeText(ctx, getString(R.string.error_guardar_acceso, e.message ?: ""), Toast.LENGTH_LONG).show()
                return@launch
            }
            val despues = withContext(Dispatchers.IO) { Biblioteca.cargar(ctx) }
            if (!isDestroyed) mostrar(despues)
            val nuevas = despues.size - antes
            Toast.makeText(
                ctx,
                if (nuevas <= 0) getString(R.string.no_se_encontraron)
                else resources.getQuantityString(R.plurals.recuperadas, nuevas, nuevas),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
