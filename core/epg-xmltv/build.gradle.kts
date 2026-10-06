plugins {
    id("omniverse.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:source-api"))
            api(libs.okio)
        }
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    maxHeapSize = "2g"
}
