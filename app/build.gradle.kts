import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val isCi = System.getenv("CI") == "true"
val gitRefName: String? = System.getenv("GITHUB_REF_NAME")
val tagVersionName: String? = gitRefName
    ?.removePrefix("refs/tags/")
    ?.removePrefix("v")
val computedVersionName: String = tagVersionName ?: "0.3.2"

fun computeVersionCodeFromName(name: String): Int {
    val parts = name.split(".")
    fun parsePart(index: Int): Int {
        val part = parts.getOrNull(index).orEmpty()
        val numericPrefix = part.takeWhile { it.isDigit() }
        return numericPrefix.toIntOrNull() ?: 0
    }

    val major = parsePart(0)
    val minor = parsePart(1)
    val patch = parsePart(2)
    // Keep new releases installable over the retired 0.4.0-rc.1 (versionCode 400).
    return 10000 + major * 10000 + minor * 100 + patch
}

val computedVersionCode: Int = computeVersionCodeFromName(computedVersionName)

android {
    namespace = "com.futaiii.sudodroid"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.futaiii.sudodroid"
        minSdk = 28
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (isCi) {
            create("github") {
                storeFile = rootProject.file("github.keystore")
                storePassword = "github"
                keyAlias = "github"
                keyPassword = "github"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (isCi) {
                signingConfig = signingConfigs.getByName("github")
            }
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/DEPENDENCIES",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }

    lint {
        abortOnError = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(files("libs/sudoku.aar"))
    implementation(files("libs/hev-socks5-tunnel.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Use the upstream AAR, which contains the default JNI binding and Android libraries.
val ensureHevAar by tasks.registering {
    val version = "2.18.0"
    val expectedSha256 = "15ec8ed121663b562c99caa5bb602d1009f24e5b09e733438b81988f12feaaab"
    val aar = projectDir.resolve("libs/hev-socks5-tunnel.aar")
    inputs.property("hevVersion", version)
    inputs.property("hevSha256", expectedSha256)
    outputs.file(aar)
    doLast {
        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (aar.exists() && sha256(aar) == expectedSha256) return@doLast
        aar.parentFile.mkdirs()
        val download = aar.resolveSibling("${aar.name}.download")
        try {
            val url = "https://github.com/heiher/hev-socks5-tunnel/releases/download/$version/hev-socks5-tunnel.aar"
            logger.lifecycle("Downloading hev-socks5-tunnel $version AAR")
            URI.create(url).toURL().openStream().use { input ->
                download.outputStream().use { output -> input.copyTo(output) }
            }
            check(sha256(download) == expectedSha256) {
                "hev-socks5-tunnel $version AAR SHA-256 mismatch"
            }
            Files.move(download.toPath(), aar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            download.delete()
        }
    }
}

// Build gomobile AAR for Sudoku ASCII core if missing.
val ensureSudokuAar by tasks.registering {
    val aar = projectDir.resolve("libs/sudoku.aar")
    inputs.file(rootProject.file("scripts/build_sudoku_aar.sh"))
    inputs.dir(rootProject.file("scripts/sudoku_patches"))
    inputs.property("sudokuRef", System.getenv("SUDOKU_REF") ?: "<default>")
    inputs.property("androidApiLevel", System.getenv("ANDROID_API_LEVEL") ?: "<default>")
    inputs.property("gomobileTargets", System.getenv("GOMOBILE_TARGETS") ?: "<default>")
    inputs.property("gomobileBin", System.getenv("GOMOBILE_BIN") ?: "<default>")
    outputs.file(aar)
    doLast {
        exec {
            workingDir = rootProject.projectDir
            commandLine("bash", "scripts/build_sudoku_aar.sh")
        }
        check(aar.exists()) { "gomobile AAR not generated; ensure Go + Android SDK/NDK are available." }
    }
}

tasks.named("preBuild").configure {
    dependsOn(ensureHevAar, ensureSudokuAar)
}
