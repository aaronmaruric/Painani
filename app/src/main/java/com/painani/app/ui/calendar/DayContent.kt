package com.painani.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.painani.app.domain.model.CalendarEvent
import com.painani.app.domain.model.Session
import com.painani.app.domain.model.SessionType
import com.painani.app.ui.theme.NothingRed
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Callbacks for editing imported events. Null hides the edit affordance. */
data class EventActions(
    val onSave: (CalendarEvent) -> Unit,
    val onDelete: (CalendarEvent) -> Unit,
)

/** Callbacks on a logged session. Null hides the affordances. */
data class SessionActions(
    /** Ask Health Connect to release the route of an imported run (system consent dialog). */
    val onLoadRoute: (Session) -> Unit,
)

/** Everything on one day: planned events first, then what actually happened. */
@Composable
fun DayContent(
    sessions: List<Session>,
    events: List<CalendarEvent>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    eventActions: EventActions? = null,
    sessionActions: SessionActions? = null,
) {
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    editing?.let { e ->
        if (eventActions != null) {
            EventEditDialog(
                event = e,
                onSave = { eventActions.onSave(it); editing = null },
                onDelete = { eventActions.onDelete(e); editing = null },
                onDismiss = { editing = null },
            )
        }
    }

    if (sessions.isEmpty() && events.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("REST DAY", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(events, key = { "e${it.id}" }) { event ->
            EventCard(event, onEdit = if (eventActions != null) ({ editing = event }) else null)
        }
        items(sessions, key = { "s${it.id}" }) { SessionCard(it, sessionActions) }
    }
}

@Composable
fun EventCard(event: CalendarEvent, onEdit: (() -> Unit)? = null) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val zone = ZoneId.systemDefault()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PLANNED",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (event.allDay) "ALL DAY"
                    else event.start.atZone(zone).format(timeFmt) + " – " + event.end.atZone(zone).format(timeFmt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (onEdit != null) {
                    IconButton(onClick = onEdit, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit event")
                    }
                }
            }
            Text(event.summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            if (event.location.isNotBlank()) {
                Text(event.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.description.isNotBlank()) {
                Text(event.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun SessionCard(session: Session, actions: SessionActions? = null) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (session.type == SessionType.RUN) "RUN" else "STRENGTH",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (session.type == SessionType.RUN) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = session.startedAt.atZone(ZoneId.systemDefault()).format(timeFmt),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            session.source?.let {
                Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "Duration ${formatDuration(session.durationMillis)}" +
                    (session.distanceMeters?.let { " · %.2f km".format(Locale.getDefault(), it / 1000) } ?: "") +
                    (session.avgHeartRate?.let { " · ♥ $it avg" + (session.maxHeartRate?.let { m -> " / $m max" } ?: "") } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (session.notes.isNotBlank()) {
                Text(session.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            when (session.type) {
                SessionType.RUN -> SplitsTable(session)
                SessionType.STRENGTH -> SetsTable(session)
            }
            // Other apps' routes are released one at a time after a system consent dialog.
            if (actions != null && session.isImported && session.type == SessionType.RUN && session.trackPoints.isEmpty()) {
                TextButton(onClick = { actions.onLoadRoute(session) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("LOAD ROUTE FROM HEALTH CONNECT")
                }
            }
        }
    }
}

@Composable
private fun SplitsTable(session: Session) {
    if (session.splits.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    val hasHr = session.splits.any { it.avgHeartRate != null }
    if (hasHr) TableHeader("SPLIT", "KM", "TIME", "PACE", "♥") else TableHeader("SPLIT", "KM", "TIME", "PACE")
    session.splits.forEach { split ->
        val cells = mutableListOf(
            "${split.index + 1}",
            "%.2f".format(Locale.getDefault(), split.distanceMeters / 1000),
            formatDuration(split.durationMillis),
            formatPace(split.paceSecPerKm),
        )
        if (hasHr) cells += split.avgHeartRate?.toString() ?: "--"
        TableRow(*cells.toTypedArray())
    }
}

@Composable
private fun SetsTable(session: Session) {
    if (session.sets.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    session.sets.groupBy { it.exercise.name }.forEach { (name, sets) ->
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        sets.forEach { set ->
            TableRow(
                "Set ${set.setIndex + 1}",
                "${set.reps} reps",
                "%.1f kg".format(Locale.getDefault(), set.weightKg),
                "",
            )
        }
    }
}

@Composable
private fun TableHeader(vararg cells: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TableRow(vararg cells: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        cells.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
    }
}

fun formatDuration(millis: Long): String {
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatPace(secPerKm: Double): String {
    if (secPerKm <= 0) return "--"
    val m = (secPerKm / 60).toInt()
    val s = (secPerKm % 60).toInt()
    return "%d:%02d /km".format(m, s)
}

/** e.g. "5.2k" for a run, "4 sets" for strength. */
fun Session.summaryLabel(): String = when (type) {
    SessionType.RUN -> distanceMeters?.let { String.format(Locale.getDefault(), "%.1fk", it / 1000) } ?: "run"
    SessionType.STRENGTH -> "${sets.size} sets"
}
