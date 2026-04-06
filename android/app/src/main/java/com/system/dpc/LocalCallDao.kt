package com.system.dpc

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocalCallDao {
    @Insert
    suspend fun insert(call: LocalCall)

    @Query("SELECT * FROM local_calls WHERE synced = 0")
    suspend fun getUnsyncedCalls(): List<LocalCall>

    @Query("UPDATE local_calls SET synced = 1 WHERE id IN (:ids)")
    suspend fun markCallsAsSynced(ids: List<Int>)
}
