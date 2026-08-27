package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.SafeCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast: $action")

        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (action in validActions) {
            // Re-schedule Watchdog WorkManager
            WatchdogWorker.enqueuePeriodic(context.applicationContext)

            val repository = SafeCallRepository(context.applicationContext)
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val isHomeEnabled = repository.isHomeAutoRingerEnabledFlow.first()
                    val isOfficeEnabled = repository.isOfficeAutoVibrateEnabledFlow.first()

                    if (isHomeEnabled || isOfficeEnabled) {
                        Log.d("BootReceiver", "Starting LocationVolumeService from BootReceiver...")
                        val serviceIntent = Intent(context.applicationContext, LocationVolumeService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
                        } else {
                            context.applicationContext.startService(serviceIntent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start service from BootReceiver", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
