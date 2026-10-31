import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val abis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

// Release signing: reads keystore credentials from GitHub Actions secrets
// (exposed to Gradle as BYEDPI_KEYSTORE_* env vars) or from a local
// keystore.properties file for offline builds. Falls back to unsigned
// release APKs when no credentials are provided.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun keystoreValue(key: String): String? =
    System.getenv("BYEDPI_KEYSTORE_$key") ?: keystoreProps.getProperty(key.lowercase())

val keystorePath = keystoreValue("PATH")
val hasKeystore = !keystorePath.isNullOrBlank() && File(keystorePath).exists()

android {
    namespace = "io.github.romanvht.byedpi"
    //noinspection GradleDependency
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.romanvht.byedpi"
        minSdk = 21
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = 1820
        versionName = "1.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(abis)
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = keystoreValue("STORE_PASSWORD")
                keyAlias = keystoreValue("KEY_ALIAS")
                keyPassword = keystoreValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }
        debug {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}-debug\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }
}

dependencies {
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"

    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(listOf(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk"
    ))

    println("Command: $commandLine")
}

tasks.preBuild {
    dependsOn("runNdkBuild")
}

// Rename release artifacts to human-readable names, e.g.
// app-arm64-v8a-release.apk -> ByeDpi-1.8.2-arm64-v8a.apk
// (the universal split APK is named -all by AGP)
tasks.configureEach {
    if (name == "packageRelease") {
        doLast {
            val outDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            outDir.listFiles { f -> f.extension == "apk" }?.forEach { apk ->
                val abi = Regex("app-(.+)-release\\.apk").find(apk.name)?.groupValues?.get(1) ?: return@forEach
                val newName = "ByeDpi-${android.defaultConfig.versionName}-$abi.apk"
                if (apk.name != newName) apk.renameTo(File(outDir, newName))
            }
        }
    }
}
