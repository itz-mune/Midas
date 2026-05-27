package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Settings(
    val sensitivity: Float = 1.5f,
    val tapToClick: Boolean = true,
    val keyboardEnabled: Boolean = true
)

class TouchpadViewModel : ViewModel() {
    private var hidService: BluetoothHidService? = null

    val isServiceConnected = MutableStateFlow(false)
    val connectionState: StateFlow<Int> = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectedDevice: StateFlow<BluetoothDevice?> = MutableStateFlow(null)
    val pairedDevices: StateFlow<List<BluetoothDevice>> = MutableStateFlow(emptyList())

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @SuppressLint("MissingPermission")
    fun attachService(service: BluetoothHidService) {
        hidService = service
        isServiceConnected.value = true
        viewModelScope.launch {
            service.hidManager.connectionState.collect {
                (connectionState as MutableStateFlow).value = it
            }
        }
        viewModelScope.launch {
            service.hidManager.connectedDevice.collect {
                (connectedDevice as MutableStateFlow).value = it
            }
        }
        viewModelScope.launch {
            service.hidManager.pairedDevices.collect {
                (pairedDevices as MutableStateFlow).value = it
            }
        }
        service.hidManager.updatePairedDevices()
    }

    fun updatePairedDevices() = hidService?.hidManager?.updatePairedDevices()
    fun connectDevice(device: BluetoothDevice) = hidService?.hidManager?.connect(device)
    fun startAdvertising() = hidService?.hidManager?.startAdvertising()
    fun disconnect() = hidService?.hidManager?.disconnect()

    fun sendMouseReport(leftBtn: Boolean, rightBtn: Boolean, dx: Int, dy: Int, scroll: Int) {
        hidService?.hidManager?.sendMouseReport(leftBtn, rightBtn, false, dx, dy, scroll)
    }

    fun sendKeyReport(modifier: Int, keycode: Int) = hidService?.hidManager?.sendKeyReport(modifier, keycode)
    fun sendKeyRelease() = hidService?.hidManager?.sendKeyReport(0, 0)

    fun updateSensitivity(value: Float) { _settings.value = _settings.value.copy(sensitivity = value) }
    fun updateTapToClick(value: Boolean) { _settings.value = _settings.value.copy(tapToClick = value) }
    fun updateKeyboardEnabled(value: Boolean) { _settings.value = _settings.value.copy(keyboardEnabled = value) }

    override fun onCleared() {
        super.onCleared()
        hidService = null
    }
}
