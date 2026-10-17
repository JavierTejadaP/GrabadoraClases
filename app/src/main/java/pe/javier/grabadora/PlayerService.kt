package pe.javier.grabadora

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reproduce en segundo plano (pantalla apagada o fuera de la app).
 * Controles en la notificación y en la pantalla de bloqueo.
 * Recuerda dónde te quedaste en cada grabación.
 * Las pantallas siguen el estado con [flujo]; la posición se lee con [posicion].
 */
class PlayerService : Service() {

    data class Instantanea(
        val uri: Uri? = null,
        val extras: Bundle? = null,
        val listo: Boolean = false,
        val sonando: Boolean = false,
        val duracion: Long = 0L,
        val iVel: Int = 0,
    )

    companion object {
        const val ACT_ABRIR = "pe.javier.grabadora.P_ABRIR"
        const val ACT_TOGGLE = "pe.javier.grabadora.P_TOGGLE"
        const val ACT_PLAY = "pe.javier.grabadora.P_PLAY"
        const val ACT_PAUSE = "pe.javier.grabadora.P_PAUSE"
        const val ACT_SEEK = "pe.javier.grabadora.P_SEEK"
        const val ACT_SALTAR = "pe.javier.grabadora.P_SALTAR"
        const val ACT_VEL = "pe.javier.grabadora.P_VEL"
        const val ACT_CERRAR = "pe.javier.grabadora.P_CERRAR"
        const val EXTRA_MS = "ms"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_VEL = "vel"
        const val SALTO = 10_000L

        val VELOCIDADES = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)

        private const val CANAL = "reproduccion"
        private const val NOTIF_ID = 2

        private val _flujo = MutableStateFlow(Instantanea())
        val flujo: StateFlow<Instantanea> = _flujo

        /** Error para mostrar una vez; la pantalla que lo muestra lo vuelve a poner en null. */
        val error = MutableStateFlow<String?>(null)

        val uriActual: Uri? get() = _flujo.value.uri
        val listo: Boolean get() = _flujo.value.listo
        val duracion: Long get() = _flujo.value.duracion
        val iVel: Int get() = _flujo.value.iVel

        @Volatile private var mpRef: MediaPlayer? = null

        fun reproduciendo(): Boolean = runCatching { listo && mpRef?.isPlaying == true }.getOrDefault(false)

        fun posicion(): Long = runCatching {
            if (listo) mpRef?.currentPosition?.toLong() ?: 0L else 0L
        }.getOrDefault(0L)

