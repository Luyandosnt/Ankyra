package com.gecogames.ankyra.plan

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gecogames.ankyra.timer.TimerSnapshot
import com.gecogames.ankyra.timer.TimerStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PlanBlack = Color(0xFF000000)
private val PlanSurface = Color(0xFF1B1B1E)
private val PlanSurfaceRaised = Color(0xFF2D2D30)
private val PlanMuted = Color(0xFF96969D)
private val PlanPurple = Color(0xFF635BEF)
private val PlanGreen = Color(0xFF58C98B)
private val PlanOrange = Color(0xFFF0A45D)

@Composable
fun PlanScreen(
    timerSnapshot: TimerSnapshot,
    onStartPlan: (String) -> Unit,
    onOpenTimer: () -> Unit
) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var showHistory by remember { mutableStateOf(false) }
    var revision by remember { mutableLongStateOf(PlanStore.revision(context)) }
    var editingCard by remember { mutableStateOf<PlanCard?>(null) }
    var showCardEditor by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            revision = PlanStore.revision(context)
            delay(500L)
        }
    }

    val plan = remember(selectedDate, revision) { PlanStore.get(context, selectedDate) }
    val refresh = { revision = PlanStore.revision(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PlanBlack)
            .padding(horizontal = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("ANKYRA", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(if (showHistory) "Plan history" else "Build your day", color = PlanMuted, fontSize = 12.sp)
            }
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "Plan" else "History", color = PlanPurple)
            }
        }

        if (showHistory) {
            HistoryList(
                plans = remember(revision) { PlanStore.past(context) },
                onSelect = {
                    selectedDate = LocalDate.parse(it.date)
                    showHistory = false
                }
            )
        } else {
            DateNavigation(
                date = selectedDate,
                onPrevious = { selectedDate = selectedDate.minusDays(1) },
                onNext = { selectedDate = selectedDate.plusDays(1) },
                onToday = { selectedDate = today }
            )

            if (plan == null) {
                EmptyPlan(
                    date = selectedDate,
                    onCreate = {
                        PlanStore.create(context, selectedDate)
                        refresh()
                    }
                )
            } else {
                PlanDetail(
                    plan = plan,
                    readOnly = selectedDate.isBefore(today),
                    timerSnapshot = timerSnapshot,
                    onSave = {
                        PlanStore.save(context, it)
                        refresh()
                    },
                    onAddCard = {
                        editingCard = null
                        showCardEditor = true
                    },
                    onEditCard = {
                        editingCard = it
                        showCardEditor = true
                    },
                    onStartPlan = { onStartPlan(plan.date) },
                    onOpenTimer = onOpenTimer,
                    onDuplicate = {
                        showDuplicatePicker(context, selectedDate) { target ->
                            PlanStore.duplicate(context, plan, target)
                            selectedDate = target
                            refresh()
                        }
                    }
                )
            }
        }
    }

    if (showCardEditor && plan != null) {
        CardEditorDialog(
            plan = plan,
            card = editingCard,
            onDismiss = { showCardEditor = false },
            onSave = { goal, durationMinutes, notes, groupName, firstCardStartMinute ->
                var groups = plan.groups
                val cleanGroup = groupName.trim()
                val groupId = if (cleanGroup.isBlank()) {
                    null
                } else {
                    val existing = groups.firstOrNull { it.name.equals(cleanGroup, true) }
                    if (existing != null) {
                        existing.id
                    } else {
                        val newGroup = PlanGroup(name = cleanGroup, order = groups.size)
                        groups = groups + newGroup
                        newGroup.id
                    }
                }
                val updatedCards = if (editingCard == null) {
                    plan.cards + PlanCard(
                        goal = goal.trim(),
                        plannedDurationMillis = durationMinutes * 60_000L,
                        notes = notes.trim(),
                        groupId = groupId,
                        order = plan.cards.size
                    )
                } else {
                    plan.cards.map { card ->
                        if (card.id == editingCard!!.id) {
                            card.copy(
                                goal = goal.trim(),
                                plannedDurationMillis = durationMinutes * 60_000L,
                                notes = notes.trim(),
                                groupId = groupId
                            )
                        } else {
                            card
                        }
                    }
                }
                PlanStore.save(
                    context,
                    plan.copy(
                        startMinuteOfDay = firstCardStartMinute ?: plan.startMinuteOfDay,
                        groups = groups,
                        cards = updatedCards
                    )
                )
                showCardEditor = false
                refresh()
            }
        )
    }
}

