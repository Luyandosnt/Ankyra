package com.gecogames.ankyra.plan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

object PlanStore {
    private const val PREFS = "ankyra_plans"
    private const val KEY_PLANS = "plans_json"
    private const val KEY_REVISION = "revision"
    private val lock = Any()

    fun revision(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_REVISION, 0L)

    fun get(context: Context, date: LocalDate): PlanDay? =
        all(context).firstOrNull { it.date == date.toString() }

    fun all(context: Context): List<PlanDay> = synchronized(lock) {
        readPlans(context).sortedByDescending { it.date }
    }

    fun past(context: Context, today: LocalDate = LocalDate.now()): List<PlanDay> =
        all(context).filter { LocalDate.parse(it.date).isBefore(today) }

    fun create(context: Context, date: LocalDate, startMinuteOfDay: Int = 9 * 60): PlanDay {
        val existing = get(context, date)
        if (existing != null) return existing
        val plan = PlanDay(date = date.toString(), startMinuteOfDay = startMinuteOfDay)
        save(context, plan)
        return plan
    }

    fun save(context: Context, plan: PlanDay): PlanDay = synchronized(lock) {
        val plans = readPlans(context).toMutableList()
        val normalized = normalize(plan)
        val index = plans.indexOfFirst { it.date == normalized.date }
        if (index >= 0) plans[index] = normalized else plans += normalized
        writePlans(context, plans)
        normalized
    }

    fun duplicate(context: Context, source: PlanDay, targetDate: LocalDate): PlanDay {
        val groupIds = source.groups.associate { it.id to UUID.randomUUID().toString() }
        val copy = PlanDay(
            date = targetDate.toString(),
            startMinuteOfDay = source.startMinuteOfDay,
            groups = source.orderedGroups().mapIndexed { index, group ->
                PlanGroup(
                    id = groupIds.getValue(group.id),
                    name = group.name,
                    order = index
                )
            },
            cards = source.orderedCards().mapIndexed { index, card ->
                PlanCard(
                    goal = card.goal,
                    plannedDurationMillis = card.plannedDurationMillis,
                    notes = card.notes,
                    groupId = card.groupId?.let(groupIds::get),
                    order = index
                )
            }
        )
        return save(context, copy)
    }

    fun startNextCard(
        context: Context,
        date: String,
        nowMillis: Long = System.currentTimeMillis()
    ): PlanCard? = synchronized(lock) {
        val plans = readPlans(context).toMutableList()
        val index = plans.indexOfFirst { it.date == date }
        if (index < 0) return@synchronized null

        var plan = plans[index]
        if (plan.startedAtMillis == null) {
            plan = freezeBaseline(plan, nowMillis)
        }
        val current = plan.cards.firstOrNull { it.status == PlanCardStatus.ACTIVE }
        val next = current ?: plan.orderedCards().firstOrNull {
            it.status == PlanCardStatus.PENDING
        } ?: return@synchronized null

        val updatedCard = next.copy(
            status = PlanCardStatus.ACTIVE,
            actualStartMillis = next.actualStartMillis ?: nowMillis,
            actualFinishMillis = null,
            actualDurationMillis = null
        )
        plan = plan.copy(cards = plan.cards.map { if (it.id == next.id) updatedCard else it })
        plans[index] = plan
        writePlans(context, plans)
        updatedCard
    }

    fun completeCard(
        context: Context,
        date: String?,
        cardId: String?,
        actualDurationMillis: Long,
        finishMillis: Long = System.currentTimeMillis()
    ) {
        mutateCard(context, date, cardId) { card ->
            card.copy(
                status = PlanCardStatus.COMPLETED,
                actualStartMillis = card.actualStartMillis
                    ?: (finishMillis - actualDurationMillis.coerceAtLeast(0L)),
                actualFinishMillis = finishMillis,
                actualDurationMillis = actualDurationMillis.coerceAtLeast(0L)
            )
        }
        finishPlanIfDone(context, date)
    }

    fun skipCard(
        context: Context,
        date: String?,
        cardId: String?,
        actualDurationMillis: Long = 0L,
        finishMillis: Long = System.currentTimeMillis()
    ) {
        mutateCard(context, date, cardId) { card ->
            card.copy(
                status = PlanCardStatus.SKIPPED,
                actualFinishMillis = finishMillis,
                actualDurationMillis = actualDurationMillis.coerceAtLeast(0L)
            )
        }
        finishPlanIfDone(context, date)
    }

    fun restartCard(
        context: Context,
        date: String?,
        cardId: String?,
        startMillis: Long = System.currentTimeMillis()
    ) {
        mutateCard(context, date, cardId) { card ->
            card.copy(
                status = PlanCardStatus.ACTIVE,
                actualStartMillis = startMillis,
                actualFinishMillis = null,
                actualDurationMillis = null
            )
        }
    }

    private fun mutateCard(
        context: Context,
        date: String?,
        cardId: String?,
        transform: (PlanCard) -> PlanCard
    ) {
        if (date == null || cardId == null) return
        synchronized(lock) {
            val plans = readPlans(context).toMutableList()
            val planIndex = plans.indexOfFirst { it.date == date }
            if (planIndex < 0) return@synchronized
            val plan = plans[planIndex]
            if (plan.cards.none { it.id == cardId }) return@synchronized
            plans[planIndex] = plan.copy(
                cards = plan.cards.map { if (it.id == cardId) transform(it) else it }
            )
            writePlans(context, plans)
        }
    }

