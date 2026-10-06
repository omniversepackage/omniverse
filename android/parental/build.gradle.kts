plugins {
    id("omniverse.android.library")
}

android {
    namespace = "com.yodesla.omniverse.android.parental"
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":android:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
