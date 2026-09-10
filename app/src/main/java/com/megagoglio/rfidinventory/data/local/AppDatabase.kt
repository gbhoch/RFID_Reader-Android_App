package com.megagoglio.rfidinventory.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun toStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(status: SessionStatus): String = status.name
}

@Database(
    entities = [SessionEntity::class, TagReadEntity::class, SectorEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

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
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
