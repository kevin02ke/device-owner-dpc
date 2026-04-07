package com.system.dpc

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.serializer.AuthSerializer
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.Json

object Supabase {
    private var _client: SupabaseClient? = null

    val client: SupabaseClient
        get() = _client ?: throw IllegalStateException("Supabase not initialized")

    fun initialize(context: Context) {
        if (_client == null) {
            _client = createSupabaseClient(
                supabaseUrl = "https://oqfgxwkevinqombzdtas.supabase.co",
                supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im9xZmd4d2tldmlucW9tYnpkdGFzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MTI2MDM2MjcsImV4cCI6MjAyODE3OTYyN30.iPZ5hWAF22k2jB3BAma_2fL2ySBBbQx2-hxbBSh2n24"
            ) {
                install(Auth) {
                    val json = Json { ignoreUnknownKeys = true }
                    serializer = AuthSerializer(json) // Use the custom serializer
                }
                install(Postgrest)
                install(Realtime)
                install(Storage)
            }
        }
    }
}