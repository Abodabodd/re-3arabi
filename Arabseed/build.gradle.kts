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
        freeCompilerArgs = listOf("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}

dependencies {
    // FIXME remove this when crossplatform is fully supported
    val cloudstream by configurations
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
