package com.yodesla.omniverse.app

import android.app.Application
import com.yodesla.omniverse.core.brand.BrandConfig
import kotlinx.coroutines.launch

class OmniverseApp : Application() {
    /** Parsed once from assets/brand.json (copied from brands/<brand>/ at build time). */
    val brand: BrandConfig by lazy {
        BrandConfig.parse(assets.open("brand.json").bufferedReader().use { it.readText() })
    }

    val graph: AppGraph by lazy { AppGraph(this, brand) }

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark("Application.onCreate")
        // First thing in the process (task 78): a crash anywhere — even during startup — lands in
        // filesDir/crash and shows up in Settings › Diagnostics. No upload, no SDK.
        CrashLog.install(this)
        // Task 87: pin the image cache before any screen can request artwork.
        installAppImageLoader()
        // Task 101: building the graph is now cheap (the database opens on a background thread), so
        // it is created here to start that warm-up while the activity is still being set up. Work
        // Manager scheduling and the Watch Next publisher both read the database, so they wait for
        // the app scope instead of running on the thread that has to draw the first frame.
        val graph = this.graph
        graph.appScope.launch {
            SyncWorker.schedule(this@OmniverseApp)
            // Task 105: the weekly source health check, plus a catch-up run now if the last one is
            // more than a week old (it waits for Home's rows before touching the network).
            SourceHealthWorker.schedule(this@OmniverseApp)
            graph.checkSourceHealthAtStartup()
            // Android TV home screen "Play Next" row mirrors Continue Watching.
            WatchNextPublisher(this@OmniverseApp, graph).start(graph.appScope)
        }
        StartupTrace.mark("Application.onCreate returned")
    }
}
