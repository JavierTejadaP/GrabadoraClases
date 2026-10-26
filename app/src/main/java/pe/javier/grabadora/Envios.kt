package pe.javier.grabadora

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Compartir grabaciones (a Drive, WhatsApp…) y anotar cuáles ya salieron del celular,
 * para saber qué falta pasar a la laptop. Solo se marcan si se eligió una app en el
 * menú de compartir; si se cancela, quedan como pendientes.
 */
object Envios {

    const val ACCION_ENVIADAS = "pe.javier.grabadora.ENVIADAS"
    const val EXTRA_ARCHIVOS = "archivos"

    fun compartir(act: Activity, lista: List<Grabacion>) =
        compartir(act, lista.map { it.uri }, lista.map { it.nombre })

    /** [archivos]: nombres de las grabaciones, para marcarlas como enviadas. */
    fun compartir(act: Activity, audios: List<Uri>, archivos: List<String>) {
        if (audios.isEmpty()) return
        val uris = ArrayList(audios)
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.type = "audio/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // El sistema avisa a EnviadasReceiver cuando se elige una app del menú
        val aviso = PendingIntent.getBroadcast(
            act, 40,
            Intent(act, EnviadasReceiver::class.java)
                .setAction(ACCION_ENVIADAS)
                .putStringArrayListExtra(EXTRA_ARCHIVOS, ArrayList(archivos)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        act.startActivity(Intent.createChooser(i, act.getString(R.string.compartir), aviso.intentSender))
    }
}

/** Recibe la app elegida al compartir y marca esas grabaciones como enviadas. */
class EnviadasReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Envios.ACCION_ENVIADAS) return
        val archivos = intent.getStringArrayListExtra(Envios.EXTRA_ARCHIVOS) ?: return
        Biblioteca.marcarEnviadas(ctx, archivos)
    }
}
