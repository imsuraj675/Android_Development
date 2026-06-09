package com.example.sender

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SenderService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID  = "sender_server_channel"
        private const val NOTIF_ID    = 101

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SenderService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SenderService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Sender Server", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Sender running in the background"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(0))
        scope.launch {
            (application as SenderApp).server.connectedDevices.collect { devices ->
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, buildNotification(devices.size))
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        (application as SenderApp).stopServer()
        super.onDestroy()
    }

    private fun buildNotification(deviceCount: Int): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = when (deviceCount) {
            0    -> "Running · no devices connected"
            1    -> "Running · 1 device connected"
            else -> "Running · $deviceCount devices connected"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sender")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }
}
