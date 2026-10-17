package pe.javier.grabadora

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.text.DateFormatSymbols
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Un curso. El id no cambia aunque se renombre, así sus grabaciones siguen siendo suyas. */
data class Curso(val id: Long, val nombre: String, val color: Int)

/** Una clase del horario. [ini] y [fin] en minutos desde las 00:00; [tipo] es uno de [Horario.tipos]. */
data class Bloque(val id: Long, val curso: Curso, val dia: Int, val ini: Int, val fin: Int, val tipo: String)

/** Lo que se puede elegir para grabar: un curso con su tipo, o grabación libre (ambos null). */
data class Opcion(val curso: Curso?, val tipo: String?)

/**
 * Cursos y horario, guardados en [BaseDatos]. Se cargan en memoria al abrir la app (App.onCreate)
 * y se vuelven a leer después de cada cambio.
 */
object Horario {
    const val TEORIA = "teoria"
    const val PRACTICA = "practica"
    const val LABORATORIO = "laboratorio"

    val tipos = listOf(TEORIA, PRACTICA, LABORATORIO)

    /** Lunes a domingo, en el orden en que se muestran. */
    val dias = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
    )

    /** Minutos antes del inicio en que ya se detecta la clase. */
    private const val MARGEN = 15

    /** Color de las grabaciones libres. */
    val GRIS = 0xFF6B7280.toInt()

    val PALETA = intArrayOf(
        0xFF5B8DEF.toInt(), // azul
        0xFF34C38F.toInt(), // verde
        0xFFB37FEB.toInt(), // morado
        0xFFF07F4F.toInt(), // naranja
        0xFF2EC4D6.toInt(), // celeste
        0xFFE8618C.toInt(), // rosa
        0xFF8BC34A.toInt(), // lima
        0xFF7C83FD.toInt(), // índigo
    )

    private const val PREFS = "horario"
    private const val KEY_VIEJO = "bloques"        // horario guardado antes de la base de datos
    private const val KEY_MIGRADO = "migrado_bd"
    private const val ASSET_INICIAL = "horario_inicial.json"

    /** Todos los cursos, en orden alfabético. */
    @Volatile var cursos: List<Curso> = emptyList()
        private set

    @Volatile var bloques: List<Bloque> = emptyList()
        private set

    @Volatile var opciones: List<Opcion> = listOf(Opcion(null, null))
        private set

    // ---------------- cargar ----------------

    @Synchronized
    fun cargar(ctx: Context) {
        val db = BaseDatos.de(ctx).writableDatabase
        migrarSiHaceFalta(ctx, db)
        leer(db)
    }

    private fun leer(db: SQLiteDatabase) {
        val cs = mutableListOf<Curso>()
        db.rawQuery("SELECT id, nombre, color FROM cursos", null).use { c ->
            while (c.moveToNext()) cs.add(Curso(c.getLong(0), c.getString(1), c.getInt(2)))
        }
        cursos = cs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nombre })

        val porId = cs.associateBy { it.id }
        val bs = mutableListOf<Bloque>()
        db.rawQuery("SELECT id, curso_id, dia, ini, fin, tipo FROM clases", null).use { c ->
            while (c.moveToNext()) {
                val curso = porId[c.getLong(1)] ?: continue
                bs.add(Bloque(c.getLong(0), curso, c.getInt(2), c.getInt(3), c.getInt(4), c.getString(5)))
            }
        }
        bloques = ordenar(bs)
        opciones = calcularOpciones(bloques)
    }

    fun ordenar(lista: List<Bloque>): List<Bloque> =
        lista.sortedWith(compareBy({ dias.indexOf(it.dia) }, { it.ini }))

    fun calcularOpciones(lista: List<Bloque>): List<Opcion> =
        listOf(Opcion(null, null)) + lista
            .map { Opcion(it.curso, it.tipo) }
            .distinct()
            .sortedWith(compareBy<Opcion>({ it.curso?.nombre?.lowercase() }, { tipos.indexOf(it.tipo) }))

    /** Pasa a la base de datos el horario de la versión anterior (o el inicial de assets), una sola vez. */
    private fun migrarSiHaceFalta(ctx: Context, db: SQLiteDatabase) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRADO, false)) return
        val json = prefs.getString(KEY_VIEJO, null)
            ?: runCatching {
                ctx.assets.open(ASSET_INICIAL).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
        if (json != null) {
            db.beginTransaction()
            try {
                for (c in desdeJson(json)) {
                    insertarClase(db, idCurso(db, c.curso), c.dia, c.ini, c.fin, c.tipo)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        prefs.edit().putBoolean(KEY_MIGRADO, true).remove(KEY_VIEJO).apply()
    }

    private class ClaseJson(val dia: Int, val ini: Int, val fin: Int, val curso: String, val tipo: String)

    private fun desdeJson(json: String): List<ClaseJson> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val curso = o.optString("curso").trim()
            val dia = o.optInt("dia", -1)
            val ini = o.optInt("ini", -1)
            val fin = o.optInt("fin", -1)
            if (curso.isEmpty() || problemaNombre(curso) != null || dia !in dias || ini < 0 || fin <= ini) null
            else ClaseJson(dia, ini, fin, curso, tipoDeTexto(o.optString("tipo")))
        }
    }.getOrDefault(emptyList())

    // ---------------- cambios ----------------

    /** Crea o edita una clase. El curso se crea si no existía. */
    @Synchronized
    fun guardarClase(ctx: Context, existente: Bloque?, nombreCurso: String, dia: Int, ini: Int, fin: Int, tipo: String) {
        val db = BaseDatos.de(ctx).writableDatabase
        val cursoId = idCurso(db, nombreCurso)
        if (existente == null) {
            insertarClase(db, cursoId, dia, ini, fin, tipo)
        } else {
            db.update("clases", valoresClase(cursoId, dia, ini, fin, tipo), "id = ?", arrayOf(existente.id.toString()))
        }
        limpiarCursos(db)
        leer(db)
        Avisos.programar(ctx)
    }

    @Synchronized
    fun borrarClase(ctx: Context, b: Bloque) {
        val db = BaseDatos.de(ctx).writableDatabase
        db.delete("clases", "id = ?", arrayOf(b.id.toString()))
        limpiarCursos(db)
        leer(db)
        Avisos.programar(ctx)
    }

    /** Renombra o cambia el color. Devuelve false si ya hay otro curso con ese nombre. */
    @Synchronized
    fun editarCurso(ctx: Context, curso: Curso, nombre: String, color: Int): Boolean {
        val db = BaseDatos.de(ctx).writableDatabase
        val ocupado = db.rawQuery(
            "SELECT 1 FROM cursos WHERE nombre = ? AND id != ?", arrayOf(nombre, curso.id.toString())
        ).use { it.moveToFirst() }
        if (ocupado) return false
        val v = ContentValues().apply {
            put("nombre", nombre)
            put("color", color)
        }
        db.update("cursos", v, "id = ?", arrayOf(curso.id.toString()))
        leer(db)
        return true
    }

    /**
     * Para grabaciones que se conocen solo por el nombre del archivo ("Calculo I - Teoria - …"):
     * busca el curso ignorando tildes y mayúsculas, o lo crea.
     */
    @Synchronized
    fun cursoParaArchivo(ctx: Context, nombreEnArchivo: String): Curso {
        buscarPorNombre(nombreEnArchivo)?.let { return it }
        val db = BaseDatos.de(ctx).writableDatabase
        val id = idCurso(db, nombreEnArchivo)
        leer(db)
        return cursos.first { it.id == id }
    }

    /** Curso con ese nombre, sin importar tildes ni mayúsculas. */
    fun buscarPorNombre(nombre: String, lista: List<Curso> = cursos): Curso? {
        val clave = sinTildes(nombre).trim()
        return lista.firstOrNull { sinTildes(it.nombre).equals(clave, ignoreCase = true) }
    }

    fun curso(id: Long?): Curso? = id?.let { i -> cursos.firstOrNull { it.id == i } }

    private fun idCurso(db: SQLiteDatabase, nombre: String): Long {
        db.rawQuery("SELECT id FROM cursos WHERE nombre = ?", arrayOf(nombre)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val v = ContentValues().apply {
            put("nombre", nombre)
            put("color", siguienteColor(db))
        }
        return db.insertOrThrow("cursos", null, v)
    }

    /** El color de la paleta que menos cursos usan. */
    private fun siguienteColor(db: SQLiteDatabase): Int {
        val usos = mutableMapOf<Int, Int>()
        db.rawQuery("SELECT color, COUNT(*) FROM cursos GROUP BY color", null).use { c ->
            while (c.moveToNext()) usos[c.getInt(0)] = c.getInt(1)
        }
        return PALETA.minBy { usos[it] ?: 0 }
    }

    private fun valoresClase(cursoId: Long, dia: Int, ini: Int, fin: Int, tipo: String) = ContentValues().apply {
        put("curso_id", cursoId)
        put("dia", dia)
        put("ini", ini)
        put("fin", fin)
        put("tipo", tipo)
    }

    private fun insertarClase(db: SQLiteDatabase, cursoId: Long, dia: Int, ini: Int, fin: Int, tipo: String) {
        db.insertOrThrow("clases", null, valoresClase(cursoId, dia, ini, fin, tipo))
    }

    /** Borra los cursos que ya no tienen clases ni grabaciones. */
    private fun limpiarCursos(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM cursos WHERE id NOT IN (SELECT curso_id FROM clases) " +
                "AND id NOT IN (SELECT curso_id FROM grabaciones WHERE curso_id IS NOT NULL)"
        )
    }

    // ---------------- validación ----------------

    /** Problema con el nombre del curso (va dentro del nombre del archivo) como id de R.string, o null si sirve. */
    fun problemaNombre(curso: String): Int? = when {
        curso.isBlank() -> R.string.error_nombre_vacio
        curso.contains(" - ") -> R.string.error_nombre_guion
        curso.any { it in "/\\:*?\"<>|" } -> R.string.error_nombre_caracteres
        sinTildes(curso).trim().equals("Grabacion", ignoreCase = true) -> R.string.error_nombre_reservado
        else -> null
    }

    /** Otra clase del mismo día que se cruza con ese horario (ignorando [excepto]). */
    fun cruce(dia: Int, ini: Int, fin: Int, excepto: Bloque?, lista: List<Bloque> = bloques): Bloque? =
        lista.firstOrNull { it.id != excepto?.id && it.dia == dia && ini < it.fin && it.ini < fin }

    /** "Teoría", "Teoria" o "teoria" → [TEORIA]. Lo desconocido se toma como teoría. */
    fun tipoDeTexto(s: String): String {
        val t = sinTildes(s).trim().lowercase(Locale.ROOT)
        return tipos.firstOrNull { it == t } ?: TEORIA
    }

    // ---------------- presentación ----------------

    fun color(curso: Curso?): Int = curso?.color ?: GRIS

    /** "Computación Gráfica" -> "CG"; "Física" -> "FÍ"; sin curso -> "♪". */
    fun iniciales(nombre: String?): String {
        if (nombre == null) return "♪"
        val palabras = nombre.split(" ").filter { it.length > 2 }
        return if (palabras.size >= 2) "${palabras[0][0]}${palabras[1][0]}".uppercase()
        else nombre.take(2).uppercase()
    }

    fun etiquetaTipo(ctx: Context, tipo: String?): String = when (tipo) {
        TEORIA -> ctx.getString(R.string.tipo_teoria)
        PRACTICA -> ctx.getString(R.string.tipo_practica)
        LABORATORIO -> ctx.getString(R.string.tipo_laboratorio)
        else -> tipo ?: ""
    }

    /** "Cálculo I - Teoría", o "Grabación libre". */
    fun etiqueta(ctx: Context, curso: Curso?, tipo: String?): String =
        if (curso == null) ctx.getString(R.string.grabacion_libre)
        else ctx.getString(R.string.curso_y_tipo, curso.nombre, etiquetaTipo(ctx, tipo))

    fun etiqueta(ctx: Context, o: Opcion): String = etiqueta(ctx, o.curso, o.tipo)

    /** Nombre del día en el idioma del celular ("Lunes"). */
    fun nombreDia(dia: Int): String =
        DateFormatSymbols.getInstance().weekdays.getOrNull(dia).orEmpty()
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** 420 -> "7:00". */
    fun hhmm(min: Int): String = String.format(Locale.US, "%d:%02d", min / 60, min % 60)

    // ---------------- detección ----------------

    /** Clase en curso ahora mismo (o la que empieza en menos de 15 min). */
    fun detectar(cal: Calendar = Calendar.getInstance(), lista: List<Bloque> = bloques): Bloque? {
        val dia = cal.get(Calendar.DAY_OF_WEEK)
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val hoy = lista.filter { it.dia == dia }
        return hoy.firstOrNull { m >= it.ini && m < it.fin }
            ?: hoy.firstOrNull { m >= it.ini - MARGEN && m < it.ini }
    }

    fun indiceDe(b: Bloque?): Int {
        if (b == null) return 0
        return opciones.indexOfFirst { it.curso?.id == b.curso.id && it.tipo == b.tipo }.coerceAtLeast(0)
    }

    // ---------------- archivos ----------------

    /** En el nombre del archivo el tipo va siempre en español sin tildes, para poder leerlo de vuelta. */
    private fun tipoEnArchivo(tipo: String): String = when (tipo) {
        TEORIA -> "Teoria"
        PRACTICA -> "Practica"
        LABORATORIO -> "Laboratorio"
        else -> sinTildes(tipo)
    }

    fun nombreArchivo(curso: Curso?, tipo: String?, fecha: Date = Date()): String =
        if (curso == null || tipo == null) {
            "Grabacion - " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(fecha)
        } else {
            "${sinTildes(curso.nombre)} - ${tipoEnArchivo(tipo)} - " +
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(fecha)
        }

    fun sinTildes(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }
}
