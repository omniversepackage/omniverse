plugins {
    id("omniverse.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:source-api"))
            api(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            api(libs.okhttp)
        }
        jvmTest.dependencies {
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
