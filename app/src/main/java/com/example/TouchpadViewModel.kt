package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TouchpadViewModel : ViewModel() {
    private var hidService: BluetoothHidService? = null

    val isServiceConnected = MutableStateFlow(false)

    val connectionState: StateFlow<Int> = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectedDevice: StateFlow<BluetoothDevice?> = MutableStateFlow(null)
    val pairedDevices: StateFlow<List<BluetoothDevice>> = MutableStateFlow(emptyList())

    @SuppressLint("MissingPermission")
    fun attachService(service: BluetoothHidService) {
        hidService = service
        isServiceConnected.value = true

        viewModelScope.launch {
            service.hidManager.connectionState.collect { state ->
                (connectionState as MutableStateFlow).value = state
            }
        }
        viewModelScope.launch {
            service.hidManager.connectedDevice.collect { device ->
                (connectedDevice as MutableStateFlow).value = device
            }
        }
        viewModelScope.launch {
            service.hidManager.pairedDevices.collect { devices ->
                (pairedDevices as MutableStateFlow).value = devices
            }
        }
        service.hidManager.updatePairedDevices()
    }
    
    fun updatePairedDevices() {
        hidService?.hidManager?.updatePairedDevices()
    }

    fun connectDevice(device: BluetoothDevice) {
        hidService?.hidManager?.connect(device)
    }

    fun startAdvertising() {
        hidService?.hidManager?.startAdvertising()
    }
    
    fun disconnect() {
        hidService?.hidManager?.disconnect()
    }

    fun sendMouseReport(leftBtn: Boolean, rightBtn: Boolean, dx: Int, dy: Int, scroll: Int) {
        hidService?.hidManager?.sendMouseReport(leftBtn, rightBtn, false, dx, dy, scroll)
    }

    override fun onCleared() {
        super.onCleared()
        hidService = null
    }
}
