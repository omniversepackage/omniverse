plugins {
    id("omniverse.android.library")
}

android {
    namespace = "com.yodesla.omniverse.android.update"
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:update"))
    implementation(project(":core:net"))
    implementation(project(":core:source-api"))
    implementation(project(":android:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
