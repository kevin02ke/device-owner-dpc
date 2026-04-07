package com.system.dpc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class DpcAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DpcAccessibility", "Accessibility Service Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(): Bitmap = suspendCancellableCoroutine { continuation ->
        val executor = Executor { it.run() }
        
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                if (bitmap != null) {
                    continuation.resume(bitmap)
                } else {
                    continuation.resumeWithException(Exception("Failed to wrap hardware buffer into Bitmap"))
                }
            }

            override fun onFailure(errorCode: Int) {
                continuation.resumeWithException(Exception("Screenshot failed with error code: $errorCode"))
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not required for screenshot trigger
    }

    override fun onInterrupt() {
        // Not required
    }

    companion object {
        @Volatile
        var instance: DpcAccessibilityService? = null
            private set
    }
}