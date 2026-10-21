// Modified for Maxit, 2026-10-20.
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.maximiliano.maxit.screenshots"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    // Include composeApp's assets (which contain compose resources)
    sourceSets {
        getByName("main") {
            assets.directories.add(
                project(":composeApp").file("build/generated/assets/copyAndroidMainComposeResourcesToAndroidAssets").path,
            )
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    testImplementation(project(":composeApp"))
    // Required for types used directly in test code (KMP doesn't expose transitively)
    testImplementation(libs.filekit.core)
    implementation(libs.tts)
    implementation(libs.tts.compose)
    testImplementation(libs.kotlinx.collections.immutable)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.compose.material3)
    testImplementation(libs.compose.components.resources)
}

// Ensure composeApp resources are generated before screenshot tests
tasks.matching { it.name.contains("preparePaparazzi") }.configureEach {
    dependsOn(":composeApp:copyAndroidMainComposeResourcesToAndroidAssets")
}

// Paparazzi 2.0.0-alpha04's HTML reporter calls a Gradle internal API removed in 9.4
tasks.withType<Test>().configureEach {
    reports.html.required.set(false)
}

// Keep the localized screenshot fixtures available without running the full matrix by default.
// Opt in with -PincludeLocalizedScreenshots when recording or verifying Paparazzi snapshots.
tasks.withType<Test>().configureEach {
    if (!providers.gradleProperty("includeLocalizedScreenshots").isPresent) {
        filter.excludeTestsMatching("*.StoreScreenshotTest")
        filter.excludeTestsMatching("*.TabletStoreScreenshotTest")
    }
}
