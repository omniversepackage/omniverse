plugins {
    id("omniverse.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:source-api"))
            implementation(project(":core:net"))
            implementation(project(":core:epg-xmltv"))
            api(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
