package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
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
        private const val M_CURSOS = 2
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
            setText(R.string.agregar_clase_boton)
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
        tvAyuda.setText(if (filas.isEmpty()) R.string.horario_vacio else R.string.horario_ayuda)

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
                            setTypeface(typeface, Typeface.BOLD)
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
                        ini.text = Horario.iniciales(b.curso.nombre)
                        ini.backgroundTintList = ColorStateList.valueOf(Horario.color(b.curso))
                        v.findViewById<TextView>(R.id.tvNombre).text = b.curso.nombre
                        v.findViewById<TextView>(R.id.tvDetalle).text = getString(
                            R.string.tipo_y_horas,
                            Horario.etiquetaTipo(this@HorarioActivity, b.tipo), Horario.hhmm(b.ini), Horario.hhmm(b.fin)
                        )
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

        fun etiqueta(texto: Int) = TextView(this).apply {
            setText(texto)
            textSize = 12f
            setTextColor(getColor(R.color.texto2))
            setPadding(0, dp(14), 0, dp(4))
        }

        val etCurso = AutoCompleteTextView(this).apply {
            setSingleLine()
            setHint(R.string.curso_ejemplo)
            threshold = 1
            setAdapter(
                ArrayAdapter(this@HorarioActivity, android.R.layout.simple_dropdown_item_1line, Horario.cursos.map { it.nombre })
            )
            setText(existente?.curso?.nombre ?: "")
        }
        val spTipo = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@HorarioActivity, android.R.layout.simple_spinner_dropdown_item,
                Horario.tipos.map { Horario.etiquetaTipo(this@HorarioActivity, it) }
            )
            setSelection(Horario.tipos.indexOf(existente?.tipo ?: Horario.TEORIA).coerceAtLeast(0))
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
            tvIni.text = getString(R.string.hora_inicio, Horario.hhmm(ini))
            tvFin.text = getString(R.string.hora_fin, Horario.hhmm(fin))
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
            addView(etiqueta(R.string.campo_curso))
            addView(etCurso)
            addView(etiqueta(R.string.campo_tipo))
            addView(spTipo)
            addView(etiqueta(R.string.campo_dia))
            addView(spDia)
            addView(etiqueta(R.string.campo_hora))
            addView(filaHoras)
            addView(tvError)
        }

        val b = AlertDialog.Builder(this)
            .setTitle(if (existente == null) R.string.agregar_clase else R.string.editar_clase)
            .setView(form)
            .setPositiveButton(R.string.guardar, null)
            .setNegativeButton(R.string.cancelar, null)
        if (existente != null) b.setNeutralButton(R.string.borrar) { _, _ -> confirmarBorrar(existente) }
        val dialogo = b.show()

        // Se valida antes de cerrar
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val escrito = etCurso.text.toString().trim().replace(Regex("\\s+"), " ")
            // Si ya existe con otras mayúsculas/tildes, se usa el nombre existente
            val nombre = Horario.buscarPorNombre(escrito)?.nombre ?: escrito
            val dia = Horario.dias[spDia.selectedItemPosition]
            val problema = Horario.problemaNombre(nombre)
            val cruce = Horario.cruce(dia, ini, fin, existente)
            val error = when {
                problema != null -> getString(problema)
                fin <= ini -> getString(R.string.error_fin_antes_inicio)
                cruce != null -> getString(
                    R.string.error_cruce,
                    cruce.curso.nombre, Horario.etiquetaTipo(this, cruce.tipo), Horario.hhmm(cruce.ini), Horario.hhmm(cruce.fin)
                )
                else -> null
            }
            if (error != null) {
                tvError.text = error
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            Horario.guardarClase(this, existente, nombre, dia, ini, fin, Horario.tipos[spTipo.selectedItemPosition])
            cargar()
            dialogo.dismiss()
        }
    }

    private fun confirmarBorrar(b: Bloque) {
        AlertDialog.Builder(this)
            .setTitle(R.string.borrar_clase_titulo)
            .setMessage(
                getString(
                    R.string.borrar_clase_mensaje,
                    Horario.etiqueta(this, b.curso, b.tipo),
                    Horario.nombreDia(b.dia), Horario.hhmm(b.ini), Horario.hhmm(b.fin)
                )
            )
            .setPositiveButton(R.string.borrar) { _, _ ->
                Horario.borrarClase(this, b)
                cargar()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    // ---------------- menú ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_AGREGAR, 0, R.string.agregar).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, M_CURSOS, 1, R.string.editar_curso).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_AGREGAR -> editar(null)
            M_CURSOS -> DialogoCurso.elegirYEditar(this) { cargar() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
