package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import java.util.Calendar

/**
 * "Mi horario": los cursos y a qué hora es cada clase.
 * Con esto la app sabe qué clase grabar según el día y la hora.
 * Toque: editar o borrar una clase.
 */
class HorarioActivity : Activity() {

    companion object {
        private const val M_AGREGAR = 1
    }

    /** Fila de la lista: encabezado de día o una clase. */
    private sealed class Fila {
        class Dia(val nombre: String) : Fila()
        class Clase(val b: Bloque) : Fila()
    }

    private lateinit var lista: ListView
    private lateinit var tvAyuda: TextView
    private var filas: List<Fila> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        tvAyuda = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.texto2))
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        lista.addHeaderView(tvAyuda, null, false)

        val pie = TextView(this).apply {
            text = "+  Agregar clase"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.texto))
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { editar(null) }
        }
        val contPie = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            addView(pie)
        }
        lista.addFooterView(contPie, null, false)

        setContentView(lista)
        lista.setOnItemClickListener { _, _, pos, _ ->
            (filas.getOrNull(pos - lista.headerViewsCount) as? Fila.Clase)?.let { editar(it.b) }
        }
        cargar()
    }

    private fun cargar() {
        val f = mutableListOf<Fila>()
        for (dia in Horario.dias) {
            val del = Horario.bloques.filter { it.dia == dia }
            if (del.isEmpty()) continue
            f.add(Fila.Dia(Horario.nombreDia(dia)))
            del.forEach { f.add(Fila.Clase(it)) }
        }
        filas = f
        tvAyuda.text = if (filas.isEmpty()) {
            "Aún no tienes clases.\nAgrega cada curso con su día y hora: así la app elige sola qué clase grabar."
        } else {
            "Toca una clase para editarla o borrarla."
        }

        lista.adapter = object : BaseAdapter() {
            override fun getCount() = filas.size
            override fun getItem(position: Int) = filas[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (filas[position] is Fila.Dia) 0 else 1
            override fun isEnabled(position: Int) = filas[position] is Fila.Clase
            override fun areAllItemsEnabled() = false

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                when (val fila = filas[position]) {
                    is Fila.Dia -> {
                        val v = convertView as? TextView ?: TextView(parent.context).apply {
                            textSize = 13f
                            setTextColor(getColor(R.color.texto2))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(dp(20), dp(16), dp(20), dp(4))
                        }
                        v.text = fila.nombre.uppercase()
                        v
                    }
                    is Fila.Clase -> {
                        val v = convertView
                            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_curso, parent, false)
                        val b = fila.b
                        val ini = v.findViewById<TextView>(R.id.tvIniciales)
                        ini.text = Horario.iniciales(b.curso)
                        ini.backgroundTintList = ColorStateList.valueOf(Horario.color(b.curso))
                        v.findViewById<TextView>(R.id.tvNombre).text = b.curso
                        v.findViewById<TextView>(R.id.tvDetalle).text =
                            "${b.tipo} · ${Horario.hhmm(b.ini)} – ${Horario.hhmm(b.fin)}"
                        v
                    }
                }
        }
    }

    // ---------------- agregar / editar ----------------

    /** Abre el formulario. [existente] null = clase nueva. */
    private fun editar(existente: Bloque?) {
        val hoy = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        var ini = existente?.ini ?: (7 * 60)
        var fin = existente?.fin ?: (9 * 60)

        fun etiqueta(texto: String) = TextView(this).apply {
            text = texto
            textSize = 12f
            setTextColor(getColor(R.color.texto2))
            setPadding(0, dp(14), 0, dp(4))
        }

        val etCurso = AutoCompleteTextView(this).apply {
            setSingleLine()
            hint = "Ej. Cálculo I"
            threshold = 1
            setAdapter(ArrayAdapter(this@HorarioActivity, android.R.layout.simple_dropdown_item_1line, Horario.cursos))
            setText(existente?.curso ?: "")
        }
        val spTipo = Spinner(this).apply {
            adapter = ArrayAdapter(this@HorarioActivity, android.R.layout.simple_spinner_dropdown_item, Horario.tipos)
            setSelection(Horario.tipos.indexOf(existente?.tipo).coerceAtLeast(0))
        }
        val spDia = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@HorarioActivity, android.R.layout.simple_spinner_dropdown_item,
                Horario.dias.map { Horario.nombreDia(it) }
            )
            setSelection(Horario.dias.indexOf(existente?.dia ?: hoy).coerceAtLeast(0))
        }

        fun botonHora(): TextView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.texto))
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val tvIni = botonHora()
        val tvFin = botonHora()
        fun pintarHoras() {
            tvIni.text = "Inicio  ${Horario.hhmm(ini)}"
            tvFin.text = "Fin  ${Horario.hhmm(fin)}"
        }
        pintarHoras()
        val es24 = DateFormat.is24HourFormat(this)
        tvIni.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                val dur = (fin - ini).coerceAtLeast(60)
                ini = h * 60 + m
                fin = (ini + dur).coerceAtMost(24 * 60 - 1)   // mantiene la duración
                pintarHoras()
            }, ini / 60, ini % 60, es24).show()
        }
        tvFin.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                fin = h * 60 + m
                pintarHoras()
            }, fin / 60, fin % 60, es24).show()
        }
        val filaHoras = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvIni, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(10), 1))
            addView(tvFin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val tvError = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.acento))
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(etiqueta("CURSO"))
            addView(etCurso)
            addView(etiqueta("TIPO"))
            addView(spTipo)
            addView(etiqueta("DÍA"))
            addView(spDia)
            addView(etiqueta("HORA"))
            addView(filaHoras)
            addView(tvError)
        }

        val b = AlertDialog.Builder(this)
            .setTitle(if (existente == null) "Agregar clase" else "Editar clase")
            .setView(form)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
        if (existente != null) b.setNeutralButton("Borrar") { _, _ -> confirmarBorrar(existente) }
        val dialogo = b.show()

        // Se valida antes de cerrar
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val curso = etCurso.text.toString().trim().replace(Regex("\\s+"), " ")
            // Si ya existe con otras mayúsculas/tildes, se usa el nombre existente
            val nombre = Horario.cursos.firstOrNull {
                Horario.sinTildes(it).equals(Horario.sinTildes(curso), ignoreCase = true)
            } ?: curso
            val nuevo = Bloque(
                Horario.dias[spDia.selectedItemPosition], ini, fin, nombre,
                Horario.tipos[spTipo.selectedItemPosition]
            )
            val cruce = Horario.cruce(nuevo, existente)
            val error = when {
                Horario.problemaNombre(nombre) != null -> Horario.problemaNombre(nombre)
                fin <= ini -> "La hora de fin debe ser después del inicio"
                cruce != null ->
                    "Se cruza con ${cruce.curso} (${cruce.tipo}, ${Horario.hhmm(cruce.ini)} – ${Horario.hhmm(cruce.fin)})"
                else -> null
            }
            if (error != null) {
                tvError.text = error
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            Horario.guardar(this, Horario.bloques.filter { it != existente } + nuevo)
            cargar()
            dialogo.dismiss()
        }
    }

    private fun confirmarBorrar(b: Bloque) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar esta clase?")
            .setMessage("${b.curso} - ${b.tipo}\n${Horario.nombreDia(b.dia)} ${Horario.hhmm(b.ini)} – ${Horario.hhmm(b.fin)}\n\nLas grabaciones de este curso no se borran.")
            .setPositiveButton("Borrar") { _, _ ->
                Horario.guardar(this, Horario.bloques.filter { it != b })
                cargar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- menú ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_AGREGAR, 0, "Agregar").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_AGREGAR -> editar(null)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
