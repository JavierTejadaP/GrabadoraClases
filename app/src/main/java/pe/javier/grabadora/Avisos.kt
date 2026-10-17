package pe.javier.grabadora

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Avisos según el horario: unos minutos antes de cada clase (con botón Grabar),
 * al terminar si sigues grabando, y opcionalmente detener solo un rato después.
 * Solo se programa la próxima alarma; al sonar se calcula la siguiente.
 */
object Avisos {

    enum class Tipo { INICIO, FIN, DETENER }

    data class Ajustes(val inicio: Boolean = true, val fin: Boolean = true, val detenerSolo: Boolean = false)

    data class Evento(val tipo: Tipo, val bloque: Bloque, val cuandoMs: Long)

    /** Minutos antes del inicio en que avisa. */
    const val MIN_ANTES = 5
    /** Minutos después del fin en que detiene sola (si está activado). */
    const val MIN_DETENER = 15
    /** Si la alarma llega más tarde que esto (celular apagado), ya no se avisa. */
    private const val TARDE_MS = 20 * 60_000L
    private const val VENTANA_MS = 10 * 60_000L

    const val ACCION_AVISO = "pe.javier.grabadora.AVISO"
    private const val EXTRA_TIPO = "tipo"
    private const val EXTRA_BLOQUE = "bloque"
    private const val EXTRA_CUANDO = "cuando"

    private const val CANAL = "recordatorios"
    const val NOTIF_INICIO = 10
    const val NOTIF_FIN = 11

    private const val PREFS = "avisos"

    // ---------------- ajustes ----------------

    fun ajustes(ctx: Context): Ajustes {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Ajustes(p.getBoolean("inicio", true), p.getBoolean("fin", true), p.getBoolean("detener", false))
    }

