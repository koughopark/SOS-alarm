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
import android.net.Uri
import android.os.PowerManager
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
    val homeRadius by repository.homeRadiusFlow.collectAsState(initial = 200)
    val isHomeAutoRingerEnabled by repository.isHomeAutoRingerEnabledFlow.collectAsState(initial = false)

    val officeLatitude by repository.officeLatitudeFlow.collectAsState(initial = 0.0)
    val officeLongitude by repository.officeLongitudeFlow.collectAsState(initial = 0.0)
    val officeAddress by repository.officeAddressFlow.collectAsState(initial = "")
    val officeRadius by repository.officeRadiusFlow.collectAsState(initial = 200)
    val isOfficeAutoVibrateEnabled by repository.isOfficeAutoVibrateEnabledFlow.collectAsState(initial = false)

    val callLimitMinutes by repository.callLimitMinutesFlow.collectAsState(initial = 60)
    val safeHomeBrightnessPercent by repository.safeHomeBrightnessPercentFlow.collectAsState(initial = 100)

    // Form inputs state
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var isEditingGuardian by remember { mutableStateOf(false) }

    var homeLatInput by remember { mutableStateOf("") }
    var homeLngInput by remember { mutableStateOf("") }
    var homeAddressInput by remember { mutableStateOf("") }
    var homeRadiusInput by remember { mutableStateOf("200") }
    var isEditingHome by remember { mutableStateOf(false) }

    var officeLatInput by remember { mutableStateOf("") }
    var officeLngInput by remember { mutableStateOf("") }
    var officeAddressInput by remember { mutableStateOf("") }
    var officeRadiusInput by remember { mutableStateOf("200") }
    var isEditingOffice by remember { mutableStateOf(false) }

    var showMapPicker by remember { mutableStateOf(false) }
    var mapTarget by remember { mutableStateOf("home") } // "home" or "office"

    var callLimitInput by remember { mutableStateOf("60") }
    var brightnessInput by remember { mutableStateOf("100") }

    // Test result dialog states
    var showTestResultDialog by remember { mutableStateOf(false) }
    var testResultTitle by remember { mutableStateOf("") }
    var testResultMessage by remember { mutableStateOf("") }

    LaunchedEffect(safeHomeBrightnessPercent) {
        brightnessInput = safeHomeBrightnessPercent.toString()
    }

    LaunchedEffect(homeRadius) {
        homeRadiusInput = homeRadius.toString()
    }

    LaunchedEffect(officeRadius) {
        officeRadiusInput = officeRadius.toString()
    }

    // Check for overlap between Home and Office geofence regions
    val locationDistanceBetween: Float? = remember(homeLatitude, homeLongitude, officeLatitude, officeLongitude) {
        if (homeLatitude != 0.0 && homeLongitude != 0.0 && officeLatitude != 0.0 && officeLongitude != 0.0) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(homeLatitude, homeLongitude, officeLatitude, officeLongitude, results)
            results[0]
        } else null
    }

    val isOverlapDetected: Boolean = remember(locationDistanceBetween, homeRadius, officeRadius) {
        if (locationDistanceBetween != null) {
            locationDistanceBetween < (homeRadius + officeRadius)
        } else false
    }

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

    LaunchedEffect(officeLatitude, officeLongitude, officeAddress) {
        if (!isEditingOffice) {
            officeLatInput = if (officeLatitude != 0.0) officeLatitude.toString() else ""
            officeLngInput = if (officeLongitude != 0.0) officeLongitude.toString() else ""
            officeAddressInput = if (officeAddress.isNotEmpty()) officeAddress else "회사"
        }
    }

    LaunchedEffect(callLimitMinutes) {
        callLimitInput = callLimitMinutes.toString()
    }

    // Start/Stop location monitoring service automatically
    LaunchedEffect(isHomeAutoRingerEnabled, isOfficeAutoVibrateEnabled) {
        val serviceIntent = Intent(context, LocationVolumeService::class.java)
        if (isHomeAutoRingerEnabled || isOfficeAutoVibrateEnabled) {
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
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasWriteSettingsPermission by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

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

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }

        hasWriteSettingsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.System.canWrite(context)
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
        WatchdogWorker.enqueuePeriodic(context)
    }

    // Refresh permissions automatically when user returns from settings screen
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                updatePermissions()
                WatchdogWorker.enqueuePeriodic(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
    val activeContacts = emergencyContacts.filter { it.phone.isNotBlank() }
    var editingContactIndex by remember { mutableStateOf(-1) }
    var contactNameInput by remember { mutableStateOf("") }
    var contactPhoneInput by remember { mutableStateOf("") }
    var isSosPressing by remember { mutableStateOf(false) }
    var sosProgress by remember { mutableStateOf(0f) }

    val triggerSOS = {
        coroutineScope.launch {
            val locationStr = getSOSSendingLocation()
            val message = "[긴급 구조 요청] 도움이 필요합니다. 현재 위치: $locationStr"
            
            val uniquePhones = (emergencyContacts.map { it.phone } + guardianPhone)
                .filter { it.isNotBlank() }
                .distinct()
                
            var dispatchedTargetCount = 0

            uniquePhones.forEach { Phone ->
                sendSmsDirect(Phone, message)
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

    // Sync automatic editor for empty state "기본 1칸"
    LaunchedEffect(emergencyContacts) {
        val activeContacts = emergencyContacts.filter { it.phone.isNotBlank() }
        if (activeContacts.isEmpty() && editingContactIndex == -1) {
            editingContactIndex = 0
            contactNameInput = ""
            contactPhoneInput = ""
        }
    }

    Scaffold(
        topBar = {
            Column {
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
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFFFFF8F1),
                    contentColor = Color(0xFFDC2626)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("안심 보호 🚨", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("위치 & 권한 ⚙️", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("동작 테스트 🧪", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFFFFDFB)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==========================================
            // [1] 최상단: SOS 버튼 (원터치 비상 구조 시스템)
            // ==========================================
            if (selectedTab == 0) {
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
            }


            // ==========================================
            // [2] 두번째: 기능 실행 및 설정 (통화지키미, 비상보호자설정, 귀가무음해제)
            // ==========================================
            
            // A. 통화지키미 (작동 정보 및 한도 시간 설정)
            if (selectedTab == 0) {
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
                                text = "어르신이 지정하신 ${callLimitMinutes}분 이상 연속해서 전화를 끊지 않으실 경우 통화를 강제 모니터링하여, 화면에 안전 확인 선택창을 띄우고 진동을 울립니다. 일정 시간 확인 반응이 없을 시 등록된 비상 수신처 전체로 긴급 문자가 동시 전송됩니다.",
                                fontSize = 14.sp,
                                color = Color(0xFF475569),
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Start
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // 시간(분)을 수정할 수 있는 입력 UI
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = callLimitInput,
                                    onValueChange = { 
                                        if (it.all { char -> char.isDigit() }) {
                                            callLimitInput = it
                                        }
                                    },
                                    label = { Text("어르신 안심 감지 통화 시간 (분)") },
                                    placeholder = { Text("예: 60") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF059669),
                                        focusedLabelColor = Color(0xFF059669)
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        val mins = callLimitInput.toIntOrNull()
                                        if (mins == null || mins <= 0) {
                                            Toast.makeText(context, "올바른 시간(분)을 입력해 주세요 (1분 이상).", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        coroutineScope.launch {
                                            repository.saveCallLimitMinutes(mins)
                                            Toast.makeText(context, "안심 감지 제한 한도가 ${mins}분으로 저장되었습니다!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                    modifier = Modifier.height(56.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("확정", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // B. 비상보호자(수신처) 및 긴급 연락처 통합 설정 (최대 5명 동적 추가식)
            if (selectedTab == 0) {
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
                                            text = "비상보호자(수신처) 설정 (최대 5명)",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0F172A)
                                        )
                                        Text(
                                            text = "안심 긴급 구조 SOS 문자 수송을 위한 리스트",
                                            fontSize = 12.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Render list of active registered contacts
                                activeContacts.forEach { contact ->
                                    if (editingContactIndex == contact.index) {
                                        // Under edit
                                        ContactEditForm(
                                            index = contact.index,
                                            name = contactNameInput,
                                            phone = contactPhoneInput,
                                            onNameChange = { contactNameInput = it },
                                            onPhoneChange = { contactPhoneInput = it },
                                            onSave = {
                                                if (contactNameInput.isBlank() || contactPhoneInput.isBlank()) {
                                                    Toast.makeText(context, "성명과 연락처를 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
                                                    return@ContactEditForm
                                                }
                                                coroutineScope.launch {
                                                    repository.saveEmergencyContact(contact.index, contactNameInput.trim(), contactPhoneInput.trim())
                                                    if (contact.index == 0) {
                                                        repository.saveGuardianInfo(contactNameInput.trim(), contactPhoneInput.trim())
                                                    }
                                                    editingContactIndex = -1
                                                    Toast.makeText(context, "보호자 정보가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onCancel = { editingContactIndex = -1 }
                                        )
                                    } else {
                                        // Read-only Row view
                                        ContactRow(
                                            contact = contact,
                                            onEdit = {
                                                editingContactIndex = contact.index
                                                contactNameInput = contact.name
                                                contactPhoneInput = contact.phone
                                            },
                                            onDelete = {
                                                coroutineScope.launch {
                                                    repository.saveEmergencyContact(contact.index, "", "")
                                                    if (contact.index == 0) {
                                                        repository.saveGuardianInfo("", "")
                                                    }
                                                    Toast.makeText(context, "보호자 정보가 안전하게 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                }

                                // If we are currently adding a new contact slot that is not in the active entries
                                if (editingContactIndex != -1 && activeContacts.none { it.index == editingContactIndex }) {
                                    ContactEditForm(
                                        index = editingContactIndex,
                                        name = contactNameInput,
                                        phone = contactPhoneInput,
                                        onNameChange = { contactNameInput = it },
                                        onPhoneChange = { contactPhoneInput = it },
                                        onSave = {
                                            if (contactNameInput.isBlank() || contactPhoneInput.isBlank()) {
                                                Toast.makeText(context, "성명과 연락처를 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
                                                return@ContactEditForm
                                            }
                                            coroutineScope.launch {
                                                repository.saveEmergencyContact(editingContactIndex, contactNameInput.trim(), contactPhoneInput.trim())
                                                if (editingContactIndex == 0) {
                                                    repository.saveGuardianInfo(contactNameInput.trim(), contactPhoneInput.trim())
                                                }
                                                editingContactIndex = -1
                                                Toast.makeText(context, "보호자 정보가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onCancel = { editingContactIndex = -1 }
                                    )
                                }
                            }

                            // Add button (only show when editing session is inactive)
                            if (editingContactIndex == -1) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        if (activeContacts.size >= 5) {
                                            Toast.makeText(context, "보호자는 최대 5명까지만 등록할 수 있습니다.", Toast.LENGTH_LONG).show()
                                            return@Button
                                        }
                                        // Find first vacant index
                                        val vacantIndex = (0..4).firstOrNull { idx -> activeContacts.none { it.index == idx } }
                                        if (vacantIndex != null) {
                                            editingContactIndex = vacantIndex
                                            contactNameInput = ""
                                            contactPhoneInput = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Contact")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("비상보호자 연락처 추가 등록하기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            // C. 실시간 안심 귀가 무음 해제 지오펜스
            if (selectedTab == 1) {
                if (isOverlapDetected && locationDistanceBetween != null) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, Color(0xFFEF4444), RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Warning",
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "⚠️ 위치 반경 겹침(충돌) 주의",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color(0xFF991B1B)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "우리집과 진동구역 간 거리: 약 ${locationDistanceBetween.toInt()}m (두 반경의 합계: ${homeRadius + officeRadius}m)\n두 구역이 겹쳐 벨소리와 진동이 충돌할 수 있으니 반경(m)을 줄여주세요.",
                                        fontSize = 12.sp,
                                        color = Color(0xFFB91C1C),
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }

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
                                            text = "실시간 안심 귀가 무음 해제 🏠",
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
                                            Toast.makeText(context, "귀가 감지를 사용하려면 먼저 하단의 'GPS 위치 권한'을 허용해주세요!", Toast.LENGTH_LONG).show()
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

                            // Home registration details outline
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
                                            Toast.makeText(context, "기기의 GPS 수신을 대기하고 있습니다. 잠시 후 다시 시도해주시거나 하단에서 수동 지정해주세요.", Toast.LENGTH_LONG).show()
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

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    mapTarget = "home"
                                    showMapPicker = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF1F5F9),
                                    contentColor = Color(0xFF0F172A)
                                ),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search Address",
                                    tint = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "주소 검색으로 우리집 위치 지정하기 🔍",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF0F172A)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0x11000000))
                            Spacer(modifier = Modifier.height(14.dp))

                            // Home Radius Setting
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Radius Icon",
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "우리집 감지 반경(거리) 설정",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF0F172A)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "💡 아파트/주택 실내 GPS 오차를 감안하여 150m ~ 300m 설정을 권장합니다. (기본값: 200m)",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 15.sp
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = homeRadiusInput,
                                    onValueChange = { input ->
                                        if (input.all { it.isDigit() }) {
                                            homeRadiusInput = input
                                        }
                                    },
                                    label = { Text("우리집 인식 반경 (미터 m)") },
                                    placeholder = { Text("200") },
                                    suffix = { Text("m", color = Color(0xFF64748B), fontSize = 13.sp) },
                                    modifier = Modifier.weight(1.5f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFF59E0B),
                                        focusedLabelColor = Color(0xFFF59E0B)
                                    )
                                )

                                Button(
                                    onClick = {
                                        val radiusVal = homeRadiusInput.toIntOrNull()
                                        if (radiusVal == null || radiusVal < 50 || radiusVal > 3000) {
                                            Toast.makeText(context, "50m ~ 3000m 사이의 올바른 반경 거리를 입력해주세요.", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        coroutineScope.launch {
                                            repository.saveHomeRadius(radiusVal)
                                            if (homeLatitude != 0.0 && homeLongitude != 0.0 && officeLatitude != 0.0 && officeLongitude != 0.0) {
                                                val results = FloatArray(1)
                                                android.location.Location.distanceBetween(homeLatitude, homeLongitude, officeLatitude, officeLongitude, results)
                                                val dist = results[0].toInt()
                                                if (dist < (radiusVal + officeRadius)) {
                                                    Toast.makeText(context, "⚠️ 경고: 우리집과 진동구역 반경이 겹칩니다! (두 지점 거리: ${dist}m / 반경 합계: ${radiusVal + officeRadius}m). 벨소리/진동 충돌 방지를 위해 반경을 줄여주세요.", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "우리집 감지 반경이 ${radiusVal}m로 안전하게 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "우리집 감지 반경이 ${radiusVal}m로 안전하게 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                    modifier = Modifier.height(56.dp).weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("반경 저장", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = Color(0x11000000))
                            Spacer(modifier = Modifier.height(16.dp))

                            // Custom Brightness Section
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Brightness Icon",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "귀가 시 자동 전환할 화면 밝기 (%) 설정",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF0F172A)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "안심 귀가 완료 시, 화면이 너무 밝아 눈이 아프지 않도록 원하는 밝기 비율(1~100%)을 지정할 수 있습니다.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 15.sp
                            )
                            
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = brightnessInput,
                                    onValueChange = { input ->
                                        if (input.all { it.isDigit() }) {
                                            brightnessInput = input
                                        }
                                    },
                                    label = { Text("원하는 밝기 (%)") },
                                    placeholder = { Text("100") },
                                    modifier = Modifier.weight(1.5f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFF59E0B),
                                        focusedLabelColor = Color(0xFFF59E0B)
                                    )
                                )

                                Button(
                                    onClick = {
                                        val percent = brightnessInput.toIntOrNull()
                                        if (percent == null || percent !in 1..100) {
                                            Toast.makeText(context, "1부터 100 사이의 숫자를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        coroutineScope.launch {
                                            repository.saveSafeHomeBrightnessPercent(percent)
                                            Toast.makeText(context, "귀가 시 자동 화면 밝기가 ${percent}%로 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                    modifier = Modifier.height(56.dp).weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("설정 저장", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                Button(
                                    onClick = {
                                        val percent = brightnessInput.toIntOrNull()
                                        if (percent == null || percent !in 1..100) {
                                            Toast.makeText(context, "먼저 올바른 숫자를 입력하고 시도해주세요.", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        try {
                                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.System.canWrite(context)) {
                                                android.provider.Settings.System.putInt(
                                                    context.contentResolver,
                                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                                )
                                                val brightnessVal = ((percent * 255) / 100).coerceIn(1, 255)
                                                android.provider.Settings.System.putInt(
                                                    context.contentResolver,
                                                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                                    brightnessVal
                                                )
                                                Toast.makeText(context, "즉시 적용 성공: 화면 밝기를 ${percent}%(${brightnessVal})로 변경했습니다.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                                    data = android.net.Uri.parse("package:${context.packageName}")
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                                Toast.makeText(context, "화면 밝기를 바로 변경하려면 '화면 밝기 자동 조절 권한' 승인이 필요합니다.", Toast.LENGTH_LONG).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                                    modifier = Modifier.size(0.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text("설정 저장", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                             }

                             Spacer(modifier = Modifier.height(12.dp))

                            // Toggle/Expand manual coord editor label
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
                        }
                    }
                }
            }


            // D. 실시간 특정 위치 자동 진동 전환 지오펜스
            if (selectedTab == 1) {
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
                                        contentDescription = "Office Geofence Icon",
                                        tint = Color(0xFF8B5CF6),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "특정 위치 자동 진동 전환 📳",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0F172A)
                                        )
                                        Text(
                                            text = "특정지역(회사 등) 반경 50미터 진입 시 진동 모드로 자동 전환합니다",
                                            fontSize = 12.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }

                                Switch(
                                    checked = isOfficeAutoVibrateEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked && !hasLocationPermission) {
                                            Toast.makeText(context, "위치 진동 전환을 사용하려면 먼저 하단의 'GPS 위치 권한'을 허용해주세요!", Toast.LENGTH_LONG).show()
                                            return@Switch
                                        }
                                        coroutineScope.launch {
                                            if (officeLatitude == 0.0 || officeLongitude == 0.0) {
                                                Toast.makeText(context, "먼저 '특정 위치(회사 등)' 정보를 등록해주세요!", Toast.LENGTH_LONG).show()
                                                return@launch
                                            }
                                            repository.saveOfficeAutoVibrateEnabled(checked)
                                            Toast.makeText(
                                                context,
                                                if (checked) "특정 위치 자동 진동 전환 서비스가 시작되었습니다." else "진동 자동 전환 서비스가 중지되었습니다.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF8B5CF6)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Office registration details outline
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFEDE9FE).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                    .border(1.dp, Color(0xFFDDD6FE), RoundedCornerShape(16.dp))
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = "Office GPS Icon",
                                        tint = Color(0xFF7C3AED),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "등록된 안심 진동 위치:",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = Color(0xFF4C1D95)
                                        )
                                        if (officeLatitude != 0.0 && officeLongitude != 0.0) {
                                            Text(
                                                text = "$officeAddress\n(위도: ${String.format(Locale.US, "%.5f", officeLatitude)}, 경도: ${String.format(Locale.US, "%.5f", officeLongitude)})",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF6D28D9)
                                            )
                                        } else {
                                            Text(
                                                text = "미지정 (아래 버튼으로 현재 위치를 등록해 주세요)",
                                                fontSize = 13.sp,
                                                color = Color(0xFF6D28D9)
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
                                                repository.saveOfficeLocation(foundLocation.latitude, foundLocation.longitude, "안심 지정 회사")
                                                Toast.makeText(context, "현재 GPS 좌표 (${String.format(Locale.US, "%.5f", foundLocation.latitude)}, ${String.format(Locale.US, "%.5f", foundLocation.longitude)})가 안심 진동 위치로 정상 등록되었습니다!", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "기기의 GPS 수신을 대기하고 있습니다. 잠시 후 다시 시도해주시거나 하단에서 수동 지정해주세요.", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "원활한 작동을 위해 먼저 위치 정보(GPS) 권한을 승인해 주세요.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(imageVector = Icons.Default.LocationOn, contentDescription = "Get GPS Location")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("현재 위치를 진동 위치로 등록하기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    mapTarget = "office"
                                    showMapPicker = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF1F5F9),
                                    contentColor = Color(0xFF0F172A)
                                ),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search Address",
                                    tint = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "주소 검색으로 진동 위치 지정하기 🔍",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF0F172A)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0x11000000))
                            Spacer(modifier = Modifier.height(14.dp))

                            // Office Radius Setting
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Radius Icon",
                                    tint = Color(0xFF7C3AED),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "진동 구역 감지 반경(거리) 설정",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF0F172A)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "💡 사무실/건물 실내 GPS 오차를 감안하여 150m ~ 300m 설정을 권장합니다. (기본값: 200m)",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 15.sp
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = officeRadiusInput,
                                    onValueChange = { input ->
                                        if (input.all { it.isDigit() }) {
                                            officeRadiusInput = input
                                        }
                                    },
                                    label = { Text("진동 구역 인식 반경 (미터 m)") },
                                    placeholder = { Text("200") },
                                    suffix = { Text("m", color = Color(0xFF64748B), fontSize = 13.sp) },
                                    modifier = Modifier.weight(1.5f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF8B5CF6),
                                        focusedLabelColor = Color(0xFF8B5CF6)
                                    )
                                )

                                Button(
                                    onClick = {
                                        val radiusVal = officeRadiusInput.toIntOrNull()
                                        if (radiusVal == null || radiusVal < 50 || radiusVal > 3000) {
                                            Toast.makeText(context, "50m ~ 3000m 사이의 올바른 반경 거리를 입력해주세요.", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        coroutineScope.launch {
                                            repository.saveOfficeRadius(radiusVal)
                                            if (homeLatitude != 0.0 && homeLongitude != 0.0 && officeLatitude != 0.0 && officeLongitude != 0.0) {
                                                val results = FloatArray(1)
                                                android.location.Location.distanceBetween(homeLatitude, homeLongitude, officeLatitude, officeLongitude, results)
                                                val dist = results[0].toInt()
                                                if (dist < (radiusVal + homeRadius)) {
                                                    Toast.makeText(context, "⚠️ 경고: 진동구역과 우리집 반경이 겹칩니다! (두 지점 거리: ${dist}m / 반경 합계: ${radiusVal + homeRadius}m). 벨소리/진동 충돌 방지를 위해 반경을 줄여주세요.", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "진동 구역 감지 반경이 ${radiusVal}m로 안전하게 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "진동 구역 감지 반경이 ${radiusVal}m로 안전하게 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                    modifier = Modifier.height(56.dp).weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("반경 저장", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Toggle/Expand manual coord editor label
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { isEditingOffice = !isEditingOffice }) {
                                    Text(
                                        text = if (isEditingOffice) "상세 설정 접기 ▲" else "수동 좌표 직접 지정 ▼",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF6D28D9)
                                    )
                                }
                            }

                            if (isEditingOffice) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = officeAddressInput,
                                        onValueChange = { officeAddressInput = it },
                                        label = { Text("위치 대표 명칭 (예: 회사, 복지관, 도서실)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF8B5CF6),
                                            focusedLabelColor = Color(0xFF8B5CF6)
                                        )
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = officeLatInput,
                                            onValueChange = { officeLatInput = it },
                                            label = { Text("위도 (Latitude)") },
                                            placeholder = { Text("37.5665") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFF8B5CF6),
                                                focusedLabelColor = Color(0xFF8B5CF6)
                                            )
                                        )
                                        OutlinedTextField(
                                            value = officeLngInput,
                                            onValueChange = { officeLngInput = it },
                                            label = { Text("경도 (Longitude)") },
                                            placeholder = { Text("126.9780") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFF8B5CF6),
                                                focusedLabelColor = Color(0xFF8B5CF6)
                                            )
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val latVal = officeLatInput.toDoubleOrNull()
                                            val lngVal = officeLngInput.toDoubleOrNull()
                                            if (latVal == null || lngVal == null) {
                                                Toast.makeText(context, "올바른 숫자형 위도/경도를 소수로 입력해주세요 (예: 37.56, 126.97)", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            coroutineScope.launch {
                                                repository.saveOfficeLocation(latVal, lngVal, officeAddressInput.trim())
                                                isEditingOffice = false
                                                Toast.makeText(context, "수동 진동 좌표가 정상 입력되어 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("수동 지정 완료", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }


            // ==========================================
            // [3] 세번째: 권한 부여와 관련된 내용 및 배터리 설정
            // ==========================================
            if (selectedTab == 1) {
                item {
                    val hasAll = hasSmsPermission && hasPhoneStatePermission && hasLocationPermission && hasNotificationPermission && hasOverlayPermission && hasWriteSettingsPermission
                    val hasBasicAll = hasSmsPermission && hasPhoneStatePermission && hasLocationPermission && hasNotificationPermission && hasAnswerCallsPermission
                    val containerColor = if (hasAll) Color(0xFFE8F5E9) else Color(0xFFFEF2F2)
                    val borderColor = if (hasAll) Color(0xFFA5D6A7) else Color(0xFFFCA5A5)
                    val titleColor = if (hasAll) Color(0xFF0F5132) else Color(0xFF842029)
                    val statusText = if (hasAll) "정상 작동 준비 완료" else "작동을 위해 필수 시스템 권한 승인 필요"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, borderColor, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = containerColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            // 1. 헤더 영역
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (hasAll) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    tint = if (hasAll) Color(0xFF059669) else Color(0xFFDC2626),
                                    contentDescription = "Status icon",
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "필수 연결 및 권한 현황",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        color = titleColor
                                    )
                                    Text(
                                        text = statusText,
                                        fontSize = 12.sp,
                                        color = if (hasAll) Color(0xFF198754) else Color(0xFFDC3545)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0x11000000))
                            Spacer(modifier = Modifier.height(14.dp))

                            // 2. [그룹 1] 기본 시스템 권한 섹션
                            Text(
                                text = "📋 일반 시스템 권한 (기본 승인 대상)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF1E293B),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            val basicPermissions = listOf(
                                "1. 통화 상태 감지 권한" to hasPhoneStatePermission,
                                "2. 보호자 안심 문자 발송 권한" to hasSmsPermission,
                                "3. GPS 위치 정보 권한" to hasLocationPermission,
                                "4. 원격 종료 제어 권한" to hasAnswerCallsPermission,
                                "5. 알림 및 긴급 경보 권한" to hasNotificationPermission
                            )

                            basicPermissions.forEach { (name, isAllowed) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 13.sp,
                                        color = Color(0xFF475569)
                                    )
                                    Surface(
                                        color = if (isAllowed) Color(0xFFE2F0D9) else Color(0xFFFEF3C7), // 허용: 연녹색, 미허용: 연노란색(Amber 100)
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (isAllowed) "허용됨" else "미허용",
                                            color = if (isAllowed) Color(0xFF27AE60) else Color(0xFFD97706), // 허용: 녹색, 미허용: 어두운 노란색(Amber 600)
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = Color(0x11000000))
                            Spacer(modifier = Modifier.height(16.dp))

                            // 3. [그룹 2] 특수 시스템 권한 섹션 (UI 완전 통일)
                            Text(
                                text = "⚙️ 특수 시스템 권한 (개별 설정 필요)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF1E293B),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            val isBatteryOptimizationsIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                powerManager.isIgnoringBatteryOptimizations(context.packageName)
                             } else {
                                true
                             }

                            val dndAccessNotGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val alertNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                alertNotificationManager.isNotificationPolicyAccessGranted == false
                            } else {
                                false
                            }

                            // 6) 다른 앱 위에 표시 권한 (필수)
                            SpecialPermissionRow(
                                name = "6. 다른 앱 위에 표시 권한 (필수)",
                                isAllowed = hasOverlayPermission,
                                onSetupClick = {
                                    try {
                                        val intent = Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {
                                            Toast.makeText(context, "다른 앱 위에 표시 설정창을 열 수 없습니다. 직접 기기 설정에서 허용해 주세요.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            )

                            // 7) 화면 밝기 자동 조절 권한 (필수)
                            SpecialPermissionRow(
                                name = "7. 화면 밝기 자동 조절 권한 (필수)",
                                isAllowed = hasWriteSettingsPermission,
                                onSetupClick = {
                                    try {
                                        val intent = Intent(
                                            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {
                                            Toast.makeText(context, "화면 밝기 설정창을 열 수 없습니다. 직접 기기 설정에서 허용해 주세요.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            )

                            // 8) 배터리 최적화 제외 설정 (필수)
                            SpecialPermissionRow(
                                name = "8. 배터리 최적화 제외 설정 (필수)",
                                isAllowed = isBatteryOptimizationsIgnored,
                                onSetupClick = {
                                    var succeeded = false
                                    try {
                                        val intent = Intent().apply {
                                            action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                        succeeded = true
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Direct ignore prompt failed", e)
                                    }

                                    if (!succeeded) {
                                        try {
                                            val intent = Intent().apply {
                                                action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                data = Uri.fromParts("package", context.packageName, null)
                                            }
                                            context.startActivity(intent)
                                            Toast.makeText(
                                                context,
                                                "앱 상세 설정 화면이 열렸습니다.\n'배터리' -> '제한 없음(Unrestricted)'으로 전환해 주시면 오작동을 완전히 방지할 수 있습니다!",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            succeeded = true
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "App info screen failed", e)
                                        }
                                    }

                                    if (!succeeded) {
                                        try {
                                            val intent = Intent().apply {
                                                action = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                            }
                                            context.startActivity(intent)
                                            Toast.makeText(
                                                context,
                                                "배터리 제한 목록이 열렸습니다.\n나의 필터 항목에서 '전체'를 누르고 이 어플을 필터 제외로 체크해 주세요.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            succeeded = true
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "General optimize screen failed", e)
                                        }
                                    }

                                    if (!succeeded) {
                                        Toast.makeText(context, "배터리 설정을 편리하게 열 수 없었습니다. 기기 설정에서 배터리 최적화 제외를 수동 적용하세요.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            // 9) 무음 해제 권한 (선택)
                            SpecialPermissionRow(
                                name = "9. 무음 해제 권한 (선택)",
                                isAllowed = !dndAccessNotGranted,
                                onSetupClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "권한 설정 창을 열지 못했습니다. 직접 기기 설정에서 '방해 금지 제어 허용'을 검색해 활성화 해주세요.", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "이 기기 버전에서는 지원하지 않거나 설정할 필요가 없습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            // 10) 365일 상시 가동 / 절전 및 권한 회수 방지 설정 (필수 권장)
                            SpecialPermissionRow(
                                name = "10. 절전/권한회수 방지 (365일 상시가동)",
                                isAllowed = isBatteryOptimizationsIgnored,
                                onSetupClick = {
                                    var opened = false
                                    // 1. Try Unused App Restrictions / Auto Revoke Intent (Android 11+)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        try {
                                            val intent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                            Toast.makeText(
                                                context,
                                                "💡 '사용하지 않는 앱 권한 삭제'를 [해제/비활성화]하고, '배터리' 항목을 '제한 없음'으로 설정해 주세요.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            opened = true
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Auto revoke intent failed", e)
                                        }
                                    }

                                    // 2. Try Samsung Device Care (Background Usage Limits)
                                    if (!opened && Build.MANUFACTURER.contains("samsung", ignoreCase = true)) {
                                        try {
                                            val intent = Intent().apply {
                                                component = android.content.ComponentName(
                                                    "com.samsung.android.lool",
                                                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                                                )
                                            }
                                            context.startActivity(intent)
                                            Toast.makeText(
                                                context,
                                                "💡 삼성 디바이스 케어가 열렸습니다.\n'백그라운드 사용 제한' -> '절전 예외 앱'에 이 앱을 추가해 주세요!",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            opened = true
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Samsung device care intent failed", e)
                                        }
                                    }

                                    // 3. Fallback to App Details Settings
                                    if (!opened) {
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                            }
                                            context.startActivity(intent)
                                            Toast.makeText(
                                                context,
                                                "💡 앱 설정 화면입니다.\n1. '사용하지 않는 앱 권한 삭제' OFF\n2. '배터리' -> '제한 없음'으로 변경해 주세요.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "기기 설정 -> 애플리케이션 -> 이 앱에서 배터리 제한 없음을 설정해 주세요.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )

                            // 일반 권한이 모두 부여되지 않았다면, 전체 권한 목록 최하단에 일괄 허용 버튼 배치
                            if (!hasBasicAll) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFF59E0B), // 노란색(Amber) 계열로 변경하여 빨간색 제거
                                        contentColor = Color.White
                                    ),
                                    onClick = {
                                        // 1. Request overlay permission first if needed (Android 6.0+)
                                        if (!hasOverlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            try {
                                                val intent = Intent(
                                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                                context.startActivity(intent)
                                                Toast.makeText(context, "다른 앱 위에 표시 권한을 허용해 주세요!", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                                    context.startActivity(intent)
                                                } catch (ex: Exception) {}
                                            }
                                        }

                                        // 2. Request conventional runtime permissions
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
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        permLauncher.launch(permissions.toTypedArray())
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("기본 시스템 권한 일괄 허용하기 🔓", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }


            // ==========================================
            // [4] 네번째: 기능테스트 2종 (가상 시뮬레이터 및 귀가 모조 테스트)
            // ==========================================
            if (selectedTab == 2) {
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
                                    "안심 서비스 가상 테스트 센터 (2종)",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF78350F)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "앱의 긴급 모니터링 동작 및 귀가 무음 벨소리 복원을 가상 환경(에뮬레이터 등)에서 수월하게 진단 및 검증해볼 수 있는 시뮬레이션입니다.",
                                fontSize = 13.sp,
                                color = Color(0xFFB45309),
                                lineHeight = 18.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "테스트 1: 가상 안심 귀가 진입 테스트",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF78350F)
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // Test Box 1: Virtual entry simulation test button
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            val percent = repository.getSafeHomeBrightnessPercent()
                                            val brightnessVal = ((percent.coerceIn(1, 100) * 255) / 100).coerceIn(1, 255)

                                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                            
                                            // Switch ringer to Normal mode
                                            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                                            
                                            // Maximize ringer volume
                                            val maxRingVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                                            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRingVol, AudioManager.FLAG_SHOW_UI)
                                            
                                            // Set screen brightness
                                            val hasWritePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                android.provider.Settings.System.canWrite(context)
                                            } else {
                                                true
                                            }

                                            var brightnessApplied = false
                                            if (hasWritePerm) {
                                                android.provider.Settings.System.putInt(
                                                    context.contentResolver,
                                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                                )
                                                android.provider.Settings.System.putInt(
                                                    context.contentResolver,
                                                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                                    brightnessVal
                                                )
                                                brightnessApplied = true
                                            }

                                            // Trigger brief vibration feedback to let the user know they clicked it and it succeeded
                                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                            } else {
                                                @Suppress("DEPRECATION")
                                                vibrator.vibrate(200)
                                            }

                                            testResultTitle = "🔔 [가상 귀가 성공]"
                                            testResultMessage = if (brightnessApplied) {
                                                "집 반경 50m 이내에 가상 도달하여 다음 조치가 정상적으로 완료되었습니다:\n\n" +
                                                "🔊 휴대폰 무음/진동 모드가 해제되고 '벨소리 모드'로 변경되었습니다.\n" +
                                                "🔔 벨소리 볼륨이 최대로 설정되었습니다.\n" +
                                                "☀️ 화면 밝기가 설정하신 비율(${percent}%)로 자동 조정되었습니다."
                                            } else {
                                                "집 반경 50m 이내에 가상 도달하여 다음 조치가 완료되었습니다:\n\n" +
                                                "🔊 휴대폰 무음/진동 모드가 해제되고 '벨소리 모드'로 변경되었습니다.\n" +
                                                "🔔 벨소리 볼륨이 최대로 설정되었습니다.\n\n" +
                                                "⚠️ [화면 밝기 조절 실패]\n" +
                                                "'화면 밝기 자동 조절 권한'이 없어서 화면 밝기를 ${percent}%로 변경하지 못했습니다. " +
                                                "위치 & 권한 탭 하단에서 해당 권한을 허용해 주세요."
                                            }
                                            showTestResultDialog = true
                                        } catch (e: SecurityException) {
                                            testResultTitle = "⚠️ [가상 귀가 실패]"
                                            testResultMessage = "시스템 음량 모드를 제어할 수 없습니다.\n\n" +
                                                "'방해 금지 모드 권한'이 승인되지 않았습니다. " +
                                                "위치 & 권한 탭에서 권한 승인을 완료해 주세요."
                                            showTestResultDialog = true
                                        } catch (e: Exception) {
                                            testResultTitle = "오류 발생"
                                            testResultMessage = "동작 중 다음과 같은 에러가 발생했습니다:\n${e.message}"
                                            showTestResultDialog = true
                                        }
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

                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "테스트 2: 가상 통화감지 시뮬레이터",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF78350F)
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // Test Box 2: Simulator Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Immediate Alert Trigger
                                Button(
                                    onClick = {
                                        if (guardianPhone.isEmpty() && activeContacts.isEmpty()) {
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
                                            if (guardianPhone.isEmpty() && activeContacts.isEmpty()) {
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
            }


            // ==========================================
            // [5] 또 다른 하단: 상태 및 대응 이력 (Logs)
            // ==========================================
            if (selectedTab == 0) {
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

        if (showMapPicker) {
            val initialLat = if (mapTarget == "home") homeLatitude else officeLatitude
            val initialLng = if (mapTarget == "home") homeLongitude else officeLongitude
            val targetName = if (mapTarget == "home") "우리집" else "진동 위치"
            AddressSearchDialog(
                targetTitle = targetName,
                initialLatitude = initialLat,
                initialLongitude = initialLng,
                onDismissRequest = { showMapPicker = false },
                onConfirm = { lat, lng, address ->
                    coroutineScope.launch {
                        if (mapTarget == "home") {
                            val addr = address.ifBlank { "안심 지정 우리집" }
                            repository.saveHomeLocation(lat, lng, addr)
                            homeLatInput = lat.toString()
                            homeLngInput = lng.toString()
                            homeAddressInput = addr
                            Toast.makeText(context, "우리집 위치가 성공적으로 자동 지정되었습니다!", Toast.LENGTH_SHORT).show()
                        } else {
                            val addr = address.ifBlank { "안심 지정 진동구역" }
                            repository.saveOfficeLocation(lat, lng, addr)
                            officeLatInput = lat.toString()
                            officeLngInput = lng.toString()
                            officeAddressInput = addr
                            Toast.makeText(context, "진동 위치가 성공적으로 자동 지정되었습니다!", Toast.LENGTH_SHORT).show()
                        }
                        showMapPicker = false
                    }
                }
            )
        }

        if (showTestResultDialog) {
            AlertDialog(
                onDismissRequest = { showTestResultDialog = false },
                title = { Text(testResultTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF0F172A)) },
                text = { Text(testResultMessage, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF334155)) },
                confirmButton = {
                    Button(
                        onClick = { showTestResultDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Text("확인", color = Color.White)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = Color.White
            )
        }
    }
}

data class AddressCandidate(
    val title: String,
    val fullAddress: String,
    val latitude: Double,
    val longitude: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressSearchDialog(
    targetTitle: String,
    initialLatitude: Double,
    initialLongitude: Double,
    onDismissRequest: () -> Unit,
    onConfirm: (Double, Double, String) -> Unit
) {
    val context = LocalContext.current
    var queryText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<AddressCandidate>>(emptyList()) }
    var selectedCandidate by remember { mutableStateOf<AddressCandidate?>(null) }
    var hasSearched by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, "검색할 주소 또는 장소명을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        isSearching = true
        hasSearched = true
        errorMessage = null
        selectedCandidate = null

        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val candidates = mutableListOf<AddressCandidate>()

            // 1. Android Geocoder
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(context, Locale.KOREAN)
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocationName(trimmed, 5)
                    if (!list.isNullOrEmpty()) {
                        for (addr in list) {
                            val line = addr.getAddressLine(0) ?: trimmed
                            val clean = line.replace("^대한민국\\s*".toRegex(), "").trim()
                            val feature = addr.featureName ?: clean
                            candidates.add(
                                AddressCandidate(
                                    title = if (feature.isNotEmpty() && feature != clean) feature else clean,
                                    fullAddress = clean,
                                    latitude = addr.latitude,
                                    longitude = addr.longitude
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AddressSearch", "Geocoder search error: ${e.message}")
            }

            // 2. Fallback OpenStreetMap Nominatim
            if (candidates.isEmpty()) {
                try {
                    val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
                    val url = java.net.URL("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=5&accept-language=ko")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        setRequestProperty("User-Agent", "SafeCallAlertApp/1.0 (Android; Korean Address Search)")
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(response)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val lat = obj.getDouble("lat")
                        val lon = obj.getDouble("lon")
                        val disp = obj.optString("display_name", trimmed)
                        val clean = disp.replace("^대한민국\\s*,?\\s*".toRegex(), "")
                            .split(",")
                            .reversed()
                            .joinToString(" ") { it.trim() }
                        val name = obj.optString("name", clean.split(" ").lastOrNull() ?: clean)
                        candidates.add(
                            AddressCandidate(
                                title = name.ifBlank { clean },
                                fullAddress = clean,
                                latitude = lat,
                                longitude = lon
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("AddressSearch", "Nominatim search error: ${e.message}")
                }
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                isSearching = false
                searchResults = candidates
                if (candidates.isNotEmpty()) {
                    selectedCandidate = candidates.first()
                } else {
                    errorMessage = "'$trimmed' 에 대한 검색 결과가 없습니다.\n도로명 주소나 주요 건물명(예: 상록구 예술대학로, 서울역)으로 다시 검색해보세요."
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$targetTitle 주소 검색 🔍",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = "한국어 주소/장소를 입력하면 위도·경도가 자동 지정됩니다.",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = { Text("주소 또는 건물명 입력 (예: 예술대학로 6, 서울역)", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF2563EB),
                        unfocusedBorderColor = Color(0xFFCBD5E1),
                        focusedContainerColor = Color(0xFFF8FAFC),
                        unfocusedContainerColor = Color(0xFFF8FAFC)
                    ),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF2563EB))
                    },
                    trailingIcon = {
                        if (queryText.isNotEmpty()) {
                            IconButton(onClick = { queryText = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color(0xFF94A3B8))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { performSearch(queryText) }
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action Search Button
                Button(
                    onClick = { performSearch(queryText) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSearching
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("주소 검색 중...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("주소 및 좌표 검색하기", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Results or guidance container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
                        .padding(8.dp)
                ) {
                    if (isSearching) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF2563EB))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("주소와 위도/경도 좌표를 조회하는 중입니다...", fontSize = 13.sp, color = Color(0xFF64748B))
                        }
                    } else if (errorMessage != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                fontSize = 13.sp,
                                color = Color(0xFF475569),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    } else if (!hasSearched) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "원하시는 한글 주소나 건물명을 검색해주세요.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "예: 예술대학로6길 2, 상록구 월피동, 서울역",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (searchResults.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("검색된 주소 정보가 없습니다.", fontSize = 13.sp, color = Color(0xFF64748B))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults) { item ->
                                val isSelected = selectedCandidate == item
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedCandidate = item },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0xFFEFF6FF) else Color.White
                                    ),
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF2563EB) else Color(0xFFE2E8F0)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = if (isSelected) Color(0xFF2563EB) else Color(0xFF94A3B8),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.fullAddress,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color(0xFF0F172A)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "위도: ${String.format(Locale.US, "%.5f", item.latitude)}, 경도: ${String.format(Locale.US, "%.5f", item.longitude)}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom confirmation area
                if (selectedCandidate != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "선택된 위치 및 좌표",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF475569)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectedCandidate?.fullAddress ?: "",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                text = "위도: ${String.format(Locale.US, "%.5f", selectedCandidate?.latitude ?: 0.0)}, 경도: ${String.format(Locale.US, "%.5f", selectedCandidate?.longitude ?: 0.0)}",
                                fontSize = 11.sp,
                                color = Color(0xFF2563EB),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("취소", color = Color(0xFF475569))
                    }
                    Button(
                        onClick = {
                            selectedCandidate?.let {
                                onConfirm(it.latitude, it.longitude, it.fullAddress)
                            }
                        },
                        modifier = Modifier.weight(1.6f),
                        enabled = selectedCandidate != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("이 주소로 자동 지정 완료", fontWeight = FontWeight.Bold)
                    }
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

@Composable
fun ContactRow(
    contact: EmergencyContact,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFEF2F2), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFFEE2E2), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFFFCA5A5), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${contact.index + 1}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = contact.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF7F1D1D)
                )
                Text(
                    text = contact.phone,
                    fontSize = 12.sp,
                    color = Color(0xFFB91C1C)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) {
                Text("수정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF475569))
            }
            TextButton(onClick = onDelete) {
                Text("삭제", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFDC2626))
            }
        }
    }
}

@Composable
fun ContactEditForm(
    index: Int,
    name: String,
    phone: String,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFEF2F2), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(
            text = if (index == 0) "비상 보호자 (대표) 등록/수정" else "${index + 1}번 긴급 연락처 등록/수정",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFF991B1B)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("이름 또는 관계 (예: 큰딸, 사회복지사, 이웃집)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFDC2626),
                focusedLabelColor = Color(0xFFDC2626)
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
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
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("저장", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("취소", color = Color(0xFF475569))
            }
        }
    }
}

@Composable
fun SpecialPermissionRow(
    name: String,
    isAllowed: Boolean,
    onSetupClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 13.sp,
            color = Color(0xFF475569),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = if (isAllowed) Color(0xFFE2F0D9) else Color(0xFFFEF3C7), // 허용: 연녹색, 미허용(설정): 연노란색(Amber 100)
            shape = RoundedCornerShape(6.dp),
            modifier = if (!isAllowed) {
                Modifier.clickable { onSetupClick() }
            } else {
                Modifier
            }
        ) {
            Text(
                text = if (isAllowed) "허용됨" else "설정",
                color = if (isAllowed) Color(0xFF27AE60) else Color(0xFFD97706), // 허용: 녹색, 미허용: 어두운 노란색(Amber 600)
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
