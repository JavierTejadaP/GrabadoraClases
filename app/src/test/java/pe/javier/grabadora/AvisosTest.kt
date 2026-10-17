package pe.javier.grabadora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class AvisosTest {

    private val calculo = Curso(1, "Cálculo I", Horario.PALETA[0])
    private val lunes7a9 = Bloque(10, calculo, Calendar.MONDAY, 7 * 60, 9 * 60, Horario.TEORIA)
    private val viernes14a16 = Bloque(11, calculo, Calendar.FRIDAY, 14 * 60, 16 * 60, Horario.PRACTICA)

    /** Agosto de 2026: el 17 es lunes. */
    private fun dia(d: Int, h: Int, m: Int) = GregorianCalendar(2026, Calendar.AUGUST, d, h, m)

    private fun ms(d: Int, h: Int, m: Int) = dia(d, h, m).timeInMillis

    @Test
    fun siguienteEsHoyMasTarde() {
        assertEquals(ms(17, 6, 55), Avisos.siguiente(Calendar.MONDAY, 6 * 60 + 55, dia(17, 6, 0)))
    }

    @Test
    fun siguientePasaALaOtraSemanaSiYaPaso() {
        assertEquals(ms(24, 6, 55), Avisos.siguiente(Calendar.MONDAY, 6 * 60 + 55, dia(17, 7, 30)))
    }

    @Test
    fun siguienteOtroDiaDeLaSemana() {
        // Desde el lunes, el viernes 21; desde el sábado 22, el viernes 28
        assertEquals(ms(21, 13, 55), Avisos.siguiente(Calendar.FRIDAY, 13 * 60 + 55, dia(17, 9, 0)))
        assertEquals(ms(28, 13, 55), Avisos.siguiente(Calendar.FRIDAY, 13 * 60 + 55, dia(22, 9, 0)))
    }

    @Test
    fun proximoEsElAvisoDeInicio() {
        val ev = Avisos.proximo(listOf(lunes7a9, viernes14a16), Avisos.Ajustes(), dia(17, 6, 0))!!
        assertEquals(Avisos.Tipo.INICIO, ev.tipo)
        assertEquals(lunes7a9, ev.bloque)
        assertEquals(ms(17, 7 - 1, 60 - Avisos.MIN_ANTES), ev.cuandoMs)
    }

    @Test
    fun duranteLaClaseElProximoEsElFin() {
        val ev = Avisos.proximo(listOf(lunes7a9, viernes14a16), Avisos.Ajustes(), dia(17, 8, 0))!!
        assertEquals(Avisos.Tipo.FIN, ev.tipo)
        assertEquals(ms(17, 9, 0), ev.cuandoMs)
    }

    @Test
    fun detenerSoloSiEstaActivado() {
        val sinDetener = Avisos.proximo(listOf(lunes7a9), Avisos.Ajustes(inicio = false, fin = false), dia(17, 9, 5))
        assertNull(sinDetener)
        val conDetener = Avisos.proximo(listOf(lunes7a9), Avisos.Ajustes(inicio = false, fin = false, detenerSolo = true), dia(17, 9, 5))!!
        assertEquals(Avisos.Tipo.DETENER, conDetener.tipo)
        assertEquals(ms(17, 9, Avisos.MIN_DETENER), conDetener.cuandoMs)
    }

    @Test
    fun sinClasesNoHayAvisos() {
        assertNull(Avisos.proximo(emptyList(), Avisos.Ajustes(), dia(17, 6, 0)))
    }

    // ---------------- búsqueda ----------------

    @Test
    fun buscaSinTildesNiMayusculas() {
        val textos = listOf("Derivadas parciales", "Cálculo I", "Teoría", "lunes, 17 de agosto de 2026")
        assertTrue(Biblioteca.coincide(textos, "derivadas"))
        assertTrue(Biblioteca.coincide(textos, "CALCULO derivadas"))
        assertTrue(Biblioteca.coincide(textos, "teoria agosto"))
        assertFalse(Biblioteca.coincide(textos, "integrales"))
        assertFalse(Biblioteca.coincide(textos, "derivadas física"))
    }

    @Test
    fun busquedaVaciaCoincideConTodo() {
        assertTrue(Biblioteca.coincide(listOf(null, "x"), "   "))
    }
}
