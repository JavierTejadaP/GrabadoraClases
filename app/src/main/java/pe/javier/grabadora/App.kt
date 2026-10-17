package pe.javier.grabadora

import android.app.Application

/** Carga el horario antes que cualquier pantalla o servicio. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Horario.cargar(this)
    }
}
