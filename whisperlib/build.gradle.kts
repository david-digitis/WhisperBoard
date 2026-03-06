plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.whispercpp"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        create("nouserlib") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
        }
        create("runTests") {
            initWith(buildTypes.getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
        create("debugNoMinify") {
            initWith(buildTypes.getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    ndkVersion = "28.0.13004108"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
