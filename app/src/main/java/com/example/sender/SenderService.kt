package com.example.sender

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SenderService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "sender_server_channel"
        const val NOTIF_ID = 101

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
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        startForeground(NOTIF_ID, buildNotification(0, emptyList(), emptyList()))
        val server = (application as SenderApp).server
        scope.launch {
            combine(
                server.connectedDevices,
                server.activeTransfers,
                server.incomingTransfers
            ) { devices, outgoing, incoming ->
                Triple(devices.size, outgoing, incoming)
            }.collect { (count, outgoing, incoming) ->
                nm.notify(NOTIF_ID, buildNotification(count, outgoing, incoming))
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Explicitly remove the notification when the server is stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        (application as SenderApp).stopServer()
        super.onDestroy()
    }

    private fun buildNotification(
        deviceCount: Int,
        outgoing: List<TransferProgress>,
        incoming: List<TransferProgress>
    ): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activeTransfer = outgoing.firstOrNull() ?: incoming.firstOrNull()
        val isSending = outgoing.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(tapIntent)
            .setOngoing(true)

        if (activeTransfer != null) {
            val shortName = activeTransfer.name.let { n ->
                if (n.length > 22) "${n.take(10)}…${n.takeLast(9)}" else n
            }
            val pct = (activeTransfer.fraction * 100).toInt()
            val direction = if (isSending) "Sending" else "Receiving"
            builder.setContentTitle("$direction · $pct%")
            builder.setContentText(shortName)
            if (activeTransfer.totalBytes > 0) {
                builder.setProgress(100, pct, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            val statusText = when (deviceCount) {
                0    -> "Running · no devices connected"
                1    -> "Running · 1 device connected"
                else -> "Running · $deviceCount devices connected"
            }
            builder.setContentTitle("Sender")
            builder.setContentText(statusText)
        }

        return builder.build()
    }
}
