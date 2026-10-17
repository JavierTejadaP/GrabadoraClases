package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ActionMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grabaciones de un curso, de la más reciente a la más antigua.
 * Toque: abre el reproductor.
 * Mantener presionado: seleccionar varias para compartir o borrar.
 */
class GrabacionesActivity : Activity() {

    companion object {
        const val EXTRA_CURSO = "curso"
        private const val REQ_BORRAR = 10
        private const val M_COMPARTIR = 1
        private const val M_BORRAR = 2
        private const val M_TODO = 3
    }

    private val es = Locale.forLanguageTag("es-PE")
    private val fmtFecha = SimpleDateFormat("EEEE d 'de' MMMM yyyy", es)
    private val fmtHora = SimpleDateFormat("h:mm a", es)
    private val fmtDia = SimpleDateFormat("d", es)
    private val fmtMes = SimpleDateFormat("MMM", es)
    private val fmtSemana = SimpleDateFormat("EEEE", es)

    private var curso: String? = null
    private lateinit var lista: ListView
    private var items: List<Grabacion> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        curso = intent.getStringExtra(EXTRA_CURSO)
        title = curso ?: "Grabaciones libres"
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val root = FrameLayout(this)
        lista = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = getDrawable(android.R.color.transparent)
            clipToPadding = false
            setPadding(0, 8, 0, 24)
        }
        val vacio = TextView(this).apply {
            text = "Aún no hay grabaciones aquí"
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(48, 48, 48, 48)
            setTextColor(getColor(R.color.texto2))
        }
        root.addView(lista, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(vacio, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        lista.emptyView = vacio
        setContentView(root)

        lista.setOnItemClickListener { _, _, pos, _ -> abrir(items[pos]) }

        lista.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
        lista.setMultiChoiceModeListener(object : AbsListView.MultiChoiceModeListener {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.add(0, M_COMPARTIR, 0, "Compartir")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu?.add(0, M_BORRAR, 1, "Borrar")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu?.add(0, M_TODO, 2, "Todas")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                val sel = seleccionados()
                when (item?.itemId) {
                    M_COMPARTIR -> {
                        compartir(sel)
                        mode?.finish()
                    }
                    M_BORRAR -> confirmarBorrar(sel) { mode?.finish() }
                    M_TODO -> for (i in items.indices) lista.setItemChecked(i, true)
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode?) {}

            override fun onItemCheckedStateChanged(mode: ActionMode?, position: Int, id: Long, checked: Boolean) {
                val n = lista.checkedItemCount
                mode?.title = if (n == 1) "1 seleccionada" else "$n seleccionadas"
            }
        })
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    private fun cargar() {
        items = Biblioteca.cargar(this).filter { it.curso == curso }
        val color = ColorStateList.valueOf(Horario.color(curso))
        lista.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_grabacion, parent, false)
                val g = items[position]
                val fecha = Date(g.fechaMs)
                v.findViewById<View>(R.id.bloqueFecha).backgroundTintList = color
                v.findViewById<TextView>(R.id.tvDia).text = fmtDia.format(fecha)
                v.findViewById<TextView>(R.id.tvMes).text = fmtMes.format(fecha).uppercase(es).removeSuffix(".")
                v.findViewById<TextView>(R.id.tvTitulo).text =
                    if (g.curso == null) fmtFecha.format(fecha).replaceFirstChar { it.titlecase(es) }
                    else "${g.tipo} · ${fmtSemana.format(fecha).replaceFirstChar { it.titlecase(es) }}"
                v.findViewById<TextView>(R.id.tvDetalle).text =
                    "${fmtHora.format(fecha)} · ${Biblioteca.fmtDuracion(g.duracionAproxMs)} · ${Biblioteca.fmtTamano(g.bytes)}"
                val tvM = v.findViewById<TextView>(R.id.tvMarcas)
                tvM.visibility = if (g.numMarcas > 0) View.VISIBLE else View.GONE
                tvM.text = g.numMarcas.toString()
                return v
            }
        }
    }

    private fun seleccionados(): List<Grabacion> {
        val c = lista.checkedItemPositions ?: return emptyList()
        return (0 until c.size()).filter { c.valueAt(it) }.map { c.keyAt(it) }
            .filter { it in items.indices }.map { items[it] }
    }

    // ---------------- acciones ----------------

    private fun abrir(g: Grabacion) {
        val fecha = fmtFecha.format(Date(g.fechaMs)).replaceFirstChar { it.titlecase(es) }
        startActivity(
            Intent(this, ReproductorActivity::class.java)
                .putExtra(ReproductorActivity.EXTRA_URI, g.uri.toString())
                .putExtra(ReproductorActivity.EXTRA_NOMBRE, g.nombre)
                .putExtra(ReproductorActivity.EXTRA_TITULO, g.curso?.let { "$it - ${g.tipo}" } ?: "Grabación libre")
                .putExtra(ReproductorActivity.EXTRA_SUBTITULO, "$fecha · ${fmtHora.format(Date(g.fechaMs))}")
                .putExtra(ReproductorActivity.EXTRA_MARCAS, g.marcasUri?.toString())
                .putExtra(ReproductorActivity.EXTRA_BYTES, g.bytes)
        )
    }

    private fun compartir(sel: List<Grabacion>) {
        if (sel.isEmpty()) return
        val uris = ArrayList<Uri>(sel.map { it.uri })
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.type = "audio/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Compartir"))
    }

    private fun confirmarBorrar(sel: List<Grabacion>, alTerminar: () -> Unit) {
        if (sel.isEmpty()) return
        val titulo = if (sel.size == 1) "¿Borrar esta grabación?" else "¿Borrar ${sel.size} grabaciones?"
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage("También se borran sus marcas. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                val fallidos = Biblioteca.borrar(this, sel)
                if (fallidos.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    try {
                        val pi = MediaStore.createDeleteRequest(contentResolver, fallidos)
                        @Suppress("DEPRECATION")
                        startIntentSenderForResult(pi.intentSender, REQ_BORRAR, null, 0, 0, 0)
                    } catch (e: Exception) {
                        Toast.makeText(this, "No se pudo borrar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Borrado", Toast.LENGTH_SHORT).show()
                }
                cargar()
                alTerminar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BORRAR) cargar()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
