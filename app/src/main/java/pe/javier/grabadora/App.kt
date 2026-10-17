package pe.javier.grabadora

import android.app.Application

/** Carga el horario antes que cualquier pantalla o servicio, y deja programado el próximo aviso. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Horario.cargar(this)
        Avisos.programar(this)
    }
}
