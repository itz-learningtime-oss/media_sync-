package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DetectedMedia
import com.example.data.model.TransferProtocol
import com.example.ui.MainViewModel
import com.example.ui.components.MediaDetailModal
import com.example.ui.screens.CompanionScriptDialog
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.MediaSyncTheme
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var hasStoragePermissionState by mutableStateOf(false)
    private var hasNotificationPermissionState by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        checkPermissions()
        if (hasStoragePermissionState && hasNotificationPermissionState) {
            // Auto start service if not already running
            if (!viewModel.isServiceRunning.value) {
                viewModel.startService(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
        handleIntent(intent)

        setContent {
            MediaSyncTheme(darkTheme = true) {
                MainAppScreen(
                    viewModel = viewModel,
                    hasStoragePermission = hasStoragePermissionState,
                    hasNotificationPermission = hasNotificationPermissionState,
                    onRequestPermissions = { requestAppPermissions() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val mediaId = intent.getLongExtra("EXTRA_MEDIA_ID", -1L)
        val mediaUri = intent.getStringExtra("EXTRA_MEDIA_URI")
        val mediaName = intent.getStringExtra("EXTRA_MEDIA_NAME")

        if (mediaId != -1L && mediaUri != null && mediaName != null) {
            val media = DetectedMedia(
                mediaStoreId = mediaId,
                contentUriString = mediaUri,
                displayName = mediaName,
                filePath = null,
                sizeBytes = 0L,
                mimeType = "image/*",
                dateAddedSeconds = System.currentTimeMillis() / 1000,
                isScreenshot = mediaName.contains("screenshot", ignoreCase = true),
                bucketName = null
            )
            viewModel.selectMediaDetail(media)
        }
    }

    private fun checkPermissions() {
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        hasStoragePermissionState = storageGranted
        hasNotificationPermissionState = notifGranted
    }

    fun requestAppPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    hasStoragePermission: Boolean,
    hasNotificationPermission: Boolean,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val eventCount by viewModel.eventCount.collectAsStateWithLifecycle()
    val serviceStartTime by viewModel.serviceStartTime.collectAsStateWithLifecycle()
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val detectedList by viewModel.detectedMediaList.collectAsStateWithLifecycle()
    val transferLogs by viewModel.transferLogs.collectAsStateWithLifecycle()
    val isTestingConnection by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val selectedMedia by viewModel.selectedMediaForDetail.collectAsStateWithLifecycle()
    val showCompanionScript by viewModel.showCompanionScriptDialog.collectAsStateWithLifecycle()
    val httpSuccessCount by viewModel.httpSuccessCount.collectAsStateWithLifecycle()
    val ftpSuccessCount by viewModel.ftpSuccessCount.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(if (isServiceRunning) CyanGlow else Slate400, androidx.compose.foundation.shape.CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "MediaSync Bridge",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.setShowCompanionScript(true) },
                        modifier = Modifier.testTag("top_bar_script_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "PC Script",
                            tint = CyanGlow
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("bottom_nav_bar")
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Home"
                        )
                    },
                    label = { Text("Listener") },
                    colors = navigationItemColors()
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (transferLogs.isNotEmpty()) {
                                    Badge(
                                        containerColor = CyanPrimary,
                                        contentColor = Color(0xFF0B1120)
                                    ) {
                                        Text("${transferLogs.size}")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "History"
                            )
                        }
                    },
                    label = { Text("Transfers") },
                    colors = navigationItemColors()
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("PC Setup") },
                    colors = navigationItemColors()
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    isServiceRunning = isServiceRunning,
                    eventCount = eventCount,
                    serviceStartTime = serviceStartTime,
                    serverConfig = serverConfig,
                    detectedMediaList = detectedList,
                    hasStoragePermission = hasStoragePermission,
                    hasNotificationPermission = hasNotificationPermission,
                    onRequestPermissions = onRequestPermissions,
                    onToggleService = { enable ->
                        if (enable) {
                            if (!hasStoragePermission || !hasNotificationPermission) {
                                onRequestPermissions()
                            } else {
                                viewModel.startService(context)
                            }
                        } else {
                            viewModel.stopService(context)
                        }
                    },
                    onTriggerScan = {
                        if (!hasStoragePermission) {
                            onRequestPermissions()
                        } else {
                            viewModel.triggerScan(context)
                        }
                    },
                    onSimulateScreenshot = {
                        viewModel.simulateScreenshotEvent()
                    },
                    onSendHttp = { media ->
                        viewModel.transferMedia(media, TransferProtocol.HTTP)
                    },
                    onSendFtp = { media ->
                        viewModel.transferMedia(media, TransferProtocol.FTP)
                    },
                    onSelectMedia = { media ->
                        viewModel.selectMediaDetail(media)
                    },
                    onClearDetected = {
                        viewModel.clearDetected()
                    }
                )

                1 -> HistoryScreen(
                    transferLogs = transferLogs,
                    httpCount = httpSuccessCount,
                    ftpCount = ftpSuccessCount,
                    onClearHistory = { viewModel.clearHistory() }
                )

                2 -> SettingsScreen(
                    currentConfig = serverConfig,
                    isTestingConnection = isTestingConnection,
                    connectionStatus = connectionStatus,
                    onSaveConfig = { config -> viewModel.updateConfig(config) },
                    onTestConnection = { viewModel.testConnection() },
                    onShowCompanionScript = { viewModel.setShowCompanionScript(true) }
                )
            }
        }
    }

    // Modal for media details & direct action
    if (selectedMedia != null) {
        MediaDetailModal(
            media = selectedMedia!!,
            onDismiss = { viewModel.selectMediaDetail(null) },
            onSendHttp = { viewModel.transferMedia(selectedMedia!!, TransferProtocol.HTTP) },
            onSendFtp = { viewModel.transferMedia(selectedMedia!!, TransferProtocol.FTP) }
        )
    }

    // Modal for PC Companion Python Script
    if (showCompanionScript) {
        CompanionScriptDialog(
            onDismiss = { viewModel.setShowCompanionScript(false) }
        )
    }
}

@Composable
private fun navigationItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = CyanGlow,
    selectedTextColor = CyanGlow,
    indicatorColor = Slate800,
    unselectedIconColor = Slate400,
    unselectedTextColor = Slate400
)
