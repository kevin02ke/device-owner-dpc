package com.system.dpc

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_sms")
data class LocalSms(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val number: String,
    val body: String,
    val timestamp: Long,
    var synced: Boolean = false
)
