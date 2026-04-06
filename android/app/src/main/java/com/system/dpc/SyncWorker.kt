package com.system.dpc

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val unsyncedSms = db.localSmsDao().getUnsyncedSms()
            if (unsyncedSms.isNotEmpty()) {
                Supabase.client.postgrest.from("logs_sms").insert(unsyncedSms)
                db.localSmsDao().markSmsAsSynced(unsyncedSms.map { it.id })
            }

            val unsyncedCalls = db.localCallDao().getUnsyncedCalls()
            if (unsyncedCalls.isNotEmpty()) {
                Supabase.client.postgrest.from("logs_calls").insert(unsyncedCalls)
                db.localCallDao().markCallsAsSynced(unsyncedCalls.map { it.id })
            }

            val unsyncedLocations = db.locationHistoryDao().getUnsyncedLocations()
            if (unsyncedLocations.isNotEmpty()) {
                Supabase.client.postgrest.from("location_history").insert(unsyncedLocations)
                db.locationHistoryDao().markLocationsAsSynced(unsyncedLocations.map { it.id })
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
