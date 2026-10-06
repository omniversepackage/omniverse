plugins {
    id("omniverse.android.library")
}

android {
    namespace = "com.yodesla.omniverse.player"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":android:designsystem"))
    implementation(libs.kotlinx.coroutines.android)
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    testImplementation(kotlin("test-junit"))
}