        fun etiquetaVel(i: Int): String {
            val v = VELOCIDADES[i]
            return if (v % 1f == 0f) "${v.toInt()}x" else "${v}x"
        }
    }

    private var mp: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var am: AudioManager
    private var focusReq: AudioFocusRequest? = null
    private var reanudarAlRecuperar = false
    private var receptorRegistrado = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("posiciones", MODE_PRIVATE) }

    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Pausa si se desconectan los audífonos. */
    private val ruidoso = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pausar()
        }
    }

    private val guardarTick = object : Runnable {
        override fun run() {
            guardarPosicion()
            if (reproduciendo()) handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, getString(R.string.canal_reproduccion), NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "GrabadoraClases").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = reproducir()
                override fun onPause() = pausar()
                override fun onSeekTo(pos: Long) = buscar(pos)
                override fun onFastForward() = saltar(SALTO)
                override fun onRewind() = saltar(-SALTO)
                override fun onStop() = cerrar()
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        "rew" -> saltar(-SALTO)
                        "ff" -> saltar(SALTO)
                    }
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_ABRIR -> abrir(intent)
            ACT_TOGGLE -> if (reproduciendo()) pausar() else reproducir()
            ACT_PLAY -> reproducir()
            ACT_PAUSE -> pausar()
            ACT_SEEK -> buscar(intent.getLongExtra(EXTRA_MS, 0L))
            ACT_SALTAR -> saltar(intent.getLongExtra(EXTRA_DELTA, 0L))
            ACT_VEL -> cambiarVelocidad(intent.getIntExtra(EXTRA_VEL, 0))
            ACT_CERRAR -> {
                cerrar()
                return START_NOT_STICKY
            }
        }
        if (uriActual == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        guardarPosicion()
        liberarPlayer()
        desregistrarRuidoso()
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        handler.removeCallbacksAndMessages(null)
        session.release()
        _flujo.value = Instantanea(iVel = iVel)
        super.onDestroy()
    }

    // ---------------- abrir / cerrar ----------------

    private fun abrir(intent: Intent) {
        val u = intent.getStringExtra(ReproductorActivity.EXTRA_URI)?.let { Uri.parse(it) }
        if (u == null) {
            // startForegroundService exige llamar a startForeground aunque no haya nada que abrir
            startForeground(NOTIF_ID, construirNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            cerrar()
            return
        }
        val mismo = u == uriActual && mp != null
        if (!mismo) _flujo.value = _flujo.value.copy(extras = Bundle(intent.extras ?: Bundle()))

        startForeground(NOTIF_ID, construirNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (mismo) {
            actualizar()
            return
        }

        guardarPosicion()
        liberarPlayer()
        _flujo.value = _flujo.value.copy(uri = u, duracion = 0L)
        val bytes = intent.getLongExtra(ReproductorActivity.EXTRA_BYTES, 0L)

        try {
            val p = MediaPlayer()
            p.setAudioAttributes(atributos)
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(this, u)
            p.setOnPreparedListener {
                val dur = if (it.duration > 0) it.duration.toLong() else bytes * 8 * 1000 / 48000
                _flujo.value = _flujo.value.copy(listo = true, duracion = dur)
                val guardada = prefs.getLong(u.toString(), 0L)
                if (guardada in 5_000L..(dur - 5_000L)) it.seekTo(guardada, MediaPlayer.SEEK_CLOSEST_SYNC)
                actualizarMetadata()
                reproducir()
            }
            p.setOnSeekCompleteListener { actualizar() }
            p.setOnCompletionListener {
                prefs.edit().remove(u.toString()).apply()
                actualizar()
            }
            p.setOnErrorListener { _, what, extra ->
                error.value = getString(R.string.error_reproducir, what, extra)
                actualizar()
                true
            }
            p.prepareAsync()
            mp = p
            mpRef = p
        } catch (e: Exception) {
            error.value = getString(R.string.error_abrir_audio, e.message ?: "")
            cerrar()
            return
        }
        actualizarMetadata()
        actualizar()
    }

    private fun cerrar() {
        guardarPosicion()
        liberarPlayer()
        _flujo.value = Instantanea(iVel = iVel)
        desregistrarRuidoso()
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun liberarPlayer() {
        _flujo.value = _flujo.value.copy(listo = false, sonando = false)
        runCatching { mp?.release() }
        mp = null
        mpRef = null
    }

    // ---------------- controles ----------------

    private fun reproducir() {
        val p = mp ?: return
        if (!listo || !pedirFoco()) return
        session.isActive = true
        p.start()
        aplicarVelocidad()
        registrarRuidoso()
        handler.removeCallbacks(guardarTick)
        handler.postDelayed(guardarTick, 5000)
        actualizar()
    }

    private fun pausar() {
        val p = mp ?: return
        if (listo && p.isPlaying) p.pause()
        guardarPosicion()
        actualizar()
    }

    private fun buscar(ms: Long) {
        val p = mp ?: return
        if (!listo) return
        p.seekTo(ms.coerceIn(0L, duracion), MediaPlayer.SEEK_CLOSEST_SYNC)
        actualizar()
    }

    private fun saltar(delta: Long) = buscar(posicion() + delta)

    private fun cambiarVelocidad(i: Int) {
        _flujo.value = _flujo.value.copy(iVel = i.coerceIn(0, VELOCIDADES.size - 1))
        // Cambiarla en pausa haría que empiece a sonar; se aplica al reanudar
        if (reproduciendo()) aplicarVelocidad()
        actualizar()
    }

    private fun aplicarVelocidad() {
        val p = mp ?: return
        runCatching { p.playbackParams = p.playbackParams.setSpeed(VELOCIDADES[iVel]) }
    }

    // ---------------- foco de audio / audífonos ----------------

    private fun pedirFoco(): Boolean {
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(atributos)
            .setOnAudioFocusChangeListener { cambio ->
                when (cambio) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        reanudarAlRecuperar = false
                        pausar()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (reproduciendo()) {
                        reanudarAlRecuperar = true   // p. ej. una llamada
                        pausar()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (reanudarAlRecuperar) {
                        reanudarAlRecuperar = false
                        reproducir()
                    }
                }
            }
            .build()
            .also { focusReq = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun registrarRuidoso() {
        if (receptorRegistrado) return
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ruidoso, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(ruidoso, f)
        receptorRegistrado = true
    }

    private fun desregistrarRuidoso() {
        if (!receptorRegistrado) return
        runCatching { unregisterReceiver(ruidoso) }
        receptorRegistrado = false
    }

    // ---------------- recordar posición ----------------

    private fun guardarPosicion() {
        val u = uriActual ?: return
        if (!listo) return
        val pos = posicion()
        val e = prefs.edit()
        if (duracion > 0 && pos > duracion - 5_000L) e.remove(u.toString()) else e.putLong(u.toString(), pos)
        e.apply()
    }

    // ---------------- sesión / notificación ----------------

    private fun actualizarMetadata() {
        val ex = _flujo.value.extras
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(
                    MediaMetadata.METADATA_KEY_TITLE,
                    ex?.getString(ReproductorActivity.EXTRA_TITULO) ?: getString(R.string.grabacion)
                )
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ex?.getString(ReproductorActivity.EXTRA_SUBTITULO) ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duracion)
                .build()
        )
    }

    /** Publica el estado y refresca la sesión multimedia y la notificación. */
    private fun actualizar() {
        val play = reproduciendo()
        _flujo.value = _flujo.value.copy(sonando = play)
        val estado = when {
            play -> PlaybackState.STATE_PLAYING
            listo -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_BUFFERING
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
                )
                .setState(estado, posicion(), if (play) VELOCIDADES[iVel] else 0f, SystemClock.elapsedRealtime())
                .addCustomAction(
                    PlaybackState.CustomAction.Builder("rew", getString(R.string.atrasar_10), android.R.drawable.ic_media_rew).build()
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder("ff", getString(R.string.adelantar_10), android.R.drawable.ic_media_ff).build()
                )
                .build()
        )
        if (uriActual != null) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, construirNotif())
        }
    }

    private fun pi(accion: String, code: Int, delta: Long = 0L): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, PlayerService::class.java).setAction(accion).putExtra(EXTRA_DELTA, delta),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun construirNotif(): Notification {
        val ex = _flujo.value.extras ?: Bundle()
        val abrirPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, ReproductorActivity::class.java).putExtras(ex).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val play = reproduciendo()
        fun accion(icono: Int, texto: String, p: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(this, icono), texto, p).build()

        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(ex.getString(ReproductorActivity.EXTRA_TITULO) ?: getString(R.string.reproduciendo))
            .setContentText(ex.getString(ReproductorActivity.EXTRA_SUBTITULO) ?: "")
            .setContentIntent(abrirPi)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(accion(android.R.drawable.ic_media_rew, getString(R.string.atrasar_10), pi(ACT_SALTAR, 11, -SALTO)))
            .addAction(
                accion(
                    if (play) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    getString(if (play) R.string.pausar else R.string.reproducir),
                    pi(ACT_TOGGLE, 12)
                )
            )
            .addAction(accion(android.R.drawable.ic_media_ff, getString(R.string.adelantar_10), pi(ACT_SALTAR, 13, SALTO)))
            .addAction(accion(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cerrar), pi(ACT_CERRAR, 14)))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
