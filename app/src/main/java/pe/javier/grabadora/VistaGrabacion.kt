package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cómo se muestra una grabación en las listas (item_grabacion) y cómo se abre. */
class VistaGrabacion(private val ctx: Context) {

    private val loc = Locale.getDefault()
    private val fmtFecha = SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEEdMMMMyyyy"), loc)
    private val fmtCorta = SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEdMMM"), loc)
    private val fmtDia = SimpleDateFormat("d", loc)
    private val fmtMes = SimpleDateFormat("MMM", loc)
    private val fmtSemana = SimpleDateFormat("EEEE", loc)
    private val fmtHora = DateFormat.getTimeFormat(ctx)

    private fun mayuscula(s: String) = s.replaceFirstChar { it.titlecase(loc) }

    /** "Teoría · Lunes", o la fecha completa si es grabación libre. */
    private fun base(g: Grabacion): String {
        val fecha = Date(g.fechaMs)
        return if (g.curso == null) mayuscula(fmtFecha.format(fecha))
        else ctx.getString(R.string.tipo_y_dia, Horario.etiquetaTipo(ctx, g.tipo), mayuscula(fmtSemana.format(fecha)))
    }

    /**
     * Pinta una fila. Con tema, el tema va de título y "Teoría · Lunes" pasa al detalle.
     * [conCurso]: en los resultados de búsqueda se dice de qué curso es.
     */
    fun pintar(v: View, g: Grabacion, conCurso: Boolean) {
        val fecha = Date(g.fechaMs)
        v.findViewById<View>(R.id.bloqueFecha).backgroundTintList = ColorStateList.valueOf(Horario.color(g.curso))
        v.findViewById<TextView>(R.id.tvDia).text = fmtDia.format(fecha)
        v.findViewById<TextView>(R.id.tvMes).text = fmtMes.format(fecha).uppercase(loc).removeSuffix(".")

        val partes = mutableListOf<String>()
        if (conCurso) partes.add(g.curso?.nombre ?: ctx.getString(R.string.grabacion_libre))
        if (g.tema != null) partes.add(if (conCurso) mayuscula(fmtCorta.format(fecha)) else base(g))
        partes.add(fmtHora.format(fecha))
        partes.add(Biblioteca.fmtDuracion(ctx, g.duracionAproxMs))
        if (!conCurso) partes.add(Biblioteca.fmtTamano(g.bytes))

        v.findViewById<TextView>(R.id.tvTitulo).text = g.tema ?: base(g)
        v.findViewById<TextView>(R.id.tvDetalle).text = partes.joinToString(" · ")
        val tvM = v.findViewById<TextView>(R.id.tvMarcas)
        tvM.visibility = if (g.numMarcas > 0) View.VISIBLE else View.GONE
        tvM.text = g.numMarcas.toString()
    }

    /** Textos en los que busca el buscador de "Mis grabaciones". */
    fun textosBusqueda(g: Grabacion): List<String?> {
        val fecha = Date(g.fechaMs)
        return listOf(
            g.tema,
            g.curso?.nombre ?: ctx.getString(R.string.grabacion_libre),
            Horario.etiquetaTipo(ctx, g.tipo),
            fmtFecha.format(fecha),
            fmtCorta.format(fecha),
        )
    }

    fun abrir(act: Activity, g: Grabacion) {
        val fecha = Date(g.fechaMs)
        act.startActivity(
            Intent(act, ReproductorActivity::class.java)
                .putExtra(ReproductorActivity.EXTRA_URI, g.uri.toString())
                .putExtra(ReproductorActivity.EXTRA_NOMBRE, g.nombre)
                .putExtra(ReproductorActivity.EXTRA_TITULO, Horario.etiqueta(act, g.curso, g.tipo))
                .putExtra(
                    ReproductorActivity.EXTRA_SUBTITULO,
                    act.getString(R.string.fecha_y_hora, mayuscula(fmtFecha.format(fecha)), fmtHora.format(fecha))
                )
                .putExtra(ReproductorActivity.EXTRA_MARCAS, g.marcasUri?.toString())
                .putExtra(ReproductorActivity.EXTRA_BYTES, g.bytes)
                .putExtra(ReproductorActivity.EXTRA_CURSO_ID, g.curso?.id ?: -1L)
                .putExtra(ReproductorActivity.EXTRA_TEMA, g.tema)
        )
    }

    companion object {
        /** Diálogo para escribir el tema de una clase. [alGuardar] recibe el tema (null = sin tema). */
        fun pedirTema(act: Activity, actual: String?, titulo: Int, cancelar: Int, alGuardar: (String?) -> Unit): AlertDialog {
            val d = act.resources.displayMetrics.density
            val et = EditText(act).apply {
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setHint(R.string.tema_ejemplo)
                setText(actual ?: "")
                setSelection(text.length)
            }
            val cont = FrameLayout(act).apply {
                setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
                addView(et)
            }
            return AlertDialog.Builder(act)
                .setTitle(titulo)
                .setView(cont)
                .setPositiveButton(R.string.guardar) { _, _ ->
                    alGuardar(et.text.toString().trim().takeIf { it.isNotEmpty() })
                }
                .setNegativeButton(cancelar, null)
                .show()
        }
    }
}
