package com.example

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.material.icons.filled.*
import android.annotation.SuppressLint

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: TouchpadViewModel

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothHidService.LocalBinder
            val hidService = binder.getService()
            viewModel.attachService(hidService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            viewModel = viewModel()
            MyApplicationTheme {
                MainScreen(viewModel, serviceConnection)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            // Ignore if not bound
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: TouchpadViewModel, serviceConnection: ServiceConnection) {
    val context = LocalContext.current
    val isServiceConnected by viewModel.isServiceConnected.collectAsState()

    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = bluetoothPermissions)

    if (permissionsState.allPermissionsGranted) {
        LaunchedEffect(Unit) {
            val serviceIntent = Intent(context, BluetoothHidService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        if (isServiceConnected) {
            TouchpadApp(viewModel)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    } else {
        PermissionScreen(permissionsState)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionScreen(permissionsState: MultiplePermissionsState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Bluetooth Permissions Required",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This app needs Bluetooth permissions to act as an HID touchpad.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { permissionsState.launchMultiplePermissionRequest() },
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Grant Permissions")
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchpadApp(viewModel: TouchpadViewModel) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()

    var showDevicePicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Status Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.clickable { showDevicePicker = true }) {
                    Text(
                        text = "MIDAS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Virtual Touchpad",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                ConnectionStatusPill(connectionState, connectedDevice, onClick = { showDevicePicker = true })
            }

            // Main Touchpad Surface Container
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    TouchpadSurface(viewModel)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick Settings Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = { /* TODO */ },
                            modifier = Modifier
                                .size(40.dp)
                                .background(com.example.ui.theme.ButtonBg36343B, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Sensitivity: High",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
                    }
                }
            }
        }
    }

    // Auto-close picker once the host connects
    LaunchedEffect(connectionState) {
        if (connectionState == BluetoothProfile.STATE_CONNECTED) showDevicePicker = false
    }

    if (showDevicePicker) {
        ModalBottomSheet(
            onDismissRequest = {
                if (connectionState == BluetoothProfile.STATE_CONNECTING) viewModel.disconnect()
                showDevicePicker = false
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 16.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outline, CircleShape)
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Active Connection Info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Computer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                "Active Device",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                connectedDevice?.name ?: "None",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Text("Disconnect", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }

                // Device List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Make Discoverable button
                    val isConnecting = connectionState == BluetoothProfile.STATE_CONNECTING
                    Button(
                        onClick = { viewModel.startAdvertising() },
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp).padding(end = 0.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Advertising — pair from your laptop's Bluetooth settings",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        } else {
                            Icon(Icons.Default.Bluetooth, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Make Discoverable",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Previously Paired",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { context.startActivity(intent) } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Open BT Settings", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    if (pairedDevices.isEmpty()) {
                        Text(
                            "No previously paired devices.\nTap \"Make Discoverable\", then add this device from your laptop's Bluetooth settings.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(pairedDevices) { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.background)
                                        .clickable {
                                            viewModel.connectDevice(device)
                                            showDevicePicker = false
                                        }
                                        .padding(horizontal = 20.dp, vertical = 18.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            device.name ?: "Unknown Device",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (device.address == connectedDevice?.address && connectionState == BluetoothProfile.STATE_CONNECTED) {
                                        Text(
                                            "CONNECTED",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (-0.5).sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            "PAIRED",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (-0.5).sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectionStatusPill(state: Int, device: BluetoothDevice?, onClick: () -> Unit = {}) {
    val (backgroundColor, text, isConnected) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            "Connected",
            true
        )
        BluetoothProfile.STATE_CONNECTING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            "Connecting...",
            false
        )
        else -> Triple(
            com.example.ui.theme.Border49454F,
            "Disconnected",
            false
        )
    }

    val animatedColor by animateColorAsState(targetValue = backgroundColor, label = "Color")

    Surface(
        color = animatedColor,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (isConnected) {
                // Pulse dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(com.example.ui.theme.Pulse4F378B, CircleShape)
                        .padding(end = 8.dp) // Wait, we can use spacer for spacing
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(12.dp)
                        .padding(end = 6.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TouchpadSurface(viewModel: TouchpadViewModel) {
    val haptic = LocalHapticFeedback.current
    var touchPoint by remember { mutableStateOf<Offset?>(null) }

    val sensitivity = 1.5f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, com.example.ui.theme.Border49454F, RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendMouseReport(leftBtn = true, rightBtn = false, 0, 0, 0)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                        touchPoint = it
                    },
                    onDoubleTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = true, 0, 0, 0)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                        touchPoint = it
                    }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointers = event.changes
                        if (pointers.isNotEmpty()) {
                            val activePointers = pointers.filter { it.pressed }
                            if (activePointers.isNotEmpty()) {
                                touchPoint = activePointers.first().position

                                if (activePointers.size == 1) {
                                    val first = activePointers.first()
                                    if (first.positionChange() != Offset.Zero) {
                                        val dx = (first.positionChange().x * sensitivity).toInt()
                                        val dy = (first.positionChange().y * sensitivity).toInt()
                                        viewModel.sendMouseReport(false, false, dx, dy, 0)
                                    }
                                } else if (activePointers.size == 2) {
                                    val avgChangeY = (activePointers[0].positionChange().y + activePointers[1].positionChange().y) / 2
                                    val scrollAmount = (avgChangeY * -0.5f).toInt()
                                    if (scrollAmount != 0) {
                                        viewModel.sendMouseReport(false, false, 0, 0, scrollAmount)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            } else {
                                touchPoint = null
                            }
                        } else {
                            touchPoint = null
                        }
                    }
                }
            }
    ) {
        // Glow Trail Effect (dynamic)
        touchPoint?.let { offset ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFD0BCFF).copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = offset,
                        radius = 300f
                    ),
                    radius = 300f,
                    center = offset
                )
            }
        }

        // Center Interaction Symbol
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(0.3f), // opacity-30
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp) // w-12 h-12
                    .border(2.dp, com.example.ui.theme.TextSecondaryCAC4D0, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(6.dp).background(com.example.ui.theme.TextSecondaryCAC4D0, CircleShape))
            }
            Text(
                text = "GLIDE TO MOVE",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Light, letterSpacing = 2.sp),
                color = com.example.ui.theme.TextSecondaryCAC4D0,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        // Interactive Scroll Bar Simulation
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp, top = 48.dp, bottom = 48.dp)
                .width(6.dp) // w-1.5
                .fillMaxHeight()
                .background(com.example.ui.theme.Border49454F.copy(alpha = 0.5f), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.33f)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
            )
        }

        // Bottom Interaction Regions
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp) // h-16
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = com.example.ui.theme.Border49454F.copy(alpha = 0.3f),
                        shape = RectangleShape
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendMouseReport(leftBtn = true, rightBtn = false, 0, 0, 0)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "LEFT CLICK",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = com.example.ui.theme.TextSecondaryCAC4D0
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = com.example.ui.theme.Border49454F.copy(alpha = 0.3f),
                        shape = RectangleShape
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = true, 0, 0, 0)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "RIGHT CLICK",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = com.example.ui.theme.TextSecondaryCAC4D0
                )
            }
        }
    }
}


