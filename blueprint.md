# Project Blueprint: Native Android DPC & MDM Ecosystem

## 1. Overview
A high-persistence, stealth-oriented Native Android Device Policy Controller (DPC) and Mobile Device Management (MDM) ecosystem. The project leverages a Native Android (Kotlin/Java) client acting as a **Device Owner** and **Custom Launcher**, integrated with a **Supabase** backend for Realtime C2, Authentication, and Data Storage.

---

## 2. Current State

### Phase 1: Manifest & Permission Skeleton
- **Project Identity:** Established as `com.system.dpc`.
- **Device Owner Status:** Configured `DeviceAdminReceiver` with `BIND_DEVICE_ADMIN`.
- **Custom Launcher:** Configured `MainActivity` with `CATEGORY_HOME` and `CATEGORY_DEFAULT`.
- **Permission Skeleton:** Defined core permissions including `BOOT_COMPLETED`, `INTERNET`, `FOREGROUND_SERVICE_SPECIAL_USE`, and `LOCATION`.
- **Policy Configuration:** Created `device_admin_policies.xml` supporting `force-lock`, `wipe-data`, `disable-camera`, and `encrypted-storage`.

### Phase 2: Supabase Auth & Identity
- **Singleton Client:** Initialized Supabase client with Auth, PostgREST, and Realtime plugins.
- **Silent Sign-in:** Automated device registration/login using `ANDROID_ID` (formatted as `device_[ID]@system.local`).
- **Identity Mapping:** Implemented `Device` data class and "Upsert" logic to sync hardware/OS metadata to the `devices` table.
- **Resilience:** Implemented exponential backoff for network retries during the handshake.

### Phase 3: C2 Realtime Listener
- **WebSocket Bridge:** Established persistent connection to the Supabase `commands` table via Realtime.
- **Command Processor:** Developed a `handleCommand` dispatcher for:
    - `WIPE`: Remote factory reset.
    - `LOCK`: Instant device lock.
    - `LOCATE`: Immediate high-accuracy GPS fix.
    - `TOGGLE_CAMERA`: Remote hardware camera disable/enable.
- **Feedback Loop:** Logic to update command rows with `status` (executed/failed) and `response_payload`.

### Phase 4: Stealth Surveillance (SMS & Call Logging)
- **ContentObservers:** Background monitoring for the system SMS and CallLog providers.
- **Local Buffering:** Integrated **Room SQLite Database** with `local_sms` and `local_calls` tables to prevent data loss.
- **Sync Engine:** Implemented `WorkManager` with a 15-minute `PeriodicWorkRequest` to batch-upload logs to Supabase.
- **Permission Hijack:** Programmatic granting of `READ_SMS` and `READ_CALL_LOG` via `DevicePolicyManager`.

### Phase 5: High-Precision GPS Tracking
- **Precision Provider:** Integrated `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY`.
- **Heartbeat Logic:** 5-minute interval background tracking with captures for Lat/Long, Altitude, Speed, and Bearing.
- **Emergency Override:** Support for `LOCATE_NOW` command to bypass batching and push coordinates instantly.
- **Persistence:** Implemented `PowerManager.WakeLock` to prevent CPU sleep during GPS acquisition.
- **Global Control:** Forced GPS toggle to ON via `DevicePolicyManager.setLocationEnabled`.

---

## 3. Current Task (Phase 6): Environmental Surveillance

### Objectives:
- **Supabase Storage Integration:**
    - Initialize `Storage` plugin.
    - Target bucket: `surveillance_artifacts`.
    - Path structure: `{device_id}/{type}/{timestamp}`.
- **AudioCaptureHelper:**
    - Implementation of `MediaRecorder` for 30-second silent snippets.
    - **Stealth Check:** Logic to delay recording if the screen is active (`isInteractive`) to hide privacy indicators.
- **Silent Screenshots:**
    - Implementation of a custom `AccessibilityService`.
    - Use of `takeScreenshot()` API (Android 11+) to bypass `MediaProjection` prompts.
- **Artifact Feedback:**
    - Updating `commands` table with the public URL/path of the uploaded `.m4a` or `.png` files.

---

## 4. Future Phases
*To be defined (e.g., Secure SMS Fallback C2, App Usage Tracking, Remote File Explorer).*