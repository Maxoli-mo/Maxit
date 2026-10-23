// Modified for Maxit, 2026-10-20.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("KaiDatabase") {
            packageName.set("com.maximiliano.maxit.db")
            // The schema lives on user devices — structural changes need a
            // numbered .sqm migration file (see conversation.sq header).
            // This makes the build verify that migrations reproduce the schema.
            verifyMigrations.set(true)
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability.conf"))
}

val syncIosVersion =
    tasks.register<SyncIosVersion>("syncIosVersion") {
        appVersion.set(libs.versions.appVersion)
        xcConfigFile.set(rootProject.layout.projectDirectory.file("iosApp/Configuration/Config.xcconfig"))
    }

val generateVersion =
    tasks.register<GenerateVersion>("generateVersion") {
        dependsOn(syncIosVersion)
        appVersion.set(libs.versions.appVersion)
        outputDirectory.set(layout.buildDirectory.dir("generated/src/commonMain/kotlin"))
    }

kotlin {
    android {
        namespace = "com.maximiliano.maxit.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            // Dynamic so LiteRT-LM's `-Xlinker -all_load` (in its Package.swift) doesn't
            // sweep up ComposeApp's static archive too and trip thousands of duplicate
            // symbols at link time. Each framework gets its own link context.
            isStatic = false
            // Must differ from the iosApp bundle identifier — iOS refuses to install a
            // .app whose embedded framework shares its parent's identifier (MIInstaller
            // error 57 / DuplicateIdentifier).
            binaryOption("bundleId", "com.maximiliano.maxit.composeapp")
        }
    }

    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "composeApp"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer =
                    (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                        // Serve sources to debug inside browser
                        static(rootDirPath)
                        static(projectDirPath)
                    }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateVersion.flatMap { it.outputDirectory })
            dependencies {
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.core)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.components.uiToolingPreview)

                implementation(libs.androidx.navigation.compose)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)

                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)

                implementation(libs.tts)
                implementation(libs.tts.compose)

                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.koin.core)

                implementation(libs.multiplatform.settings)
                implementation(libs.multiplatform.settings.no.arg)

                implementation(libs.filekit.core)
                implementation(libs.filekit.compose)

                implementation(libs.coil.compose)
                implementation(libs.coil.svg)
                implementation(libs.coil.network.ktor3)

                implementation(libs.reorderable)

                implementation(libs.sqldelight.runtime)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.multiplatform.settings.test)
            }
        }
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.process)
                implementation(libs.spght.encryptedprefs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.koin.android)
                implementation(libs.material)
                implementation(libs.litert.lm)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.xz)
            }
        }
        getByName("desktopMain") {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.slf4j.nop)
                implementation(libs.litert.lm.jvm)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.ktor.network)
            implementation(libs.ktor.network.tls)
            implementation(libs.sqldelight.native.driver)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.maximiliano.maxit.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(
                project.file("proguard-rules.pro"),
                project.file("proguard-desktop.pro"),
            )
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Maxit"
            packageVersion = libs.versions.appVersion.get()

            macOS {
                iconFile.set(project.file("icon.icns"))
            }
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Maxit"
            }
            linux {
                iconFile.set(project.file("icon.png"))
                // Applies to the shared jlink runtime image for every platform, not just Linux —
                // java.sql is required since the SQLDelight/SQLite migration (9d6ceec9).
                modules("jdk.security.auth", "java.sql")
            }
        }
    }
}

abstract class GenerateVersion : DefaultTask() {
    @get:Input
    abstract val appVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val appVersion = appVersion.get()
        val versionFile = outputDirectory.file("com/maximiliano/maxit/Version.kt").get().asFile
        versionFile.parentFile.mkdirs()
        versionFile.writeText(
            """
            package com.maximiliano.maxit

            object Version {
                const val appVersion = "$appVersion"
            }
            """.trimIndent(),
        )
    }
}

@UntrackedTask(because = "Updates tracked iOS configuration in place rather than producing generated output.")
abstract class SyncIosVersion : DefaultTask() {
    @get:Input
    abstract val appVersion: Property<String>

    @get:Internal
    abstract val xcConfigFile: RegularFileProperty

    @TaskAction
    fun sync() {
        val file = xcConfigFile.get().asFile
        if (!file.exists()) return

        val appVersion = appVersion.get()
        val content = file.readText()
        val updatedContent =
            if (content.contains("APP_VERSION=")) {
                content.replace(Regex("APP_VERSION=.*"), "APP_VERSION=$appVersion")
            } else {
                content.trimEnd() + "\nAPP_VERSION=$appVersion\n"
            }
        if (content != updatedContent) {
            file.writeText(updatedContent)
        }
    }
}
