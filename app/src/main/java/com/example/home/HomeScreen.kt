package com.example.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.PrettyNightsApp
import com.example.config.AppConfig
import com.example.ride.RideService
import com.example.ride.MusicService
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import com.example.statemachine.RideState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScreenWledStatus {
    UNKNOWN,
    CHECKING,
    REACHABLE,
    UNREACHABLE
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@Composable
fun HomeScreen(
    config: AppConfig,
    onTabRequest: (Int) -> Unit,
    onNavigateToTuning: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Service/Ride Running States
    val isRunning by RideService.isServiceRunning.collectAsStateWithLifecycle()
    val activeState by RideService.activeRideState.collectAsStateWithLifecycle()

    val isMusicRunning by MusicService.isServiceRunning.collectAsStateWithLifecycle()
    val musicBands by MusicService.fftBands.collectAsStateWithLifecycle()
    var selectedMode by remember { mutableStateOf("STANDARD") }
    var microphoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(isRunning, isMusicRunning) {
        if (isRunning) {
            selectedMode = "STANDARD"
        } else if (isMusicRunning) {
            selectedMode = "MUSIC"
        }
    }

    // 2. Readiness State Machine backing this Screen (Spec Section 3)
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionPermanentlyDenied by remember { mutableStateOf(false) }
    var wledStatus by remember { mutableStateOf(ScreenWledStatus.UNKNOWN) }

    // Helper function to ping WLED
    fun checkWledReachability() {
        if (isRunning) {
            wledStatus = ScreenWledStatus.REACHABLE
            return
        }
        coroutineScope.launch {
            wledStatus = ScreenWledStatus.CHECKING
            val reachable = withContext(Dispatchers.IO) {
                PrettyNightsApp.instance.wledController.ping(config.wledHost)
            }
            wledStatus = if (reachable) ScreenWledStatus.REACHABLE else ScreenWledStatus.UNREACHABLE
        }
    }

    // Checking permission state & updating permanently denied state dynamically on ON_RESUME
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val isFineGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                locationGranted = isFineGranted

                val isMicGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                microphoneGranted = isMicGranted

                if (!isFineGranted) {
                    val sharedPrefs = context.getSharedPreferences("pretty_nights_prefs", Context.MODE_PRIVATE)
                    val hasRequestedBefore = sharedPrefs.getBoolean("has_requested_location", false)
                    if (hasRequestedBefore) {
                        val activity = context.findActivity()
                        val showRationale = activity?.let {
                            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
                        } ?: false
                        permissionPermanentlyDenied = !showRationale
                    }
                } else {
                    permissionPermanentlyDenied = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handle check on first-load or host change
    LaunchedEffect(config.wledHost) {
        checkWledReachability()
    }

    // Keep WLED state consistent when service starts/stops
    LaunchedEffect(isRunning, isMusicRunning) {
        if (isRunning || isMusicRunning) {
            wledStatus = ScreenWledStatus.REACHABLE
        } else {
            checkWledReachability()
        }
    }

    // Permission Request Launcher (Section 5.3)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        locationGranted = fineGranted

        val sharedPrefs = context.getSharedPreferences("pretty_nights_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("has_requested_location", true).apply()

        if (!fineGranted) {
            val activity = context.findActivity()
            val showRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
            } ?: false
            permissionPermanentlyDenied = !showRationale
        } else {
            permissionPermanentlyDenied = false
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphoneGranted = granted
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, MusicService::class.java).apply {
                putExtra(MusicService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MusicService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            Toast.makeText(context, "MediaProjection consent denied. Music Mode cannot start.", Toast.LENGTH_LONG).show()
        }
    }

    // LED glow pulsation animation for visual placeholder/art (purely aesthetic)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val activeLook = remember(activeState, config) {
        when (activeState) {
            RideState.IDLE -> config.idleLook
            RideState.CRUISING -> config.cruisingLook
            RideState.BRAKING, RideState.BRAKING_HOLD -> config.brakingLook
        }
    }

    val primaryColorHex = activeLook.colors.getOrNull(0) ?: "#FF4500"
    val secondaryColorHex = activeLook.colors.getOrNull(1) ?: primaryColorHex
    val uiColorPrimary = remember(primaryColorHex) { try { Color(android.graphics.Color.parseColor(primaryColorHex)) } catch (e: Exception) { Color.Red } }
    val uiColorSecondary = remember(secondaryColorHex) { try { Color(android.graphics.Color.parseColor(secondaryColorHex)) } catch (e: Exception) { Color.Magenta } }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // --- 1. Header & App Title ("Pretty Nights") ---
            Row(
                modifier = Modifier.fillMaxWidth().testTag("app_logo_area"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsBike,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "PRETTY NIGHTS",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // --- Mode Selection Toggle ---
            val isAnyServiceRunning = isRunning || isMusicRunning
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(4.dp)
                    .testTag("mode_selector_row"),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("STANDARD", "MUSIC").forEach { mode ->
                    val isSelected = selectedMode == mode
                    val isLockedMode = isAnyServiceRunning && !isSelected
                    val targetColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(targetColor)
                            .clickable(enabled = !isAnyServiceRunning) {
                                selectedMode = mode
                            }
                            .padding(vertical = 10.dp)
                            .testTag("mode_toggle_$mode"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (mode == "STANDARD") "STANDARD MODE" else "MUSIC MODE",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = if (isLockedMode) 0.38f else 1.0f)
                        )
                    }
                }
            }

            // --- 2. Pass/Fail Readiness Indicators ---
            Card(
                modifier = Modifier.fillMaxWidth().testTag("readiness_indicators_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Ride Readiness Handshake",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Phone sensors / Microphone indicator
                    if (selectedMode == "STANDARD") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Phone sensors (location)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = if (locationGranted) "Ready to measure speed" else "Access required for ride analytics",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }

                            // Status Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (locationGranted) Color(0xFF1B5E20) else Color(0xFFC62828))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (locationGranted) "PASS" else "FAIL",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("mic_readiness_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Microphone input", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = if (microphoneGranted) "Ready to capture internal audio" else "Access required for music reactions",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }

                            // Status Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (microphoneGranted) Color(0xFF1B5E20) else Color(0xFFC62828))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (microphoneGranted) "PASS" else "FAIL",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                    // WLED controller indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("WLED controller", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = when (wledStatus) {
                                        ScreenWledStatus.CHECKING -> "Checking ${config.wledHost}..."
                                        ScreenWledStatus.REACHABLE -> "Connected to ${config.wledHost}"
                                        else -> "Offline or unavailable at ${config.wledHost}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        // Status Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when (wledStatus) {
                                        ScreenWledStatus.REACHABLE -> Color(0xFF1B5E20)
                                        ScreenWledStatus.CHECKING -> Color(0xFFE65100)
                                        else -> Color(0xFFC62828)
                                    }
                                )
                               .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = when (wledStatus) {
                                    ScreenWledStatus.REACHABLE -> "PASS"
                                    ScreenWledStatus.CHECKING -> "PING"
                                    else -> "FAIL"
                                },
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            val isModeStandard = selectedMode == "STANDARD"
            val currentPermissionGranted = if (isModeStandard) locationGranted else microphoneGranted

            // --- 3. Contextual Action Area (Sections 4-5) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("contextual_actions_panel"),
                contentAlignment = Alignment.Center
            ) {
                if (!currentPermissionGranted) {
                    if (isModeStandard) {
                        // Location NOT GRANTED Case
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (permissionPermanentlyDenied) {
                                // Blocked / Settings Redirect Case
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Permission Blocked",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Location is required and has been blocked. Enable it in Settings to ride.",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.testTag("blocked_message")
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("btn_open_settings"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open Settings", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                // Rationale & standard Grant location flow
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Pretty Nights uses your location to measure ride speed, which drives the light effects.",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.testTag("rationale_text")
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        val req = mutableListOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            req.add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        permissionLauncher.launch(req.toTypedArray())
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("btn_grant_location"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.LockOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Grant location", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Microphone NOT GRANTED Case (Music Mode)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Pretty Nights needs record audio permission to capture internal audio, allowing your lights to pulse and react in real-time.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.testTag("rationale_text_mic")
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("btn_grant_mic"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant microphone permission", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (wledStatus != ScreenWledStatus.REACHABLE) {
                    // Permission IS GRANTED, but WLED unreachable
                    Button(
                        onClick = { checkWledReachability() },
                        enabled = wledStatus != ScreenWledStatus.CHECKING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_recheck_wled"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        if (wledStatus == ScreenWledStatus.CHECKING) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pinging Controller...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Re-check WLED", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Both GRANTED and REACHABLE -> Clear message
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isModeStandard) "Systems operating normally. Ready to ride!" else "Systems operating normally. Ready to stream!",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }
            }

            val waveOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 2f * Math.PI.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "waveOffset"
            )

            if (selectedMode == "STANDARD") {
                // --- 4. Glowing Bike Art visual (Purely decorative, no live speed) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    // Background grid pattern
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val strokeColor = Color.DarkGray.copy(alpha = 0.15f)
                                val step = 35.dp.toPx()
                                var x = 0f
                                while (x < size.width) {
                                    drawLine(strokeColor, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1f)
                                    x += step
                                }
                                var y = 0f
                                while (y < size.height) {
                                    drawLine(strokeColor, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1f)
                                    y += step
                                }
                            }
                    )

                    Canvas(modifier = Modifier.size(240.dp, 140.dp)) {
                        // Draw decorative bike wheels and frame
                        drawCircle(
                            color = Color.DarkGray.copy(alpha = 0.35f),
                            radius = 28.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(40.dp.toPx(), 95.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawCircle(
                            color = Color.DarkGray.copy(alpha = 0.35f),
                            radius = 28.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(200.dp.toPx(), 95.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // Frame bars
                        drawLine(Color.Gray.copy(alpha = 0.35f), start = androidx.compose.ui.geometry.Offset(40.dp.toPx(), 95.dp.toPx()), end = androidx.compose.ui.geometry.Offset(100.dp.toPx(), 95.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                        drawLine(Color.Gray.copy(alpha = 0.35f), start = androidx.compose.ui.geometry.Offset(100.dp.toPx(), 95.dp.toPx()), end = androidx.compose.ui.geometry.Offset(150.dp.toPx(), 40.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                        drawLine(Color.Gray.copy(alpha = 0.35f), start = androidx.compose.ui.geometry.Offset(150.dp.toPx(), 40.dp.toPx()), end = androidx.compose.ui.geometry.Offset(200.dp.toPx(), 95.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                        drawLine(Color.Gray.copy(alpha = 0.35f), start = androidx.compose.ui.geometry.Offset(40.dp.toPx(), 95.dp.toPx()), end = androidx.compose.ui.geometry.Offset(90.dp.toPx(), 50.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                        drawLine(Color.Gray.copy(alpha = 0.35f), start = androidx.compose.ui.geometry.Offset(90.dp.toPx(), 50.dp.toPx()), end = androidx.compose.ui.geometry.Offset(150.dp.toPx(), 40.dp.toPx()), strokeWidth = 2.5.dp.toPx())

                        // Draw glowing neon light string if running
                        val glowBrush = Brush.linearGradient(
                            colors = listOf(uiColorPrimary, uiColorSecondary)
                        )

                        if (isRunning) {
                            drawPath(
                                path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(48.dp.toPx(), 90.dp.toPx())
                                    lineTo(90.dp.toPx(), 50.dp.toPx())
                                    lineTo(142.dp.toPx(), 42.dp.toPx())
                                    lineTo(192.dp.toPx(), 90.dp.toPx())
                                },
                                brush = glowBrush,
                                style = Stroke(width = 9.dp.toPx() * pulseAlpha, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                                alpha = 0.85f
                            )

                            drawPath(
                                path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(48.dp.toPx(), 90.dp.toPx())
                                    lineTo(90.dp.toPx(), 50.dp.toPx())
                                    lineTo(142.dp.toPx(), 42.dp.toPx())
                                    lineTo(192.dp.toPx(), 90.dp.toPx())
                                },
                                color = Color.White,
                                style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                        }
                    }

                    // Central Status Text
                    Text(
                        text = if (isRunning) activeState.name else "STANDBY",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.8.sp,
                        color = if (isRunning) {
                            when (activeState) {
                                RideState.BRAKING, RideState.BRAKING_HOLD -> Color.Red
                                RideState.CRUISING -> Color.Cyan
                                RideState.IDLE -> Color(0xFFC084FC)
                            }
                        } else Color.LightGray,
                        style = TextStyle(
                            shadow = Shadow(
                                color = uiColorPrimary.copy(alpha = 0.75f),
                                blurRadius = 12f
                            )
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )
                }
            } else {
                // Equalizer Visualizer!
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // Equalizer Bars
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        for (i in 0 until 16) {
                            val amplitude = if (isMusicRunning) {
                                musicBands.getOrElse(i) { 0f }
                            } else {
                                0.15f + 0.15f * kotlin.math.sin(waveOffset + i * 0.5f)
                            }
                            
                            val animatedAmplitude by animateFloatAsState(
                                targetValue = amplitude,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                label = "bar_$i"
                            )
                            
                            val barColor = remember(i) {
                                val ratio = i / 15f
                                when {
                                    ratio < 0.4f -> Color(0xFF00E5FF) // Cyan
                                    ratio < 0.75f -> Color(0xFFD500F9) // Purple / Magenta
                                    else -> Color(0xFFFF3D00) // Orange / Red
                                }
                            }
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(fraction = animatedAmplitude.coerceIn(0.02f, 1.0f))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(barColor)
                                    .testTag("eq_bar_$i")
                            )
                        }
                    }
                    
                    Text(
                        text = if (isMusicRunning) "STREAMING" else "STANDBY",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.8.sp,
                        color = if (isMusicRunning) Color(0xFFE040FB) else Color.Gray,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // --- 5. Main Start / Stop Button ---
            val isReady = if (isModeStandard) {
                locationGranted && wledStatus == ScreenWledStatus.REACHABLE
            } else {
                microphoneGranted && wledStatus == ScreenWledStatus.REACHABLE
            }
            val buttonEnabled = isRunning || isMusicRunning || isReady

            Button(
                onClick = {
                    if (isModeStandard) {
                        if (isMusicRunning) {
                            Toast.makeText(context, "Error: Music Mode is currently running. Stop it first.", Toast.LENGTH_LONG).show()
                        } else {
                            val serviceIntent = Intent(context, RideService::class.java)
                            if (isRunning) {
                                context.stopService(serviceIntent)
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    context.startService(serviceIntent)
                                }
                            }
                        }
                    } else {
                        if (isRunning) {
                            Toast.makeText(context, "Error: Ride tracking is currently running. Stop it first.", Toast.LENGTH_LONG).show()
                        } else {
                            if (isMusicRunning) {
                                val serviceIntent = Intent(context, MusicService::class.java).apply {
                                    action = MusicService.ACTION_STOP_MUSIC
                                }
                                context.startService(serviceIntent)
                            } else {
                                val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                            }
                        }
                    }
                },
                enabled = buttonEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("btn_ride_toggle"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning || isMusicRunning) Color(0xFFC62828) else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.25f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isRunning || isMusicRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning || isMusicRunning) {
                            if (isRunning) "STOP RIDE" else "STOP MUSIC"
                        } else {
                            if (isModeStandard) "START RIDE" else "START MUSIC"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // --- 6. Secondary Navigation / Setup Shortcuts (Section 2.5) ---
            Card(
                modifier = Modifier.fillMaxWidth().testTag("setup_shortcuts_panel"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Setup & Calibration",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Shortcut to Patterns
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTabRequest(1) }
                                .testTag("shortcut_patterns"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Patterns", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Shortcut to Calibration settings
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTabRequest(3) }
                                .testTag("shortcut_settings"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Calibrate", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Shortcut to Developer / Tuning Dashboard
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToTuning() }
                                .testTag("shortcut_tuning"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Dev Tuning", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
