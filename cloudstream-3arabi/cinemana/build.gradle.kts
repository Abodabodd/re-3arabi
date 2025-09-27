// In the build.gradle.kts INSIDE your Arabseed folder

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.arabseed"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // هذا السطر سيقوم بتحميل مكتبة CloudStream وحل كل مشاكل loadAllLinks
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
}