@Composable
private fun DateNavigation(
    date: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPrevious) { Text("‹", color = Color.White, fontSize = 28.sp) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            TextButton(onClick = onToday) {
                Text(if (date == LocalDate.now()) "Today" else "Return to today", color = PlanPurple)
            }
        }
        TextButton(onClick = onNext) { Text("›", color = Color.White, fontSize = 28.sp) }
    }
}

@Composable
private fun EmptyPlan(date: LocalDate, onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No plan yet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (date == LocalDate.now()) "Build today out of blocks of time."
            else "Create a plan for this day.",
            color = PlanMuted
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            colors = ButtonDefaults.buttonColors(containerColor = PlanPurple),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("Create plan")
        }
    }
}

@Composable
private fun PlanDetail(
    plan: PlanDay,
    readOnly: Boolean,
    timerSnapshot: TimerSnapshot,
    onSave: (PlanDay) -> Unit,
    onAddCard: () -> Unit,
    onEditCard: (PlanCard) -> Unit,
    onStartPlan: () -> Unit,
    onOpenTimer: () -> Unit,
    onDuplicate: () -> Unit
) {
    val scheduled = plan.scheduledCards()
    val summary = plan.summary()
    val activeCard = plan.cards.firstOrNull { it.status == PlanCardStatus.ACTIVE }
    val planTimerRunning = timerSnapshot.isPlanTimer &&
        timerSnapshot.planDate == plan.date &&
        (timerSnapshot.status == TimerStatus.RUNNING || timerSnapshot.status == TimerStatus.PAUSED)
    val terminal = plan.cards.isNotEmpty() && plan.cards.all {
        it.status == PlanCardStatus.COMPLETED || it.status == PlanCardStatus.SKIPPED
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (plan.cards.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Starts ${formatMinuteOfDay(plan.startMinuteOfDay)}",
                        color = PlanMuted,
                        fontSize = 13.sp
                    )
                    Text(
                        "${formatDuration(summary.plannedMillis)} planned · ends ${formatClock(plan.expectedFinishMillis())}",
                        color = PlanMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }

        items(scheduled, key = { it.card.id }) { scheduledCard ->
            PlanCardView(
                scheduled = scheduledCard,
                groupName = plan.groups.firstOrNull { it.id == scheduledCard.card.groupId }?.name,
                readOnly = readOnly,
                onEdit = { onEditCard(scheduledCard.card) },
                onMoveUp = { onSave(moveCard(plan, scheduledCard.card.id, -1)) },
                onMoveDown = { onSave(moveCard(plan, scheduledCard.card.id, 1)) },
                onDelete = {
                    onSave(plan.copy(cards = plan.cards.filterNot { it.id == scheduledCard.card.id }))
                }
            )
        }

        if (plan.groups.any { group -> plan.cards.any { it.groupId == group.id } }) {
            item {
                GroupTotals(
                    plan = plan,
                    readOnly = readOnly,
                    onMove = { groupId, direction ->
                        onSave(moveGroup(plan, groupId, direction))
                    }
                )
            }
        }

        if (!readOnly && !terminal) {
            item {
                OutlinedButton(
                    onClick = onAddCard,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("+ Add Card", color = Color.White, fontSize = 16.sp)
                }
            }
        }

        if (terminal || readOnly) {
            item {
                DailySummary(summary)
            }
        }

        item {
            Column {
                if (!readOnly && plan.cards.isNotEmpty() && !terminal) {
                    Button(
                        onClick = if (planTimerRunning) onOpenTimer else onStartPlan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PlanPurple),
                        shape = RoundedCornerShape(30.dp)
                    ) {
                        Text(
                            if (planTimerRunning) "Open active timer"
                            else if (activeCard != null) "Resume Plan"
                            else "Start Plan",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = onDuplicate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Copy plan to another date", color = Color.White)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GroupTotals(
    plan: PlanDay,
    readOnly: Boolean,
    onMove: (String, Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PlanSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("GROUPS", color = PlanMuted, fontSize = 11.sp, letterSpacing = 1.4.sp)
            plan.orderedGroups().forEach { group ->
                val total = plan.cards.filter { it.groupId == group.id }.sumOf { it.plannedDurationMillis }
                if (total > 0L) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(group.name, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDuration(total), color = PlanMuted)
                            if (!readOnly) {
                                Spacer(Modifier.width(6.dp))
                                TextButton(onClick = { onMove(group.id, -1) }) {
                                    Text("↑", color = PlanMuted)
                                }
                                TextButton(onClick = { onMove(group.id, 1) }) {
                                    Text("↓", color = PlanMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCardView(
    scheduled: ScheduledPlanCard,
    groupName: String?,
    readOnly: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val card = scheduled.card
    val statusColor = when (card.status) {
        PlanCardStatus.ACTIVE -> PlanPurple
        PlanCardStatus.COMPLETED -> PlanGreen
        PlanCardStatus.SKIPPED -> PlanOrange
        PlanCardStatus.PENDING -> PlanMuted
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = PlanSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = !readOnly && card.status == PlanCardStatus.PENDING,
                onClick = onEdit
            )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    if (groupName != null) {
                        Text(groupName.uppercase(Locale.getDefault()), color = PlanPurple, fontSize = 10.sp)
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(card.goal, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (card.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(card.notes, color = PlanMuted, fontSize = 13.sp)
                    }
                }
                Text(card.status.name.lowercase().replaceFirstChar(Char::uppercase), color = statusColor, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${formatClock(scheduled.adjustedStartMillis)}–${formatClock(scheduled.adjustedEndMillis)}",
                    color = Color.White
                )
                Text(formatDuration(card.plannedDurationMillis), color = PlanMuted)
            }
            if (card.actualDurationMillis != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Actual ${formatDuration(card.actualDurationMillis)}" +
                        card.actualStartMillis?.let { " · ${formatClock(it)}" }.orEmpty() +
                        card.actualFinishMillis?.let { "–${formatClock(it)}" }.orEmpty(),
                    color = statusColor,
                    fontSize = 12.sp
                )
                if (
                    card.baselineStartMillis != null &&
                    scheduled.adjustedStartMillis != scheduled.plannedStartMillis
                ) {
                    Text(
                        "Originally ${formatClock(scheduled.plannedStartMillis)}–${formatClock(scheduled.plannedEndMillis)}",
                        color = PlanMuted,
                        fontSize = 11.sp
                    )
                }
            }
            if (!readOnly && card.status == PlanCardStatus.PENDING) {
                Spacer(Modifier.height(6.dp))
                Text("Tap card to edit goal, hours or minutes", color = PlanMuted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = PlanSurfaceRaised)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onMoveUp) { Text("↑", color = PlanMuted, fontSize = 20.sp) }
                    TextButton(onClick = onMoveDown) { Text("↓", color = PlanMuted, fontSize = 20.sp) }
                    TextButton(onClick = onDelete) { Text("Delete", color = PlanOrange) }
                }
            }
        }
    }
}

@Composable
private fun DailySummary(summary: PlanSummary) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PlanSurfaceRaised),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("DAILY SUMMARY", color = Color.White, fontWeight = FontWeight.Bold)
            SummaryRow("Planned", formatDuration(summary.plannedMillis))
            SummaryRow("Executed", formatDuration(summary.executedMillis))
            SummaryRow("Completed", "${summary.completed} / ${summary.total}")
            SummaryRow("Skipped", summary.skipped.toString())
            SummaryRow("Difference", formatSignedDuration(summary.differenceMillis))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = PlanMuted)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryList(plans: List<PlanDay>, onSelect: (PlanDay) -> Unit) {
    if (plans.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No plan history yet", color = Color.White, fontSize = 22.sp)
            Text("Finished days will stay here.", color = PlanMuted)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(plans, key = { it.date }) { plan ->
            val summary = plan.summary()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(plan) },
                colors = CardDefaults.cardColors(containerColor = PlanSurface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        LocalDate.parse(plan.date).format(
                            DateTimeFormatter.ofPattern("dd MMM").withLocale(Locale.getDefault())
                        ).uppercase(Locale.getDefault()),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Planned: ${formatDuration(summary.plannedMillis)}", color = PlanMuted)
                    Text("Executed: ${formatDuration(summary.executedMillis)}", color = PlanMuted)
                    Text("${summary.completed}/${summary.total} cards completed", color = PlanGreen)
                }
            }
        }
    }
}

@Composable
private fun CardEditorDialog(
    plan: PlanDay,
    card: PlanCard?,
    onDismiss: () -> Unit,
    onSave: (String, Long, String, String, Int?) -> Unit
) {
    val context = LocalContext.current
    val firstCard = plan.orderedCards().firstOrNull()
    val controlsPlanStart = plan.startedAtMillis == null &&
        (plan.cards.isEmpty() || firstCard?.id == card?.id)
    var goal by remember(card?.id) { mutableStateOf(card?.goal.orEmpty()) }
    val initialMinutes = (card?.plannedDurationMillis ?: 30 * 60_000L) / 60_000L
    var hours by remember(card?.id) { mutableStateOf((initialMinutes / 60L).toString()) }
    var minutes by remember(card?.id) { mutableStateOf((initialMinutes % 60L).toString()) }
    var notes by remember(card?.id) { mutableStateOf(card?.notes.orEmpty()) }
    var group by remember(card?.id) {
        mutableStateOf(plan.groups.firstOrNull { it.id == card?.groupId }?.name.orEmpty())
    }
    var startMinute by remember(card?.id) { mutableStateOf(plan.startMinuteOfDay) }
    val duration = (hours.toLongOrNull() ?: 0L) * 60L + (minutes.toLongOrNull() ?: 0L)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PlanSurface,
        title = { Text(if (card == null) "Add Card" else "Edit Card", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("Goal") },
                    singleLine = true
                )
                if (controlsPlanStart) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Start time", color = PlanMuted, fontSize = 12.sp)
                            Text(
                                formatMinuteOfDay(startMinute),
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute -> startMinute = hour * 60 + minute },
                                    startMinute / 60,
                                    startMinute % 60,
                                    true
                                ).show()
                            }
                        ) {
                            Text("Change", color = Color.White)
                        }
                    }
                }
                Text("Duration", color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hours") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = {
                            minutes = it.filter(Char::isDigit).take(2).let { value ->
                                value.toIntOrNull()?.coerceAtMost(59)?.toString() ?: value
                            }
                        },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(15L, 30L, 60L, 120L).forEach { preset ->
                        TextButton(
                            onClick = {
                                hours = (preset / 60L).toString()
                                minutes = (preset % 60L).toString()
                            }
                        ) {
                            Text(if (preset < 60L) "${preset}m" else "${preset / 60L}h", color = PlanPurple)
                        }
                    }
                }
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("Group (optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2
                )
                Text(
                    "An alarm and vibration will play when this card finishes.",
                    color = PlanMuted,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        goal,
                        duration,
                        notes,
                        group,
                        startMinute.takeIf { controlsPlanStart }
                    )
                },
                enabled = goal.isNotBlank() && duration > 0L,
                colors = ButtonDefaults.buttonColors(containerColor = PlanPurple)
            ) {
                Text(if (card == null) "Add next" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = PlanMuted) }
        }
    )
}

