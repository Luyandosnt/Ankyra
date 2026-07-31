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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gecogames.ankyra.MainActivity
import com.gecogames.ankyra.R
import com.gecogames.ankyra.plan.PlanStore
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
                val snapshot = TimerStore.start(
                    context = this,
                    durationMillis = duration,
                    title = intent.getStringExtra(EXTRA_TITLE),
                    planDate = intent.getStringExtra(EXTRA_PLAN_DATE),
                    planCardId = intent.getStringExtra(EXTRA_PLAN_CARD_ID)
                )
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

            ACTION_ADD_TIME -> {
                cancelAlarm(this)
                val snapshot = TimerStore.addTime(
                    this,
                    intent.getLongExtra(EXTRA_ADD_MILLIS, DEFAULT_ADD_TIME_MILLIS)
                )
                if (snapshot.status == TimerStatus.RUNNING) {
                    scheduleAlarm(this, snapshot.remainingMillis)
                }
                showForeground(snapshot)
            }

            ACTION_RESTART -> {
                cancelAlarm(this)
                val current = TimerStore.snapshot(this)
                PlanStore.restartCard(this, current.planDate, current.planCardId)
                val snapshot = TimerStore.restart(this)
                scheduleAlarm(this, snapshot.remainingMillis)
                showForeground(snapshot)
            }

            ACTION_FINISH_EARLY -> finishCurrentPlanCard(skipped = false)

            ACTION_SKIP -> finishCurrentPlanCard(skipped = true)

            ACTION_CANCEL -> {
                cancelAlarm(this)
                val current = TimerStore.snapshot(this)
                if (current.isPlanTimer) {
                    PlanStore.skipCard(
                        context = this,
                        date = current.planDate,
                        cardId = current.planCardId,
                        actualDurationMillis = current.elapsedMillis
                    )
                }
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

    private fun finishCurrentPlanCard(skipped: Boolean) {
        cancelAlarm(this)
        val current = TimerStore.snapshot(this)
        if (skipped) {
            PlanStore.skipCard(
                context = this,
                date = current.planDate,
                cardId = current.planCardId,
                actualDurationMillis = current.elapsedMillis
            )
        } else {
            PlanStore.completeCard(
                context = this,
                date = current.planDate,
                cardId = current.planCardId,
                actualDurationMillis = current.elapsedMillis
            )
        }
        TimerStore.finish(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val startedNext = current.planDate?.let {
            startNextPlanCard(this, it, announce = false)
        } ?: false
        if (!startedNext) stopSelf()
    }

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
        const val ACTION_ADD_TIME = "com.gecogames.ankyra.action.ADD_TIME"
        const val ACTION_FINISH_EARLY = "com.gecogames.ankyra.action.FINISH_EARLY"
        const val ACTION_SKIP = "com.gecogames.ankyra.action.SKIP"
        const val ACTION_RESTART = "com.gecogames.ankyra.action.RESTART"
        const val EXTRA_DURATION = "duration_millis"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAN_DATE = "plan_date"
        const val EXTRA_PLAN_CARD_ID = "plan_card_id"
        const val EXTRA_ADD_MILLIS = "add_millis"
        const val DEFAULT_ADD_TIME_MILLIS = 5 * 60_000L

        // A new channel ID upgrades existing installations from the old low-importance
        // channel, whose importance cannot be raised after the channel is created.
        private const val TIMER_CHANNEL = "ankyra_live_timer_v2"
        private const val FINISHED_CHANNEL = "ankyra_finished_timer"
        private const val PLAN_START_CHANNEL = "ankyra_plan_start_alarm"
        private const val NOTIFICATION_ID = 4101
        private const val FINISHED_NOTIFICATION_ID = 4102
        private const val PLAN_START_NOTIFICATION_ID = 4103
        private const val ALARM_REQUEST_CODE = 4201

        fun start(
            context: Context,
            durationMillis: Long,
            title: String? = null,
            planDate: String? = null,
            planCardId: String? = null
        ) {
            val intent = Intent(context, TimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DURATION, durationMillis)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PLAN_DATE, planDate)
                .putExtra(EXTRA_PLAN_CARD_ID, planCardId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startNextPlanCard(
            context: Context,
            planDate: String,
            announce: Boolean
        ): Boolean {
            val current = TimerStore.snapshot(context)
            if (current.status == TimerStatus.RUNNING || current.status == TimerStatus.PAUSED) {
                return false
            }
            val card = PlanStore.startNextCard(context, planDate) ?: return false
            if (announce) announcePlanCardStart(context, card.goal)
            start(
                context = context,
                durationMillis = card.plannedDurationMillis,
                title = card.goal,
                planDate = planDate,
                planCardId = card.id
            )
            return true
        }

        fun sendAction(context: Context, action: String, addMillis: Long? = null) {
            val intent = Intent(context, TimerService::class.java).setAction(action)
            if (addMillis != null) intent.putExtra(EXTRA_ADD_MILLIS, addMillis)
            ContextCompat.startForegroundService(
                context,
                intent
            )
        }

        fun canPostLiveUpdates(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 36) return true
            return context.getSystemService(NotificationManager::class.java)
                .canPostPromotedNotifications()
        }

        fun openLiveUpdateSettings(context: Context) {
            if (Build.VERSION.SDK_INT < 36) return
            val intent = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        fun completeTimer(context: Context) {
            createChannels(context)
            cancelAlarm(context)
            val snapshot = TimerStore.snapshot(context)
            PlanStore.completeCard(
                context = context,
                date = snapshot.planDate,
                cardId = snapshot.planCardId,
                actualDurationMillis = snapshot.elapsedMillis
            )
            TimerStore.finish(context)

            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(
                    FINISHED_NOTIFICATION_ID,
                    buildFinishedNotification(context, snapshot.title)
                )
            }
            vibrate(context)
            val startedNext = snapshot.planDate?.let {
                startNextPlanCard(context, it, announce = false)
            } ?: false
            if (!startedNext) {
                context.stopService(Intent(context, TimerService::class.java))
            }
        }

        private fun announcePlanCardStart(context: Context, goal: String) {
            createChannels(context)
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val openApp = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                NotificationManagerCompat.from(context).notify(
                    PLAN_START_NOTIFICATION_ID,
                    NotificationCompat.Builder(context, PLAN_START_CHANNEL)
                        .setSmallIcon(R.drawable.ic_timer)
                        .setColor(Color.rgb(99, 91, 239))
                        .setContentTitle("Plan card starting now")
                        .setContentText(goal)
                        .setContentIntent(openApp)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setTimeoutAfter(15_000L)
                        .build()
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
            val finishIntent = servicePendingIntent(context, ACTION_FINISH_EARLY, 3)
            val skipIntent = servicePendingIntent(context, ACTION_SKIP, 4)
            val elapsed = (snapshot.totalMillis - remaining).coerceAtLeast(0L)
            val progressPercent = if (snapshot.totalMillis > 0L) {
                ((elapsed * 100L) / snapshot.totalMillis).coerceIn(0L, 100L).toInt()
            } else {
                0
            }
            val progressStyle = NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(progressPercent)

            val builder = NotificationCompat.Builder(context, TIMER_CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setColor(Color.rgb(99, 91, 239))
                .setContentTitle(snapshot.title ?: "Ankyra Timer")
                .setContentText(
                    if (running) {
                        "${formatDuration(remaining)} remaining"
                    } else {
                        "${formatDuration(remaining)} remaining · Paused"
                    }
                )
                .setSubText("${formatCompactDuration(snapshot.totalMillis)} · ends ${formatEndTime(endWallClock)}")
                .setStyle(progressStyle)
                .setContentIntent(openApp)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(formatChipDuration(remaining))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
            if (snapshot.isPlanTimer) {
                builder
                    .addAction(android.R.drawable.checkbox_on_background, "Finish", finishIntent)
                    .addAction(android.R.drawable.ic_media_next, "Skip", skipIntent)
            } else {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    cancelIntent
                )
            }
            return builder.build()
        }

        private fun buildFinishedNotification(context: Context, title: String?): Notification {
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, FINISHED_CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(if (title != null) "Completed: $title" else "Timer complete")
                .setContentText(
                    if (title != null) {
                        "Ankyra will continue the Plan automatically."
                    } else {
                        "Your Ankyra timer has finished."
                    }
                )
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
                "Live timer",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows Ankyra at the top of notifications and on the lock screen"
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
            val planStartChannel = NotificationChannel(
                PLAN_START_CHANNEL,
                "Plan start alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Rings and vibrates when an Ankyra plan card starts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 150, 350, 150, 500)
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannels(
                listOf(timerChannel, finishedChannel, planStartChannel)
            )
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

        private fun formatChipDuration(milliseconds: Long): String {
            val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours >= 10L) {
                String.format(Locale.getDefault(), "%dh%02dm", hours, minutes)
            } else if (hours > 0L) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }.take(7)
        }

        private fun formatCompactDuration(milliseconds: Long): String {
            val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return when {
                hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
                hours > 0L -> "${hours}h"
                else -> "${minutes}m"
            }
        }

        private fun formatEndTime(epochMillis: Long): String =
            java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(java.util.Date(epochMillis))
    }
}
