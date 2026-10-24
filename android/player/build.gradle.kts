plugins {
    id("omniverse.android.library")
}

android {
    namespace = "com.yodesla.omniverse.player"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":android:designsystem"))
    implementation(libs.kotlinx.coroutines.android)
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(project(":core:net"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.runner)
}
