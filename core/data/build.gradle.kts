plugins {
    id("omniverse.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:source-api"))
            api(project(":core:database"))
            api(project(":core:hooks"))
            implementation(project(":core:net"))
            implementation(libs.okio)
            api(libs.paging.common)
            implementation(libs.sqldelight.paging)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
