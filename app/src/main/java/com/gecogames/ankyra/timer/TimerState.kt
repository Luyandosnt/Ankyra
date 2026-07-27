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
    val endElapsedRealtime: Long
)

object TimerStore {
    private const val PREFS = "ankyra_timer"
    private const val KEY_STATUS = "status"
    private const val KEY_TOTAL = "total"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_END_ELAPSED = "end_elapsed"

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
            endElapsedRealtime = end
        )
    }

    fun start(context: Context, durationMillis: Long): TimerSnapshot {
        val duration = durationMillis.coerceAtLeast(1_000L)
        val end = SystemClock.elapsedRealtime() + duration
        save(context, TimerStatus.RUNNING, duration, duration, end)
        return snapshot(context)
    }

    fun pause(context: Context): TimerSnapshot {
        val current = snapshot(context)
        save(context, TimerStatus.PAUSED, current.totalMillis, current.remainingMillis, 0L)
        return snapshot(context)
    }

    fun resume(context: Context): TimerSnapshot {
        val current = snapshot(context)
        val remaining = current.remainingMillis.coerceAtLeast(1_000L)
        val end = SystemClock.elapsedRealtime() + remaining
        save(context, TimerStatus.RUNNING, current.totalMillis, remaining, end)
        return snapshot(context)
    }

    fun finish(context: Context): TimerSnapshot {
        val current = snapshot(context)
        save(context, TimerStatus.FINISHED, current.totalMillis, 0L, 0L)
        return snapshot(context)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun save(
        context: Context,
        status: TimerStatus,
        total: Long,
        remaining: Long,
        end: Long
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status.name)
            .putLong(KEY_TOTAL, total)
            .putLong(KEY_REMAINING, remaining)
            .putLong(KEY_END_ELAPSED, end)
            .apply()
    }
}
