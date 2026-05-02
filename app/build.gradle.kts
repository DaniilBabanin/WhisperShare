import org.gradle.api.JavaVersion

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val useVulkan = project.findProperty("whispershare.vulkan")?.toString()?.toBoolean() ?: false

android {
    namespace = "io.whispershare"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.whispershare"
        minSdk = 31           // Android 12 — covers Pixel 9 easily
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"

        ndk {
            // Pixel 9 is arm64. Drop the others to keep APK small.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DWHISPERSHARE_VULKAN=${if (useVulkan) "ON" else "OFF"}"
                )
                cppFlags += listOf("-std=c++17", "-O3", "-fvisibility=hidden")
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("WHISPERSHARE_KEYSTORE_PATH")
                ?: project.findProperty("WHISPERSHARE_KEYSTORE_PATH") as String?
                ?: "release.keystore"
            storeFile = file(keystorePath)
            storePassword = System.getenv("WHISPERSHARE_STORE_PASSWORD")
                ?: project.findProperty("WHISPERSHARE_STORE_PASSWORD") as String? ?: ""
            keyAlias = System.getenv("WHISPERSHARE_KEY_ALIAS")
                ?: project.findProperty("WHISPERSHARE_KEY_ALIAS") as String? ?: ""
            keyPassword = System.getenv("WHISPERSHARE_KEY_PASSWORD")
                ?: project.findProperty("WHISPERSHARE_KEY_PASSWORD") as String? ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
        // Don't compress the GGML model if we ever ship one in assets
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
