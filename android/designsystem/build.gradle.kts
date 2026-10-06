plugins {
    id("omniverse.android.library")
}

android {
    namespace = "com.yodesla.omniverse.designsystem"
}

dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.tv.material)
    api(libs.compose.material3)
    api(libs.coil.compose)
    api(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
}
