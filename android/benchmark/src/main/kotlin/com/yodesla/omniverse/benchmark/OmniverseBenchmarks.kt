package com.yodesla.omniverse.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN.md §9 budgets. Run on the Shield / Onn (Android 11+ OK):
 *   .\gradlew.bat :android:benchmark:connectedBenchmarkAndroidTest
 * Baseline Profile generation needs Android 13+ (use the omni_gtv emulator):
 *   ...connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yodesla.omniverse.benchmark.BaselineProfileGenerator
 */
@RunWith(AndroidJUnit4::class)
class OmniverseBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) { startAndWaitForHome() }

    @Test
    fun guideScroll() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 3,
        setupBlock = {
            startAndWaitForHome()
            openGuide()
        },
    ) { scrollGuide() }

    @Test
    fun homeBrowse() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 3,
        setupBlock = { startAndWaitForHome() },
    ) { browseHome() }
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        startAndWaitForHome()
        browseHome()
        pressHome()
        startAndWaitForHome()
        openGuide()
        scrollGuide()
    }
}
