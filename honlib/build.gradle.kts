plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.readershell.ebook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readershell.ebook"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_CLOUD_URL", "\"https://honlib.razerblade.dev\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_CLOUD_URL", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        getByName("main") {
            // Web UI assets are copied here at build time by :honlib:copyWebAssets.
            assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
        }
    }
}

/**
 * Copy the HonLib web UI into the APK's assets/web/ at build time.
 * Source: ../HonLib/{static,fonts} (sibling of lib-sdk). The web UI is the
 * universal client; bundling it lets the WebView load http://127.0.0.1:PORT/
 * offline. Repo: https://github.com/east35/HonLib.
 */
val webUiSourceStatic = file("${rootDir}/../HonLib/static")
val webUiSourceFonts  = file("${rootDir}/../HonLib/fonts")

val copyWebAssets by tasks.registering(Copy::class) {
    val dest = layout.buildDirectory.dir("generated/webAssets/web")
    into(dest)
    from(webUiSourceStatic) { into(".") }
    from(webUiSourceFonts)  { into("fonts") }
    doFirst {
        require(webUiSourceStatic.isDirectory) {
            "HonLib static dir not found at $webUiSourceStatic — adjust path in honlib/build.gradle.kts"
        }
    }
}

afterEvaluate {
    tasks.matching {
        it.name.startsWith("merge") && it.name.endsWith("Assets")
            || it.name.contains("lint", ignoreCase = true)
    }
        .configureEach { dependsOn(copyWebAssets) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Contract tests run on-device: the router needs a real Context
    // (EncryptedSharedPreferences, SQLite, filesDir), so these are instrumented
    // (androidTest), not local JVM unit tests. MockWebServer stands in for cloud.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
