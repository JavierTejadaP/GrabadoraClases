package pe.javier.grabadora

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Una clase del horario. [ini] y [fin] en minutos desde las 00:00. */
data class Bloque(val dia: Int, val ini: Int, val fin: Int, val curso: String, val tipo: String)

data class Opcion(val etiqueta: String, val curso: String?, val tipo: String?)

/**
 * Horario de clases. Lo arma el usuario en "Mi horario" y se guarda en el celular.
 * La primera vez se carga assets/horario_inicial.json si existe (no se sube al repositorio).
 */
object Horario {
    private const val T = "Teoría"
    private const val P = "Práctica"
    private const val L = "Laboratorio"

    val tipos = listOf(T, P, L)

    /** Lunes a domingo, en el orden en que se muestran. */
    val dias = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
    )
    private val nombresDia = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

    /** Minutos antes del inicio en que ya se detecta la clase. */
    private const val MARGEN = 15

    private const val PREFS = "horario"
    private const val KEY = "bloques"
    private const val ASSET_INICIAL = "horario_inicial.json"

    var bloques: List<Bloque> = emptyList()
        private set

    /** Cursos sin repetir, en orden alfabético. */
    var cursos: List<String> = emptyList()
        private set

    var opciones: List<Opcion> = listOf(Opcion("Grabación libre", null, null))
        private set

    // ---------------- guardar / cargar ----------------

    /** Se llama al abrir la app (App.onCreate). */
    fun cargar(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null)
        if (json != null) {
            fijar(desdeJson(json))
            return
        }
        val inicial = runCatching {
            ctx.assets.open(ASSET_INICIAL).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        guardar(ctx, inicial?.let { desdeJson(it) } ?: emptyList())
    }

    fun guardar(ctx: Context, nuevos: List<Bloque>) {
        fijar(nuevos)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, aJson(bloques))
            .apply()
    }

    private fun fijar(lista: List<Bloque>) {
        bloques = lista.sortedWith(compareBy({ dias.indexOf(it.dia) }, { it.ini }))
        cursos = bloques.map { it.curso }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        val o = mutableListOf(Opcion("Grabación libre", null, null))
        bloques.map { it.curso to it.tipo }
            .distinct()
            .sortedWith(compareBy<Pair<String, String>>({ it.first.lowercase() }, { ordenTipo(it.second) }))
            .forEach { (c, t) -> o.add(Opcion("$c - $t", c, t)) }
        opciones = o
    }

    private fun aJson(lista: List<Bloque>): String {
        val arr = JSONArray()
        for (b in lista) {
            arr.put(
                JSONObject()
                    .put("dia", b.dia).put("ini", b.ini).put("fin", b.fin)
                    .put("curso", b.curso).put("tipo", b.tipo)
            )
        }
        return arr.toString()
    }

    private fun desdeJson(json: String): List<Bloque> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val curso = o.optString("curso").trim()
            val dia = o.optInt("dia", -1)
            val ini = o.optInt("ini", -1)
            val fin = o.optInt("fin", -1)
            if (curso.isEmpty() || dia !in dias || ini < 0 || fin <= ini) null
            else Bloque(dia, ini, fin, curso, o.optString("tipo").ifBlank { T })
        }
    }.getOrDefault(emptyList())

    // ---------------- validación ----------------

    /** Devuelve el problema con el nombre del curso, o null si sirve (va en el nombre del archivo). */
    fun problemaNombre(curso: String): String? = when {
        curso.isBlank() -> "Escribe el nombre del curso"
        curso.contains(" - ") -> "El nombre no puede tener \" - \""
        curso.any { it in "/\\:*?\"<>|" } -> "El nombre no puede tener / \\ : * ? \" < > |"
        sinTildes(curso).trim().equals("Grabacion", ignoreCase = true) -> "Ese nombre está reservado"
        else -> null
    }

    /** Otra clase del mismo día que se cruza con [b] (ignorando [excepto]). */
    fun cruce(b: Bloque, excepto: Bloque?): Bloque? =
        bloques.firstOrNull { it != excepto && it.dia == b.dia && b.ini < it.fin && it.ini < b.fin }

    // ---------------- presentación ----------------

    private val PALETA = intArrayOf(
        0xFF5B8DEF.toInt(), // azul
        0xFF34C38F.toInt(), // verde
        0xFFB37FEB.toInt(), // morado
        0xFFF07F4F.toInt(), // naranja
        0xFF2EC4D6.toInt(), // celeste
        0xFFE8618C.toInt(), // rosa
    )

    /** Color fijo de cada curso (gris para grabaciones libres). */
    fun color(curso: String?): Int {
        if (curso == null) return 0xFF6B7280.toInt()
        val i = cursos.indexOf(curso)
        return PALETA[(if (i < 0) curso.hashCode().and(0x7fffffff) else i) % PALETA.size]
    }

    /** "Cálculo Integral" -> "CI"; "Física" -> "FÍ". */
    fun iniciales(curso: String?): String {
        if (curso == null) return "♪"
        val palabras = curso.split(" ").filter { it.length > 2 }
        return if (palabras.size >= 2) "${palabras[0][0]}${palabras[1][0]}".uppercase()
        else curso.take(2).uppercase()
    }

    fun nombreDia(dia: Int): String = nombresDia.getOrElse(dias.indexOf(dia)) { "?" }

    /** 420 -> "7:00". */
    fun hhmm(min: Int): String = String.format(Locale.US, "%d:%02d", min / 60, min % 60)

    /** "Calculo Integral" -> "Cálculo Integral" (para leer nombres de archivo). */
    fun cursoDe(sinTilde: String): String = cursos.firstOrNull { sinTildes(it) == sinTilde } ?: sinTilde

    fun tipoDe(sinTilde: String): String = tipos.firstOrNull { sinTildes(it) == sinTilde } ?: sinTilde

    private fun ordenTipo(t: String) = tipos.indexOf(t).let { if (it < 0) tipos.size else it }

    // ---------------- detección ----------------

    /** Clase en curso ahora mismo (o la que empieza en menos de 15 min). */
    fun detectar(cal: Calendar = Calendar.getInstance()): Bloque? {
        val dia = cal.get(Calendar.DAY_OF_WEEK)
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val hoy = bloques.filter { it.dia == dia }
        return hoy.firstOrNull { m >= it.ini && m < it.fin }
            ?: hoy.firstOrNull { m >= it.ini - MARGEN && m < it.ini }
    }

    fun indiceDe(b: Bloque?): Int {
        if (b == null) return 0
        return opciones.indexOfFirst { it.curso == b.curso && it.tipo == b.tipo }.coerceAtLeast(0)
    }

    fun nombreArchivo(o: Opcion, fecha: Date = Date()): String {
        return if (o.curso == null || o.tipo == null) {
            "Grabacion - " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(fecha)
        } else {
            "${sinTildes(o.curso)} - ${sinTildes(o.tipo)} - " +
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(fecha)
        }
    }

    fun sinTildes(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }
}
