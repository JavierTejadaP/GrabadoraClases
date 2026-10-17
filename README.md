# Grabadora de clases

App Android para grabar clases de la universidad. Sabe qué clase toca según tu horario,
graba en segundo plano aunque apagues la pantalla y te deja marcar los momentos importantes
para volver a ellos después.

## Qué hace

- **Mi horario**: agregas tus cursos con su día, hora y tipo (teoría, práctica o laboratorio).
  Al abrir la app ya aparece elegida la clase que toca.
- **Grabación en primer plano**: sigue grabando con la pantalla apagada. Formato AAC-ADTS,
  así que si algo falla a mitad de clase lo grabado hasta ese punto queda válido.
- **Avisos**: si el micrófono deja de captar sonido (otra app lo está usando) o se acaba el espacio.
- **Marcas**: un toque durante la grabación o al escuchar marca el minuto; luego saltas a él.
- **Reproductor**: sigue sonando fuera de la app, controles en la notificación y la pantalla
  de bloqueo, velocidades de 0.75x a 2x y recuerda dónde te quedaste.
- **Mis grabaciones**: ordenadas por curso, para compartir o borrar varias a la vez.
  Mantén presionado un curso para renombrarlo o cambiarle el color; sus grabaciones lo siguen.

## Cómo está hecha

- Sin librerías de interfaz: vistas de Android y Kotlin, más `kotlinx-coroutines`.
- `BaseDatos` (SQLite) guarda los cursos, el horario y un índice de las grabaciones con su
  curso y cuántas marcas tiene. Los audios siguen en Descargas; el índice se pone al día en
  segundo plano cada vez que se abre una lista.
- `RecorderService` y `PlayerService` publican su estado en un `StateFlow`; las pantallas lo
  escuchan mientras están visibles.
- Todos los textos están en `res/values/strings.xml`, listos para traducir.
- Tests de la lógica del horario y las marcas en `app/src/test`.

Las grabaciones se guardan en `Descargas/GrabadoraClases`, así puedes copiarlas a la PC o
compartirlas aunque desinstales la app. Si reinstalas, desde *Mis grabaciones* puedes dar
acceso a esa carpeta y vuelven a aparecer.

## Requisitos

- Android 10 (API 29) o superior.
- Para compilar: Android Studio con JDK 17.

## Compilar

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`. Cada push a GitHub también lo compila y
corre los tests (pestaña *Actions*), y deja el APK para descargar.

### Horario inicial (opcional)

Si existe `app/src/main/assets/horario_inicial.json`, la app lo carga la primera vez que se abre.
Ese archivo está en `.gitignore` para que tu horario personal no se publique. Formato:

```json
[
  {"dia": 2, "ini": 420, "fin": 540, "curso": "Cálculo I", "tipo": "Teoría"}
]
```

`dia` usa `java.util.Calendar` (2 = lunes … 7 = sábado, 1 = domingo); `ini` y `fin` son
minutos desde las 00:00 (420 = 7:00).

## Licencia

[MIT](LICENSE)
