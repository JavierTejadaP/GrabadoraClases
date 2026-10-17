package pe.javier.grabadora

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista de cursos con cuántas grabaciones tiene cada uno. */
class CursosActivity : Activity() {

    companion object {
        private const val REQ_CARPETA = 30
    }

    private class Entrada(val curso: String?, val cantidad: Int, val ultima: Long?)

    private val fmtCorta = SimpleDateFormat("d MMM", Locale.forLanguageTag("es-PE"))
    private lateinit var lista: ListView
    private lateinit var tvRecuperar: TextView
    private var entradas: List<Entrada> = emptyList()

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
            text = "¿Faltan grabaciones de antes de reinstalar la app?\nToca aquí y elige la carpeta GrabadoraClases"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.marca))
            setPadding(48, 40, 48, 40)
            setOnClickListener { elegirCarpeta() }
        }
        pie.addView(tvRecuperar)
        lista.addFooterView(pie, null, false)
        setContentView(lista)
        lista.setOnItemClickListener { _, _, pos, _ ->
            startActivity(
                Intent(this, GrabacionesActivity::class.java)
                    .putExtra(GrabacionesActivity.EXTRA_CURSO, entradas[pos].curso)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    private fun cargar() {
        val todas = Biblioteca.cargar(this)
        val nombres = (Horario.cursos + todas.mapNotNull { it.curso }).distinct().sorted()
        fun entrada(c: String?): Entrada {
            val de = todas.filter { it.curso == c }
            return Entrada(c, de.size, de.maxOfOrNull { it.fechaMs })
        }
        entradas = nombres.map { entrada(it) } + entrada(null)
        tvRecuperar.visibility = if (Biblioteca.carpetaConAcceso(this) == null) View.VISIBLE else View.GONE

        lista.adapter = object : BaseAdapter() {
            override fun getCount() = entradas.size
            override fun getItem(position: Int) = entradas[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_curso, parent, false)
                val e = entradas[position]
                val ini = v.findViewById<TextView>(R.id.tvIniciales)
                ini.text = Horario.iniciales(e.curso)
                ini.backgroundTintList = ColorStateList.valueOf(Horario.color(e.curso))
                v.findViewById<TextView>(R.id.tvNombre).text = e.curso ?: "Grabaciones libres"
                v.findViewById<TextView>(R.id.tvDetalle).text = when (e.cantidad) {
                    0 -> "Sin grabaciones"
                    1 -> "1 grabación"
                    else -> "${e.cantidad} grabaciones"
                } + (e.ultima?.let { " · última ${fmtCorta.format(Date(it))}" } ?: "")
                v.alpha = if (e.cantidad == 0) 0.55f else 1f
                return v
            }
        }
    }

    private fun elegirCarpeta() {
        try {
            startActivityForResult(Biblioteca.intentElegirCarpeta(), REQ_CARPETA)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No se pudo abrir el selector de carpetas", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode != REQ_CARPETA || resultCode != RESULT_OK || tree == null) return
        val antes = Biblioteca.cargar(this).size
        try {
            Biblioteca.guardarCarpeta(this, tree)
        } catch (e: SecurityException) {
            Toast.makeText(this, "No se pudo guardar el acceso: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        cargar()
        val nuevas = Biblioteca.cargar(this).size - antes
        Toast.makeText(
            this,
            when (nuevas) {
                0 -> "No se encontraron grabaciones anteriores en esa carpeta"
                1 -> "Se recuperó 1 grabación"
                else -> "Se recuperaron $nuevas grabaciones"
            },
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
