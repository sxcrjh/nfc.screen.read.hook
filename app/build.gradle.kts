plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.changan.hook.nfc"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.changan.hook.nfc"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation("io.github.lingqiqi5211.ezhooktool:core:1.1.3")
    implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-102:1.1.3")
}
