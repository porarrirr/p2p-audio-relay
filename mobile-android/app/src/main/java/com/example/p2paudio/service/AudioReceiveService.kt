package com.example.p2paudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.p2paudio.R
import com.example.p2paudio.logging.AppLogger

@Suppress("DEPRECATION")
internal fun receiverWifiLockModeForSdk(sdkInt: Int): Int {
    return if (sdkInt >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}

class AudioReceiveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i("AudioReceiveService", "service_create", "Receiver foreground service created")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        AppLogger.d(
            "AudioReceiveService",
            "service_start_command",
            "Received onStartCommand",
            context = mapOf("startId" to startId, "flags" to flags, "action" to action)
        )

        return when (action) {
            ACTION_START_RECEIVE -> {
                startAsForegroundService()
                acquireKeepaliveLocks()
                START_STICKY
            }

            ACTION_STOP_RECEIVE -> {
                stopSelfResult(startId)
                START_NOT_STICKY
            }

            else -> START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        AppLogger.i("AudioReceiveService", "service_destroy", "Receiver foreground service destroyed")
        releaseKeepaliveLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireKeepaliveLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:udp-receive")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        if (wifiLock?.isHeld != true) {
            wifiLock = wifiManager
                ?.createWifiLock(
                    receiverWifiLockModeForSdk(Build.VERSION.SDK_INT),
                    "$packageName:udp-receive"
                )
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }

        AppLogger.i(
            "AudioReceiveService",
            "keepalive_locks_acquired",
            "Receiver keepalive locks acquired",
            context = mapOf(
                "wakeLockHeld" to (wakeLock?.isHeld == true),
                "wifiLockHeld" to (wifiLock?.isHeld == true),
                "wifiLockMode" to receiverWifiLockModeForSdk(Build.VERSION.SDK_INT)
            )
        )
    }

    private fun releaseKeepaliveLocks() {
        wifiLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wifiLock = null

        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_receive_content_title))
            .setContentText(getString(R.string.notification_receive_content_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START_RECEIVE = "com.example.p2paudio.action.START_RECEIVE"
        const val ACTION_STOP_RECEIVE = "com.example.p2paudio.action.STOP_RECEIVE"
        private const val CHANNEL_ID = "p2p_audio_receive_channel"
        private const val NOTIFICATION_ID = 3002
    }
}
