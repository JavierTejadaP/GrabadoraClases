package pe.javier.grabadora

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base de datos local: cursos (con su color), el horario y el índice de grabaciones.
 * Los audios y las marcas siguen en Descargas/GrabadoraClases; aquí solo se guarda
 * a qué curso pertenece cada uno y lo que cuesta calcular (cuántas marcas tiene).
 */
class BaseDatos private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "grabadora.db", null, 3) {

    companion object {
        @Volatile private var instancia: BaseDatos? = null

        fun de(ctx: Context): BaseDatos =
            instancia ?: synchronized(this) {
                instancia ?: BaseDatos(ctx.applicationContext).also { instancia = it }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE cursos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                nombre TEXT NOT NULL UNIQUE COLLATE NOCASE,
                color INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE clases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                curso_id INTEGER NOT NULL REFERENCES cursos(id) ON DELETE CASCADE,
                dia INTEGER NOT NULL,
                ini INTEGER NOT NULL,
                fin INTEGER NOT NULL,
                tipo TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE grabaciones (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                archivo TEXT NOT NULL UNIQUE,
                uri TEXT NOT NULL,
                curso_id INTEGER REFERENCES cursos(id) ON DELETE SET NULL,
                tipo TEXT,
                fecha_ms INTEGER NOT NULL,
                bytes INTEGER NOT NULL DEFAULT 0,
                marcas_uri TEXT,
                marcas_firma INTEGER,
                num_marcas INTEGER NOT NULL DEFAULT 0,
                tema TEXT,
                enviada INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_grabaciones_curso ON grabaciones(curso_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 1 → 2: tema de la clase
        if (oldVersion < 2) db.execSQL("ALTER TABLE grabaciones ADD COLUMN tema TEXT")
        // 2 → 3: ya se mandó a la laptop (Drive, WhatsApp…)
        if (oldVersion < 3) db.execSQL("ALTER TABLE grabaciones ADD COLUMN enviada INTEGER NOT NULL DEFAULT 0")
    }
}
