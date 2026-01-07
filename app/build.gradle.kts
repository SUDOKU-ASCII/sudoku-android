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
val computedVersionName: String = tagVersionName ?: "0.1.0"

fun computeVersionCodeFromName(name: String): Int {
    val parts = name.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10000 + minor * 100 + patch
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
        // Reduce APK size by allowing native libs to be compressed in the APK.
        jniLibs.useLegacyPackaging = true
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

// Ensure hev-socks5-tunnel submodules are present even when the repo is from a zip without submodules.
val ensureHevDeps by tasks.registering {
    doLast {
        val root = project.rootDir
        val hevRoot = root.resolve("third_party/hev-socks5-tunnel")
        val deps = listOf(
            hevRoot.resolve("Android.mk"),
            hevRoot.resolve("build.mk"),
            hevRoot.resolve("third-part/yaml/Android.mk"),
            hevRoot.resolve("third-part/lwip/Android.mk"),
            hevRoot.resolve("third-part/hev-task-system/Android.mk")
        )
        if (!hevRoot.exists()) {
            logger.lifecycle("Cloning hev-socks5-tunnel into jni directory")
            hevRoot.parentFile.mkdirs()
            exec {
                workingDir = root
                commandLine("git", "clone", "--recursive", "https://github.com/heiher/hev-socks5-tunnel", hevRoot.path)
            }
            exec {
                workingDir = hevRoot
                commandLine("git", "checkout", "47b6cb90f4641ed9b00911ef2c521a9836b60c5b")
            }
        }
        exec {
            workingDir = hevRoot
            commandLine("git", "submodule", "update", "--init", "--recursive")
        }
        check(deps.all { it.exists() }) {
            "Failed to fetch hev-socks5-tunnel dependencies under app/src/main/jni; please ensure git and network are available."
        }
    }
}

// Build gomobile AAR for Sudoku ASCII core if missing.
val ensureSudokuAar by tasks.registering {
    val aar = projectDir.resolve("libs/sudoku.aar")
    inputs.file(rootProject.file("scripts/build_sudoku_aar.sh"))
    outputs.file(aar)
    onlyIf { !aar.exists() }
    doLast {
        exec {
            workingDir = rootProject.projectDir
            commandLine("bash", "scripts/build_sudoku_aar.sh")
        }
        check(aar.exists()) { "gomobile AAR not generated; ensure Go + Android SDK/NDK are available." }
    }
}

tasks.named("preBuild").configure {
    dependsOn(ensureSudokuAar)
}
