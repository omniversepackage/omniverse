package com.yodesla.omniverse.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yodesla.omniverse.core.data.AutoRefreshPolicy
import com.yodesla.omniverse.designsystem.OmniPickerDialog
import com.yodesla.omniverse.designsystem.PickerOption
import com.yodesla.omniverse.feature.home.settings.OmniActionRow
import com.yodesla.omniverse.feature.home.settings.OmniPickerRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
fun AutoRefreshSettings(graph: AppGraph, discovery: Boolean) {
    val key = if (discovery) AutoRefreshPolicy.DISCOVERY else AutoRefreshPolicy.SOURCES
    val raw by graph.userData.setting(key).collectAsStateWithLifecycle(initialValue = null)
    val policy = AutoRefreshPolicy.parse(raw)
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val status by graph.userData.setting("discovery_status").collectAsStateWithLifecycle(initialValue = null)
    fun save(value: AutoRefreshPolicy) { scope.launch { graph.userData.putSetting(key, value.encode()) } }
    val names = mapOf("off" to "Off", "hours" to "Every set number of hours", "daily" to "At a daily time", "startup" to "Every app start")
    Column {
        OmniPickerRow(if (discovery) "Discovery & service popularity updates" else "Automatic source updates", names[policy.mode].orEmpty(),
            onClick = { picker = "mode" }, secondary = "Device-wide · background updates may be delayed by Android")
        if (policy.mode == "hours") OmniPickerRow("Update interval", "Every ${policy.hours} hours", onClick = { picker = "hours" })
        if (policy.mode == "daily") {
            OmniPickerRow("Daily hour", "%02d:xx".format(policy.minute / 60), onClick = { picker = "hour" }, secondary = "Local device time (24-hour clock)")
            OmniPickerRow("Daily minute", "xx:%02d".format(policy.minute % 60), onClick = { picker = "minute" })
        }
        OmniActionRow(if (discovery) "Refresh discovery now" else "Refresh all sources now", if (busy) "Updating…" else "Refresh",
            secondary = result ?: if (discovery) (status ?: "TMDB daily/weekly interest, release dates and US service popularity. Only playable titles appear.") else "Channels, guide, movies and shows from all your sources.",
            onClick = { if (!busy) scope.launch {
                busy = true
                try {
                    val ok = if (discovery) graph.discovery.refresh() else graph.sources.sources().let { flow ->
                        var success = true
                        flow.first().forEach { if (!graph.syncSourceNow(it.id)) success = false }
                        success
                    }
                    if (ok) graph.userData.putSetting("${key}_attempt", graph.clock.nowMs().toString())
                    result = if (ok) "Updated" else "Some data unavailable; saved results kept"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { result = "Update failed; try again later" }
                finally { busy = false }
            } })
    }
    picker?.let { which ->
        val options = when (which) {
            "mode" -> names.map { PickerOption(it.key, it.value) }
            "hours" -> (1..168).map { PickerOption(it.toString(), if (it == 1) "Every hour" else "Every $it hours") }
            "hour" -> (0..23).map { PickerOption(it.toString(), "%02d:xx".format(it)) }
            else -> (0..59).map { PickerOption(it.toString(), "xx:%02d".format(it)) }
        }
        OmniPickerDialog(title = when (which) { "mode" -> "Automatic updates"; "hours" -> "Update interval"; "hour" -> "Daily hour"; else -> "Daily minute" },
            options = options, selected = when (which) { "mode" -> policy.mode; "hours" -> policy.hours.toString(); "hour" -> (policy.minute / 60).toString(); else -> (policy.minute % 60).toString() },
            onSelect = { value ->
                save(when (which) { "mode" -> policy.copy(mode = value); "hours" -> policy.copy(hours = value.toInt()); "hour" -> policy.copy(minute = value.toInt() * 60 + policy.minute % 60); else -> policy.copy(minute = policy.minute / 60 * 60 + value.toInt()) })
                picker = null
            }, onDismiss = { picker = null })
    }
}
