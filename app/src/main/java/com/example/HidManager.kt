package com.example

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class HidManager(private val context: Context) {
    private val TAG = "HidManager"

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

    private var reportCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false
    private var connectedGattDevice: BluetoothDevice? = null

    private val pendingServices = ArrayDeque<BluetoothGattService>()

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: ${device.address}, state=$newState, status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedGattDevice = device
                    _connectedDevice.value = device
                    _connectionState.value = BluetoothProfile.STATE_CONNECTED
                    stopAdvertising()
                    updatePairedDevices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedGattDevice?.address == device.address) {
                        connectedGattDevice = null
                        notificationsEnabled = false
                        _connectedDevice.value = null
                        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
                    }
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "onServiceAdded: ${service.uuid}, status=$status")
            addNextService()
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
                notificationsEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.d(TAG, "Notifications ${if (notificationsEnabled) "enabled" else "disabled"} by ${device.address}")
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    fun init() {
        gattServer = bluetoothManager?.openGattServer(context, gattCallback)
        pendingServices.addAll(listOf(createHidService(), createDeviceInfoService(), createBatteryService()))
        addNextService()
    }

    private fun addNextService() {
        pendingServices.removeFirstOrNull()?.let { gattServer?.addService(it) }
    }

    fun startAdvertising() {
        if (_connectionState.value != BluetoothProfile.STATE_DISCONNECTED) return
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising not supported on this device"); return
        }
        advertiser = adv
        _connectionState.value = BluetoothProfile.STATE_CONNECTING

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // HID service UUID in the primary ad packet; device name in scan response to avoid overflow
        val adData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_HID))
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, adData, scanResponse, advertiseCallback)
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        if (_connectionState.value == BluetoothProfile.STATE_CONNECTING) {
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        }
    }

    // Tapping a known device just starts advertising; the host will auto-reconnect if bonded
    fun connect(device: BluetoothDevice) = startAdvertising()

    fun disconnect() {
        connectedGattDevice?.let { gattServer?.cancelConnection(it) }
        stopAdvertising()
    }

    fun sendMouseReport(
        leftButton: Boolean, rightButton: Boolean, middleButton: Boolean,
        dx: Int, dy: Int, scroll: Int
    ) {
        val device = connectedGattDevice ?: return
        if (_connectionState.value != BluetoothProfile.STATE_CONNECTED || !notificationsEnabled) return

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

        reportCharacteristic?.let { char ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, char, false, report)
            } else {
                @Suppress("DEPRECATION") char.value = report
                @Suppress("DEPRECATION") gattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    fun updatePairedDevices() {
        if (bluetoothAdapter?.isEnabled == true) {
            _pairedDevices.value = bluetoothAdapter.bondedDevices.toList()
        }
    }

    fun cleanup() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
    }

    // --- GATT service builders ---

    private fun createHidService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        service.addCharacteristic(char(CHAR_PROTOCOL_MODE,
            PROP_READ or PROP_WRITE_NR, PERM_READ or PERM_WRITE, byteArrayOf(0x01)))

        service.addCharacteristic(char(CHAR_REPORT_MAP,
            PROP_READ, PERM_READ, MOUSE_REPORT_DESC))

        // bcdHID=1.11, country=0, flags=NormallyConnectable|RemoteWake
        service.addCharacteristic(char(CHAR_HID_INFO,
            PROP_READ, PERM_READ, byteArrayOf(0x11, 0x01, 0x00, 0x03)))

        service.addCharacteristic(char(CHAR_HID_CONTROL_POINT,
            PROP_WRITE_NR, PERM_WRITE, byteArrayOf(0x00)))

        val reportChar = BluetoothGattCharacteristic(
            CHAR_REPORT, PROP_READ or PROP_NOTIFY, PERM_READ
        ).apply { value = ByteArray(4) }
        reportChar.addDescriptor(desc(UUID_CCC, PERM_READ or PERM_WRITE,
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
        reportChar.addDescriptor(desc(UUID_REPORT_REF, PERM_READ,
            byteArrayOf(0x01, 0x01))) // Report ID=1, Input Report
        service.addCharacteristic(reportChar)
        reportCharacteristic = reportChar

        return service
    }

    private fun createDeviceInfoService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // PnP ID: source=USB(0x02), VID=0x0000, PID=0x0000, version=0x0001
        service.addCharacteristic(char(CHAR_PNP_ID,
            PROP_READ, PERM_READ, byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)))
        return service
    }

    private fun createBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val battChar = BluetoothGattCharacteristic(
            CHAR_BATTERY_LEVEL, PROP_READ or PROP_NOTIFY, PERM_READ
        ).apply { value = byteArrayOf(100) }
        battChar.addDescriptor(desc(UUID_CCC, PERM_READ or PERM_WRITE,
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
        service.addCharacteristic(battChar)
        return service
    }

    private fun char(uuid: UUID, properties: Int, permissions: Int, value: ByteArray) =
        BluetoothGattCharacteristic(uuid, properties, permissions).apply { this.value = value }

    private fun desc(uuid: UUID, permissions: Int, value: ByteArray) =
        BluetoothGattDescriptor(uuid, permissions).apply { this.value = value }

    companion object {
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
            0x05, 0x01,              // Usage Page (Generic Desktop)
            0x09, 0x02,              // Usage (Mouse)
            0xA1.toByte(), 0x01,     // Collection (Application)
            0x85.toByte(), 0x01,     //   Report ID (1)
            0x09, 0x01,              //   Usage (Pointer)
            0xA1.toByte(), 0x00,     //   Collection (Physical)
            0x05, 0x09,              //     Usage Page (Button)
            0x19, 0x01,              //     Usage Minimum (1)
            0x29, 0x03,              //     Usage Maximum (3)
            0x15, 0x00,              //     Logical Minimum (0)
            0x25, 0x01,              //     Logical Maximum (1)
            0x95.toByte(), 0x03,     //     Report Count (3)
            0x75, 0x01,              //     Report Size (1)
            0x81.toByte(), 0x02,     //     Input (Data, Var, Abs) — buttons
            0x95.toByte(), 0x01,     //     Report Count (1)
            0x75, 0x05,              //     Report Size (5)
            0x81.toByte(), 0x03,     //     Input (Const) — padding
            0x05, 0x01,              //     Usage Page (Generic Desktop)
            0x09, 0x30,              //     Usage (X)
            0x09, 0x31,              //     Usage (Y)
            0x15, 0x81.toByte(),     //     Logical Minimum (-127)
            0x25, 0x7F,              //     Logical Maximum (127)
            0x75, 0x08,              //     Report Size (8)
            0x95.toByte(), 0x02,     //     Report Count (2)
            0x81.toByte(), 0x06,     //     Input (Data, Var, Rel) — X/Y
            0x09, 0x38,              //     Usage (Wheel)
            0x15, 0x81.toByte(),     //     Logical Minimum (-127)
            0x25, 0x7F,              //     Logical Maximum (127)
            0x75, 0x08,              //     Report Size (8)
            0x95.toByte(), 0x01,     //     Report Count (1)
            0x81.toByte(), 0x06,     //     Input (Data, Var, Rel) — scroll
            0xC0.toByte(),           //   End Collection
            0xC0.toByte()            // End Collection
        )
    }
}
