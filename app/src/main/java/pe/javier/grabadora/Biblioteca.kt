package pe.javier.grabadora

import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.util.Locale

data class Grabacion(
    val uri: Uri,
    val nombre: String,
    val curso: String?,      // null = grabación libre
    val tipo: String,
    val fechaMs: Long,
    val bytes: Long,
    val marcasUri: Uri?,
    val numMarcas: Int,
) {
    /** Estimada por tamaño: se graba a 48 kbps. */
    val duracionAproxMs: Long get() = bytes * 8 * 1000 / 48000
}

/** Lee las grabaciones guardadas en Descargas/GrabadoraClases. */
object Biblioteca {

    private class Fila(val uri: Uri, val nombre: String, val bytes: Long, val fechaMs: Long)

    fun cargar(ctx: Context): List<Grabacion> {
        val cr = ctx.contentResolver
        val coleccion = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proyeccion = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val audios = mutableListOf<Fila>()
        val marcas = mutableMapOf<String, Uri>()

        cr.query(
            coleccion, proyeccion,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${RecorderService.CARPETA}%"),
            null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iNombre = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iTam = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iFecha = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val nombre = c.getString(iNombre) ?: continue
                val uri = ContentUris.withAppendedId(coleccion, c.getLong(iId))
                when {
                    nombre.endsWith(".aac", ignoreCase = true) ->
                        audios.add(Fila(uri, nombre, c.getLong(iTam), c.getLong(iFecha) * 1000))
                    nombre.endsWith(" - marcas.txt") ->
                        marcas[nombre.removeSuffix(" - marcas.txt")] = uri
                }
            }
        }

        // Archivos de una instalación anterior: MediaStore ya no los muestra, pero la carpeta con acceso sí
        val vistos = audios.map { it.nombre }.toHashSet()
        for (f in listarCarpeta(ctx)) {
            when {
                f.nombre.endsWith(".aac", ignoreCase = true) -> if (vistos.add(f.nombre)) audios.add(f)
                f.nombre.endsWith(" - marcas.txt") -> marcas.putIfAbsent(f.nombre.removeSuffix(" - marcas.txt"), f.uri)
            }
        }

        val actual = if (RecorderService.estado != RecorderService.Estado.DETENIDO) RecorderService.archivoActual else null

        return audios
            .filter { it.nombre != actual }
            .map { f ->
                val base = f.nombre.dropLast(4)
                val partes = base.split(" - ")
                val esCurso = partes.size >= 3 && partes[0] != "Grabacion"
                val curso = if (esCurso) Horario.cursoDe(partes[0]) else null
                val tipo = if (esCurso) Horario.tipoDe(partes[1]) else "Grabación libre"
                val mUri = marcas[base]
                Grabacion(f.uri, f.nombre, curso, tipo, f.fechaMs, f.bytes, mUri, mUri?.let { contarMarcas(cr, it) } ?: 0)
            }
            .sortedByDescending { it.fechaMs }
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

    private fun listarCarpeta(ctx: Context): List<Fila> {
        val tree = carpetaConAcceso(ctx) ?: return emptyList()
        val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val proyeccion = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val filas = mutableListOf<Fila>()
        runCatching {
            ctx.contentResolver.query(hijos, proyeccion, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val nombre = c.getString(1) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    filas.add(Fila(uri, nombre, c.getLong(2), c.getLong(3)))
                }
            }
        }
        return filas
    }

    private fun contarMarcas(cr: ContentResolver, uri: Uri): Int = runCatching {
        cr.openInputStream(uri)?.bufferedReader()?.use { r -> r.lineSequence().count { it.startsWith("Marca ") } } ?: 0
    }.getOrDefault(0)

    fun leerTexto(cr: ContentResolver, uri: Uri): String = runCatching {
        cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
    }.getOrDefault("")

    /** Borra audio + marcas. Devuelve los que el sistema no dejó borrar directo. */
    fun borrar(ctx: Context, lista: List<Grabacion>): List<Uri> =
        borrarUris(ctx, lista.flatMap { listOfNotNull(it.uri, it.marcasUri) })

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

    /** Busca el archivo de marcas de una grabación por su nombre. */
    fun buscarMarcas(ctx: Context, nombreAudio: String): Uri? {
        if (nombreAudio.isEmpty()) return null
        val coleccion = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val nombreMarcas = nombreAudio.removeSuffix(".aac") + " - marcas.txt"
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

    /** Escribe (o crea) el archivo de marcas de una grabación. Devuelve su Uri. */
    fun guardarMarcas(ctx: Context, nombreAudio: String, existente: Uri?, titulo: String, lista: List<Long>): Uri? {
        val cr = ctx.contentResolver
        var u = existente
        try {
            if (u == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nombreAudio.removeSuffix(".aac") + " - marcas.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + RecorderService.CARPETA)
                }
                u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }
            val texto = buildString {
                appendLine(titulo)
                appendLine(nombreAudio)
                appendLine()
                lista.forEachIndexed { i, ms -> appendLine("Marca ${i + 1} - ${Horario.fmt(ms)}") }
            }
            u?.let { cr.openOutputStream(it, "wt")?.use { o -> o.write(texto.toByteArray(Charsets.UTF_8)) } }
        } catch (_: Exception) {
        }
        return u
    }

    fun fmtDuracion(ms: Long): String {
        val min = ms / 60000
        return when {
            min < 1 -> "<1 min"
            min < 60 -> "~$min min"
            else -> "~${min / 60} h ${min % 60} min"
        }
    }

    fun fmtTamano(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}
