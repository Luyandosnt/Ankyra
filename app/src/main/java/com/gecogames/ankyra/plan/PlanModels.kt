package com.gecogames.ankyra.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class PlanCardStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    SKIPPED
}

data class PlanGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val order: Int
)

data class PlanCard(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val plannedDurationMillis: Long,
    val notes: String = "",
    val groupId: String? = null,
    val order: Int,
    val status: PlanCardStatus = PlanCardStatus.PENDING,
    val baselineStartMillis: Long? = null,
    val baselineEndMillis: Long? = null,
    val actualStartMillis: Long? = null,
    val actualFinishMillis: Long? = null,
    val actualDurationMillis: Long? = null
)

data class PlanDay(
    val date: String,
    val startMinuteOfDay: Int = 9 * 60,
    val groups: List<PlanGroup> = emptyList(),
    val cards: List<PlanCard> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null
)

data class ScheduledPlanCard(
    val card: PlanCard,
    val plannedStartMillis: Long,
    val plannedEndMillis: Long,
    val adjustedStartMillis: Long,
    val adjustedEndMillis: Long
)

data class PlanSummary(
    val plannedMillis: Long,
    val executedMillis: Long,
    val completed: Int,
    val total: Int,
    val skipped: Int
) {
    val differenceMillis: Long
        get() = executedMillis - plannedMillis
}

fun PlanDay.startEpochMillis(zoneId: ZoneId = ZoneId.systemDefault()): Long {
    val dateValue = LocalDate.parse(date)
    return dateValue
        .atStartOfDay(zoneId)
        .plusMinutes(startMinuteOfDay.toLong())
        .toInstant()
        .toEpochMilli()
}

fun PlanDay.orderedCards(): List<PlanCard> = cards.sortedBy { it.order }

fun PlanDay.orderedGroups(): List<PlanGroup> = groups.sortedBy { it.order }

fun PlanDay.scheduledCards(zoneId: ZoneId = ZoneId.systemDefault()): List<ScheduledPlanCard> {
    var baselineCursor = startEpochMillis(zoneId)
    var adjustedCursor = startedAtMillis ?: baselineCursor

    return orderedCards().map { card ->
        val plannedStart = card.baselineStartMillis ?: baselineCursor
        val plannedEnd = card.baselineEndMillis ?: (plannedStart + card.plannedDurationMillis)
        baselineCursor = plannedEnd

        val adjustedStart = card.actualStartMillis ?: if (startedAtMillis != null) {
            adjustedCursor
        } else {
            plannedStart
        }
        val adjustedEnd = card.actualFinishMillis
            ?: (adjustedStart + card.plannedDurationMillis)
        adjustedCursor = adjustedEnd

        ScheduledPlanCard(
            card = card,
            plannedStartMillis = plannedStart,
            plannedEndMillis = plannedEnd,
            adjustedStartMillis = adjustedStart,
            adjustedEndMillis = adjustedEnd
        )
    }
}

fun PlanDay.summary(): PlanSummary = PlanSummary(
    plannedMillis = cards.sumOf { it.plannedDurationMillis },
    executedMillis = cards.sumOf { it.actualDurationMillis ?: 0L },
    completed = cards.count { it.status == PlanCardStatus.COMPLETED },
    total = cards.size,
    skipped = cards.count { it.status == PlanCardStatus.SKIPPED }
)

fun PlanDay.expectedFinishMillis(): Long =
    scheduledCards().lastOrNull()?.adjustedEndMillis ?: startEpochMillis()

fun PlanDay.isHistorical(nowMillis: Long = System.currentTimeMillis()): Boolean {
    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return LocalDate.parse(date).isBefore(today)
}

