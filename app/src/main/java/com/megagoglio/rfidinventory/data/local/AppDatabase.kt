package com.megagoglio.rfidinventory.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun toStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(status: SessionStatus): String = status.name
}

/**
 * v2 -> v3: filtros de EPC salvos.
 *
 * Diferente do bump 1->2 (destrutivo de propósito, feito antes de a v1 ser
 * distribuída), aqui já pode haver fila de sincronização real em campo — uma
 * Migration explícita evita apagar `sessions`/`tag_reads` de quem já tiver o app.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `epc_filters` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `startChar` INTEGER NOT NULL,
                `lengthChar` INTEGER NOT NULL,
                `valueHex` TEXT NOT NULL,
                `bank` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

@Database(
    entities = [SessionEntity::class, TagReadEntity::class, SectorEntity::class, EpcFilterEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun epcFilterDao(): EpcFilterDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "rfid-inventory.db",
            )
                // v1 -> v2 (contexto de inventário/setor nas sessões + cache de setores).
                // Destrutivo de propósito: a v1 nunca foi distribuída, e uma sessão da v1
                // não tem inventoryId/sectorId — não haveria como enviá-la ao servidor
                // de qualquer forma. Ao publicar a primeira versão, TROCAR por uma
                // Migration real: a partir daí o banco guarda coleta de campo.
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
