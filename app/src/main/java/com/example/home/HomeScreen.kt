package com.example.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.config.AppConfig
import com.example.config.RideMarkerType
import com.example.ride.BoschLiveDataState
import com.example.ride.MusicService
import com.example.ride.RideService
import com.example.statemachine.RideState
import com.example.wled.WledTransportState

enum class ScreenWledStatus {
    UNKNOWN,
    CHECKING,
    REACHABLE,
    UNREACHABLE
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@Composable
fun HomeScreen(
    config: AppConfig? = null,
    onTabRequest: (Int) -> Unit = {},
    onNavigateToTuning: () -> Unit = {},
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val vmConfig by vm.config.collectAsStateWithLifecycle()
    val isRunning by vm.isRunning.collectAsStateWithLifecycle()
    val activeState by vm.activeState.collectAsStateWithLifecycle()
    val isMusicRunning by vm.isMusicRunning.collectAsStateWithLifecycle()
    val musicBands by vm.musicBands.collectAsStateWithLifecycle()
    val markerCount by vm.markerCount.collectAsStateWithLifecycle()
    val lastMarkerLabel by vm.lastMarkerLabel.collectAsStateWithLifecycle()
    val boschState by vm.boschState.collectAsStateWithLifecycle()
    val wledTransportState by vm.wledTransportState.collectAsStateWithLifecycle()
    val wledStatus by vm.wledStatus.collectAsStateWithLifecycle()

    val effectiveConfig = config ?: vmConfig

    LaunchedEffect(effectiveConfig.wledHost) {
        vm.checkWledReachability(effectiveConfig.wledHost)
    }

    LaunchedEffect(isRunning, isMusicRunning) {
        if (isRunning || isMusicRunning) {
            vm.setWledStatusReachable()
        } else {
            vm.checkWledReachability(effectiveConfig.wledHost)
        }
    }

    HomeContent(
        config = effectiveConfig,
        isRunning = isRunning,
        activeState = activeState,
        isMusicRunning = isMusicRunning,
        musicBands = musicBands,
        markerCount = markerCount,
        lastMarkerLabel = lastMarkerLabel,
        boschState = boschState,
        wledTransportState = wledTransportState,
        wledStatus = wledStatus,
        onCheckWled = { vm.checkWledReachability(effectiveConfig.wledHost) },
        onStampMarker = vm::stampMarker,
        onToggleLogOnlyMode = vm::toggleLogOnlyMode,
        onTabRequest = onTabRequest,
        onNavigateToTuning = onNavigateToTuning
    )
}

@Composable
fun HomeContent(
    config: AppConfig,
    isRunning: Boolean = false,
    activeState: RideState = RideState.IDLE,
    isMusicRunning: Boolean = false,
    musicBands: FloatArray = FloatArray(16),
    markerCount: Int = 0,
    lastMarkerLabel: String? = null,
    boschState: BoschLiveDataState = BoschLiveDataState(),
    wledTransportState: WledTransportState = WledTransportState(),
    wledStatus: ScreenWledStatus = ScreenWledStatus.UNKNOWN,
    onCheckWled: () -> Unit = {},
    onStampMarker: (RideMarkerType) -> Unit = {},
    onToggleLogOnlyMode: (Boolean) -> Unit = {},
    onTabRequest: (Int) -> Unit = {},
    onNavigateToTuning: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedMode by remember { mutableStateOf("STANDARD") }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var bluetoothGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    var microphoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning, isMusicRunning) {
        if (isRunning) {
            selectedMode = "STANDARD"
        } else if (isMusicRunning) {
            selectedMode = "MUSIC"
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val isFineGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                locationGranted = isFineGranted

                bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        locationGranted = fineGranted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT]
                ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        }

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

    val activeLook = remember(activeState, config) {
        when (activeState) {
            RideState.IDLE -> config.idleLook
            RideState.CRUISING -> config.cruisingLook
            RideState.BRAKING -> config.brakingLook
            RideState.BRAKING_HOLD -> config.brakingHoldLook
        }
    }

    val primaryColorHex = activeLook.colors.getOrNull(0) ?: "#FF4500"
    val secondaryColorHex = activeLook.colors.getOrNull(1) ?: primaryColorHex
    val uiColorPrimary = remember(primaryColorHex) { try { Color(android.graphics.Color.parseColor(primaryColorHex)) } catch (e: Exception) { Color.Red } }
    val uiColorSecondary = remember(secondaryColorHex) { try { Color(android.graphics.Color.parseColor(secondaryColorHex)) } catch (e: Exception) { Color.Magenta } }

    val isModeStandard = selectedMode == "STANDARD"
    val isReady = if (isModeStandard) {
        locationGranted && (config.logOnlyMode || wledStatus == ScreenWledStatus.REACHABLE)
    } else {
        microphoneGranted && wledStatus == ScreenWledStatus.REACHABLE
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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

            // 1. Header & App Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_logo_area"),
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

            // 2. Mode Selector, Start/Stop Button, and Live Marker Bar
            HomeActionControls(
                selectedMode = selectedMode,
                isRunning = isRunning,
                isMusicRunning = isMusicRunning,
                isReady = isReady,
                logOnlyMode = config.logOnlyMode,
                markerCount = markerCount,
                lastMarkerLabel = lastMarkerLabel,
                onSelectMode = { selectedMode = it },
                onStampMarker = { markerType ->
                    onStampMarker(markerType)
                    Toast.makeText(context, "Marker stamped: ${markerType.wireValue}", Toast.LENGTH_SHORT).show()
                },
                onToggleService = {
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
                }
            )

            // 3. Readiness Handshake & Contextual Actions
            HomeReadinessCard(
                config = config,
                selectedMode = selectedMode,
                locationGranted = locationGranted,
                microphoneGranted = microphoneGranted,
                permissionPermanentlyDenied = permissionPermanentlyDenied,
                wledStatus = wledStatus,
                wledTransportState = wledTransportState,
                boschState = boschState,
                bluetoothGranted = bluetoothGranted,
                context = context,
                onGrantLocation = {
                    val req = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        req.add(Manifest.permission.BLUETOOTH_CONNECT)
                        req.add(Manifest.permission.BLUETOOTH_SCAN)
                        req.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        req.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(req.toTypedArray())
                },
                onGrantMic = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onCheckWled = onCheckWled
            )

            // 4. Glowing Bike Art or Audio Equalizer
            HomeVisualizerCard(
                selectedMode = selectedMode,
                isRunning = isRunning,
                isMusicRunning = isMusicRunning,
                activeState = activeState,
                musicBands = musicBands,
                uiColorPrimary = uiColorPrimary,
                uiColorSecondary = uiColorSecondary
            )

            // 5. Dedicated Log Only Mode (No WLED) Toggle at Bottom of Home Screen
            if (isModeStandard) {
                HomeLogOnlyModeCard(
                    logOnlyMode = config.logOnlyMode,
                    isAnyServiceRunning = isRunning || isMusicRunning,
                    onToggleLogOnlyMode = onToggleLogOnlyMode
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
