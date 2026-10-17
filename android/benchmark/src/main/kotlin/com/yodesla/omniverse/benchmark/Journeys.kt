package com.yodesla.omniverse.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "com.yodesla.omniverse.bench"

/** Launch and wait until the nav rail has drawn (its icons carry content descriptions). */
fun MacrobenchmarkScope.startAndWaitForHome() {
    pressHome()
    startActivityAndWait()
    check(device.wait(Until.hasObject(By.desc("Home")), 5_000)) { "Home navigation did not load" }
}

/** Open the named dev guide; rail position varies with installed features. */
fun MacrobenchmarkScope.openGuide() {
    device.pressDPadLeft()
    val guide = device.wait(Until.findObject(By.desc("Guide preview")), 5_000)
        ?: error("Guide preview navigation item is unavailable")
    guide.click()
    check(device.wait(Until.hasObject(By.textContains("Guide preview")), 5_000)) { "Guide preview did not open" }
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
