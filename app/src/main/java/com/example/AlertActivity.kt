package com.example

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.lifecycle.lifecycleScope
import com.example.data.SafeCallRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlertActivity : ComponentActivity() {
 
    private var vibrator: Vibrator? = null
    private var ringtone: android.media.Ringtone? = null
    private var audioManager: android.media.AudioManager? = null
    private var originalAlarmVolume: Int = -1
    private var originalMusicVolume: Int = -1
    private var originalSpeakerphoneState: Boolean = false
    private var originalAudioMode: Int = -1
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var toneGenerator: android.media.ToneGenerator? = null
    private var toneJob: kotlinx.coroutines.Job? = null
    private lateinit var repository: SafeCallRepository
    private var isSmsSent = false
 
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
 
        // Configure activity to turn screen on and show when locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
 
        repository = SafeCallRepository(this)
 
        // Start strong vibration and alarm sound
        startVibrationAndSound()
 
        val isTestMode = intent.getBooleanExtra("extra_test_mode", false)
        val initialSeconds = if (isTestMode) 15 else 300 // 5 minutes = 300s, test mode = 15s
 
        setContent {
            MyApplicationTheme {
                AlertScreen(
                    repository = repository,
                    initialSeconds = initialSeconds,
                    isTestMode = isTestMode,
                    onSafeClicked = {
                        handleSafeResponse()
                    },
                    onEndCallClicked = {
                        endActiveCall()
                    },
                    onTimeoutTriggered = {
                        handleTimeoutResponse()
                    }
                )
            }
        }
    }

    private fun endActiveCall() {
        stopVibrationAndSound()
        lifecycleScope.launch {
            repository.addLog(0, "CALL_ENDED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    telecomManager.endCall()
                    Log.d("AlertActivity", "End call succeeded via TelecomManager")
                }
            } catch (e: Exception) {
                Log.e("AlertActivity", "TelecomManager endCall failing", e)
            }
        }
        Toast.makeText(this, "통화를 즉시 종료했습니다.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startVibrationAndSound() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager?.let { am ->
                // Save original values
                originalAlarmVolume = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
                originalMusicVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                originalSpeakerphoneState = am.isSpeakerphoneOn
                originalAudioMode = am.mode

                // 1. Maximize all relevant volume streams to guarantee absolute maximum volume can be reached
                val maxAlarmVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxAlarmVol, 0)

                val maxMusicVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxMusicVol, 0)

                val maxRingVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
                am.setStreamVolume(android.media.AudioManager.STREAM_RING, maxRingVol, 0)

                // 2. Force mode and route to outer speakerphone
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = am.availableCommunicationDevices
                    val speakerDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        val active = am.setCommunicationDevice(speakerDevice)
                        Log.d("AlertActivity", "Selected modern communication device TYPE_BUILTIN_SPEAKER: $active")
                    } else {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                        Log.d("AlertActivity", "Device list empty, fallback to speakerphoneOn = true")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    Log.d("AlertActivity", "Legacy speakerphone forced to true")
                }

                // 3. Request transient exclusive Audio Focus to override standard in-call background ducking policies
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val focusAttrs = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                            .setAudioAttributes(focusAttrs)
                            .setAcceptsDelayedFocusGain(false)
                            .build()
                        am.requestAudioFocus(audioFocusRequest!!)
                        Log.d("AlertActivity", "Transient exclusive AudioFocus granted for Alarm usage.")
                    } else {
                        @Suppress("DEPRECATION")
                        am.requestAudioFocus(
                            null,
                            android.media.AudioManager.STREAM_ALARM,
                            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                        )
                        Log.d("AlertActivity", "Legacy focus requested.")
                    }
                } catch (focusEx: Exception) {
                    Log.e("AlertActivity", "Failed to acquire audio focus", focusEx)
                }
            }
        } catch (e: Exception) {
            Log.e("AlertActivity", "Audio controls initialization failed", e)
        }

        try {
            // Priority: STREAM_VOICE_CALL which sits at top level during an active phone conversation!
            toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 100)
            toneJob = lifecycleScope.launch {
                while (true) {
                    try {
                        // TONE_CDMA_HIGH_L (Loud, sharp, high pitch warning paging beep)
                        toneGenerator?.startTone(android.media.ToneGenerator.TONE_CDMA_HIGH_L, 800)
                        delay(1200)
                    } catch (ex: Exception) {
                        Log.e("AlertActivity", "Tone loop failed", ex)
                        break
                    }
                }
            }
            Log.d("AlertActivity", "ToneGenerator initialized on STREAM_VOICE_CALL stream.")
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to start ToneGenerator on STREAM_VOICE_CALL, fallback to ALARM stream", e)
            try {
                toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                toneJob = lifecycleScope.launch {
                    while (true) {
                        try {
                            toneGenerator?.startTone(android.media.ToneGenerator.TONE_CDMA_HIGH_L, 800)
                            delay(1200)
                        } catch (ex: Exception) {
                            Log.e("AlertActivity", "Tone loop failed", ex)
                            break
                        }
                    }
                }
            } catch (e2: Exception) {
                Log.e("AlertActivity", "All ToneGenerator initialization failed", e2)
            }
        }

        try {
            // Get standard alarm or fallback to ringtone URI
            val alertUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

            // Play via MediaPlayer configured for USAGE_ALARM (designed to play loudly via system outer speaker)
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                setVolume(1.0f, 1.0f) // Set programmatic volume to absolute maximum (100% gain)
                start()
            }
            Log.d("AlertActivity", "MediaPlayer played with maximum alarm stream settings.")

            // 4. Attach LoudnessEnhancer to the player's session. This amplifies the acoustic sound wave
            // programmatically far beyond standard maximum device levels (target gain set in millibels: 3000mB = +30dB boost!)
            try {
                loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(mediaPlayer!!.audioSessionId).apply {
                    setTargetGain(3000)
                    enabled = true
                }
                Log.d("AlertActivity", "LoudnessEnhancer successfully activated at +30dB gain.")
            } catch (e: Exception) {
                Log.e("AlertActivity", "Failed to start LoudnessEnhancer wave booster", e)
            }
        } catch (e: Exception) {
            Log.e("AlertActivity", "Loud USAGE_ALARM playback failed, attempting USAGE_MEDIA fallback", e)
            try {
                val alertUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

                mediaPlayer?.release()
                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(applicationContext, alertUri)
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    setVolume(1.0f, 1.0f)
                    start()
                }

                try {
                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(mediaPlayer!!.audioSessionId).apply {
                        setTargetGain(2500)
                        enabled = true
                    }
                    Log.d("AlertActivity", "LoudnessEnhancer activated on fallback media player at +25dB.")
                } catch (leEx: Exception) {
                    Log.e("AlertActivity", "LoudnessEnhancer fallback failed", leEx)
                }
            } catch (fallbackEx: Exception) {
                Log.e("AlertActivity", "Fallback media player failed, using Ringtone player", fallbackEx)
                try {
                    val alertUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    ringtone = android.media.RingtoneManager.getRingtone(applicationContext, alertUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ringtone?.audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                    ringtone?.play()
                } catch (rEx: Exception) {
                    Log.e("AlertActivity", "All audio options failed", rEx)
                }
            }
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            vibrator?.let {
                if (it.hasVibrator()) {
                    val pattern = longArrayOf(0, 1000, 400, 1000, 400)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(VibrationEffect.createWaveform(pattern, 1))
                    } else {
                        @Suppress("DEPRECATION")
                        it.vibrate(pattern, 1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AlertActivity", "Vibration failed", e)
        }
    }

    private fun stopVibrationAndSound() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to cancel vibration", e)
        }
        try {
            toneJob?.cancel()
            toneJob = null
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to cancel toneJob", e)
        }
        try {
            toneGenerator?.let {
                it.stopTone()
                it.release()
            }
            toneGenerator = null
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to release toneGenerator", e)
        }
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to stop ringtone", e)
        }
        try {
            loudnessEnhancer?.let {
                it.enabled = false
                it.release()
            }
            loudnessEnhancer = null
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to release loudnessEnhancer", e)
        }
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to stop/release mediaPlayer", e)
        }
        try {
            audioManager?.let { am ->
                // Abandon Audio Focus
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
                audioFocusRequest = null

                // Restore stream volumes and speakerphone status
                if (originalAlarmVolume != -1) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
                }
                if (originalMusicVolume != -1) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
                }
                if (originalAudioMode != -1) {
                    am.mode = originalAudioMode
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = originalSpeakerphoneState
                }
                Log.d("AlertActivity", "Audio focus, settings, and routing successfully restored to original states.")
            }
        } catch (e: Exception) {
            Log.e("AlertActivity", "Failed to restore audio configurations", e)
        }
    }

    private fun handleSafeResponse() {
        if (isSmsSent) return
        isSmsSent = true
        stopVibrationAndSound()

        lifecycleScope.launch {
            val primaryPhone = repository.getGuardianPhone()
            val emergencyContacts = repository.getEmergencyContactsOnce()
            
            val message = "[안심 통화 알리미] 어르신이 현재 통화 중이시며, '안전함'을 확인하셨습니다."
            var sentCount = 0

            if (primaryPhone.isNotEmpty()) {
                sendSMS(primaryPhone, message)
                sentCount++
            }

            emergencyContacts.forEach { contact ->
                if (contact.phone.isNotBlank()) {
                    sendSMS(contact.phone, message)
                    sentCount++
                }
            }

            if (sentCount > 0) {
                repository.addLog(60, "SMS_SAFE_SENT")
                Toast.makeText(this@AlertActivity, "모든 보호자(총 ${sentCount}곳)에게 안심 문자를 전송했습니다.", Toast.LENGTH_LONG).show()
            } else {
                repository.addLog(60, "DISMISSED_NO_PHONE")
                Toast.makeText(this@AlertActivity, "저장된 보호자 또는 긴급 연락처가 없습니다.", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun handleSafeResponseOld() {

        lifecycleScope.launch {
            val phone = repository.getGuardianPhone()
            val name = repository.getGuardianName()

            if (phone.isNotEmpty()) {
                val message = "[안심 통화 알리미] 어르신이 현재 통화 중이시며, '안전함'을 확인하셨습니다."
                sendSMS(phone, message)
                repository.addLog(60, "SMS_SAFE_SENT")
                Toast.makeText(this@AlertActivity, "보호자($name)에게 안심 문자를 전송했습니다.", Toast.LENGTH_LONG).show()
            } else {
                repository.addLog(60, "DISMISSED_NO_PHONE")
                Toast.makeText(this@AlertActivity, "저장된 보호자 연락처가 없습니다.", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun handleTimeoutResponse() {
        if (isSmsSent) return
        isSmsSent = true
        stopVibrationAndSound()

        lifecycleScope.launch {
            val primaryPhone = repository.getGuardianPhone()
            val emergencyContacts = repository.getEmergencyContactsOnce()

            val message = "[긴급 알림] 어르신이 장시간 통화 중이며 응답이 없습니다. 신속한 확인이 필요합니다."
            var sentCount = 0

            if (primaryPhone.isNotEmpty()) {
                sendSMS(primaryPhone, message)
                sentCount++
            }

            emergencyContacts.forEach { contact ->
                if (contact.phone.isNotBlank()) {
                    sendSMS(contact.phone, message)
                    sentCount++
                }
            }

            if (sentCount > 0) {
                repository.addLog(65, "SMS_TIMEOUT_SENT")
                Toast.makeText(this@AlertActivity, "모든 보호자(총 ${sentCount}곳)에게 미응답 경고 문자를 보냈습니다.", Toast.LENGTH_LONG).show()
            } else {
                repository.addLog(65, "TIMEOUT_NO_PHONE")
                Toast.makeText(this@AlertActivity, "경고: 어르신 미응답 - 보호자 연락처 부족", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun handleTimeoutResponseOld() {
        if (isSmsSent) return
        isSmsSent = true
        stopVibrationAndSound()

        lifecycleScope.launch {
            val phone = repository.getGuardianPhone()
            val name = repository.getGuardianName()

            if (phone.isNotEmpty()) {
                val message = "[긴급 알림] 어르신이 장시간 통화 중이며 응답이 없습니다. 신속한 확인이 필요합니다."
                sendSMS(phone, message)
                repository.addLog(65, "SMS_TIMEOUT_SENT")
                Toast.makeText(this@AlertActivity, "보호자($name)에게 미응답 경고 문자를 보냈습니다.", Toast.LENGTH_LONG).show()
            } else {
                repository.addLog(65, "TIMEOUT_NO_PHONE")
                Toast.makeText(this@AlertActivity, "경고: 어르신 미응답 - 보호자 연락처 부족", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun sendSMS(phone: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phone, null, message, null, null)
            Log.d("AlertActivity", "SMS Sent to $phone: $message")
        } catch (e: Exception) {
            Log.e("AlertActivity", "SMS dispatcher failed", e)
            Toast.makeText(this, "문자 발송 실패: 권한 확인 또는 기기 한계", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        stopVibrationAndSound()
        super.onDestroy()
    }
}

@Composable
fun AlertScreen(
    repository: SafeCallRepository,
    initialSeconds: Int,
    isTestMode: Boolean,
    onSafeClicked: () -> Unit,
    onEndCallClicked: () -> Unit,
    onTimeoutTriggered: () -> Unit
) {
    var secondsLeft by remember { mutableStateOf(initialSeconds) }
    val context = LocalContext.current

    val guardianName by repository.guardianNameFlow.collectAsState(initial = "")
    val guardianPhone by repository.guardianPhoneFlow.collectAsState(initial = "")
    val callLimitMinutes by repository.callLimitMinutesFlow.collectAsState(initial = 60)

    val guardianNameDisplay = if (guardianName.isNotEmpty()) guardianName else "미등록 보호자"
    val guardianRelationDisplay = if (guardianName.isNotEmpty()) "안심 보호자" else "보호자 등록이 필요합니다"

    // Pulsing animation for elements
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Countdown Timer Loop
    LaunchedEffect(key1 = true) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        onTimeoutTriggered()
    }

    val minutes = secondsLeft / 60
    val seconds = secondsLeft % 60
    val timeString = String.format("%d:%02d", minutes, seconds)

    val progress = if (initialSeconds > 0) secondsLeft.toFloat() / initialSeconds.toFloat() else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF8F1)) // Warm cream/peach minimal background
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Section: High Contrast Status Badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .background(Color(0xFFFEF3C7), RoundedCornerShape(50.dp))
                        .border(2.dp, Color(0xFFF59E0B), RoundedCornerShape(50.dp))
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "장시간 통화 알림",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF78350F)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "통화가 계속되고\n있습니다",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A),
                    textAlign = TextAlign.Center,
                    lineHeight = 44.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${callLimitMinutes}분째 통화 중 감지",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF475569)
                )
            }

            // Central Visual Cue (Huge Icon with vibration visual)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .scale(scale)
                        .background(Color(0xFFF59E0B), CircleShape)
                        .border(8.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Active call warning symbol",
                        tint = Color.White,
                        modifier = Modifier.size(90.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "혹시 전화를 끄는 것을\n잊으셨나요?",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF334155),
                    textAlign = TextAlign.Center,
                    lineHeight = 30.sp
                )
            }

            // Timer / Progress bar: 5 minute countdown
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "자동 안심 문자 발송까지",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = timeString,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB45309)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = Color(0xFFF59E0B),
                    trackColor = Color(0xFFE2E8F0)
                )

                if (isTestMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "[테스트 설정 - 15초 카운트다운]",
                        fontSize = 12.sp,
                        color = Color(0xFFD97606),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            // Primary Action: Huge Safety Button + Guardian Context
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Safety button
                Card(
                    shape = RoundedCornerShape(40.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF059669)), // Emerald-600
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .scale(scale)
                        .clip(RoundedCornerShape(40.dp))
                        .clickable { onSafeClicked() }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "네, 괜찮아요!",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "보호자에게 안심 문자 발송",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFD1FAE5),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Immediate Call Disconnect Button (통화 종료)
                Card(
                    shape = RoundedCornerShape(40.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFDC2626)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(85.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .clickable { onEndCallClicked() }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "End Call Icon",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "전화 바로 끊기 (통화 종료)",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Guardian Context info bar at bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x99FFFFFF), RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFFF1F5F9), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "Guardian Profile Icon",
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = guardianRelationDisplay,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    text = guardianNameDisplay,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E293B)
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "미응답 시", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(
                                text = "긴급 확인 요청",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFDC2626)
                            )
                        }
                    }
                }
            }
        }
    }
}
