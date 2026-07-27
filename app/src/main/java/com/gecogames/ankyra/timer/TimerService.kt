package com.gecogames.ankyra.timer

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gecogames.ankyra.MainActivity
import com.gecogames.ankyra.R
import java.util.Locale

class TimerService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
                if (duration <= 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val snapshot = TimerStore.start(this, duration)
                scheduleAlarm(this, snapshot.remainingMillis)
                showForeground(snapshot)
            }

            ACTION_PAUSE -> {
                cancelAlarm(this)
                showForeground(TimerStore.pause(this))
            }

            ACTION_RESUME -> {
                val snapshot = TimerStore.resume(this)
                scheduleAlarm(this, snapshot.remainingMillis)
                showForeground(snapshot)
            }

            ACTION_CANCEL -> {
                cancelAlarm(this)
                TimerStore.clear(this)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                val snapshot = TimerStore.snapshot(this)
                if (snapshot.status == TimerStatus.RUNNING || snapshot.status == TimerStatus.PAUSED) {
                    showForeground(snapshot)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showForeground(snapshot: TimerSnapshot) {
        val notification = buildOngoingNotification(this, snapshot)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    companion object {
        const val ACTION_START = "com.gecogames.ankyra.action.START"
        const val ACTION_PAUSE = "com.gecogames.ankyra.action.PAUSE"
        const val ACTION_RESUME = "com.gecogames.ankyra.action.RESUME"
        const val ACTION_CANCEL = "com.gecogames.ankyra.action.CANCEL"
        const val EXTRA_DURATION = "duration_millis"

        private const val TIMER_CHANNEL = "ankyra_active_timer"
        private const val FINISHED_CHANNEL = "ankyra_finished_timer"
        private const val NOTIFICATION_ID = 4101
        private const val FINISHED_NOTIFICATION_ID = 4102
        private const val ALARM_REQUEST_CODE = 4201

        fun start(context: Context, durationMillis: Long) {
            val intent = Intent(context, TimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DURATION, durationMillis)
            ContextCompat.startForegroundService(context, intent)
        }

        fun sendAction(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TimerService::class.java).setAction(action)
            )
        }

        fun completeTimer(context: Context) {
            createChannels(context)
            cancelAlarm(context)
            TimerStore.finish(context)
            context.stopService(Intent(context, TimerService::class.java))

            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(
                    FINISHED_NOTIFICATION_ID,
                    buildFinishedNotification(context)
                )
            }
            vibrate(context)
        }

        private fun buildOngoingNotification(
            context: Context,
            snapshot: TimerSnapshot
        ): Notification {
            val running = snapshot.status == TimerStatus.RUNNING
            val remaining = snapshot.remainingMillis.coerceAtLeast(0L)
            val endWallClock = System.currentTimeMillis() + remaining
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val toggleAction = if (running) ACTION_PAUSE else ACTION_RESUME
            val toggleTitle = if (running) "Pause" else "Resume"
            val toggleIcon = if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val toggleIntent = servicePendingIntent(context, toggleAction, 1)
            val cancelIntent = servicePendingIntent(context, ACTION_CANCEL, 2)

            return NotificationCompat.Builder(context, TIMER_CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle("Ankyra Timer")
                .setContentText(if (running) "Timer running" else "${formatDuration(remaining)} remaining · Paused")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        if (running) {
                            "Timer in progress. Pause or stop it without opening Ankyra."
                        } else {
                            "${formatDuration(remaining)} remaining. Resume when ready."
                        }
                    )
                )
                .setContentIntent(openApp)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setRequestPromotedOngoing(true)
                .setShowWhen(running)
                .setWhen(if (running) endWallClock else System.currentTimeMillis())
                .setUsesChronometer(running)
                .setChronometerCountDown(running)
                .setProgress(
                    snapshot.totalMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    (snapshot.totalMillis - remaining).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    false
                )
                .addAction(toggleIcon, toggleTitle, toggleIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancelIntent)
                .build()
        }

        private fun buildFinishedNotification(context: Context): Notification {
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, FINISHED_CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle("Timer complete")
                .setContentText("Your Ankyra timer has finished.")
                .setContentIntent(openApp)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        }

        private fun servicePendingIntent(
            context: Context,
            action: String,
            requestCode: Int
        ): PendingIntent = PendingIntent.getService(
            context,
            requestCode,
            Intent(context, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun scheduleAlarm(context: Context, delayMillis: Long) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMillis,
                alarmPendingIntent(context)
            )
        }

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .cancel(alarmPendingIntent(context))
        }

        private fun alarmPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                Intent(context, TimerAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)

            val timerChannel = NotificationChannel(
                TIMER_CHANNEL,
                context.getString(R.string.notification_channel_timer),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows an active Ankyra timer and its controls"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val finishedChannel = NotificationChannel(
                FINISHED_CHANNEL,
                context.getString(R.string.notification_channel_finished),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts you when an Ankyra timer finishes"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 120, 250)
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannels(listOf(timerChannel, finishedChannel))
        }

        private fun vibrate(context: Context) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1)
            )
        }

        fun formatDuration(milliseconds: Long): String {
            val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }
    }
}
