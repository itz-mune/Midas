package com.example

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class HidManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice = _connectedDevice.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices = _pairedDevices.asStateFlow()

    private var mouseReportChar: BluetoothGattCharacteristic? = null
    private var keyboardReportChar: BluetoothGattCharacteristic? = null
    private var connectedGattDevice: BluetoothDevice? = null

    private val enabledNotifications = mutableSetOf<BluetoothGattCharacteristic>()

    private val prefs = context.getSharedPreferences("midas_prefs", Context.MODE_PRIVATE)

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            if (state == BluetoothDevice.BOND_BONDED) updatePairedDevices()
        }
    }

    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var pendingAdvertise = false

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: ${device.address}, state=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedGattDevice = device
                    _connectedDevice.value = device
                    _connectionState.value = BluetoothProfile.STATE_CONNECTED
                    stopAdvertising()
                    prefs.edit().putString("last_device_address", device.address).apply()
                    updatePairedDevices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedGattDevice?.address == device.address) {
                        connectedGattDevice = null
                        enabledNotifications.clear()
                        _connectedDevice.value = null
                        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
                        updatePairedDevices()
                    }
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "onServiceAdded: ${service.uuid}, status=$status")
            if (pendingServices.isEmpty() && pendingAdvertise) {
                pendingAdvertise = false
                doStartAdvertising()
            } else {
                addNextService()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: byteArrayOf()
            val slice = if (offset <= value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            characteristic.value = value
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val value = descriptor.value ?: byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            descriptor.value = value
            if (descriptor.uuid == UUID_CCC) {
                val char = descriptor.characteristic
                if (char != null) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        enabledNotifications.add(char)
                        Log.d(TAG, "Notifications enabled for ${char.uuid}")
                    } else {
                        enabledNotifications.remove(char)
                    }
                }
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    fun init() {
        gattServer = bluetoothManager?.openGattServer(context, gattCallback)
        pendingServices.addAll(listOf(createHidService(), createDeviceInfoService(), createBatteryService()))
        addNextService()
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondReceiver, filter)
        }
    }

    private fun addNextService() {
        pendingServices.removeFirstOrNull()?.let { gattServer?.addService(it) }
    }

    fun startAdvertising() {
        if (_connectionState.value != BluetoothProfile.STATE_DISCONNECTED) return

        // Fast path: BLE link is still alive (soft disconnect), just resume the touchpad
        if (connectedGattDevice != null) {
            _connectedDevice.value = connectedGattDevice
            _connectionState.value = BluetoothProfile.STATE_CONNECTED
            return
        }

        // BLE link has dropped — need to advertise so Windows reconnects
        _connectionState.value = BluetoothProfile.STATE_CONNECTING
        if (gattServer == null) {
            gattServer = bluetoothManager?.openGattServer(context, gattCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
                return
            }
            pendingAdvertise = true
            pendingServices.clear()
            pendingServices.addAll(listOf(createHidService(), createDeviceInfoService(), createBatteryService()))
            addNextService()
        } else {
            doStartAdvertising()
        }
    }

    private fun doStartAdvertising() {
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising not supported")
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()

        val adData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_HID)).setIncludeTxPowerLevel(false).build()

        val scanResp = AdvertiseData.Builder().setIncludeDeviceName(true).build()

        adv.startAdvertising(settings, adData, scanResp, advertiseCallback)
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        if (_connectionState.value == BluetoothProfile.STATE_CONNECTING)
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { Log.d(TAG, "Advertising started") }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        }
    }

    fun connect(device: BluetoothDevice) = startAdvertising()

    fun disconnect() {
        pendingAdvertise = false
        stopAdvertising()
        // Soft disconnect: keep the BLE link and GATT server alive.
        // The link will supervision-timeout naturally, which Windows treats as
        // "device disappeared" — not a clean user disconnect — so it will auto-reconnect
        // the next time we advertise.
        _connectedDevice.value = null
        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        updatePairedDevices()
        // connectedGattDevice intentionally kept set
    }

    fun sendMouseReport(leftButton: Boolean, rightButton: Boolean, middleButton: Boolean, dx: Int, dy: Int, scroll: Int) {
        val device = connectedGattDevice ?: return
        if (_connectionState.value != BluetoothProfile.STATE_CONNECTED) return
        val char = mouseReportChar ?: return
        if (!enabledNotifications.contains(char)) return

        var buttons = 0
        if (leftButton) buttons = buttons or 1
        if (rightButton) buttons = buttons or 2
        if (middleButton) buttons = buttons or 4

        val report = byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            scroll.coerceIn(-127, 127).toByte()
        )
        notify(device, char, report)
    }

    fun sendKeyReport(modifier: Int, keycode: Int) {
        val device = connectedGattDevice ?: return
        if (_connectionState.value != BluetoothProfile.STATE_CONNECTED) return
        val char = keyboardReportChar ?: return
        if (!enabledNotifications.contains(char)) return

        val report = byteArrayOf(modifier.toByte(), 0x00, keycode.toByte(), 0, 0, 0, 0, 0)
        notify(device, char, report)
    }

    private fun notify(device: BluetoothDevice, char: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(device, char, false, value)
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(device, char, false)
        }
    }

    fun updatePairedDevices() {
        if (bluetoothAdapter?.isEnabled != true) return
        val lastAddress = prefs.getString("last_device_address", null)
        val devices = bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
        _pairedDevices.value = if (lastAddress != null) {
            devices.sortedByDescending { it.address == lastAddress }
        } else {
            devices
        }
    }

    fun cleanup() {
        stopAdvertising()
        try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        // Don't explicitly close the GATT server — let process death trigger a
        // supervision timeout on Windows's side rather than a clean LL_TERMINATE_IND.
        // Supervision timeout = "device disappeared" → Windows auto-reconnects next time.
        // Clean disconnect = "device said goodbye" → Windows stops scanning.
    }

    // --- GATT service builders ---

    private fun createHidService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        service.addCharacteristic(char(CHAR_PROTOCOL_MODE,
            PROP_READ or PROP_WRITE_NR, PERM_READ or PERM_WRITE, byteArrayOf(0x01)))

        service.addCharacteristic(char(CHAR_REPORT_MAP,
            PROP_READ, PERM_READ, MOUSE_REPORT_DESC + KEYBOARD_REPORT_DESC))

        service.addCharacteristic(char(CHAR_HID_INFO,
            PROP_READ, PERM_READ, byteArrayOf(0x11, 0x01, 0x00, 0x03)))

        service.addCharacteristic(char(CHAR_HID_CONTROL_POINT,
            PROP_WRITE_NR, PERM_WRITE, byteArrayOf(0x00)))

        // Mouse input report — Report ID 1
        val mouse = BluetoothGattCharacteristic(CHAR_REPORT, PROP_READ or PROP_NOTIFY, PERM_READ)
            .apply { value = ByteArray(4) }
        mouse.addDescriptor(desc(UUID_CCC, PERM_READ or PERM_WRITE, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
        mouse.addDescriptor(desc(UUID_REPORT_REF, PERM_READ, byteArrayOf(0x01, 0x01)))
        service.addCharacteristic(mouse)
        mouseReportChar = mouse

        // Keyboard input report — Report ID 2
        val keyboard = BluetoothGattCharacteristic(CHAR_REPORT, PROP_READ or PROP_NOTIFY, PERM_READ)
            .apply { value = ByteArray(8) }
        keyboard.addDescriptor(desc(UUID_CCC, PERM_READ or PERM_WRITE, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
        keyboard.addDescriptor(desc(UUID_REPORT_REF, PERM_READ, byteArrayOf(0x02, 0x01)))
        service.addCharacteristic(keyboard)
        keyboardReportChar = keyboard

        return service
    }

    private fun createDeviceInfoService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(char(CHAR_PNP_ID, PROP_READ, PERM_READ,
            byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)))
        return service
    }

    private fun createBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val batt = BluetoothGattCharacteristic(CHAR_BATTERY_LEVEL, PROP_READ or PROP_NOTIFY, PERM_READ)
            .apply { value = byteArrayOf(100) }
        batt.addDescriptor(desc(UUID_CCC, PERM_READ or PERM_WRITE, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
        service.addCharacteristic(batt)
        return service
    }

    private fun char(uuid: UUID, properties: Int, permissions: Int, value: ByteArray) =
        BluetoothGattCharacteristic(uuid, properties, permissions).apply { this.value = value }

    private fun desc(uuid: UUID, permissions: Int, value: ByteArray) =
        BluetoothGattDescriptor(uuid, permissions).apply { this.value = value }

    companion object {
        private const val TAG = "HidManager"

        private val PROP_READ     = BluetoothGattCharacteristic.PROPERTY_READ
        private val PROP_NOTIFY   = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        private val PROP_WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        private val PERM_READ     = BluetoothGattCharacteristic.PERMISSION_READ
        private val PERM_WRITE    = BluetoothGattCharacteristic.PERMISSION_WRITE

        val SERVICE_HID         = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        val SERVICE_DEVICE_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val SERVICE_BATTERY     = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        val CHAR_PROTOCOL_MODE     = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
        val CHAR_REPORT            = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        val CHAR_REPORT_MAP        = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
        val CHAR_HID_INFO          = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
        val CHAR_HID_CONTROL_POINT = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
        val CHAR_PNP_ID            = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
        val CHAR_BATTERY_LEVEL     = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        val UUID_CCC        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val UUID_REPORT_REF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")

        val MOUSE_REPORT_DESC: ByteArray = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x01,                         // Report ID 1
            0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01,
            0x81.toByte(), 0x02,                         // buttons
            0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03, // padding
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x02,
            0x81.toByte(), 0x06,                         // X/Y
            0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x01,
            0x81.toByte(), 0x06,                         // scroll
            0xC0.toByte(), 0xC0.toByte()
        )

        val KEYBOARD_REPORT_DESC: ByteArray = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x02,                         // Report ID 2
            0x05, 0x07,
            0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),   // modifier keys
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03, // reserved
            0x95.toByte(), 0x06, 0x75, 0x08,            // 6 keycodes
            0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
            0x81.toByte(), 0x00,
            0xC0.toByte()
        )
    }
}
