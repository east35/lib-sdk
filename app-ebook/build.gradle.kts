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
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        getByName("main") {
            // Web UI assets are copied here at build time by :app-ebook:copyWebAssets.
            assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
        }
    }
}

/**
 * Copy the ebook-library web UI into the APK's assets/web/ at build time.
 * Source: ../../ebook-library/{static,fonts}. The web UI is the universal
 * client; bundling it lets the WebView load http://127.0.0.1:PORT/ offline.
 */
val webUiSourceStatic = file("${rootDir}/../ebook-library/static")
val webUiSourceFonts  = file("${rootDir}/../ebook-library/fonts")

val copyWebAssets by tasks.registering(Copy::class) {
    val dest = layout.buildDirectory.dir("generated/webAssets/web")
    into(dest)
    from(webUiSourceStatic) { into(".") }
    from(webUiSourceFonts)  { into("fonts") }
    doFirst {
        require(webUiSourceStatic.isDirectory) {
            "ebook-library static dir not found at $webUiSourceStatic — adjust path in app-ebook/build.gradle.kts"
        }
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn(copyWebAssets) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
