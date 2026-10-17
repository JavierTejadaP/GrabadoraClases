package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Renombrar un curso o cambiarle el color. Las grabaciones y clases lo siguen
 * porque se guardan con el id del curso, no con su nombre.
 */
object DialogoCurso {

    /** Primero elige qué curso editar. */
    fun elegirYEditar(act: Activity, alGuardar: () -> Unit) {
        val cursos = Horario.cursos
        if (cursos.isEmpty()) {
            AlertDialog.Builder(act)
                .setMessage(R.string.sin_cursos)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(act)
            .setTitle(R.string.editar_curso)
            .setItems(cursos.map { it.nombre }.toTypedArray()) { _, i -> editar(act, cursos[i], alGuardar) }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    fun editar(act: Activity, curso: Curso, alGuardar: () -> Unit) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var color = curso.color

        val etNombre = EditText(act).apply {
            setSingleLine()
            setText(curso.nombre)
            setSelection(text.length)
        }

        val fila = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val muestras = Horario.PALETA.map { c ->
            View(act).apply {
                background = act.getDrawable(R.drawable.bg_circulo)
                backgroundTintList = ColorStateList.valueOf(c)
                contentDescription = act.getString(R.string.color)
                setOnClickListener {
                    color = c
                    marcarElegido(fila, c)
                }
                tag = c
            }
        }
        muestras.forEach { v ->
            fila.addView(v, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(6) })
        }
        marcarElegido(fila, color)

        val tvError = TextView(act).apply {
            textSize = 13f
            setTextColor(act.getColor(R.color.acento))
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }

        val form = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(etNombre)
            addView(fila)
            addView(tvError)
        }

        val dialogo = AlertDialog.Builder(act)
            .setTitle(R.string.editar_curso)
            .setView(form)
            .setPositiveButton(R.string.guardar, null)
            .setNegativeButton(R.string.cancelar, null)
            .show()

        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nombre = etNombre.text.toString().trim().replace(Regex("\\s+"), " ")
            val problema = Horario.problemaNombre(nombre)
            val error = when {
                problema != null -> act.getString(problema)
                !Horario.editarCurso(act, curso, nombre, color) -> act.getString(R.string.error_nombre_repetido)
                else -> null
            }
            if (error != null) {
                tvError.text = error
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            alGuardar()
            dialogo.dismiss()
        }
    }

    /** El color elegido se ve más grande. */
    private fun marcarElegido(fila: LinearLayout, color: Int) {
        for (i in 0 until fila.childCount) {
            val v = fila.getChildAt(i)
            val elegido = v.tag == color
            v.scaleX = if (elegido) 1.25f else 0.85f
            v.scaleY = v.scaleX
            v.alpha = if (elegido) 1f else 0.6f
        }
    }
}
