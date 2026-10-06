package com.yodesla.omniverse.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.epg.EpgGrid
import com.yodesla.omniverse.designsystem.epg.EpgGridData
import com.yodesla.omniverse.designsystem.epg.GridChannel
import com.yodesla.omniverse.designsystem.epg.GridProgramme
import kotlinx.coroutines.delay
import kotlin.random.Random

/** DEBUG-ONLY: guide grid with 800 synthetic channels, to tune feel and measure frame times. */
@Composable
fun DevGuideScreen(onExit: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val data = remember { FakeGuide(800) }
    var info by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onExit)
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        Text(
            info.ifEmpty { "Guide preview · 800 channels" },
            style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.m),
        )
        EpgGrid(
            data = data,
            nowMs = now,
            modifier = Modifier.fillMaxSize().padding(start = OmniSpacing.tvSide - OmniSpacing.m),
            focusRequester = focus,
            onFocusedChange = { row, p -> info = "${data.channel(row).name} — ${p?.title ?: "No information"}" },
        )
    }
}

private class FakeGuide(override val channelCount: Int) : EpgGridData {
    private val names = listOf("News", "Sports", "Movies", "Kids", "Docs", "Music", "Comedy", "Drama")
    override fun channel(index: Int) = GridChannel("$index", index + 1, "${names[index % names.size]} ${index + 1} HD", null)
    override suspend fun programmes(index: Int, fromMs: Long, toMs: Long): List<GridProgramme> {
        delay(2) // pretend DB read
        val rnd = Random(index)
        val slot = 30 * 60_000L
        var t = fromMs - fromMs % slot - slot * rnd.nextInt(0, 2)
        val out = ArrayList<GridProgramme>()
        var n = 0
        while (t < toMs) {
            val len = slot * (1 + (Random(index * 7919L + t).nextInt(0, 4)))
            out += GridProgramme(t, t + len, "${names[(index + n) % names.size]} Show ${(t / slot) % 97}", hasArchive = index % 3 == 0)
            t += len; n++
        }
        return out
    }
}
