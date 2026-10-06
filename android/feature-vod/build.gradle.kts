plugins {
    id("omniverse.android.library")
}

android {
    namespace = "com.yodesla.omniverse.feature.vod"
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":android:designsystem"))
    implementation(project(":android:player"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
