package com.system.dpc

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val model: String,
    val android_version: String,
    val battery_level: Int
)
