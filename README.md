<div align="center">

<h1>MIDAS</h1>
<p><strong>Turn your Android phone into a wireless Bluetooth touchpad & mouse</strong></p>

<p>
  <img src="https://img.shields.io/badge/Android-API%2028%2B-3DDC84?style=flat&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=flat&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat&logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/Bluetooth-HID%20over%20GATT-0082FC?style=flat&logo=bluetooth&logoColor=white" />
</p>

</div>

---

## What is MIDAS?

MIDAS is an Android app that turns your phone into a **wireless Bluetooth mouse** for any PC or laptop that supports Bluetooth HID. No receiver dongle, no Wi-Fi, no third-party server — it works entirely over standard Bluetooth Low Energy (BLE), presenting itself to your computer the same way a hardware mouse would.

It supports two input modes — a classic **touchpad** and an **air-mouse gyroscope** mode — along with a full set of tunable settings.

---

## Features

### Input
| Feature | Details |
|---|---|
| **Touchpad mode** | Glide a single finger to move the cursor |
| **Gyroscope mode** | Tilt the phone to move the cursor (air-mouse) |
| **Two-finger scroll** | Pinch two fingers on the touchpad to scroll |
| **Scrollbar strip** | Drag the right-edge strip to scroll in both modes |
| **Tap to click** | Single tap → left click (toggleable) |
| **Long press right click** | Hold to trigger right click (toggleable) |
| **Hold left click** | Press and hold the LEFT CLICK button for text-selection drag |
| **Keyboard** | Slide up the keyboard panel to type (toggleable) |

### Connectivity
| Feature | Details |
|---|---|
| **BLE HID** | Phone acts as a standard Bluetooth HID peripheral |
| **Auto-reconnect** | Directed advertising prompts the PC to reconnect after disconnect |
| **Keep-alive** | Zero mouse report sent every 3 s to prevent BLE radio power-down |
| **Wake lock** | Foreground service holds a partial wake lock to prevent CPU/Doze throttling |

### Appearance & Settings
| Feature | Details |
|---|---|
| **Dynamic color** | Follows your wallpaper accent on Android 12+ |
| **Theme modes** | System / Light / Dark |
| **Sensitivity** | Low / Medium / High |
| **Haptic feedback** | Tactile response on clicks (toggleable) |
| **Persistent settings** | All preferences survive restarts |

---

## Requirements

- **Phone:** Android 9 (API 28) or newer
- **PC / Laptop:** Any device with Bluetooth that supports HID over GATT (Windows 10/11, macOS, Linux all work)
- **Permissions:** Bluetooth, Bluetooth Scan, Bluetooth Connect, Bluetooth Advertise

---

## Setup & Usage

### 1. First-time pairing

1. **Open MIDAS** on your phone.
2. Tap **"Make Discoverable"** — the phone starts advertising itself as a Bluetooth HID device.
3. On your **PC**, open *Bluetooth & devices* → *Add device* → *Bluetooth*.
4. Select **MIDAS** from the list and complete the pairing.
5. Once paired, the status pill at the top of the app turns green — you're connected.

### 2. Reconnecting after disconnect

If the connection drops (screen timeout, app backgrounded, etc.):

- Tap the status pill or **"Reconnect"** prompt in the app — it sends directed advertising to your already-paired PC, which causes Windows to reconnect automatically within a few seconds.
- You do **not** need to re-pair or open Bluetooth settings on the PC.

### 3. Touchpad mode

- **Move cursor** — glide one finger anywhere on the touchpad surface.
- **Left click** — single tap (if *Tap to Click* is on), or press the LEFT CLICK button.
- **Right click** — long press on the surface (if *Long Press Right Click* is on), or press the RIGHT CLICK button.
- **Scroll** — drag two fingers up/down, or drag the scrollbar strip on the right edge.
- **Text selection drag** — hold the LEFT CLICK button while moving your finger on the touchpad.

### 4. Gyroscope mode

- **Move cursor** — tilt the phone in the direction you want the cursor to go.
- **Left / Right click** — tap the respective button at the bottom.
- **Scroll** — drag the scrollbar strip on the right edge.
- **Tap to click / Long press** — tap or long-press the gesture zone (same settings as touchpad mode apply).

### 5. Settings

Open the **settings sheet** by tapping the ⚙ icon. Available options:

| Setting | Description |
|---|---|
| Theme | System / Light / Dark |
| Input Mode | Touchpad / Gyroscope |
| Sensitivity | Cursor speed (Low / Medium / High) |
| Tap to Click | Single tap on surface = left click |
| Long Press Right Click | Hold on surface = right click |
| Haptic Feedback | Vibration on click events |
| Keyboard | Show/hide the keyboard panel |

---

## Building from Source

1. Clone the repo:
   ```bash
   git clone https://github.com/itz-mune/midas.git
   cd midas
   ```

2. Open in **Android Studio** (Ladybug or newer recommended).

3. Let Gradle sync finish, then **Run** on a physical device (BLE HID requires real hardware — emulators won't work).

> **Note:** A release build requires a signing keystore. Set the `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` environment variables, or use the default debug build for testing.

---

## How it works (technical)

MIDAS uses **Bluetooth Low Energy GATT** to register the phone as a HID peripheral:

- A `BluetoothGattServer` exposes the standard HID Service with a Mouse Report characteristic.
- `BluetoothLeAdvertiser` broadcasts the phone as connectable so the PC can discover and bond with it.
- Once connected, mouse movement, button state, and scroll are encoded as 4-byte HID reports and sent via GATT notifications.
- A foreground `Service` + `PARTIAL_WAKE_LOCK` keep the BLE stack alive when the screen is off.
- A 3-second keep-alive (zero report) prevents the BLE radio from entering power-save mode and dropping the link.

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM (`AndroidViewModel` + `StateFlow`)
- **BLE:** Android `BluetoothGattServer` / `BluetoothLeAdvertiser`
- **Sensors:** `TYPE_GYROSCOPE` via `SensorManager` (gyro mode)
- **Persistence:** `SharedPreferences` (settings)

---

## License

MIT License — see [`LICENSE`](LICENSE) for details.
