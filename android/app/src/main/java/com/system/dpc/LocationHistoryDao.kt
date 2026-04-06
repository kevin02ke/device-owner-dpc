package com.system.dpc

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationHistoryDao {
    @Insert
    suspend fun insert(location: LocationHistory)

    @Query("SELECT * FROM location_history WHERE synced = 0")
    suspend fun getUnsyncedLocations(): List<LocationHistory>

    @Query("UPDATE location_history SET synced = 1 WHERE id IN (:ids)")
    suspend fun markLocationsAsSynced(ids: List<Int>)
}
