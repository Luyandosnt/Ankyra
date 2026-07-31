package com.gecogames.ankyra.plan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gecogames.ankyra.timer.TimerService
import java.time.LocalDate

object PlanScheduler {
    private const val EXTRA_PLAN_DATE = "plan_date"

    fun schedule(context: Context, plan: PlanDay) {
        val pendingIntent = pendingIntent(context, plan.date)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(pendingIntent)

        if (
            plan.cards.isEmpty() ||
            plan.startedAtMillis != null ||
            LocalDate.parse(plan.date).isBefore(LocalDate.now())
        ) return
        val firstPending = plan.scheduledCards().firstOrNull {
            it.card.status == PlanCardStatus.PENDING
        } ?: return
        val triggerAt = firstPending.plannedStartMillis.coerceAtLeast(
            System.currentTimeMillis() + 750L
        )

        runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }.onFailure {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    fun rescheduleAll(context: Context) {
        PlanStore.all(context).forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, date: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            date.hashCode(),
            Intent(context, PlanStartReceiver::class.java)
                .setData(Uri.parse("ankyra://plan-start/$date"))
                .putExtra(EXTRA_PLAN_DATE, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun planDate(intent: Intent): String? = intent.getStringExtra(EXTRA_PLAN_DATE)
}

class PlanStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = PlanScheduler.planDate(intent) ?: return
        TimerService.startNextPlanCard(context, date, announce = true)
    }
}

class PlanBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PlanScheduler.rescheduleAll(context)
        }
    }
}
