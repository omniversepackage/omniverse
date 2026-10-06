package com.yodesla.omniverse.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yodesla.omniverse.designsystem.VisualTier
import com.yodesla.omniverse.designsystem.decideTier

/** Picks Cinematic vs Lite effects for this device (PLAN.md §2 visual tier). */
@Composable
fun rememberVisualTier(): VisualTier {
    val context = LocalContext.current
    return remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        decideTier(am.isLowRamDevice, am.memoryClass, Build.VERSION.SDK_INT)
    }
}
