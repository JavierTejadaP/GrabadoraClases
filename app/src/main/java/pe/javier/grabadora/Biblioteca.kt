package pe.javier.grabadora

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.util.Locale

data class Grabacion(
    val id: Long,
    val uri: Uri,
    val nombre: String,
    val curso: Curso?,       // null = grabación libre
    val tipo: String?,
    val fechaMs: Long,
    val bytes: Long,
    val marcasUri: Uri?,
    val numMarcas: Int,
    val tema: String? = null,
) {
    /** Estimada por tamaño: se graba a 48 kbps. */
    val duracionAproxMs: Long get() = bytes * 8 * 1000 / 48000
}

/**
 * Grabaciones de Descargas/GrabadoraClases. Lleva un índice en [BaseDatos] con el curso de cada
 * una y cuántas marcas tiene, así no hay que abrir todos los archivos cada vez.
 */
object Biblioteca {

    const val SUFIJO_MARCAS = " - marcas.txt"

    /** Un archivo visto en la carpeta. [firma] cambia cuando el archivo cambia (tamaño y fecha). */
    private class Archivo(val uri: Uri, val nombre: String, val bytes: Long, val fechaMs: Long, val firma: Long)

    private class Fila(val id: Long, val uri: String, val bytes: Long, val marcasUri: String?, val marcasFirma: Long?)

    // ---------------- cargar ----------------

    /**
     * Pone al día el índice con lo que hay en la carpeta y devuelve las grabaciones, de la más
     * reciente a la más antigua. Hace E/S: llamar fuera del hilo principal.
     */
    @Synchronized
    fun cargar(ctx: Context): List<Grabacion> {
        sincronizar(ctx)
        return leer(ctx)
    }

    private fun archivoActual(): String? =
        if (RecorderService.estado != RecorderService.Estado.DETENIDO) RecorderService.archivoActual else null

