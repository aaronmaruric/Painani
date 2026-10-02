package com.painani.app.ui.run

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.painani.app.domain.model.Session
import com.painani.app.domain.model.SessionType
import com.painani.app.domain.model.Split
import com.painani.app.data.health.HealthSync
import com.painani.app.domain.repository.SessionRepository
import com.painani.app.tracking.LocationRunTracker
import com.painani.app.tracking.RunState
import com.painani.app.tracking.RunTrackingService
import com.painani.app.tracking.TrackerStatus
import com.painani.app.ui.calendar.formatDuration
import com.painani.app.ui.calendar.formatPace
import com.painani.app.ui.theme.NothingRed
import com.painani.app.ui.theme.Numeral
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun RunScreen(repository: SessionRepository, tracker: LocationRunTracker, healthSync: HealthSync) {
    val context = LocalContext.current
    val state by tracker.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showManual by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            RunTrackingService.send(context, RunTrackingService.ACTION_START)
        } else {
            scope.launch { snackbar.showSnackbar("Location permission is needed to track a run") }
        }
    }

    fun startRun() {
        if (!tracker.isGpsEnabled) {
            scope.launch { snackbar.showSnackbar("Turn on Location (GPS) first") }
            return
        }
        if (tracker.hasPermission) {
            RunTrackingService.send(context, RunTrackingService.ACTION_START)
        } else {
            val wanted = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissions.launch(wanted.toTypedArray())
        }
    }

    fun stopAndSave() {
        val final = tracker.stop()
        RunTrackingService.send(context, RunTrackingService.ACTION_STOP)
        if (final.startedAtMillis == 0L || (final.distanceMeters < 10 && final.elapsedMillis < 10_000)) {
            scope.launch { snackbar.showSnackbar("Run discarded (too short)") }
            return
        }
        scope.launch {
            val id = repository.save(final.toSession())
            healthSync.onSessionSaved(id)
            snackbar.showSnackbar("Run saved")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RUN", style = MaterialTheme.typography.titleLarge)

        LiveReadout(state)

        when (state.status) {
            TrackerStatus.IDLE -> {
                Button(
                    onClick = ::startRun,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NothingRed, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("START", style = MaterialTheme.typography.titleMedium) }
            }
            TrackerStatus.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { RunTrackingService.send(context, RunTrackingService.ACTION_RESUME) },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("RESUME") }
                OutlinedButton(onClick = ::stopAndSave, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("STOP", color = NothingRed)
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { RunTrackingService.send(context, RunTrackingService.ACTION_PAUSE) },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("PAUSE") }
                OutlinedButton(onClick = ::stopAndSave, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("STOP", color = NothingRed)
                }
            }
        }

        if (state.completedSplits.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("SPLITS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.completedSplits.asReversed().forEach { split ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("${split.index + 1}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(formatDuration(split.durationMillis), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(formatPace(split.paceSecPerKm), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.status == TrackerStatus.IDLE) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showManual = !showManual }) {
                Text(if (showManual) "HIDE MANUAL ENTRY" else "LOG MANUALLY", style = MaterialTheme.typography.labelMedium)
            }
            if (showManual) ManualEntry(repository, healthSync, snackbar)
        }

        SnackbarHost(snackbar)
    }
}

@Composable
private fun LiveReadout(state: RunState) {
    val statusLine = when (state.status) {
        TrackerStatus.IDLE -> "READY"
        TrackerStatus.WAITING_FOR_FIX -> "WAITING FOR GPS" + (state.accuracyMeters?.let { " · ±${it.toInt()} m" } ?: "")
        TrackerStatus.RUNNING -> "GPS ±${state.accuracyMeters?.toInt() ?: 0} m"
        TrackerStatus.PAUSED -> "PAUSED"
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = statusLine,
            style = MaterialTheme.typography.labelMedium,
            color = if (state.status == TrackerStatus.PAUSED) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatDuration(state.elapsedMillis),
            style = Numeral.copy(fontSize = 64.sp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("KM", String.format(Locale.getDefault(), "%.2f", state.distanceMeters / 1000))
            Stat("PACE", state.currentPaceSecPerKm?.let { formatPace(it).removeSuffix(" /km") } ?: "--:--")
            Stat("AVG", state.averagePaceSecPerKm?.let { formatPace(it).removeSuffix(" /km") } ?: "--:--")
        }
        if (state.isActive) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "SPLIT ${state.completedSplits.size + 1} · " +
                    String.format(Locale.getDefault(), "%.0f m", state.currentSplitMeters) +
                    " · " + formatDuration(state.currentSplitMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Numeral.copy(fontSize = 28.sp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ManualEntry(repository: SessionRepository, healthSync: HealthSync, snackbar: SnackbarHostState) {
    var distanceKm by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = distanceKm,
                onValueChange = { distanceKm = it },
                label = { Text("Distance (km)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it },
                label = { Text("Time (min)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedButton(
            onClick = {
                val km = distanceKm.toDoubleOrNull()
                val min = minutes.toDoubleOrNull()
                if (km == null || min == null || km <= 0 || min <= 0) {
                    scope.launch { snackbar.showSnackbar("Enter a distance and time") }
                    return@OutlinedButton
                }
                val session = buildManualRun(km, min, notes)
                scope.launch {
                    healthSync.onSessionSaved(repository.save(session))
                    distanceKm = ""; minutes = ""; notes = ""
                    snackbar.showSnackbar("Run saved")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SAVE MANUAL RUN") }
    }
}

private fun RunState.toSession(): Session = Session(
    type = SessionType.RUN,
    startedAt = Instant.ofEpochMilli(startedAtMillis),
    durationMillis = elapsedMillis,
    distanceMeters = distanceMeters,
    splits = completedSplits,
    trackPoints = trackPoints,
)

/** Builds a run session ending now, with whole-km splits at an even pace plus a partial final split. */
internal fun buildManualRun(distanceKm: Double, minutes: Double, notes: String): Session {
    val durationMillis = (minutes * 60_000).toLong()
    val distanceMeters = distanceKm * 1000
    val msPerMeter = durationMillis / distanceMeters

    val splits = buildList {
        var covered = 0.0
        var index = 0
        while (covered < distanceMeters - 1e-6) {
            val segment = minOf(1000.0, distanceMeters - covered)
            add(Split(index = index++, distanceMeters = segment, durationMillis = (segment * msPerMeter).toLong()))
            covered += segment
        }
    }

    return Session(
        type = SessionType.RUN,
        startedAt = Instant.now().minusMillis(durationMillis),
        durationMillis = durationMillis,
        notes = notes.trim(),
        distanceMeters = distanceMeters,
        splits = splits,
    )
}
