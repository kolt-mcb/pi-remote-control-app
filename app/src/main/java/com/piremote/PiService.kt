package com.piremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val CHANNEL_ID = "pi_remote_connection"
private const val NOTIFICATION_ID = 1001

/**
 * Foreground service that keeps the Pi WebSocket connection alive while the
 * app is in the background. Shows an ongoing "Connected to Pi" notification.
 */
class PiService : Service() {

    private val notificationMgr by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverHost = intent?.getStringExtra(EXTRA_HOST) ?: "Pi"
        val busy = intent?.getBooleanExtra(EXTRA_BUSY, false) == true
        val msgCount = intent?.getIntExtra(EXTRA_MSG_COUNT, 0) ?: 0

        val notification = buildNotification(serverHost, busy, msgCount)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(serverHost: String, busy: Boolean, msgCount: Int): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subText = when {
            busy -> "Thinking..."
            msgCount > 0 -> "$msgCount messages"
            else -> "Connected"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pi Remote — Connected")
            .setContentText("Connected to $serverHost")
            .setSubText(subText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pi Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active Pi agent connection"
        }
        notificationMgr.createNotificationChannel(channel)
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_BUSY = "busy"
        private const val EXTRA_MSG_COUNT = "msg_count"

        /** Start or update the foreground service */
        fun start(context: Context, serverHost: String, busy: Boolean = false, msgCount: Int = 0) {
            val intent = Intent(context, PiService::class.java).apply {
                putExtra(EXTRA_HOST, serverHost)
                putExtra(EXTRA_BUSY, busy)
                putExtra(EXTRA_MSG_COUNT, msgCount)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the foreground service */
        fun stop(context: Context) {
            context.stopService(Intent(context, PiService::class.java))
        }
    }
}
