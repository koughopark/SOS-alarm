package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.example.data.SafeCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d("PhoneStateReceiver", "Phone Call Intercepted! State changed to: $state")

        val repository = SafeCallRepository(context.applicationContext)

        // Make this non-blocking
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch the service status from Room via Flow's first() operator
                val isServiceEnabled = repository.isServiceEnabledFlow.first()

                if (!isServiceEnabled) {
                    Log.d("PhoneStateReceiver", "Safety monitoring service is disabled inside settings. Ignoring call event.")
                    return@launch
                }

                when (state) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        Log.d("PhoneStateReceiver", "Call active (OFFHOOK). Scheduling safety check after 60 mins.")
                        // Real delay: 60 minutes.
                        val delayMillis = 60 * 60 * 1000L
                        scheduleSafetyAlarm(context, delayMillis, false)
                        repository.addLog(0, "CALL_STARTED")
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        Log.d("PhoneStateReceiver", "Call ended (IDLE). Cancelling any pending safety alert.")
                        cancelSafetyAlarm(context)
                        repository.addLog(0, "CALL_ENDED")
                    }
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        Log.d("PhoneStateReceiver", "Ringing...")
                    }
                }
            } catch (e: Exception) {
                Log.e("PhoneStateReceiver", "Error processing broadcast", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ALARM_REQ_CODE = 4040

        fun scheduleSafetyAlarm(context: Context, delayMillis: Long, isTestMode: Boolean) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("extra_test_mode", isTestMode)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAtMillis = System.currentTimeMillis() + delayMillis

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
                Log.d("PhoneStateReceiver", "Safety check scheduled in ${delayMillis / 1000} seconds. Test mode: $isTestMode")
            } catch (e: Exception) {
                Log.e("PhoneStateReceiver", "Alarm Scheduling Failed", e)
            }
        }

        fun cancelSafetyAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Log.d("PhoneStateReceiver", "Pending safety check successfully canceled.")
        }
    }
}
