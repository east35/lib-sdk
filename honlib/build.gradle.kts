plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional switches for a test build, e.g.
//   ./gradlew :honlib:installDebug -PidSuffix=.dev -PcloudUrl=http://127.0.0.1:8765
//
// idSuffix installs the build as a separate app beside the released one. A
// locally signed build cannot update an install signed elsewhere, and removing
// that install to make room would wipe its settings and unsynced progress; a
// suffix leaves it alone. cloudUrl pre-fills the setup screen's server address.
val idSuffix = (findProperty("idSuffix") as String?)?.takeIf { it.isNotBlank() }
val debugCloudUrl = (findProperty("cloudUrl") as String?)?.takeIf { it.isNotBlank() }
    ?: "https://honlib.razerblade.dev"

android {
    namespace = "com.readershell.ebook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readershell.ebook"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        idSuffix?.let { applicationIdSuffix = it }
        // Its own proxy port too, so both apps can be running at once.
        buildConfigField("int", "PROXY_PORT", if (idSuffix != null) "38766" else "38765")
        // Two icons called "HonLib" would be indistinguishable on the launcher.
        manifestPlaceholders["appLabel"] = if (idSuffix != null) "HonLib Test" else "@string/app_name"
        manifestPlaceholders["setupLabel"] = if (idSuffix != null) "HonLib Test Setup" else "@string/setup_name"
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing is supplied from outside the repo so no key material or
    // password is ever committed. Absent those properties the release build is
    // left unsigned, rather than silently falling back to the debug key.
    val releaseStore = (findProperty("honlibStoreFile") as String?)?.let { file(it) }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = findProperty("honlibStorePassword") as String?
                keyAlias = findProperty("honlibKeyAlias") as String? ?: "honlib"
                keyPassword = findProperty("honlibKeyPassword") as String?
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    // Only pre-fills the server field on the setup screen; a device pointed at
    // a different library just edits it once.
    val releaseCloudUrl = (findProperty("honlibCloudUrl") as String?) ?: ""

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_CLOUD_URL", "\"$debugCloudUrl\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_CLOUD_URL", "\"$releaseCloudUrl\"")
            if (releaseStore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
 * Source: HonLib's {static,fonts}. HonLib is either a sibling checkout
 * (../HonLib) or, when this repo is checked out as HonLib's `android/`
 * submodule, the directory above. The web UI is the universal client; bundling
 * it lets the WebView load http://127.0.0.1:PORT/ offline.
 * Repo: https://github.com/east35/HonLib.
 */
val webUiRoot = listOf(file("${rootDir}/../HonLib"), file("${rootDir}/.."))
    .firstOrNull { File(it, "static/index.html").isFile }
    ?: file("${rootDir}/../HonLib")
val webUiSourceStatic = File(webUiRoot, "static")
val webUiSourceFonts  = File(webUiRoot, "fonts")

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