    private fun finishPlanIfDone(context: Context, date: String?) {
        if (date == null) return
        synchronized(lock) {
            val plans = readPlans(context).toMutableList()
            val index = plans.indexOfFirst { it.date == date }
            if (index < 0) return@synchronized
            val plan = plans[index]
            val done = plan.cards.isNotEmpty() && plan.cards.all {
                it.status == PlanCardStatus.COMPLETED || it.status == PlanCardStatus.SKIPPED
            }
            if (done && plan.finishedAtMillis == null) {
                plans[index] = plan.copy(finishedAtMillis = System.currentTimeMillis())
                writePlans(context, plans)
            }
        }
    }

    private fun freezeBaseline(plan: PlanDay, nowMillis: Long): PlanDay {
        var cursor = plan.startEpochMillis()
        val cards = plan.orderedCards().map { card ->
            val start = cursor
            val end = start + card.plannedDurationMillis
            cursor = end
            card.copy(baselineStartMillis = start, baselineEndMillis = end)
        }
        return plan.copy(
            cards = cards,
            startedAtMillis = nowMillis
        )
    }

    private fun normalize(plan: PlanDay): PlanDay {
        val referencedGroupIds = plan.cards.mapNotNull { it.groupId }.toSet()
        val groups = plan.orderedGroups()
            .filter { it.id in referencedGroupIds }
            .mapIndexed { index, group -> group.copy(order = index) }
        val validGroupIds = groups.map { it.id }.toSet()
        val cards = plan.orderedCards().mapIndexed { index, card ->
            card.copy(
                order = index,
                plannedDurationMillis = card.plannedDurationMillis.coerceAtLeast(60_000L),
                groupId = card.groupId?.takeIf(validGroupIds::contains)
            )
        }
        return plan.copy(
            startMinuteOfDay = plan.startMinuteOfDay.coerceIn(0, (24 * 60) - 1),
            groups = groups,
            cards = cards
        )
    }

    private fun readPlans(context: Context): List<PlanDay> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PLANS, "[]")
            .orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getJSONObject(index).toPlanDay())
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writePlans(context: Context, plans: List<PlanDay>) {
        val array = JSONArray()
        plans.sortedBy { it.date }.forEach { array.put(it.toJson()) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nextRevision = prefs.getLong(KEY_REVISION, 0L) + 1L
        prefs.edit()
            .putString(KEY_PLANS, array.toString())
            .putLong(KEY_REVISION, nextRevision)
            .commit()
    }

    private fun PlanDay.toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("startMinuteOfDay", startMinuteOfDay)
        put("createdAtMillis", createdAtMillis)
        putNullable("startedAtMillis", startedAtMillis)
        putNullable("finishedAtMillis", finishedAtMillis)
        put("groups", JSONArray().apply { groups.forEach { put(it.toJson()) } })
        put("cards", JSONArray().apply { cards.forEach { put(it.toJson()) } })
    }

    private fun PlanGroup.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("order", order)
    }

    private fun PlanCard.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("goal", goal)
        put("plannedDurationMillis", plannedDurationMillis)
        put("notes", notes)
        putNullable("groupId", groupId)
        put("order", order)
        put("status", status.name)
        putNullable("baselineStartMillis", baselineStartMillis)
        putNullable("baselineEndMillis", baselineEndMillis)
        putNullable("actualStartMillis", actualStartMillis)
        putNullable("actualFinishMillis", actualFinishMillis)
        putNullable("actualDurationMillis", actualDurationMillis)
    }

    private fun JSONObject.toPlanDay(): PlanDay = PlanDay(
        date = getString("date"),
        startMinuteOfDay = optInt("startMinuteOfDay", 9 * 60),
        groups = optJSONArray("groups").toGroups(),
        cards = optJSONArray("cards").toCards(),
        createdAtMillis = optLong("createdAtMillis", System.currentTimeMillis()),
        startedAtMillis = nullableLong("startedAtMillis"),
        finishedAtMillis = nullableLong("finishedAtMillis")
    )

    private fun JSONArray?.toGroups(): List<PlanGroup> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    PlanGroup(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        order = item.optInt("order", index)
                    )
                )
            }
        }
    }

    private fun JSONArray?.toCards(): List<PlanCard> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    PlanCard(
                        id = item.getString("id"),
                        goal = item.getString("goal"),
                        plannedDurationMillis = item.optLong("plannedDurationMillis", 30 * 60_000L),
                        notes = item.optString("notes", ""),
                        groupId = item.nullableString("groupId"),
                        order = item.optInt("order", index),
                        status = runCatching {
                            PlanCardStatus.valueOf(
                                item.optString("status", PlanCardStatus.PENDING.name)
                            )
                        }.getOrDefault(PlanCardStatus.PENDING),
                        baselineStartMillis = item.nullableLong("baselineStartMillis"),
                        baselineEndMillis = item.nullableLong("baselineEndMillis"),
                        actualStartMillis = item.nullableLong("actualStartMillis"),
                        actualFinishMillis = item.nullableLong("actualFinishMillis"),
                        actualDurationMillis = item.nullableLong("actualDurationMillis")
                    )
                )
            }
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.nullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
