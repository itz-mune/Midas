package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class BluetoothHidService : Service() {

    private val binder = LocalBinder()
    lateinit var hidManager: HidManager
        private set

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothHidService = this@BluetoothHidService
    }

    override fun onCreate() {
        super.onCreate()
        hidManager = HidManager(this)
        hidManager.init()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Midas:BleKeepAlive")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        hidManager.cleanup()
        serviceScope.cancel()
        wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "hid_channel",
            "Bluetooth HID Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "hid_channel")
            .setContentTitle("Midas")
            .setContentText("Running in background to maintain connection")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
