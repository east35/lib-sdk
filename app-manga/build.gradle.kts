plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Phase 3 stub. Mirrors app-ebook structure; concrete config + .cbz page
// extraction routing land here per spec §10.

android {
    namespace = "com.readershell.manga"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readershell.manga"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
