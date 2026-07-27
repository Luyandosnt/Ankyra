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
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AccessAlarm
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
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
    var pendingDuration by remember { mutableLongStateOf(0L) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingDuration > 0L) {
            TimerService.start(context, pendingDuration)
            pendingDuration = 0L
        } else if (!granted) {
            permissionMessage = "Allow notifications to use lock-screen controls and the live timer counter."
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = TimerStore.snapshot(context)
            delay(250L)
        }
    }

    val startTimer: (Long) -> Unit = { duration ->
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDuration = duration
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TimerService.start(context, duration)
        }
    }

    if (snapshot.status == TimerStatus.RUNNING || snapshot.status == TimerStatus.PAUSED) {
        RunningTimerScreen(snapshot)
    } else {
        TimerSetupScreen(
            onStart = startTimer,
            message = permissionMessage
        )
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
            .statusBarsPadding()
            .navigationBarsPadding()
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

        Spacer(Modifier.height(72.dp))
        BottomNavigation()
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
            .statusBarsPadding()
            .navigationBarsPadding()
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
                Text(
                    TimerService.formatDuration(snapshot.remainingMillis),
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Normal
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (running) "Timer running" else "Paused",
                    color = Muted,
                    fontSize = 16.sp
                )
            }
        }

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

@Composable
private fun BottomNavigation() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        BottomItem(Icons.Outlined.AccessAlarm, "Alarm", false)
        BottomItem(Icons.Outlined.Language, "World clock", false)
        BottomItem(Icons.Outlined.Timer, "Stopwatch", false)
        BottomItem(Icons.Default.HourglassBottom, "Timer", true)
    }
}

@Composable
private fun BottomItem(icon: ImageVector, label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Color.White else Muted,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (selected) Color.White else Muted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
