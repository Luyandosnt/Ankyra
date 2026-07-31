package com.gecogames.ankyra.timer

import android.content.Context
import android.os.SystemClock

enum class TimerStatus {
    IDLE,
    RUNNING,
    PAUSED,
    FINISHED
}

data class TimerSnapshot(
    val status: TimerStatus,
    val totalMillis: Long,
    val remainingMillis: Long,
    val endElapsedRealtime: Long,
    val title: String?,
    val planDate: String?,
    val planCardId: String?,
    val startedAtWallClockMillis: Long
) {
    val elapsedMillis: Long
        get() = (totalMillis - remainingMillis).coerceAtLeast(0L)

    val isPlanTimer: Boolean
        get() = planDate != null && planCardId != null
}

object TimerStore {
    private const val PREFS = "ankyra_timer"
    private const val KEY_STATUS = "status"
    private const val KEY_TOTAL = "total"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_END_ELAPSED = "end_elapsed"
    private const val KEY_TITLE = "title"
    private const val KEY_PLAN_DATE = "plan_date"
    private const val KEY_PLAN_CARD_ID = "plan_card_id"
    private const val KEY_STARTED_WALL_CLOCK = "started_wall_clock"

    fun snapshot(context: Context): TimerSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = runCatching {
            TimerStatus.valueOf(prefs.getString(KEY_STATUS, TimerStatus.IDLE.name)!!)
        }.getOrDefault(TimerStatus.IDLE)
        val end = prefs.getLong(KEY_END_ELAPSED, 0L)
        val storedRemaining = prefs.getLong(KEY_REMAINING, 0L)
        val remaining = if (status == TimerStatus.RUNNING && end > 0L) {
            (end - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            storedRemaining.coerceAtLeast(0L)
        }
        return TimerSnapshot(
            status = if (status == TimerStatus.RUNNING && remaining == 0L) TimerStatus.FINISHED else status,
            totalMillis = prefs.getLong(KEY_TOTAL, 0L),
            remainingMillis = remaining,
            endElapsedRealtime = end,
            title = prefs.getString(KEY_TITLE, null),
            planDate = prefs.getString(KEY_PLAN_DATE, null),
            planCardId = prefs.getString(KEY_PLAN_CARD_ID, null),
            startedAtWallClockMillis = prefs.getLong(KEY_STARTED_WALL_CLOCK, 0L)
        )
    }

    fun start(
        context: Context,
        durationMillis: Long,
        title: String? = null,
        planDate: String? = null,
        planCardId: String? = null
    ): TimerSnapshot {
        val duration = durationMillis.coerceAtLeast(1_000L)
        val end = SystemClock.elapsedRealtime() + duration
        save(
            context = context,
            status = TimerStatus.RUNNING,
            total = duration,
            remaining = duration,
            end = end,
            title = title,
            planDate = planDate,
            planCardId = planCardId,
            startedAtWallClock = System.currentTimeMillis()
        )
        return snapshot(context)
    }

    fun pause(context: Context): TimerSnapshot {
        val current = snapshot(context)
        saveCurrent(context, current, TimerStatus.PAUSED, current.remainingMillis, 0L)
        return snapshot(context)
    }

    fun resume(context: Context): TimerSnapshot {
        val current = snapshot(context)
        val remaining = current.remainingMillis.coerceAtLeast(1_000L)
        val end = SystemClock.elapsedRealtime() + remaining
        saveCurrent(context, current, TimerStatus.RUNNING, remaining, end)
        return snapshot(context)
    }

    fun finish(context: Context): TimerSnapshot {
        val current = snapshot(context)
        saveCurrent(context, current, TimerStatus.FINISHED, 0L, 0L)
        return snapshot(context)
    }

    fun addTime(context: Context, extraMillis: Long): TimerSnapshot {
        val current = snapshot(context)
        val extra = extraMillis.coerceAtLeast(1_000L)
        val remaining = current.remainingMillis + extra
        val end = if (current.status == TimerStatus.RUNNING) {
            SystemClock.elapsedRealtime() + remaining
        } else {
            0L
        }
        save(
            context = context,
            status = current.status,
            total = current.totalMillis + extra,
            remaining = remaining,
            end = end,
            title = current.title,
            planDate = current.planDate,
            planCardId = current.planCardId,
            startedAtWallClock = current.startedAtWallClockMillis
        )
        return snapshot(context)
    }

    fun restart(context: Context): TimerSnapshot {
        val current = snapshot(context)
        return start(
            context = context,
            durationMillis = current.totalMillis,
            title = current.title,
            planDate = current.planDate,
            planCardId = current.planCardId
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun save(
        context: Context,
        status: TimerStatus,
        total: Long,
        remaining: Long,
        end: Long,
        title: String?,
        planDate: String?,
        planCardId: String?,
        startedAtWallClock: Long
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status.name)
            .putLong(KEY_TOTAL, total)
            .putLong(KEY_REMAINING, remaining)
            .putLong(KEY_END_ELAPSED, end)
            .putString(KEY_TITLE, title)
            .putString(KEY_PLAN_DATE, planDate)
            .putString(KEY_PLAN_CARD_ID, planCardId)
            .putLong(KEY_STARTED_WALL_CLOCK, startedAtWallClock)
            .apply()
    }

    private fun saveCurrent(
        context: Context,
        current: TimerSnapshot,
        status: TimerStatus,
        remaining: Long,
        end: Long
    ) {
        save(
            context = context,
            status = status,
            total = current.totalMillis,
            remaining = remaining,
            end = end,
            title = current.title,
            planDate = current.planDate,
            planCardId = current.planCardId,
            startedAtWallClock = current.startedAtWallClockMillis
        )
    }
}
