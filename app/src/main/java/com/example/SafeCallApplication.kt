package com.example

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.SafeCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SafeCallApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("SafeCallApplication", "App initialized. Scheduling Watchdog and background protections...")

        // 1. Initialize periodic watchdog
        WatchdogWorker.enqueuePeriodic(this)

        // 2. Start LocationVolumeService if already enabled in settings
        val repository = SafeCallRepository(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isHomeEnabled = repository.isHomeAutoRingerEnabledFlow.first()
                val isOfficeEnabled = repository.isOfficeAutoVibrateEnabledFlow.first()

                if (isHomeEnabled || isOfficeEnabled) {
                    val serviceIntent = Intent(applicationContext, LocationVolumeService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(applicationContext, serviceIntent)
                    } else {
                        applicationContext.startService(serviceIntent)
                    }
                    Log.d("SafeCallApplication", "LocationVolumeService launched from Application onCreate.")
                }
            } catch (e: Exception) {
                Log.e("SafeCallApplication", "Failed to auto-launch service from Application", e)
            }
        }
    }
}
