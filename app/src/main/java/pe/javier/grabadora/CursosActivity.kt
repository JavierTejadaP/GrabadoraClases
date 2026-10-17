package pe.javier.grabadora

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
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
 * Lista de cursos con cuántas grabaciones tiene cada uno.
 * Mantener presionado un curso: renombrarlo o cambiarle el color.
 */
class CursosActivity : Activity() {

    companion object {
        private const val REQ_CARPETA = 30
    }

    private class Entrada(val curso: Curso?, val cantidad: Int, val ultima: Long?)

    private val fmtCorta = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), Locale.getDefault())
    private lateinit var lista: ListView
    private lateinit var tvRecuperar: TextView
    private var entradas: List<Entrada> = emptyList()
    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
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
        setContentView(lista)
        lista.setOnItemClickListener { _, _, pos, _ ->
            val e = entradas.getOrNull(pos) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, GrabacionesActivity::class.java)
                    .putExtra(GrabacionesActivity.EXTRA_CURSO_ID, e.curso?.id ?: -1L)
            )
        }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
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
            val todas = withContext(Dispatchers.IO) { Biblioteca.cargar(applicationContext) }
            mostrar(todas)
        }
    }

    private fun mostrar(todas: List<Grabacion>) {
        // Cursos del horario y los que tienen grabaciones, aunque ya no estén en el horario
        val ids = (Horario.bloques.map { it.curso.id } + todas.mapNotNull { it.curso?.id }).toSet()
        val cursos = Horario.cursos.filter { it.id in ids }
        fun entrada(c: Curso?): Entrada {
            val de = todas.filter { it.curso?.id == c?.id }
            return Entrada(c, de.size, de.maxOfOrNull { it.fechaMs })
        }
        entradas = cursos.map { entrada(it) } + entrada(null)
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