    fun guardarAjustes(ctx: Context, a: Ajustes) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("inicio", a.inicio)
            .putBoolean("fin", a.fin)
            .putBoolean("detener", a.detenerSolo)
            .apply()
        programar(ctx)
    }

    /** ¿Android deja que las alarmas suenen a la hora exacta? Si no, llegan con hasta 10 min de margen. */
    fun puntuales(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // ---------------- cálculo ----------------

    /** Minuto del día (desde las 00:00 del día de la clase) en que ocurre cada aviso. */
    private fun minuto(tipo: Tipo, b: Bloque): Int = when (tipo) {
        Tipo.INICIO -> b.ini - MIN_ANTES
        Tipo.FIN -> b.fin
        Tipo.DETENER -> b.fin + MIN_DETENER
    }

    /** Próxima vez, después de [ahora], que en el día [dia] de la semana se llega al [minuto]. */
    fun siguiente(dia: Int, minuto: Int, ahora: Calendar): Long {
        val c = ahora.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, (dia - ahora.get(Calendar.DAY_OF_WEEK) + 7) % 7)
        c.add(Calendar.MINUTE, minuto)
        if (c.timeInMillis <= ahora.timeInMillis) c.add(Calendar.DAY_OF_YEAR, 7)
        return c.timeInMillis
    }

    /** El próximo aviso que toca según el horario y los ajustes, o null si no hay ninguno. */
    fun proximo(bloques: List<Bloque>, a: Ajustes, ahora: Calendar): Evento? {
        val tipos = buildList {
            if (a.inicio) add(Tipo.INICIO)
            if (a.fin) add(Tipo.FIN)
            if (a.detenerSolo) add(Tipo.DETENER)
        }
        return bloques.flatMap { b -> tipos.map { t -> Evento(t, b, siguiente(b.dia, minuto(t, b), ahora)) } }
            .minByOrNull { it.cuandoMs }
    }

    // ---------------- alarma ----------------

    private fun pendiente(ctx: Context, ev: Evento?): PendingIntent {
        val i = Intent(ctx, AvisosReceiver::class.java).setAction(ACCION_AVISO)
        if (ev != null) {
            i.putExtra(EXTRA_TIPO, ev.tipo.name)
                .putExtra(EXTRA_BLOQUE, ev.bloque.id)
                .putExtra(EXTRA_CUANDO, ev.cuandoMs)
        }
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Programa la próxima alarma (y cancela la anterior). Se llama al abrir la app y tras cada cambio. */
    fun programar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pendiente(ctx, null))
        val ev = proximo(Horario.bloques, ajustes(ctx), Calendar.getInstance()) ?: return
        val pi = pendiente(ctx, ev)
        runCatching {
            if (puntuales(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.cuandoMs, pi)
            } else if (ev.tipo == Tipo.INICIO) {
                // Sin permiso de alarmas exactas: que llegue antes de la clase y no después
                am.setWindow(AlarmManager.RTC_WAKEUP, ev.cuandoMs - VENTANA_MS, VENTANA_MS, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, ev.cuandoMs, VENTANA_MS, pi)
            }
        }
    }

    // ---------------- al sonar ----------------

    fun disparar(ctx: Context, intent: Intent) {
        val tipo = runCatching { Tipo.valueOf(intent.getStringExtra(EXTRA_TIPO) ?: "") }.getOrNull() ?: return
        val b = Horario.bloques.firstOrNull { it.id == intent.getLongExtra(EXTRA_BLOQUE, -1L) } ?: return
        if (System.currentTimeMillis() - intent.getLongExtra(EXTRA_CUANDO, 0L) > TARDE_MS) return

        val grab = RecorderService.flujo.value
        val grabando = grab.estado != RecorderService.Estado.DETENIDO
        val esEsta = grab.cursoId == b.curso.id && grab.tipo == b.tipo
        when (tipo) {
            Tipo.INICIO -> if (!grabando) avisarInicio(ctx, b)
            Tipo.FIN -> if (grabando && (esEsta || grab.cursoId == null)) avisarFin(ctx, b)
            Tipo.DETENER -> if (grabando && esEsta) {
                ctx.startService(Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACT_STOP))
                notificar(
                    ctx, NOTIF_FIN,
                    Notification.Builder(ctx, CANAL)
                        .setSmallIcon(R.drawable.ic_mic)
                        .setContentTitle(ctx.getString(R.string.aviso_detenida_sola_titulo))
                        .setContentText(ctx.getString(R.string.aviso_detenida_sola_texto, Horario.etiqueta(ctx, b.curso, b.tipo)))
                        .setAutoCancel(true)
                        .setContentIntent(abrirApp(ctx, b, grabar = false))
                        .build()
                )
            }
        }
    }

    private fun abrirApp(ctx: Context, b: Bloque, grabar: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, if (grabar) 21 else 20,
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACCION_GRABAR)
                .putExtra(MainActivity.EXTRA_CURSO_ID, b.curso.id)
                .putExtra(MainActivity.EXTRA_TIPO, b.tipo)
                .putExtra(MainActivity.EXTRA_GRABAR, grabar)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun avisarInicio(ctx: Context, b: Bloque) {
        val grabar = Notification.Action.Builder(null, ctx.getString(R.string.grabar), abrirApp(ctx, b, grabar = true)).build()
        notificar(
            ctx, NOTIF_INICIO,
            Notification.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(Horario.etiqueta(ctx, b.curso, b.tipo))
                .setContentText(ctx.getString(R.string.aviso_empieza, Horario.hhmm(b.ini)))
                .setContentIntent(abrirApp(ctx, b, grabar = false))
                .addAction(grabar)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
        )
    }

    private fun avisarFin(ctx: Context, b: Bloque) {
        val detener = PendingIntent.getService(
            ctx, 22,
            Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ver = PendingIntent.getActivity(
            ctx, 23,
            Intent(ctx, GrabandoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notificar(
            ctx, NOTIF_FIN,
            Notification.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(ctx.getString(R.string.aviso_termino_titulo))
                .setContentText(ctx.getString(R.string.aviso_termino_texto, Horario.etiqueta(ctx, b.curso, b.tipo)))
                .setContentIntent(ver)
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.detener), detener).build())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
        )
    }

    private fun notificar(ctx: Context, id: Int, n: Notification) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, ctx.getString(R.string.canal_recordatorios), NotificationManager.IMPORTANCE_HIGH)
        )
        runCatching { nm.notify(id, n) }
    }

    /** Quita los avisos ya resueltos (p. ej. al empezar o terminar de grabar). */
    fun cancelar(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }
}

/** Recibe la alarma del próximo aviso y los eventos que obligan a reprogramarla. */
class AvisosReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Avisos.ACCION_AVISO) Avisos.disparar(ctx, intent)
        // Reinicio, cambio de hora o de zona, actualización de la app, permiso de alarmas exactas
        Avisos.programar(ctx)
    }
}
