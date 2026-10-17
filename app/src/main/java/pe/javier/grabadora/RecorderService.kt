package pe.javier.grabadora

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Graba en primer plano (notificación fija) para que MIUI/HyperOS no la cierre.
 * Formato AAC-ADTS: si algo falla a mitad de clase, el archivo queda válido hasta ese punto.
 * Las pantallas siguen el estado con [flujo] en vez de preguntar a cada rato.
 */
class RecorderService : Service() {

    enum class Estado { DETENIDO, GRABANDO, PAUSADO }

    /** Lo que las pantallas necesitan mostrar. Cambia solo cuando pasa algo. */
    data class Instantanea(
        val estado: Estado = Estado.DETENIDO,
        val titulo: String = "",
        val advertencia: String? = null,
        val numMarcas: Int = 0,
        val ultimoArchivo: String? = null,
    )

    companion object {
        const val ACT_START = "pe.javier.grabadora.START"
        const val ACT_PAUSE = "pe.javier.grabadora.PAUSE"
        const val ACT_RESUME = "pe.javier.grabadora.RESUME"
        const val ACT_MARK = "pe.javier.grabadora.MARK"
        const val ACT_STOP = "pe.javier.grabadora.STOP"
        const val EXTRA_NOMBRE = "nombre"
        const val EXTRA_TITULO = "titulo"
        const val EXTRA_CURSO_ID = "curso_id"
        const val EXTRA_TIPO = "tipo"
        const val CARPETA = "GrabadoraClases"

        private const val CANAL = "grabacion"
        private const val CANAL_ALERTA = "alertas"
        private const val NOTIF_ID = 1
        private const val NOTIF_ALERTA = 3

        /** Segundos seguidos sin sonido antes de avisar (micrófono ocupado/silenciado). */
        private const val SEG_SILENCIO = 45
        private const val MB = 1024L * 1024L
        private const val ESPACIO_AVISO = 300 * MB
        private const val ESPACIO_MINIMO = 30 * MB

        private val _flujo = MutableStateFlow(Instantanea())
        val flujo: StateFlow<Instantanea> = _flujo

        /** Error para mostrar una vez; la pantalla que lo muestra lo vuelve a poner en null. */
        val error = MutableStateFlow<String?>(null)

        val estado: Estado get() = _flujo.value.estado

        /** Archivo que se está grabando ahora (para no mostrarlo/borrarlo en la lista). */
        @Volatile var archivoActual: String? = null
            private set

        // Nivel de sonido (0..1) por tramos de 100 ms, alineado con el tiempo de grabación
        private const val N_TRAMOS = 1200
        private val tramos = FloatArray(N_TRAMOS)
        private val tramoId = LongArray(N_TRAMOS) { -1L }

        private fun agregarNivel(tMs: Long, v: Float) = synchronized(tramos) {
            val b = tMs / 100
            val i = (b % N_TRAMOS).toInt()
            if (tramoId[i] != b) {
                tramoId[i] = b
                tramos[i] = v
            } else if (v > tramos[i]) {
                tramos[i] = v
            }
        }

        private fun limpiarNiveles() = synchronized(tramos) {
            tramos.fill(0f)
            tramoId.fill(-1L)
        }

        /** Nivel del tramo b (0 si no hay dato). */
        fun nivel(b: Long): Float {
            if (b < 0) return 0f
            return synchronized(tramos) {
                val i = (b % N_TRAMOS).toInt()
                if (tramoId[i] == b) tramos[i] else 0f
            }
        }

        fun marcasLista(): List<Long> = synchronized(marcas) { marcas.toList() }

        @Volatile private var acumulado = 0L
        @Volatile private var desde = 0L
        private val marcas = mutableListOf<Long>()

        fun tiempoMs(): Long =
            if (estado == Estado.GRABANDO) acumulado + (SystemClock.elapsedRealtime() - desde)
            else acumulado

        fun numMarcas(): Int = _flujo.value.numMarcas

        private fun publicar(cambio: (Instantanea) -> Instantanea) = _flujo.update(cambio)
    }

    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var audioUri: Uri? = null
    private var marcasUri: Uri? = null
    private var nombreReal = ""
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var segSinSonido = 0
    private var avisoEspacio = false

    private var maxSegundo = 0

    /** Cada 50 ms mide el sonido (para la onda); cada 1 s revisa silencio; cada 15 s el espacio. */
    private val vigilante = object : Runnable {
        private var vuelta = 0
        override fun run() {
            if (estado == Estado.DETENIDO) return
            if (estado == Estado.GRABANDO) {
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                agregarNivel(tiempoMs(), normalizar(amp))
                if (amp > maxSegundo) maxSegundo = amp
                if (vuelta % 20 == 19) {
                    revisarSonido(maxSegundo)
                    maxSegundo = 0
                }
            }
            if (vuelta % 300 == 0) revisarEspacio()
            vuelta++
            if (estado != Estado.DETENIDO) handler.postDelayed(this, 50)
        }
    }