private fun moveCard(plan: PlanDay, cardId: String, direction: Int): PlanDay {
    val cards = plan.orderedCards().toMutableList()
    val from = cards.indexOfFirst { it.id == cardId }
    val to = (from + direction).coerceIn(cards.indices)
    if (from >= 0 && from != to) {
        val moved = cards.removeAt(from)
        cards.add(to, moved)
    }
    return plan.copy(cards = cards.mapIndexed { index, card -> card.copy(order = index) })
}

private fun moveGroup(plan: PlanDay, groupId: String, direction: Int): PlanDay {
    val groups = plan.orderedGroups().toMutableList()
    val from = groups.indexOfFirst { it.id == groupId }
    if (from < 0) return plan
    val to = (from + direction).coerceIn(groups.indices)
    if (from == to) return plan
    val moved = groups.removeAt(from)
    groups.add(to, moved)
    val normalizedGroups = groups.mapIndexed { index, group -> group.copy(order = index) }
    val groupedCards = normalizedGroups.flatMap { group ->
        plan.orderedCards().filter { it.groupId == group.id }
    }
    val ungroupedCards = plan.orderedCards().filter { it.groupId == null }
    val cards = (groupedCards + ungroupedCards).mapIndexed { index, card ->
        card.copy(order = index)
    }
    return plan.copy(groups = normalizedGroups, cards = cards)
}

private fun showDuplicatePicker(
    context: android.content.Context,
    initialDate: LocalDate,
    onSelected: (LocalDate) -> Unit
) {
    DatePickerDialog(
        context,
        { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) },
        initialDate.year,
        initialDate.monthValue - 1,
        initialDate.dayOfMonth
    ).show()
}

private fun formatMinuteOfDay(value: Int): String =
    "%02d:%02d".format(Locale.getDefault(), value / 60, value % 60)

private fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun formatDuration(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun formatSignedDuration(millis: Long): String {
    val prefix = if (millis >= 0L) "+" else "−"
    return prefix + formatDuration(kotlin.math.abs(millis))
}
