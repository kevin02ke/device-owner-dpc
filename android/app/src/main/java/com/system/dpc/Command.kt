package com.system.dpc

import kotlinx.serialization.Serializable

@Serializable
data class Command(
    val id: String,
    val action: String,
    val payload: String? = null,
    var status: String,
    val response_payload: String? = null
)
