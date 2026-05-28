package com.example

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlin.math.abs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import android.annotation.SuppressLint
import androidx.compose.ui.graphics.vector.ImageVector

private const val GYRO_SCALE = 12f
private const val GYRO_DEAD_ZONE = 0.05f

// Maps a character to (modifier, HID keycode). Returns (0, 0) for unmapped chars.
private fun charToHidKeycode(char: Char): Pair<Int, Int> = when (char) {
    in 'a'..'z' -> 0 to (char.code - 'a'.code + 0x04)
    in 'A'..'Z' -> 0x02 to (char.code - 'A'.code + 0x04)
    in '1'..'9' -> 0 to (char.code - '1'.code + 0x1E)
    '0'  -> 0 to 0x27;  ' '  -> 0 to 0x2C;  '\n' -> 0 to 0x28;  '\t' -> 0 to 0x2B
    '!'  -> 0x02 to 0x1E; '@' -> 0x02 to 0x1F; '#' -> 0x02 to 0x20; '$' -> 0x02 to 0x21
    '%'  -> 0x02 to 0x22; '^' -> 0x02 to 0x23; '&' -> 0x02 to 0x24; '*' -> 0x02 to 0x25
    '('  -> 0x02 to 0x26; ')' -> 0x02 to 0x27
    '-'  -> 0 to 0x2D;   '_' -> 0x02 to 0x2D; '=' -> 0 to 0x2E;   '+' -> 0x02 to 0x2E
    '['  -> 0 to 0x2F;   '{' -> 0x02 to 0x2F; ']' -> 0 to 0x30;   '}' -> 0x02 to 0x30
    '\\' -> 0 to 0x31;   '|' -> 0x02 to 0x31
    ';'  -> 0 to 0x33;   ':' -> 0x02 to 0x33; '\'' -> 0 to 0x34;  '"' -> 0x02 to 0x34
    '`'  -> 0 to 0x35;   '~' -> 0x02 to 0x35
    ','  -> 0 to 0x36;   '<' -> 0x02 to 0x36; '.' -> 0 to 0x37;   '>' -> 0x02 to 0x37
    '/'  -> 0 to 0x38;   '?' -> 0x02 to 0x38
    else -> 0 to 0
}

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: TouchpadViewModel

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothHidService.LocalBinder
            viewModel.attachService(binder.getService())
        }
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            viewModel = viewModel()
            val settings by viewModel.settings.collectAsState()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = darkTheme) { MainScreen(viewModel, serviceConnection) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: TouchpadViewModel, serviceConnection: ServiceConnection) {
    val context = LocalContext.current
    val isServiceConnected by viewModel.isServiceConnected.collectAsState()

    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = bluetoothPermissions)

    if (permissionsState.allPermissionsGranted) {
        LaunchedEffect(Unit) {
            val intent = Intent(context, BluetoothHidService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        if (isServiceConnected) TouchpadApp(viewModel)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PermissionScreen(permissionsState)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionScreen(permissionsState: MultiplePermissionsState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bluetooth Permissions Required", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("This app needs Bluetooth permissions to act as an HID touchpad.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { permissionsState.launchMultiplePermissionRequest() }, shape = RoundedCornerShape(24.dp)) {
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
    val settings by viewModel.settings.collectAsState()
    val onboardingShown by viewModel.onboardingShown.collectAsState()
    val showReconnectPrompt by viewModel.showReconnectPrompt.collectAsState()

    var showDevicePicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        if (connectionState == BluetoothProfile.STATE_CONNECTED) showDevicePicker = false
        if (connectionState == BluetoothProfile.STATE_DISCONNECTED) showKeyboard = false
    }

    // Gyro sensor — only active in GYRO mode while connected
    if (settings.inputMode == InputMode.GYRO) {
        val currentSensitivity = rememberUpdatedState(settings.sensitivity)
        DisposableEffect(Unit) {
            val sensorManager = context.getSystemService(SensorManager::class.java)
            val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val scale = GYRO_SCALE * currentSensitivity.value
                    // values[1] = Y-axis rotation (tilt left/right) → horizontal
                    // values[0] = X-axis rotation (tilt up/down)    → vertical (inverted)
                    val rawX = event.values[1]
                    val rawY = event.values[0]
                    val dx = if (abs(rawX) > GYRO_DEAD_ZONE) (-rawX * scale).toInt() else 0
                    val dy = if (abs(rawY) > GYRO_DEAD_ZONE) (-rawY * scale).toInt() else 0
                    if (dx != 0 || dy != 0) viewModel.sendMouseReport(false, false, dx, dy, 0)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            if (gyro != null) sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sensorManager?.unregisterListener(listener) }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.clickable { showDevicePicker = true }) {
                    Text("MIDAS",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                        color = MaterialTheme.colorScheme.primary)
                    Text("Virtual Touchpad",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onBackground)
                }
                ConnectionStatusPill(connectionState, connectedDevice, onClick = { showDevicePicker = true })
            }

            // Touchpad + keyboard area
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp).padding(bottom = 16.dp)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (settings.inputMode == InputMode.GYRO) {
                        GyroControlSurface(viewModel, settings)
                    } else {
                        TouchpadSurface(viewModel, settings)
                    }
                    if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                        QuickConnectOverlay(
                            connectionState = connectionState,
                            pairedDevices = pairedDevices,
                            onConnect = { device ->
                                if (device != null) viewModel.connectDevice(device)
                                else viewModel.startAdvertising()
                            },
                            onCancel = { viewModel.disconnect() }
                        )
                    }
                }

                // Keyboard input area
                if (showKeyboard && settings.keyboardEnabled) {
                    KeyboardInputArea(viewModel, onDismiss = { showKeyboard = false })
                }

                Spacer(Modifier.height(16.dp))

                // Bottom controls
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Settings button
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            if (settings.inputMode == InputMode.GYRO) "Gyro Speed: ${sensitivityLabel(settings.sensitivity)}"
                            else "Sensitivity: ${sensitivityLabel(settings.sensitivity)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Keyboard toggle (only when connected and keyboard feature is on)
                    if (settings.keyboardEnabled && connectionState == BluetoothProfile.STATE_CONNECTED) {
                        IconButton(
                            onClick = { showKeyboard = !showKeyboard },
                            modifier = Modifier.size(40.dp).background(
                                if (showKeyboard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                        ) {
                            Icon(Icons.Default.Keyboard, contentDescription = "Keyboard",
                                tint = if (showKeyboard) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    // Device picker sheet
    if (showDevicePicker) {
        ModalBottomSheet(
            onDismissRequest = {
                if (connectionState == BluetoothProfile.STATE_CONNECTING) viewModel.disconnect()
                showDevicePicker = false
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            dragHandle = {
                Box(modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
                    .width(32.dp).height(4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Active connection row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(48.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Computer, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }
                        Column {
                            Text("Active Device",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary)
                            Text(connectedDevice?.name ?: "None",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Text("Disconnect", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Advertising — pair from your laptop's Bluetooth settings",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        } else {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Make Discoverable",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Previously Paired",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Open BT Settings",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    if (pairedDevices.isEmpty()) {
                        Text("No previously paired devices.\nTap \"Make Discoverable\", then add this device from your laptop's Bluetooth settings.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(pairedDevices) { device ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.background)
                                        .clickable { viewModel.connectDevice(device); showDevicePicker = false }
                                        .padding(horizontal = 20.dp, vertical = 18.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Bluetooth, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        if (device.address == connectedDevice?.address && connectionState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "PAIRED",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
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

    // Settings sheet
    if (showSettings) {
        SettingsSheet(settings, viewModel, onDismiss = { showSettings = false })
    }

    // Reconnect guidance (shown on unexpected disconnect)
    if (showReconnectPrompt) {
        ReconnectHintSheet(
            onDismiss = { viewModel.dismissReconnectPrompt() },
            onMakeDiscoverable = {
                viewModel.dismissReconnectPrompt()
                viewModel.startAdvertising()
            }
        )
    }

    // First-time onboarding (fullscreen overlay, drawn last so it's on top)
    if (!onboardingShown) {
        OnboardingOverlay(onDone = { viewModel.markOnboardingShown() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, viewModel: TouchpadViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        dragHandle = {
            Box(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                .width(32.dp).height(4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface)

            // Theme segmented control
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val themeOptions = listOf("System" to ThemeMode.SYSTEM, "Light" to ThemeMode.LIGHT, "Dark" to ThemeMode.DARK)
                val selectedThemeIndex = themeOptions.indexOfFirst { it.second == settings.themeMode }.takeIf { it >= 0 } ?: 0
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themeOptions.forEachIndexed { index, (label, mode) ->
                        SegmentedButton(
                            selected = selectedThemeIndex == index,
                            onClick = { viewModel.updateTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }

            // Input mode segmented control
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Input Mode",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val inputOptions = listOf("Touchpad" to InputMode.TOUCHPAD, "Gyroscope" to InputMode.GYRO)
                val selectedInputIndex = inputOptions.indexOfFirst { it.second == settings.inputMode }.takeIf { it >= 0 } ?: 0
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    inputOptions.forEachIndexed { index, (label, mode) ->
                        SegmentedButton(
                            selected = selectedInputIndex == index,
                            onClick = { viewModel.updateInputMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, inputOptions.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }

            // Sensitivity segmented control
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cursor Sensitivity",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sensitivityOptions = listOf("Low" to 0.8f, "Medium" to 1.5f, "High" to 2.5f)
                val selectedIndex = sensitivityOptions.indexOfFirst { it.second == settings.sensitivity }
                    .takeIf { it >= 0 } ?: 1
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    sensitivityOptions.forEachIndexed { index, (label, value) ->
                        SegmentedButton(
                            selected = selectedIndex == index,
                            onClick = { viewModel.updateSensitivity(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, sensitivityOptions.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }

            // Tap to click toggle
            ListItem(
                headlineContent = {
                    Text("Tap to Left Click", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                },
                supportingContent = {
                    Text("Single tap on the touchpad sends a left click",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    Switch(checked = settings.tapToClick, onCheckedChange = { viewModel.updateTapToClick(it) })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            )

            // Long press right click toggle
            ListItem(
                headlineContent = {
                    Text("Long Press Right Click", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                },
                supportingContent = {
                    Text("Hold finger still to send a right click",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    Switch(checked = settings.longPressRightClick, onCheckedChange = { viewModel.updateLongPressRightClick(it) })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            )

            // Haptic feedback toggle
            ListItem(
                headlineContent = {
                    Text("Haptic Feedback", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                },
                supportingContent = {
                    Text("Vibrate on left and right click",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    Switch(checked = settings.hapticFeedback, onCheckedChange = { viewModel.updateHapticFeedback(it) })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            )

            // Keyboard toggle
            ListItem(
                headlineContent = {
                    Text("Phone Keyboard", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                },
                supportingContent = {
                    Text("Show keyboard button to type into the connected device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    Switch(checked = settings.keyboardEnabled, onCheckedChange = { viewModel.updateKeyboardEnabled(it) })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
fun KeyboardInputArea(viewModel: TouchpadViewModel, onDismiss: () -> Unit) {
    var inputText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = inputText,
                onValueChange = { newText ->
                    val old = inputText
                    val commonLen = old.zip(newText).takeWhile { (a, b) -> a == b }.count()
                    val deletedCount = old.length - commonLen
                    val addedChars = newText.substring(commonLen)
                    repeat(deletedCount) { viewModel.sendKeyReport(0, 0x2A); viewModel.sendKeyRelease() }
                    addedChars.forEach { char ->
                        val (mod, code) = charToHidKeycode(char)
                        if (code != 0) { viewModel.sendKeyReport(mod, code); viewModel.sendKeyRelease() }
                    }
                    inputText = newText
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner ->
                    if (inputText.isEmpty()) {
                        Text("Type here to send keystrokes to your laptop…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                },
                singleLine = false
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardHide, contentDescription = "Dismiss keyboard",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectionStatusPill(state: Int, device: BluetoothDevice?, onClick: () -> Unit = {}) {
    val (backgroundColor, text, isConnected) = when (state) {
        BluetoothProfile.STATE_CONNECTED  -> Triple(MaterialTheme.colorScheme.primaryContainer, "Connected", true)
        BluetoothProfile.STATE_CONNECTING -> Triple(MaterialTheme.colorScheme.tertiaryContainer, "Connecting…", false)
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, "Disconnected", false)
    }
    val animatedColor by animateColorAsState(targetValue = backgroundColor, label = "pill")

    Surface(
        color = animatedColor,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
            .clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            when {
                isConnected -> {
                    Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                state == BluetoothProfile.STATE_CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                else -> Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TouchpadSurface(viewModel: TouchpadViewModel, settings: Settings) {
    val haptic = LocalHapticFeedback.current
    val glowColor = MaterialTheme.colorScheme.primary
    var touchPoint by remember { mutableStateOf<Offset?>(null) }
    var scrollThumbFraction by remember { mutableFloatStateOf(0.33f) }
    var leftClickPressed by remember { mutableStateOf(false) }
    val leftClickBg by animateColorAsState(
        targetValue = if (leftClickPressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
        label = "leftClickBg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Gesture area — pointerInput only covers this section, not the button row
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(settings) {
                        coroutineScope {
                            launch {
                                detectTapGestures(
                                    onTap = { offset ->
                                        if (settings.tapToClick) {
                                            if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            viewModel.sendMouseReport(leftBtn = true, rightBtn = false, 0, 0, 0)
                                            viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                                        }
                                        touchPoint = offset
                                    },
                                    onLongPress = { offset ->
                                        if (settings.longPressRightClick) {
                                            if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.sendMouseReport(leftBtn = false, rightBtn = true, 0, 0, 0)
                                            viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                                        }
                                        touchPoint = offset
                                    }
                                )
                            }
                            launch {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val active = event.changes.filter { it.pressed }
                                        if (active.isNotEmpty()) {
                                            touchPoint = active.first().position
                                            val scrollbarLeft = size.width - 44.dp.toPx()
                                            when {
                                                active.size >= 2 -> {
                                                    val avgY = (active[0].positionChange().y + active[1].positionChange().y) / 2
                                                    val scroll = (avgY * -0.5f).toInt()
                                                    if (scroll != 0) viewModel.sendMouseReport(false, false, 0, 0, scroll)
                                                    active.forEach { it.consume() }
                                                }
                                                active.size == 1 && active.first().position.x >= scrollbarLeft -> {
                                                    val dy = active.first().positionChange().y
                                                    if (dy != 0f) {
                                                        val scroll = (-dy * 0.1f).toInt()
                                                        if (scroll != 0) viewModel.sendMouseReport(false, false, 0, 0, scroll)
                                                        scrollThumbFraction = (scrollThumbFraction + dy / size.height).coerceIn(0f, 0.67f)
                                                        active.first().consume()
                                                    }
                                                }
                                                active.size == 1 -> {
                                                    val delta = active.first().positionChange()
                                                    if (delta != Offset.Zero) {
                                                        viewModel.sendMouseReport(false, false,
                                                            (delta.x * settings.sensitivity).toInt(),
                                                            (delta.y * settings.sensitivity).toInt(), 0)
                                                        active.first().consume()
                                                    }
                                                }
                                            }
                                        } else {
                                            touchPoint = null
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                // Glow trail
                touchPoint?.let { offset ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(glowColor.copy(alpha = 0.12f), Color.Transparent),
                                center = offset, radius = 300f
                            ),
                            radius = 300f, center = offset
                        )
                    }
                }

                // Center hint
                Column(
                    modifier = Modifier.align(Alignment.Center).alpha(0.3f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                    }
                    Text("GLIDE TO MOVE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Light, letterSpacing = 2.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp))
                }

                // Functional scrollbar
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp, top = 48.dp, bottom = 16.dp)
                        .width(6.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.weight(scrollThumbFraction.coerceAtLeast(0.001f)))
                        Box(modifier = Modifier.fillMaxWidth().weight(0.33f)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape))
                        Spacer(modifier = Modifier.weight((0.67f - scrollThumbFraction).coerceAtLeast(0.001f)))
                    }
                }
            }

            // Click buttons — outside the gesture area's pointerInput
            Row(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .background(leftClickBg)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RectangleShape)
                        .pointerInput(settings) {
                            awaitEachGesture {
                                awaitFirstDown()
                                leftClickPressed = true
                                if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.sendMouseReport(leftBtn = true, rightBtn = false, 0, 0, 0)
                                waitForUpOrCancellation()
                                leftClickPressed = false
                                viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("LEFT CLICK",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .background(Color.Transparent)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RectangleShape)
                        .clickable {
                            if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.sendMouseReport(leftBtn = false, rightBtn = true, 0, 0, 0)
                            viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("RIGHT CLICK",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun QuickConnectOverlay(
    connectionState: Int,
    pairedDevices: List<BluetoothDevice>,
    onConnect: (BluetoothDevice?) -> Unit,
    onCancel: () -> Unit
) {
    val isConnecting = connectionState == BluetoothProfile.STATE_CONNECTING
    val lastDevice = pairedDevices.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f), RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            if (isConnecting) {
                var showHint by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(8000)
                    showHint = true
                }
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Waiting for laptop…",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showHint) {
                    Text(
                        "Not connecting? On your laptop:\nSettings → Bluetooth → [your phone] → Connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                TextButton(onClick = onCancel) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp)
                )
                if (lastDevice != null) {
                    Button(
                        onClick = { onConnect(lastDevice) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Connect to ${lastDevice.name ?: "Last Device"}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else {
                    Button(
                        onClick = { onConnect(null) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Make Discoverable",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

@Composable
fun GyroControlSurface(viewModel: TouchpadViewModel, settings: Settings) {
    val haptic = LocalHapticFeedback.current
    var scrollThumbFraction by remember { mutableFloatStateOf(0.33f) }
    var leftClickPressed by remember { mutableStateOf(false) }
    val leftClickBg by animateColorAsState(
        targetValue = if (leftClickPressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
        label = "leftClickBg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(32.dp))
    ) {
        // Tap / long-press gesture zone — covers the surface minus buttons and scrollbar
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = 72.dp, end = 44.dp)
                .pointerInput(settings.tapToClick, settings.longPressRightClick, settings.hapticFeedback) {
                    detectTapGestures(
                        onTap = {
                            if (settings.tapToClick) {
                                if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.sendMouseReport(leftBtn = true, rightBtn = false, 0, 0, 0)
                                viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                            }
                        },
                        onLongPress = {
                            if (settings.longPressRightClick) {
                                if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.sendMouseReport(leftBtn = false, rightBtn = true, 0, 0, 0)
                                viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                            }
                        }
                    )
                }
        )

        // Center hint
        Column(
            modifier = Modifier.align(Alignment.Center).alpha(0.3f).padding(bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(48.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ScreenRotation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                "TILT TO MOVE",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Light, letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        // Scrollbar strip on the right
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(44.dp)
                .fillMaxHeight()
                .padding(top = 24.dp, bottom = 72.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.filter { it.pressed }
                            if (active.isNotEmpty()) {
                                val dy = active.first().positionChange().y
                                if (dy != 0f) {
                                    val scroll = (-dy * 0.1f).toInt()
                                    if (scroll != 0) viewModel.sendMouseReport(false, false, 0, 0, scroll)
                                    scrollThumbFraction = (scrollThumbFraction + dy / size.height).coerceIn(0f, 0.67f)
                                    active.first().consume()
                                }
                            }
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(6.dp)
                    .fillMaxHeight()
                    .padding(vertical = 16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.weight(scrollThumbFraction.coerceAtLeast(0.001f)))
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(0.33f)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape)
                    )
                    Spacer(modifier = Modifier.weight((0.67f - scrollThumbFraction).coerceAtLeast(0.001f)))
                }
            }
        }

        // Left + right click buttons along the bottom
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(leftClickBg)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RectangleShape)
                    .pointerInput(settings) {
                        awaitEachGesture {
                            awaitFirstDown()
                            leftClickPressed = true
                            if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.sendMouseReport(leftBtn = true, rightBtn = false, 0, 0, 0)
                            waitForUpOrCancellation()
                            leftClickPressed = false
                            viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "LEFT CLICK",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(Color.Transparent)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RectangleShape)
                    .clickable {
                        if (settings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = true, 0, 0, 0)
                        viewModel.sendMouseReport(leftBtn = false, rightBtn = false, 0, 0, 0)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "RIGHT CLICK",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun sensitivityLabel(value: Float) = when (value) {
    0.8f -> "Low"
    2.5f -> "High"
    else -> "Medium"
}

// ── Onboarding ────────────────────────────────────────────────────────────────

private data class OnboardingPage(val icon: ImageVector, val title: String, val body: String)

@Composable
fun OnboardingOverlay(onDone: () -> Unit) {
    val pages = remember {
        listOf(
            OnboardingPage(Icons.Default.Mouse, "Welcome to MIDAS",
                "Your phone becomes a wireless Bluetooth trackpad for any laptop or PC."),
            OnboardingPage(Icons.Default.TouchApp, "Swipe · Tap · Scroll",
                "Drag one finger to move the cursor. Tap to left-click. Two-finger swipe to scroll. Long-press to right-click."),
            OnboardingPage(Icons.Default.Bluetooth, "Pair Your Laptop",
                "Tap \"Make Discoverable\", then open Bluetooth on your laptop and select your phone to pair.")
        )
    }
    var page by remember { mutableIntStateOf(0) }
    val current = pages[page]
    val isLast = page == pages.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Spotlight circle (partially off-screen at top, like the reference)
        Box(
            modifier = Modifier
                .size(440.dp)
                .offset(y = (-72).dp)
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = current.icon,
                contentDescription = null,
                modifier = Modifier.size(96.dp).offset(y = 36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // Bottom content card
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                current.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                current.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(4.dp))

            // Pill dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.indices.forEach { i ->
                    val active = i == page
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(if (active) 24.dp else 8.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { if (isLast) onDone() else page++ },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    if (isLast) "Get Started" else "Next",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }

            if (!isLast) {
                TextButton(onClick = onDone) {
                    Text(
                        "Skip",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Reconnect hint ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconnectHintSheet(onDismiss: () -> Unit, onMakeDiscoverable: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        "Connection Lost",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Your laptop dropped the connection",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            Text(
                "To reconnect cleanly:",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val steps = listOf(
                "On your laptop, open Bluetooth settings and forget / remove this phone.",
                "On this phone, open Bluetooth settings and forget your laptop.",
                "Tap \"Make Discoverable\" below, then re-pair from your laptop."
            )
            steps.forEachIndexed { i, step ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onMakeDiscoverable,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Make Discoverable",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {}
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Open Bluetooth Settings",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}
