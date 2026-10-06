package com.yodesla.omniverse.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "com.yodesla.omniverse.bench"

/** Launch and wait until the nav rail has drawn (its icons carry content descriptions). */
fun MacrobenchmarkScope.startAndWaitForHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.desc("Home")), 5_000)
}

/** Rail → "Guide preview" (the dev guide with 800 synthetic channels; last rail item in dev builds). */
fun MacrobenchmarkScope.openGuide() {
    device.pressDPadLeft()
    device.waitForIdle()
    repeat(6) { device.pressDPadDown() }
    device.pressDPadCenter()
    device.wait(Until.hasObject(By.textContains("Guide preview")), 5_000)
    device.pressDPadRight()
    device.waitForIdle()
}

/** The journey users feel most: holding Down through the guide, then across, then back up. */
fun MacrobenchmarkScope.scrollGuide() {
    repeat(40) { device.pressDPadDown() }
    repeat(8) { device.pressDPadRight() }
    repeat(20) { device.pressDPadUp() }
    device.waitForIdle()
}

/** Home rows: across the first row, down a row, and back — hero crossfades included. */
fun MacrobenchmarkScope.browseHome() {
    repeat(8) { device.pressDPadRight() }
    device.pressDPadDown()
    repeat(8) { device.pressDPadRight() }
    repeat(8) { device.pressDPadLeft() }
    device.waitForIdle()
}
