package com.gecogames.ankyra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gecogames.ankyra.plan.PlanScreen
import com.gecogames.ankyra.plan.PlanScheduler
import com.gecogames.ankyra.plan.PlanStore
import com.gecogames.ankyra.timer.TimerService
import com.gecogames.ankyra.timer.TimerSnapshot
import com.gecogames.ankyra.timer.TimerStatus
import com.gecogames.ankyra.timer.TimerStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

private val Black = Color(0xFF000000)
private val SurfaceGrey = Color(0xFF2D2D30)
private val Muted = Color(0xFF929298)
private val Purple = Color(0xFF635BEF)
private val AnkyraColors = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    background = Black,
    onBackground = Color.White,
    surface = Color(0xFF1B1B1E),
    onSurface = Color.White,
    surfaceVariant = SurfaceGrey,
    onSurfaceVariant = Muted,
    outline = Color(0xFF85858F)
)

private enum class AppTab {
    TIMER,
    PLAN
}

private data class TimerLaunch(
    val durationMillis: Long? = null,
    val planDate: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AnkyraColors) {
                Surface(color = Black, modifier = Modifier.fillMaxSize()) {
                    AnkyraApp()
                }
            }
        }
    }
}

@Composable
private fun AnkyraApp() {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(TimerStore.snapshot(context)) }
    var selectedTab by remember {
        mutableStateOf(
            if (snapshot.isPlanTimer && snapshot.status != TimerStatus.IDLE) AppTab.PLAN
            else AppTab.TIMER
        )
    }
    var pendingLaunch by remember { mutableStateOf<TimerLaunch?>(null) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val onboardingPreferences = remember {
        context.getSharedPreferences("ankyra_onboarding", android.content.Context.MODE_PRIVATE)
    }
    var showOnboarding by remember {
        mutableStateOf(!onboardingPreferences.getBoolean("notification_setup_complete", false))
    }
    var onboardingPermissionFlow by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (onboardingPermissionFlow) {
            onboardingPermissionFlow = false
            if (granted && Build.VERSION.SDK_INT >= 36 && !TimerService.canPostLiveUpdates(context)) {
                TimerService.openLiveUpdateSettings(context)
            } else if (!granted) {
                permissionMessage = "Notifications are required for Plan alarms and lock-screen timers."
            }
        } else {
            val launch = pendingLaunch
            if (granted && launch != null) {
                executeTimerLaunch(context, launch)
                pendingLaunch = null
            } else if (!granted) {
                permissionMessage = "Allow notifications to use lock-screen controls and the live timer counter."
            }
        }
    }

    LaunchedEffect(Unit) {
        PlanScheduler.rescheduleAll(context)
        while (true) {
            snapshot = TimerStore.snapshot(context)
            delay(250L)
        }
    }

    val requestLaunch: (TimerLaunch) -> Unit = { launch ->
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingLaunch = launch
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            executeTimerLaunch(context, launch)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                AppTab.TIMER -> {
                    if (
                        snapshot.status == TimerStatus.RUNNING ||
                        snapshot.status == TimerStatus.PAUSED
                    ) {
                        RunningTimerScreen(snapshot)
                    } else {
                        TimerSetupScreen(
                            onStart = { requestLaunch(TimerLaunch(durationMillis = it)) },
                            message = permissionMessage
                        )
                    }
                }

                AppTab.PLAN -> PlanScreen(
                    timerSnapshot = snapshot,
                    onStartPlan = { requestLaunch(TimerLaunch(planDate = it)) },
                    onOpenTimer = { selectedTab = AppTab.TIMER }
                )
            }
        }
        PrimaryNavigation(selectedTab) { selectedTab = it }
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Color(0xFF1B1B1E),
            title = { Text("Enable Ankyra alarms", color = Color.White) },
            text = {
                Text(
                    "Ankyra needs notification access for Plan start alarms, vibration, " +
                        "lock-screen controls and the Live Timer. This setup appears only once.",
                    color = Muted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onboardingPreferences.edit()
                            .putBoolean("notification_setup_complete", true)
                            .apply()
                        showOnboarding = false
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            onboardingPermissionFlow = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else if (
                            Build.VERSION.SDK_INT >= 36 &&
                            !TimerService.canPostLiveUpdates(context)
                        ) {
                            TimerService.openLiveUpdateSettings(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("Continue")
                }
            }
        )
    }
}

private fun executeTimerLaunch(context: android.content.Context, launch: TimerLaunch) {
    if (launch.planDate != null) {
        val card = PlanStore.startNextCard(context, launch.planDate) ?: return
        TimerService.start(
            context = context,
            durationMillis = card.plannedDurationMillis,
            title = card.goal,
            planDate = launch.planDate,
            planCardId = card.id
        )
    } else {
        launch.durationMillis?.let { TimerService.start(context, it) }
    }
}

