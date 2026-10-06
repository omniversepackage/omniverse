import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("omniverse.android.application")
}

// PLAN.md §10: every brand-specific value comes from brands/<brand>/brand.json.
val brandName: String = providers.gradleProperty("omniverse.brand").getOrElse("omniverse")
val brandDir = rootProject.layout.projectDirectory.dir("brands/$brandName")
@Suppress("UNCHECKED_CAST")
val brand = JsonSlurper().parse(brandDir.file("brand.json").asFile) as Map<String, Any?>

// Public build (Task 75): brand.json's publicBuild, or forced on with -PpublicBuild=true so the
// release script can make a public APK from the same code. Baked into BuildConfig.PUBLIC_BUILD.
val publicBuild = (brand["publicBuild"] as? Boolean ?: false) ||
    providers.gradleProperty("publicBuild").getOrElse("false").toBoolean()

// Release signing: keys/keystore.properties + the .jks live in the gitignored keys/ folder
// (location recorded in HANDOFF/credentials.md; backed up on optiplex). Machines without it
// (Qwen, CI) fall back to the debug key, which is fine for anything that isn't shipped.
val keystoreProps = rootProject.file("keys/keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

// Version numbers live in version.properties at the project root; tools/release.ps1 bumps them.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// TMDB API key (task 84d): tmdbApiKey in the gitignored root local.properties. Absent file or
// property = empty string = the TMDB enricher stays disabled (core never reads files/env).
val tmdbApiKey = rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }.getProperty("tmdbApiKey").orEmpty()
}.orEmpty()

android {
    namespace = "com.yodesla.omniverse.app"
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file("keys/" + keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    defaultConfig {
        applicationId = brand["applicationId"] as String
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        resValue("string", "app_name", brand["appName"] as String)
        buildConfigField("boolean", "PUBLIC_BUILD", publicBuild.toString())
        buildConfigField("String", "TMDB_API_KEY", "\"" + tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    buildFeatures {
        resValues = true
        buildConfig = true
    }
    buildTypes {
        debug {
            // Dev-only mock Xtream server (tools/mock-xtream on optiplex). Override: -Pomniverse.mockBase=...
            val mock = providers.gradleProperty("omniverse.mockBase").getOrElse("http://192.168.1.137:8765")
            buildConfigField("String", "MOCK_BASE", "\"$mock\"")
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        release {
            buildConfigField("String", "MOCK_BASE", "\"\"")
            buildConfigField("boolean", "DEV_TOOLS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildTypes {
        // Release-speed build (R8, not debuggable) that keeps the dev screens: for measuring feel
        // and for Macrobenchmark/Baseline Profiles. Installs side by side (.bench).
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            proguardFiles("benchmark-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isProfileable = true
            val mock = providers.gradleProperty("omniverse.mockBase").getOrElse("http://192.168.1.137:8765")
            buildConfigField("String", "MOCK_BASE", "\"$mock\"")
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants { variant ->
        // brand.json (and later brand images) ship as assets; BrandConfig.parse() reads them at runtime.
        variant.sources.assets?.addStaticSourceDirectory(brandDir.asFile.absolutePath)
    }
}

dependencies {
    implementation(project(":core:brand"))
    implementation(project(":core:data"))
    implementation(project(":core:net"))
    implementation(project(":core:source-xtream"))
    implementation(project(":core:source-plex"))
    implementation(project(":core:source-m3u"))
    implementation(project(":android:feature-live"))
    implementation(project(":android:feature-vod"))
    implementation(project(":android:feature-home"))
    implementation(project(":android:feature-onboarding"))
    implementation(project(":android:update"))
    implementation(project(":core:update"))
    implementation(libs.work.runtime)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":android:designsystem"))
    implementation(project(":android:player"))
    implementation(project(":android:parental"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.profileinstaller)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
}
