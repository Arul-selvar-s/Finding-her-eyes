import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    sourceSets {
        val commonMain by getting
        val androidMain by getting
        val desktopMain by getting
        val desktopTest by getting

        // Code that only needs java.io / java.nio / ai.onnxruntime (identical API on
        // Android and Desktop) lives here once and is compiled into BOTH targets.
        androidMain.kotlin.srcDir("src/jvmShared/kotlin")
        desktopMain.kotlin.srcDir("src/jvmShared/kotlin")

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            implementation(libs.kotlinx.coroutines.core)

            // Local database (file index + face embeddings)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)

            // On-device inference (face detection + MobileFaceNet embeddings)
            implementation(libs.onnxruntime.android)
        }

        desktopTest.dependencies {
            implementation(kotlin("test"))
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)

            implementation(libs.onnxruntime.desktop)

            // PDF text + embedded-image extraction on Windows
            // (Android will use the platform PdfRenderer / MediaMetadataRetriever instead)
            implementation(libs.pdfbox)
            implementation(libs.metadata.extractor)
        }
    }
}

// Room (KMP) needs one KSP pass per target.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "app.facefiles"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.facefiles"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // A committed debug keystore keeps the APK signature identical on every CI run,
    // so a new build installs OVER the old one instead of requiring an uninstall.
    val debugKeystore = rootProject.file("facefiles-debug.keystore")
    if (debugKeystore.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.facefiles.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "FaceFiles"
            packageVersion = "1.0.0"
            description = "Private file manager with on-device face filtering"

            // Ship the full JRE module set: avoids "NoClassDefFoundError" surprises from
            // jlink trimming (sqlite, ONNX, PDFBox all touch odd JDK modules).
            includeAllModules = true

            windows {
                menuGroup = "FaceFiles"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5b1f0c0e-6a7d-4f6e-9a55-7e3f2d9c1b42"
            }
        }
    }
}
