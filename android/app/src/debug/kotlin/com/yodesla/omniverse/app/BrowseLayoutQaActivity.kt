package com.yodesla.omniverse.app

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yodesla.omniverse.designsystem.BrandStage
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.blendDestinationTop
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yodesla.omniverse.designsystem.OmniverseTheme
import com.yodesla.omniverse.feature.vod.BrowseLayoutPreview

class BrowseLayoutQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent { OmniverseTheme(textScale=intent.getFloatExtra("scale",1f)) {
            if (intent.getBooleanExtra("blend", false)) {
                CosmicBackdrop(Modifier.fillMaxSize()) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(44.dp))
                        Box(Modifier.weight(1f).fillMaxWidth().blendDestinationTop()) {
                            BrandStage(intent.getStringExtra("brand") ?: "Disney+", Modifier.fillMaxSize()) {
                                BrowseLayoutPreview(intent.getStringExtra("brand") ?: "Disney+", intent.getBooleanExtra("series", false), false)
                            }
                        }
                    }
                }
            } else BrowseLayoutPreview(intent.getStringExtra("brand") ?: "Netflix",intent.getBooleanExtra("series",false),intent.getBooleanExtra("logo",false))
        } }
    }
}
