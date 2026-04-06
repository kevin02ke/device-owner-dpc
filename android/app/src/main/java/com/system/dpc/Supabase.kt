package com.system.dpc

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.GoTrue
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.android.Android

object Supabase { // Singleton object

    private var _client: SupabaseClient? = null

    val client: SupabaseClient
        get() = _client ?: throw IllegalStateException("Supabase client not initialized")

    fun initialize() {
        if (_client == null) {
            _client = createSupabaseClient(
                supabaseUrl = "YOUR_SUPABASE_URL", // Replace with your URL
                supabaseKey = "YOUR_SUPABASE_ANON_KEY" // Replace with your anon key
            ) {
                install(GoTrue)
                install(Postgrest)
                install(Storage)
                install(Realtime)
                httpEngine = Android.create()
            }
        }
    }
}