    private fun sincronizar(ctx: Context) {
        val audios = LinkedHashMap<String, Archivo>()
        val marcas = HashMap<String, Archivo>()
        for (f in listarMediaStore(ctx) + listarCarpeta(ctx)) {
            when {
                f.nombre.endsWith(".aac", ignoreCase = true) -> audios.putIfAbsent(f.nombre, f)
                f.nombre.endsWith(SUFIJO_MARCAS) -> marcas.putIfAbsent(f.nombre.removeSuffix(SUFIJO_MARCAS), f)
            }
        }
        val actual = archivoActual()
        val db = BaseDatos.de(ctx).writableDatabase

        val filas = HashMap<String, Fila>()
        db.rawQuery("SELECT id, archivo, uri, bytes, marcas_uri, marcas_firma FROM grabaciones", null).use { c ->
            while (c.moveToNext()) {
                filas[c.getString(1)] = Fila(
                    c.getLong(0), c.getString(2), c.getLong(3), c.getString(4),
                    if (c.isNull(5)) null else c.getLong(5)
                )
            }
        }

        // Los cursos de los archivos nuevos se resuelven antes de abrir la transacción
        val nuevos = audios.keys.filter { it !in filas && it != actual }.associateWith { cursoYTipo(ctx, it) }

        db.beginTransaction()
        try {
            for ((nombre, f) in audios) {
                if (nombre == actual) continue
                val m = marcas[base(nombre)]
                val fila = filas[nombre]
                val v = ContentValues()
                if (fila == null) {
                    val (curso, tipo) = nuevos.getValue(nombre)
                    v.put("archivo", nombre)
                    v.put("uri", f.uri.toString())
                    v.put("curso_id", curso?.id)
                    v.put("tipo", tipo)
                    v.put("fecha_ms", f.fechaMs)
                    v.put("bytes", f.bytes)
                    ponerMarcas(ctx, v, m)
                    db.insertWithOnConflict("grabaciones", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                    continue
                }
                if (fila.uri != f.uri.toString() || fila.bytes != f.bytes) {
                    v.put("uri", f.uri.toString())
                    v.put("bytes", f.bytes)
                }
                when {
                    m == null -> if (fila.marcasUri != null) ponerMarcas(ctx, v, null)
                    m.uri.toString() != fila.marcasUri -> ponerMarcas(ctx, v, m)
                    // La app las acaba de escribir y ya sabe cuántas son: solo se anota la firma
                    fila.marcasFirma == null -> v.put("marcas_firma", m.firma)
                    fila.marcasFirma != m.firma -> ponerMarcas(ctx, v, m)
                }
                if (v.size() > 0) db.update("grabaciones", v, "id = ?", arrayOf(fila.id.toString()))
            }
            // Las que ya no están en la carpeta (borradas desde otra app, o sin acceso tras reinstalar)
            for ((nombre, fila) in filas) {
                if (nombre !in audios && nombre != actual) db.delete("grabaciones", "id = ?", arrayOf(fila.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun leer(ctx: Context): List<Grabacion> {
        val actual = archivoActual()
        val lista = mutableListOf<Grabacion>()
        BaseDatos.de(ctx).readableDatabase.rawQuery(
            "SELECT id, uri, archivo, curso_id, tipo, fecha_ms, bytes, marcas_uri, num_marcas, tema " +
                "FROM grabaciones ORDER BY fecha_ms DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val archivo = c.getString(2)
                if (archivo == actual) continue
                lista.add(
                    Grabacion(
                        id = c.getLong(0),
                        uri = Uri.parse(c.getString(1)),
                        nombre = archivo,
                        curso = Horario.curso(if (c.isNull(3)) null else c.getLong(3)),
                        tipo = c.getString(4),
                        fechaMs = c.getLong(5),
                        bytes = c.getLong(6),
                        marcasUri = c.getString(7)?.let { Uri.parse(it) },
                        numMarcas = c.getInt(8),
                        tema = c.getString(9)?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        return lista
    }

    private fun base(nombreAudio: String) = nombreAudio.dropLast(4)   // sin ".aac"

    /** "Calculo I - Teoria - 2026-10-17.aac" -> (Cálculo I, teoría). "Grabacion - …" -> (null, null). */
    private fun cursoYTipo(ctx: Context, nombreAudio: String): Pair<Curso?, String?> {
        val partes = base(nombreAudio).split(" - ")
        if (partes.size < 3 || partes[0] == "Grabacion") return null to null
        return Horario.cursoParaArchivo(ctx, partes[0]) to Horario.tipoDeTexto(partes[1])
    }

    private fun ponerMarcas(ctx: Context, v: ContentValues, m: Archivo?) {
        if (m == null) {
            v.putNull("marcas_uri")
            v.putNull("marcas_firma")
            v.put("num_marcas", 0)
        } else {
            v.put("marcas_uri", m.uri.toString())
            v.put("marcas_firma", m.firma)
            v.put("num_marcas", contarMarcas(ctx.contentResolver, m.uri))
        }
    }

    private fun listarMediaStore(ctx: Context): List<Archivo> {
        val coleccion = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proyeccion = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val lista = mutableListOf<Archivo>()
        runCatching {
            ctx.contentResolver.query(
                coleccion, proyeccion,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${RecorderService.CARPETA}%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val nombre = c.getString(1) ?: continue
                    val bytes = c.getLong(2)
                    lista.add(
                        Archivo(
                            ContentUris.withAppendedId(coleccion, c.getLong(0)), nombre, bytes,
                            c.getLong(3) * 1000, bytes * 31 + c.getLong(4)
                        )
                    )
                }
            }
        }
        return lista
    }

    // ---------------- registrar desde la app ----------------

    /** La grabadora anota la grabación apenas la crea, con su curso exacto. */
    fun registrar(ctx: Context, archivo: String, uri: Uri, curso: Curso?, tipo: String?) {
        val v = ContentValues().apply {
            put("archivo", archivo)
            put("uri", uri.toString())
            put("curso_id", curso?.id)
            put("tipo", tipo)
            put("fecha_ms", System.currentTimeMillis())
            put("bytes", 0L)
        }
        runCatching {
            BaseDatos.de(ctx).writableDatabase
                .insertWithOnConflict("grabaciones", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Anota el archivo de marcas y cuántas tiene, después de escribirlo. */
    fun actualizarMarcas(ctx: Context, archivo: String, marcasUri: Uri?, num: Int) {
        val v = ContentValues().apply {
            put("marcas_uri", marcasUri?.toString())
            putNull("marcas_firma")
            put("num_marcas", num)
        }
        runCatching {
            BaseDatos.de(ctx).writableDatabase.update("grabaciones", v, "archivo = ?", arrayOf(archivo))
        }
    }

    /** Tema de la clase ("Derivadas parciales"). Vacío lo borra. */
    fun ponerTema(ctx: Context, archivo: String, tema: String?) {
        val v = ContentValues().apply { put("tema", tema?.trim()?.takeIf { it.isNotEmpty() }) }
        runCatching {
            BaseDatos.de(ctx).writableDatabase.update("grabaciones", v, "archivo = ?", arrayOf(archivo))
        }
    }

    // ---------------- búsqueda ----------------

    private fun normalizar(s: String) = Horario.sinTildes(s).lowercase(Locale.ROOT)

    /**
     * ¿Cada palabra de [consulta] aparece en alguno de los [textos]? Sin importar tildes ni
     * mayúsculas: "derivadas calculo" encuentra la clase de Cálculo con tema "Derivadas".
     */
    fun coincide(textos: List<String?>, consulta: String): Boolean {
        val palabras = normalizar(consulta).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (palabras.isEmpty()) return true
        val todo = textos.filterNotNull().joinToString(" ") { normalizar(it) }
        return palabras.all { it in todo }
    }

    // ---------------- carpeta con acceso (reinstalación) ----------------

    /**
     * Al desinstalar, Android deja de considerar nuestros los archivos ya grabados y
     * MediaStore los oculta. Con acceso a la carpeta (selector del sistema) se vuelven a ver.
     */
    fun carpetaConAcceso(ctx: Context): Uri? =
        ctx.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission }?.uri

    fun intentElegirCarpeta(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Environment.DIRECTORY_DOWNLOADS}/${RecorderService.CARPETA}"
            )
        )
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }

    fun guardarCarpeta(ctx: Context, tree: Uri) {
        val cr = ctx.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        cr.persistedUriPermissions.filter { it.uri != tree }.forEach {
            runCatching { cr.releasePersistableUriPermission(it.uri, flags) }
        }
        cr.takePersistableUriPermission(tree, flags)
    }

    private fun listarCarpeta(ctx: Context): List<Archivo> {
        val tree = carpetaConAcceso(ctx) ?: return emptyList()
        val lista = mutableListOf<Archivo>()
        runCatching {
            val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val proyeccion = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            ctx.contentResolver.query(hijos, proyeccion, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val nombre = c.getString(1) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    val bytes = c.getLong(2)
                    lista.add(Archivo(uri, nombre, bytes, c.getLong(3), bytes * 31 + c.getLong(3)))
                }
            }
        }
        return lista
    }

    // ---------------- marcas ----------------

    private fun contarMarcas(cr: ContentResolver, uri: Uri): Int = runCatching {
        cr.openInputStream(uri)?.bufferedReader()?.use { r -> r.lineSequence().count { it.startsWith("Marca ") } } ?: 0
    }.getOrDefault(0)

    fun leerTexto(cr: ContentResolver, uri: Uri): String = runCatching {
        cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
    }.getOrDefault("")

    /** Busca el archivo de marcas de una grabación por su nombre. */
    fun buscarMarcas(ctx: Context, nombreAudio: String): Uri? {
        if (nombreAudio.isEmpty()) return null
        runCatching {
            BaseDatos.de(ctx).readableDatabase.rawQuery(
                "SELECT marcas_uri FROM grabaciones WHERE archivo = ?", arrayOf(nombreAudio)
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return Uri.parse(c.getString(0)) }
        }
        val coleccion = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val nombreMarcas = base(nombreAudio) + SUFIJO_MARCAS
        return ctx.contentResolver.query(
            coleccion, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(nombreMarcas), null
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(coleccion, c.getLong(0)) else null }
            ?: listarCarpeta(ctx).firstOrNull { it.nombre == nombreMarcas }?.uri
    }

    private val reMarca = Regex("""Marca \d+ - (\d+):(\d{2}):(\d{2})""")

    fun parsearMarcas(texto: String): List<Long> =
        reMarca.findAll(texto).map { m ->
            val (h, mi, s) = m.destructured
            (h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000
        }.toList()

    /** Texto del archivo de marcas. El formato no se traduce: la app lo vuelve a leer. */
    fun textoMarcas(titulo: String, nombreAudio: String, lista: List<Long>): String = buildString {
        appendLine(titulo)
        appendLine(nombreAudio)
        appendLine()
        lista.forEachIndexed { i, ms -> appendLine("Marca ${i + 1} - ${Horario.fmt(ms)}") }
    }

    /** Escribe (o crea) el archivo de marcas de una grabación. Devuelve su Uri. */
    fun guardarMarcas(ctx: Context, nombreAudio: String, existente: Uri?, titulo: String, lista: List<Long>): Uri? {
        val cr = ctx.contentResolver
        var u = existente
        try {
            if (u == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, base(nombreAudio) + SUFIJO_MARCAS)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + RecorderService.CARPETA)
                }
                u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }
            val texto = textoMarcas(titulo, nombreAudio, lista)
            u?.let { cr.openOutputStream(it, "wt")?.use { o -> o.write(texto.toByteArray(Charsets.UTF_8)) } }
        } catch (_: Exception) {
        }
        actualizarMarcas(ctx, nombreAudio, u, lista.size)
        return u
    }

    // ---------------- borrar ----------------

    /** Borra audio + marcas. Devuelve los que el sistema no dejó borrar directo. */
    fun borrar(ctx: Context, lista: List<Grabacion>): List<Uri> {
        val fallidos = borrarUris(ctx, lista.flatMap { listOfNotNull(it.uri, it.marcasUri) })
        val db = BaseDatos.de(ctx).writableDatabase
        for (g in lista) {
            if (g.uri !in fallidos) runCatching { db.delete("grabaciones", "id = ?", arrayOf(g.id.toString())) }
        }
        return fallidos
    }

    fun borrarUris(ctx: Context, uris: List<Uri>): List<Uri> {
        val fallidos = mutableListOf<Uri>()
        for (u in uris) {
            if (DocumentsContract.isDocumentUri(ctx, u)) {
                runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, u) }
                continue
            }
            try {
                ctx.contentResolver.delete(u, null, null)
            } catch (e: SecurityException) {
                fallidos.add(u)
            }
        }
        return fallidos
    }

    // ---------------- formato ----------------

    fun fmtDuracion(ctx: Context, ms: Long): String {
        val min = ms / 60000
        return when {
            min < 1 -> ctx.getString(R.string.duracion_menos_minuto)
            min < 60 -> ctx.getString(R.string.duracion_minutos, min)
            else -> ctx.getString(R.string.duracion_horas, min / 60, min % 60)
        }
    }

    fun fmtTamano(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}
