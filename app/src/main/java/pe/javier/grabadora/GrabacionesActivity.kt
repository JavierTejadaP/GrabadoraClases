package pe.javier.grabadora

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grabaciones de un curso, de la más reciente a la más antigua.
 * Toque: abre el reproductor.
 * Mantener presionado: seleccionar varias para compartir o borrar.
 * Menú: exportar todo el curso en un ZIP.
 */
class GrabacionesActivity : Activity() {

    companion object {
        /** Id del curso; -1 = grabaciones libres. */
        const val EXTRA_CURSO_ID = "curso_id"
        private const val REQ_BORRAR = 10
        private const val REQ_EXPORTAR = 11
        private const val M_COMPARTIR = 1
        private const val M_BORRAR = 2
        private const val M_TODO = 3
        private const val M_EXPORTAR = 4
    }

    private lateinit var vista: VistaGrabacion
    private var cursoId: Long? = null
    private lateinit var lista: ListView
    private var items: List<Grabacion> = emptyList()
    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vista = VistaGrabacion(this)
        cursoId = intent.getLongExtra(EXTRA_CURSO_ID, -1L).takeIf { it >= 0 }
        title = Horario.curso(cursoId)?.nombre ?: getString(R.string.grabaciones_libres)
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
            setText(R.string.aun_no_hay_grabaciones_aqui)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(48, 48, 48, 48)
            setTextColor(getColor(R.color.texto2))
        }
        root.addView(lista, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(vacio, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        lista.emptyView = vacio
        setContentView(root)

        lista.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { vista.abrir(this, it) } }

        lista.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
        lista.setMultiChoiceModeListener(object : AbsListView.MultiChoiceModeListener {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.add(0, M_COMPARTIR, 0, R.string.compartir)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu?.add(0, M_BORRAR, 1, R.string.borrar)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu?.add(0, M_TODO, 2, R.string.todas)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
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
                mode?.title = resources.getQuantityString(R.plurals.seleccionadas, n, n)
            }
        })
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
            mostrar(todas.filter { it.curso?.id == cursoId })
        }
    }

    private fun mostrar(nuevas: List<Grabacion>) {
        items = nuevas
        lista.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_grabacion, parent, false)
                vista.pintar(v, items[position], conCurso = false)
                return v
            }
        }
        invalidateOptionsMenu()
    }

    private fun seleccionados(): List<Grabacion> {
        val c = lista.checkedItemPositions ?: return emptyList()
        return (0 until c.size()).filter { c.valueAt(it) }.map { c.keyAt(it) }
            .filter { it in items.indices }.map { items[it] }
    }

    // ---------------- acciones ----------------

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
        startActivity(Intent.createChooser(i, getString(R.string.compartir)))
    }

    private fun confirmarBorrar(sel: List<Grabacion>, alTerminar: () -> Unit) {
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.borrar_grabaciones, sel.size, sel.size))
            .setMessage(R.string.borrar_mensaje)
            .setPositiveButton(R.string.borrar) { _, _ ->
                val fallidos = Biblioteca.borrar(this, sel)
                if (fallidos.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    try {
                        val pi = MediaStore.createDeleteRequest(contentResolver, fallidos)
                        startIntentSenderForResult(pi.intentSender, REQ_BORRAR, null, 0, 0, 0)
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.error_borrar, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, R.string.borrado, Toast.LENGTH_SHORT).show()
                }
                cargar()
                alTerminar()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    // ---------------- exportar ----------------

    private fun elegirDestinoZip() {
        val nombre = getString(R.string.zip_nombre, Horario.sinTildes(title.toString()))
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, nombre),
                REQ_EXPORTAR
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_selector_carpetas, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportar(destino: Uri) {
        val lista = items
        val dialogo = AlertDialog.Builder(this)
            .setTitle(R.string.exportando)
            .setMessage(getString(R.string.exportando_n, 0, lista.size))
            .setCancelable(false)
            .show()
        val ctx = applicationContext
        MainScope().launch {
            val resultado = withContext(Dispatchers.IO) {
                runCatching {
                    Exportar.zip(ctx, lista, destino) { hechas, total ->
                        runOnUiThread { dialogo.setMessage(getString(R.string.exportando_n, hechas, total)) }
                    }
                }
            }
            if (!isDestroyed) runCatching { dialogo.dismiss() }
            resultado
                .onSuccess { n -> Toast.makeText(ctx, resources.getQuantityString(R.plurals.exportadas, n, n), Toast.LENGTH_LONG).show() }
                .onFailure { e -> Toast.makeText(ctx, getString(R.string.error_exportar, e.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- resultados / menú ----------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BORRAR -> cargar()
            REQ_EXPORTAR -> if (resultCode == RESULT_OK) data?.data?.let { exportar(it) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_EXPORTAR, 0, R.string.exportar_zip)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_EXPORTAR)?.isEnabled = items.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_EXPORTAR -> elegirDestinoZip()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
