package pe.javier.grabadora

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Un ZIP con las grabaciones de un curso, sus marcas y un índice, para pasarlas a la PC o a Drive. */
object Exportar {

    /**
     * Escribe el ZIP en [destino] (elegido por el usuario con el selector del sistema).
     * Hace E/S: llamar fuera del hilo principal. Devuelve cuántas grabaciones entraron.
     */
    fun zip(ctx: Context, lista: List<Grabacion>, destino: Uri, progreso: (hechas: Int, total: Int) -> Unit): Int {
        val cr = ctx.contentResolver
        var ok = 0
        val salida = cr.openOutputStream(destino, "w") ?: throw IOException("No se pudo abrir el destino")
        ZipOutputStream(BufferedOutputStream(salida)).use { zip ->
            lista.forEachIndexed { i, g ->
                progreso(i, lista.size)
                // El audio ya está comprimido: guardarlo tal cual es mucho más rápido
                zip.setLevel(Deflater.NO_COMPRESSION)
                val entro = runCatching {
                    cr.openInputStream(g.uri)?.use { entrada ->
                        zip.putNextEntry(ZipEntry(g.nombre).apply { time = g.fechaMs })
                        entrada.copyTo(zip)
                        zip.closeEntry()
                        true
                    } ?: false
                }.getOrDefault(false)
                if (entro) ok++

                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                g.marcasUri?.let { m ->
                    runCatching {
                        cr.openInputStream(m)?.use { entrada ->
                            zip.putNextEntry(ZipEntry(g.nombre.removeSuffix(".aac") + Biblioteca.SUFIJO_MARCAS))
                            entrada.copyTo(zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(ctx.getString(R.string.zip_indice_nombre)))
            zip.write(indice(ctx, lista).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        progreso(lista.size, lista.size)
        return ok
    }

    /** Una línea por grabación: fecha, clase, tema y archivo. */
    private fun indice(ctx: Context, lista: List<Grabacion>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return buildString {
            for (g in lista.sortedBy { it.fechaMs }) {
                val partes = listOfNotNull(
                    fmt.format(Date(g.fechaMs)),
                    Horario.etiqueta(ctx, g.curso, g.tipo),
                    g.tema,
                    g.nombre,
                )
                appendLine(partes.joinToString(" · "))
            }
        }
    }
}
