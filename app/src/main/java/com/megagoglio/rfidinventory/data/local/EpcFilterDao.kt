package com.megagoglio.rfidinventory.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpcFilterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(filter: EpcFilterEntity)

    @Query("DELETE FROM epc_filters WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM epc_filters ORDER BY name ASC")
    fun all(): Flow<List<EpcFilterEntity>>
}
