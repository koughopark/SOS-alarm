package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm triggering safety alert window!")
        
        val isTestMode = intent.getBooleanExtra("extra_test_mode", false)
        
        // 1. Launch AlertActivity in a clean, isolated task
        val alertIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_test_mode", isTestMode)
        }

        // 2. Set up high-priority full-screen intent notification to bypass background limits on Android 10-16
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "emergency_alarm_channel"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "🚨 긴급 안전 전송 시스템",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "지정된 안심 통화 시간 도달 시 안전 유무 알림을 전송하는 전용 채널입니다."
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 400, 1000, 400, 1000)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            4050,
            alertIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Build with high urgency & alarm category
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 안전 안심 확인 알림")
            .setContentText(if (isTestMode) "[가상 시뮬레이션 중] 즉시 탭해 주시거나 상태 선택을 진행하세요." else "설정된 통화 안전 보장 시간이 초과되었습니다.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 1000, 400, 1000, 400, 1000))
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true) // Core bypass: forces display immediately over background/lockscreen

        try {
            notificationManager.notify(4051, builder.build())
            Log.d("AlarmReceiver", "Dispatched High-Priority FullScreenIntent Notification successfully.")
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Failed to dispatch fullScreenIntent notification", e)
        }

        // 3. Keep direct startActivity inside try-catch as high priority fallback
        try {
            context.startActivity(alertIntent)
            Log.d("AlarmReceiver", "Direct background startActivity succeeded.")
        } catch (e: Exception) {
            Log.w("AlarmReceiver", "Direct background startActivity blocked: ${e.message}")
        }
    }
}
