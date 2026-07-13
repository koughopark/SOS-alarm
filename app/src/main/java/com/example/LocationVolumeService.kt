package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.data.SafeCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LocationVolumeService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var repository: SafeCallRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hasTriggeredAtHome = false
    private var hasTriggeredAtOffice = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            checkProximity(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        repository = SafeCallRepository(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("귀가 자동 감지 작동 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestLocationUpdates()
        return START_STICKY
    }

    private fun requestLocationUpdates() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L, // 3 seconds
                    2f,    // 2 meters
                    locationListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    2f,
                    locationListener
                )
            }
        } catch (e: SecurityException) {
            Log.e("LocationService", "No location permissions", e)
        } catch (e: Exception) {
            Log.e("LocationService", "Failed to request location updates", e)
        }
    }

    private fun checkProximity(currentLocation: Location) {
        serviceScope.launch {
            // 1. Process Home Location (Auto Normal Volume Ringer)
            val homeEnabled = repository.isHomeAutoRingerEnabledFlow.first()
            if (homeEnabled) {
                val homeLat = repository.homeLatitudeFlow.first()
                val homeLng = repository.homeLongitudeFlow.first()
                if (homeLat != 0.0 && homeLng != 0.0) {
                    val homeLocation = Location("home").apply {
                        latitude = homeLat
                        longitude = homeLng
                    }
                    val distance = currentLocation.distanceTo(homeLocation)
                    Log.d("LocationService", "Distance inside background monitor to home: $distance m")

                    if (distance <= 50f) {
                        if (!hasTriggeredAtHome) {
                            hasTriggeredAtHome = true
                            triggerVolumeNormalization()
                        }
                    } else if (distance > 100f) {
                        if (hasTriggeredAtHome) {
                            hasTriggeredAtHome = false
                            updateNotificationText("안심 귀가 모드: 집 밖 (볼륨 자동 해제 대기 중)")
                        }
                    }
                }
            }

            // 2. Process Office/Specific Location (Auto Vibration Mode)
            val officeEnabled = repository.isOfficeAutoVibrateEnabledFlow.first()
            if (officeEnabled) {
                val officeLat = repository.officeLatitudeFlow.first()
                val officeLng = repository.officeLongitudeFlow.first()
                if (officeLat != 0.0 && officeLng != 0.0) {
                    val officeLocation = Location("office").apply {
                        latitude = officeLat
                        longitude = officeLng
                    }
                    val distance = currentLocation.distanceTo(officeLocation)
                    Log.d("LocationService", "Distance inside background monitor to office: $distance m")

                    if (distance <= 50f) {
                        if (!hasTriggeredAtOffice) {
                            hasTriggeredAtOffice = true
                            triggerVibrationSetting()
                        }
                    } else if (distance > 100f) {
                        if (hasTriggeredAtOffice) {
                            hasTriggeredAtOffice = false
                            updateNotificationText("회사 진동 모드: 특정지점 밖 (진동 자동 전환 대기 중)")
                        }
                    }
                }
            }
        }
    }

    private fun triggerVibrationSetting() {
        runOnMainThread {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                
                // Switch ringer to Vibrate mode
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                
                serviceScope.launch {
                    val officeAddress = repository.officeAddressFlow.first().ifBlank { "지정구역" }
                    runOnMainThread {
                        updateNotificationText("위치 지정 진동 전환: $officeAddress 진입 완료!")
                        Toast.makeText(
                            applicationContext,
                            "📳 [$officeAddress 진입] 벨소리가 해제되고 진동 모드로 자동 전환되었습니다!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: SecurityException) {
                Log.e("LocationService", "Failed to change audio due to Do Not Disturb access", e)
                Toast.makeText(
                    applicationContext,
                    "⚠️ [위치 진동 전환] '방해 금지 권한(기기 설정)'이 보장되지 않아 볼륨을 진동으로 변경하지 못했습니다.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e("LocationService", "Failed to set audio settings to vibrate", e)
            }
        }
    }

    private fun triggerVolumeNormalization() {
        runOnMainThread {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                
                // Switch ringer to Normal mode
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                
                // Maximize ringtone volume
                val maxRingVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRingVol, AudioManager.FLAG_SHOW_UI)
                
                // Also maximize screen brightness if WRITE_SETTINGS is granted
                serviceScope.launch {
                    val percent = repository.safeHomeBrightnessPercentFlow.first()
                    val brightnessVal = ((percent.coerceIn(1, 100) * 255) / 100).coerceIn(1, 255)
                    runOnMainThread {
                        try {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.System.canWrite(applicationContext)) {
                                // Turn off automatic/adaptive brightness first so that setting manual value takes full effect and remains stable
                                android.provider.Settings.System.putInt(
                                    contentResolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                )
                                // Set screen brightness to custom value
                                android.provider.Settings.System.putInt(
                                    contentResolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                    brightnessVal
                                )
                                Log.d("LocationService", "Screen brightness set to custom value ($brightnessVal) ($percent%)")
                            } else {
                                Log.d("LocationService", "WRITE_SETTINGS permission not granted; skipping screen brightness adjustment.")
                            }
                        } catch (be: Exception) {
                            Log.e("LocationService", "Failed to change screen brightness", be)
                        }

                        updateNotificationText("안심 귀가 완료: 무음/진동 해제 및 벨소리/화면밝기(${percent}%) 설정 완료!")
                        
                        Toast.makeText(
                            applicationContext,
                            "🔔☀️ [안심 귀가] 집 반경 50m 이내에 도달하여 벨소리와 화면 밝기가 ${percent}%로 자동 설정되었습니다!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: SecurityException) {
                Log.e("LocationService", "Failed to change audio due to Do Not Disturb access", e)
                Toast.makeText(
                    applicationContext,
                    "⚠️ [안심 귀가] 집 근처 도달! '방해 금지 권한(기기 설정)'이 보장되지 않아 벨소리를 기기에서 자동 변환하지 못했습니다. 무음/진동 해제를 위해 설정에서 권한을 변경해주세요.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e("LocationService", "Failed to set audio settings", e)
            }
        }
    }

    private fun updateNotificationText(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("실시간 안심 귀가 감지 서비스")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "실시간 안심 귀가 감지 채널",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "LocationVolumeServiceChannel"
        private const val NOTIFICATION_ID = 9920
    }
}
