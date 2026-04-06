package com.system.dpc

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_calls")
data class LocalCall(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val number: String,
    val duration: Long,
    val timestamp: Long,
    var synced: Boolean = false
)
