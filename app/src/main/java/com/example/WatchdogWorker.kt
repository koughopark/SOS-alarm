package com.example

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.SafeCallRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class WatchdogWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Watchdog heartbeat check running...")
        try {
            val repository = SafeCallRepository(applicationContext)
            val isHomeEnabled = repository.isHomeAutoRingerEnabledFlow.first()
            val isOfficeEnabled = repository.isOfficeAutoVibrateEnabledFlow.first()

            if (isHomeEnabled || isOfficeEnabled) {
                // Ensure LocationVolumeService is alive
                val isServiceRunning = isServiceRunning(applicationContext, LocationVolumeService::class.java)
                Log.d(TAG, "Watchdog status: isHomeEnabled=$isHomeEnabled, isOfficeEnabled=$isOfficeEnabled, isRunning=$isServiceRunning")

                if (!isServiceRunning) {
                    Log.w(TAG, "LocationVolumeService is not running! Reviving service now...")
                    val intent = Intent(applicationContext, LocationVolumeService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(applicationContext, intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog check failed", e)
        }

        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "WatchdogWorker"
        private const val PERIODIC_WORK_NAME = "SafeCall_Heartbeat_Watchdog"

        fun enqueuePeriodic(context: Context) {
            try {
                val periodicWork = PeriodicWorkRequestBuilder<WatchdogWorker>(
                    15, TimeUnit.MINUTES, // System minimum periodic interval
                    5, TimeUnit.MINUTES   // Flex interval
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicWork
                )
                Log.d(TAG, "Periodic Watchdog successfully scheduled (15-min heartbeat).")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule periodic watchdog", e)
            }
        }

        fun enqueueImmediate(context: Context) {
            try {
                val oneTimeWork = OneTimeWorkRequestBuilder<WatchdogWorker>().build()
                WorkManager.getInstance(context).enqueue(oneTimeWork)
                Log.d(TAG, "Immediate Watchdog check enqueued.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue immediate watchdog", e)
            }
        }
    }
}
