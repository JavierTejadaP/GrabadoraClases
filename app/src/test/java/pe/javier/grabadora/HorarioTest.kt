package pe.javier.grabadora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class HorarioTest {

    private val calculo = Curso(1, "Cálculo I", Horario.PALETA[0])
    private val fisica = Curso(2, "Física", Horario.PALETA[1])

    private val lunesCalculo = Bloque(10, calculo, Calendar.MONDAY, 7 * 60, 9 * 60, Horario.TEORIA)
    private val lunesFisica = Bloque(11, fisica, Calendar.MONDAY, 9 * 60, 11 * 60, Horario.PRACTICA)
    private val horario = listOf(lunesCalculo, lunesFisica)

    /** Lunes 17 de agosto de 2026 a la hora indicada. */
    private fun lunes(h: Int, m: Int) = GregorianCalendar(2026, Calendar.AUGUST, 17, h, m)

    // ---------------- nombres de curso ----------------

    @Test
    fun nombreValido() {
        assertNull(Horario.problemaNombre("Cálculo I"))
        assertNull(Horario.problemaNombre("Organización de Archivos"))
    }

    @Test
    fun nombresInvalidos() {
        assertEquals(R.string.error_nombre_vacio, Horario.problemaNombre("   "))
        assertEquals(R.string.error_nombre_guion, Horario.problemaNombre("Cálculo - I"))
        assertEquals(R.string.error_nombre_caracteres, Horario.problemaNombre("Física/Química"))
        assertEquals(R.string.error_nombre_reservado, Horario.problemaNombre("Grabación"))
    }

    @Test
    fun buscaCursoSinImportarTildesNiMayusculas() {
        val cursos = listOf(calculo, fisica)
        assertEquals(calculo, Horario.buscarPorNombre("calculo i", cursos))
        assertEquals(fisica, Horario.buscarPorNombre("FISICA", cursos))
        assertNull(Horario.buscarPorNombre("Química", cursos))
    }

    // ---------------- cruces ----------------

    @Test
    fun detectaCruce() {
        val c = Horario.cruce(Calendar.MONDAY, 8 * 60, 10 * 60, null, horario)
        assertEquals(lunesCalculo, c)
    }

    @Test
    fun clasesSeguidasNoSeCruzan() {
        assertNull(Horario.cruce(Calendar.MONDAY, 11 * 60, 13 * 60, null, horario))
    }

    @Test
    fun otroDiaNoSeCruza() {
        assertNull(Horario.cruce(Calendar.TUESDAY, 7 * 60, 9 * 60, null, horario))
    }

    @Test
    fun alEditarNoChocaConsigoMisma() {
        assertNull(Horario.cruce(Calendar.MONDAY, 7 * 60, 8 * 60, lunesCalculo, horario))
    }

    // ---------------- detección ----------------

    @Test
    fun detectaLaClaseEnCurso() {
        assertEquals(lunesCalculo, Horario.detectar(lunes(8, 30), horario))
        assertEquals(lunesFisica, Horario.detectar(lunes(9, 0), horario))
    }

    @Test
    fun detectaLaQueEmpiezaEnMenosDe15Minutos() {
        assertEquals(lunesCalculo, Horario.detectar(lunes(6, 50), horario))
    }

    @Test
    fun sinClaseNoDetectaNada() {
        assertNull(Horario.detectar(lunes(6, 30), horario))
        assertNull(Horario.detectar(lunes(12, 0), horario))
    }

    // ---------------- archivos ----------------

    @Test
    fun nombreDeArchivoSinTildes() {
        val fecha = GregorianCalendar(2026, Calendar.OCTOBER, 17, 10, 5).time
        assertEquals("Calculo I - Teoria - 2026-10-17", Horario.nombreArchivo(calculo, Horario.TEORIA, fecha))
        assertEquals("Grabacion - 2026-10-17 10-05", Horario.nombreArchivo(null, null, fecha))
    }

    @Test
    fun tipoDesdeTexto() {
        assertEquals(Horario.TEORIA, Horario.tipoDeTexto("Teoría"))
        assertEquals(Horario.PRACTICA, Horario.tipoDeTexto("Practica"))
        assertEquals(Horario.LABORATORIO, Horario.tipoDeTexto("laboratorio"))
        assertEquals(Horario.TEORIA, Horario.tipoDeTexto("???"))
    }

    // ---------------- orden y presentación ----------------

    @Test
    fun opcionesEmpiezanConGrabacionLibreYVanPorNombre() {
        val opciones = Horario.calcularOpciones(listOf(lunesFisica, lunesCalculo))
        assertEquals(Opcion(null, null), opciones[0])
        assertEquals(calculo, opciones[1].curso)
        assertEquals(fisica, opciones[2].curso)
    }

    @Test
    fun ordenaPorDiaYHora() {
        val martes = Bloque(12, fisica, Calendar.TUESDAY, 7 * 60, 9 * 60, Horario.TEORIA)
        val domingo = Bloque(13, fisica, Calendar.SUNDAY, 7 * 60, 9 * 60, Horario.TEORIA)
        assertEquals(
            listOf(lunesCalculo, lunesFisica, martes, domingo),
            Horario.ordenar(listOf(domingo, martes, lunesFisica, lunesCalculo))
        )
    }

    @Test
    fun iniciales() {
        assertEquals("CG", Horario.iniciales("Computación Gráfica"))
        assertEquals("FÍ", Horario.iniciales("Física"))
        assertEquals("♪", Horario.iniciales(null))
    }

    @Test
    fun formatos() {
        assertEquals("7:05", Horario.hhmm(425))
        assertEquals("01:02:03", Horario.fmt(3_723_000))
    }

    // ---------------- marcas ----------------

    @Test
    fun marcasIdaYVuelta() {
        val marcas = listOf(4_000L, 65_000L, 3_725_000L)
        val texto = Biblioteca.textoMarcas("Cálculo I - Teoría", "Calculo I - Teoria - 2026-10-17.aac", marcas)
        assertEquals(marcas, Biblioteca.parsearMarcas(texto))
    }

    @Test
    fun marcasDeTextoVacio() {
        assertNotNull(Biblioteca.parsearMarcas(""))
        assertEquals(0, Biblioteca.parsearMarcas("").size)
    }
}
