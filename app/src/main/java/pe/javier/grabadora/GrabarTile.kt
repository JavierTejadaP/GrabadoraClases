package pe.javier.grabadora

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Botón "Grabar clase" en los ajustes rápidos (el panel que se baja desde arriba).
 * Sin grabación: abre la app y empieza a grabar la clase que toca. Grabando: abre la grabación.
 * Android no deja encender el micrófono desde aquí directamente, por eso pasa por la app.
 */
class GrabarTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        actualizar()
    }

    override fun onClick() {
        super.onClick()
        val grabando = RecorderService.estado != RecorderService.Estado.DETENIDO
        val i = if (grabando) {
            Intent(this, GrabandoActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACCION_GRABAR)
                .putExtra(MainActivity.EXTRA_GRABAR, true)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 30, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun actualizar() {
        val t = qsTile ?: return
        val inst = RecorderService.flujo.value
        when (inst.estado) {
            RecorderService.Estado.GRABANDO -> {
                t.state = Tile.STATE_ACTIVE
                t.label = getString(R.string.tile_grabando)
            }
            RecorderService.Estado.PAUSADO -> {
                t.state = Tile.STATE_ACTIVE
                t.label = getString(R.string.en_pausa)
            }
            RecorderService.Estado.DETENIDO -> {
                t.state = Tile.STATE_INACTIVE
                t.label = getString(R.string.tile_grabar)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (inst.estado == RecorderService.Estado.DETENIDO) {
                Horario.detectar()?.let { it.curso.nombre } ?: getString(R.string.grabacion_libre)
            } else {
                inst.titulo
            }
        }
        t.updateTile()
    }
}
