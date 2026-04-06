package com.system.dpc

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocalSmsDao {
    @Insert
    suspend fun insert(sms: LocalSms)

    @Query("SELECT * FROM local_sms WHERE synced = 0")
    suspend fun getUnsyncedSms(): List<LocalSms>

    @Query("UPDATE local_sms SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSmsAsSynced(ids: List<Int>)
}