@Composable
private fun TimerSetupScreen(
    onStart: (Long) -> Unit,
    message: String?
) {
    var hours by remember { mutableIntStateOf(2) }
    var minutes by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
            }
        }

        Spacer(Modifier.height(76.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            HeaderLabel("Hours")
            HeaderLabel("Minutes")
            HeaderLabel("Seconds")
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            NumberWheel(0..99, hours, { hours = it }, Modifier.weight(1f))
            Colon()
            NumberWheel(0..59, minutes, { minutes = it }, Modifier.weight(1f))
            Colon()
            NumberWheel(0..59, seconds, { seconds = it }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(68.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PresetButton("00:10:00") {
                hours = 0
                minutes = 10
                seconds = 0
            }
            PresetButton("00:15:00") {
                hours = 0
                minutes = 15
                seconds = 0
            }
            PresetButton("00:30:00") {
                hours = 0
                minutes = 30
                seconds = 0
            }
        }

        Spacer(Modifier.weight(1f))

        if (message != null) {
            Text(
                text = message,
                color = Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        Button(
            onClick = {
                val duration = ((hours * 3_600L) + (minutes * 60L) + seconds) * 1_000L
                if (duration > 0L) onStart(duration)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Purple),
            shape = RoundedCornerShape(40.dp),
            modifier = Modifier
                .fillMaxWidth(0.48f)
                .height(64.dp)
        ) {
            Text("Start", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun HeaderLabel(text: String) {
    Text(
        text = text,
        color = Muted,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.size(width = 92.dp, height = 24.dp)
    )
}

@Composable
private fun NumberWheel(
    range: IntRange,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val values = remember(range) { range.toList() }
    val itemHeight = 72.dp
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(values.indices))
    val flingBehavior = rememberSnapFlingBehavior(state)

    LaunchedEffect(selected) {
        val target = selected.coerceIn(values.indices)
        if (state.firstVisibleItemIndex != target) state.animateScrollToItem(target)
    }

    LaunchedEffect(state) {
        snapshotFlow {
            val offsetRatio = state.firstVisibleItemScrollOffset /
                state.layoutInfo.visibleItemsInfo.firstOrNull()?.size.orEmpty().coerceAtLeast(1).toFloat()
            (state.firstVisibleItemIndex + offsetRatio.roundToInt()).coerceIn(values.indices)
        }
            .distinctUntilChanged()
            .collect { onSelected(values[it]) }
    }

    Box(
        modifier = modifier.height(itemHeight * 3),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = itemHeight),
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(values) { _, value ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = value.toString().padStart(2, '0'),
                        color = if (isSelected) Color.White else Color(0xFF242427),
                        fontSize = if (isSelected) 48.sp else 44.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun Int?.orEmpty(): Int = this ?: 0

@Composable
private fun Colon() {
    Text(
        text = ":",
        color = Color.White,
        fontSize = 48.sp,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

@Composable
private fun PresetButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(102.dp)
            .background(SurfaceGrey, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFFD6D6DA), fontSize = 14.sp)
    }
}

@Composable
private fun RunningTimerScreen(snapshot: TimerSnapshot) {
    val context = LocalContext.current
    val running = snapshot.status == TimerStatus.RUNNING
    val ratio = if (snapshot.totalMillis > 0L) {
        snapshot.remainingMillis.toFloat() / snapshot.totalMillis.toFloat()
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("ANKYRA", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Timer", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                modifier = Modifier.size(310.dp),
                color = Purple,
                trackColor = Color(0xFF38383C),
                strokeWidth = 12.dp
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (snapshot.title != null) {
                    Text(
                        snapshot.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 36.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    TimerService.formatDuration(snapshot.remainingMillis),
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Normal
                )
                Spacer(Modifier.height(12.dp))
                if (snapshot.isPlanTimer) {
                    Text(
                        "of ${TimerService.formatDuration(snapshot.totalMillis)}",
                        color = Muted,
                        fontSize = 16.sp
                    )
                } else {
                    Text(
                        if (running) "Timer running" else "Paused",
                        color = Muted,
                        fontSize = 16.sp
                    )
                }
            }
        }

        if (snapshot.isPlanTimer) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TimerActionButton(
                    label = if (running) "Pause" else "Resume",
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) {
                    TimerService.sendAction(
                        context,
                        if (running) TimerService.ACTION_PAUSE else TimerService.ACTION_RESUME
                    )
                }
                TimerActionButton("Finish early", modifier = Modifier.weight(1f)) {
                    TimerService.sendAction(context, TimerService.ACTION_FINISH_EARLY)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimerActionButton("+5 min", compact = true, modifier = Modifier.weight(1f)) {
                    TimerService.sendAction(
                        context,
                        TimerService.ACTION_ADD_TIME,
                        TimerService.DEFAULT_ADD_TIME_MILLIS
                    )
                }
                TimerActionButton("Restart", compact = true, modifier = Modifier.weight(1f)) {
                    TimerService.sendAction(context, TimerService.ACTION_RESTART)
                }
                TimerActionButton("Skip", compact = true, modifier = Modifier.weight(1f)) {
                    TimerService.sendAction(context, TimerService.ACTION_SKIP)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { TimerService.sendAction(context, TimerService.ACTION_CANCEL) },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceGrey),
                    shape = RoundedCornerShape(40.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Delete")
                }
                Button(
                    onClick = {
                        TimerService.sendAction(
                            context,
                            if (running) TimerService.ACTION_PAUSE else TimerService.ACTION_RESUME
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple),
                    shape = RoundedCornerShape(40.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                ) {
                    Icon(
                        if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(if (running) "Pause" else "Resume")
                }
            }
        }
    }
}

@Composable
private fun TimerActionButton(
    label: String,
    primary: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Purple else SurfaceGrey),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier.height(if (compact) 48.dp else 60.dp)
    ) {
        Text(label, fontSize = if (compact) 12.sp else 15.sp)
    }
}

@Composable
private fun PrimaryNavigation(selected: AppTab, onSelected: (AppTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111113))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) Purple else Color.Transparent,
                        RoundedCornerShape(24.dp)
                    )
                    .clickable { onSelected(tab) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (tab == AppTab.TIMER) "Timer" else "Plan",
                    color = if (active) Color.White else Muted,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
