package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.telephony.SmsManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.location.Location
import android.location.LocationManager
import android.app.NotificationManager
import android.media.AudioManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.PhoneStateReceiver
import com.example.data.CallLogEntity
import com.example.data.SafeCallRepository
import com.example.data.EmergencyContact
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var repository: SafeCallRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = SafeCallRepository(this)

        setContent {
            MyApplicationTheme {
                MainScreen(repository = repository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: SafeCallRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State collected from local RoomDB
    val guardianName by repository.guardianNameFlow.collectAsState(initial = "")
    val guardianPhone by repository.guardianPhoneFlow.collectAsState(initial = "")
    val isServiceEnabled by repository.isServiceEnabledFlow.collectAsState(initial = true)
    val callLogs by repository.allLogsFlow.collectAsState(initial = emptyList())

    val homeLatitude by repository.homeLatitudeFlow.collectAsState(initial = 0.0)
    val homeLongitude by repository.homeLongitudeFlow.collectAsState(initial = 0.0)
    val homeAddress by repository.homeAddressFlow.collectAsState(initial = "")
    val isHomeAutoRingerEnabled by repository.isHomeAutoRingerEnabledFlow.collectAsState(initial = false)

    // Form inputs state
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var isEditingGuardian by remember { mutableStateOf(false) }

    var homeLatInput by remember { mutableStateOf("") }
    var homeLngInput by remember { mutableStateOf("") }
    var homeAddressInput by remember { mutableStateOf("") }
    var isEditingHome by remember { mutableStateOf(false) }

    // Sync form inputs when DB values load
    LaunchedEffect(guardianName, guardianPhone) {
        if (!isEditingGuardian) {
            nameInput = guardianName
            phoneInput = guardianPhone
        }
    }

    LaunchedEffect(homeLatitude, homeLongitude, homeAddress) {
        if (!isEditingHome) {
            homeLatInput = if (homeLatitude != 0.0) homeLatitude.toString() else ""
            homeLngInput = if (homeLongitude != 0.0) homeLongitude.toString() else ""
            homeAddressInput = if (homeAddress.isNotEmpty()) homeAddress else "우리집"
        }
    }

    // Start/Stop location monitoring service automatically
    LaunchedEffect(isHomeAutoRingerEnabled) {
        val serviceIntent = Intent(context, LocationVolumeService::class.java)
        if (isHomeAutoRingerEnabled) {
            val isGpsGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (isGpsGranted) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("MainActivity", "Started LocationVolumeService foreground")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to start Location volume service", e)
                }
            }
        } else {
            try {
                context.stopService(serviceIntent)
                Log.d("MainActivity", "Stopped LocationVolumeService")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to stop service", e)
            }
        }
    }

    // Permission Check States
    var hasSmsPermission by remember { mutableStateOf(false) }
    var hasPhoneStatePermission by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var hasAnswerCallsPermission by remember { mutableStateOf(false) }

    val updatePermissions = {
        hasSmsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        hasPhoneStatePermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        hasLocationPermission = (ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED)

        hasAnswerCallsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // Dynamic permission request launcher
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        updatePermissions()
        val allPrimaryGranted = (results[Manifest.permission.SEND_SMS] == true) &&
                (results[Manifest.permission.READ_PHONE_STATE] == true)
        if (allPrimaryGranted) {
            Toast.makeText(context, "필수 권한이 정상 작동되도록 승인되었습니다!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "안전 감지 및 안심 문자 발송을 위해 문자/전화 권한 승인이 필수적입니다.", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger initial permission assessment
    LaunchedEffect(key1 = true) {
        updatePermissions()
    }

    // SOS triggering and location retrieval helper
    val getSOSSendingLocation = {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = locationManager.getProviders(true)
                var bestLocation: Location? = null
                for (provider in providers) {
                    val loc = locationManager.getLastKnownLocation(provider) ?: continue
                    if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                        bestLocation = loc
                    }
                }
                if (bestLocation != null) {
                    String.format(Locale.US, "위도 %.5f, 경도 %.5f", bestLocation.latitude, bestLocation.longitude)
                } else {
                    "서울특별시 중구 태평로1가 (추정 위치)"
                }
            } else {
                "위치 권한 미허용 (추정 위치: 서울특별시 종로구)"
            }
        } catch (e: Exception) {
            "네트워크 위치 검색 실패 (추정 위치)"
        }
    }

    val sendSmsDirect = { phone: String, message: String ->
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phone, null, message, null, null)
            Log.d("MainActivity", "SOS SMS sent to $phone")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to dispatch SMS", e)
        }
    }

    // Emergency Contacts Settings States
    val emergencyContacts by repository.getEmergencyContactsFlow().collectAsState(initial = emptyList())
    var editingContactIndex by remember { mutableStateOf(-1) }
    var contactNameInput by remember { mutableStateOf("") }
    var contactPhoneInput by remember { mutableStateOf("") }
    var isSosPressing by remember { mutableStateOf(false) }
    var sosProgress by remember { mutableStateOf(0f) }

    val triggerSOS = {
        coroutineScope.launch {
            val locationStr = getSOSSendingLocation()
            val message = "[긴급 구조 요청] 도움이 필요합니다. 현재 위치: $locationStr"
            var dispatchedTargetCount = 0

            // 1. Send to all 5 custom emergency contacts
            emergencyContacts.forEach { contact ->
                if (contact.phone.isNotBlank()) {
                    sendSmsDirect(contact.phone, message)
                    dispatchedTargetCount++
                }
            }

            // 2. Also send to primary guardian if present
            if (guardianPhone.isNotBlank()) {
                sendSmsDirect(guardianPhone, message)
                dispatchedTargetCount++
            }

            if (dispatchedTargetCount > 0) {
                repository.addLog(0, "SMS_SOS_SENT")
                Toast.makeText(context, "${dispatchedTargetCount}개 연락처로 안심 긴급 구조 SOS 문자가 발급 및 발송 완료되었습니다!", Toast.LENGTH_LONG).show()

                // Generate safety vibration
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(1200, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(1200)
                    }
                } catch (e: Exception) {
                    Log.d("MainActivity", "Vibrate fail")
                }
            } else {
                Toast.makeText(context, "등록된 긴급 연락처나 비상 보호자가 없습니다. 먼저 보호자 정보를 설정창에 입력해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Active Simulation State
    var simulatedCallActive by remember { mutableStateOf(false) }
    var simulatedCallElapsedSeconds by remember { mutableStateOf(0) }

    // Simulation Clock Loop
    LaunchedEffect(simulatedCallActive) {
        if (simulatedCallActive) {
            simulatedCallElapsedSeconds = 0
            while (simulatedCallActive) {
                delay(1000)
                simulatedCallElapsedSeconds++
                // Automatically trigger test Alarm (AlertActivity) at 15 seconds
                if (simulatedCallElapsedSeconds == 15) {
                    simulatedCallActive = false
                    // Directly launch AlertActivity for robust instant verification!
                    val intent = Intent(context, AlertActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("extra_test_mode", true)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    // SOS Countdown tracking loop
    LaunchedEffect(isSosPressing) {
        if (isSosPressing) {
            val startTime = System.currentTimeMillis()
            val duration = 3000L
            while (isSosPressing) {
                val elapsed = System.currentTimeMillis() - startTime
                sosProgress = (elapsed.toFloat() / duration).coerceAtMost(1f)
                if (elapsed >= duration) {
                    triggerSOS()
                    isSosPressing = false
                    sosProgress = 0f
                    break
                }
                delay(50)
            }
        } else {
            sosProgress = 0f
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "안심 통화 알리미",
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F172A),
                        fontSize = 21.sp
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFFFFF8F1)
                )
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFFF8F1)) // Warm cream minimalist background
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // 0. High Contrast SOS Panic Button (3 seconds hold)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFECACA), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)), // Warm panic pink/cream background
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "원터치 비상 구조 시스템 (SOS)",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF991B1B)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "위험할 때 아래 SOS 버튼을 3초 동안 꾹 누르면\n위치정보와 구조 문자가 긴급 연락처 전체에 발송됩니다.",
                            fontSize = 12.sp,
                            color = Color(0xFFB91C1C),
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Large SOS tactile button container
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(170.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFCA5A5).copy(alpha = 0.3f))
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitFirstDown()
                                            isSosPressing = true
                                            waitForUpOrCancellation()
                                            isSosPressing = false
                                        }
                                    }
                                }
                        ) {
                            // Pulsing Outer ring
                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSosPressing) Color(0xFF7F1D1D) else Color(0xFFDC2626)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "SOS",
                                        fontSize = 44.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                    if (isSosPressing) {
                                        val remainingSecs = (3 - (sosProgress * 3).toInt()).coerceAtLeast(1)
                                        Text(
                                            text = "${remainingSecs}초 더 유지",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }

                            // Dynamic Progress Overlay
                            if (isSosPressing || sosProgress > 0f) {
                                CircularProgressIndicator(
                                    progress = sosProgress,
                                    modifier = Modifier.size(160.dp),
                                    color = Color(0xFF7F1D1D),
                                    strokeWidth = 6.dp,
                                    trackColor = Color.Transparent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Tactical feedback hint text
                        Text(
                            text = if (isSosPressing) "🚨 구조 요청 전송 중... 떼지 마세요!" else "👆 이곳을 손가락으로 꾹 누르세요 (3초)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSosPressing) Color(0xFF991B1B) else Color(0xFF475569)
                        )
                    }
                }
            }

            // 0.5. Immediate Call Disconnect Button (전화 바로 끊기)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFDC2626)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable {
                            simulatedCallActive = false
                            PhoneStateReceiver.cancelSafetyAlarm(context)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                try {
                                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                                        @Suppress("DEPRECATION")
                                        telecomManager.endCall()
                                        Log.d("MainActivity", "Call end commanded via Telecom API")
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to terminate call", e)
                                }
                            }
                            Toast.makeText(context, "즉시 전화를 끊었으며 통화가 종료되었습니다.", Toast.LENGTH_SHORT).show()
                        }
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
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 1. Welcome & Service Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x99FFFFFF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(if (isServiceEnabled) Color(0xFF059669) else Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isServiceEnabled) "통화 지킴이 작동 중" else "지킴이 대기 상태",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isServiceEnabled) Color(0xFF0F172A) else Color.Gray
                                )
                            }

                            Switch(
                                checked = isServiceEnabled,
                                onCheckedChange = { checked ->
                                    coroutineScope.launch {
                                        repository.saveServiceEnabled(checked)
                                        if (!checked) {
                                            PhoneStateReceiver.cancelSafetyAlarm(context)
                                        }
                                        Toast.makeText(
                                            context,
                                            if (checked) "안심 통화 시스템이 활성화되었습니다." else "시스템이 비활성화되었습니다.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF059669)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "어르신이 60분 이상 전화를 끊지 않으실 경우, 진동 알림을 보내고 안심 확인 화면을 띄웁니다. 미응답 상태가 계속되면 등록하신 보호자에게 긴급 구호 문자가 자동 전송됩니다.",
                            fontSize = 14.sp,
                            color = Color(0xFF475569),
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }

            // 2. Permission Status Cards
            item {
                val hasAll = hasSmsPermission && hasPhoneStatePermission && hasLocationPermission
                val containerColor = if (hasAll) Color(0xFFE8F5E9) else Color(0xFFFEF2F2)
                val borderColor = if (hasAll) Color(0xFFA5D6A7) else Color(0xFFFCA5A5)
                val titleColor = if (hasAll) Color(0xFF0F5132) else Color(0xFF842029)
                val statusText = if (hasAll) "정상 작동 준비 완료" else "작동을 위해 권한 동의 필요"

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderColor, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = containerColor),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (hasAll) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    tint = if (hasAll) Color(0xFF059669) else Color(0xFFDC2626),
                                    contentDescription = "Status",
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "연결 및 권한 현황",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = titleColor
                                    )
                                    Text(
                                        text = statusText,
                                        fontSize = 12.sp,
                                        color = if (hasAll) Color(0xFF198754) else Color(0xFFDC3545)
                                    )
                                }
                            }

                            if (!hasAll) {
                                Button(
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    onClick = {
                                        val permissions = mutableListOf(
                                            Manifest.permission.SEND_SMS,
                                            Manifest.permission.READ_PHONE_STATE,
                                            Manifest.permission.VIBRATE,
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
                                        }
                                        permLauncher.launch(permissions.toTypedArray())
                                    },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("권한 허용", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0x11000000))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Individual permission logs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("1. 통화 상태 감지 권한", fontSize = 13.sp, color = Color(0xFF475569))
                            Text(
                                text = if (hasPhoneStatePermission) "허용됨" else "미허용",
                                color = if (hasPhoneStatePermission) Color(0xFF059669) else Color(0xFFDC2626),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("2. 보호자 문자 발송 권한", fontSize = 13.sp, color = Color(0xFF475569))
                            Text(
                                text = if (hasSmsPermission) "허용됨" else "미허용",
                                color = if (hasSmsPermission) Color(0xFF059669) else Color(0xFFDC2626),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("3. GPS 위치 정보 권한", fontSize = 13.sp, color = Color(0xFF475569))
                            Text(
                                text = if (hasLocationPermission) "허용됨" else "미허용",
                                color = if (hasLocationPermission) Color(0xFF059669) else Color(0xFFDC2626),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("4. 원격 전격 종료 제어 권한", fontSize = 13.sp, color = Color(0xFF475569))
                            Text(
                                text = if (hasAnswerCallsPermission) "허용됨" else "미허용",
                                color = if (hasAnswerCallsPermission) Color(0xFF059669) else Color(0xFFDC2626),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // 3. Guardian Registration Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x99FFFFFF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Lock Icon",
                                    tint = Color(0xFF059669),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "비상 보호자(수신처) 설정",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }

                            if (!isEditingGuardian && guardianPhone.isNotEmpty()) {
                                TextButton(onClick = { isEditingGuardian = true }) {
                                    Text("수정하기", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF059669))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isEditingGuardian || guardianPhone.isEmpty()) {
                            // Name input
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("보호자 성명 (예: 아들, 딸, 사회복지사)") },
                                placeholder = { Text("예: 큰아들") },
                                leadingIcon = { Icon(imageVector = Icons.Default.Person, contentDescription = "Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF059669),
                                    focusedLabelColor = Color(0xFF059669)
                                )
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Phone input
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { phoneInput = it },
                                label = { Text("보호자 휴대폰 번호 (- 제외)") },
                                placeholder = { Text("01012345678") },
                                leadingIcon = { Icon(imageVector = Icons.Default.Phone, contentDescription = "Phone") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF059669),
                                    focusedLabelColor = Color(0xFF059669)
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    if (nameInput.isBlank() || phoneInput.isBlank()) {
                                        Toast.makeText(context, "보호자 성명과 연락처를 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    coroutineScope.launch {
                                        repository.saveGuardianInfo(nameInput.trim(), phoneInput.trim())
                                        isEditingGuardian = false
                                        Toast.makeText(context, "보호자 정보가 안전하게 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Save")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("보호자 정보 등록/저장", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        } else {
                            // Saved View Mode
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF0FDF4), RoundedCornerShape(16.dp))
                                    .border(1.dp, Color(0xFFBBF7D0), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "Account Icon",
                                        tint = Color(0xFF059669),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = guardianName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = Color(0xFF065F46)
                                        )
                                        Text(
                                            text = guardianPhone,
                                            fontSize = 14.sp,
                                            color = Color(0xFF047857)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3.5. Emergency Contacts Card (Up to 5)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x99FFFFFF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "SOS Contacts Icon",
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "긴급 연락처 설정 (최대 5명)",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "SOS 긴급 요청 시 메세지가 동시에 발송됩니다",
                                        fontSize = 12.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Render 5 slots
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (index in 0 until 5) {
                                val contact = emergencyContacts.find { it.index == index }
                                val name = contact?.name ?: ""
                                val phone = contact?.phone ?: ""
                                val isEditing = editingContactIndex == index

                                if (isEditing) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFFEF2F2), RoundedCornerShape(16.dp))
                                            .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(16.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = "${index + 1}번 긴급 연락처 등록",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFF991B1B)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = contactNameInput,
                                            onValueChange = { contactNameInput = it },
                                            label = { Text("이름 또는 관계 (예: 큰딸, 이웃집)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFFDC2626),
                                                focusedLabelColor = Color(0xFFDC2626)
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = contactPhoneInput,
                                            onValueChange = { contactPhoneInput = it },
                                            label = { Text("휴대폰 번호 (- 제외)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFFDC2626),
                                                focusedLabelColor = Color(0xFFDC2626)
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Button(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        repository.saveEmergencyContact(index, contactNameInput.trim(), contactPhoneInput.trim())
                                                        editingContactIndex = -1
                                                        Toast.makeText(context, "${index + 1}번 긴급 연락처가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("저장", fontWeight = FontWeight.Bold)
                                            }
                                            OutlinedButton(
                                                onClick = { editingContactIndex = -1 },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("취소", color = Color(0xFF475569))
                                            }
                                        }
                                    }
                                } else {
                                    // Row presentation list
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (phone.isNotBlank()) Color(0xFFFEF2F2) else Color(0xFFF8FAFC),
                                                RoundedCornerShape(16.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (phone.isNotBlank()) Color(0xFFFEE2E2) else Color(0xFFE2E8F0),
                                                RoundedCornerShape(16.dp)
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(
                                                        if (phone.isNotBlank()) Color(0xFFFCA5A5) else Color(0xFFCBD5E1),
                                                        CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${index + 1}",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            if (phone.isNotBlank()) {
                                                Column {
                                                    Text(
                                                        text = name,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp,
                                                        color = Color(0xFF7F1D1D)
                                                    )
                                                    Text(
                                                        text = phone,
                                                        fontSize = 12.sp,
                                                        color = Color(0xFFB91C1C)
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = "등록된 연락처가 없습니다.",
                                                    fontSize = 13.sp,
                                                    color = Color(0xFF64748B)
                                                )
                                            }
                                        }

                                        TextButton(
                                            onClick = {
                                                editingContactIndex = index
                                                contactNameInput = name
                                                contactPhoneInput = phone
                                            }
                                        ) {
                                            Text(
                                                text = if (phone.isNotBlank()) "수정" else "등록",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (phone.isNotBlank()) Color(0xFF475569) else Color(0xFFDC2626)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3.8. 안심 귀가 벨소리 복원 설정 (지정 반경 진입 시 무음 해제)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x99FFFFFF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Geofence Icon",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "3.8. 실시간 안심 귀가 무음 해제 🏠",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "집 반경 50미터 진입 시 볼륨을 최대로 자동 변경합니다",
                                        fontSize = 12.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }

                            Switch(
                                checked = isHomeAutoRingerEnabled,
                                onCheckedChange = { checked ->
                                    if (checked && !hasLocationPermission) {
                                        Toast.makeText(context, "귀가 감지를 사용하려면 먼저 상단의 'GPS 위치 권한'을 허용해주세요!", Toast.LENGTH_LONG).show()
                                        return@Switch
                                    }
                                    coroutineScope.launch {
                                        if (homeLatitude == 0.0 || homeLongitude == 0.0) {
                                            Toast.makeText(context, "먼저 '우리집 위치' 정보를 등록해주세요!", Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                        repository.saveHomeAutoRingerEnabled(checked)
                                        Toast.makeText(
                                            context,
                                            if (checked) "안심 귀가 자동 볼륨 해제 서비스가 시작되었습니다." else "서비스가 중지되었습니다.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFF59E0B)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Home registration details
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFEF3C7).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Home GPS Icon",
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "등록된 우리집 위치:",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF78350F)
                                    )
                                    if (homeLatitude != 0.0 && homeLongitude != 0.0) {
                                        Text(
                                            text = "$homeAddress\n(위도: ${String.format(Locale.US, "%.5f", homeLatitude)}, 경도: ${String.format(Locale.US, "%.5f", homeLongitude)})",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFB45309)
                                        )
                                    } else {
                                        Text(
                                            text = "미지정 (아래 버튼으로 현재 위치를 등록해 주세요)",
                                            fontSize = 13.sp,
                                            color = Color(0xFFB45309)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Fast registration button
                        Button(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                                    val providers = locationManager.getProviders(true)
                                    var foundLocation: Location? = null
                                    for (provider in providers) {
                                        val loc = locationManager.getLastKnownLocation(provider) ?: continue
                                        if (foundLocation == null || loc.accuracy < foundLocation.accuracy) {
                                            foundLocation = loc
                                        }
                                    }
                                    if (foundLocation != null) {
                                        coroutineScope.launch {
                                            repository.saveHomeLocation(foundLocation.latitude, foundLocation.longitude, "안심 지정 우리집")
                                            Toast.makeText(context, "현재 GPS 좌표 (${String.format(Locale.US, "%.5f", foundLocation.latitude)}, ${String.format(Locale.US, "%.5f", foundLocation.longitude)})가 안전하게 우리집으로 등록되었습니다!", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "기기의 GPS 수신을 대기하고 있습니다. 잠시 후 다시 조절해주시거나 하단에서 수동 지정해주세요.", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    Toast.makeText(context, "원활한 작동을 위해 먼저 위치 정보(GPS) 권한을 승인해 주세요.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.LocationOn, contentDescription = "Get GPS Location")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("현재 위치를 우리집으로 등록하기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Virtual entry simulation test button
                        Button(
                            onClick = {
                                try {
                                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    
                                    // Switch ringer to Normal mode
                                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                                    
                                    // Maximize ringer volume
                                    val maxRingVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                                    audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRingVol, AudioManager.FLAG_SHOW_UI)
                                    
                                    // Trigger brief vibration feedback to let the user know they clicked it and it succeeded
                                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vibrator.vibrate(200)
                                    }

                                    Toast.makeText(
                                        context,
                                        "🔔 [가상 귀가 성공] 집 반경 50m 이내에 가상 도달하여 무음/진동 모드가 해제되고 벨소리가 최대로 정상 복원되었습니다!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: SecurityException) {
                                    Toast.makeText(
                                        context,
                                        "⚠️ [가상 귀가 실패] '방해 금지 모드 권한'이 없어서 시스템 음량 모드를 제어할 수 없습니다. 아래의 권한 설정 버튼을 눌러 권한을 승인해 주세요.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "오류 발생: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)), // Emerald Green
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Simulate Entry")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("가상 귀가 진입 테스트 실행", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Toggle/Expand manual coord editor
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { isEditingHome = !isEditingHome }) {
                                Text(
                                    text = if (isEditingHome) "상세 설정 접기 ▲" else "수동 좌표 직접 지정 ▼",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF78350F)
                                )
                            }
                        }

                        if (isEditingHome) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = homeAddressInput,
                                    onValueChange = { homeAddressInput = it },
                                    label = { Text("위치 대표 명칭 (예: 우리집, 할머니 댁)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFF59E0B),
                                        focusedLabelColor = Color(0xFFF59E0B)
                                    )
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = homeLatInput,
                                        onValueChange = { homeLatInput = it },
                                        label = { Text("위도 (Latitude)") },
                                        placeholder = { Text("37.5665") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFF59E0B),
                                            focusedLabelColor = Color(0xFFF59E0B)
                                        )
                                    )
                                    OutlinedTextField(
                                        value = homeLngInput,
                                        onValueChange = { homeLngInput = it },
                                        label = { Text("경도 (Longitude)") },
                                        placeholder = { Text("126.9780") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFF59E0B),
                                            focusedLabelColor = Color(0xFFF59E0B)
                                        )
                                    )
                                }

                                Button(
                                    onClick = {
                                        val latVal = homeLatInput.toDoubleOrNull()
                                        val lngVal = homeLngInput.toDoubleOrNull()
                                        if (latVal == null || lngVal == null) {
                                            Toast.makeText(context, "올바른 숫자형 위도/경도를 소수로 입력해주세요 (예: 37.56, 126.97)", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        coroutineScope.launch {
                                            repository.saveHomeLocation(latVal, lngVal, homeAddressInput.trim())
                                            isEditingHome = false
                                            Toast.makeText(context, "수동 좌표가 정상 입력되어 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("수동 지정 완료", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }

                        // Special DND helper if API level constraints apply
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val dndAccessNotGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.isNotificationPolicyAccessGranted == false
                        } else {
                            false
                        }

                        if (dndAccessNotGranted) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFED7AA), RoundedCornerShape(12.dp))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "⚠️ 중요 안내: 기기가 '무음 모드'일 경우, 앱이 무음을 강제로 해제하려면 '방해 금지 모드 허용 권한'이 필수적으로 필요합니다.",
                                        fontSize = 11.sp,
                                        color = Color(0xFFC2410C),
                                        lineHeight = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                try {
                                                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "권한 설정 창을 열지 못했습니다. 직접 기기 설정에서 '방해 금지 제어 허용'을 검색해 활성화 해주세요.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("권한 설정 바로 가기", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Elderly Simulation Controls Area (Highly intuitive for Emulators)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)), // Neutral soft warm amber-50
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Simulator Action Icon",
                                    tint = Color(0xFFD97606),
                                    modifier = Modifier.size(24.dp)
                                )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "기능 확인용 가상 시뮬레이터",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF78350F)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "에뮬레이터 환경에서 실제 60분 통화가 힘든 경우, 아래 시뮬레이터 기능을 이용하여 전체 동선(모니터링 알림, 화면 진동, 버튼 발송 및 미응답 타임아웃)을 편리하게 테스트할 수 있습니다.",
                            fontSize = 13.sp,
                            color = Color(0xFFB45309),
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Simulator Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Immediate Alert Trigger
                            Button(
                                onClick = {
                                    if (guardianPhone.isEmpty() && emergencyContacts.isEmpty()) {
                                        Toast.makeText(context, "보호자 정보가 등록되지 않아도 가상 시뮬레이터 알림창을 즉시 실행합니다.", Toast.LENGTH_LONG).show()
                                    } else if (guardianPhone.isEmpty()) {
                                        Toast.makeText(context, "긴급 연락처 등록 상태에서 가상 시뮬레이터 알림창을 즉시 실행합니다.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "즉시 테스트 알림창 진동 및 화면 감지를 실행합니다.", Toast.LENGTH_SHORT).show()
                                    }
                                    // Direct explicit launch ensures immediate verification on any device, bypassing background launch limits!
                                    val intent = Intent(context, AlertActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        putExtra("extra_test_mode", true)
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97606)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    "🚨 즉시 알림창\n      (진동+테스트)",
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 16.sp
                                )
                            }

                            // Dynamic call monitoring simulator
                            Button(
                                onClick = {
                                    if (simulatedCallActive) {
                                        simulatedCallActive = false
                                        PhoneStateReceiver.cancelSafetyAlarm(context)
                                        Toast.makeText(context, "가상 통화 감지가 중지되었습니다.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        if (guardianPhone.isEmpty() && emergencyContacts.isEmpty()) {
                                             Toast.makeText(context, "보호자 정보 등록 없이 가상 통화를 가동합니다. 15초 뒤 알림창이 뜹니다.", Toast.LENGTH_LONG).show()
                                        } else {
                                             Toast.makeText(context, "가상 통화가 시작되었습니다. 15초 뒤 감지 창이 자동 호출됩니다.", Toast.LENGTH_LONG).show()
                                        }
                                        simulatedCallActive = true
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (simulatedCallActive) Color(0xFF475569) else Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = if (simulatedCallActive) "📞 통화 정지" else "📞 통화 시작\n(15초 후 감지)",
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 16.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Showing elapsed simulation duration gracefully
                        AnimatedVisibility(
                            visible = simulatedCallActive,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFFD97606))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "가상 통화 상태 감지 중: 지속 $simulatedCallElapsedSeconds / 15초",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD97606)
                                )
                            }
                        }


                    }
                }
            }

            // 5. Call Logs (Stored History in Room)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "상태 및 대응 이력",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF0F172A)
                    )

                    if (callLogs.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    repository.clearLogs()
                                    Toast.makeText(context, "기록이 모두 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("모두 지우기", color = Color(0xFFDC2626), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (callLogs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x99FFFFFF), RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp))
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Action Icon",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "수행된 조치 이력이 없습니다.",
                                fontSize = 14.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }
            } else {
                items(callLogs) { log ->
                    LogItem(log = log)
                }
            }
        }
    }
}

@Composable
fun LogItem(log: CallLogEntity) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREAN) }
    val timeLabel = formatter.format(Date(log.timestamp))

    val (statusLabel, statusColor, desc) = when (log.status) {
        "CALL_STARTED" -> Triple("통화 개시", Color(0xFF2563EB), "어르신의 가상 통화가 개시되었습니다.")
        "CALL_ENDED" -> Triple("통화 정상 종료", Color(0xFF0D9488), "어르신의 통화가 무사히 종료되었습니다.")
        "SMS_SAFE_SENT" -> Triple("안전 확인 완료", Color(0xFF059669), "어르신이 '안전함'을 직접 버튼으로 회신하여 보호자에게 안심 문자가 발송되었습니다.")
        "SMS_TIMEOUT_SENT" -> Triple("미응답 문자 경고", Color(0xFFDC2626), "어르신의 5분간 미응답으로 인해 긴급 안전 확인 경보 문자가 자동 전송되었습니다.")
        "DISMISSED_NO_PHONE", "TIMEOUT_NO_PHONE" -> Triple("전송 보류", Color(0xFF64748B), "보호자 연락처 미등록으로 인해 대응 조치 로그가 실시간 보류되었습니다.")
        else -> Triple(log.status, Color(0xFF334155), "기록된 이벤트")
    }

    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x99FFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = statusColor
                )
                Text(
                    text = timeLabel,
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = desc,
                fontSize = 13.sp,
                color = Color(0xFF475569),
                lineHeight = 18.sp
            )
        }
    }
}
