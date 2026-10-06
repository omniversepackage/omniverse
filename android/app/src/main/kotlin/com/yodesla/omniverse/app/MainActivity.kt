package com.yodesla.omniverse.app

import android.app.SearchManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yodesla.omniverse.designsystem.OmniverseTheme
import com.yodesla.omniverse.designsystem.TEXT_SIZE_KEY
import com.yodesla.omniverse.designsystem.parseAccent
import com.yodesla.omniverse.designsystem.textScaleFor
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.mark("MainActivity.onCreate")
        com.yodesla.omniverse.designsystem.ClockFormat.use24h = android.text.format.DateFormat.is24HourFormat(this)
        val app = application as OmniverseApp
        val brand = app.brand
        val graph = app.graph
        if (BuildConfig.DEV_TOOLS) graph.devBootstrap(BuildConfig.MOCK_BASE)
        val tv = isTelevision()
        DeepLinks.parse(intent)?.let { DeepLinks.pending.value = it }
        SearchIntents.queryOf(intent)?.let { VoiceSearch.pending.value = it }
        setContent {
            // Task 84: the per-profile text size drives the theme scale. Collecting the setting here means
            // a change on the Settings screen re-scales the whole app immediately, no restart.
            val textSize by graph.userData.setting(TEXT_SIZE_KEY).collectAsStateWithLifecycle(initialValue = null)
            OmniverseTheme(accent = parseAccent(brand.accentColor), publicBuild = graph.publicBuild, textScale = textScaleFor(textSize)) {
                TvRoot(brand, graph, mobile = !tv)
            }
        }
        StartupTrace.mark("MainActivity.setContent returned")
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DeepLinks.parse(intent)?.let { DeepLinks.pending.value = it }
        SearchIntents.queryOf(intent)?.let { VoiceSearch.pending.value = it }
    }

    override fun onStart() {
        super.onStart()
        // Stale-while-revalidate: the UI shows what's cached; stale sources refresh in the background.
        (application as OmniverseApp).graph.syncIfStale()
    }

    override fun onStop() {
        super.onStop()
        // Parental unlock lasts only while the app is in the foreground.
        (application as OmniverseApp).graph.parental.lockAgain()
    }

    private fun isTelevision(): Boolean {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

/** Voice / global search (task 80): the remote mic or "search in Omniverse" carries the spoken text. */
private object SearchIntents {
    /** Google's action for "search in <app>" from Assistant / Google TV results. */
    private const val GOOGLE_SEARCH_ACTION = "com.google.android.gms.actions.SEARCH_ACTION"

    fun queryOf(intent: Intent?): String? {
        val action = intent?.action ?: return null
        if (action != Intent.ACTION_SEARCH && action != GOOGLE_SEARCH_ACTION) return null
        return (intent.getStringExtra(SearchManager.QUERY) ?: intent.getStringExtra(SearchManager.USER_QUERY))
            ?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Set by MainActivity on a voice-search intent, consumed (and cleared) by TvRoot. */
object VoiceSearch {
    val pending = MutableStateFlow<String?>(null)
}
