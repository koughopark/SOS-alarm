package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm triggering safety alert window!")
        
        val isTestMode = intent.getBooleanExtra("extra_test_mode", false)
        
        // Launch AlertActivity in a clean, isolated task
        val alertIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_test_mode", isTestMode)
        }
        context.startActivity(alertIntent)
    }
}
