package com.example

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class InputMode { TOUCHPAD, GYRO }

data class Settings(
    val sensitivity: Float = 1.5f,
    val tapToClick: Boolean = true,
    val longPressRightClick: Boolean = true,
    val hapticFeedback: Boolean = true,
    val keyboardEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val inputMode: InputMode = InputMode.TOUCHPAD
)

class TouchpadViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("midas_settings", Context.MODE_PRIVATE)

    private var hidService: BluetoothHidService? = null

    val isServiceConnected = MutableStateFlow(false)
    val connectionState: StateFlow<Int> = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectedDevice: StateFlow<BluetoothDevice?> = MutableStateFlow(null)
    val pairedDevices: StateFlow<List<BluetoothDevice>> = MutableStateFlow(emptyList())

    val onboardingShown = MutableStateFlow(prefs.getBoolean("onboarding_shown", false))
    val showReconnectPrompt = MutableStateFlow(false)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun loadSettings() = Settings(
        sensitivity = prefs.getFloat("sensitivity", 1.5f),
        tapToClick = prefs.getBoolean("tap_to_click", true),
        longPressRightClick = prefs.getBoolean("long_press_right_click", true),
        hapticFeedback = prefs.getBoolean("haptic_feedback", true),
        keyboardEnabled = prefs.getBoolean("keyboard_enabled", true),
        themeMode = try { ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!) } catch (_: Exception) { ThemeMode.SYSTEM },
        inputMode = try { InputMode.valueOf(prefs.getString("input_mode", InputMode.TOUCHPAD.name)!!) } catch (_: Exception) { InputMode.TOUCHPAD }
    )

    private fun save(s: Settings) {
        prefs.edit()
            .putFloat("sensitivity", s.sensitivity)
            .putBoolean("tap_to_click", s.tapToClick)
            .putBoolean("long_press_right_click", s.longPressRightClick)
            .putBoolean("haptic_feedback", s.hapticFeedback)
            .putBoolean("keyboard_enabled", s.keyboardEnabled)
            .putString("theme_mode", s.themeMode.name)
            .putString("input_mode", s.inputMode.name)
            .apply()
    }

    @SuppressLint("MissingPermission")
    fun attachService(service: BluetoothHidService) {
        hidService = service
        isServiceConnected.value = true
        viewModelScope.launch {
            var prev = BluetoothProfile.STATE_DISCONNECTED
            service.hidManager.connectionState.collect { state ->
                if (prev == BluetoothProfile.STATE_CONNECTED && state == BluetoothProfile.STATE_DISCONNECTED) {
                    showReconnectPrompt.value = true
                }
                prev = state
                (connectionState as MutableStateFlow).value = state
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

    fun markOnboardingShown() {
        prefs.edit().putBoolean("onboarding_shown", true).apply()
        onboardingShown.value = true
    }

    fun dismissReconnectPrompt() { showReconnectPrompt.value = false }

    fun updatePairedDevices() = hidService?.hidManager?.updatePairedDevices()
    fun connectDevice(device: BluetoothDevice) = hidService?.hidManager?.connect(device)
    fun startAdvertising() = hidService?.hidManager?.startAdvertising()
    fun disconnect() = hidService?.hidManager?.disconnect()

    fun sendMouseReport(leftBtn: Boolean, rightBtn: Boolean, dx: Int, dy: Int, scroll: Int) {
        hidService?.hidManager?.sendMouseReport(leftBtn, rightBtn, false, dx, dy, scroll)
    }

    fun sendKeyReport(modifier: Int, keycode: Int) = hidService?.hidManager?.sendKeyReport(modifier, keycode)
    fun sendKeyRelease() = hidService?.hidManager?.sendKeyReport(0, 0)

    fun updateSensitivity(value: Float) { update(_settings.value.copy(sensitivity = value)) }
    fun updateTapToClick(value: Boolean) { update(_settings.value.copy(tapToClick = value)) }
    fun updateLongPressRightClick(value: Boolean) { update(_settings.value.copy(longPressRightClick = value)) }
    fun updateHapticFeedback(value: Boolean) { update(_settings.value.copy(hapticFeedback = value)) }
    fun updateKeyboardEnabled(value: Boolean) { update(_settings.value.copy(keyboardEnabled = value)) }
    fun updateTheme(mode: ThemeMode) { update(_settings.value.copy(themeMode = mode)) }
    fun updateInputMode(mode: InputMode) { update(_settings.value.copy(inputMode = mode)) }

    private fun update(s: Settings) { _settings.value = s; save(s) }

    override fun onCleared() {
        super.onCleared()
        hidService = null
    }
}