    /** Amplitud (0..32767) a escala logarítmica 0..1. */
    private fun normalizar(amp: Int): Float {
        if (amp <= 0) return 0f
        val db = 20.0 * kotlin.math.log10(amp / 32767.0)
        return ((db + 50.0) / 50.0).toFloat().coerceIn(0.04f, 1f)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_START -> iniciar(
                intent.getStringExtra(EXTRA_NOMBRE) ?: "Grabacion",
                intent.getStringExtra(EXTRA_TITULO) ?: "",
                Horario.curso(intent.getLongExtra(EXTRA_CURSO_ID, -1L)),
                intent.getStringExtra(EXTRA_TIPO),
            )
            ACT_PAUSE -> pausar()
            ACT_RESUME -> reanudar()
            ACT_MARK -> marcar()
            ACT_STOP -> detener()
        }
        if (estado == Estado.DETENIDO) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (estado != Estado.DETENIDO) {
            // El sistema cerró el servicio a mitad de clase
            avisar(getString(R.string.aviso_detenida_titulo), getString(R.string.aviso_detenida_sistema))
            detener(pararServicio = false)
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------- vigilancia ----------------

    private fun revisarSonido(amp: Int) {
        if (amp > 0) {
            if (_flujo.value.advertencia != null) {
                publicar { it.copy(advertencia = null) }
                cancelarAviso()
                actualizarNotif()
            }
            segSinSonido = 0
            return
        }
        segSinSonido++
        if (segSinSonido == SEG_SILENCIO) {
            publicar { it.copy(advertencia = getString(R.string.advertencia_sin_sonido)) }
            avisar(getString(R.string.aviso_sin_sonido_titulo), getString(R.string.aviso_sin_sonido_texto))
            actualizarNotif()
        }
    }

    private fun espacioLibre(): Long =
        runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun revisarEspacio() {
        val libre = espacioLibre()
        if (libre < ESPACIO_MINIMO) {
            error.value = getString(R.string.error_sin_espacio_detenida)
            avisar(getString(R.string.aviso_detenida_titulo), getString(R.string.aviso_detenida_espacio))
            detener()
        } else if (libre < ESPACIO_AVISO && !avisoEspacio) {
            avisoEspacio = true
            avisar(
                getString(R.string.aviso_poco_espacio_titulo),
                getString(R.string.aviso_poco_espacio_texto, libre / MB, libre / MB / 21)
            )
        }
    }

    /** Notificación que suena/vibra, aparte de la fija. */
    private fun avisar(tituloAviso: String, texto: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ALERTA, getString(R.string.canal_alertas), NotificationManager.IMPORTANCE_HIGH)
        )
        val abrir = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            NOTIF_ALERTA,
            Notification.Builder(this, CANAL_ALERTA)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(tituloAviso)
                .setContentText(texto)
                .setStyle(Notification.BigTextStyle().bigText(texto))
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun cancelarAviso() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERTA)
    }

    // ---------------- acciones ----------------

    private fun iniciar(nombre: String, tituloNuevo: String, curso: Curso?, tipo: String?) {
        crearCanal()
        if (estado != Estado.DETENIDO) {
            // Doble toque en "Grabar": ya está grabando; solo se confirma el primer plano
            startForeground(NOTIF_ID, construirNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            return
        }
        segSinSonido = 0
        avisoEspacio = false
        maxSegundo = 0
        limpiarNiveles()
        acumulado = 0L
        desde = SystemClock.elapsedRealtime()
        synchronized(marcas) { marcas.clear() }
        marcasUri = null
        publicar {
            it.copy(estado = Estado.GRABANDO, titulo = tituloNuevo, advertencia = null, numMarcas = 0)
        }

        // Debe llamarse enseguida tras startForegroundService
        startForeground(NOTIF_ID, construirNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        try {
            if (espacioLibre() < ESPACIO_MINIMO) {
                throw IllegalStateException(getString(R.string.error_sin_espacio))
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$nombre.aac")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/aac")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + CARPETA)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(getString(R.string.error_crear_archivo))
            audioUri = uri
            val fd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException(getString(R.string.error_abrir_archivo))
            pfd = fd
            nombreReal = consultarNombre(uri) ?: "$nombre.aac"
            archivoActual = nombreReal
            Biblioteca.registrar(this, nombreReal, uri, curso, if (curso == null) null else tipo)

            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(fd.fileDescriptor)
            r.setOnErrorListener { _, what, extra ->
                error.value = getString(R.string.error_grabador, what, extra)
                avisar(getString(R.string.aviso_detenida_titulo), getString(R.string.aviso_detenida_microfono))
                detener()
            }
            r.prepare()
            r.start()
            recorder = r
            desde = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            error.value = getString(R.string.error_no_inicio, e.message ?: "")
            liberar()
            audioUri?.let { u -> runCatching { contentResolver.delete(u, null, null) } }
            audioUri = null
            archivoActual = null
            publicar { it.copy(estado = Estado.DETENIDO) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrabadoraClases::rec")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        handler.removeCallbacks(vigilante)
        handler.postDelayed(vigilante, 1000)
        actualizarNotif()
    }

    private fun pausar() {
        if (estado != Estado.GRABANDO) return
        try {
            recorder?.pause()
        } catch (e: Exception) {
            error.value = getString(R.string.error_pausar, e.message ?: "")
            return
        }
        acumulado += SystemClock.elapsedRealtime() - desde
        publicar { it.copy(estado = Estado.PAUSADO) }
        actualizarNotif()
    }

    private fun reanudar() {
        if (estado != Estado.PAUSADO) return
        try {
            recorder?.resume()
        } catch (e: Exception) {
            error.value = getString(R.string.error_reanudar, e.message ?: "")
            return
        }
        desde = SystemClock.elapsedRealtime()
        segSinSonido = 0
        publicar { it.copy(estado = Estado.GRABANDO) }
        actualizarNotif()
    }

    private fun marcar() {
        if (estado == Estado.DETENIDO) return
        val t = tiempoMs()
        val n = synchronized(marcas) {
            marcas.add(t)
            marcas.size
        }
        publicar { it.copy(numMarcas = n) }
        escribirMarcas()
        Toast.makeText(this, getString(R.string.marca_n, n, Horario.fmt(t)), Toast.LENGTH_SHORT).show()
    }

    private fun detener(pararServicio: Boolean = true) {
        if (estado == Estado.DETENIDO) return
        if (estado == Estado.GRABANDO) acumulado += SystemClock.elapsedRealtime() - desde
        handler.removeCallbacks(vigilante)
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        liberar()
        archivoActual = null
        audioUri = null
        publicar { it.copy(estado = Estado.DETENIDO, advertencia = null, ultimoArchivo = nombreReal) }
        if (pararServicio) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun liberar() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { pfd?.close() }
        pfd = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------------- archivos ----------------

    private fun consultarNombre(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun escribirMarcas() {
        try {
            if (marcasUri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nombreReal.removeSuffix(".aac") + Biblioteca.SUFIJO_MARCAS)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + CARPETA)
                }
                marcasUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }
            val lista = marcasLista()
            val texto = Biblioteca.textoMarcas(_flujo.value.titulo, nombreReal, lista)
            marcasUri?.let { u ->
                contentResolver.openOutputStream(u, "wt")?.use { it.write(texto.toByteArray(Charsets.UTF_8)) }
            }
            Biblioteca.actualizarMarcas(this, nombreReal, marcasUri, lista.size)
        } catch (e: Exception) {
            error.value = getString(R.string.error_guardar_marcas, e.message ?: "")
        }
    }

    // ---------------- notificación ----------------

    private fun crearCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, getString(R.string.canal_grabacion), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun pi(accion: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, RecorderService::class.java).setAction(accion),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun construirNotif(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, GrabandoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val inst = _flujo.value
        val grabando = inst.estado == Estado.GRABANDO
        val icono = Icon.createWithResource(this, R.drawable.ic_mic)

        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (grabando) R.string.notif_grabando else R.string.notif_pausado))
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (grabando) {
            b.setContentText(inst.advertencia?.let { getString(R.string.aviso_con_titulo, it, inst.titulo) } ?: inst.titulo)
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - tiempoMs())
        } else {
            b.setContentText(getString(R.string.titulo_con_tiempo, inst.titulo, Horario.fmt(tiempoMs())))
                .setUsesChronometer(false)
                .setShowWhen(false)
        }

        b.addAction(
            Notification.Action.Builder(
                icono,
                getString(if (grabando) R.string.pausar else R.string.reanudar),
                pi(if (grabando) ACT_PAUSE else ACT_RESUME, 1)
            ).build()
        )
        b.addAction(Notification.Action.Builder(icono, getString(R.string.marcar), pi(ACT_MARK, 2)).build())
        b.addAction(Notification.Action.Builder(icono, getString(R.string.detener), pi(ACT_STOP, 3)).build())

        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun actualizarNotif() {
        if (estado == Estado.DETENIDO) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, construirNotif())
    }
}
